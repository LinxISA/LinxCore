package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Valid}

object OooPcRecoveryScanState extends ChiselEnum {
  val Idle, Scan, Prepared, Rejected = Value
}

class OooPcBufferIO(val p: OooParams = OooParams()) extends Bundle {
  val prepare = Flipped(Valid(new OooD3GroupedReservation(p)))
  val prepared = Output(new OooPcPreparedBindings(p))
  val prepareReady = Output(Bool())
  val publishFire = Input(Bool())
  val commit = Flipped(Decoupled(new OooRobCommitBatch(p)))
  val recoveryPrepare = Flipped(Valid(new OooRobRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Output(new OooPcRecoveryPrepared(p))
  val recoveryFire = Input(Bool())
  val readTokens = Input(Vec(p.pcReadPorts, new PcBufferToken(p)))
  val readValid = Output(Vec(p.pcReadPorts, Bool()))
  val readPc = Output(Vec(p.pcReadPorts, UInt(p.pcWidth.W)))

  val prepareRejected = Valid(new OooPcPrepareReject(p))
  val commitRejected = Valid(new OooPcCommitReject(p))
  val recoveryRejected = Valid(new OooPcRecoveryReject(p))
  val usedBases = Output(Vec(p.stidCount, UInt(p.pcPartitionCountWidth.W)))
  val head = Output(Vec(p.stidCount, new PcBufferToken(p)))
  val currentValid = Output(Vec(p.stidCount, Bool()))
  val current = Output(Vec(p.stidCount, new PcBufferToken(p)))
}

/** Fixed-partition, bank-addressed PC-base buffer.
  *
  * The default 64 rows are four independent 16-row rings. D3 observes a pure
  * prepare result; bases become visible only on the shared S1 publication.
  * Commit consumes exact ROB-group/token prefixes and frees only an ordered
  * partition-head prefix whose close owners have committed. Low local-index
  * bits select one physical address bank and higher bits select its row; the
  * token's global index and allocation epoch remain the public identity.
  */
class OooPcBuffer(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooPcBufferIO(p))

  private val maxOffset = ((BigInt(1) << p.pcOffsetWidth) - 1).U(p.pcWidth.W)
  private def partitionBase(stid: UInt): UInt =
    stid * p.pcEntriesPerStid.U
  private def localIndex(token: PcBufferToken): UInt =
    token.index(p.pcPartitionIndexWidth - 1, 0)

  val table = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.pcBankCount)(
      VecInit(Seq.fill(p.pcRowsPerBank)(
        0.U.asTypeOf(new OooPcBaseEntry(p)))))))))
  private def sizedLocal(local: UInt): UInt =
    local.pad(p.pcPartitionIndexWidth)
  private def pcBankIndex(local: UInt): UInt =
    if (p.pcBankCount == 1) 0.U(p.pcBankIndexWidth.W)
    else sizedLocal(local)(p.pcBankSelectionBits - 1, 0)
  private def pcBankRowIndex(local: UInt): UInt =
    if (p.pcRowsPerBank == 1) 0.U(p.pcBankRowIndexWidth.W)
    else sizedLocal(local)(p.pcPartitionIndexWidth - 1,
      p.pcBankSelectionBits)
  private def rowAt(stid: UInt, local: UInt): OooPcBaseEntry =
    table(stid)(pcBankIndex(local))(pcBankRowIndex(local))
  val usedBases = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.pcPartitionCountWidth.W))))
  val headLocal = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.pcPartitionIndexWidth.W))))
  val headEpoch = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.reservationEpochWidth.W))))
  val tailLocal = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.pcPartitionIndexWidth.W))))
  val tailEpoch = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.reservationEpochWidth.W))))
  val currentValid = RegInit(VecInit(Seq.fill(p.stidCount)(false.B)))
  val currentToken = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U.asTypeOf(new PcBufferToken(p)))))
  val currentBase = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.pcWidth.W))))

  val recoveryScanState = RegInit(OooPcRecoveryScanState.Idle)
  val retainedRecoveryPlan = RegInit(
    0.U.asTypeOf(new OooRobRecoveryPlan(p)))
  val retainedRecoveryPrepared = RegInit(
    0.U.asTypeOf(new OooPcRecoveryPrepared(p)))
  val retainedRecoveryReject = RegInit(
    0.U.asTypeOf(new OooPcRecoveryReject(p)))
  val recoveryScanCursor = RegInit(0.U(p.pcRecoveryScanCursorWidth.W))
  val recoveryHitCounts = RegInit(VecInit(Seq.fill(p.pcEntriesPerStid)(
    0.U(p.nonFlushPrefixCountWidth.W))))
  val recoveryPriorKeys = RegInit(VecInit(Seq.fill(p.pcEntriesPerStid)(
    0.U.asTypeOf(new RobGroupKey(p)))))
  val recoveryCloseOwnerKilledMask = RegInit(0.U(p.pcEntriesPerStid.W))
  val recoveryFreedRowMask = RegInit(0.U(p.pcEntriesPerStid.W))
  val recoveryFreedBasesReg = RegInit(0.U(p.nonFlushPrefixCountWidth.W))
  val recoveryFirstAllocatedValid = RegInit(false.B)
  val recoveryFirstAllocated = RegInit(
    0.U.asTypeOf(new PcBufferToken(p)))
  val recoveryKilledRowsExactReg = RegInit(true.B)
  val recoveryAllocatedOrderExactReg = RegInit(true.B)
  val recoverySnapshotUsedBases = RegInit(0.U(p.pcPartitionCountWidth.W))
  val recoverySnapshotTail = RegInit(
    0.U.asTypeOf(new PcBufferToken(p)))

  val recoveryPlan = Mux(
    recoveryScanState === OooPcRecoveryScanState.Idle,
    io.recoveryPrepare.bits,
    retainedRecoveryPlan)
  val recoveryStid = recoveryPlan.request.rename.key.member.group.stid
  val recoveryStidInRange = recoveryStid < p.stidCount.U
  val safeRecoveryStid = Mux(recoveryStidInRange, recoveryStid, 0.U)
  val recoveryOfferExact = io.recoveryPrepare.valid &&
    io.recoveryPrepare.bits.asUInt === recoveryPlan.asUInt
  val recoveryTargetsStid =
    (io.recoveryPrepare.valid ||
      recoveryScanState =/= OooPcRecoveryScanState.Idle) &&
      recoveryPlan.valid && recoveryStidInRange
  val recoveryCommitConflict = WireDefault(false.B)

  val prepareStid = io.prepare.bits.transaction.plan.stid
  val prepareStidInRange = prepareStid < p.stidCount.U
  val safePrepareStid = Mux(prepareStidInRange, prepareStid, 0.U)
  val groupCount = io.prepare.bits.transaction.plan.groupCount
  val groupCountInRange = groupCount.orR && groupCount <= p.instructionDecodeWidth.U
  val expectedGroupMask =
    ((1.U((p.instructionDecodeWidth + 1).W) << groupCount) - 1.U)(
      p.instructionDecodeWidth - 1, 0)

  val groupHasPc = Wire(Vec(p.instructionDecodeWidth, Bool()))
  val groupMinPc = Wire(Vec(p.instructionDecodeWidth, UInt(p.pcWidth.W)))
  val groupMaxPc = Wire(Vec(p.instructionDecodeWidth, UInt(p.pcWidth.W)))
  val groupParentsExact = Wire(Vec(p.instructionDecodeWidth, Bool()))
  for (groupIndex <- 0 until p.instructionDecodeWidth) {
    val seen = Wire(Vec(p.decodedUopWidth * p.maxArchitecturalParentRefs + 1, Bool()))
    val minima = Wire(Vec(p.decodedUopWidth * p.maxArchitecturalParentRefs + 1,
      UInt(p.pcWidth.W)))
    val maxima = Wire(Vec(p.decodedUopWidth * p.maxArchitecturalParentRefs + 1,
      UInt(p.pcWidth.W)))
    seen(0) := false.B
    minima(0) := 0.U
    maxima(0) := 0.U
    for (uopIndex <- 0 until p.decodedUopWidth;
         parentIndex <- 0 until p.maxArchitecturalParentRefs) {
      val flatIndex = uopIndex * p.maxArchitecturalParentRefs + parentIndex
      val uop = io.prepare.bits.transaction.decoded.uops(uopIndex)
      val parent = uop.identity.parents(parentIndex)
      val hit = io.prepare.bits.transaction.groups(groupIndex).logicalUopMask(uopIndex) &&
        parentIndex.U < uop.identity.parentCount && parent.key.valid
      seen(flatIndex + 1) := seen(flatIndex) || hit
      minima(flatIndex + 1) := Mux(
        !seen(flatIndex),
        Mux(hit, parent.pc, minima(flatIndex)),
        Mux(hit && parent.pc < minima(flatIndex), parent.pc, minima(flatIndex)))
      maxima(flatIndex + 1) := Mux(
        hit && (!seen(flatIndex) || parent.pc > maxima(flatIndex)),
        parent.pc,
        maxima(flatIndex))
    }
    groupHasPc(groupIndex) := seen.last
    groupMinPc(groupIndex) := minima.last
    groupMaxPc(groupIndex) := maxima.last
    groupParentsExact(groupIndex) := (0 until p.decodedUopWidth).flatMap { uopIndex =>
      (0 until p.maxArchitecturalParentRefs).map { parentIndex =>
        val uop = io.prepare.bits.transaction.decoded.uops(uopIndex)
        val parent = uop.identity.parents(parentIndex)
        val hit = io.prepare.bits.transaction.groups(groupIndex).logicalUopMask(uopIndex) &&
          parentIndex.U < uop.identity.parentCount
        !hit || (parent.key.valid &&
          parent.key.peId === io.prepare.bits.transaction.plan.peId &&
          parent.key.stid === prepareStid &&
          parent.key.epoch === io.prepare.bits.transaction.plan.epoch)
      }
    }.reduce(_ && _)
  }

  val scanValid = Wire(Vec(p.instructionDecodeWidth + 1, Bool()))
  val scanToken = Wire(Vec(p.instructionDecodeWidth + 1, new PcBufferToken(p)))
  val scanBase = Wire(Vec(p.instructionDecodeWidth + 1, UInt(p.pcWidth.W)))
  val scanAllocCount = Wire(Vec(p.instructionDecodeWidth + 1, UInt(p.robGroupCountWidth.W)))
  val scanLegal = Wire(Vec(p.instructionDecodeWidth, Bool()))
  val groupTokens = Wire(Vec(p.instructionDecodeWidth, new PcBufferToken(p)))
  val groupBases = Wire(Vec(p.instructionDecodeWidth, UInt(p.pcWidth.W)))
  val newBase = Wire(Vec(p.instructionDecodeWidth, Bool()))
  val implicitClose = Wire(Vec(p.instructionDecodeWidth, Bool()))
  val implicitCloseTokens = Wire(Vec(p.instructionDecodeWidth, new PcBufferToken(p)))

  scanValid(0) := currentValid(safePrepareStid)
  scanToken(0) := currentToken(safePrepareStid)
  scanBase(0) := currentBase(safePrepareStid)
  scanAllocCount(0) := 0.U
  for (groupIndex <- 0 until p.instructionDecodeWidth) {
    val group = io.prepare.bits.transaction.groups(groupIndex)
    val active = groupIndex.U < groupCount
    val currentFits = groupHasPc(groupIndex) &&
      groupMinPc(groupIndex) >= scanBase(groupIndex) &&
      groupMaxPc(groupIndex) - scanBase(groupIndex) <= maxOffset
    val allocate = active &&
      (!scanValid(groupIndex) || !currentFits || group.preciseTrap)
    val tailSum = tailLocal(safePrepareStid) +& scanAllocCount(groupIndex)
    val tailWrap = tailSum >= p.pcEntriesPerStid.U
    val allocatedToken = Wire(new PcBufferToken(p))
    allocatedToken.valid := true.B
    allocatedToken.index :=
      partitionBase(safePrepareStid) + tailSum(p.pcPartitionIndexWidth - 1, 0)
    allocatedToken.byteOffset := 0.U
    allocatedToken.allocationEpoch := tailEpoch(safePrepareStid) + tailWrap.asUInt

    newBase(groupIndex) := allocate
    implicitClose(groupIndex) := allocate && scanValid(groupIndex)
    implicitCloseTokens(groupIndex) := scanToken(groupIndex)
    groupTokens(groupIndex) := Mux(allocate, allocatedToken, scanToken(groupIndex))
    groupBases(groupIndex) := Mux(allocate, groupMinPc(groupIndex), scanBase(groupIndex))
    val spanFits = groupHasPc(groupIndex) &&
      groupMaxPc(groupIndex) - groupMinPc(groupIndex) <= maxOffset
    scanLegal(groupIndex) := Mux(
      active,
      group.valid && group.key.valid &&
        group.key.peId === io.prepare.bits.transaction.plan.peId &&
        group.key.stid === prepareStid && spanFits &&
        groupParentsExact(groupIndex) &&
        groupTokens(groupIndex).valid,
      !group.valid)
    val closes = group.releasePcBase || group.preciseTrap
    scanValid(groupIndex + 1) := Mux(active, !closes, scanValid(groupIndex))
    scanToken(groupIndex + 1) := Mux(active, groupTokens(groupIndex), scanToken(groupIndex))
    scanBase(groupIndex + 1) := Mux(active, groupBases(groupIndex), scanBase(groupIndex))
    scanAllocCount(groupIndex + 1) := scanAllocCount(groupIndex) + allocate.asUInt
  }

  val allocatedBases = scanAllocCount.last
  val freeBases = p.pcEntriesPerStid.U(p.pcPartitionCountWidth.W) -
    usedBases(safePrepareStid)
  val hasCapacity = freeBases >= allocatedBases
  val writePortsFit = allocatedBases <= p.pcWritePorts.U
  val newTargetsFree = (0 until p.instructionDecodeWidth).map { groupIndex =>
    !newBase(groupIndex) ||
      !rowAt(safePrepareStid, localIndex(groupTokens(groupIndex))).valid
  }.reduce(_ && _)
  val liveCurrentExact = !currentValid(safePrepareStid) || {
    val row = rowAt(safePrepareStid, localIndex(currentToken(safePrepareStid)))
    row.valid && row.token.index === currentToken(safePrepareStid).index &&
      row.token.allocationEpoch === currentToken(safePrepareStid).allocationEpoch &&
      row.stid === prepareStid && row.base === currentBase(safePrepareStid)
  }
  val uopMappingExact = (0 until p.decodedUopWidth).map { uopIndex =>
    val active = io.prepare.bits.transaction.decoded.uopMask(uopIndex)
    val uop = io.prepare.bits.transaction.decoded.uops(uopIndex)
    val requestedGroup = io.prepare.bits.transaction.uopGroupIndex(uopIndex)
    val groupInRange = requestedGroup < groupCount &&
      requestedGroup < p.instructionDecodeWidth.U
    val safeGroup = Mux(groupInRange, requestedGroup, 0.U)
    val targetGroup = io.prepare.bits.transaction.groups(safeGroup)
    val maskInverseExact = (0 until p.instructionDecodeWidth).map { groupIndex =>
      io.prepare.bits.transaction.groups(groupIndex).logicalUopMask(uopIndex) ===
        (active && requestedGroup === groupIndex.U)
    }.reduce(_ && _)
    val parentsFit = (0 until p.maxArchitecturalParentRefs).map { parentIndex =>
      val parent = uop.identity.parents(parentIndex)
      val parentActive = active && parentIndex.U < uop.identity.parentCount
      !parentActive || (parent.key.valid && parent.pc >= groupBases(safeGroup) &&
        parent.pc - groupBases(safeGroup) <= maxOffset)
    }.reduce(_ && _)
    uop.valid === active && (!active ||
      (groupInRange && targetGroup.valid && targetGroup.logicalUopMask(uopIndex) &&
        maskInverseExact && parentsFit))
  }.reduce(_ && _)

  val commitStid = io.commit.bits.release.firstGroup.stid
  val exactCommitConflict = Wire(Bool())
  val prepareExact = prepareStidInRange && groupCountInRange &&
    io.prepare.bits.transaction.groupMask === expectedGroupMask &&
    io.prepare.bits.transaction.plan.peId === io.prepare.bits.transaction.decoded.peId &&
    io.prepare.bits.transaction.plan.stid === io.prepare.bits.transaction.decoded.stid &&
    scanLegal.reduce(_ && _) && uopMappingExact && hasCapacity && writePortsFit &&
    newTargetsFree && liveCurrentExact

  io.prepared := 0.U.asTypeOf(io.prepared)
  io.prepared.validMask := expectedGroupMask
  io.prepared.groupTokens := groupTokens
  io.prepared.newBaseMask := newBase.asUInt
  io.prepared.newBases := groupBases
  io.prepared.implicitCloseMask := implicitClose.asUInt
  io.prepared.implicitCloseTokens := implicitCloseTokens
  io.prepared.allocatedBases := allocatedBases
  io.prepared.currentAfterValid := scanValid.last
  io.prepared.currentAfter := scanToken.last
  for (uopIndex <- 0 until p.decodedUopWidth;
       parentIndex <- 0 until p.maxArchitecturalParentRefs) {
    val requestedGroup = io.prepare.bits.transaction.uopGroupIndex(uopIndex)
    val groupInRange = requestedGroup < groupCount &&
      requestedGroup < p.instructionDecodeWidth.U
    val groupIndex = Mux(groupInRange, requestedGroup, 0.U)
    val parent = io.prepare.bits.transaction.decoded.uops(uopIndex).identity.parents(parentIndex)
    val token = io.prepared.parentTokens(uopIndex)(parentIndex)
    val parentValid = io.prepare.bits.transaction.decoded.uopMask(uopIndex) &&
      parentIndex.U < io.prepare.bits.transaction.decoded.uops(uopIndex).identity.parentCount &&
      parent.key.valid
    token.valid := parentValid && groupInRange
    token.index := groupTokens(groupIndex).index
    token.byteOffset := Mux(parentValid && groupInRange,
      parent.pc - groupBases(groupIndex), 0.U)
    token.allocationEpoch := groupTokens(groupIndex).allocationEpoch
  }
  val recoveryBlocksPrepare = recoveryTargetsStid &&
    prepareStid === recoveryStid
  io.prepareReady := prepareExact && !exactCommitConflict &&
    !recoveryBlocksPrepare
  io.prepareRejected.valid := io.prepare.valid && !io.prepareReady
  io.prepareRejected.bits.stid := prepareStid
  io.prepareRejected.bits.transactionId := io.prepare.bits.transaction.plan.transactionId
  io.prepareRejected.bits.groupMask := io.prepare.bits.transaction.groupMask

  val commitCount = io.commit.bits.release.groupCount
  val commitStidInRange = commitStid < p.stidCount.U
  val safeCommitStid = Mux(commitStidInRange, commitStid, 0.U)
  val commitCountInRange = commitCount.orR && commitCount <= p.retireGroupWidth.U
  val headToken = Wire(new PcBufferToken(p))
  headToken.valid := usedBases(safeCommitStid).orR
  headToken.index := partitionBase(safeCommitStid) + headLocal(safeCommitStid)
  headToken.byteOffset := 0.U
  headToken.allocationEpoch := headEpoch(safeCommitStid)

  val commitGroupExact = Wire(Vec(p.retireGroupWidth, Bool()))
  for (groupIndex <- 0 until p.retireGroupWidth) {
    val active = groupIndex.U < commitCount
    val group = io.commit.bits.groups(groupIndex)
    val row = rowAt(safeCommitStid, localIndex(group.pcBase))
    val liveExact = group.valid && group.key.valid && group.key.stid === commitStid &&
      group.pcBase.valid && row.valid && row.stid === commitStid &&
      row.token.asUInt === group.pcBase.asUInt
    val startsEntry = if (groupIndex == 0) true.B else
      group.pcBase.index =/= io.commit.bits.groups(groupIndex - 1).pcBase.index ||
        group.pcBase.allocationEpoch =/=
          io.commit.bits.groups(groupIndex - 1).pcBase.allocationEpoch
    val entryPrefixExact = !startsEntry ||
      (row.nextCommitRobGroup.valid && row.nextCommitRobGroup.asUInt === group.key.asUInt)
    val ridSequential = if (groupIndex == 0) true.B else {
      val prior = io.commit.bits.groups(groupIndex - 1).key
      val sum = prior.ridSlot +& 1.U
      val wrap = sum >= p.robGroupsPerStid.U
      group.key.peId === prior.peId && group.key.stid === prior.stid &&
        group.key.ridSlot === sum(p.ridSlotWidth - 1, 0) &&
        group.key.ridGeneration === prior.ridGeneration + wrap.asUInt
    }
    val orderExact = if (groupIndex == 0) {
      val headRow = rowAt(safeCommitStid, headLocal(safeCommitStid))
      val nextSum = headLocal(safeCommitStid) +& 1.U
      val nextWrap = nextSum >= p.pcEntriesPerStid.U
      val closesEmptyHead = headRow.valid && headRow.closed &&
        headRow.liveRobGroups === 0.U && headRow.closeOwnerValid &&
        headRow.closeOwner.asUInt === group.key.asUInt &&
        group.pcBase.index === partitionBase(safeCommitStid) +
          nextSum(p.pcPartitionIndexWidth - 1, 0) &&
        group.pcBase.allocationEpoch === headEpoch(safeCommitStid) + nextWrap.asUInt
      (group.pcBase.index === headToken.index &&
        group.pcBase.allocationEpoch === headToken.allocationEpoch) || closesEmptyHead
    } else {
      val prior = io.commit.bits.groups(groupIndex - 1).pcBase
      val priorGroup = io.commit.bits.groups(groupIndex - 1)
      val sameBase = group.pcBase.asUInt === prior.asUInt &&
        !priorGroup.releasePcBase && !priorGroup.preciseTrap
      val priorLocal = localIndex(prior)
      val nextSum = priorLocal +& 1.U
      val nextWrap = nextSum >= p.pcEntriesPerStid.U
      val nextBase = group.pcBase.index === partitionBase(safeCommitStid) +
        nextSum(p.pcPartitionIndexWidth - 1, 0) &&
        group.pcBase.allocationEpoch === prior.allocationEpoch + nextWrap.asUInt
      sameBase || nextBase
    }
    commitGroupExact(groupIndex) := Mux(
      active,
      liveExact && entryPrefixExact && ridSequential && orderExact,
      !group.valid)
  }

  val hitsByLocal = Wire(Vec(p.pcEntriesPerStid, UInt(p.robReleaseCountWidth.W)))
  for (local <- 0 until p.pcEntriesPerStid) {
    val row = rowAt(safeCommitStid, local.U)
    hitsByLocal(local) := PopCount((0 until p.retireGroupWidth).map { groupIndex =>
      groupIndex.U < commitCount && localIndex(io.commit.bits.groups(groupIndex).pcBase) === local.U &&
        io.commit.bits.groups(groupIndex).pcBase.allocationEpoch ===
          row.token.allocationEpoch
    })
  }
  val countsLegal = (0 until p.pcEntriesPerStid).map { local =>
    hitsByLocal(local) <= rowAt(safeCommitStid, local.U).liveRobGroups
  }.reduce(_ && _)
  val freePrefix = Wire(Vec(p.retireGroupWidth + 1, UInt(p.robReleaseCountWidth.W)))
  freePrefix(0) := 0.U
  for (offset <- 0 until p.retireGroupWidth) {
    val sum = headLocal(safeCommitStid) +& offset.U
    val wrap = sum >= p.pcEntriesPerStid.U
    val local = sum(p.pcPartitionIndexWidth - 1, 0)
    val row = rowAt(safeCommitStid, local)
    val closeOwnerCommits = (0 until p.retireGroupWidth).map { groupIndex =>
      groupIndex.U < commitCount && row.closeOwnerValid &&
        io.commit.bits.groups(groupIndex).key.asUInt === row.closeOwner.asUInt
    }.reduce(_ || _)
    val exact = row.valid && row.token.index ===
      partitionBase(safeCommitStid) + local &&
      row.token.allocationEpoch === headEpoch(safeCommitStid) + wrap.asUInt
    val empty = row.closed && (row.closeCommitted || closeOwnerCommits) &&
      hitsByLocal(local) === row.liveRobGroups
    val extend = freePrefix(offset) === offset.U && exact && empty
    freePrefix(offset + 1) := Mux(extend, (offset + 1).U, freePrefix(offset))
  }
  val freedBases = freePrefix.last
  val releaseHeaderExact = io.commit.bits.release.firstGroup.valid &&
    io.commit.bits.groups(0).valid &&
    io.commit.bits.release.firstGroup.asUInt === io.commit.bits.groups(0).key.asUInt
  val commitExact = commitStidInRange && commitCountInRange &&
    usedBases(safeCommitStid).orR && commitGroupExact.reduce(_ && _) &&
    countsLegal && releaseHeaderExact
  recoveryCommitConflict := io.commit.valid && commitExact &&
    commitStid === recoveryStid

  // Recovery preparation walks a fixed number of ROB-group records per cycle.
  // Every result below is private until all slices validate; common recoveryFire
  // remains the sole event that mutates PC rows or partition pointers.
  val recoveryExpectedKilledMask =
    ((1.U((p.robGroupsPerStid + 1).W) << recoveryPlan.killedGroupCount) - 1.U)(
      p.robGroupsPerStid - 1, 0)
  val recoveryCaptureShapeExact = recoveryPlan.valid &&
    recoveryStidInRange && recoveryPlan.oldOccupied.orR &&
    recoveryPlan.oldOccupied <= p.robGroupsPerStid.U &&
    recoveryPlan.newOccupied <= recoveryPlan.oldOccupied &&
    recoveryPlan.killedGroupCount <= p.robGroupsPerStid.U &&
    recoveryPlan.killedGroupCount ===
      recoveryPlan.oldOccupied - recoveryPlan.newOccupied &&
    recoveryPlan.killedGroupMask === recoveryExpectedKilledMask &&
    recoveryPlan.survivingTailValid === recoveryPlan.newOccupied.orR &&
    (!recoveryPlan.survivingTailValid ||
      (recoveryPlan.survivingTail.valid &&
        recoveryPlan.survivingTail.key.valid &&
        recoveryPlan.survivingTail.key.stid === recoveryStid &&
        recoveryPlan.survivingTail.pcBase.valid))
  val recoveryCaptureMalformed = io.recoveryPrepare.valid &&
    recoveryScanState === OooPcRecoveryScanState.Idle &&
    !recoveryCaptureShapeExact
  val recoveryOfferChanged =
    recoveryScanState =/= OooPcRecoveryScanState.Idle &&
      recoveryScanState =/= OooPcRecoveryScanState.Rejected &&
      io.recoveryPrepare.valid && !recoveryOfferExact

  val recoveryScanGroups = Wire(Vec(p.pcRecoveryScanGroupsPerCycle,
    new OooRobPhysicalGroupRecord(p)))
  val recoveryScanActive = Wire(Vec(p.pcRecoveryScanGroupsPerCycle, Bool()))
  val recoveryScanAllocated = Wire(Vec(p.pcRecoveryScanGroupsPerCycle, Bool()))
  val recoveryScanRowsExact = Wire(Vec(p.pcRecoveryScanGroupsPerCycle, Bool()))
  for (scanLane <- 0 until p.pcRecoveryScanGroupsPerCycle) {
    val offsetWide = recoveryScanCursor *
      p.pcRecoveryScanGroupsPerCycle.U + scanLane.U
    val offset = Wire(UInt(p.nonFlushPrefixCountWidth.W))
    offset := offsetWide
    val group = recoveryPlan.killedGroups(offset(p.ridSlotWidth - 1, 0))
    val active = offset < recoveryPlan.killedGroupCount
    val row = rowAt(safeRecoveryStid, localIndex(group.pcBase))
    val implicitRow = rowAt(safeRecoveryStid,
      localIndex(group.pcImplicitClose))
    recoveryScanGroups(scanLane) := group
    recoveryScanActive(scanLane) := active
    recoveryScanAllocated(scanLane) := active && group.pcBaseAllocated
    recoveryScanRowsExact(scanLane) :=
      recoveryPlan.killedGroupMask(offset(p.ridSlotWidth - 1, 0)) === active &&
        (!active || (group.valid && group.key.valid &&
          group.key.stid === recoveryStid && group.pcBase.valid &&
          row.valid && row.stid === recoveryStid &&
          row.token.asUInt === group.pcBase.asUInt &&
          (!group.pcBaseAllocated ||
            row.firstRobGroup.asUInt === group.key.asUInt) &&
          (!group.pcImplicitCloseValid ||
            (group.pcBaseAllocated && implicitRow.valid &&
              implicitRow.stid === recoveryStid &&
              implicitRow.token.asUInt === group.pcImplicitClose.asUInt))))
  }
  val recoverySliceAllocatedCount = PopCount(recoveryScanAllocated)
  val recoverySliceHasAllocated = recoveryScanAllocated.asUInt.orR
  val recoverySliceFirstAllocatedIndex =
    if (p.pcRecoveryScanGroupsPerCycle == 1) 0.U
    else PriorityEncoder(recoveryScanAllocated.asUInt)
  val recoveryFirstAllocatedCandidate = Mux(
    recoveryFirstAllocatedValid,
    recoveryFirstAllocated,
    recoveryScanGroups(recoverySliceFirstAllocatedIndex).pcBase)
  val recoveryFirstAllocatedValidNext = recoveryFirstAllocatedValid ||
    recoverySliceHasAllocated
  val recoveryFreedBasesNext = recoveryFreedBasesReg +
    recoverySliceAllocatedCount
  val recoverySliceAllocatedOrderExact = Wire(Vec(
    p.pcRecoveryScanGroupsPerCycle, Bool()))
  for (scanLane <- 0 until p.pcRecoveryScanGroupsPerCycle) {
    val priorInSlice = if (scanLane == 0) 0.U else
      PopCount(recoveryScanAllocated.take(scanLane))
    val allocationOrdinal = recoveryFreedBasesReg + priorInSlice
    val expectedSum = localIndex(recoveryFirstAllocatedCandidate) +&
      allocationOrdinal
    val expectedWrap = expectedSum >= p.pcEntriesPerStid.U
    val group = recoveryScanGroups(scanLane)
    recoverySliceAllocatedOrderExact(scanLane) :=
      !recoveryScanAllocated(scanLane) ||
        (localIndex(group.pcBase) ===
          expectedSum(p.pcPartitionIndexWidth - 1, 0) &&
          group.pcBase.index === partitionBase(safeRecoveryStid) +
            expectedSum(p.pcPartitionIndexWidth - 1, 0) &&
          group.pcBase.allocationEpoch ===
            recoveryFirstAllocatedCandidate.allocationEpoch +
              expectedWrap.asUInt)
  }
  val recoveryAllocatedOrderExactNext =
    recoveryAllocatedOrderExactReg &&
      recoverySliceAllocatedOrderExact.asUInt.andR
  val recoveryKilledRowsExactNext = recoveryKilledRowsExactReg &&
    recoveryScanRowsExact.asUInt.andR

  val recoveryHitCountsNext = Wire(Vec(p.pcEntriesPerStid,
    UInt(p.nonFlushPrefixCountWidth.W)))
  val recoveryPriorKeysNext = Wire(Vec(p.pcEntriesPerStid,
    new RobGroupKey(p)))
  val recoverySliceCloseOwnerKilled = Wire(Vec(p.pcEntriesPerStid, Bool()))
  val recoverySliceFreedRows = Wire(Vec(p.pcEntriesPerStid, Bool()))
  val recoveryPivotReopens = Wire(Vec(p.pcEntriesPerStid, Bool()))
  val recoveryHitCountsLegal = Wire(Vec(p.pcEntriesPerStid, Bool()))
  for (local <- 0 until p.pcEntriesPerStid) {
    val row = rowAt(safeRecoveryStid, local.U)
    val laneHits = VecInit((0 until p.pcRecoveryScanGroupsPerCycle).map {
      scanLane => recoveryScanActive(scanLane) &&
        localIndex(recoveryScanGroups(scanLane).pcBase) === local.U &&
        recoveryScanGroups(scanLane).pcBase.allocationEpoch ===
          row.token.allocationEpoch
    })
    val sliceHits = PopCount(laneHits)
    recoveryHitCountsNext(local) := recoveryHitCounts(local) + sliceHits
    val firstLane = if (p.pcRecoveryScanGroupsPerCycle == 1) 0.U
      else PriorityEncoder(laneHits.asUInt)
    val firstKilledKey = recoveryScanGroups(firstLane).key
    val priorKey = Wire(new RobGroupKey(p))
    priorKey.valid := laneHits.asUInt.orR
    priorKey.peId := firstKilledKey.peId
    priorKey.stid := firstKilledKey.stid
    priorKey.ridSlot := Mux(firstKilledKey.ridSlot === 0.U,
      (p.robGroupsPerStid - 1).U,
      firstKilledKey.ridSlot - 1.U)
    priorKey.ridGeneration := Mux(firstKilledKey.ridSlot === 0.U,
      firstKilledKey.ridGeneration - 1.U,
      firstKilledKey.ridGeneration)
    recoveryPriorKeysNext(local) := Mux(
      recoveryHitCounts(local).orR,
      recoveryPriorKeys(local),
      priorKey)
    recoverySliceCloseOwnerKilled(local) := row.closeOwnerValid &&
      (0 until p.pcRecoveryScanGroupsPerCycle).map { scanLane =>
        recoveryScanActive(scanLane) &&
          recoveryScanGroups(scanLane).key.asUInt === row.closeOwner.asUInt
      }.reduce(_ || _)
    recoverySliceFreedRows(local) :=
      (0 until p.pcRecoveryScanGroupsPerCycle).map { scanLane =>
        recoveryScanAllocated(scanLane) &&
          localIndex(recoveryScanGroups(scanLane).pcBase) === local.U &&
          recoveryScanGroups(scanLane).pcBase.allocationEpoch ===
            row.token.allocationEpoch
      }.reduce(_ || _)
    recoveryPivotReopens(local) := row.closeOwnerValid &&
      recoveryPlan.survivingPivotValid &&
      (recoveryPlan.pivot.releasePcBase ||
        recoveryPlan.pivot.preciseTrap) &&
      !(recoveryPlan.survivingPivot.releasePcBase ||
        recoveryPlan.survivingPivot.preciseTrap) &&
      recoveryPlan.pivot.key.asUInt === row.closeOwner.asUInt
    recoveryHitCountsLegal(local) :=
      recoveryHitCountsNext(local) <= row.liveRobGroups
  }
  val recoveryCloseOwnerKilledMaskNext =
    recoveryCloseOwnerKilledMask |
      recoverySliceCloseOwnerKilled.asUInt | recoveryPivotReopens.asUInt
  val recoveryFreedRowMaskNext = recoveryFreedRowMask |
    recoverySliceFreedRows.asUInt
  val recoveryScanLast = recoveryScanCursor ===
    (p.pcRecoveryScanCycles - 1).U

  val recoveryTailEndSum = localIndex(recoveryFirstAllocatedCandidate) +&
    recoveryFreedBasesNext
  val recoveryTailEndWrap = recoveryTailEndSum >= p.pcEntriesPerStid.U
  val recoveryTailSuffixExact = Mux(
    recoveryFreedBasesNext.orR,
    recoveryFirstAllocatedValidNext && recoveryAllocatedOrderExactNext &&
      recoverySnapshotTail.index === partitionBase(safeRecoveryStid) +
        recoveryTailEndSum(p.pcPartitionIndexWidth - 1, 0) &&
      recoverySnapshotTail.allocationEpoch ===
        recoveryFirstAllocatedCandidate.allocationEpoch +
          recoveryTailEndWrap.asUInt &&
      recoverySnapshotUsedBases >= recoveryFreedBasesNext,
    true.B)
  val recoveryTailAfter = Mux(
    recoveryFreedBasesNext.orR,
    recoveryFirstAllocatedCandidate,
    recoverySnapshotTail)
  val recoveryFirstKilled = recoveryPlan.killedGroups(0)
  val recoveryCurrentAfterValid = Mux(
    recoveryPlan.survivingTailValid,
    !(recoveryPlan.survivingTail.releasePcBase ||
      recoveryPlan.survivingTail.preciseTrap),
    Mux(recoveryFirstKilled.pcBaseAllocated,
      recoveryFirstKilled.pcImplicitCloseValid,
      true.B))
  val recoveryCurrentAfter = Mux(
    recoveryPlan.survivingTailValid,
    recoveryPlan.survivingTail.pcBase,
    Mux(recoveryFirstKilled.pcBaseAllocated,
      recoveryFirstKilled.pcImplicitClose,
      recoveryFirstKilled.pcBase))
  val recoveryCurrentRow = rowAt(safeRecoveryStid,
    localIndex(recoveryCurrentAfter))
  val recoveryCurrentAfterExact = !recoveryCurrentAfterValid ||
    (recoveryCurrentAfter.valid && recoveryCurrentRow.valid &&
      recoveryCurrentRow.stid === recoveryStid &&
      recoveryCurrentRow.token.asUInt === recoveryCurrentAfter.asUInt)
  val recoveryCurrentBaseAfter = Mux(
    recoveryCurrentAfterValid,
    recoveryCurrentRow.base,
    0.U)
  val recoveryScanExact = recoveryKilledRowsExactNext &&
    recoveryAllocatedOrderExactNext && recoveryTailSuffixExact &&
    recoveryCurrentAfterExact && recoveryHitCountsLegal.asUInt.andR
  val recoveryScanRejected =
    recoveryScanState === OooPcRecoveryScanState.Scan &&
      recoveryScanLast && !recoveryScanExact

  io.recoveryPrepareReady :=
    recoveryScanState === OooPcRecoveryScanState.Prepared &&
      recoveryOfferExact && retainedRecoveryPrepared.valid &&
      !recoveryCommitConflict
  io.recoveryPrepared := retainedRecoveryPrepared
  io.recoveryPrepared.valid := io.recoveryPrepareReady
  io.recoveryRejected.valid := recoveryCaptureMalformed ||
    recoveryOfferChanged || recoveryScanRejected ||
    (recoveryScanState === OooPcRecoveryScanState.Rejected &&
      io.recoveryPrepare.valid)
  io.recoveryRejected.bits := retainedRecoveryReject
  when(recoveryCaptureMalformed) {
    io.recoveryRejected.bits.requested := io.recoveryPrepare.bits
    io.recoveryRejected.bits.liveTail.valid := true.B
    io.recoveryRejected.bits.liveTail.index :=
      partitionBase(safeRecoveryStid) + tailLocal(safeRecoveryStid)
    io.recoveryRejected.bits.liveTail.byteOffset := 0.U
    io.recoveryRejected.bits.liveTail.allocationEpoch :=
      tailEpoch(safeRecoveryStid)
    io.recoveryRejected.bits.usedBases := usedBases(safeRecoveryStid)
    io.recoveryRejected.bits.killedRowsExact := false.B
    io.recoveryRejected.bits.tailSuffixExact := false.B
    io.recoveryRejected.bits.currentAfterExact := false.B
  }.elsewhen(recoveryOfferChanged) {
    io.recoveryRejected.bits.requested := recoveryPlan
    io.recoveryRejected.bits.liveTail := recoverySnapshotTail
    io.recoveryRejected.bits.usedBases := recoverySnapshotUsedBases
    io.recoveryRejected.bits.killedRowsExact := false.B
    io.recoveryRejected.bits.tailSuffixExact := false.B
    io.recoveryRejected.bits.currentAfterExact := false.B
  }.elsewhen(recoveryScanRejected) {
    io.recoveryRejected.bits.requested := recoveryPlan
    io.recoveryRejected.bits.liveTail := recoverySnapshotTail
    io.recoveryRejected.bits.usedBases := recoverySnapshotUsedBases
    io.recoveryRejected.bits.killedRowsExact := recoveryKilledRowsExactNext
    io.recoveryRejected.bits.tailSuffixExact := recoveryTailSuffixExact
    io.recoveryRejected.bits.currentAfterExact := recoveryCurrentAfterExact
  }

  when(recoveryScanState === OooPcRecoveryScanState.Idle &&
      io.recoveryPrepare.valid && recoveryCaptureShapeExact &&
      !recoveryCommitConflict) {
    retainedRecoveryPlan := io.recoveryPrepare.bits
    retainedRecoveryPrepared :=
      0.U.asTypeOf(retainedRecoveryPrepared)
    retainedRecoveryReject := 0.U.asTypeOf(retainedRecoveryReject)
    recoveryScanCursor := 0.U
    recoveryHitCounts.foreach(_ := 0.U)
    recoveryPriorKeys.foreach(_ := 0.U.asTypeOf(new RobGroupKey(p)))
    recoveryCloseOwnerKilledMask := 0.U
    recoveryFreedRowMask := 0.U
    recoveryFreedBasesReg := 0.U
    recoveryFirstAllocatedValid := false.B
    recoveryFirstAllocated := 0.U.asTypeOf(recoveryFirstAllocated)
    recoveryKilledRowsExactReg := true.B
    recoveryAllocatedOrderExactReg := true.B
    recoverySnapshotUsedBases := usedBases(safeRecoveryStid)
    recoverySnapshotTail.valid := true.B
    recoverySnapshotTail.index := partitionBase(safeRecoveryStid) +
      tailLocal(safeRecoveryStid)
    recoverySnapshotTail.byteOffset := 0.U
    recoverySnapshotTail.allocationEpoch := tailEpoch(safeRecoveryStid)
    recoveryScanState := OooPcRecoveryScanState.Scan
  }.elsewhen(recoveryScanState =/= OooPcRecoveryScanState.Idle &&
      !io.recoveryPrepare.valid) {
    recoveryScanState := OooPcRecoveryScanState.Idle
  }.elsewhen(recoveryOfferChanged) {
    retainedRecoveryReject.requested := recoveryPlan
    retainedRecoveryReject.liveTail := recoverySnapshotTail
    retainedRecoveryReject.usedBases := recoverySnapshotUsedBases
    retainedRecoveryReject.killedRowsExact := false.B
    retainedRecoveryReject.tailSuffixExact := false.B
    retainedRecoveryReject.currentAfterExact := false.B
    recoveryScanState := OooPcRecoveryScanState.Rejected
  }.elsewhen(recoveryScanState === OooPcRecoveryScanState.Scan) {
    recoveryHitCounts := recoveryHitCountsNext
    recoveryPriorKeys := recoveryPriorKeysNext
    recoveryCloseOwnerKilledMask := recoveryCloseOwnerKilledMaskNext
    recoveryFreedRowMask := recoveryFreedRowMaskNext
    recoveryFreedBasesReg := recoveryFreedBasesNext
    recoveryFirstAllocatedValid := recoveryFirstAllocatedValidNext
    when(!recoveryFirstAllocatedValid && recoverySliceHasAllocated) {
      recoveryFirstAllocated := recoveryFirstAllocatedCandidate
    }
    recoveryKilledRowsExactReg := recoveryKilledRowsExactNext
    recoveryAllocatedOrderExactReg := recoveryAllocatedOrderExactNext
    when(recoveryScanLast) {
      when(recoveryScanExact) {
        retainedRecoveryPrepared.valid := true.B
        retainedRecoveryPrepared.stid := recoveryStid
        retainedRecoveryPrepared.freedBases := recoveryFreedBasesNext.pad(
          math.max(p.nonFlushPrefixCountWidth, p.pcPartitionCountWidth))(
            p.pcPartitionCountWidth - 1, 0)
        retainedRecoveryPrepared.tailAfter := recoveryTailAfter
        retainedRecoveryPrepared.currentAfterValid :=
          recoveryCurrentAfterValid
        retainedRecoveryPrepared.currentAfter := recoveryCurrentAfter
        retainedRecoveryPrepared.currentBaseAfter :=
          recoveryCurrentBaseAfter
        recoveryScanState := OooPcRecoveryScanState.Prepared
      }.otherwise {
        retainedRecoveryReject.requested := recoveryPlan
        retainedRecoveryReject.liveTail := recoverySnapshotTail
        retainedRecoveryReject.usedBases := recoverySnapshotUsedBases
        retainedRecoveryReject.killedRowsExact :=
          recoveryKilledRowsExactNext
        retainedRecoveryReject.tailSuffixExact := recoveryTailSuffixExact
        retainedRecoveryReject.currentAfterExact :=
          recoveryCurrentAfterExact
        recoveryScanState := OooPcRecoveryScanState.Rejected
      }
    }.otherwise {
      recoveryScanCursor := recoveryScanCursor + 1.U
    }
  }.elsewhen(recoveryScanState === OooPcRecoveryScanState.Prepared &&
      io.recoveryFire) {
    recoveryScanState := OooPcRecoveryScanState.Idle
  }
  io.commit.ready := commitExact &&
    !(recoveryTargetsStid && commitStid === recoveryStid)
  val commitFire = io.commit.valid && io.commit.ready
  exactCommitConflict := io.commit.valid && commitExact && prepareStidInRange &&
    commitStid === prepareStid
  io.commitRejected.valid := io.commit.valid && !io.commit.ready
  io.commitRejected.bits.requested := io.commit.bits
  io.commitRejected.bits.head := headToken

  val publishFire = io.publishFire && io.prepare.valid && io.prepareReady
  when(io.publishFire) {
    assert(io.prepare.valid && io.prepareReady,
      "PC-buffer publish must use the exact accepted prepare view")
  }

  for (stid <- 0 until p.stidCount) {
    val publishHere = publishFire && prepareStid === stid.U
    val commitHere = commitFire && commitStid === stid.U
    when(publishHere) {
      val sum = tailLocal(stid) +& allocatedBases
      val wrap = sum >= p.pcEntriesPerStid.U
      usedBases(stid) := usedBases(stid) + allocatedBases
      tailLocal(stid) := sum(p.pcPartitionIndexWidth - 1, 0)
      tailEpoch(stid) := tailEpoch(stid) + wrap.asUInt
      currentValid(stid) := io.prepared.currentAfterValid
      currentToken(stid) := io.prepared.currentAfter
      currentBase(stid) := scanBase.last
    }
    when(commitHere) {
      val sum = headLocal(stid) +& freedBases
      val wrap = sum >= p.pcEntriesPerStid.U
      usedBases(stid) := usedBases(stid) - freedBases
      headLocal(stid) := sum(p.pcPartitionIndexWidth - 1, 0)
      headEpoch(stid) := headEpoch(stid) + wrap.asUInt
    }
  }

  for (stid <- 0 until p.stidCount; local <- 0 until p.pcEntriesPerStid) {
    val row = rowAt(stid.U, local.U)
    val publishHere = publishFire && prepareStid === stid.U
    val matching = VecInit((0 until p.instructionDecodeWidth).map { groupIndex =>
      groupIndex.U < groupCount && localIndex(groupTokens(groupIndex)) === local.U &&
        (newBase(groupIndex) || groupTokens(groupIndex).allocationEpoch ===
          row.token.allocationEpoch)
    })
    val groupHits = PopCount(matching)
    val newHit = (0 until p.instructionDecodeWidth).map { groupIndex =>
      matching(groupIndex) && newBase(groupIndex)
    }.reduce(_ || _)
    val closeHit = (0 until p.instructionDecodeWidth).map { groupIndex =>
      (matching(groupIndex) &&
        (io.prepare.bits.transaction.groups(groupIndex).releasePcBase ||
          io.prepare.bits.transaction.groups(groupIndex).preciseTrap)) ||
        (implicitClose(groupIndex) &&
          localIndex(implicitCloseTokens(groupIndex)) === local.U &&
          implicitCloseTokens(groupIndex).allocationEpoch ===
            row.token.allocationEpoch)
    }.reduce(_ || _)
    val closeOwner = Wire(new RobGroupKey(p))
    val lastGroup = Wire(new RobGroupKey(p))
    closeOwner := 0.U.asTypeOf(closeOwner)
    lastGroup := 0.U.asTypeOf(lastGroup)
    for (groupIndex <- 0 until p.instructionDecodeWidth) {
      when(matching(groupIndex)) {
        lastGroup := io.prepare.bits.transaction.groups(groupIndex).key
      }
      when((matching(groupIndex) &&
        (io.prepare.bits.transaction.groups(groupIndex).releasePcBase ||
          io.prepare.bits.transaction.groups(groupIndex).preciseTrap)) ||
        (implicitClose(groupIndex) &&
          localIndex(implicitCloseTokens(groupIndex)) === local.U &&
          implicitCloseTokens(groupIndex).allocationEpoch ===
            row.token.allocationEpoch)) {
        closeOwner := io.prepare.bits.transaction.groups(groupIndex).key
      }
    }
    val firstIndex = PriorityEncoder(matching.asUInt)
    val retireHits = Mux(commitStid === stid.U, hitsByLocal(local), 0.U)
    val commitHere = commitFire && commitStid === stid.U
    val closeOwnerCommits = (0 until p.retireGroupWidth).map { groupIndex =>
      groupIndex.U < commitCount && row.closeOwnerValid &&
        io.commit.bits.groups(groupIndex).key.asUInt === row.closeOwner.asUInt
    }.reduce(_ || _)
    val lastRetired = Wire(new RobGroupKey(p))
    lastRetired := 0.U.asTypeOf(lastRetired)
    for (groupIndex <- 0 until p.retireGroupWidth) {
      when(groupIndex.U < commitCount &&
        localIndex(io.commit.bits.groups(groupIndex).pcBase) === local.U &&
        io.commit.bits.groups(groupIndex).pcBase.allocationEpoch ===
          row.token.allocationEpoch) {
        lastRetired := io.commit.bits.groups(groupIndex).key
      }
    }
    val freedHere = (0 until p.retireGroupWidth).map { offset =>
      val sum = headLocal(stid) +& offset.U
      val wrap = sum >= p.pcEntriesPerStid.U
      offset.U < freedBases && sum(p.pcPartitionIndexWidth - 1, 0) === local.U &&
        row.token.allocationEpoch === headEpoch(stid) + wrap.asUInt
    }.reduce(_ || _)

    when(publishHere && groupHits.orR) {
      when(newHit) {
        row.valid := true.B
        row.stid := stid.U
        row.token := groupTokens(firstIndex)
        row.base := groupBases(firstIndex)
        row.firstRobGroup :=
          io.prepare.bits.transaction.groups(firstIndex).key
        row.nextCommitRobGroup :=
          io.prepare.bits.transaction.groups(firstIndex).key
        row.liveRobGroups := groupHits
      }.otherwise {
        row.liveRobGroups := row.liveRobGroups + groupHits
      }
      row.lastRobGroup := lastGroup
      row.closed := Mux(newHit, closeHit, row.closed || closeHit)
      when(closeHit) {
        row.closeOwnerValid := true.B
        row.closeOwner := closeOwner
        row.closeCommitted := false.B
      }
    }.elsewhen(publishHere && closeHit) {
      row.closed := true.B
      row.closeOwnerValid := true.B
      row.closeOwner := closeOwner
      row.closeCommitted := false.B
    }
    when(commitHere && retireHits.orR) {
      val sum = lastRetired.ridSlot +& 1.U
      val wrap = sum >= p.robGroupsPerStid.U
      row.liveRobGroups := row.liveRobGroups - retireHits
      row.nextCommitRobGroup.valid := true.B
      row.nextCommitRobGroup.peId := lastRetired.peId
      row.nextCommitRobGroup.stid := lastRetired.stid
      row.nextCommitRobGroup.ridSlot := sum(p.ridSlotWidth - 1, 0)
      row.nextCommitRobGroup.ridGeneration :=
        lastRetired.ridGeneration + wrap.asUInt
    }
    when(commitHere && closeOwnerCommits) {
      row.closeCommitted := true.B
    }
    when(commitHere && freedHere) {
      row := 0.U.asTypeOf(new OooPcBaseEntry(p))
    }
  }

  val recoveryApply = io.recoveryFire && io.recoveryPrepareReady
  when(io.recoveryFire) {
    assert(io.recoveryPrepare.valid && io.recoveryPrepareReady &&
      io.recoveryPrepared.valid,
      "PC recovery fire requires the same exact prepared suffix")
  }
  when(recoveryApply) {
    usedBases(safeRecoveryStid) :=
      usedBases(safeRecoveryStid) - retainedRecoveryPrepared.freedBases
    tailLocal(safeRecoveryStid) :=
      localIndex(retainedRecoveryPrepared.tailAfter)
    tailEpoch(safeRecoveryStid) :=
      retainedRecoveryPrepared.tailAfter.allocationEpoch
    currentValid(safeRecoveryStid) :=
      retainedRecoveryPrepared.currentAfterValid
    currentToken(safeRecoveryStid) := Mux(
      retainedRecoveryPrepared.currentAfterValid,
      retainedRecoveryPrepared.currentAfter,
      0.U.asTypeOf(new PcBufferToken(p)))
    currentBase(safeRecoveryStid) :=
      retainedRecoveryPrepared.currentBaseAfter
  }
  for (local <- 0 until p.pcEntriesPerStid) {
    val row = rowAt(safeRecoveryStid, local.U)
    val closeOwnerKilled = recoveryCloseOwnerKilledMask(local)
    val freedHere = recoveryFreedRowMask(local)
    when(recoveryApply && recoveryHitCounts(local).orR) {
      row.liveRobGroups := row.liveRobGroups - recoveryHitCounts(local)
      row.lastRobGroup := recoveryPriorKeys(local)
    }
    when(recoveryApply && closeOwnerKilled && !freedHere) {
      row.closed := false.B
      row.closeOwnerValid := false.B
      row.closeOwner :=
        0.U.asTypeOf(new RobGroupKey(p))
      row.closeCommitted := false.B
    }
    when(recoveryApply && freedHere) {
      row := 0.U.asTypeOf(new OooPcBaseEntry(p))
    }
  }

  io.usedBases := usedBases
  for (stid <- 0 until p.stidCount) {
    io.head(stid).valid := usedBases(stid).orR
    io.head(stid).index := stid.U * p.pcEntriesPerStid.U + headLocal(stid)
    io.head(stid).byteOffset := 0.U
    io.head(stid).allocationEpoch := headEpoch(stid)
  }
  io.currentValid := currentValid
  io.current := currentToken

  for (port <- 0 until p.pcReadPorts) {
    val token = io.readTokens(port)
    val readStidRaw = token.index / p.pcEntriesPerStid.U
    val readStidInRange = readStidRaw < p.stidCount.U
    val readStid = readStidRaw(p.stidWidth - 1, 0)
    val safeReadStid = Mux(readStidInRange, readStid, 0.U)
    val row = rowAt(safeReadStid, localIndex(token))
    io.readValid(port) := token.valid && readStidInRange && row.valid &&
      row.stid === readStid && row.token.index === token.index &&
      row.token.allocationEpoch === token.allocationEpoch
    io.readPc(port) := Mux(
      io.readValid(port),
      row.base + token.byteOffset.pad(p.pcWidth),
      0.U)
  }
}
