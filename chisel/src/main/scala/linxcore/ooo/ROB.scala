package linxcore.ooo

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams
import linxcore.top.interface._

object ROBState extends ChiselEnum {
  val Free, Live, Completed, Retired = Value
}

class ROBIO(val p: CoreParams) extends Bundle {
  val prepare = Flipped(Decoupled(new D3RenameGroup(p)))
  val prepared = Output(new OOORobPrepared(p))
  val publishFire = Input(Bool())
  val completion = Flipped(Decoupled(new CompletionTxn(p)))
  val completionAccepted = Valid(new RobIdentity(p))
  val completionRejected = Valid(new RobIdentity(p))
  val commit = Decoupled(new OOORobCommitPreview(p))
  val release = Flipped(Valid(new OOORobReleaseTxn(p)))
  val recoveryPrepare = Flipped(Decoupled(new RecoveryPlan(p)))
  val recoveryPrepared = Valid(new RecoveryPlan(p))
  val recoveryApply = Flipped(Valid(new RecoveryPlan(p)))
  val ridTailSlot = Output(Vec(p.ooo.stidCount,
    UInt(InterfaceWidth.index(p.ooo.robGroupsPerStid).W)))
  val ridTailGeneration = Output(Vec(p.ooo.stidCount,
    UInt(p.ridGenerationWidth.W)))
}

class ROB(val p: CoreParams) extends Module {
  require(p.ooo.robBankCount > 0)
  require(p.ooo.robBankCount <= p.ooo.robGroupsPerStid)

  val io = IO(new ROBIO(p))

  private val stidWidth = InterfaceWidth.index(p.ooo.stidCount)
  private val slotWidth = InterfaceWidth.index(p.ooo.robGroupsPerStid)
  private val memberWidth = InterfaceWidth.index(p.ooo.maxInstructionsPerRobGroup)
  private val retireWidth = p.widths.retireWidth
  private val d3Width = p.ooo.d3PrefixWidth

  private def safeStid(stid: UInt): UInt =
    if (p.ooo.stidCount == 1) 0.U(stidWidth.W) else stid

  private def slotPlus(slot: UInt, add: UInt): (UInt, Bool) = {
    val sum = slot +& add
    val wrap = sum >= p.ooo.robGroupsPerStid.U
    (sum(slotWidth - 1, 0), wrap)
  }

