package linxcore.ooo

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams
import linxcore.top.interface._

class BROBIO(val p: CoreParams) extends Bundle {
  val prepare = Flipped(Decoupled(new D3RenameGroup(p)))
  val robPrepared = Input(new OOORobPrepared(p))
  val prepared = Output(new BROBPrepared(p))
  val publishFire = Input(Bool())
  val release = Flipped(Valid(new BROBReleaseTxn(p)))
  val releaseReady = Output(Bool())
  val releaseApply = Input(Bool())
  val releaseAccepted = Valid(new RobIdentity(p))
  val releaseRejected = Valid(new RobIdentity(p))
  val recoveryPrepare = Flipped(Decoupled(new RecoveryPlan(p)))
  val recoveryPrepared = Valid(new RecoveryPlan(p))
  val recoveryApply = Flipped(Valid(new RecoveryPlan(p)))
  val recoveryAbort = Flipped(Valid(new RecoveryPlan(p)))
  val debugUsed = Output(Vec(p.ooo.stidCount,
    UInt(PrefixPacketContract.countWidth(p.ooo.brobEntriesPerStid).W)))
}

class BROB(val p: CoreParams) extends Module {
  val io = IO(new BROBIO(p))

  private val physicalStidWidth = math.max(1, log2Ceil(p.ooo.stidCount))
  private val physicalBidWidth = math.max(1,
    log2Ceil(p.ooo.brobEntriesPerStid))
  private def safeStid(stid: UInt): UInt =
    if (p.ooo.stidCount == 1) 0.U(physicalStidWidth.W)
    else stid(physicalStidWidth - 1, 0)
  private def bidInRange(bid: UInt): Bool =
    bid < p.ooo.brobEntriesPerStid.U
  private def physicalBid(bid: UInt): UInt =
    bid(physicalBidWidth - 1, 0)
  private def safePhysicalBid(bid: UInt): UInt =
    Mux(bidInRange(bid), physicalBid(bid), 0.U(physicalBidWidth.W))

