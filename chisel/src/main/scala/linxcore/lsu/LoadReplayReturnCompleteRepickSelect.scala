package linxcore.lsu

import chisel3._
import chisel3.util.{OHToUInt, PopCount, log2Ceil}

import linxcore.commit.{CommitOperandTrace, CommitTraceParams}
import linxcore.rob.ROBID

class LoadReplayReturnCompleteRepickSelectIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)
  private val countWidth = log2Ceil(liqEntries + 1)
  private val sourceTraceParams =
    CommitTraceParams(regWidth = math.max(8, archRegWidth), dataWidth = addrWidth)

  val enable = Input(Bool())
  val rows = Input(Vec(
    liqEntries,
    new LoadInflightRow(
      liqEntries,
      idEntries,
      storeEntries,
      addrWidth,
      pcWidth,
      lineBytes,
      sizeWidth,
      archRegWidth,
      physRegWidth,
      lsidWidth = lsidWidth
    )
  ))

  val repickMask = Output(UInt(liqEntries.W))
  val sourceReturnedMask = Output(UInt(liqEntries.W))
  val dataCompleteMask = Output(UInt(liqEntries.W))
  val requestCompleteMask = Output(UInt(liqEntries.W))
  val returnCandidateMask = Output(UInt(liqEntries.W))
  val returnMask = Output(UInt(liqEntries.W))
  val returnValid = Output(Bool())
  val returnIndex = Output(UInt(liqPtrWidth.W))
  val candidateCount = Output(UInt(countWidth.W))

  val selectedLoadId = Output(new ROBID(liqEntries))
  val selectedBid = Output(new ROBID(idEntries))
  val selectedGid = Output(new ROBID(idEntries))
  val selectedRid = Output(new ROBID(idEntries))
  val selectedLoadLsId = Output(new ROBID(idEntries))
  val selectedPc = Output(UInt(pcWidth.W))
  val selectedAddr = Output(UInt(addrWidth.W))
  val selectedSize = Output(UInt(sizeWidth.W))
  val selectedReturnSignExtend = Output(Bool())
  val selectedDst = Output(new LoadReplayDestination(archRegWidth, physRegWidth))
  val selectedSourceTraceValid = Output(Bool())
  val selectedSource0 = Output(new CommitOperandTrace(sourceTraceParams))
  val selectedSource1 = Output(new CommitOperandTrace(sourceTraceParams))
  val selectedRequestByteMask = Output(UInt(lineBytes.W))
  val selectedLineData = Output(UInt((lineBytes * 8).W))
  val selectedValidMask = Output(UInt(lineBytes.W))
  val selectedSpecWakeup = Output(Bool())
  val selectedStackValid = Output(Bool())
}

