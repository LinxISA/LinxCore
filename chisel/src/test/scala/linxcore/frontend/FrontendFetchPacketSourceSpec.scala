package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.{FrontendDecodePacket, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

object FrontendFetchPacketSourceReference {
  final case class Packet(valid: Boolean, peId: Int, threadId: Int, pc: BigInt, window: BigInt, pktUid: BigInt, checkpointId: Int)
  final case class Output(
      reqValid: Boolean,
      reqPc: BigInt,
      respReady: Boolean,
      out: Packet,
      active: Boolean,
      waitingResponse: Boolean,
      packetValid: Boolean,
      reqFire: Boolean,
      respFire: Boolean,
      outFire: Boolean,
      advanceZero: Boolean,
      currentPc: BigInt,
      issuedPc: BigInt,
      nextPktUid: BigInt)

  val EmptyPacket: Packet = Packet(valid = false, peId = 0, threadId = 0, pc = 0, window = 0, pktUid = 0, checkpointId = 0)

  final class Model {
    private var active = false
    private var currentPc = BigInt(0)
    private var waitingResponse = false
    private var issuedPc = BigInt(0)
    private var issuedUid = BigInt(0)
    private var issuedPeId = 0
    private var issuedThreadId = 0
    private var nextPktUid = BigInt(0)
    private var packet = EmptyPacket

    def step(
        startValid: Boolean = false,
        startPc: BigInt = 0,
        restartValid: Boolean = false,
        restartPc: BigInt = 0,
        flushValid: Boolean = false,
        peId: Int = 0,
        threadId: Int = 0,
        reqReady: Boolean = false,
        respValid: Boolean = false,
        respWindow: BigInt = 0,
        outReady: Boolean = false,
        advanceBytes: Int = 8): Output = {
      val restartOrStart = restartValid || startValid
      val selectedRestartPc = if (restartValid) restartPc else startPc
      val reqValid = active && !waitingResponse && !packet.valid && !flushValid && !restartOrStart
      val respReady = waitingResponse && !packet.valid && !flushValid && !restartOrStart
      val out = packet.copy(valid = packet.valid && !flushValid && !restartOrStart)
      val reqFire = reqValid && reqReady
      val respFire = respValid && respReady
      val outFire = out.valid && outReady
      val advanceZero = outFire && advanceBytes == 0

      val observed = Output(
        reqValid = reqValid,
        reqPc = currentPc,
        respReady = respReady,
        out = out,
        active = active,
        waitingResponse = waitingResponse,
        packetValid = packet.valid,
        reqFire = reqFire,
        respFire = respFire,
        outFire = outFire,
        advanceZero = advanceZero,
        currentPc = currentPc,
        issuedPc = issuedPc,
        nextPktUid = nextPktUid)

      if (flushValid || restartOrStart) {
        waitingResponse = false
        packet = packet.copy(valid = false)
        if (restartOrStart) {
          active = true
          currentPc = selectedRestartPc
          if (startValid && !restartValid) {
            nextPktUid = 0
          }
        } else {
          active = false
        }
      } else {
        if (reqFire) {
          waitingResponse = true
          issuedPc = currentPc
          issuedUid = nextPktUid
          issuedPeId = peId
          issuedThreadId = threadId
          nextPktUid += 1
        }
        if (respFire) {
          waitingResponse = false
          packet = Packet(
            valid = true,
            peId = issuedPeId,
            threadId = issuedThreadId,
            pc = issuedPc,
            window = respWindow,
            pktUid = issuedUid,
            checkpointId = (issuedUid & 0x3f).toInt)
        }
        if (outFire) {
          packet = packet.copy(valid = false)
          currentPc = packet.pc + (if (advanceBytes == 0) 8 else advanceBytes)
        }
      }

      observed
    }
  }
}

class FrontendFetchPacketSourceProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val startValid = Input(Bool())
  val startPc = Input(UInt(p.pcWidth.W))
  val restartValid = Input(Bool())
  val restartPc = Input(UInt(p.pcWidth.W))
  val flushValid = Input(Bool())
  val reqValid = Output(Bool())
  val reqReady = Input(Bool())
  val reqPc = Output(UInt(p.pcWidth.W))
  val respValid = Input(Bool())
  val respReady = Output(Bool())
  val respWindow = Input(UInt(p.windowWidth.W))
  val outReady = Input(Bool())
  val out = Output(new FrontendDecodePacket(p))
  val advanceBytes = Input(UInt(4.W))
  val currentPc = Output(UInt(p.pcWidth.W))
  val nextPktUid = Output(UInt(p.uopUidWidth.W))
}

class FrontendFetchPacketSourceProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new FrontendFetchPacketSourceProbeIO(p))
  val source = Module(new FrontendFetchPacketSource(p))

  source.io.startValid := io.startValid
  source.io.startPc := io.startPc
  source.io.restartValid := io.restartValid
  source.io.restartPc := io.restartPc
  source.io.flushValid := io.flushValid
  source.io.peId := 0.U
  source.io.threadId := 0.U
  source.io.reqReady := io.reqReady
  source.io.respValid := io.respValid
  source.io.respWindow := io.respWindow
  source.io.outReady := io.outReady
  source.io.advanceBytes := io.advanceBytes

  io.reqValid := source.io.reqValid
  io.reqPc := source.io.reqPc
  io.respReady := source.io.respReady
  io.out := source.io.out
  io.currentPc := source.io.currentPc
  io.nextPktUid := source.io.nextPktUid
}

class FrontendFetchPacketSourceSpec extends AnyFunSuite {
  test("reference issues one in-order fetch request and packetizes the response") {
    val source = new FrontendFetchPacketSourceReference.Model

    val boot = source.step(startValid = true, startPc = 0x4000)
    assert(!boot.reqValid)

    val req = source.step(peId = 2, threadId = 3, reqReady = true)
    assert(req.reqValid)
    assert(req.reqFire)
    assert(req.reqPc == 0x4000)

    val resp = source.step(respValid = true, respWindow = BigInt("0010002000300040", 16))
    assert(resp.respReady)
    assert(resp.respFire)

    val visible = source.step()
    assert(visible.out.valid)
    assert(visible.out.peId == 2)
    assert(visible.out.threadId == 3)
    assert(visible.out.pc == 0x4000)
    assert(visible.out.window == BigInt("0010002000300040", 16))
    assert(visible.out.pktUid == 0)
    assert(visible.out.checkpointId == 0)
  }

  test("reference holds response packets under backpressure and advances by decoded bytes") {
    val source = new FrontendFetchPacketSourceReference.Model

    source.step(startValid = true, startPc = 0x8000)
    source.step(reqReady = true)
    source.step(respValid = true, respWindow = 0x1111)

    val stalled = source.step(outReady = false)
    assert(stalled.out.valid)
    assert(!stalled.outFire)
    assert(stalled.currentPc == 0x8000)

    val consumed = source.step(outReady = true, advanceBytes = 6)
    assert(consumed.outFire)

    val next = source.step(reqReady = true)
    assert(next.reqFire)
    assert(next.reqPc == 0x8006)
    assert(next.nextPktUid == 1)
  }

  test("reference restart flushes pending work and preserves packet uid progression") {
    val source = new FrontendFetchPacketSourceReference.Model

    source.step(startValid = true, startPc = 0x1000)
    source.step(reqReady = true)
    val restart = source.step(restartValid = true, restartPc = 0x2000)
    assert(!restart.reqValid)
    assert(restart.waitingResponse)

    val afterRestart = source.step()
    assert(!afterRestart.waitingResponse)
    assert(afterRestart.reqValid)
    assert(afterRestart.reqPc == 0x2000)

    val req = source.step(reqReady = true)
    assert(req.reqFire)
    assert(req.reqPc == 0x2000)
    assert(req.nextPktUid == 1)
  }

  test("reference flush without restart disables the source until the next start") {
    val source = new FrontendFetchPacketSourceReference.Model

    source.step(startValid = true, startPc = 0x1000)
    source.step(reqReady = true)
    source.step(flushValid = true)

    val idle = source.step(reqReady = true, respValid = true, respWindow = 0x1234)
    assert(!idle.reqValid)
    assert(!idle.respReady)
    assert(!idle.out.valid)

    val restart = source.step(startValid = true, startPc = 0x3000)
    assert(!restart.reqValid)
    val req = source.step(reqReady = true)
    assert(req.reqFire)
    assert(req.reqPc == 0x3000)
  }

  test("IO fields preserve fetch request, response, packet, and diagnostics widths") {
    val p = InterfaceParams()
    val io = new FrontendFetchPacketSourceIO(p)

    assert(io.startPc.getWidth == 64)
    assert(io.restartPc.getWidth == 64)
    assert(io.peId.getWidth == 8)
    assert(io.threadId.getWidth == 8)
    assert(io.reqPc.getWidth == 64)
    assert(io.respWindow.getWidth == 64)
    assert(io.out.window.getWidth == 64)
    assert(io.out.pktUid.getWidth == 64)
    assert(io.out.checkpointId.getWidth == 6)
    assert(io.advanceBytes.getWidth == 4)
    assert(io.currentPc.getWidth == 64)
    assert(io.nextPktUid.getWidth == 64)
  }

  test("FrontendFetchPacketSource elaborates request, response, and packet outputs") {
    val sv = ChiselStage.emitSystemVerilog(new FrontendFetchPacketSourceProbe(InterfaceParams()))

    assert(sv.contains("module FrontendFetchPacketSourceProbe"))
    assert(sv.contains("FrontendFetchPacketSource"))
    assert(sv.contains("io_reqValid"))
    assert(sv.contains("io_respReady"))
    assert(sv.contains("io_out_valid"))
    assert(sv.contains("io_currentPc"))
    assert(sv.contains("io_nextPktUid"))
  }
}
