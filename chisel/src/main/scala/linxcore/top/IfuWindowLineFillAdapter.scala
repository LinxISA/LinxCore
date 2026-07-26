package linxcore.top

import chisel3._
import chisel3.util.{Cat, Decoupled, log2Ceil}
import linxcore.common.InterfaceParams

class IfuWindowLineFillAdapterIO(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val lineRequest = Flipped(Decoupled(new IfuLineMemoryReadRequest(p, lineBytes)))
  val lineResponse = Decoupled(new IfuLineMemoryReadResponse(p, lineBytes))

  val fetchReqValid = Output(Bool())
  val fetchReqReady = Input(Bool())
  val fetchReqPc = Output(UInt(p.pcWidth.W))
  val fetchReqFire = Output(Bool())
  val fetchRespValid = Input(Bool())
  val fetchRespReady = Output(Bool())
  val fetchRespWindow = Input(UInt(p.windowWidth.W))
  val fetchRespFire = Output(Bool())

  val active = Output(Bool())
  val beat = Output(UInt(log2Ceil(lineBytes / (p.windowWidth / 8)).W))
}

/** Converts one tagged production-IFU cacheline request into ordered accesses
  * over the autonomous benchmark's existing 64-bit ELF-memory window port.
  *
  * Only one cacheline is assembled at a time because the window port has no
  * response tag. The production-side tag and physical line address are held
  * from request acceptance through response retirement; neither is recreated
  * from a later PC. Little-endian window zero occupies the low 64 bits of the
  * completed line.
  */
class IfuWindowLineFillAdapter(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Module {
  private val windowBytes = p.windowWidth / 8
  private val beatsPerLine = lineBytes / windowBytes
  private val beatWidth = log2Ceil(beatsPerLine)

  require(p.windowWidth == 64, "autonomous ELF memory supplies 64-bit windows")
  require(lineBytes == 64, "production IFU cacheline is 64 bytes")
  require(beatsPerLine > 1 && (beatsPerLine & (beatsPerLine - 1)) == 0)

  val io = IO(new IfuWindowLineFillAdapterIO(p, lineBytes))

  val requestActive = RegInit(false.B)
  val waitingResponse = RegInit(false.B)
  val responseValid = RegInit(false.B)
  val requestTag = RegInit(0.U(p.uopUidWidth.W))
  val requestLinePa = RegInit(0.U(p.pcWidth.W))
  val beat = RegInit(0.U(beatWidth.W))
  val windows = RegInit(VecInit(Seq.fill(beatsPerLine)(0.U(p.windowWidth.W))))
  val lineData = RegInit(0.U((lineBytes * 8).W))

  io.lineRequest.ready := !requestActive && !responseValid
  io.fetchReqValid := requestActive && !waitingResponse && !responseValid
  io.fetchReqPc := requestLinePa + (beat << log2Ceil(windowBytes))
  io.fetchReqFire := io.fetchReqValid && io.fetchReqReady
  io.fetchRespReady := requestActive && waitingResponse && !responseValid
  io.fetchRespFire := io.fetchRespValid && io.fetchRespReady

  io.lineResponse.valid := responseValid
  io.lineResponse.bits.tag := requestTag
  io.lineResponse.bits.linePa := requestLinePa
  io.lineResponse.bits.lineData := lineData

  io.active := requestActive || responseValid
  io.beat := beat

  when(io.lineRequest.fire) {
    requestActive := true.B
    waitingResponse := false.B
    requestTag := io.lineRequest.bits.tag
    requestLinePa := io.lineRequest.bits.linePa
    beat := 0.U
  }

  when(io.fetchReqFire) {
    waitingResponse := true.B
  }

  when(io.fetchRespFire) {
    val assembled = Wire(Vec(beatsPerLine, UInt(p.windowWidth.W)))
    assembled := windows
    assembled(beat) := io.fetchRespWindow
    windows(beat) := io.fetchRespWindow
    waitingResponse := false.B
    when(beat === (beatsPerLine - 1).U) {
      lineData := Cat(assembled.reverse)
      requestActive := false.B
      responseValid := true.B
    }.otherwise {
      beat := beat + 1.U
    }
  }

  when(io.lineResponse.fire) {
    responseValid := false.B
  }

  when(requestActive || responseValid) {
    assert(requestLinePa(log2Ceil(lineBytes) - 1, 0) === 0.U,
      "production IFU line requests must remain cacheline aligned")
  }
  assert(!(io.fetchRespValid && !waitingResponse && requestActive),
    "autonomous window response must correspond to an accepted request")
}

