package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Fill, is, log2Ceil, switch}

class LoadReplayReturnDataExtractIO(
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val lineBytes: Int = 64)
    extends Bundle {
  val enable = Input(Bool())
  val returnValid = Input(Bool())
  val lineData = Input(UInt((lineBytes * 8).W))
  val lineValidMask = Input(UInt(lineBytes.W))
  val addr = Input(UInt(addrWidth.W))
  val size = Input(UInt(sizeWidth.W))
  val signExtend = Input(Bool())

  val candidateValid = Output(Bool())
  val requestByteMask = Output(UInt(lineBytes.W))
  val bytesComplete = Output(Bool())
  val crossLine = Output(Bool())
  val sizeSupported = Output(Bool())
  val rawData = Output(UInt(dataWidth.W))
  val data = Output(UInt(dataWidth.W))
  val dataValid = Output(Bool())

  val blockedByDisabled = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedByZeroSize = Output(Bool())
  val blockedByUnsupportedSize = Output(Bool())
  val blockedByCrossLine = Output(Bool())
  val blockedByIncompleteBytes = Output(Bool())
}

class LoadReplayReturnDataExtract(
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val lineBytes: Int = 64)
    extends Module {
  require(addrWidth >= 7, "load replay return extractor needs 64-byte line addresses")
  require(dataWidth == 64, "load replay return extractor currently emits scalar 64-bit data")
  require(sizeWidth >= 7, "load replay return extractor sizeWidth must cover 64-byte scalar lines")
  require(lineBytes == 64, "load replay return extractor currently models 64-byte cachelines")

  private val dataBytes = dataWidth / 8
  private val offsetWidth = log2Ceil(lineBytes)

  val io = IO(new LoadReplayReturnDataExtractIO(addrWidth, dataWidth, sizeWidth, lineBytes))

  private def zeroExtend(raw: UInt, bits: Int): UInt =
    if (bits == dataWidth) raw else Cat(0.U((dataWidth - bits).W), raw(bits - 1, 0))

  private def signExtend(raw: UInt, bits: Int): UInt =
    if (bits == dataWidth) raw else Cat(Fill(dataWidth - bits, raw(bits - 1)), raw(bits - 1, 0))

  val candidate = io.enable && io.returnValid
  val offset = Wire(UInt(sizeWidth.W))
  offset := io.addr(offsetWidth - 1, 0)
  val end = offset +& io.size
  val nonZeroSize = io.size =/= 0.U
  val crossesLine = candidate && nonZeroSize && (end > lineBytes.U(end.getWidth.W))
  val sizeSupported =
    (io.size === 1.U) || (io.size === 2.U) || (io.size === 4.U) || (io.size === dataBytes.U)
  val extractCandidate = candidate && nonZeroSize && sizeSupported && !crossesLine

  val mask = Wire(Vec(lineBytes, Bool()))
  for (byte <- 0 until lineBytes) {
    val byteIdx = byte.U(end.getWidth.W)
    mask(byte) := extractCandidate && (byteIdx >= offset) && (byteIdx < end)
  }
  val requestMask = mask.asUInt
  val bytesComplete = extractCandidate && (requestMask =/= 0.U) && ((io.lineValidMask & requestMask) === requestMask)

  val line = Wire(Vec(lineBytes, UInt(8.W)))
  for (byte <- 0 until lineBytes) {
    line(byte) := io.lineData((byte * 8) + 7, byte * 8)
  }

  val rawBytes = Wire(Vec(dataBytes, UInt(8.W)))
  for (outByte <- 0 until dataBytes) {
    rawBytes(outByte) := 0.U
    val source = offset +& outByte.U(sizeWidth.W)
    for (lineByte <- 0 until lineBytes) {
      when(extractCandidate && (outByte.U(sizeWidth.W) < io.size) && (source === lineByte.U(source.getWidth.W))) {
        rawBytes(outByte) := line(lineByte)
      }
    }
  }
  val raw = Cat(rawBytes.reverse)

  val zeroed = Wire(UInt(dataWidth.W))
  zeroed := raw
  switch(io.size) {
    is(1.U) { zeroed := zeroExtend(raw, 8) }
    is(2.U) { zeroed := zeroExtend(raw, 16) }
    is(4.U) { zeroed := zeroExtend(raw, 32) }
    is(dataBytes.U) { zeroed := raw }
  }

  val signed = Wire(UInt(dataWidth.W))
  signed := raw
  switch(io.size) {
    is(1.U) { signed := signExtend(raw, 8) }
    is(2.U) { signed := signExtend(raw, 16) }
    is(4.U) { signed := signExtend(raw, 32) }
    is(dataBytes.U) { signed := raw }
  }

  val dataValid = extractCandidate && bytesComplete

  io.candidateValid := candidate
  io.requestByteMask := requestMask
  io.bytesComplete := bytesComplete
  io.crossLine := crossesLine
  io.sizeSupported := candidate && nonZeroSize && sizeSupported
  io.rawData := Mux(extractCandidate, raw, 0.U)
  io.data := Mux(dataValid, Mux(io.signExtend, signed, zeroed), 0.U)
  io.dataValid := dataValid

  io.blockedByDisabled := !io.enable && io.returnValid
  io.blockedByNoCandidate := io.enable && !io.returnValid
  io.blockedByZeroSize := candidate && !nonZeroSize
  io.blockedByUnsupportedSize := candidate && nonZeroSize && !sizeSupported
  io.blockedByCrossLine := candidate && nonZeroSize && sizeSupported && crossesLine
  io.blockedByIncompleteBytes := extractCandidate && !bytesComplete
}
