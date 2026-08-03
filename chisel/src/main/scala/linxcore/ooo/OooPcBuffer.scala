package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, PopCount, PriorityEncoder, Valid, log2Ceil}
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface._

private[ooo] class PcBufferRow(val p: CoreParams) extends Bundle {
  private val liveWidth = PrefixPacketContract.countWidth(
    p.ooo.robGroupsPerStid)
  val valid = Bool()
  val stid = UInt(InterfaceWidth.index(p.ooo.stidCount).W)
  val pcBufferIndex = UInt(InterfaceWidth.index(p.ooo.pcBufferEntries).W)
  val allocationEpoch = UInt(p.ooo.pcAllocationEpochWidth.W)
  val pcBase = UInt(p.pcWidth.W)
  val firstRob = new RobIdentity(p)
  val lastRob = new RobIdentity(p)
  val nextCommitValid = Bool()
  val nextCommitPeId = UInt(p.peIdWidth.W)
  val nextCommitStid = UInt(InterfaceWidth.index(p.ooo.stidCount).W)
  val nextCommitRidSlot = UInt(
    InterfaceWidth.index(p.ooo.robGroupsPerStid).W)
  val nextCommitRidGeneration = UInt(p.ridGenerationWidth.W)
  val liveRobGroups = UInt(liveWidth.W)
  val closed = Bool()
  val closeOwnerValid = Bool()
  val closeOwner = new RobIdentity(p)
  val closeCommitted = Bool()
}

private[ooo] class PcBufferReadRow(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val stid = UInt(InterfaceWidth.index(p.ooo.stidCount).W)
  val pcBufferIndex = UInt(InterfaceWidth.index(p.ooo.pcBufferEntries).W)
  val allocationEpoch = UInt(p.ooo.pcAllocationEpochWidth.W)
  val pcBase = UInt(p.pcWidth.W)
}

private[ooo] object PcBufferRecoveryState extends ChiselEnum {
  val Idle, Scan, Prepared, Rejected = Value
}

class OooPcBufferIO(val p: CoreParams) extends Bundle {
  val prepare = Flipped(Valid(new D3RenameGroup(p)))
  val prepared = Output(new PcBufferD3Prepared(p))
  val prepareReady = Output(Bool())
  val publicationIdentity = Input(Valid(new OOORobPrepared(p)))
  val publishFire = Input(Bool())

  val commitPreview = Flipped(Valid(new CommitTxn(p)))
  val commitReady = Output(Bool())
  val commitApply = Input(Bool())

  val recovery = Flipped(new RecoveryTargetIO(p))

  val readAddress = Input(Vec(p.ooo.pcReadPorts,
    new PcBufferReadAddress(p)))
  val readPcBase = Output(Vec(p.ooo.pcReadPorts, Valid(UInt(p.pcWidth.W))))
}

/** Fixed-partition, bank-addressed PC-base owner.
  *
  * D3 preparation is side-effect free. One common S1 publication installs at
  * most three bases, while six readyless reads are served by three exact
  * replicas. Commit and recovery mutate only on their common apply events.
  */
class OooPcBuffer(val p: CoreParams = ParamProfiles.Default) extends Module {
  val io = IO(new OooPcBufferIO(p))

  private val d3Width = p.ooo.d3PrefixWidth
  private val retireWidth = p.widths.retireWidth
  private val entriesPerStid = p.ooo.pcBufferEntries / p.ooo.stidCount
  private val localWidth = InterfaceWidth.index(entriesPerStid)
  private val indexWidth = InterfaceWidth.index(p.ooo.pcBufferEntries)
  private val usedWidth = PrefixPacketContract.countWidth(entriesPerStid)
  private val liveWidth = PrefixPacketContract.countWidth(
    p.ooo.robGroupsPerStid)
  private val bankSelectionBits =
    if (p.ooo.pcBankCount == 1) 0 else log2Ceil(p.ooo.pcBankCount)
  private val rowsPerBank = entriesPerStid / p.ooo.pcBankCount
  private val bankIndexWidth = InterfaceWidth.index(p.ooo.pcBankCount)
  private val bankRowWidth = InterfaceWidth.index(rowsPerBank)
  private val scanWidth = p.ooo.pcRecoveryScanGroupsPerCycle
  private val scanCursorWidth = PrefixPacketContract.countWidth(entriesPerStid)
  private val maxOffset =
    ((BigInt(1) << p.ooo.pcOffsetWidth) - 1).U(p.pcWidth.W)

  private def partitionBase(stid: UInt): UInt = stid * entriesPerStid.U
  private def localFromIndex(index: UInt): UInt = index(localWidth - 1, 0)
  private def sizedLocal(local: UInt): UInt = local.pad(localWidth)
  private def bank(local: UInt): UInt =
    if (p.ooo.pcBankCount == 1) 0.U(bankIndexWidth.W)
    else sizedLocal(local)(bankSelectionBits - 1, 0)
  private def bankRow(local: UInt): UInt =
    if (rowsPerBank == 1) 0.U(bankRowWidth.W)
    else sizedLocal(local)(localWidth - 1, bankSelectionBits)
  private def groupMatches(a: VirtualRobGroupIntent, b: RobIdentity): Bool =
    a.valid && a.peId === b.peId && a.stid === b.stid &&
      a.ridSlot === b.ridSlot && a.ridGeneration === b.ridGeneration
  private def nextGroup(previous: RobIdentity, current: RobIdentity): Bool = {
    val slotSum = previous.ridSlot +& 1.U
    val wraps = slotSum >= p.ooo.robGroupsPerStid.U
    current.peId === previous.peId && current.stid === previous.stid &&
      current.ridSlot === slotSum(
        InterfaceWidth.index(p.ooo.robGroupsPerStid) - 1, 0) &&
      current.ridGeneration === previous.ridGeneration + wraps.asUInt
  }
  private def groupOrdinal(id: RobIdentity): UInt =
    Cat(id.ridGeneration, id.ridSlot)
  private def select[T <: Data](values: Vec[T], index: UInt): T =
    if (p.ooo.stidCount == 1) values(0) else values(index)

  private val table = RegInit(VecInit(Seq.fill(p.ooo.stidCount) {
    VecInit(Seq.fill(p.ooo.pcBankCount) {
      VecInit(Seq.fill(rowsPerBank)(0.U.asTypeOf(new PcBufferRow(p))))
    })
  }))
  private val readReplicas = RegInit(VecInit(Seq.fill(
    p.ooo.pcReadReplicaCount) {
    VecInit(Seq.fill(p.ooo.stidCount) {
      VecInit(Seq.fill(p.ooo.pcBankCount) {
        VecInit(Seq.fill(rowsPerBank)(
          0.U.asTypeOf(new PcBufferReadRow(p))))
      })
    })
  }))
  dontTouch(readReplicas)

