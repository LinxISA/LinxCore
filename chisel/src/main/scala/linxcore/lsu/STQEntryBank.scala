package linxcore.lsu

import chisel3._
import chisel3.util._

import linxcore.common.{DestinationKind, InterfaceParams, TULinkFlushSequenceSource}
import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

object STQStoreType extends ChiselEnum {
  val All, Addr, Data = Value
}

class STQStoreRequest(
    val entries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val pcWidth: Int = 64)
    extends Bundle {
  val storeType = STQStoreType()
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val bid = new ROBID(entries)
  val gid = new ROBID(entries)
  val rid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val tSeq = new ROBID(mapQDepth)
  val uSeq = new ROBID(mapQDepth)
  val tuDstValid = Bool()
  val tuDstKind = DestinationKind()
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
  val size = UInt(sizeWidth.W)
  val stackValid = Bool()
  val scalarIex = Bool()
  val simtLane = UInt(simtLaneWidth.W)
}

class STQEntryBankRow(
    val entries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val pcWidth: Int = 64)
    extends Bundle {
  val valid = Bool()
  val status = STQEntryStatus()
  val storeType = STQStoreType()
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val bid = new ROBID(entries)
  val gid = new ROBID(entries)
  val rid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val tSeq = new ROBID(mapQDepth)
  val uSeq = new ROBID(mapQDepth)
  val tuDstValid = Bool()
  val tuDstKind = DestinationKind()
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
  val size = UInt(sizeWidth.W)
  val stackValid = Bool()
  val scalarIex = Bool()
  val simtLane = UInt(simtLaneWidth.W)
  val addrReady = Bool()
  val dataReady = Bool()
}

class STQEntryBankIO(
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
  private val countWidth = log2Ceil(entries + 1)
  private val sourceParams = InterfaceParams(robEntries = entries)

  val flush = Input(new FlushBus(entries, peIdWidth, stidWidth, tidWidth))

  val insertValid = Input(Bool())
  val insert = Input(new STQStoreRequest(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth))
  val insertReady = Output(Bool())
  val insertAccepted = Output(Bool())
  val insertAllocated = Output(Bool())
  val insertMerged = Output(Bool())
  val insertConflict = Output(Bool())
  val insertIndex = Output(UInt(ptrWidth.W))

  val markCommitValid = Input(Bool())
  val markCommitIndex = Input(UInt(ptrWidth.W))
  val markCommitAccepted = Output(Bool())
  val markCommitIgnored = Output(Bool())

  val commitFreeValid = Input(Bool())
  val commitFreeIndex = Input(UInt(ptrWidth.W))
  val commitFreeAccepted = Output(Bool())
  val commitFreeIgnored = Output(Bool())
  val commitFreeMaskValid = Input(Bool())
  val commitFreeMask = Input(UInt(entries.W))
  val commitFreeAcceptedMask = Output(UInt(entries.W))
  val commitFreeIgnoredMask = Output(UInt(entries.W))
  val commitFreeCount = Output(UInt(countWidth.W))

  val flushApplied = Output(Bool())
  val flushMatchMask = Output(UInt(entries.W))
  val flushFreeMask = Output(UInt(entries.W))
  val flushStatusBlockedMask = Output(UInt(entries.W))
  val flushFreeCount = Output(UInt(countWidth.W))
  val lsuTULinkSource = Output(new TULinkFlushSequenceSource(sourceParams, mapQDepth, stidWidth))
  val lsuTULinkSourceMatched = Output(Bool())
  val lsuTULinkSourceMultipleMatch = Output(Bool())

  val rows = Output(Vec(entries, new STQEntryBankRow(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth)))
  val occupiedMask = Output(UInt(entries.W))
  val waitMask = Output(UInt(entries.W))
  val commitMask = Output(UInt(entries.W))
  val addrReadyMask = Output(UInt(entries.W))
  val dataReadyMask = Output(UInt(entries.W))

  val empty = Output(Bool())
  val full = Output(Bool())
  val stall = Output(Bool())
  val residentCount = Output(UInt(countWidth.W))
  val outstandingWaitCount = Output(UInt(countWidth.W))
}

