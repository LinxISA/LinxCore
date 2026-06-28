package linxcore.lsu

import chisel3._
import chisel3.util._

import linxcore.rob.ROBID

class STQInsertProbeIO(
    val entries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32)
    extends Bundle {
  private val ptrWidth = log2Ceil(entries)

  val requestValid = Input(Bool())
  val request = Input(new STQStoreRequest(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth))
  val rows = Input(Vec(entries, new STQEntryBankRow(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth)))
  val flushApplied = Input(Bool())

  val ready = Output(Bool())
  val requestReady = Output(Bool())
  val canMerge = Output(Bool())
  val canAllocate = Output(Bool())
  val conflict = Output(Bool())
  val mergeMask = Output(UInt(entries.W))
  val conflictMask = Output(UInt(entries.W))
  val freeMask = Output(UInt(entries.W))
  val mergeIndex = Output(UInt(ptrWidth.W))
  val allocateIndex = Output(UInt(ptrWidth.W))
  val insertIndex = Output(UInt(ptrWidth.W))
}

class STQInsertProbe(
    val entries: Int = 16,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32)
    extends Module {
  require(entries > 1, "STQ insert probe entries must be greater than one")
  require((entries & (entries - 1)) == 0, "STQ insert probe entries must be a power of two")

  val io = IO(new STQInsertProbeIO(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth))

  private def sameStoreId(row: STQEntryBankRow, req: STQStoreRequest): Bool =
    ROBID.equal(row.bid, req.bid) && ROBID.equal(row.lsId, req.lsId) &&
      (req.scalarIex || (row.simtLane === req.simtLane))

  private def compatibleMerge(row: STQEntryBankRow, req: STQStoreRequest): Bool =
    ((req.storeType === STQStoreType.Addr) && (row.storeType === STQStoreType.Data)) ||
      ((req.storeType === STQStoreType.Data) && (row.storeType === STQStoreType.Addr))

  val occupiedVec = Wire(Vec(entries, Bool()))
  val mergeCandidateVec = Wire(Vec(entries, Bool()))
  val conflictVec = Wire(Vec(entries, Bool()))
  val partialInsert = io.request.storeType =/= STQStoreType.All

  for (idx <- 0 until entries) {
    val row = io.rows(idx)
    val sameWaitStore = row.valid && (row.status === STQEntryStatus.Wait) && sameStoreId(row, io.request)

    occupiedVec(idx) := row.valid
    mergeCandidateVec(idx) := io.requestValid && partialInsert && sameWaitStore && compatibleMerge(row, io.request)
    conflictVec(idx) := io.requestValid && partialInsert && sameWaitStore && !compatibleMerge(row, io.request)
  }

  val mergeMask = mergeCandidateVec.asUInt
  val conflictMask = conflictVec.asUInt
  val freeMask = ~occupiedVec.asUInt
  val mergeHit = mergeMask.orR
  val conflictHit = conflictMask.orR
  val allocateHit = freeMask.orR
  val canMerge = partialInsert && mergeHit
  val canAllocate = (io.request.storeType === STQStoreType.All || !mergeHit) && allocateHit
  val conflict = io.requestValid && conflictHit && !canMerge
  val ready = !io.flushApplied && !conflict && (canMerge || canAllocate)
  val mergeIndex = OHToUInt(PriorityEncoderOH(mergeMask))
  val allocateIndex = OHToUInt(PriorityEncoderOH(freeMask))

  io.ready := ready
  io.requestReady := io.requestValid && ready
  io.canMerge := io.requestValid && canMerge
  io.canAllocate := io.requestValid && canAllocate
  io.conflict := conflict
  io.mergeMask := mergeMask
  io.conflictMask := conflictMask
  io.freeMask := freeMask
  io.mergeIndex := mergeIndex
  io.allocateIndex := allocateIndex
  io.insertIndex := Mux(canMerge, mergeIndex, allocateIndex)
}
