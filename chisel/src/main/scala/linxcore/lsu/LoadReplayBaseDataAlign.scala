package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, log2Ceil}

class LoadReplayBaseDataAlignIO(
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val lineBytes: Int = 64)
    extends Bundle {
  val enable = Input(Bool())
  val loadValid = Input(Bool())
  val loadAddr = Input(UInt(addrWidth.W))
  val loadSize = Input(UInt(sizeWidth.W))
  val loadData = Input(UInt(dataWidth.W))

  val requestValid = Output(Bool())
  val requestCrossesLine = Output(Bool())
  val requestByteMask = Output(UInt(lineBytes.W))
  val lineData = Output(UInt((lineBytes * 8).W))
  val lineValidMask = Output(UInt(lineBytes.W))
  val dataReturned = Output(Bool())
}

class LoadReplayBaseDataAlign(
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val lineBytes: Int = 64)
    extends Module {
  require(addrWidth >= 7, "load replay base data align needs 64-byte line addresses")
  require(dataWidth == 64, "load replay base data align currently accepts one 64-bit scalar response")
  require(sizeWidth >= 7, "load replay base data align sizeWidth must cover 64-byte scalar lines")
  require(lineBytes == 64, "load replay base data align currently models 64-byte cachelines")

  private val loadBytes = dataWidth / 8
  private val offsetWidth = log2Ceil(lineBytes)

  val io = IO(new LoadReplayBaseDataAlignIO(addrWidth, dataWidth, sizeWidth, lineBytes))

  val offset = Wire(UInt(sizeWidth.W))
  val size = Wire(UInt(sizeWidth.W))
  offset := io.loadAddr(offsetWidth - 1, 0)
  size := io.loadSize
  val crosses = (offset +& size) > lineBytes.U
  val valid = io.enable && io.loadValid && (size =/= 0.U) && !crosses

  val mask = Wire(Vec(lineBytes, Bool()))
  val end = offset +& size
  for (byte <- 0 until lineBytes) {
    val byteIdx = byte.U(end.getWidth.W)
    mask(byte) := valid && (byteIdx >= offset) && (byteIdx < end)
  }

  val bytes = Wire(Vec(lineBytes, UInt(8.W)))
  for (byte <- 0 until lineBytes) {
    bytes(byte) := 0.U
  }
  for (loadByte <- 0 until loadBytes) {
    val target = io.loadAddr(offsetWidth - 1, 0) + loadByte.U((offsetWidth + 1).W)
    for (byte <- 0 until lineBytes) {
      when(valid && (target === byte.U(target.getWidth.W))) {
        bytes(byte) := io.loadData((loadByte * 8) + 7, loadByte * 8)
      }
    }
  }

  io.requestValid := valid
  io.requestCrossesLine := io.enable && io.loadValid && (size =/= 0.U) && crosses
  io.requestByteMask := mask.asUInt
  io.lineData := Cat(bytes.reverse)
  io.lineValidMask := mask.asUInt
  io.dataReturned := valid
}
