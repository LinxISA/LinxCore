package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, log2Ceil}
import linxcore.common.InterfaceParams

class IfuPredictionJoinRow(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val maxGroupsPerTransaction: Int = 8)
    extends Bundle {
  private val groupCountWidth = log2Ceil(maxGroupsPerTransaction + 1)
  private val groupIndexWidth = math.max(1, log2Ceil(maxGroupsPerTransaction))

  val valid = Bool()
  val request = new ISideFetchRequest(p, lineBytes)
  val groups = Vec(maxGroupsPerTransaction, new InstructionBufferEnqueueGroup(p))
  val groupCount = UInt(groupCountWidth.W)
  val emitIndex = UInt(groupIndexWidth.W)
  val iSideComplete = Bool()
  val finalPredictionValid = Bool()
  val finalPrediction = new BranchPredictionRecord(p)
  val correctionSeen = Bool()
  val correctionApplied = Bool()
  val finalEpoch = UInt(p.blockEpochWidth.W)
}

class IfuPredictionJoinIO(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val entries: Int = 8,
    val maxGroupsPerTransaction: Int = 8)
    extends Bundle {
  private val countWidth = log2Ceil(entries + 1)

  val allocate = Flipped(Decoupled(new ISideFetchRequest(p, lineBytes)))
  val iSide = Flipped(Decoupled(new InstructionBufferEnqueueGroup(p)))
  val prediction = Flipped(Decoupled(new BSidePredictionUpdate(p, lineBytes)))
  val flush = Input(new IfuInnerFlush(p))
  val out = Decoupled(new InstructionBufferEnqueueGroup(p))

  val count = Output(UInt(countWidth.W))
  val iSideUnmatched = Output(Bool())
  val predictionUnmatched = Output(Bool())
  val headWaitingForISide = Output(Bool())
  val headWaitingForPrediction = Output(Bool())
  val headWaitingForCorrection = Output(Bool())
}

