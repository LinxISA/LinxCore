package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Fill, log2Ceil}

class LoadRefillWakeupRequest(
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  val isRead = Bool()
  val lineAddr = UInt(addrWidth.W)
  val data = UInt((lineBytes * 8).W)
  val l2Miss = Bool()
}

class LoadRefillWakeupIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Bundle {
  val refillValid = Input(Bool())
  val refill = Input(new LoadRefillWakeupRequest(addrWidth, lineBytes))
  val rows = Input(Vec(liqEntries, new LoadInflightRow(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth)))

  val refillAccepted = Output(Bool())
  val wakeMask = Output(UInt(liqEntries.W))
  val requestByteMasks = Output(Vec(liqEntries, UInt(lineBytes.W)))
  val lineValidMask = Output(UInt(lineBytes.W))
}

class LoadRefillWakeup(
    val liqEntries: Int = 16,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(addrWidth >= 7, "LoadRefillWakeup needs 64-byte line addresses")
  require(lineBytes == 64, "LoadRefillWakeup currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  private val lineOffsetWidth = log2Ceil(lineBytes)

  val io = IO(new LoadRefillWakeupIO(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))

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

  val refillAccepted = io.refillValid && io.refill.isRead
  val wakeVec = Wire(Vec(liqEntries, Bool()))

  for (idx <- 0 until liqEntries) {
    val row = io.rows(idx)
    val sameLine = activeLineAddr(row) === io.refill.lineAddr
    wakeVec(idx) :=
      refillAccepted &&
        isWorking(row) &&
        sameLine &&
        !row.l1Hit &&
        !row.isTile
    io.requestByteMasks(idx) := requestByteMask(row)
  }

  io.refillAccepted := refillAccepted
  io.wakeMask := wakeVec.asUInt
  io.lineValidMask := Fill(lineBytes, refillAccepted)
}
