package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, OHToUInt, PopCount, log2Ceil}

import linxcore.rob.ROBID

class LoadInflightLaunchSelectIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)
  private val countWidth = log2Ceil(liqEntries + 1)

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
      physRegWidth
    )
  ))

  val waitMask = Output(UInt(liqEntries.W))
  val waitStoreBlockedMask = Output(UInt(liqEntries.W))
  val tileBlockedMask = Output(UInt(liqEntries.W))
  val unblockedWaitMask = Output(UInt(liqEntries.W))
  val requestCompleteMask = Output(UInt(liqEntries.W))
  val dataHitMask = Output(UInt(liqEntries.W))
  val launchCandidateMask = Output(UInt(liqEntries.W))
  val launchMask = Output(UInt(liqEntries.W))
  val launchValid = Output(Bool())
  val launchIndex = Output(UInt(liqPtrWidth.W))
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
  val selectedRequestByteMask = Output(UInt(lineBytes.W))
  val selectedSpecWakeup = Output(Bool())
  val selectedStackValid = Output(Bool())
}

class LoadInflightLaunchSelect(
    val liqEntries: Int = 16,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(liqEntries > 1, "LIQ launch selector entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ launch selector entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(addrWidth >= 7, "LoadInflightLaunchSelect needs 64-byte line addresses")
  require(lineBytes == 64, "LoadInflightLaunchSelect currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  private val liqPtrWidth = log2Ceil(liqEntries)
  private val lineOffsetWidth = log2Ceil(lineBytes)

  val io = IO(new LoadInflightLaunchSelectIO(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth
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
  val waitVec = Wire(Vec(liqEntries, Bool()))
  val waitStoreBlockedVec = Wire(Vec(liqEntries, Bool()))
  val tileBlockedVec = Wire(Vec(liqEntries, Bool()))
  val unblockedWaitVec = Wire(Vec(liqEntries, Bool()))
  val requestCompleteVec = Wire(Vec(liqEntries, Bool()))
  val dataHitVec = Wire(Vec(liqEntries, Bool()))
  val launchCandidateVec = Wire(Vec(liqEntries, Bool()))

  for (idx <- 0 until liqEntries) {
    val row = io.rows(idx)
    val requestMask = requestByteMask(row)
    val requestComplete = requestMask.orR && ((row.validMask & requestMask) === requestMask)
    val rowDataHit = row.l1Hit || row.storeBypass || requestComplete
    val wait = row.valid && (row.status === LoadInflightStatus.Wait)
    val unblockedWait = wait && !row.waitStore && !row.isTile

    requestByteMasks(idx) := requestMask
    waitVec(idx) := wait
    waitStoreBlockedVec(idx) := wait && row.waitStore
    tileBlockedVec(idx) := wait && !row.waitStore && row.isTile
    unblockedWaitVec(idx) := unblockedWait
    requestCompleteVec(idx) := unblockedWait && requestComplete
    dataHitVec(idx) := unblockedWait && rowDataHit
    launchCandidateVec(idx) := io.enable && dataHitVec(idx)
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
      olderCandidateVec(other) := launchCandidateVec(other) && (strictlyOlder || tieOlder)
    }
    selectedVec(idx) := launchCandidateVec(idx) && !olderCandidateVec.asUInt.orR
  }

  val launchMask = selectedVec.asUInt
  val launchValid = launchMask.orR
  val launchIndex = Wire(UInt(liqPtrWidth.W))
  launchIndex := 0.U
  if (liqEntries > 1) {
    launchIndex := OHToUInt(launchMask)
  }

  val selectedRow = io.rows(launchIndex)

  io.waitMask := waitVec.asUInt
  io.waitStoreBlockedMask := waitStoreBlockedVec.asUInt
  io.tileBlockedMask := tileBlockedVec.asUInt
  io.unblockedWaitMask := unblockedWaitVec.asUInt
  io.requestCompleteMask := requestCompleteVec.asUInt
  io.dataHitMask := dataHitVec.asUInt
  io.launchCandidateMask := launchCandidateVec.asUInt
  io.launchMask := launchMask
  io.launchValid := launchValid
  io.launchIndex := launchIndex
  io.candidateCount := PopCount(launchCandidateVec)

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
  io.selectedRequestByteMask := 0.U
  io.selectedSpecWakeup := false.B
  io.selectedStackValid := false.B
  when(launchValid) {
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
    io.selectedRequestByteMask := requestByteMasks(launchIndex)
    io.selectedSpecWakeup := selectedRow.specWakeup
    io.selectedStackValid := selectedRow.stackValid
  }
}
