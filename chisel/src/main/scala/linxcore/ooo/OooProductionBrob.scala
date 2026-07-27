package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Valid}

class OooProductionBrobIO(val p: OooParams = OooParams()) extends Bundle {
  val prepare = Flipped(Valid(new OooD3GroupedReservation(p)))
  val prepared = Output(new OooBrobPreparedBindings(p))
  val prepareReady = Output(Bool())
  val publishFire = Input(Bool())
  val commit = Flipped(Decoupled(new OooRobCommitBatch(p)))

  val prepareRejected = Valid(new OooBrobPrepareReject(p))
  val commitRejected = Valid(new OooBrobCommitReject(p))
  val usedBlocks = Output(Vec(p.stidCount, UInt(p.brobCountWidth.W)))
  val head = Output(Vec(p.stidCount, new BrobPointer(p)))
  val tail = Output(Vec(p.stidCount, new BrobPointer(p)))
  val currentValid = Output(Vec(p.stidCount, Bool()))
  val current = Output(Vec(p.stidCount, new BrobPointer(p)))
}

/** Per-STID production block reorder buffer.
  *
  * `prepare` is a side-effect-free view of the retained D3 row. The caller may
  * use `prepared.pointers` to form S1 bindings only while `prepareReady` is
  * true. State changes exactly once on the shared `publishFire`; commit changes
  * BROB state only on the same retained ROB commit handshake.
  */