class LoadReplayReturnCompleteRepickSelect(
    val liqEntries: Int = 16,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val lsidWidth: Int = 32)
    extends Module {
  require(liqEntries > 1, "LIQ return selector entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ return selector entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(addrWidth >= 7, "LoadReplayReturnCompleteRepickSelect needs 64-byte line addresses")
  require(lineBytes == 64, "LoadReplayReturnCompleteRepickSelect currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  private val liqPtrWidth = log2Ceil(liqEntries)
  private val lineOffsetWidth = log2Ceil(lineBytes)

  val io = IO(new LoadReplayReturnCompleteRepickSelectIO(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    lsidWidth
  ))

  private def zeroId(entries: Int): ROBID =
    ROBID.disabled(entries)

  private def requestByteMask(row: LoadInflightRow): UInt = {
    val mask = Wire(Vec(lineBytes, Bool()))
    val offset = Wire(UInt(sizeWidth.W))
    offset := row.addr(lineOffsetWidth - 1, 0)
    val end = offset +& row.size
    for (byte <- 0 until lineBytes) {
      val byteIndex = byte.U(end.getWidth.W)
      mask(byte) := row.valid && row.size =/= 0.U && byteIndex >= offset && byteIndex < end
    }
    mask.asUInt
  }

  val requestByteMasks = Wire(Vec(liqEntries, UInt(lineBytes.W)))
  val repickVec = Wire(Vec(liqEntries, Bool()))
  val sourceReturnedVec = Wire(Vec(liqEntries, Bool()))
  val dataCompleteVec = Wire(Vec(liqEntries, Bool()))
  val requestCompleteVec = Wire(Vec(liqEntries, Bool()))
  val returnCandidateVec = Wire(Vec(liqEntries, Bool()))

  for (idx <- 0 until liqEntries) {
    val row = io.rows(idx)
    val requestMask = requestByteMask(row)
    val requestComplete = requestMask.orR && ((row.validMask & requestMask) === requestMask)
    val repick = row.valid && (row.status === LoadInflightStatus.Repick)
    val sourceReturned = row.sourcesReturned && row.scbReturned && row.stqReturned
    val dataComplete = row.dataComplete && requestComplete

    requestByteMasks(idx) := requestMask
    repickVec(idx) := repick
    sourceReturnedVec(idx) := repick && sourceReturned
    dataCompleteVec(idx) := repick && dataComplete
    requestCompleteVec(idx) := repick && requestComplete
    returnCandidateVec(idx) :=
      io.enable && repick && sourceReturned && dataComplete && !row.waitStore && !row.isTile
  }

  val selectedVec = Wire(Vec(liqEntries, Bool()))
  for (idx <- 0 until liqEntries) {
    val olderCandidateVec = Wire(Vec(liqEntries, Bool()))
    for (other <- 0 until liqEntries) {
      val sameOrder =
        ROBID.equal(io.rows(other).bid, io.rows(idx).bid) &&
          ROBID.equal(io.rows(other).loadLsId, io.rows(idx).loadLsId)
      val strictlyOlder =
        STQCommitQueue.lessEqualBidLs(io.rows(other).bid, io.rows(other).loadLsId, io.rows(idx).bid, io.rows(idx).loadLsId) &&
          !sameOrder
      val tieOlder = sameOrder && (other < idx).B
      olderCandidateVec(other) := returnCandidateVec(other) && (strictlyOlder || tieOlder)
    }
    selectedVec(idx) := returnCandidateVec(idx) && !olderCandidateVec.asUInt.orR
  }

  val returnMask = selectedVec.asUInt
  val returnValid = returnMask.orR
  val returnIndex = Wire(UInt(liqPtrWidth.W))
  returnIndex := 0.U
  if (liqEntries > 1) {
    returnIndex := OHToUInt(returnMask)
  }

  val selectedRow = io.rows(returnIndex)

  io.repickMask := repickVec.asUInt
  io.sourceReturnedMask := sourceReturnedVec.asUInt
  io.dataCompleteMask := dataCompleteVec.asUInt
  io.requestCompleteMask := requestCompleteVec.asUInt
  io.returnCandidateMask := returnCandidateVec.asUInt
  io.returnMask := returnMask
  io.returnValid := returnValid
  io.returnIndex := returnIndex
  io.candidateCount := PopCount(returnCandidateVec)

  io.selectedLoadId := zeroId(liqEntries)
  io.selectedBid := zeroId(idEntries)
  io.selectedGid := zeroId(idEntries)
  io.selectedRid := zeroId(idEntries)
  io.selectedLoadLsId := zeroId(idEntries)
  io.selectedPc := 0.U
  io.selectedAddr := 0.U
  io.selectedSize := 0.U
  io.selectedReturnSignExtend := false.B
  io.selectedDst := LoadReplayDestination.none(archRegWidth, physRegWidth)
  io.selectedSourceTraceValid := false.B
  io.selectedSource0 := 0.U.asTypeOf(io.selectedSource0)
  io.selectedSource1 := 0.U.asTypeOf(io.selectedSource1)
  io.selectedRequestByteMask := 0.U
  io.selectedLineData := 0.U
  io.selectedValidMask := 0.U
  io.selectedSpecWakeup := false.B
  io.selectedStackValid := false.B
  when(returnValid) {
    io.selectedLoadId := selectedRow.loadId
    io.selectedBid := selectedRow.bid
    io.selectedGid := selectedRow.gid
    io.selectedRid := selectedRow.rid
    io.selectedLoadLsId := selectedRow.loadLsId
    io.selectedPc := selectedRow.pc
    io.selectedAddr := selectedRow.addr
    io.selectedSize := selectedRow.size
    io.selectedReturnSignExtend := selectedRow.returnSignExtend
    io.selectedDst := selectedRow.dst
    io.selectedSourceTraceValid := selectedRow.sourceTraceValid
    io.selectedSource0 := selectedRow.source0
    io.selectedSource1 := selectedRow.source1
    io.selectedRequestByteMask := requestByteMasks(returnIndex)
    io.selectedLineData := selectedRow.lineData
    io.selectedValidMask := selectedRow.validMask
    io.selectedSpecWakeup := selectedRow.specWakeup
    io.selectedStackValid := selectedRow.stackValid
  }
}
