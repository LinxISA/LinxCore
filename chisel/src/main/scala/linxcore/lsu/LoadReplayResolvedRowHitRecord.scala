package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, log2Ceil}

import linxcore.rob.ROBID

class LoadReplayResolvedRowHitRecordIO(
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
  val enable = Input(Bool())
  val row = Input(new LoadInflightRow(
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
  ))
  val useResolvedImage = Input(Bool())
  val resolvedLineData = Input(UInt((lineBytes * 8).W))
  val resolvedValidMask = Input(UInt(lineBytes.W))
  val resolvedDataComplete = Input(Bool())
  val resolvedSourcesReturned = Input(Bool())
  val resolvedScbReturned = Input(Bool())
  val resolvedStqReturned = Input(Bool())

  val recordValid = Output(Bool())
  val record = Output(new LoadHitRecord(
    liqEntries, idEntries, addrWidth, lineBytes, sizeWidth, pcWidth, lsidWidth))
  val blockedByInvalidRow = Output(Bool())
  val blockedByNotRepick = Output(Bool())
  val blockedByIncompleteReturn = Output(Bool())
  val blockedByWaitStore = Output(Bool())
  val blockedByTile = Output(Bool())
}

class LoadReplayResolvedRowHitRecord(
    val liqEntries: Int = 4,
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
  require(lineBytes > 1, "lineBytes must be greater than one")
  require((lineBytes & (lineBytes - 1)) == 0, "lineBytes must be a power of two")

  private val lineOffsetWidth = log2Ceil(lineBytes)

  val io = IO(new LoadReplayResolvedRowHitRecordIO(
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

  private def zeroRecord: LoadHitRecord = {
    val record = Wire(new LoadHitRecord(
      liqEntries, idEntries, addrWidth, lineBytes, sizeWidth, pcWidth, lsidWidth))
    record := 0.U.asTypeOf(record)
    record.loadId := ROBID.disabled(liqEntries)
    record.bid := ROBID.disabled(idEntries)
    record.gid := ROBID.disabled(idEntries)
    record.rid := ROBID.disabled(idEntries)
    record.loadLsId := ROBID.disabled(idEntries)
    record
  }

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

  val row = io.row
  val candidateLineData = Mux(io.useResolvedImage, io.resolvedLineData, row.lineData)
  val candidateValidMask = Mux(io.useResolvedImage, io.resolvedValidMask, row.validMask)
  val candidateDataComplete = Mux(io.useResolvedImage, io.resolvedDataComplete, row.dataComplete)
  val candidateSourcesReturned = Mux(io.useResolvedImage, io.resolvedSourcesReturned, row.sourcesReturned)
  val candidateScbReturned = Mux(io.useResolvedImage, io.resolvedScbReturned, row.scbReturned)
  val candidateStqReturned = Mux(io.useResolvedImage, io.resolvedStqReturned, row.stqReturned)
  val computedByteMask = requestByteMask(row)
  val candidateByteMask = Mux(row.loadByteMask.orR || !io.useResolvedImage, row.loadByteMask, computedByteMask)
  val completeReturn =
    candidateDataComplete &&
      candidateSourcesReturned &&
      candidateScbReturned &&
      candidateStqReturned &&
      candidateByteMask.orR &&
      ((candidateValidMask & candidateByteMask) === candidateByteMask)
  val candidate =
    io.enable &&
      row.valid &&
      (row.status === LoadInflightStatus.Repick) &&
      completeReturn &&
      !row.waitStore &&
      !row.isTile

  val record = Wire(chiselTypeOf(io.record))
  record := zeroRecord
  record.loadId := row.loadId
  record.bid := row.bid
  record.gid := row.gid
  record.rid := row.rid
  record.loadLsId := row.loadLsId
  record.loadLsIdFullValid := row.loadLsIdFullValid
  record.loadLsIdFull := row.loadLsIdFull
  record.pc := row.pc
  record.addr := row.addr
  record.lineAddr := Cat(row.addr(addrWidth - 1, lineOffsetWidth), 0.U(lineOffsetWidth.W))
  record.size := row.size
  record.byteMask := candidateByteMask
  record.data := candidateLineData
  record.forwardedMask := row.forwardMask

  io.recordValid := candidate
  io.record := record
  io.blockedByInvalidRow := io.enable && !row.valid
  io.blockedByNotRepick := io.enable && row.valid && (row.status =/= LoadInflightStatus.Repick)
  io.blockedByIncompleteReturn :=
    io.enable && row.valid && (row.status === LoadInflightStatus.Repick) && !completeReturn
  io.blockedByWaitStore :=
    io.enable && row.valid && (row.status === LoadInflightStatus.Repick) && completeReturn && row.waitStore
  io.blockedByTile :=
    io.enable && row.valid && (row.status === LoadInflightStatus.Repick) && completeReturn && !row.waitStore && row.isTile
}