class OooProductionBrob(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooProductionBrobIO(p))

  val table = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.brobEntriesPerStid)(
      0.U.asTypeOf(new OooBrobEntry(p)))))))
  val usedBlocks = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.brobCountWidth.W))))
  val headSlot = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.nativeBidWidth.W))))
  val headGeneration = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.brobGenerationWidth.W))))
  val tailSlot = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.nativeBidWidth.W))))
  val tailGeneration = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.brobGenerationWidth.W))))
  val currentValid = RegInit(VecInit(Seq.fill(p.stidCount)(false.B)))
  val currentPointer = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U.asTypeOf(new BrobPointer(p)))))

  val prepareStid = io.prepare.bits.transaction.plan.stid
  val prepareStidInRange = prepareStid < p.stidCount.U
  val safePrepareStid = Mux(prepareStidInRange, prepareStid, 0.U)
  val prepareGroupCount = io.prepare.bits.transaction.plan.groupCount
  val prepareGroupCountInRange = prepareGroupCount.orR &&
    prepareGroupCount <= p.instructionDecodeWidth.U
  val expectedPrepareMask =
    ((1.U((p.instructionDecodeWidth + 1).W) << prepareGroupCount) - 1.U)(
      p.instructionDecodeWidth - 1, 0)

  val scanCurrentValid = Wire(Vec(p.instructionDecodeWidth + 1, Bool()))
  val scanCurrent = Wire(Vec(p.instructionDecodeWidth + 1, new BrobPointer(p)))
  val scanAllocCount = Wire(Vec(p.instructionDecodeWidth + 1, UInt(p.robGroupCountWidth.W)))
  val scanLegal = Wire(Vec(p.instructionDecodeWidth, Bool()))
  val groupPointers = Wire(Vec(p.instructionDecodeWidth, new BrobPointer(p)))
  val newBlock = Wire(Vec(p.instructionDecodeWidth, Bool()))
  val implicitClose = Wire(Vec(p.instructionDecodeWidth, Bool()))
  val implicitClosePointers = Wire(Vec(p.instructionDecodeWidth, new BrobPointer(p)))

  scanCurrentValid(0) := currentValid(safePrepareStid)
  scanCurrent(0) := currentPointer(safePrepareStid)
  scanAllocCount(0) := 0.U
  for (groupIndex <- 0 until p.instructionDecodeWidth) {
    val group = io.prepare.bits.transaction.groups(groupIndex)
    val active = groupIndex.U < prepareGroupCount
    val allocate = active && group.boundaryStart
    val tailSum = tailSlot(safePrepareStid) +& scanAllocCount(groupIndex)
    val tailWrap = tailSum >= p.brobEntriesPerStid.U
    val allocatedPointer = Wire(new BrobPointer(p))
    allocatedPointer.valid := true.B
    allocatedPointer.bid.valid := true.B
    allocatedPointer.bid.value := tailSum(p.nativeBidWidth - 1, 0)
    allocatedPointer.generation := tailGeneration(safePrepareStid) + tailWrap.asUInt

    newBlock(groupIndex) := allocate
    implicitClose(groupIndex) := allocate && scanCurrentValid(groupIndex)
    implicitClosePointers(groupIndex) := scanCurrent(groupIndex)
    groupPointers(groupIndex) := Mux(allocate, allocatedPointer, scanCurrent(groupIndex))
    scanLegal(groupIndex) := Mux(
      active,
      group.valid && group.key.valid &&
        group.key.peId === io.prepare.bits.transaction.plan.peId &&
        group.key.stid === prepareStid &&
        (allocate || scanCurrentValid(groupIndex)) && groupPointers(groupIndex).valid &&
        groupPointers(groupIndex).bid.valid,
      !group.valid)
    scanCurrentValid(groupIndex + 1) := Mux(
      active,
      !group.boundaryStop,
      scanCurrentValid(groupIndex))
    scanCurrent(groupIndex + 1) := Mux(
      active,
      groupPointers(groupIndex),
      scanCurrent(groupIndex))
    scanAllocCount(groupIndex + 1) :=
      scanAllocCount(groupIndex) + allocate.asUInt
  }

  val allocatedBlocks = scanAllocCount(p.instructionDecodeWidth)
  val tailAfterSum = tailSlot(safePrepareStid) +& allocatedBlocks
  val tailAfterWrap = tailAfterSum >= p.brobEntriesPerStid.U
  val freeBlocks = p.brobEntriesPerStid.U(p.brobCountWidth.W) - usedBlocks(safePrepareStid)
  val prepareHasCapacity = freeBlocks >= allocatedBlocks
  val newTargetsFree = (0 until p.instructionDecodeWidth).map { groupIndex =>
    !newBlock(groupIndex) ||
      !table(safePrepareStid)(groupPointers(groupIndex).bid.value).valid
  }.reduce(_ && _)
  val liveCurrentExact = !currentValid(safePrepareStid) || {
    val row = table(safePrepareStid)(currentPointer(safePrepareStid).bid.value)
    row.valid && row.pointer.asUInt === currentPointer(safePrepareStid).asUInt &&
      row.peId === io.prepare.bits.transaction.plan.peId
  }
  val commitStid = io.commit.bits.release.firstGroup.stid
  val exactCommitConflict = Wire(Bool())
  val prepareExact = prepareStidInRange && prepareGroupCountInRange &&
    io.prepare.bits.transaction.groupMask === expectedPrepareMask &&
    io.prepare.bits.transaction.plan.peId === io.prepare.bits.transaction.decoded.peId &&
    io.prepare.bits.transaction.plan.stid === io.prepare.bits.transaction.decoded.stid &&
    io.prepare.bits.transaction.plan.epoch === io.prepare.bits.transaction.decoded.epoch &&
    scanLegal.reduce(_ && _) && prepareHasCapacity && newTargetsFree && liveCurrentExact

  io.prepared := 0.U.asTypeOf(io.prepared)
  io.prepared.validMask := expectedPrepareMask
  io.prepared.pointers := groupPointers
  io.prepared.newBlockMask := newBlock.asUInt
  io.prepared.implicitCloseMask := implicitClose.asUInt
  io.prepared.implicitClosePointers := implicitClosePointers
  io.prepared.allocatedBlocks := allocatedBlocks
  io.prepared.tailAfter.valid := true.B
  io.prepared.tailAfter.bid.valid := true.B
  io.prepared.tailAfter.bid.value := tailAfterSum(p.nativeBidWidth - 1, 0)
  io.prepared.tailAfter.generation := tailGeneration(safePrepareStid) + tailAfterWrap.asUInt
  io.prepared.currentAfterValid := scanCurrentValid(p.instructionDecodeWidth)
  io.prepared.currentAfter := scanCurrent(p.instructionDecodeWidth)
  io.prepareReady := prepareExact && !exactCommitConflict
  io.prepareRejected.valid := io.prepare.valid && !io.prepareReady
  io.prepareRejected.bits.stid := prepareStid
  io.prepareRejected.bits.transactionId := io.prepare.bits.transaction.plan.transactionId
  io.prepareRejected.bits.groupMask := io.prepare.bits.transaction.groupMask

  val commitGroupCount = io.commit.bits.release.groupCount
  val commitStidInRange = commitStid < p.stidCount.U
  val safeCommitStid = Mux(commitStidInRange, commitStid, 0.U)
  val commitCountInRange = commitGroupCount.orR && commitGroupCount <= p.retireGroupWidth.U
  val headPointer = Wire(new BrobPointer(p))
  headPointer.valid := usedBlocks(safeCommitStid).orR
  headPointer.bid.valid := headPointer.valid
  headPointer.bid.value := headSlot(safeCommitStid)
  headPointer.generation := headGeneration(safeCommitStid)

  val commitGroupExact = Wire(Vec(p.retireGroupWidth, Bool()))
  for (groupIndex <- 0 until p.retireGroupWidth) {
    val active = groupIndex.U < commitGroupCount
    val group = io.commit.bits.groups(groupIndex)
    val row = table(safeCommitStid)(group.brob.bid.value)
    val liveExact = group.valid && group.key.valid && group.key.stid === commitStid &&
      group.brob.valid && group.brob.bid.valid && row.valid &&
      row.pointer.asUInt === group.brob.asUInt && row.peId === group.key.peId &&
      row.stid === commitStid
    val startsEntryPrefix = if (groupIndex == 0) {
      true.B
    } else {
      group.brob.asUInt =/= io.commit.bits.groups(groupIndex - 1).brob.asUInt
    }
    val priorGroupSequential = if (groupIndex == 0) {
      true.B
    } else {
      val priorKey = io.commit.bits.groups(groupIndex - 1).key
      val nextRidSum = priorKey.ridSlot +& 1.U
      val nextRidWrap = nextRidSum >= p.robGroupsPerStid.U
      group.key.valid && priorKey.valid && group.key.peId === priorKey.peId &&
        group.key.stid === priorKey.stid &&
        group.key.ridSlot === nextRidSum(p.ridSlotWidth - 1, 0) &&
        group.key.ridGeneration === priorKey.ridGeneration + nextRidWrap.asUInt
    }
    val entryPrefixExact = !startsEntryPrefix ||
      (row.nextCommitRobGroup.valid &&
        row.nextCommitRobGroup.asUInt === group.key.asUInt)
    val orderExact = if (groupIndex == 0) {
      val headRow = table(safeCommitStid)(headPointer.bid.value)
      val nextSum = headPointer.bid.value +& 1.U
      val nextWrap = nextSum >= p.brobEntriesPerStid.U
      val closesEmptyHead = headRow.valid && headRow.closed &&
        headRow.liveRobGroups === 0.U && headRow.closeOwnerValid &&
        headRow.closeOwner.asUInt === group.key.asUInt && group.boundaryStart &&
        group.brob.bid.value === nextSum(p.nativeBidWidth - 1, 0) &&
        group.brob.generation === headPointer.generation + nextWrap.asUInt
      group.brob.asUInt === headPointer.asUInt || closesEmptyHead
    } else {
      val prior = io.commit.bits.groups(groupIndex - 1)
      val nextSum = prior.brob.bid.value +& 1.U
      val nextWrap = nextSum >= p.brobEntriesPerStid.U
      val sameBlock = group.brob.asUInt === prior.brob.asUInt &&
        !group.boundaryStart && !prior.boundaryStop
      val nextBlock = group.boundaryStart && group.brob.valid && group.brob.bid.valid &&
        group.brob.bid.value === nextSum(p.nativeBidWidth - 1, 0) &&
        group.brob.generation === prior.brob.generation + nextWrap.asUInt
      sameBlock || nextBlock
    }
    commitGroupExact(groupIndex) := Mux(
      active,
      liveExact && orderExact && priorGroupSequential && entryPrefixExact,
      !group.valid)
  }

  val retireHitsBySlot = Wire(Vec(p.brobEntriesPerStid, UInt(p.robReleaseCountWidth.W)))
  for (slot <- 0 until p.brobEntriesPerStid) {
    retireHitsBySlot(slot) := PopCount((0 until p.retireGroupWidth).map { groupIndex =>
      groupIndex.U < commitGroupCount &&
        io.commit.bits.groups(groupIndex).brob.bid.value === slot.U &&
        io.commit.bits.groups(groupIndex).brob.generation === table(safeCommitStid)(slot).pointer.generation
    })
  }
  val retireCountsLegal = (0 until p.brobEntriesPerStid).map { slot =>
    retireHitsBySlot(slot) <= table(safeCommitStid)(slot).liveRobGroups
  }.reduce(_ && _)

  val freePrefix = Wire(Vec(p.retireGroupWidth + 1, UInt(p.robReleaseCountWidth.W)))
  freePrefix(0) := 0.U
  for (offset <- 0 until p.retireGroupWidth) {
    val pointerSum = headSlot(safeCommitStid) +& offset.U
    val pointerWrap = pointerSum >= p.brobEntriesPerStid.U
    val slot = pointerSum(p.nativeBidWidth - 1, 0)
    val row = table(safeCommitStid)(slot)
    val hits = retireHitsBySlot(slot)
    val closeOwnerCommits = (0 until p.retireGroupWidth).map { groupIndex =>
      groupIndex.U < commitGroupCount && row.closeOwnerValid &&
        io.commit.bits.groups(groupIndex).key.asUInt === row.closeOwner.asUInt
    }.reduce(_ || _)
    val exactPointer = row.pointer.valid && row.pointer.bid.valid &&
      row.pointer.bid.value === slot &&
      row.pointer.generation === headGeneration(safeCommitStid) + pointerWrap.asUInt
    val becomesEmpty = row.valid && row.closed &&
      (row.closeCommitted || closeOwnerCommits) && hits === row.liveRobGroups
    val extend = freePrefix(offset) === offset.U && exactPointer && becomesEmpty
    freePrefix(offset + 1) := Mux(extend, (offset + 1).U, freePrefix(offset))
  }
  val freedBlocks = freePrefix(p.retireGroupWidth)
  val releaseHeaderExact = io.commit.bits.release.firstGroup.valid &&
    io.commit.bits.groups(0).valid &&
    io.commit.bits.release.firstGroup.asUInt === io.commit.bits.groups(0).key.asUInt
  val commitExact = commitStidInRange && commitCountInRange &&
    usedBlocks(safeCommitStid).orR && commitGroupExact.reduce(_ && _) &&
    retireCountsLegal && releaseHeaderExact
  // One exact same-STID commit wins this cycle. Invalid retained commits report
  // rejection but cannot starve a prepare; different STIDs remain concurrent.
  exactCommitConflict := io.commit.valid && commitExact && prepareStidInRange &&
    commitStid === prepareStid
  io.commit.ready := commitExact
  val commitFire = io.commit.valid && io.commit.ready
  io.commitRejected.valid := io.commit.valid && !io.commit.ready
  io.commitRejected.bits.requested := io.commit.bits
  io.commitRejected.bits.head := headPointer

  val publishFire = io.publishFire && io.prepare.valid && io.prepareReady
  when(io.publishFire) {
    assert(io.prepare.valid && io.prepareReady,
      "BROB publish must use the exact accepted prepare view")
  }

  for (stid <- 0 until p.stidCount) {
    val publishHere = publishFire && prepareStid === stid.U
    val commitHere = commitFire && commitStid === stid.U
    when(publishHere) {
      usedBlocks(stid) := usedBlocks(stid) + allocatedBlocks
      tailSlot(stid) := tailAfterSum(p.nativeBidWidth - 1, 0)
      tailGeneration(stid) := tailGeneration(stid) + tailAfterWrap.asUInt
      currentValid(stid) := io.prepared.currentAfterValid
      currentPointer(stid) := io.prepared.currentAfter
    }
    when(commitHere) {
      val headSum = headSlot(stid) +& freedBlocks
      val headWrap = headSum >= p.brobEntriesPerStid.U
      usedBlocks(stid) := usedBlocks(stid) - freedBlocks
      headSlot(stid) := headSum(p.nativeBidWidth - 1, 0)
      headGeneration(stid) := headGeneration(stid) + headWrap.asUInt
    }
  }

  for (stid <- 0 until p.stidCount; slot <- 0 until p.brobEntriesPerStid) {
    val publishHere = publishFire && prepareStid === stid.U
    val matchingGroups = VecInit((0 until p.instructionDecodeWidth).map { groupIndex =>
      groupIndex.U < prepareGroupCount &&
        groupPointers(groupIndex).bid.value === slot.U &&
        groupPointers(groupIndex).generation ===
          Mux(newBlock(groupIndex), groupPointers(groupIndex).generation, table(stid)(slot).pointer.generation)
    })
    val groupHits = PopCount(matchingGroups)
    val newHit = (0 until p.instructionDecodeWidth).map { groupIndex =>
      matchingGroups(groupIndex) && newBlock(groupIndex)
    }.reduce(_ || _)
    val closeHit = (0 until p.instructionDecodeWidth).map { groupIndex =>
      (matchingGroups(groupIndex) &&
        io.prepare.bits.transaction.groups(groupIndex).boundaryStop) ||
        (implicitClose(groupIndex) &&
          implicitClosePointers(groupIndex).bid.value === slot.U &&
          implicitClosePointers(groupIndex).generation === table(stid)(slot).pointer.generation)
    }.reduce(_ || _)
    val closeOwner = Wire(new RobGroupKey(p))
    closeOwner := 0.U.asTypeOf(closeOwner)
    for (groupIndex <- 0 until p.instructionDecodeWidth) {
      when((matchingGroups(groupIndex) &&
        io.prepare.bits.transaction.groups(groupIndex).boundaryStop) ||
        (implicitClose(groupIndex) &&
          implicitClosePointers(groupIndex).bid.value === slot.U &&
          implicitClosePointers(groupIndex).generation === table(stid)(slot).pointer.generation)) {
        closeOwner := io.prepare.bits.transaction.groups(groupIndex).key
      }
    }
    val firstGroupIndex = PriorityEncoder(matchingGroups.asUInt)
    val lastGroup = Wire(new RobGroupKey(p))
    lastGroup := 0.U.asTypeOf(lastGroup)
    for (groupIndex <- 0 until p.instructionDecodeWidth) {
      when(matchingGroups(groupIndex)) {
        lastGroup := io.prepare.bits.transaction.groups(groupIndex).key
      }
    }

    val retireHits = Mux(commitStid === stid.U, retireHitsBySlot(slot), 0.U)
    val commitHere = commitFire && commitStid === stid.U
    val closeOwnerCommits = (0 until p.retireGroupWidth).map { groupIndex =>
      groupIndex.U < commitGroupCount && table(stid)(slot).closeOwnerValid &&
        io.commit.bits.groups(groupIndex).key.asUInt === table(stid)(slot).closeOwner.asUInt
    }.reduce(_ || _)
    val lastRetiredGroup = Wire(new RobGroupKey(p))
    lastRetiredGroup := 0.U.asTypeOf(lastRetiredGroup)
    for (groupIndex <- 0 until p.retireGroupWidth) {
      when(groupIndex.U < commitGroupCount &&
        io.commit.bits.groups(groupIndex).brob.bid.value === slot.U &&
        io.commit.bits.groups(groupIndex).brob.generation ===
          table(stid)(slot).pointer.generation) {
        lastRetiredGroup := io.commit.bits.groups(groupIndex).key
      }
    }
    val freedHere = (0 until p.retireGroupWidth).map { offset =>
      val pointerSum = headSlot(stid) +& offset.U
      val pointerWrap = pointerSum >= p.brobEntriesPerStid.U
      offset.U < freedBlocks && pointerSum(p.nativeBidWidth - 1, 0) === slot.U &&
        table(stid)(slot).pointer.generation === headGeneration(stid) + pointerWrap.asUInt
    }.reduce(_ || _)

    when(publishHere && groupHits.orR) {
      when(newHit) {
        table(stid)(slot).valid := true.B
        table(stid)(slot).pointer := groupPointers(firstGroupIndex)
        table(stid)(slot).peId := io.prepare.bits.transaction.plan.peId
        table(stid)(slot).stid := stid.U
        table(stid)(slot).firstRobGroup :=
          io.prepare.bits.transaction.groups(firstGroupIndex).key
        table(stid)(slot).nextCommitRobGroup :=
          io.prepare.bits.transaction.groups(firstGroupIndex).key
        table(stid)(slot).liveRobGroups := groupHits
      }.otherwise {
        table(stid)(slot).liveRobGroups := table(stid)(slot).liveRobGroups + groupHits
      }
      table(stid)(slot).lastRobGroup := lastGroup
      table(stid)(slot).closed := Mux(newHit, closeHit, table(stid)(slot).closed || closeHit)
      when(closeHit) {
        table(stid)(slot).closeOwnerValid := true.B
        table(stid)(slot).closeOwner := closeOwner
        table(stid)(slot).closeCommitted := false.B
      }
    }.elsewhen(publishHere && closeHit) {
      table(stid)(slot).closed := true.B
      table(stid)(slot).closeOwnerValid := true.B
      table(stid)(slot).closeOwner := closeOwner
      table(stid)(slot).closeCommitted := false.B
    }
    when(commitHere && retireHits.orR) {
      val nextRidSum = lastRetiredGroup.ridSlot +& 1.U
      val nextRidWrap = nextRidSum >= p.robGroupsPerStid.U
      table(stid)(slot).liveRobGroups := table(stid)(slot).liveRobGroups - retireHits
      table(stid)(slot).nextCommitRobGroup.valid := true.B
      table(stid)(slot).nextCommitRobGroup.peId := lastRetiredGroup.peId
      table(stid)(slot).nextCommitRobGroup.stid := lastRetiredGroup.stid
      table(stid)(slot).nextCommitRobGroup.ridSlot :=
        nextRidSum(p.ridSlotWidth - 1, 0)
      table(stid)(slot).nextCommitRobGroup.ridGeneration :=
        lastRetiredGroup.ridGeneration + nextRidWrap.asUInt
    }
    when(commitHere && closeOwnerCommits) {
      table(stid)(slot).closeCommitted := true.B
    }
    when(commitHere && freedHere) {
      table(stid)(slot) := 0.U.asTypeOf(new OooBrobEntry(p))
    }
  }

  io.usedBlocks := usedBlocks
  for (stid <- 0 until p.stidCount) {
    io.head(stid).valid := usedBlocks(stid).orR
    io.head(stid).bid.valid := io.head(stid).valid
    io.head(stid).bid.value := headSlot(stid)
    io.head(stid).generation := headGeneration(stid)
    io.tail(stid).valid := true.B
    io.tail(stid).bid.valid := true.B
    io.tail(stid).bid.value := tailSlot(stid)
    io.tail(stid).generation := tailGeneration(stid)
  }
  io.currentValid := currentValid
  io.current := currentPointer
}
