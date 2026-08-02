package linxcore.fpga.zybo

import chisel3._
import chisel3.util.{Cat, Decoupled, MuxLookup}

class LinxAxi4MasterIO extends Bundle {
  val request = Flipped(Decoupled(new LinxMemRequest))
  val response = Decoupled(new LinxMemResponse)
  val axi = new LinxAxi4MasterPort
}

object LinxAxi4MasterState extends ChiselEnum {
  val Idle, SendAr, ReceiveR, SendAw, SendW, ReceiveB, Respond = Value
}

class LinxAxi4Master extends Module {
  val io = IO(new LinxAxi4MasterIO)

  import LinxAxi4MasterState._

  private val beatBytes = LinxAxi4.DataBytes
  private val lineBeats = ZyboZ720Generated.LineBytes / beatBytes
  private val ramBase = ZyboZ720Generated.LinxMemoryBase
  private val ramEnd = ramBase + ZyboZ720Generated.LinxMemorySize

  require(lineBeats == 8, "a 64-byte Zybo line must contain eight AXI beats")
  require(LinxPlatformMemory.RequestIdWidth == 32,
    "native request identity must remain a full 32-bit LSID")

  private val state = RegInit(Idle)
  private val retainedRequest = Reg(new LinxMemRequest)
  private val retainedResponse = Reg(new LinxMemResponse)
  private val readBeats = RegInit(VecInit(Seq.fill(lineBeats)(0.U(LinxAxi4.DataWidth.W))))
  private val readBeatCount = RegInit(0.U(9.W))
  private val readProtocolError = RegInit(false.B)
  private val readBusError = RegInit(false.B)
  private val writeAwAccepted = RegInit(false.B)
  private val writeWAccepted = RegInit(false.B)

  private val liveAddr = io.request.bits.addr
  private val scalarSizeSupported = io.request.bits.size <= 3.U
  private val scalarAligned = MuxLookup(io.request.bits.size, false.B)(Seq(
    0.U -> true.B,
    1.U -> !liveAddr(0),
    2.U -> !liveAddr(1, 0).orR,
    3.U -> !liveAddr(2, 0).orR
  ))
  private val lineAligned = !liveAddr(5, 0).orR
  private val spanBytes = Mux(
    io.request.bits.line,
    ZyboZ720Generated.LineBytes.U(9.W),
    (1.U(9.W) << io.request.bits.size))
  private val liveAddrWide = Cat(0.U(1.W), liveAddr)
  private val endExclusive = liveAddrWide +& spanBytes
  private val finalAddr = endExclusive - 1.U
  private val insideRam =
    liveAddrWide >= ramBase.U(33.W) && endExclusive <= ramEnd.U(34.W)
  private val crosses4KiB = liveAddrWide(31, 12) =/= finalAddr(31, 12)

  private val scalarByteMask = MuxLookup(io.request.bits.size, 0.U(beatBytes.W))(Seq(
    0.U -> 1.U(beatBytes.W),
    1.U -> 3.U(beatBytes.W),
    2.U -> 15.U(beatBytes.W),
    3.U -> 255.U(beatBytes.W)
  ))
  private val expectedStrobe =
    (scalarByteMask << liveAddr(2, 0))(beatBytes - 1, 0)
  private val strobeProtocolError = Mux(
    io.request.bits.write,
    io.request.bits.wstrb =/= expectedStrobe,
    io.request.bits.wstrb =/= 0.U)
  private val shapeProtocolError =
    (io.request.bits.line && io.request.bits.write) ||
      (io.request.bits.line && io.request.bits.size =/= 6.U) ||
      strobeProtocolError
  private val accessError =
    (!io.request.bits.line && !scalarSizeSupported) ||
      Mux(io.request.bits.line, !lineAligned, !scalarAligned) ||
      !insideRam || crosses4KiB

  io.request.ready := state === Idle
  io.response.valid := state === Respond
  io.response.bits := retainedResponse

  io.axi.ar.valid := state === SendAr
  io.axi.ar.bits.id := LinxAxi4.SupportedId.U
  io.axi.ar.bits.addr := retainedRequest.addr
  io.axi.ar.bits.len := Mux(retainedRequest.line, (lineBeats - 1).U, 0.U)
  io.axi.ar.bits.size := Mux(retainedRequest.line, 3.U, retainedRequest.size)
  io.axi.ar.bits.burst := LinxAxi4.BurstIncr.U

  io.axi.r.ready := state === ReceiveR

