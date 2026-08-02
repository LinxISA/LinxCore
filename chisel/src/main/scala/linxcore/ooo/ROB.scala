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
  val brobPrepared = Input(new BROBPrepared(p))
  val prepared = Output(new OOORobPrepared(p))
  val publishFire = Input(Bool())
  val completion = Flipped(Decoupled(new RobResolveTxn(p)))
  val completionAccepted = Valid(new RobIdentity(p))
  val completionRejected = Valid(new RobIdentity(p))
  val commit = Decoupled(new OOORobCommitPreview(p))
  val commitApply = Input(Bool())
  val release = Flipped(Valid(new OOORobReleaseTxn(p)))
  val releaseReady = Output(Bool())
  val releaseApply = Input(Bool())
  val recoveryPrepare = Flipped(Decoupled(new RecoveryPlan(p)))
  val recoveryPrepared = Valid(new RecoveryPlan(p))
  val recoveryApply = Flipped(Valid(new RecoveryPlan(p)))
  val recoveryAbort = Flipped(Valid(new RecoveryPlan(p)))
  val recoveryCandidate = Input(Vec(2, Valid(new RecoveryCandidateLookup(p))))
  val recoveryCandidateStatus = Output(Vec(2, Valid(new RecoveryCandidateStatus(p))))
  val ridTailSlot = Output(Vec(p.ooo.stidCount,
    UInt(InterfaceWidth.index(p.ooo.robGroupsPerStid).W)))
  val ridTailGeneration = Output(Vec(p.ooo.stidCount,
    UInt(p.ridGenerationWidth.W)))
  val ridHeadSlot = Output(Vec(p.ooo.stidCount,
    UInt(InterfaceWidth.index(p.ooo.robGroupsPerStid).W)))
}

class ROB(val p: CoreParams) extends Module {
  require(p.ooo.robBankCount > 0)
  require(p.ooo.robBankCount <= p.ooo.robGroupsPerStid)
  require(p.ooo.robGroupsPerStid % p.ooo.robBankCount == 0)
  RecoveryAge.requireUnambiguousWindow(p)

  val io = IO(new ROBIO(p))

  private val stidWidth = InterfaceWidth.index(p.ooo.stidCount)
  private val slotWidth = InterfaceWidth.index(p.ooo.robGroupsPerStid)
  private val memberWidth = InterfaceWidth.index(p.ooo.maxInstructionsPerRobGroup)
  private val retireWidth = p.widths.retireWidth
  private val d3Width = p.ooo.d3PrefixWidth
  private val robRowsPerBank = p.ooo.robGroupsPerStid / p.ooo.robBankCount

  private def fitIndex(value: UInt, size: Int): UInt = {
    val width = log2Ceil(size)
    if (size == 1) 0.U(0.W) else Cat(0.U(width.W), value)(width - 1, 0)
  }

  private def stidIndex(stid: UInt): UInt =
    fitIndex(stid, p.ooo.stidCount)

  private def robBank(slot: UInt): UInt =
    fitIndex(slot % p.ooo.robBankCount.U, p.ooo.robBankCount)

  private def robRow(slot: UInt): UInt =
    fitIndex(slot / p.ooo.robBankCount.U, robRowsPerBank)

  private def safeStid(stid: UInt): UInt =
    if (p.ooo.stidCount == 1) 0.U(stidWidth.W) else stid

  private def slotPlus(slot: UInt, add: UInt): (UInt, Bool) = {
    val sum = slot +& add
    val wrap = sum >= p.ooo.robGroupsPerStid.U
    (sum(slotWidth - 1, 0), wrap)
  }

