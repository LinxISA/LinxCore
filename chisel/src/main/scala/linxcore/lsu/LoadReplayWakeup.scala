package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, log2Ceil}

import linxcore.rob.ROBID

object LoadReplayWakeSource extends ChiselEnum {
  val StoreUnit, StoreCoalescingBuffer = Value
}

class LoadReplayWakeupRequest(
    val idEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32)
    extends Bundle {
  val source = LoadReplayWakeSource()
  val storeId = new ROBID(idEntries)
  val storeLsId = new ROBID(idEntries)
  val storeLsIdFullValid = Bool()
  val storeLsIdFull = UInt(lsidWidth.W)
  val pc = UInt(pcWidth.W)
  val lineAddr = UInt(addrWidth.W)
  val validMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
}

class LoadReplayWakeupIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val lsidWidth: Int = 32)
    extends Bundle {
  val wakeValid = Input(Bool())
  val wake = Input(new LoadReplayWakeupRequest(idEntries, addrWidth, pcWidth, lineBytes, lsidWidth))
  val rows = Input(Vec(liqEntries, new LoadInflightRow(
    liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth,
    lsidWidth = lsidWidth)))

  val waitStoreClearMask = Output(UInt(liqEntries.W))
  val mergeMask = Output(UInt(liqEntries.W))
  val completedMask = Output(UInt(liqEntries.W))
  val requestByteMasks = Output(Vec(liqEntries, UInt(lineBytes.W)))
  val mergedValidMasks = Output(Vec(liqEntries, UInt(lineBytes.W)))
  val mergedLineData = Output(Vec(liqEntries, UInt((lineBytes * 8).W)))
}

class LoadReplayWakeup(
    val liqEntries: Int = 16,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val lsidWidth: Int = 32)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(addrWidth >= 7, "LoadReplayWakeup needs 64-byte line addresses")
  require(lineBytes == 64, "LoadReplayWakeup currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  private val lineOffsetWidth = log2Ceil(lineBytes)

  val io = IO(new LoadReplayWakeupIO(
    liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth, lsidWidth))

  val waitStoreDiagnostics =
    Module(new LoadReplayWakeMatchDiagnostics(
      liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth,
      lsidWidth = lsidWidth))
  waitStoreDiagnostics.io.wakeValid := io.wakeValid
  waitStoreDiagnostics.io.wake := io.wake
  waitStoreDiagnostics.io.rows := io.rows

  private def lineAddr(addr: UInt): UInt =
    Cat(addr(addrWidth - 1, lineOffsetWidth), 0.U(lineOffsetWidth.W))

  private def activeSecondSegment(row: LoadInflightRow): Bool =
    row.crossLine && row.secondSegmentActive

  private def activeLineAddr(row: LoadInflightRow): UInt =
    Mux(activeSecondSegment(row), lineAddr(row.addr) + lineBytes.U, lineAddr(row.addr))

  private def requestByteMask(row: LoadInflightRow): UInt = {
    val mask = Wire(Vec(lineBytes, Bool()))
    val offset = Wire(UInt(sizeWidth.W))
    offset := Mux(activeSecondSegment(row), 0.U, row.addr(lineOffsetWidth - 1, 0))
    val firstSize = lineBytes.U(sizeWidth.W) - row.addr(lineOffsetWidth - 1, 0)
    val size = Mux(activeSecondSegment(row), row.size - firstSize, Mux(row.crossLine, firstSize, row.size))
    val end = offset +& size
    for (byte <- 0 until lineBytes) {
      val byteIndex = byte.U(end.getWidth.W)
      mask(byte) := row.valid && size =/= 0.U && byteIndex >= offset && byteIndex < end
    }
    mask.asUInt
  }

  private def isWorking(row: LoadInflightRow): Bool =
    row.valid && (row.status =/= LoadInflightStatus.Idle) && (row.status =/= LoadInflightStatus.Resolved)

  val mergeVec = Wire(Vec(liqEntries, Bool()))
  val completedVec = Wire(Vec(liqEntries, Bool()))

  for (idx <- 0 until liqEntries) {
    val row = io.rows(idx)
    val sameLine = activeLineAddr(row) === io.wake.lineAddr
    val requestMask = requestByteMask(row)
    val mergedMask = row.validMask | io.wake.validMask
    val completed = (requestMask =/= 0.U) && ((mergedMask & requestMask) === requestMask)
    val storeWake = io.wake.source === LoadReplayWakeSource.StoreUnit
    val scbWake = io.wake.source === LoadReplayWakeSource.StoreCoalescingBuffer
    val wakeBeforeOrAtSnapshot =
      STQCommitQueue.lessEqualBidLs(io.wake.storeId, io.wake.storeLsId, row.youngestStoreId, row.youngestStoreLsId)
    val storeMissEligible =
      storeWake &&
        row.valid &&
        sameLine &&
        ((row.status === LoadInflightStatus.L1DcMiss) || (row.status === LoadInflightStatus.L2Wait)) &&
        wakeBeforeOrAtSnapshot
    val scbEligible =
      scbWake &&
        isWorking(row) &&
        sameLine &&
        (row.status =/= LoadInflightStatus.Repick)

    mergeVec(idx) := io.wakeValid && (io.wake.validMask =/= 0.U) && (storeMissEligible || scbEligible)
    completedVec(idx) := mergeVec(idx) && completed

    io.requestByteMasks(idx) := requestMask
    io.mergedValidMasks(idx) := mergedMask

    val mergedBytes = Wire(Vec(lineBytes, UInt(8.W)))
    for (byte <- 0 until lineBytes) {
      val rowByte = row.lineData((byte * 8) + 7, byte * 8)
      val wakeByte = io.wake.data((byte * 8) + 7, byte * 8)
      mergedBytes(byte) := Mux(io.wake.validMask(byte), wakeByte, rowByte)
    }
    io.mergedLineData(idx) := Cat(mergedBytes.reverse)
  }

  io.waitStoreClearMask := waitStoreDiagnostics.io.storeUnitFullMatchMask
  io.mergeMask := mergeVec.asUInt
  io.completedMask := completedVec.asUInt
}