class STQEntryBank(
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
  require(entries > 1, "STQ entries must be greater than one")
  require((entries & (entries - 1)) == 0, "STQ entries must be a power of two")

  private val countWidth = log2Ceil(entries + 1)

  private val sourceParams = InterfaceParams(robEntries = entries)

  val io = IO(new STQEntryBankIO(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth))

  private def zeroRow: STQEntryBankRow = {
    val row = Wire(new STQEntryBankRow(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth))
    row := 0.U.asTypeOf(row)
    row.status := STQEntryStatus.Idle
    row
  }

  private def zeroSource: TULinkFlushSequenceSource = {
    val source = Wire(new TULinkFlushSequenceSource(sourceParams, mapQDepth, stidWidth))
    source := 0.U.asTypeOf(source)
    source
  }

  private def requestToRow(req: STQStoreRequest): STQEntryBankRow = {
    val row = Wire(new STQEntryBankRow(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth))
    row := 0.U.asTypeOf(row)
    row.valid := true.B
    row.status := STQEntryStatus.Wait
    row.storeType := req.storeType
    row.peId := req.peId
    row.stid := req.stid
    row.tid := req.tid
    row.bid := req.bid
    row.gid := req.gid
    row.rid := req.rid
    row.lsId := req.lsId
    row.tSeq := req.tSeq
    row.uSeq := req.uSeq
    row.tuDstValid := req.tuDstValid
    row.tuDstKind := req.tuDstKind
    row.pc := req.pc
    row.addr := req.addr
    row.data := req.data
    row.size := req.size
    row.stackValid := req.stackValid
    row.scalarIex := req.scalarIex
    row.simtLane := req.simtLane
    row.addrReady := (req.storeType === STQStoreType.All) || (req.storeType === STQStoreType.Addr)
    row.dataReady := (req.storeType === STQStoreType.All) || (req.storeType === STQStoreType.Data)
    row
  }

  private def mergeRow(row: STQEntryBankRow, req: STQStoreRequest): STQEntryBankRow = {
    val out = Wire(new STQEntryBankRow(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth))
    out := row
    out.storeType := STQStoreType.All
    out.stackValid := row.stackValid || req.stackValid
    when(req.storeType === STQStoreType.Addr) {
      out.addrReady := true.B
      out.addr := req.addr
      out.size := req.size
    }
    when(req.storeType === STQStoreType.Data) {
      out.dataReady := true.B
      out.data := req.data
    }
    out
  }

  val rows = RegInit(VecInit(Seq.fill(entries)(zeroRow)))
  val residentCount = RegInit(0.U(countWidth.W))
  val outstandingWaitCount = RegInit(0.U(countWidth.W))

  val occupiedVec = Wire(Vec(entries, Bool()))
  val waitVec = Wire(Vec(entries, Bool()))
  val commitVec = Wire(Vec(entries, Bool()))
  val addrReadyVec = Wire(Vec(entries, Bool()))
  val dataReadyVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    occupiedVec(idx) := rows(idx).valid
    waitVec(idx) := rows(idx).valid && (rows(idx).status === STQEntryStatus.Wait)
    commitVec(idx) := rows(idx).valid && (rows(idx).status === STQEntryStatus.Commit)
    addrReadyVec(idx) := rows(idx).valid && rows(idx).addrReady
    dataReadyVec(idx) := rows(idx).valid && rows(idx).dataReady
    io.rows(idx) := rows(idx)
  }

  io.occupiedMask := occupiedVec.asUInt
  io.waitMask := waitVec.asUInt
  io.commitMask := commitVec.asUInt
  io.addrReadyMask := addrReadyVec.asUInt
  io.dataReadyMask := dataReadyVec.asUInt
  io.empty := residentCount === 0.U
  io.full := residentCount === entries.U
  io.stall := io.full && (outstandingWaitCount === residentCount)
  io.residentCount := residentCount
  io.outstandingWaitCount := outstandingWaitCount

  val flushPrune = Module(new STQFlushPrune(entries, peIdWidth, stidWidth, tidWidth))
  flushPrune.io.flush := io.flush
  for (idx <- 0 until entries) {
    flushPrune.io.rows(idx).valid := rows(idx).valid
    flushPrune.io.rows(idx).status := rows(idx).status
    flushPrune.io.rows(idx).peId := rows(idx).peId
    flushPrune.io.rows(idx).stid := rows(idx).stid
    flushPrune.io.rows(idx).tid := rows(idx).tid
    flushPrune.io.rows(idx).bid := rows(idx).bid
    flushPrune.io.rows(idx).gid := rows(idx).gid
    flushPrune.io.rows(idx).lsId := rows(idx).lsId
  }

  val flushApplied = flushPrune.io.freeMask.orR
  io.flushApplied := flushApplied
  io.flushMatchMask := flushPrune.io.matchMask
  io.flushFreeMask := flushPrune.io.freeMask
  io.flushStatusBlockedMask := flushPrune.io.statusBlockedMask
  io.flushFreeCount := flushPrune.io.freeCount

  val lsuSourceMatchVec = Wire(Vec(entries, Bool()))
  val sourceRequired = io.flush.req.valid && !io.flush.baseOnBid
  for (idx <- 0 until entries) {
    lsuSourceMatchVec(idx) :=
      sourceRequired &&
        rows(idx).valid &&
        ROBID.equal(rows(idx).bid, io.flush.req.bid) &&
        ROBID.equal(rows(idx).rid, io.flush.req.rid) &&
        (rows(idx).stid === io.flush.req.stid)
  }

  val lsuSourceSelected = Wire(new STQEntryBankRow(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth))
  lsuSourceSelected := zeroRow
  for (idx <- 0 until entries) {
    when(lsuSourceMatchVec(idx)) {
      lsuSourceSelected := rows(idx)
    }
  }

  val lsuSource = Wire(new TULinkFlushSequenceSource(sourceParams, mapQDepth, stidWidth))
  lsuSource := zeroSource
  lsuSource.valid := lsuSourceMatchVec.asUInt.orR
  lsuSource.bid := lsuSourceSelected.bid
  lsuSource.rid := lsuSourceSelected.rid
  lsuSource.stid := lsuSourceSelected.stid
  lsuSource.tSeq := lsuSourceSelected.tSeq
  lsuSource.uSeq := lsuSourceSelected.uSeq
  lsuSource.dstValid := lsuSourceSelected.tuDstValid
  lsuSource.dstKind := lsuSourceSelected.tuDstKind

  io.lsuTULinkSource := lsuSource
  io.lsuTULinkSourceMatched := lsuSource.valid
  io.lsuTULinkSourceMultipleMatch := PopCount(lsuSourceMatchVec) > 1.U

  val insertProbe = Module(new STQInsertProbe(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth))
  insertProbe.io.requestValid := io.insertValid
  insertProbe.io.request := io.insert
  insertProbe.io.rows := rows
  insertProbe.io.flushApplied := flushApplied

  val mergeIndex = insertProbe.io.mergeIndex
  val allocateIndex = insertProbe.io.allocateIndex

  io.insertConflict := insertProbe.io.conflict
  io.insertReady := insertProbe.io.ready
  io.insertAccepted := io.insertValid && io.insertReady
  io.insertMerged := io.insertAccepted && insertProbe.io.canMerge
  io.insertAllocated := io.insertAccepted && !insertProbe.io.canMerge
  io.insertIndex := Mux(io.insertMerged, mergeIndex, allocateIndex)

  val markCommitRow = rows(io.markCommitIndex)
  val markCommitLocalReady =
    markCommitRow.valid &&
      (markCommitRow.status === STQEntryStatus.Wait) &&
      markCommitRow.addrReady &&
      markCommitRow.dataReady &&
      (markCommitRow.storeType === STQStoreType.All)
  io.markCommitAccepted := !flushApplied && io.markCommitValid && markCommitLocalReady
  io.markCommitIgnored := io.markCommitValid && (!markCommitLocalReady || flushApplied)

  val freeCommitRow = rows(io.commitFreeIndex)
  val commitFreeLocalReady = freeCommitRow.valid && (freeCommitRow.status === STQEntryStatus.Commit)
  io.commitFreeAccepted := !flushApplied && io.commitFreeValid && commitFreeLocalReady
  io.commitFreeIgnored := io.commitFreeValid && (!commitFreeLocalReady || flushApplied)

  val commitFreeReqVec = Wire(Vec(entries, Bool()))
  val commitFreeAcceptedVec = Wire(Vec(entries, Bool()))
  val commitFreeIgnoredVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    val singleHit = io.commitFreeValid && (io.commitFreeIndex === idx.U)
    val maskHit = io.commitFreeMaskValid && io.commitFreeMask(idx)
    val rowReady = rows(idx).valid && (rows(idx).status === STQEntryStatus.Commit)
    commitFreeReqVec(idx) := singleHit || maskHit
    commitFreeAcceptedVec(idx) := !flushApplied && commitFreeReqVec(idx) && rowReady
    commitFreeIgnoredVec(idx) := commitFreeReqVec(idx) && (!rowReady || flushApplied)
  }
  io.commitFreeAcceptedMask := commitFreeAcceptedVec.asUInt
  io.commitFreeIgnoredMask := commitFreeIgnoredVec.asUInt
  io.commitFreeCount := PopCount(commitFreeAcceptedVec)

  for (idx <- 0 until entries) {
    when(flushPrune.io.freeMask(idx)) {
      rows(idx) := zeroRow
    }
  }

  when(io.markCommitAccepted) {
    rows(io.markCommitIndex).status := STQEntryStatus.Commit
  }

  for (idx <- 0 until entries) {
    when(commitFreeAcceptedVec(idx)) {
      rows(idx) := zeroRow
    }
  }

  when(io.insertMerged) {
    rows(mergeIndex) := mergeRow(rows(mergeIndex), io.insert)
  }

  when(io.insertAllocated) {
    rows(allocateIndex) := requestToRow(io.insert)
  }

  val allocDelta = io.insertAllocated.asUInt
  val markCommitDelta = io.markCommitAccepted.asUInt
  val commitFreeDelta = io.commitFreeCount
  val flushFreeDelta = Mux(flushApplied, flushPrune.io.freeCount, 0.U)

  residentCount := residentCount + allocDelta - commitFreeDelta - flushFreeDelta
  outstandingWaitCount := outstandingWaitCount + allocDelta - markCommitDelta - flushFreeDelta
}