  private val writeIssueActive = state === SendAw || state === SendW

  io.axi.aw.valid := writeIssueActive && !writeAwAccepted
  io.axi.aw.bits.id := LinxAxi4.SupportedId.U
  io.axi.aw.bits.addr := retainedRequest.addr
  io.axi.aw.bits.len := 0.U
  io.axi.aw.bits.size := retainedRequest.size
  io.axi.aw.bits.burst := LinxAxi4.BurstIncr.U

  io.axi.w.valid := writeIssueActive && !writeWAccepted
  io.axi.w.bits.data := retainedRequest.wdata
  io.axi.w.bits.strb := retainedRequest.wstrb
  io.axi.w.bits.last := true.B

  io.axi.b.ready := state === ReceiveB

  private val terminalLineData = Cat((0 until lineBeats).reverse.map { beat =>
    Mux(readBeatCount === beat.U, io.axi.r.bits.data, readBeats(beat))
  })

  when(io.request.fire) {
    retainedRequest := io.request.bits
    retainedResponse.id := io.request.bits.id
    retainedResponse.rdata := 0.U
    retainedResponse.fault := LinxMemFault.NoFault
    retainedResponse.last := io.request.bits.last
    readBeatCount := 0.U
    readProtocolError := false.B
    readBusError := false.B
    writeAwAccepted := false.B
    writeWAccepted := false.B
    for (beat <- 0 until lineBeats) readBeats(beat) := 0.U

    when(shapeProtocolError) {
      retainedResponse.fault := LinxMemFault.Protocol
      state := Respond
    }.elsewhen(accessError) {
      retainedResponse.fault := LinxMemFault.Access
      state := Respond
    }.elsewhen(io.request.bits.write) {
      state := SendAw
    }.otherwise {
      state := SendAr
    }
  }

  when(io.axi.ar.fire) {
    state := ReceiveR
  }

  when(io.axi.r.fire) {
    val expectedLastBeat = Mux(retainedRequest.line, (lineBeats - 1).U, 0.U)
    val beatOverflow = readBeatCount > expectedLastBeat
    val framingError = beatOverflow || (io.axi.r.bits.last =/=
      (readBeatCount === expectedLastBeat))
    val nextProtocolError =
      readProtocolError ||
        io.axi.r.bits.id =/= LinxAxi4.SupportedId.U ||
        framingError
    val nextBusError =
      readBusError || io.axi.r.bits.resp =/= LinxAxi4.RespOkay.U

    when(readBeatCount < lineBeats.U) {
      readBeats(readBeatCount(2, 0)) := io.axi.r.bits.data
    }
    readProtocolError := nextProtocolError
    readBusError := nextBusError

    when(io.axi.r.bits.last || readBeatCount === expectedLastBeat) {
      retainedResponse.rdata := Mux(
        retainedRequest.line,
        terminalLineData,
        Cat(0.U((LinxPlatformMemory.LineDataWidth - LinxAxi4.DataWidth).W),
          io.axi.r.bits.data))
      retainedResponse.fault := Mux(
        nextProtocolError,
        LinxMemFault.Protocol,
        Mux(nextBusError, LinxMemFault.Bus, LinxMemFault.NoFault))
      state := Respond
    }.otherwise {
      when(!readBeatCount.andR) {
        readBeatCount := readBeatCount + 1.U
      }
    }
  }

  when(writeIssueActive) {
    val nextAwAccepted = writeAwAccepted || io.axi.aw.fire
    val nextWAccepted = writeWAccepted || io.axi.w.fire

    when(io.axi.aw.fire) {
      writeAwAccepted := true.B
    }
    when(io.axi.w.fire) {
      writeWAccepted := true.B
    }

    when(nextAwAccepted && nextWAccepted) {
      state := ReceiveB
    }.elsewhen(nextAwAccepted) {
      state := SendW
    }.otherwise {
      state := SendAw
    }
  }

  when(io.axi.b.fire) {
    retainedResponse.rdata := 0.U
    retainedResponse.fault := Mux(
      io.axi.b.bits.id =/= LinxAxi4.SupportedId.U,
      LinxMemFault.Protocol,
      Mux(io.axi.b.bits.resp =/= LinxAxi4.RespOkay.U,
        LinxMemFault.Bus, LinxMemFault.NoFault))
    state := Respond
  }

  when(io.response.fire) {
    state := Idle
  }
}

object EmitLinxAxi4Master extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(new LinxAxi4Master, args)
}