  val state = RegInit(VecInit(Seq.fill(p.ooo.stidCount) {
    VecInit(Seq.fill(p.ooo.robBankCount) {
      VecInit(Seq.fill(robRowsPerBank) {
        VecInit(Seq.fill(p.ooo.maxInstructionsPerRobGroup) { ROBState.Free })
      })
    })
  }))
  val memberLive = RegInit(VecInit(Seq.fill(p.ooo.stidCount) {
    VecInit(Seq.fill(p.ooo.robBankCount) {
      VecInit(Seq.fill(robRowsPerBank) {
        VecInit(Seq.fill(p.ooo.maxInstructionsPerRobGroup) { false.B })
      })
    })
  }))
  val identities = Reg(Vec(p.ooo.stidCount,
    Vec(p.ooo.robBankCount,
      Vec(robRowsPerBank,
        Vec(p.ooo.maxInstructionsPerRobGroup, new RobIdentity(p))))))
  val commits = Reg(Vec(p.ooo.stidCount,
    Vec(p.ooo.robBankCount,
      Vec(robRowsPerBank,
        Vec(p.ooo.maxInstructionsPerRobGroup, new CommitEntry(p))))))
  val renames = Reg(Vec(p.ooo.stidCount,
    Vec(p.ooo.robBankCount,
      Vec(robRowsPerBank,
        Vec(p.ooo.maxInstructionsPerRobGroup, new RenameCommitReleaseEntry(p))))))

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
  val residentGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount) {
    VecInit(Seq.fill(p.ooo.robBankCount) {
      VecInit(Seq.fill(robRowsPerBank) {
        VecInit(Seq.fill(p.ooo.maxInstructionsPerRobGroup) {
          0.U(p.residentGenerationWidth.W)
        })
      })
    })
  }))

  private def stateAt(stid: UInt, slot: UInt, member: UInt) =
    state(stidIndex(stid))(robBank(slot))(robRow(slot))(member)
  private def memberLiveAt(stid: UInt, slot: UInt, member: UInt) =
    memberLive(stidIndex(stid))(robBank(slot))(robRow(slot))(member)
  private def identityAt(stid: UInt, slot: UInt, member: UInt) =
    identities(stidIndex(stid))(robBank(slot))(robRow(slot))(member)
  private def commitAt(stid: UInt, slot: UInt, member: UInt) =
    commits(stidIndex(stid))(robBank(slot))(robRow(slot))(member)
  private def renameAt(stid: UInt, slot: UInt, member: UInt) =
    renames(stidIndex(stid))(robBank(slot))(robRow(slot))(member)
  private def residentGenerationAt(stid: UInt, slot: UInt, member: UInt) =
    residentGeneration(stidIndex(stid))(robBank(slot))(robRow(slot))(member)
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
  val orderAge = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    VecInit(Seq.fill(orderCapacity)(0.U(p.transactionIdWidth.W))))))
  val nextAllocationAge = RegInit(0.U(p.transactionIdWidth.W))
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
  io.ridHeadSlot := headSlot

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
  val prepareGroupStarts = Wire(Vec(d3Width, Bool()))
  val prepareGroupOrdinal = Wire(Vec(d3Width,
    UInt(PrefixPacketContract.countWidth(d3Width).W)))
  for (lane <- 0 until d3Width) {
    val active = lane.U < io.prepare.bits.count
    val row = io.prepare.bits.entries(lane)
    prepareGroupStarts(lane) := active && row.uop.decoded.rob.memberIndex === 0.U
    prepareGroupOrdinal(lane) := PopCount(prepareGroupStarts.take(lane + 1)) - 1.U
  }
  val prepareExactRows = (0 until d3Width).map { lane =>
    val active = lane.U < io.prepare.bits.count
    val row = io.prepare.bits.entries(lane)
    val ordinal = prepareGroupOrdinal(lane)
    val (expectedSlot, expectedWrap) = slotPlus(tailSlot(prepareStid), ordinal)
    val expectedGen = tailGeneration(prepareStid) + expectedWrap.asUInt
    val expectedResidentGeneration = residentGenerationAt(
      prepareStid, expectedSlot, row.uop.decoded.rob.memberIndex)
    val firstLane = lane == 0
    val prev = if (firstLane) row else io.prepare.bits.entries(lane - 1)
    val continuousMember = if (firstLane) {
      row.uop.decoded.rob.memberIndex === 0.U
    } else {
      Mux(row.uop.decoded.rob.memberIndex === 0.U,
        row.uop.decoded.rob.ridSlot =/= prev.uop.decoded.rob.ridSlot,
        row.uop.decoded.rob.ridSlot === prev.uop.decoded.rob.ridSlot &&
          row.uop.decoded.rob.memberIndex ===
            prev.uop.decoded.rob.memberIndex + 1.U)
    }
    !active || (
      row.uop.decoded.rob.ridSlot === expectedSlot &&
        row.uop.decoded.rob.ridGeneration === expectedGen &&
        row.uop.decoded.rob.memberIndex < p.ooo.maxInstructionsPerRobGroup.U &&
        (!row.residentBound ||
          row.uop.decoded.rob.residentGeneration === expectedResidentGeneration) &&
        continuousMember &&
        !memberLiveAt(prepareStid, expectedSlot, row.uop.decoded.rob.memberIndex))
  }.reduce(_ && _)
  val brobExact = (0 until d3Width).map { lane =>
    val active = lane.U < io.prepare.bits.count
    val row = io.prepare.bits.entries(lane)
    val binding = io.brobPrepared.entries(lane)
    val rawBindingExact = !row.brobBound || (
      row.uop.decoded.rob.bid === binding.bid &&
        row.uop.decoded.rob.brobGeneration === binding.brobGeneration)
    val previousBindingExact = if (lane == 0) {
      true.B
    } else {
      val previousActive = (lane - 1).U < io.prepare.bits.count
      val previous = io.brobPrepared.entries(lane - 1)
      !previousActive || row.blockStart || (
        binding.bid === previous.bid &&
          binding.brobGeneration === previous.brobGeneration)
    }
    Mux(active,
      io.brobPrepared.count === io.prepare.bits.count &&
        binding.valid &&
        binding.stid === prepareStid &&
        binding.allocated === row.blockStart &&
        rawBindingExact && previousBindingExact,
      !binding.valid)
  }.reduce(_ && _)
  val prepareReady = prepareCountLegal && prepareGroupCountLegal &&
    prepareCapacity && prepareShape && prepareExactRows &&
    PopCount(prepareGroupStarts) === io.prepare.bits.groupCount &&
    brobExact
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
    when(active) {
      prepared.entries(lane).rob.bid := io.brobPrepared.entries(lane).bid
      prepared.entries(lane).rob.brobGeneration :=
        io.brobPrepared.entries(lane).brobGeneration
    }
    prepared.entries(lane).rob.residentGeneration :=
      residentGenerationAt(prepareStid, rid, member)
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
        identityAt(stid, slot, member) := id
        commitAt(stid, slot, member) := prepared.entries(lane).commit
        renameAt(stid, slot, member) := prepared.entries(lane).rename
        memberLiveAt(stid, slot, member) := true.B
        stateAt(stid, slot, member) := Mux(
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
        orderAge(stid)(orderIndex) := nextAllocationAge + lane.U
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
    nextAllocationAge := nextAllocationAge + io.prepare.bits.count
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
      stateAt(compStid, comp.ridSlot, comp.memberIndex) := ROBState.Completed
      commitAt(compStid, comp.ridSlot, comp.memberIndex).resultValid :=
        io.completion.bits.destinationValid
      commitAt(compStid, comp.ridSlot, comp.memberIndex).result :=
        io.completion.bits.value
      commitAt(compStid, comp.ridSlot, comp.memberIndex).trap :=
        io.completion.bits.trap
    }
  }

  val preview = Wire(new OOORobCommitPreview(p))
  preview := 0.U.asTypeOf(preview)
  val stidPrefixCounts = Wire(Vec(p.ooo.stidCount,
    UInt(PrefixPacketContract.countWidth(retireWidth).W)))
  val stidHeadValid = Wire(Vec(p.ooo.stidCount, Bool()))
  val stidHeadTrap = Wire(Vec(p.ooo.stidCount, Bool()))
  for (stid <- 0 until p.ooo.stidCount) {
    val headIdx = orderCommitHead(stid)
    stidHeadValid(stid) := orderCommitCount(stid) =/= 0.U &&
      orderValid(stid)(headIdx) && orderCompleted(stid)(headIdx) &&
      !orderRetired(stid)(headIdx)
    stidHeadTrap(stid) := stidHeadValid(stid) &&
      orderCommits(stid)(headIdx).trap.valid
    val counts = Wire(Vec(retireWidth + 1,
      UInt(PrefixPacketContract.countWidth(retireWidth).W)))
    counts(0) := 0.U
    for (lane <- 0 until retireWidth) {
      val idx = orderPlus(orderCommitHead(stid), lane.U)
      val completed = lane.U < orderCommitCount(stid) &&
        orderValid(stid)(idx) && orderCompleted(stid)(idx) &&
        !orderRetired(stid)(idx) &&
        !orderCommits(stid)(idx).trap.valid
      counts(lane + 1) := Mux(counts(lane) === lane.U && completed,
        (lane + 1).U,
        counts(lane))
    }
    stidPrefixCounts(stid) := counts(retireWidth)
  }
  val eligibleStids = VecInit((0 until p.ooo.stidCount).map { stid =>
    stidPrefixCounts(stid) =/= 0.U || stidHeadTrap(stid)
  })
  val selectedStid = PriorityEncoder(eligibleStids.asUInt)
  for (lane <- 0 until retireWidth) {
    val idx = orderPlus(orderCommitHead(selectedStid), lane.U)
    preview.entries(lane).valid := lane.U < stidPrefixCounts(selectedStid)
    preview.entries(lane).commit := orderCommits(selectedStid)(idx)
    preview.entries(lane).rename := orderRenames(selectedStid)(idx)
  }
  preview.count := stidPrefixCounts(selectedStid)
  preview.headValid := stidHeadValid(selectedStid)
  preview.head := orderIds(selectedStid)(orderCommitHead(selectedStid))
  preview.headTrap := orderCommits(selectedStid)(orderCommitHead(selectedStid)).trap
  val previewValid = preview.count =/= 0.U
  val previewTxnValid = previewValid || preview.headTrap.valid
  val retainedValid = RegInit(false.B)
  val retained = Reg(new OOORobCommitPreview(p))
  val useRetained = retainedValid
  io.commit.valid := useRetained || previewTxnValid
  io.commit.bits := Mux(useRetained, retained, preview)
  val commitApplyFire = io.commit.valid && io.commitApply
  when(io.commit.valid && !io.commit.ready && !retainedValid) {
    retained := preview
    retainedValid := true.B
  }.elsewhen(commitApplyFire && retainedValid) {
    retainedValid := false.B
  }
  when(commitApplyFire) {
    val applyCount = Mux(io.release.valid, io.release.bits.count,
      io.commit.bits.count)
    for (lane <- 0 until retireWidth) {
      when(lane.U < applyCount) {
        val id = Mux(io.release.valid, io.release.bits.lanes(lane).rob,
          io.commit.bits.entries(lane).commit.rob)
        val stid = safeStid(id.stid)
        stateAt(stid, id.ridSlot, id.memberIndex) := ROBState.Retired
        for (idx <- 0 until orderCapacity) {
          when(orderValid(stid)(idx) && orderIds(stid)(idx).asUInt === id.asUInt) {
            orderRetired(stid)(idx) := true.B
          }
        }
      }
    }
    val firstId = Mux(io.release.valid, io.release.bits.lanes(0).rob,
      io.commit.bits.entries(0).commit.rob)
    val stid = safeStid(firstId.stid)
    val (nextRetire, wrap) = slotPlus(retireSlot(stid),
      applyCount)
    retireSlot(stid) := nextRetire
    retireGeneration(stid) := retireGeneration(stid) + wrap.asUInt
    orderCommitHead(stid) := orderPlus(orderCommitHead(stid),
      applyCount)
    orderCommitCount(stid) := orderCommitCount(stid) - applyCount
    when(io.release.valid) {
      assert(io.releaseApply && io.releaseReady,
        "ROB commit must apply the authoritative retained release transaction")
      assert(io.release.bits.count <= io.commit.bits.count,
        "ROB authoritative apply count cannot exceed the live preview")
      for (lane <- 0 until retireWidth) {
        when(lane.U < io.release.bits.count) {
          assert(io.release.bits.lanes(lane).valid)
          assert(io.release.bits.lanes(lane).rob.asUInt ===
            io.commit.bits.entries(lane).commit.rob.asUInt,
            "ROB authoritative apply identity must match the live preview prefix")
        }
      }
    }
  }

  val releasePrefixShape = (0 until retireWidth).map { lane =>
    io.release.bits.lanes(lane).valid === (lane.U < io.release.bits.count)
  }.reduce(_ && _)
  val releaseFirst = io.release.bits.lanes(0).rob
  val releaseStid = safeStid(releaseFirst.stid)
  val releaseLaneExact = Wire(Vec(retireWidth, Bool()))
  val releaseGroupFinished = Wire(Vec(retireWidth, Bool()))
  for (lane <- 0 until retireWidth) {
    val active = lane.U < io.release.bits.count
    val id = io.release.bits.lanes(lane).rob
    val expectedIdx = orderPlus(orderHead(releaseStid), lane.U)
    releaseLaneExact(lane) := !active || (
      io.release.bits.lanes(lane).valid &&
        id.stid === releaseFirst.stid &&
        orderValid(releaseStid)(expectedIdx) &&
        orderCompleted(releaseStid)(expectedIdx) &&
        orderIds(releaseStid)(expectedIdx).asUInt === id.asUInt &&
        memberLiveAt(releaseStid, id.ridSlot, id.memberIndex) &&
        identityAt(releaseStid, id.ridSlot, id.memberIndex).asUInt === id.asUInt)
    val youngerLiveSameGroup = (0 until p.ooo.maxInstructionsPerRobGroup).map { member =>
      member.U > id.memberIndex &&
        memberLiveAt(releaseStid, id.ridSlot, member.U)
    }.reduce(_ || _)
    releaseGroupFinished(lane) := active && releaseLaneExact(lane) &&
      !youngerLiveSameGroup
  }
  io.releaseReady := io.release.valid && io.release.bits.count =/= 0.U &&
    io.release.bits.count <= retireWidth.U &&
    releaseFirst.stid < p.ooo.stidCount.U &&
    releasePrefixShape && releaseLaneExact.asUInt.andR
  val releaseFire = io.releaseReady && io.releaseApply
  when(io.release.valid) {
    assert(io.release.bits.count <= retireWidth.U)
  }
  when(releaseFire) {
    for (lane <- 0 until retireWidth) {
      when(lane.U < io.release.bits.count) {
        val id = io.release.bits.lanes(lane).rob
        memberLiveAt(releaseStid, id.ridSlot, id.memberIndex) := false.B
        stateAt(releaseStid, id.ridSlot, id.memberIndex) := ROBState.Free
        for (idx <- 0 until orderCapacity) {
          when(orderValid(releaseStid)(idx) && orderIds(releaseStid)(idx).asUInt === id.asUInt) {
            orderValid(releaseStid)(idx) := false.B
          }
        }
        residentGenerationAt(releaseStid, id.ridSlot, id.memberIndex) :=
          residentGenerationAt(releaseStid, id.ridSlot, id.memberIndex) + 1.U
      }
    }
    val releasedGroups = PopCount(releaseGroupFinished)
    val (nextHead, wrap) = slotPlus(headSlot(releaseStid), releasedGroups)
    headSlot(releaseStid) := nextHead
    headGeneration(releaseStid) := headGeneration(releaseStid) + wrap.asUInt
    groupCount(releaseStid) := groupCount(releaseStid) - releasedGroups
    orderHead(releaseStid) := orderPlus(orderHead(releaseStid), io.release.bits.count)
    orderCount(releaseStid) := orderCount(releaseStid) - io.release.bits.count
  }

  val recoveryPending = RegInit(false.B)
  val recoveryPlan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))
  for (source <- 0 until 2) {
    val lookup = io.recoveryCandidate(source).bits.event
    val lookupStid = safeStid(lookup.trigger.stid)
    val matches = Wire(Vec(orderCapacity, Bool()))
    for (idx <- 0 until orderCapacity) {
      matches(idx) := io.recoveryCandidate(source).valid &&
        lookup.trigger.stid < p.ooo.stidCount.U &&
        orderValid(lookupStid)(idx) &&
        orderIds(lookupStid)(idx).asUInt === lookup.trigger.asUInt &&
        memberLiveAt(lookupStid, lookup.trigger.ridSlot,
          lookup.trigger.memberIndex) &&
        !orderRetired(lookupStid)(idx)
    }
    val hit = matches.asUInt.orR
    val idx = PriorityEncoder(matches.asUInt)
    io.recoveryCandidateStatus(source).valid := io.recoveryCandidate(source).valid
    io.recoveryCandidateStatus(source).bits := 0.U.asTypeOf(
      io.recoveryCandidateStatus(source).bits)
    io.recoveryCandidateStatus(source).bits.transactionId := lookup.transactionId
    io.recoveryCandidateStatus(source).bits.trigger := lookup.trigger
    io.recoveryCandidateStatus(source).bits.eligible := hit
    io.recoveryCandidateStatus(source).bits.rejected :=
      io.recoveryCandidate(source).valid && !hit
    io.recoveryCandidateStatus(source).bits.ageToken := orderAge(lookupStid)(idx)
    io.recoveryCandidateStatus(source).bits.headTrap := hit &&
      idx === orderCommitHead(lookupStid) &&
      orderCompleted(lookupStid)(idx) &&
      !orderRetired(lookupStid)(idx) &&
      orderCommits(lookupStid)(idx).trap.valid
  }
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
  val killedMembers = orderCount(recStid) - firstOffset
  val killedGroupStarts = Wire(Vec(orderCapacity, Bool()))
  for (off <- 0 until orderCapacity) {
    val active = recHit && off.U >= firstOffset && off.U < orderCount(recStid)
    val idx = orderPlus(orderHead(recStid), off.U)
    val prevOff = Mux(off.U === 0.U, 0.U, off.U - 1.U)
    val prevIdx = orderPlus(orderHead(recStid), prevOff)
    val startsSuffix = off.U === firstOffset
    val startsNewGroup =
      orderIds(recStid)(idx).ridGeneration =/=
        orderIds(recStid)(prevIdx).ridGeneration ||
        orderIds(recStid)(idx).ridSlot =/=
          orderIds(recStid)(prevIdx).ridSlot
    killedGroupStarts(off) := active && (startsSuffix || startsNewGroup)
  }
  preparedPlan.killedGroupCount := PopCount(killedGroupStarts)
  preparedPlan.killedMemberCount := killedMembers
  preparedPlan.survivingTailValid := recHit && firstOffset =/= 0.U
  val survivingTailOffset = Mux(firstOffset === 0.U, 0.U, firstOffset - 1.U)
  val survivingTailIdx = orderPlus(orderHead(recStid), survivingTailOffset)
  preparedPlan.survivingTail := orderIds(recStid)(survivingTailIdx)

  io.recoveryPrepared.valid := recoveryPending || io.recoveryPrepare.ready
  io.recoveryPrepared.bits := Mux(recoveryPending, recoveryPlan, preparedPlan)
  when(io.recoveryPrepare.fire) {
    recoveryPending := true.B
    recoveryPlan := preparedPlan
  }
  val recoveryApplyHit = recoveryPending && io.recoveryApply.valid &&
    io.recoveryApply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recoveryApply.bits, recoveryPlan)
  val recoveryAbortHit = recoveryPending && io.recoveryAbort.valid &&
    io.recoveryAbort.bits.phase === RecoveryPhase.Abort &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recoveryAbort.bits, recoveryPlan)

  when(recoveryApplyHit && !recoveryAbortHit) {
    val stid = safeStid(recoveryPlan.trigger.stid)
    retainedValid := false.B
    for (slot <- 0 until p.ooo.robGroupsPerStid) {
      for (member <- 0 until p.ooo.maxInstructionsPerRobGroup) {
        val id = identityAt(stid, slot.U, member.U)
        when(RecoveryPlanContract.suffixMember(recoveryPlan, id) &&
          memberLiveAt(stid, slot.U, member.U)) {
          memberLiveAt(stid, slot.U, member.U) := false.B
          stateAt(stid, slot.U, member.U) := ROBState.Free
          residentGenerationAt(stid, slot.U, member.U) :=
            residentGenerationAt(stid, slot.U, member.U) + 1.U
        }
      }
    }
    when(recoveryPlan.firstKilledValid) {
      for (off <- 0 until orderCapacity) {
        val idx = orderPlus(orderHead(stid), off.U)
        when(off.U < orderCount(stid) && orderValid(stid)(idx) &&
          RecoveryPlanContract.suffixMember(recoveryPlan, orderIds(stid)(idx))) {
          orderValid(stid)(idx) := false.B
          orderCompleted(stid)(idx) := false.B
          orderRetired(stid)(idx) := false.B
        }
      }
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
      val killedCommitEntries = Wire(Vec(orderCapacity, Bool()))
      for (off <- 0 until orderCapacity) {
        val idx = orderPlus(orderCommitHead(stid), off.U)
        killedCommitEntries(off) := off.U < orderCommitCount(stid) &&
          orderValid(stid)(idx) &&
          RecoveryPlanContract.suffixMember(recoveryPlan, orderIds(stid)(idx))
      }
      val killedCommitCount = PopCount(killedCommitEntries)
      val commitHeadKilled = orderCommitCount(stid) =/= 0.U &&
        RecoveryPlanContract.suffixMember(
          recoveryPlan, orderIds(stid)(orderCommitHead(stid)))
      when(commitHeadKilled) {
        orderCommitHead(stid) := orderPlus(orderTail(stid),
          0.U - recoveryPlan.killedMemberCount)
      }
      orderCommitCount(stid) := orderCommitCount(stid) - killedCommitCount
    }
    recoveryPending := false.B
  }
  when(recoveryAbortHit) {
    recoveryPending := false.B
  }

  when(io.prepare.valid) {
    assert(io.prepare.bits.count <= d3Width.U)
    assert(io.prepare.bits.groupCount <= io.prepare.bits.count)
  }
  when(io.commitApply) {
    assert(io.commit.valid)
  }
  assert(!(completionAcceptedReg && completionRejectedReg))
}