  val used = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(PrefixPacketContract.countWidth(p.ooo.brobEntriesPerStid).W))))
  io.debugUsed := used
  val tail = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(p.nativeBidWidth.W))))
  val generation = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.brobGenerationWidth.W))))
  val currentValid = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(false.B)))
  val currentBid = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.nativeBidWidth.W))))
  val currentGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.brobGenerationWidth.W))))
  val tableValid = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    VecInit(Seq.fill(p.ooo.brobEntriesPerStid)(false.B)))))
  val tableGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    VecInit(Seq.fill(p.ooo.brobEntriesPerStid)(
      0.U(p.brobGenerationWidth.W))))))
  val tableClosed = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    VecInit(Seq.fill(p.ooo.brobEntriesPerStid)(false.B)))))
  val tableFirstRob = Reg(Vec(p.ooo.stidCount,
    Vec(p.ooo.brobEntriesPerStid, new RobIdentity(p))))
  val tableLastRob = Reg(Vec(p.ooo.stidCount,
    Vec(p.ooo.brobEntriesPerStid, new RobIdentity(p))))
  val head = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(p.nativeBidWidth.W))))
  val headGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.brobGenerationWidth.W))))

  val prepareStidRaw = io.prepare.bits.entries(0).uop.decoded.rob.stid
  val prepareStidInRange = prepareStidRaw < p.ooo.stidCount.U
  val stid = safeStid(prepareStidRaw)
  val prepared = Wire(new BROBPrepared(p))
  prepared := 0.U.asTypeOf(prepared)
  prepared.count := io.prepare.bits.count
  val scanTail = Wire(Vec(p.ooo.d3PrefixWidth + 1, UInt(p.nativeBidWidth.W)))
  val scanGen = Wire(Vec(p.ooo.d3PrefixWidth + 1, UInt(p.brobGenerationWidth.W)))
  val scanCurrentValid = Wire(Vec(p.ooo.d3PrefixWidth + 1, Bool()))
  val scanCurrentBid = Wire(Vec(p.ooo.d3PrefixWidth + 1, UInt(p.nativeBidWidth.W)))
  val scanCurrentGen = Wire(Vec(p.ooo.d3PrefixWidth + 1, UInt(p.brobGenerationWidth.W)))
  val scanCurrentResident = Wire(Vec(p.ooo.d3PrefixWidth + 1, Bool()))
  val allocCount = Wire(Vec(p.ooo.d3PrefixWidth + 1,
    UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W)))
  scanTail(0) := tail(stid)
  scanGen(0) := generation(stid)
  scanCurrentValid(0) := currentValid(stid)
  scanCurrentBid(0) := currentBid(stid)
  scanCurrentGen(0) := currentGeneration(stid)
  scanCurrentResident(0) := currentValid(stid) &&
    tableValid(stid)(physicalBid(currentBid(stid))) &&
    tableGeneration(stid)(physicalBid(currentBid(stid))) ===
      currentGeneration(stid)
  allocCount(0) := 0.U
  for (lane <- 0 until p.ooo.d3PrefixWidth) {
    val active = lane.U < io.prepare.bits.count
    val startsBlock = active && io.prepare.bits.entries(lane).blockStart
    val stopsBlock = active && io.prepare.bits.entries(lane).blockStop
    // A recovered open-current context can outlive retirement of its original
    // BROB slot.  The first redirected continuation re-materializes that
    // context at the current tail so its eventual stop has a live head-owned
    // slot to close and release.
    val reopensBlock = active && !startsBlock && scanCurrentValid(lane) &&
      !scanCurrentResident(lane)
    val allocatesBlock = startsBlock || reopensBlock
    val bid = Mux(allocatesBlock, scanTail(lane), scanCurrentBid(lane))
    val gen = Mux(allocatesBlock, scanGen(lane), scanCurrentGen(lane))
    prepared.entries(lane).valid := active
    prepared.entries(lane).stid := stid
    prepared.entries(lane).bid := bid
    prepared.entries(lane).brobGeneration := gen
    prepared.entries(lane).allocated := allocatesBlock
    val nextTailWide = scanTail(lane) +& allocatesBlock.asUInt
    val wrap = nextTailWide >= p.ooo.brobEntriesPerStid.U
    scanTail(lane + 1) := Mux(wrap, 0.U(p.nativeBidWidth.W),
      physicalBid(nextTailWide))
    scanGen(lane + 1) := scanGen(lane) + wrap.asUInt
    scanCurrentValid(lane + 1) := Mux(active, !stopsBlock, scanCurrentValid(lane))
    scanCurrentBid(lane + 1) := Mux(allocatesBlock, bid, scanCurrentBid(lane))
    scanCurrentGen(lane + 1) := Mux(allocatesBlock, gen, scanCurrentGen(lane))
    scanCurrentResident(lane + 1) := Mux(
      active, scanCurrentResident(lane) || allocatesBlock,
      scanCurrentResident(lane))
    allocCount(lane + 1) := allocCount(lane) + allocatesBlock.asUInt
  }
  io.prepared := prepared
  val publicationExact = io.robPrepared.count === io.prepare.bits.count &&
    (0 until p.ooo.d3PrefixWidth).map { lane =>
      val active = lane.U < io.prepare.bits.count
      val raw = io.prepare.bits.entries(lane).uop.decoded.rob
      val bound = io.robPrepared.entries(lane).rob
      !active || (
        io.robPrepared.entries(lane).valid &&
          bound.peId === raw.peId &&
          bound.stid === raw.stid &&
          bound.ridSlot === raw.ridSlot &&
          bound.ridGeneration === raw.ridGeneration &&
          bound.memberIndex === raw.memberIndex &&
          bound.bid === prepared.entries(lane).bid &&
          bound.brobGeneration === prepared.entries(lane).brobGeneration)
    }.reduce(_ && _)
  val recoveryPending = RegInit(false.B)
  val recoveryPlan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))
  val recoveryKilledMaskReg = RegInit(VecInit(Seq.fill(p.ooo.brobEntriesPerStid)(
    false.B)))
  val recoveryKilledCountReg =
    RegInit(0.U(PrefixPacketContract.countWidth(p.ooo.brobEntriesPerStid).W))
  val recoveryStraddlingBlockReg = RegInit(false.B)
  val recoveryStraddlingBidReg = RegInit(0.U(p.nativeBidWidth.W))
  val recoveryStraddlingGenerationReg = RegInit(0.U(p.brobGenerationWidth.W))
  val recoveryCurrentValidReg = RegInit(false.B)
  val recoveryCurrentBidReg = RegInit(0.U(p.nativeBidWidth.W))
  val recoveryCurrentGenerationReg = RegInit(0.U(p.brobGenerationWidth.W))
  val recoveryUsedReg =
    RegInit(0.U(PrefixPacketContract.countWidth(p.ooo.brobEntriesPerStid).W))
  val recoveryTailReg = RegInit(0.U(p.nativeBidWidth.W))
  val recoveryGenerationReg = RegInit(0.U(p.brobGenerationWidth.W))
  val recoverySurvivingTailReg = Reg(new RobIdentity(p))

  io.prepare.ready := !recoveryPending &&
    prepareStidInRange &&
    io.prepare.bits.count <= p.ooo.d3PrefixWidth.U &&
    used(stid) + allocCount(p.ooo.d3PrefixWidth) <= p.ooo.brobEntriesPerStid.U &&
    publicationExact

  when(io.prepare.valid && io.publishFire && io.prepare.ready) {
    tail(stid) := scanTail(p.ooo.d3PrefixWidth)
    generation(stid) := scanGen(p.ooo.d3PrefixWidth)
    currentValid(stid) := scanCurrentValid(p.ooo.d3PrefixWidth)
    currentBid(stid) := scanCurrentBid(p.ooo.d3PrefixWidth)
    currentGeneration(stid) := scanCurrentGen(p.ooo.d3PrefixWidth)
    used(stid) := used(stid) + allocCount(p.ooo.d3PrefixWidth)
    for (lane <- 0 until p.ooo.d3PrefixWidth) {
      when(prepared.entries(lane).valid && prepared.entries(lane).allocated) {
        val boundRob = io.robPrepared.entries(lane).rob
        val preparedBid = physicalBid(prepared.entries(lane).bid)
        tableValid(stid)(preparedBid) := true.B
        tableGeneration(stid)(preparedBid) :=
          prepared.entries(lane).brobGeneration
        tableClosed(stid)(preparedBid) := false.B
        tableFirstRob(stid)(preparedBid) :=
          boundRob
        tableLastRob(stid)(preparedBid) :=
          boundRob
      }
      val currentBlockInPrefix = scanCurrentValid(lane) &&
        scanCurrentBid(lane) === prepared.entries(lane).bid
      when(prepared.entries(lane).valid &&
        (prepared.entries(lane).allocated ||
          tableValid(stid)(physicalBid(prepared.entries(lane).bid)) ||
          currentBlockInPrefix)) {
        val boundRob = io.robPrepared.entries(lane).rob
        tableLastRob(stid)(physicalBid(prepared.entries(lane).bid)) :=
          boundRob
      }
      when(prepared.entries(lane).valid && io.prepare.bits.entries(lane).blockStop) {
        val boundRob = io.robPrepared.entries(lane).rob
        tableClosed(stid)(physicalBid(prepared.entries(lane).bid)) := true.B
        tableLastRob(stid)(physicalBid(prepared.entries(lane).bid)) :=
          boundRob
      }
    }
  }

  val rel = io.release.bits.entries(0)
  val relStid = safeStid(rel.stid)
  val relPrefixShape = (0 until p.widths.retireWidth).map { lane =>
    val active = lane.U < io.release.bits.count
    !active || (
      io.release.bits.entries(lane).stid === rel.stid &&
        io.release.bits.entries(lane).bid === head(relStid) &&
        io.release.bits.entries(lane).brobGeneration === headGeneration(relStid))
  }.reduce(_ && _)
  val relIncludesLast = (0 until p.widths.retireWidth).map { lane =>
    val active = lane.U < io.release.bits.count
    val id = io.release.bits.entries(lane)
    val last = tableLastRob(relStid)(physicalBid(head(relStid)))
    active &&
      id.stid === last.stid &&
      id.ridSlot === last.ridSlot &&
      id.ridGeneration === last.ridGeneration &&
      id.memberIndex === last.memberIndex &&
      id.bid === last.bid &&
      id.brobGeneration === last.brobGeneration
  }.reduce(_ || _)
  val releaseExact = !recoveryPending &&
    io.release.valid && io.release.bits.count =/= 0.U &&
    rel.stid < p.ooo.stidCount.U &&
    rel.bid === head(relStid) &&
    bidInRange(rel.bid) &&
    rel.brobGeneration === headGeneration(relStid) &&
    tableValid(relStid)(safePhysicalBid(rel.bid)) &&
    tableGeneration(relStid)(safePhysicalBid(rel.bid)) ===
      rel.brobGeneration &&
    tableClosed(relStid)(safePhysicalBid(rel.bid)) &&
    relPrefixShape && relIncludesLast
  io.releaseReady := releaseExact
  val relRob = Wire(new RobIdentity(p))
  relRob := rel
  io.releaseAccepted.valid := releaseExact && io.releaseApply
  io.releaseAccepted.bits := relRob
  io.releaseRejected.valid := io.release.valid && io.release.bits.count =/= 0.U &&
    !releaseExact
  io.releaseRejected.bits := relRob
  when(io.release.valid && io.release.bits.count =/= 0.U) {
    when(releaseExact && io.releaseApply) {
      val relBid = physicalBid(rel.bid)
      tableValid(relStid)(relBid) := false.B
      tableClosed(relStid)(relBid) := false.B
      used(relStid) := used(relStid) - 1.U
      val nextHead = head(relStid) +& 1.U
      val wrap = nextHead >= p.ooo.brobEntriesPerStid.U
      head(relStid) := Mux(wrap, 0.U(p.nativeBidWidth.W),
        physicalBid(nextHead))
      headGeneration(relStid) := headGeneration(relStid) + wrap.asUInt
    }
  }

  val recIn = io.recoveryPrepare.bits
  val recStid = safeStid(recIn.trigger.stid)
  val recSuffixLegal = RecoveryPlanContract.legalSuffixWindow(recIn)
  val recTargetValid = recIn.trigger.stid < p.ooo.stidCount.U
  val recFirstBid = recIn.firstKilled.bid
  val recLastBid = recIn.lastKilled.bid
  val recFirstBidInRange = bidInRange(recFirstBid)
  val recLastBidInRange = bidInRange(recLastBid)
  val recFirstPhysicalBid = safePhysicalBid(recFirstBid)
  val recLastPhysicalBid = safePhysicalBid(recLastBid)
  val recFirstSlotValid = recIn.firstKilledValid &&
    recFirstBidInRange && tableValid(recStid)(recFirstPhysicalBid) &&
    tableGeneration(recStid)(recFirstPhysicalBid) ===
      recIn.firstKilled.brobGeneration
  val recLastSlotValid = recIn.firstKilledValid &&
    recLastBidInRange && tableValid(recStid)(recLastPhysicalBid) &&
    tableGeneration(recStid)(recLastPhysicalBid) ===
      recIn.lastKilled.brobGeneration
  val recFirstSlotFirstKilled = recFirstSlotValid &&
    RecoveryPlanContract.suffixMember(recIn,
      tableFirstRob(recStid)(recFirstPhysicalBid))
  val recFirstSlotStraddles = recFirstSlotValid &&
    !RecoveryPlanContract.suffixMember(recIn,
      tableFirstRob(recStid)(recFirstPhysicalBid)) &&
    RecoveryPlanContract.suffixMember(recIn,
      tableLastRob(recStid)(recFirstPhysicalBid))
  val recFirstEndpointExact = !recIn.firstKilledValid || (
    (recFirstSlotFirstKilled &&
      tableFirstRob(recStid)(recFirstPhysicalBid).asUInt ===
        recIn.firstKilled.asUInt) ||
      (recFirstSlotStraddles &&
        RecoveryPlanContract.suffixMember(recIn, recIn.firstKilled)))
  val recLastEndpointExact = !recIn.firstKilledValid || (
    recLastSlotValid &&
      tableLastRob(recStid)(recLastPhysicalBid).asUInt ===
        recIn.lastKilled.asUInt)
  val prepareKilledMask = Wire(Vec(p.ooo.brobEntriesPerStid, Bool()))
  val prepareStraddlingMask = Wire(Vec(p.ooo.brobEntriesPerStid, Bool()))
  for (bid <- 0 until p.ooo.brobEntriesPerStid) {
    val entryExact = tableValid(recStid)(bid) &&
      tableFirstRob(recStid)(bid).peId === recIn.trigger.peId &&
      tableFirstRob(recStid)(bid).stid === recIn.trigger.stid &&
      tableFirstRob(recStid)(bid).bid === bid.U &&
      tableFirstRob(recStid)(bid).brobGeneration === tableGeneration(recStid)(bid) &&
      tableLastRob(recStid)(bid).peId === recIn.trigger.peId &&
      tableLastRob(recStid)(bid).stid === recIn.trigger.stid &&
      tableLastRob(recStid)(bid).bid === bid.U &&
      tableLastRob(recStid)(bid).brobGeneration === tableGeneration(recStid)(bid)
    val firstKilled = entryExact &&
      RecoveryPlanContract.suffixMember(recIn, tableFirstRob(recStid)(bid))
    val lastKilled = entryExact &&
      RecoveryPlanContract.suffixMember(recIn, tableLastRob(recStid)(bid))
    prepareKilledMask(bid) := recIn.firstKilledValid && firstKilled
    prepareStraddlingMask(bid) := recIn.firstKilledValid && !firstKilled && lastKilled
  }
  val prepareStraddlingCount = PopCount(prepareStraddlingMask)
  val recLocalProjectionExact =
    !recIn.firstKilledValid || prepareKilledMask.asUInt.orR ||
      prepareStraddlingMask.asUInt.orR
  val recBidExact = recSuffixLegal && recTargetValid &&
    recFirstEndpointExact && recLastEndpointExact &&
    recLocalProjectionExact && prepareStraddlingCount <= 1.U
  io.recoveryPrepare.ready := !recoveryPending &&
    recIn.phase === RecoveryPhase.Prepare && recBidExact
  io.recoveryPrepared.valid := recoveryPending || io.recoveryPrepare.ready
  io.recoveryPrepared.bits := Mux(recoveryPending, recoveryPlan, recIn)
  when(io.recoveryPrepare.fire) {
    val partialCurrent = Wire(Bool())
    val killedCount = Wire(UInt(PrefixPacketContract.countWidth(
      p.ooo.brobEntriesPerStid).W))
    killedCount := PopCount(prepareKilledMask)
    val straddlingBlock = prepareStraddlingMask.asUInt.orR
    val straddlingBid = PriorityEncoder(prepareStraddlingMask)
    val straddlingTailWide = straddlingBid +& 1.U
    val straddlingTailWrap = straddlingTailWide >= p.ooo.brobEntriesPerStid.U
    assert(prepareStraddlingCount <= 1.U)
    // Pruning the closing suffix reopens the surviving prefix regardless of
    // whether the original block had already published its stop marker.
    partialCurrent := straddlingBlock
    val currentSurvives = currentValid(recStid) &&
      tableValid(recStid)(physicalBid(currentBid(recStid))) &&
      !prepareKilledMask(physicalBid(currentBid(recStid)))
    recoveryPending := true.B
    recoveryPlan := recIn
    recoveryKilledMaskReg := prepareKilledMask
    recoveryKilledCountReg := killedCount
    recoveryStraddlingBlockReg := straddlingBlock
    recoveryStraddlingBidReg := straddlingBid
    recoveryStraddlingGenerationReg := tableGeneration(recStid)(straddlingBid)
    recoveryCurrentValidReg := Mux(partialCurrent,
      true.B,
      currentSurvives)
    recoveryCurrentBidReg := Mux(partialCurrent,
      straddlingBid, currentBid(recStid))
    recoveryCurrentGenerationReg := Mux(partialCurrent,
      tableGeneration(recStid)(straddlingBid), currentGeneration(recStid))
    recoveryUsedReg := used(recStid) - killedCount
    recoveryTailReg := Mux(straddlingBlock,
      Mux(straddlingTailWrap, 0.U(p.nativeBidWidth.W),
        straddlingTailWide.pad(p.nativeBidWidth)),
      recIn.firstKilled.bid)
    recoveryGenerationReg := Mux(straddlingBlock,
      tableGeneration(recStid)(straddlingBid) + straddlingTailWrap.asUInt,
      recIn.firstKilled.brobGeneration)
    recoverySurvivingTailReg := recIn.survivingTail
  }

  val recoveryApplyHit = recoveryPending && io.recoveryApply.valid &&
    io.recoveryApply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recoveryApply.bits, recoveryPlan)
  val recoveryAbortHit = recoveryPending && io.recoveryAbort.valid &&
    io.recoveryAbort.bits.phase === RecoveryPhase.Abort &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recoveryAbort.bits, recoveryPlan)
  val recoveryApplyStid = safeStid(recoveryPlan.trigger.stid)
  when(recoveryApplyHit && !recoveryAbortHit) {
    when(recoveryPlan.firstKilledValid) {
      for (bid <- 0 until p.ooo.brobEntriesPerStid) {
        when(recoveryKilledMaskReg(bid)) {
          tableValid(recoveryApplyStid)(bid) := false.B
          tableClosed(recoveryApplyStid)(bid) := false.B
        }
      }
      tail(recoveryApplyStid) := recoveryTailReg
      generation(recoveryApplyStid) := recoveryGenerationReg
      used(recoveryApplyStid) := recoveryUsedReg
      currentValid(recoveryApplyStid) := recoveryCurrentValidReg
      currentBid(recoveryApplyStid) := recoveryCurrentBidReg
      currentGeneration(recoveryApplyStid) := recoveryCurrentGenerationReg
      when(recoveryStraddlingBlockReg) {
        val straddlingBid = physicalBid(recoveryStraddlingBidReg)
        assert(tableGeneration(recoveryApplyStid)(straddlingBid) ===
          recoveryStraddlingGenerationReg)
        tableLastRob(recoveryApplyStid)(straddlingBid) :=
          recoverySurvivingTailReg
      }
    }
    recoveryPending := false.B
  }
  when(recoveryAbortHit) {
    recoveryPending := false.B
  }
}