  val state = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    VecInit(Seq.fill(p.ooo.robGroupsPerStid)(
      VecInit(Seq.fill(p.ooo.maxInstructionsPerRobGroup)(ROBState.Free)))))))
  val memberLive = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    VecInit(Seq.fill(p.ooo.robGroupsPerStid)(
      VecInit(Seq.fill(p.ooo.maxInstructionsPerRobGroup)(false.B)))))))
  val identities = Reg(Vec(p.ooo.stidCount,
    Vec(p.ooo.robGroupsPerStid,
      Vec(p.ooo.maxInstructionsPerRobGroup, new RobIdentity(p)))))
  val commits = Reg(Vec(p.ooo.stidCount,
    Vec(p.ooo.robGroupsPerStid,
      Vec(p.ooo.maxInstructionsPerRobGroup, new CommitEntry(p)))))
  val renames = Reg(Vec(p.ooo.stidCount,
    Vec(p.ooo.robGroupsPerStid,
      Vec(p.ooo.maxInstructionsPerRobGroup, new RenameCommitReleaseEntry(p)))))

  val headSlot = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(slotWidth.W))))
  val headGeneration =
    RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(p.ridGenerationWidth.W))))
  val tailSlot = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(slotWidth.W))))
  val tailGeneration =
    RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(p.ridGenerationWidth.W))))
  val retireSlot = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(slotWidth.W))))
  val retireGeneration =
    RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(p.ridGenerationWidth.W))))
  val groupCount = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(PrefixPacketContract.countWidth(p.ooo.robGroupsPerStid).W))))
  val residentGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    VecInit(Seq.fill(p.ooo.robGroupsPerStid)(
      VecInit(Seq.fill(p.ooo.maxInstructionsPerRobGroup)(
        0.U(p.residentGenerationWidth.W))))))))
  private val orderCapacity = p.ooo.robCapacityPerStid
  private val orderPtrWidth = InterfaceWidth.index(orderCapacity)
  val orderIds = Reg(Vec(p.ooo.stidCount,
    Vec(orderCapacity, new RobIdentity(p))))
  val orderCommits = Reg(Vec(p.ooo.stidCount,
    Vec(orderCapacity, new CommitEntry(p))))
  val orderRenames = Reg(Vec(p.ooo.stidCount,
    Vec(orderCapacity, new RenameCommitReleaseEntry(p))))
  val orderValid = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    VecInit(Seq.fill(orderCapacity)(false.B)))))
  val orderCompleted = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    VecInit(Seq.fill(orderCapacity)(false.B)))))
  val orderRetired = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    VecInit(Seq.fill(orderCapacity)(false.B)))))
  val orderHead = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(orderPtrWidth.W))))
  val orderCommitHead =
    RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(orderPtrWidth.W))))
  val orderTail = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(orderPtrWidth.W))))
  val orderCount = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(PrefixPacketContract.countWidth(orderCapacity).W))))
  val orderCommitCount = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(PrefixPacketContract.countWidth(orderCapacity).W))))

  private def orderPlus(ptr: UInt, add: UInt): UInt = {
    val sum = ptr +& add
    Mux(sum >= orderCapacity.U, sum - orderCapacity.U, sum)(orderPtrWidth - 1, 0)
  }

  io.ridTailSlot := tailSlot
  io.ridTailGeneration := tailGeneration

  val prepareStid = safeStid(io.prepare.bits.entries(0).uop.decoded.rob.stid)
  val prepareCountLegal =
    io.prepare.bits.count > 0.U && io.prepare.bits.count <= d3Width.U
  val prepareGroupCountLegal =
    io.prepare.bits.groupCount > 0.U &&
      io.prepare.bits.groupCount <= io.prepare.bits.count
  val prepareCapacity =
    groupCount(prepareStid) + io.prepare.bits.groupCount <=
      p.ooo.robGroupsPerStid.U
  val prepareShape = (0 until d3Width).map { lane =>
    val active = lane.U < io.prepare.bits.count
    val row = io.prepare.bits.entries(lane)
    row.uop.decoded.valid === active &&
      (!active || row.uop.decoded.rob.stid === prepareStid)
  }.reduce(_ && _)
  val prepareReady = prepareCountLegal && prepareGroupCountLegal &&
    prepareCapacity
  io.prepare.ready := prepareReady

  val prepared = Wire(new OOORobPrepared(p))
  prepared := 0.U.asTypeOf(prepared)
  prepared.count := io.prepare.bits.count
  for (lane <- 0 until d3Width) {
    val active = lane.U < io.prepare.bits.count
    val in = io.prepare.bits.entries(lane)
    val rid = in.uop.decoded.rob.ridSlot
    val member = in.uop.decoded.rob.memberIndex
    prepared.entries(lane).valid := active
    prepared.entries(lane).rob := in.uop.decoded.rob
    prepared.entries(lane).rob.residentGeneration :=
      residentGeneration(prepareStid)(rid)(member)
    prepared.entries(lane).commit.instruction :=
      in.uop.decoded.instruction.parent.identity
    prepared.entries(lane).commit.rob := prepared.entries(lane).rob
    prepared.entries(lane).commit.instructionBits :=
      in.uop.decoded.instruction.parent.instruction
    prepared.entries(lane).commit.instructionLengthBytes :=
      in.uop.decoded.instruction.parent.lengthBytes
    prepared.entries(lane).commit.opcode := in.uop.decoded.opcode
    prepared.entries(lane).commit.destination := in.uop.destinations(0)
    prepared.entries(lane).commit.trap.valid := in.trap.valid
    prepared.entries(lane).commit.trap.kind := TrapKind.Exception
    prepared.entries(lane).commit.trap.cause := in.trap.cause
    prepared.entries(lane).commit.trap.rob := prepared.entries(lane).rob
    prepared.entries(lane).commit.trap.instruction :=
      in.uop.decoded.instruction.parent.identity
    prepared.entries(lane).rename.valid := active
    prepared.entries(lane).rename.rob := prepared.entries(lane).rob
    prepared.entries(lane).rename.blockLast := in.uop.decoded.blockBoundary
    prepared.entries(lane).rename.history := in.history
  }
  io.prepared := prepared

  when(io.prepare.valid && io.publishFire && prepareReady) {
    for (lane <- 0 until d3Width) {
      when(lane.U < io.prepare.bits.count) {
        val id = prepared.entries(lane).rob
        val stid = safeStid(id.stid)
        val slot = id.ridSlot
        val member = id.memberIndex
        identities(stid)(slot)(member) := id
        commits(stid)(slot)(member) := prepared.entries(lane).commit
        renames(stid)(slot)(member) := prepared.entries(lane).rename
        memberLive(stid)(slot)(member) := true.B
        state(stid)(slot)(member) := Mux(
          io.prepare.bits.entries(lane).earlyRobComplete,
          ROBState.Completed,
          ROBState.Live)
        val orderIndex = orderPlus(orderTail(stid), lane.U)
        orderIds(stid)(orderIndex) := id
        orderCommits(stid)(orderIndex) := prepared.entries(lane).commit
        orderRenames(stid)(orderIndex) := prepared.entries(lane).rename
        orderValid(stid)(orderIndex) := true.B
        orderCompleted(stid)(orderIndex) :=
          io.prepare.bits.entries(lane).earlyRobComplete
        orderRetired(stid)(orderIndex) := false.B
      }
    }
    val (nextTail, wrap) = slotPlus(tailSlot(prepareStid),
      io.prepare.bits.groupCount)
    tailSlot(prepareStid) := nextTail
    tailGeneration(prepareStid) := tailGeneration(prepareStid) + wrap.asUInt
    groupCount(prepareStid) := groupCount(prepareStid) +
      io.prepare.bits.groupCount
    orderTail(prepareStid) :=
      orderPlus(orderTail(prepareStid), io.prepare.bits.count)
    orderCount(prepareStid) := orderCount(prepareStid) + io.prepare.bits.count
    orderCommitCount(prepareStid) :=
      orderCommitCount(prepareStid) + io.prepare.bits.count
  }

  val comp = io.completion.bits.rob
  val compStid = safeStid(comp.stid)
  val compMatches = Wire(Vec(orderCapacity, Bool()))
  for (idx <- 0 until orderCapacity) {
    compMatches(idx) := comp.stid < p.ooo.stidCount.U &&
      orderValid(compStid)(idx) &&
      orderIds(compStid)(idx).asUInt === comp.asUInt &&
      !orderCompleted(compStid)(idx)
  }
  val compHit = compMatches.asUInt.orR
  val compIndex = PriorityEncoder(compMatches.asUInt)
  io.completion.ready := true.B
  val completionAcceptedReg = RegInit(false.B)
  val completionRejectedReg = RegInit(false.B)
  val completionReportedRob = Reg(new RobIdentity(p))
  io.completionAccepted.valid := completionAcceptedReg
  io.completionAccepted.bits := completionReportedRob
  io.completionRejected.valid := completionRejectedReg
  io.completionRejected.bits := completionReportedRob
  completionAcceptedReg := false.B
  completionRejectedReg := false.B
  when(io.completion.fire) {
    completionReportedRob := comp
    completionAcceptedReg := compHit
    completionRejectedReg := !compHit
    when(compHit) {
      orderCompleted(compStid)(compIndex) := true.B
      state(compStid)(comp.ridSlot)(comp.memberIndex) := ROBState.Completed
      commits(compStid)(comp.ridSlot)(comp.memberIndex).resultValid :=
        io.completion.bits.destinationValid
      commits(compStid)(comp.ridSlot)(comp.memberIndex).result :=
        io.completion.bits.value
      commits(compStid)(comp.ridSlot)(comp.memberIndex).trap :=
        io.completion.bits.trap
    }
  }

  val preview = Wire(new OOORobCommitPreview(p))
  preview := 0.U.asTypeOf(preview)
  val stidPrefixCounts = Wire(Vec(p.ooo.stidCount,
    UInt(PrefixPacketContract.countWidth(retireWidth).W)))
  for (stid <- 0 until p.ooo.stidCount) {
    val counts = Wire(Vec(retireWidth + 1,
      UInt(PrefixPacketContract.countWidth(retireWidth).W)))
    counts(0) := 0.U
    for (lane <- 0 until retireWidth) {
      val idx = orderPlus(orderCommitHead(stid), lane.U)
      val completed = lane.U < orderCommitCount(stid) &&
        orderValid(stid)(idx) && orderCompleted(stid)(idx) &&
        !orderRetired(stid)(idx)
      counts(lane + 1) := Mux(counts(lane) === lane.U && completed,
        (lane + 1).U,
        counts(lane))
    }
    stidPrefixCounts(stid) := counts(retireWidth)
  }
  val eligibleStids = VecInit((0 until p.ooo.stidCount).map { stid =>
    stidPrefixCounts(stid) =/= 0.U
  })
  val selectedStid = PriorityEncoder(eligibleStids.asUInt)
  for (lane <- 0 until retireWidth) {
    val idx = orderPlus(orderCommitHead(selectedStid), lane.U)
    preview.entries(lane).valid := lane.U < stidPrefixCounts(selectedStid)
    preview.entries(lane).commit := orderCommits(selectedStid)(idx)
    preview.entries(lane).rename := orderRenames(selectedStid)(idx)
  }
  preview.count := stidPrefixCounts(selectedStid)
  val previewValid = preview.count =/= 0.U
  val retainedValid = RegInit(false.B)
  val retained = Reg(new OOORobCommitPreview(p))
  val useRetained = retainedValid
  io.commit.valid := useRetained || previewValid
  io.commit.bits := Mux(useRetained, retained, preview)
  when(io.commit.valid && !io.commit.ready && !retainedValid) {
    retained := preview
    retainedValid := true.B
  }.elsewhen(io.commit.fire && retainedValid) {
    retainedValid := false.B
  }
  when(io.commit.fire) {
    for (lane <- 0 until retireWidth) {
      when(lane.U < io.commit.bits.count) {
        val id = io.commit.bits.entries(lane).commit.rob
        val stid = safeStid(id.stid)
        state(stid)(id.ridSlot)(id.memberIndex) := ROBState.Retired
        for (idx <- 0 until orderCapacity) {
          when(orderValid(stid)(idx) && orderIds(stid)(idx).asUInt === id.asUInt) {
            orderRetired(stid)(idx) := true.B
          }
        }
      }
    }
    val stid = safeStid(io.commit.bits.entries(0).commit.rob.stid)
    val (nextRetire, wrap) = slotPlus(retireSlot(stid),
      io.commit.bits.count)
    retireSlot(stid) := nextRetire
    retireGeneration(stid) := retireGeneration(stid) + wrap.asUInt
    orderCommitHead(stid) := orderPlus(orderCommitHead(stid),
      io.commit.bits.count)
    orderCommitCount(stid) := orderCommitCount(stid) - io.commit.bits.count
  }

  when(io.release.valid) {
    assert(io.release.bits.count <= retireWidth.U)
    for (lane <- 0 until retireWidth) {
      when(lane.U < io.release.bits.count && io.release.bits.lanes(lane).valid) {
        val id = io.release.bits.lanes(lane).rob
        val stid = safeStid(id.stid)
        when(memberLive(stid)(id.ridSlot)(id.memberIndex) &&
          identities(stid)(id.ridSlot)(id.memberIndex).asUInt === id.asUInt &&
          state(stid)(id.ridSlot)(id.memberIndex) === ROBState.Retired) {
          memberLive(stid)(id.ridSlot)(id.memberIndex) := false.B
          state(stid)(id.ridSlot)(id.memberIndex) := ROBState.Free
          for (idx <- 0 until orderCapacity) {
            when(orderValid(stid)(idx) && orderIds(stid)(idx).asUInt === id.asUInt) {
              orderValid(stid)(idx) := false.B
            }
          }
          residentGeneration(stid)(id.ridSlot)(id.memberIndex) :=
            residentGeneration(stid)(id.ridSlot)(id.memberIndex) + 1.U
        }
      }
    }
    when(io.release.bits.count =/= 0.U) {
      val first = io.release.bits.lanes(0).rob
      val last = Wire(new RobIdentity(p))
      last := io.release.bits.lanes(0).rob
      for (lane <- 0 until retireWidth) {
        when((lane + 1).U === io.release.bits.count) {
          last := io.release.bits.lanes(lane).rob
        }
      }
      val releasedGroups = last.ridSlot - first.ridSlot + 1.U
      val stid = safeStid(first.stid)
      val (nextHead, wrap) = slotPlus(headSlot(stid), releasedGroups)
      headSlot(stid) := nextHead
      headGeneration(stid) := headGeneration(stid) + wrap.asUInt
      groupCount(stid) := groupCount(stid) - releasedGroups
      orderHead(stid) := orderPlus(orderHead(stid), io.release.bits.count)
      orderCount(stid) := orderCount(stid) - io.release.bits.count
    }
  }

  val recoveryPending = RegInit(false.B)
  val recoveryPlan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))
  val recIn = io.recoveryPrepare.bits
  val recStid = safeStid(recIn.trigger.stid)
  val recMatches = Wire(Vec(orderCapacity, Bool()))
  val recOffsets = Wire(Vec(orderCapacity,
    UInt(PrefixPacketContract.countWidth(orderCapacity).W)))
  for (off <- 0 until orderCapacity) {
    val idx = orderPlus(orderHead(recStid), off.U)
    recOffsets(off) := off.U
    recMatches(off) := off.U < orderCount(recStid) &&
      orderValid(recStid)(idx) &&
      orderIds(recStid)(idx).asUInt === recIn.trigger.asUInt
  }
  val recHit = recIn.trigger.stid < p.ooo.stidCount.U && recMatches.asUInt.orR
  val recOffset = PriorityEncoder(recMatches.asUInt)
  io.recoveryPrepare.ready := !recoveryPending && recHit &&
    recIn.phase === RecoveryPhase.Prepare
  val preparedPlan = Wire(new RecoveryPlan(p))
  preparedPlan := recIn
  val branchSkipsTrigger = recIn.cause === RecoveryCause.Branch
  val firstOffset = Mux(branchSkipsTrigger, recOffset + 1.U, recOffset)
  val lastOffset = orderCount(recStid) - 1.U
  val firstIdx = orderPlus(orderHead(recStid), firstOffset)
  val lastIdx = orderPlus(orderHead(recStid), lastOffset)
  preparedPlan.firstKilledValid := recHit && firstOffset < orderCount(recStid)
  preparedPlan.firstKilled := orderIds(recStid)(firstIdx)
  preparedPlan.lastKilled := orderIds(recStid)(lastIdx)
  val killedFromTrigger = groupCount(recStid) - recOffset
  val killedMembers = orderCount(recStid) - firstOffset
  preparedPlan.killedGroupCount := killedMembers
  preparedPlan.killedMemberCount := killedMembers
  preparedPlan.survivingTailValid := recOffset =/= 0.U
  preparedPlan.survivingTail := recIn.trigger
  preparedPlan.survivingTail.ridSlot := recIn.trigger.ridSlot - 1.U

  io.recoveryPrepared.valid := recoveryPending || io.recoveryPrepare.ready
  io.recoveryPrepared.bits := Mux(recoveryPending, recoveryPlan, preparedPlan)
  when(io.recoveryPrepare.fire) {
    recoveryPending := true.B
    recoveryPlan := preparedPlan
  }
  when(io.recoveryApply.valid &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recoveryApply.bits, recoveryPlan)) {
    val stid = safeStid(recoveryPlan.trigger.stid)
    for (slot <- 0 until p.ooo.robGroupsPerStid) {
      for (member <- 0 until p.ooo.maxInstructionsPerRobGroup) {
        val id = identities(stid)(slot)(member)
        when(RecoveryPlanContract.suffixMember(recoveryPlan, id) &&
          memberLive(stid)(slot)(member)) {
          memberLive(stid)(slot)(member) := false.B
          state(stid)(slot)(member) := ROBState.Free
          residentGeneration(stid)(slot)(member) :=
            residentGeneration(stid)(slot)(member) + 1.U
        }
      }
    }
    when(recoveryPlan.firstKilledValid) {
      val (afterPartialSlot, afterPartialWrap) =
        slotPlus(recoveryPlan.firstKilled.ridSlot, 1.U)
      val killsWholeFirstGroup = recoveryPlan.firstKilled.memberIndex === 0.U
      val prunedWholeGroups = Mux(killsWholeFirstGroup,
        recoveryPlan.killedGroupCount,
        Mux(recoveryPlan.killedGroupCount === 0.U,
          0.U,
          recoveryPlan.killedGroupCount - 1.U))
      tailSlot(stid) := Mux(killsWholeFirstGroup,
        recoveryPlan.firstKilled.ridSlot,
        afterPartialSlot)
      tailGeneration(stid) := recoveryPlan.firstKilled.ridGeneration +
        Mux(killsWholeFirstGroup, 0.U, afterPartialWrap.asUInt)
      groupCount(stid) := groupCount(stid) - prunedWholeGroups
      orderTail(stid) := orderPlus(orderTail(stid), 0.U - recoveryPlan.killedMemberCount)
      orderCount(stid) := orderCount(stid) - recoveryPlan.killedMemberCount
    }
    recoveryPending := false.B
  }

  when(io.prepare.valid) {
    assert(io.prepare.bits.count <= d3Width.U)
    assert(io.prepare.bits.groupCount <= io.prepare.bits.count)
  }
  assert(!(completionAcceptedReg && completionRejectedReg))
}