class IfuPredictionJoin(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val entries: Int = 8,
    val maxGroupsPerTransaction: Int = 8)
    extends Module {
  require(entries > 0 && (entries & (entries - 1)) == 0)
  require(maxGroupsPerTransaction > 0 && (maxGroupsPerTransaction & (maxGroupsPerTransaction - 1)) == 0)

  private val ptrWidth = math.max(1, log2Ceil(entries))
  private val countWidth = log2Ceil(entries + 1)
  private val groupCountWidth = log2Ceil(maxGroupsPerTransaction + 1)
  private val groupIndexWidth = math.max(1, log2Ceil(maxGroupsPerTransaction))

  val io = IO(new IfuPredictionJoinIO(p, lineBytes, entries, maxGroupsPerTransaction))

  val rows = RegInit(
    VecInit(
      Seq.fill(entries)(
        0.U.asTypeOf(new IfuPredictionJoinRow(p, lineBytes, maxGroupsPerTransaction)))))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  def advance(ptr: UInt, amount: UInt): UInt =
    (ptr + amount)(ptrWidth - 1, 0)

  def rowAt(index: UInt): IfuPredictionJoinRow =
    if (entries == 1) rows(0) else rows(index)

  def exactRequest(lhs: ISideFetchRequest, rhs: ISideFetchRequest): Bool =
    lhs.identity.peId === rhs.identity.peId &&
      lhs.transactionId === rhs.transactionId &&
      lhs.identity.threadId === rhs.identity.threadId &&
      lhs.identity.fetchPacketUid === rhs.identity.fetchPacketUid &&
      lhs.identity.fetchSeq === rhs.identity.fetchSeq &&
      lhs.identity.checkpointId === rhs.identity.checkpointId &&
      lhs.identity.epoch === rhs.identity.epoch

  def exactIdentity(
      row: IfuPredictionJoinRow,
      identity: IfuFetchIdentity,
      transactionId: UInt): Bool =
    row.request.identity.peId === identity.peId &&
      row.request.transactionId === transactionId &&
      row.request.identity.threadId === identity.threadId &&
      row.request.identity.fetchPacketUid === identity.fetchPacketUid &&
      row.request.identity.fetchSeq === identity.fetchSeq &&
      row.request.identity.checkpointId === identity.checkpointId &&
      row.request.identity.epoch === identity.epoch

  val allocateFire = io.allocate.valid && io.allocate.ready
  io.allocate.ready := count =/= entries.U && !io.flush.valid

  val iSideHasRows = io.iSide.bits.validMask.orR
  // A following control BSTART may close the current transaction without
  // contributing an instruction lane to it.  F4 still carries the exact
  // transaction identity in entry 0 and must be able to publish that empty
  // terminal event so the retained groups can receive their BF4 prediction.
  val iSideHasIdentity = iSideHasRows || io.iSide.bits.transactionComplete
  val iSideIdentity = io.iSide.bits.entries(0).identity
  val iSideMatches = Wire(Vec(entries, Bool()))
  val predictionMatches = Wire(Vec(entries, Bool()))
  for (entry <- 0 until entries) {
    iSideMatches(entry) :=
      rows(entry).valid &&
        iSideHasIdentity &&
        exactIdentity(rows(entry), iSideIdentity, io.iSide.bits.entries(0).transactionId)
    predictionMatches(entry) :=
      rows(entry).valid &&
        exactRequest(rows(entry).request, io.prediction.bits.request)
  }
  val iSideMatchValid = iSideMatches.asUInt.orR
  val iSideMatchIndex = chisel3.util.PriorityEncoder(iSideMatches.asUInt)
  val predictionMatchValid = predictionMatches.asUInt.orR
  val predictionMatchIndex = chisel3.util.PriorityEncoder(predictionMatches.asUInt)

  io.iSide.ready :=
    !io.flush.valid &&
      iSideMatchValid &&
      (!iSideHasRows || rowAt(iSideMatchIndex).groupCount < maxGroupsPerTransaction.U)
  io.prediction.ready := !io.flush.valid && predictionMatchValid
  io.iSideUnmatched := io.iSide.valid && !iSideMatchValid
  io.predictionUnmatched := io.prediction.valid && !predictionMatchValid

  val headRow = rowAt(head)
  val headCorrectionReady = !headRow.correctionSeen || headRow.correctionApplied
  val headReady =
    count =/= 0.U &&
      headRow.valid &&
      headRow.iSideComplete &&
      headRow.finalPredictionValid &&
      headCorrectionReady
  val emitGroup = headRow.groups(headRow.emitIndex)
  val emitGroupHasRows = emitGroup.validMask.orR
  io.out.valid :=
    headReady && headRow.groupCount =/= 0.U && emitGroupHasRows && !io.flush.valid
  io.out.bits := emitGroup
  for (lane <- 0 until p.fetchWidth) {
    when(emitGroup.validMask(lane)) {
      io.out.bits.entries(lane).identity.epoch := headRow.finalEpoch
      io.out.bits.entries(lane).prediction := headRow.finalPrediction
      io.out.bits.entries(lane).prediction.epoch := headRow.finalEpoch
    }
  }

  val outFire = io.out.valid && io.out.ready
  // A terminal BF4 may prune every lane from one retained group while a
  // later group in the same transaction still contains architectural body
  // instructions.  Empty groups are internal join state and must not be sent
  // to InstructionBuffer, whose enqueue contract requires at least one lane.
  val skipsEmptyGroup =
    headReady && headRow.groupCount =/= 0.U && !emitGroupHasRows && !io.flush.valid
  val advancesGroup = outFire || skipsEmptyGroup
  val advancesLastGroup =
    advancesGroup &&
      (headRow.emitIndex.pad(groupCountWidth) +& 1.U) === headRow.groupCount
  val retiresEmptyHead =
    headReady && headRow.groupCount === 0.U && !io.flush.valid
  val retiresHead = advancesLastGroup || retiresEmptyHead

  io.count := count
  io.headWaitingForISide := headRow.valid && !headRow.iSideComplete
  io.headWaitingForPrediction := headRow.valid && !headRow.finalPredictionValid
  io.headWaitingForCorrection :=
    headRow.valid &&
      headRow.finalPredictionValid &&
      headRow.correctionSeen &&
      !headRow.correctionApplied

  when(io.flush.valid) {
    for (entry <- 0 until entries) {
      rows(entry).valid := false.B
    }
    val keep = Wire(Vec(entries, Bool()))
    for (offset <- 0 until entries) {
      val readPtr = advance(head, offset.U)
      val row = rowAt(readPtr)
      val remainingInstructionSurvives = Wire(Vec(maxGroupsPerTransaction, Bool()))
      for (group <- 0 until maxGroupsPerTransaction) {
        val laneSurvives = Wire(Vec(p.fetchWidth, Bool()))
        for (lane <- 0 until p.fetchWidth) {
          laneSurvives(lane) :=
            row.groups(group).validMask(lane) &&
              !IfuFlushContract.killsInstruction(row.groups(group).entries(lane), io.flush)
        }
        remainingInstructionSurvives(group) :=
          group.U >= row.emitIndex &&
            group.U < row.groupCount &&
            laneSurvives.asUInt.orR
      }
      keep(offset) :=
        offset.U < count &&
          (!IfuFlushContract.kills(
             row.request.identity,
             row.request.transactionId,
             io.flush) || remainingInstructionSurvives.asUInt.orR)
    }
    val keepPrefix = Wire(Vec(entries + 1, UInt(countWidth.W)))
    keepPrefix(0) := 0.U
    for (offset <- 0 until entries) {
      keepPrefix(offset + 1) := keepPrefix(offset) + keep(offset).asUInt
      val readPtr = advance(head, offset.U)
      val writePtr = keepPrefix(offset)(ptrWidth - 1, 0)
      val row = rowAt(readPtr)
      when(keep(offset)) {
        val retained = Wire(new IfuPredictionJoinRow(p, lineBytes, maxGroupsPerTransaction))
        retained := row
        for (group <- 0 until maxGroupsPerTransaction) {
          val retainedMask = Wire(Vec(p.fetchWidth, Bool()))
          for (lane <- 0 until p.fetchWidth) {
            retainedMask(lane) :=
              row.groups(group).validMask(lane) &&
                !IfuFlushContract.killsInstruction(row.groups(group).entries(lane), io.flush)
          }
          retained.groups(group).validMask := retainedMask.asUInt
        }
        rowAt(writePtr) := retained
        rowAt(writePtr).finalEpoch := io.flush.newEpoch
        when(
          row.request.transactionId === io.flush.transactionId &&
            row.request.identity.fetchSeq === io.flush.fetchSeq &&
            row.request.identity.epoch === io.flush.oldEpoch) {
          rowAt(writePtr).correctionApplied := true.B
        }
      }
    }
    val keptCount = keepPrefix(entries)
    head := 0.U
    tail := keptCount(ptrWidth - 1, 0)
    count := keptCount
  }.otherwise {
    when(allocateFire) {
      rowAt(tail) := 0.U.asTypeOf(rowAt(tail))
      rowAt(tail).valid := true.B
      rowAt(tail).request := io.allocate.bits
      rowAt(tail).finalEpoch := io.allocate.bits.identity.epoch
      tail := advance(tail, 1.U)
    }

    when(io.iSide.fire) {
      when(iSideHasRows) {
        val groupIndex = rowAt(iSideMatchIndex).groupCount
        rowAt(iSideMatchIndex).groups(groupIndex(groupIndexWidth - 1, 0)) := io.iSide.bits
        rowAt(iSideMatchIndex).groupCount := groupIndex + 1.U
      }
      when(io.iSide.bits.transactionComplete) {
        rowAt(iSideMatchIndex).iSideComplete := true.B
      }
    }

    when(io.prediction.fire) {
      rowAt(predictionMatchIndex).finalPrediction := io.prediction.bits.prediction
      when(io.prediction.bits.correction) {
        rowAt(predictionMatchIndex).correctionSeen := true.B
      }
      when(io.prediction.bits.finalResponse) {
        rowAt(predictionMatchIndex).finalPredictionValid := true.B
      }
    }

    when(advancesGroup && !advancesLastGroup) {
      rowAt(head).emitIndex := headRow.emitIndex + 1.U
    }
    when(retiresHead) {
      rowAt(head).valid := false.B
      head := advance(head, 1.U)
    }

    when(allocateFire && !retiresHead) {
      count := count + 1.U
    }.elsewhen(!allocateFire && retiresHead) {
      count := count - 1.U
    }
  }

  when(io.iSide.fire) {
    assert(chisel3.util.PopCount(iSideMatches) === 1.U)
  }
  when(io.prediction.fire) {
    assert(chisel3.util.PopCount(predictionMatches) === 1.U)
  }
}