  private def rowAt(stid: UInt, local: UInt): PcBufferRow =
    select(table, stid)(bank(local))(bankRow(local))
  private def readRowAt(
      replica: Int,
      stid: UInt,
      local: UInt): PcBufferReadRow =
    select(readReplicas(replica), stid)(bank(local))(bankRow(local))

  private val usedBases = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(usedWidth.W))))
  private val headLocal = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(localWidth.W))))
  private val headEpoch = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.pcAllocationEpochWidth.W))))
  private val tailLocal = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(localWidth.W))))
  private val tailEpoch = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.pcAllocationEpochWidth.W))))
  private val currentValid = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(false.B)))
  private val currentIndex = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(indexWidth.W))))
  private val currentEpoch = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.pcAllocationEpochWidth.W))))
  private val currentBase = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.pcWidth.W))))

  // D3 exact ROB-group discovery.
  private val prepareCount = io.prepare.bits.count
  private val prepareCountExact = prepareCount =/= 0.U &&
    prepareCount <= d3Width.U
  private val firstRob = io.prepare.bits.entries(0).uop.decoded.rob
  private val prepareStidInRange = firstRob.stid < p.ooo.stidCount.U
  private val prepareStid = Mux(prepareStidInRange, firstRob.stid, 0.U)
  private val groupCount = io.prepare.bits.groupCount
  private val groupCountExact = groupCount =/= 0.U &&
    groupCount <= prepareCount && groupCount <= d3Width.U

  private val laneGroupMatch = Wire(Vec(d3Width, Vec(d3Width, Bool())))
  for (lane <- 0 until d3Width; group <- 0 until d3Width) {
    val active = lane.U < prepareCount
    laneGroupMatch(lane)(group) := active && group.U < groupCount &&
      groupMatches(io.prepare.bits.groups(group),
        io.prepare.bits.entries(lane).uop.decoded.rob)
  }

  private val groupSeen = Wire(Vec(d3Width, Bool()))
  private val groupMinPc = Wire(Vec(d3Width, UInt(p.pcWidth.W)))
  private val groupMaxPc = Wire(Vec(d3Width, UInt(p.pcWidth.W)))
  private val groupFirstRob = Wire(Vec(d3Width, new RobIdentity(p)))
  private val groupLastRob = Wire(Vec(d3Width, new RobIdentity(p)))
  private val groupTrap = Wire(Vec(d3Width, Bool()))
  private val groupBlockStop = Wire(Vec(d3Width, Bool()))
  for (group <- 0 until d3Width) {
    val seen = Wire(Vec(d3Width + 1, Bool()))
    val minima = Wire(Vec(d3Width + 1, UInt(p.pcWidth.W)))
    val maxima = Wire(Vec(d3Width + 1, UInt(p.pcWidth.W)))
    val first = Wire(Vec(d3Width + 1, new RobIdentity(p)))
    val last = Wire(Vec(d3Width + 1, new RobIdentity(p)))
    val traps = Wire(Vec(d3Width + 1, Bool()))
    val stops = Wire(Vec(d3Width + 1, Bool()))
    seen(0) := false.B
    minima(0) := 0.U
    maxima(0) := 0.U
    first(0) := 0.U.asTypeOf(first(0))
    last(0) := 0.U.asTypeOf(last(0))
    traps(0) := false.B
    stops(0) := false.B
    for (lane <- 0 until d3Width) {
      val hit = laneGroupMatch(lane)(group)
      val entry = io.prepare.bits.entries(lane)
      val pc = entry.uop.decoded.instruction.parent.pc
      seen(lane + 1) := seen(lane) || hit
      minima(lane + 1) := Mux(hit && (!seen(lane) || pc < minima(lane)),
        pc, minima(lane))
      maxima(lane + 1) := Mux(hit && (!seen(lane) || pc > maxima(lane)),
        pc, maxima(lane))
      first(lane + 1) := Mux(hit && !seen(lane),
        entry.uop.decoded.rob, first(lane))
      last(lane + 1) := Mux(hit, entry.uop.decoded.rob, last(lane))
      traps(lane + 1) := traps(lane) || (hit && entry.trap.valid)
      stops(lane + 1) := stops(lane) || (hit &&
        (entry.blockStop || entry.uop.decoded.blockStop))
    }
    groupSeen(group) := seen.last
    groupMinPc(group) := minima.last
    groupMaxPc(group) := maxima.last
    groupFirstRob(group) := first.last
    groupLastRob(group) := last.last
    groupTrap(group) := traps.last
    groupBlockStop(group) := stops.last
  }

  private val laneShapeExact = (0 until d3Width).map { lane =>
    val active = lane.U < prepareCount
    val entry = io.prepare.bits.entries(lane)
    Mux(active,
      entry.uop.decoded.valid &&
        PopCount(laneGroupMatch(lane)) === 1.U &&
        entry.uop.decoded.rob.stid === firstRob.stid &&
        entry.uop.decoded.rob.peId === firstRob.peId &&
        entry.uop.decoded.instruction.parent.identity.stid === firstRob.stid &&
        entry.uop.decoded.instruction.parent.identity.peId === firstRob.peId,
      !entry.uop.decoded.valid)
  }.reduce(_ && _)
  private val groupShapeExact = (0 until d3Width).map { group =>
    val active = group.U < groupCount
    Mux(active,
      io.prepare.bits.groups(group).valid && groupSeen(group),
      !io.prepare.bits.groups(group).valid && !groupSeen(group))
  }.reduce(_ && _)

  // Base selection proceeds in exact group order and never mutates state.
  private val scanValid = Wire(Vec(d3Width + 1, Bool()))
  private val scanIndex = Wire(Vec(d3Width + 1, UInt(indexWidth.W)))
  private val scanEpoch = Wire(Vec(d3Width + 1,
    UInt(p.ooo.pcAllocationEpochWidth.W)))
  private val scanBase = Wire(Vec(d3Width + 1, UInt(p.pcWidth.W)))
  private val scanAllocCount = Wire(Vec(d3Width + 1, UInt(usedWidth.W)))
  private val groupIndex = Wire(Vec(d3Width, UInt(indexWidth.W)))
  private val groupEpoch = Wire(Vec(d3Width,
    UInt(p.ooo.pcAllocationEpochWidth.W)))
  private val groupBase = Wire(Vec(d3Width, UInt(p.pcWidth.W)))
  private val groupNewBase = Wire(Vec(d3Width, Bool()))
  private val groupImplicitClose = Wire(Vec(d3Width, Bool()))
  private val groupImplicitCloseIndex = Wire(Vec(d3Width, UInt(indexWidth.W)))
  private val groupImplicitCloseEpoch = Wire(Vec(d3Width,
    UInt(p.ooo.pcAllocationEpochWidth.W)))

  scanValid(0) := select(currentValid, prepareStid)
  scanIndex(0) := select(currentIndex, prepareStid)
  scanEpoch(0) := select(currentEpoch, prepareStid)
  scanBase(0) := select(currentBase, prepareStid)
  scanAllocCount(0) := 0.U
  for (group <- 0 until d3Width) {
    val active = group.U < groupCount
    val fits = groupSeen(group) && groupMinPc(group) >= scanBase(group) &&
      groupMaxPc(group) - scanBase(group) <= maxOffset
    val allocate = active && (!scanValid(group) || !fits || groupTrap(group))
    val tailSum = select(tailLocal, prepareStid) +& scanAllocCount(group)
    val wraps = tailSum >= entriesPerStid.U
    val allocatedIndex = partitionBase(prepareStid) +
      tailSum(localWidth - 1, 0)
    groupNewBase(group) := allocate
    groupImplicitClose(group) := allocate && scanValid(group)
    groupImplicitCloseIndex(group) := scanIndex(group)
    groupImplicitCloseEpoch(group) := scanEpoch(group)
    groupIndex(group) := Mux(allocate, allocatedIndex, scanIndex(group))
    groupEpoch(group) := Mux(allocate,
      select(tailEpoch, prepareStid) + wraps.asUInt, scanEpoch(group))
    groupBase(group) := Mux(allocate, groupMinPc(group), scanBase(group))
    val closes = active && (groupBlockStop(group) || groupTrap(group))
    scanValid(group + 1) := Mux(active, !closes, scanValid(group))
    scanIndex(group + 1) := Mux(active, groupIndex(group), scanIndex(group))
    scanEpoch(group + 1) := Mux(active, groupEpoch(group), scanEpoch(group))
    scanBase(group + 1) := Mux(active, groupBase(group), scanBase(group))
    scanAllocCount(group + 1) := scanAllocCount(group) + allocate.asUInt
  }

  private val freeBases = entriesPerStid.U(usedWidth.W) -
    select(usedBases, prepareStid)
  private val groupResourceExact = Wire(Vec(d3Width, Bool()))
  private val acceptedGroupChain = Wire(Vec(d3Width + 1, Bool()))
  private val acceptedGroups = Wire(Vec(d3Width, Bool()))
  acceptedGroupChain(0) := true.B
  for (group <- 0 until d3Width) {
    val active = group.U < groupCount
    val allocatedThroughGroup = scanAllocCount(group + 1)
    val offsetExact = groupSeen(group) &&
      groupMaxPc(group) >= groupBase(group) &&
      groupMaxPc(group) - groupBase(group) <= maxOffset
    val local = localFromIndex(groupIndex(group))
    val rowAvailable = !groupNewBase(group) ||
      !rowAt(prepareStid, local).valid
    groupResourceExact(group) := active && offsetExact && rowAvailable &&
      allocatedThroughGroup <= p.ooo.pcWritePorts.U &&
      allocatedThroughGroup <= freeBases
    acceptedGroups(group) := acceptedGroupChain(group) &&
      groupResourceExact(group)
    acceptedGroupChain(group + 1) := acceptedGroups(group)
  }
  private val acceptedGroupCount = PopCount(acceptedGroups)
  private val acceptedLanes = Wire(Vec(d3Width, Bool()))
  for (lane <- 0 until d3Width) {
    acceptedLanes(lane) := lane.U < prepareCount &&
      (0 until d3Width).map { group =>
        group.U < acceptedGroupCount && laneGroupMatch(lane)(group)
      }.reduce(_ || _)
  }
  private val acceptedCount = PopCount(acceptedLanes)
  private val boundGroupFirstRob = Wire(Vec(d3Width, new RobIdentity(p)))
  private val boundGroupLastRob = Wire(Vec(d3Width, new RobIdentity(p)))
  for (group <- 0 until d3Width) {
    val boundFirst = Wire(Vec(d3Width + 1, new RobIdentity(p)))
    val boundLast = Wire(Vec(d3Width + 1, new RobIdentity(p)))
    val boundSeen = Wire(Vec(d3Width + 1, Bool()))
    boundFirst(0) := 0.U.asTypeOf(boundFirst(0))
    boundLast(0) := 0.U.asTypeOf(boundLast(0))
    boundSeen(0) := false.B
    for (lane <- 0 until d3Width) {
      val hit = laneGroupMatch(lane)(group) && lane.U < acceptedCount
      val identity = io.publicationIdentity.bits.entries(lane).rob
      boundFirst(lane + 1) := Mux(hit && !boundSeen(lane),
        identity, boundFirst(lane))
      boundLast(lane + 1) := Mux(hit, identity, boundLast(lane))
      boundSeen(lane + 1) := boundSeen(lane) || hit
    }
    boundGroupFirstRob(group) := boundFirst.last
    boundGroupLastRob(group) := boundLast.last
  }
  private val acceptedLanePrefixExact = (0 until d3Width).map { lane =>
    acceptedLanes(lane) === (lane.U < acceptedCount)
  }.reduce(_ && _)
  private val allocatedBases = scanAllocCount(acceptedGroupCount)
  private val acceptedScanValid = scanValid(acceptedGroupCount)
  private val acceptedScanIndex = scanIndex(acceptedGroupCount)
  private val acceptedScanEpoch = scanEpoch(acceptedGroupCount)
  private val acceptedScanBase = scanBase(acceptedGroupCount)
  private val currentRowExact = !select(currentValid, prepareStid) || {
    val current = select(currentIndex, prepareStid)
    val row = rowAt(prepareStid, localFromIndex(current))
    row.valid && row.stid === prepareStid &&
      row.pcBufferIndex === current &&
      row.allocationEpoch === select(currentEpoch, prepareStid) &&
      row.pcBase === select(currentBase, prepareStid)
  }

  // Recovery state is declared before admission so its target can fence only
  // the selected STID while peer partitions continue.
  private val recoveryState = RegInit(PcBufferRecoveryState.Idle)
  private val recoveryPlan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))
  private val recoveryTargetStid = recoveryPlan.trigger.stid
  private val offeredRecoveryStid = io.recovery.prepare.bits.trigger.stid
  private val recoveryOfferTargetsPrepare = io.recovery.prepare.valid &&
    offeredRecoveryStid < p.ooo.stidCount.U &&
    prepareStidInRange && offeredRecoveryStid === prepareStid
  private val retainedRecoveryTargetsPrepare =
    recoveryState =/= PcBufferRecoveryState.Idle &&
      prepareStidInRange && recoveryTargetStid === prepareStid
  private val prepareRecoveryFence = recoveryOfferTargetsPrepare ||
    retainedRecoveryTargetsPrepare

  private val commitCount = io.commitPreview.bits.count
  private val commitCountExact = commitCount =/= 0.U &&
    commitCount <= retireWidth.U
  private val commitFirstRob = io.commitPreview.bits.entries(0).rob
  private val commitStidInRange = commitFirstRob.stid < p.ooo.stidCount.U
  private val commitStid = Mux(commitStidInRange, commitFirstRob.stid, 0.U)
  private val sameStidCommitPreview = io.commitPreview.valid &&
    commitStidInRange && prepareStidInRange && commitStid === prepareStid

  io.prepareReady := io.prepare.valid && prepareCountExact &&
    prepareStidInRange && groupCountExact && laneShapeExact &&
    groupShapeExact && acceptedGroupCount.orR && acceptedCount.orR &&
    acceptedLanePrefixExact && currentRowExact && !prepareRecoveryFence &&
    !sameStidCommitPreview
  io.prepared := 0.U.asTypeOf(io.prepared)
  io.prepared.count := acceptedCount
  io.prepared.groupCount := acceptedGroupCount
  for (lane <- 0 until d3Width) {
    val matches = laneGroupMatch(lane)
    val selected = PriorityEncoder(matches.asUInt)
    val active = lane.U < acceptedCount && matches.asUInt.orR
    val pc = io.prepare.bits.entries(lane).uop.decoded.instruction.parent.pc
    io.prepared.lanes(lane).valid := active
    io.prepared.lanes(lane).pcBufferIndex := groupIndex(selected)
    io.prepared.lanes(lane).pcOffset :=
      (pc - groupBase(selected))(p.ooo.pcOffsetWidth - 1, 0)
    io.prepared.lanes(lane).allocationEpoch := groupEpoch(selected)
  }

  private val publishFire = io.publishFire && io.prepare.valid &&
    io.prepareReady
  when(io.publishFire) {
    assert(io.prepare.valid && io.prepareReady,
      "PC-buffer publication requires the exact accepted D3 view")
    assert(io.publicationIdentity.valid &&
      io.publicationIdentity.bits.count === acceptedCount,
      "PC-buffer publication requires ROB-bound identities for the exact accepted prefix")
    for (lane <- 0 until d3Width) {
      when(lane.U < acceptedCount) {
        val preparedRob = io.prepare.bits.entries(lane).uop.decoded.rob
        val bound = io.publicationIdentity.bits.entries(lane)
        assert(bound.valid && bound.rob.peId === preparedRob.peId &&
          bound.rob.stid === preparedRob.stid &&
          bound.rob.ridSlot === preparedRob.ridSlot &&
          bound.rob.ridGeneration === preparedRob.ridGeneration &&
          bound.rob.memberIndex === preparedRob.memberIndex,
          "PC-buffer ROB-bound publication identities must preserve accepted lane order")
      }
    }
  }

  // Exact ordered commit preview. Only group-last rows advance the base owner.
  private val commitLaneExact = Wire(Vec(retireWidth, Bool()))
  for (lane <- 0 until retireWidth) {
    val active = lane.U < commitCount
    val entry = io.commitPreview.bits.entries(lane)
    val reference = entry.pcBufferIndexOffset
    val local = localFromIndex(reference.pcBufferIndex)
    val row = rowAt(commitStid, local)
    val partitionExact = reference.pcBufferIndex >= partitionBase(commitStid) &&
      reference.pcBufferIndex < partitionBase(commitStid) + entriesPerStid.U
    val rowExact = reference.valid && partitionExact && row.valid &&
      row.stid === commitStid &&
      row.pcBufferIndex === reference.pcBufferIndex &&
      row.allocationEpoch === reference.allocationEpoch
    val priorHits = Wire(Vec(lane + 1, Bool()))
    val priorRob = Wire(new RobIdentity(p))
    priorRob := 0.U.asTypeOf(priorRob)
    for (prior <- 0 until lane) {
      val previous = io.commitPreview.bits.entries(prior)
      priorHits(prior) := prior.U < commitCount && previous.robGroupLast &&
        previous.pcBufferIndexOffset.valid &&
        previous.pcBufferIndexOffset.pcBufferIndex ===
          reference.pcBufferIndex &&
        previous.pcBufferIndexOffset.allocationEpoch ===
          reference.allocationEpoch
      when(priorHits(prior)) { priorRob := previous.rob }
    }
    priorHits(lane) := false.B
    val hasPrior = priorHits.asUInt.orR
    val firstGroupExact = row.nextCommitValid &&
      entry.rob.peId === row.nextCommitPeId &&
      entry.rob.stid === row.nextCommitStid &&
      entry.rob.ridSlot === row.nextCommitRidSlot &&
      entry.rob.ridGeneration === row.nextCommitRidGeneration
    val ordered = !entry.robGroupLast ||
      Mux(hasPrior, nextGroup(priorRob, entry.rob), firstGroupExact)
    commitLaneExact(lane) := Mux(active,
      entry.rob.stid === commitFirstRob.stid && rowExact && ordered,
      true.B)
  }

  private val commitHitsByLocal = Wire(Vec(entriesPerStid, UInt(
    PrefixPacketContract.countWidth(retireWidth).W)))
  private val commitCloseByLocal = Wire(Vec(entriesPerStid, Bool()))
  private val commitLastRobByLocal = Wire(Vec(entriesPerStid,
    new RobIdentity(p)))
  for (local <- 0 until entriesPerStid) {
    val row = rowAt(commitStid, local.U)
    val groupHits = VecInit((0 until retireWidth).map { lane =>
      val entry = io.commitPreview.bits.entries(lane)
      lane.U < commitCount && entry.robGroupLast &&
        entry.pcBufferIndexOffset.valid &&
        localFromIndex(entry.pcBufferIndexOffset.pcBufferIndex) === local.U &&
        entry.pcBufferIndexOffset.allocationEpoch === row.allocationEpoch
    })
    commitHitsByLocal(local) := PopCount(groupHits)
    commitCloseByLocal(local) := row.closeOwnerValid &&
      (0 until retireWidth).map { lane =>
        lane.U < commitCount &&
          io.commitPreview.bits.entries(lane).rob.asUInt ===
            row.closeOwner.asUInt
      }.reduce(_ || _)
    val last = Wire(new RobIdentity(p))
    last := 0.U.asTypeOf(last)
    for (lane <- 0 until retireWidth) {
      when(groupHits(lane)) { last := io.commitPreview.bits.entries(lane).rob }
    }
    commitLastRobByLocal(local) := last
  }
  private val commitCountsExact = (0 until entriesPerStid).map { local =>
    commitHitsByLocal(local) <= rowAt(commitStid, local.U).liveRobGroups
  }.reduce(_ && _)
  private val recoveryOfferTargetsCommit = io.recovery.prepare.valid &&
    offeredRecoveryStid < p.ooo.stidCount.U && commitStidInRange &&
    offeredRecoveryStid === commitStid
  private val retainedRecoveryTargetsCommit =
    recoveryState =/= PcBufferRecoveryState.Idle && commitStidInRange &&
      recoveryTargetStid === commitStid
  private val commitRecoveryFence = recoveryOfferTargetsCommit ||
    retainedRecoveryTargetsCommit

  private val commitShapeExact = commitCountExact && commitStidInRange &&
    commitLaneExact.asUInt.andR && commitCountsExact
  io.commitReady := io.commitPreview.valid && commitShapeExact &&
    !commitRecoveryFence &&
    !(io.prepare.valid && prepareStidInRange && prepareStid === commitStid)

  private val freeChain = Wire(Vec(retireWidth + 1, Bool()))
  private val freedLocal = Wire(Vec(retireWidth, UInt(localWidth.W)))
  freeChain(0) := true.B
  for (offset <- 0 until retireWidth) {
    val sum = select(headLocal, commitStid) +& offset.U
    val wraps = sum >= entriesPerStid.U
    val local = sum(localWidth - 1, 0)
    val row = rowAt(commitStid, local)
    val liveAfter = row.liveRobGroups - commitHitsByLocal(local)
    val closeAfter = row.closeCommitted || commitCloseByLocal(local)
    val headExact = row.valid &&
      row.pcBufferIndex === partitionBase(commitStid) + local &&
      row.allocationEpoch === select(headEpoch, commitStid) + wraps.asUInt
    freedLocal(offset) := local
    freeChain(offset + 1) := freeChain(offset) &&
      offset.U < select(usedBases, commitStid) &&
      headExact && liveAfter === 0.U && row.closed && closeAfter
  }
  private val freedBases = PopCount(freeChain.tail)

  when(io.commitApply) {
    assert(io.commitPreview.valid && io.commitReady,
      "PC-buffer commit apply requires the exact common preview")
  }

  // Recovery scans the target partition in allocation order. It retains row
  // repair masks and echoes the canonical plan without projecting identity.
  private val recoveryCursor = RegInit(0.U(scanCursorWidth.W))
  private val recoverySnapshotUsed = RegInit(0.U(usedWidth.W))
  private val recoverySnapshotHeadLocal = RegInit(0.U(localWidth.W))
  private val recoverySnapshotHeadEpoch = RegInit(
    0.U(p.ooo.pcAllocationEpochWidth.W))
  private val recoverySnapshotTailLocal = RegInit(0.U(localWidth.W))
  private val recoverySnapshotTailEpoch = RegInit(
    0.U(p.ooo.pcAllocationEpochWidth.W))
  private val recoveryFreeMask = RegInit(0.U(entriesPerStid.W))
  private val recoveryRepairMask = RegInit(0.U(entriesPerStid.W))
  private val recoveryReopenMask = RegInit(0.U(entriesPerStid.W))
  private val recoveryRepairLive = RegInit(VecInit(Seq.fill(entriesPerStid)(
    0.U(liveWidth.W))))
  private val recoveryExact = RegInit(true.B)
  private val recoverySeenSuffix = RegInit(false.B)
  private val recoveryFirstEndpoint = RegInit(false.B)
  private val recoveryLastEndpoint = RegInit(false.B)
  private val recoveryKilledGroups = RegInit(0.U(liveWidth.W))
  private val recoveryFreedBases = RegInit(0.U(usedWidth.W))
  private val recoveryFirstFreedValid = RegInit(false.B)
  private val recoveryFirstFreedIndex = RegInit(0.U(indexWidth.W))
  private val recoveryFirstFreedEpoch = RegInit(
    0.U(p.ooo.pcAllocationEpochWidth.W))
  private val recoveryLastSurvivorValid = RegInit(false.B)
  private val recoveryLastSurvivorIndex = RegInit(0.U(indexWidth.W))
  private val recoveryLastSurvivorEpoch = RegInit(
    0.U(p.ooo.pcAllocationEpochWidth.W))
  private val recoveryLastSurvivorBase = RegInit(0.U(p.pcWidth.W))
  private val recoveryLastSurvivorOpen = RegInit(false.B)

  private val recoveryOfferLegal =
    io.recovery.prepare.bits.phase === RecoveryPhase.Prepare &&
      io.recovery.prepare.bits.trigger.stid < p.ooo.stidCount.U &&
      RecoveryPlanContract.legalSuffixWindow(io.recovery.prepare.bits)
  io.recovery.prepare.ready := recoveryState === PcBufferRecoveryState.Idle &&
    recoveryOfferLegal
  io.recovery.prepared.valid := recoveryState === PcBufferRecoveryState.Prepared
  io.recovery.prepared.bits := recoveryPlan

  private val scanExactChain = Wire(Vec(scanWidth + 1, Bool()))
  private val scanSeenChain = Wire(Vec(scanWidth + 1, Bool()))
  private val scanFirstEndpointChain = Wire(Vec(scanWidth + 1, Bool()))
  private val scanLastEndpointChain = Wire(Vec(scanWidth + 1, Bool()))
  private val scanKilledGroupsChain = Wire(Vec(scanWidth + 1, UInt(liveWidth.W)))
  private val scanFreedBasesChain = Wire(Vec(scanWidth + 1, UInt(usedWidth.W)))
  private val scanFirstFreedValidChain = Wire(Vec(scanWidth + 1, Bool()))
  private val scanFirstFreedIndexChain = Wire(Vec(scanWidth + 1,
    UInt(indexWidth.W)))
  private val scanFirstFreedEpochChain = Wire(Vec(scanWidth + 1,
    UInt(p.ooo.pcAllocationEpochWidth.W)))
  private val scanLastSurvivorValidChain = Wire(Vec(scanWidth + 1, Bool()))
  private val scanLastSurvivorIndexChain = Wire(Vec(scanWidth + 1,
    UInt(indexWidth.W)))
  private val scanLastSurvivorEpochChain = Wire(Vec(scanWidth + 1,
    UInt(p.ooo.pcAllocationEpochWidth.W)))
  private val scanLastSurvivorBaseChain = Wire(Vec(scanWidth + 1,
    UInt(p.pcWidth.W)))
  private val scanLastSurvivorOpenChain = Wire(Vec(scanWidth + 1, Bool()))
  private val scanFree = Wire(Vec(scanWidth, Bool()))
  private val scanRepair = Wire(Vec(scanWidth, Bool()))
  private val scanReopen = Wire(Vec(scanWidth, Bool()))
  private val scanLocal = Wire(Vec(scanWidth, UInt(localWidth.W)))
  private val scanRepairLive = Wire(Vec(scanWidth, UInt(liveWidth.W)))

  scanExactChain(0) := recoveryExact
  scanSeenChain(0) := recoverySeenSuffix
  scanFirstEndpointChain(0) := recoveryFirstEndpoint
  scanLastEndpointChain(0) := recoveryLastEndpoint
  scanKilledGroupsChain(0) := recoveryKilledGroups
  scanFreedBasesChain(0) := recoveryFreedBases
  scanFirstFreedValidChain(0) := recoveryFirstFreedValid
  scanFirstFreedIndexChain(0) := recoveryFirstFreedIndex
  scanFirstFreedEpochChain(0) := recoveryFirstFreedEpoch
  scanLastSurvivorValidChain(0) := recoveryLastSurvivorValid
  scanLastSurvivorIndexChain(0) := recoveryLastSurvivorIndex
  scanLastSurvivorEpochChain(0) := recoveryLastSurvivorEpoch
  scanLastSurvivorBaseChain(0) := recoveryLastSurvivorBase
  scanLastSurvivorOpenChain(0) := recoveryLastSurvivorOpen

  for (lane <- 0 until scanWidth) {
    val position = recoveryCursor +& lane.U
    val active = position < recoverySnapshotUsed
    val localSum = recoverySnapshotHeadLocal +& position
    val wraps = localSum >= entriesPerStid.U
    val local = localSum(localWidth - 1, 0)
    val row = rowAt(recoveryTargetStid, local)
    val liveFirst = Wire(new RobIdentity(p))
    liveFirst := row.firstRob
    liveFirst.peId := row.nextCommitPeId
    liveFirst.stid := row.nextCommitStid
    liveFirst.ridSlot := row.nextCommitRidSlot
    liveFirst.ridGeneration := row.nextCommitRidGeneration
    liveFirst.memberIndex := 0.U
    val rowExact = !active || (row.valid &&
      row.stid === recoveryTargetStid &&
      row.pcBufferIndex === partitionBase(recoveryTargetStid) + local &&
      row.allocationEpoch === recoverySnapshotHeadEpoch + wraps.asUInt &&
      row.nextCommitValid && row.liveRobGroups =/= 0.U &&
      liveFirst.peId === recoveryPlan.trigger.peId &&
      liveFirst.stid === recoveryPlan.trigger.stid &&
      row.lastRob.peId === recoveryPlan.trigger.peId &&
      row.lastRob.stid === recoveryPlan.trigger.stid)
    val firstKilled = active && recoveryPlan.firstKilledValid &&
      RecoveryPlanContract.suffixMember(recoveryPlan, liveFirst)
    val lastKilled = active && recoveryPlan.firstKilledValid &&
      RecoveryPlanContract.suffixMember(recoveryPlan, row.lastRob)
    val straddles = !firstKilled && lastKilled
    val liveFirstOrdinal = groupOrdinal(liveFirst)
    val killedOrdinal = groupOrdinal(recoveryPlan.firstKilled)
    val groupsBeforeKilled = killedOrdinal - liveFirstOrdinal
    val keepsPartialGroup = recoveryPlan.firstKilled.memberIndex =/= 0.U
    val survivorsWide = groupsBeforeKilled + keepsPartialGroup.asUInt
    val survivors = Mux(straddles,
      survivorsWide(liveWidth - 1, 0), row.liveRobGroups)
    val survivorsExact = !straddles ||
      (survivors =/= 0.U && survivors <= row.liveRobGroups)
    val free = firstKilled
    val repair = straddles
    val closeKilled = active && row.closeOwnerValid &&
      RecoveryPlanContract.suffixMember(recoveryPlan, row.closeOwner)
    val suffixOrderExact = !active || !scanSeenChain(lane) || firstKilled
    val firstEndpoint = recoveryPlan.firstKilledValid && (
      (firstKilled && liveFirst.asUInt === recoveryPlan.firstKilled.asUInt) ||
        (straddles &&
          groupOrdinal(recoveryPlan.firstKilled) >= liveFirstOrdinal &&
          groupOrdinal(recoveryPlan.firstKilled) <= groupOrdinal(row.lastRob)))
    val lastEndpoint = lastKilled &&
      row.lastRob.asUInt === recoveryPlan.lastKilled.asUInt
    val removedGroups = Mux(free, row.liveRobGroups,
      Mux(repair,
        row.liveRobGroups - survivors + keepsPartialGroup.asUInt, 0.U))
    val retained = active && !free
    val openAfter = !row.closed || closeKilled

    scanLocal(lane) := local
    scanFree(lane) := free
    scanRepair(lane) := repair
    scanReopen(lane) := retained && closeKilled
    scanRepairLive(lane) := survivors
    scanExactChain(lane + 1) := scanExactChain(lane) && rowExact &&
      survivorsExact && suffixOrderExact
    scanSeenChain(lane + 1) := scanSeenChain(lane) || lastKilled
    scanFirstEndpointChain(lane + 1) := scanFirstEndpointChain(lane) ||
      firstEndpoint
    scanLastEndpointChain(lane + 1) := scanLastEndpointChain(lane) ||
      lastEndpoint
    scanKilledGroupsChain(lane + 1) := scanKilledGroupsChain(lane) +
      removedGroups
    scanFreedBasesChain(lane + 1) := scanFreedBasesChain(lane) + free.asUInt
    scanFirstFreedValidChain(lane + 1) :=
      scanFirstFreedValidChain(lane) || free
    scanFirstFreedIndexChain(lane + 1) := Mux(
      free && !scanFirstFreedValidChain(lane), row.pcBufferIndex,
      scanFirstFreedIndexChain(lane))
    scanFirstFreedEpochChain(lane + 1) := Mux(
      free && !scanFirstFreedValidChain(lane), row.allocationEpoch,
      scanFirstFreedEpochChain(lane))
    scanLastSurvivorValidChain(lane + 1) :=
      scanLastSurvivorValidChain(lane) || retained
    scanLastSurvivorIndexChain(lane + 1) := Mux(retained,
      row.pcBufferIndex, scanLastSurvivorIndexChain(lane))
    scanLastSurvivorEpochChain(lane + 1) := Mux(retained,
      row.allocationEpoch, scanLastSurvivorEpochChain(lane))
    scanLastSurvivorBaseChain(lane + 1) := Mux(retained,
      row.pcBase, scanLastSurvivorBaseChain(lane))
    scanLastSurvivorOpenChain(lane + 1) := Mux(retained,
      openAfter, scanLastSurvivorOpenChain(lane))
  }

  private val recoveryScanDone = recoveryCursor + scanWidth.U >=
    recoverySnapshotUsed
  private val recoveryFinalExact = scanExactChain.last &&
    scanFirstEndpointChain.last === recoveryPlan.firstKilledValid &&
    scanLastEndpointChain.last === recoveryPlan.firstKilledValid &&
    scanKilledGroupsChain.last === recoveryPlan.killedGroupCount
  private val scanFreeMask = (0 until scanWidth).map { lane =>
    Mux(scanFree(lane), (1.U(entriesPerStid.W) << scanLocal(lane)), 0.U)
  }.reduce(_ | _)
  private val scanRepairMask = (0 until scanWidth).map { lane =>
    Mux(scanRepair(lane), (1.U(entriesPerStid.W) << scanLocal(lane)), 0.U)
  }.reduce(_ | _)
  private val scanReopenMask = (0 until scanWidth).map { lane =>
    Mux(scanReopen(lane), (1.U(entriesPerStid.W) << scanLocal(lane)), 0.U)
  }.reduce(_ | _)

  when(io.recovery.prepare.fire) {
    val stid = io.recovery.prepare.bits.trigger.stid
    recoveryPlan := io.recovery.prepare.bits
    recoveryCursor := 0.U
    recoverySnapshotUsed := select(usedBases, stid)
    recoverySnapshotHeadLocal := select(headLocal, stid)
    recoverySnapshotHeadEpoch := select(headEpoch, stid)
    recoverySnapshotTailLocal := select(tailLocal, stid)
    recoverySnapshotTailEpoch := select(tailEpoch, stid)
    recoveryFreeMask := 0.U
    recoveryRepairMask := 0.U
    recoveryReopenMask := 0.U
    recoveryRepairLive.foreach(_ := 0.U)
    recoveryExact := true.B
    recoverySeenSuffix := false.B
    recoveryFirstEndpoint := false.B
    recoveryLastEndpoint := false.B
    recoveryKilledGroups := 0.U
    recoveryFreedBases := 0.U
    recoveryFirstFreedValid := false.B
    recoveryFirstFreedIndex := 0.U
    recoveryFirstFreedEpoch := 0.U
    recoveryLastSurvivorValid := false.B
    recoveryLastSurvivorIndex := 0.U
    recoveryLastSurvivorEpoch := 0.U
    recoveryLastSurvivorBase := 0.U
    recoveryLastSurvivorOpen := false.B
    recoveryState := PcBufferRecoveryState.Scan
  }.elsewhen(recoveryState === PcBufferRecoveryState.Scan) {
    recoveryExact := scanExactChain.last
    recoverySeenSuffix := scanSeenChain.last
    recoveryFirstEndpoint := scanFirstEndpointChain.last
    recoveryLastEndpoint := scanLastEndpointChain.last
    recoveryKilledGroups := scanKilledGroupsChain.last
    recoveryFreedBases := scanFreedBasesChain.last
    recoveryFirstFreedValid := scanFirstFreedValidChain.last
    recoveryFirstFreedIndex := scanFirstFreedIndexChain.last
    recoveryFirstFreedEpoch := scanFirstFreedEpochChain.last
    recoveryLastSurvivorValid := scanLastSurvivorValidChain.last
    recoveryLastSurvivorIndex := scanLastSurvivorIndexChain.last
    recoveryLastSurvivorEpoch := scanLastSurvivorEpochChain.last
    recoveryLastSurvivorBase := scanLastSurvivorBaseChain.last
    recoveryLastSurvivorOpen := scanLastSurvivorOpenChain.last
    recoveryFreeMask := recoveryFreeMask | scanFreeMask
    recoveryRepairMask := recoveryRepairMask | scanRepairMask
    recoveryReopenMask := recoveryReopenMask | scanReopenMask
    for (lane <- 0 until scanWidth) {
      when(scanRepair(lane)) {
        recoveryRepairLive(scanLocal(lane)) := scanRepairLive(lane)
      }
    }
    when(recoveryScanDone) {
      recoveryState := Mux(recoveryFinalExact,
        PcBufferRecoveryState.Prepared, PcBufferRecoveryState.Rejected)
    }.otherwise {
      recoveryCursor := recoveryCursor + scanWidth.U
    }
  }

  private val recoveryApply = recoveryState === PcBufferRecoveryState.Prepared &&
    io.recovery.apply.valid &&
    io.recovery.apply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.apply.bits, recoveryPlan)
  private val recoveryAbort =
    (recoveryState === PcBufferRecoveryState.Prepared ||
      recoveryState === PcBufferRecoveryState.Rejected) &&
      io.recovery.abort.valid &&
      io.recovery.abort.bits.phase === RecoveryPhase.Abort &&
      RecoveryPlanContract.sameTransactionIgnoringPhase(
        io.recovery.abort.bits, recoveryPlan)
  when(io.recovery.apply.valid) {
    assert(recoveryApply,
      "PC-buffer recovery apply must match the retained canonical plan")
  }
  when(io.recovery.abort.valid) {
    assert(recoveryAbort,
      "PC-buffer recovery abort must match the retained canonical plan")
  }
  when(recoveryApply || recoveryAbort) {
    recoveryState := PcBufferRecoveryState.Idle
  }

  // Common pointer mutations. Same-STID publication and commit are mutually
  // excluded; recovery fences only its target partition.
  for (stid <- 0 until p.ooo.stidCount) {
    val publishHere = publishFire && prepareStid === stid.U
    val commitHere = io.commitApply && io.commitReady && commitStid === stid.U
    val recoveryHere = recoveryApply && recoveryTargetStid === stid.U
    when(publishHere) {
      val sum = tailLocal(stid) +& allocatedBases
      val wraps = sum >= entriesPerStid.U
      usedBases(stid) := usedBases(stid) + allocatedBases
      tailLocal(stid) := sum(localWidth - 1, 0)
      tailEpoch(stid) := tailEpoch(stid) + wraps.asUInt
      currentValid(stid) := acceptedScanValid
      currentIndex(stid) := acceptedScanIndex
      currentEpoch(stid) := acceptedScanEpoch
      currentBase(stid) := acceptedScanBase
    }
    when(commitHere) {
      val sum = headLocal(stid) +& freedBases
      val wraps = sum >= entriesPerStid.U
      usedBases(stid) := usedBases(stid) - freedBases
      headLocal(stid) := sum(localWidth - 1, 0)
      headEpoch(stid) := headEpoch(stid) + wraps.asUInt
    }
    when(recoveryHere) {
      usedBases(stid) := usedBases(stid) - recoveryFreedBases
      when(recoveryFirstFreedValid) {
        tailLocal(stid) := localFromIndex(recoveryFirstFreedIndex)
        tailEpoch(stid) := recoveryFirstFreedEpoch
      }.otherwise {
        tailLocal(stid) := recoverySnapshotTailLocal
        tailEpoch(stid) := recoverySnapshotTailEpoch
      }
      currentValid(stid) := recoveryLastSurvivorValid &&
        recoveryLastSurvivorOpen
      currentIndex(stid) := recoveryLastSurvivorIndex
      currentEpoch(stid) := recoveryLastSurvivorEpoch
      currentBase(stid) := recoveryLastSurvivorBase
    }
  }

  // Banked metadata and the three exact read replicas share every mutation.
  for (stid <- 0 until p.ooo.stidCount; local <- 0 until entriesPerStid) {
    val row = rowAt(stid.U, local.U)
    val publishHere = publishFire && prepareStid === stid.U
    val matchingGroups = VecInit((0 until d3Width).map { group =>
      group.U < acceptedGroupCount &&
        localFromIndex(groupIndex(group)) === local.U &&
        groupEpoch(group) === Mux(groupNewBase(group),
          groupEpoch(group), row.allocationEpoch)
    })
    val groupHits = PopCount(matchingGroups)
    val newHit = (0 until d3Width).map { group =>
      matchingGroups(group) && groupNewBase(group)
    }.reduce(_ || _)
    val firstGroup = PriorityEncoder(matchingGroups.asUInt)
    val lastGroup = Wire(UInt(InterfaceWidth.index(d3Width).W))
    lastGroup := firstGroup
    for (group <- 0 until d3Width) {
      when(matchingGroups(group)) { lastGroup := group.U }
    }
    val explicitClose = (0 until d3Width).map { group =>
      matchingGroups(group) &&
        (groupBlockStop(group) || groupTrap(group))
    }.reduce(_ || _)
    val implicitClose = (0 until d3Width).map { group =>
      group.U < acceptedGroupCount && groupImplicitClose(group) &&
        localFromIndex(groupImplicitCloseIndex(group)) === local.U &&
        groupImplicitCloseEpoch(group) === row.allocationEpoch
    }.reduce(_ || _)
    val closeOwner = Wire(new RobIdentity(p))
    closeOwner := 0.U.asTypeOf(closeOwner)
    for (group <- 0 until d3Width) {
      when(matchingGroups(group) &&
          (groupBlockStop(group) || groupTrap(group))) {
        closeOwner := boundGroupLastRob(group)
      }
      when(group.U < acceptedGroupCount && groupImplicitClose(group) &&
          localFromIndex(groupImplicitCloseIndex(group)) === local.U &&
          groupImplicitCloseEpoch(group) === row.allocationEpoch) {
        closeOwner := boundGroupFirstRob(group)
      }
    }

    val commitHere = io.commitApply && io.commitReady && commitStid === stid.U
    val commitHits = commitHitsByLocal(local)
    val commitClose = commitCloseByLocal(local)
    val freedHere = (0 until retireWidth).map { offset =>
      freeChain(offset + 1) && freedLocal(offset) === local.U
    }.reduce(_ || _)
    val recoveryHere = recoveryApply && recoveryTargetStid === stid.U

    when(publishHere && groupHits.orR) {
      when(newHit) {
        row.valid := true.B
        row.stid := stid.U
        row.pcBufferIndex := groupIndex(firstGroup)
        row.allocationEpoch := groupEpoch(firstGroup)
        row.pcBase := groupBase(firstGroup)
        row.firstRob := boundGroupFirstRob(firstGroup)
        row.nextCommitValid := true.B
        row.nextCommitPeId := boundGroupFirstRob(firstGroup).peId
        row.nextCommitStid := boundGroupFirstRob(firstGroup).stid
        row.nextCommitRidSlot := boundGroupFirstRob(firstGroup).ridSlot
        row.nextCommitRidGeneration := boundGroupFirstRob(firstGroup).ridGeneration
        row.liveRobGroups := groupHits
        row.closed := explicitClose
        row.closeOwnerValid := explicitClose
        row.closeCommitted := false.B
        for (replica <- 0 until p.ooo.pcReadReplicaCount) {
          val readRow = readRowAt(replica, stid.U, local.U)
          readRow.valid := true.B
          readRow.stid := stid.U
          readRow.pcBufferIndex := groupIndex(firstGroup)
          readRow.allocationEpoch := groupEpoch(firstGroup)
          readRow.pcBase := groupBase(firstGroup)
        }
      }.otherwise {
        row.liveRobGroups := row.liveRobGroups + groupHits
      }
      row.lastRob := boundGroupLastRob(lastGroup)
      when(explicitClose) {
        row.closed := true.B
        row.closeOwnerValid := true.B
        row.closeOwner := closeOwner
        row.closeCommitted := false.B
      }
    }
    when(publishHere && implicitClose) {
      row.closed := true.B
      row.closeOwnerValid := true.B
      row.closeOwner := closeOwner
      row.closeCommitted := false.B
    }
    when(commitHere && commitHits.orR) {
      row.liveRobGroups := row.liveRobGroups - commitHits
      val last = commitLastRobByLocal(local)
      val sum = last.ridSlot +& 1.U
      val wraps = sum >= p.ooo.robGroupsPerStid.U
      row.nextCommitPeId := last.peId
      row.nextCommitStid := last.stid
      row.nextCommitRidSlot := sum(
        InterfaceWidth.index(p.ooo.robGroupsPerStid) - 1, 0)
      row.nextCommitRidGeneration := last.ridGeneration + wraps.asUInt
    }
    when(commitHere && commitClose) { row.closeCommitted := true.B }
    when(commitHere && freedHere) {
      row.valid := false.B
      row.closed := false.B
      row.closeOwnerValid := false.B
      row.closeCommitted := false.B
      for (replica <- 0 until p.ooo.pcReadReplicaCount) {
        readRowAt(replica, stid.U, local.U).valid := false.B
      }
    }
    when(recoveryHere && recoveryRepairMask(local)) {
      row.liveRobGroups := recoveryRepairLive(local)
      row.lastRob := recoveryPlan.survivingTail
    }
    when(recoveryHere && recoveryReopenMask(local)) {
      row.closed := false.B
      row.closeOwnerValid := false.B
      row.closeCommitted := false.B
    }
    when(recoveryHere && recoveryFreeMask(local)) {
      row.valid := false.B
      row.closed := false.B
      row.closeOwnerValid := false.B
      row.closeCommitted := false.B
      for (replica <- 0 until p.ooo.pcReadReplicaCount) {
        readRowAt(replica, stid.U, local.U).valid := false.B
      }
    }
  }

  private val readsPerReplica = p.ooo.pcReadPorts /
    p.ooo.pcReadReplicaCount
  for (port <- 0 until p.ooo.pcReadPorts) {
    val address = io.readAddress(port)
    val stidInRange = address.stid < p.ooo.stidCount.U
    val safeStid = Mux(stidInRange, address.stid, 0.U)
    val local = localFromIndex(address.pcBufferIndex)
    val row = readRowAt(port / readsPerReplica, safeStid, local)
    val partitionExact = address.pcBufferIndex >= partitionBase(safeStid) &&
      address.pcBufferIndex < partitionBase(safeStid) + entriesPerStid.U
    io.readPcBase(port).valid := address.valid && stidInRange &&
      partitionExact && row.valid && row.stid === address.stid &&
      row.pcBufferIndex === address.pcBufferIndex &&
      row.allocationEpoch === address.allocationEpoch
    io.readPcBase(port).bits := row.pcBase
  }
}
