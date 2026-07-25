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
      discardResponse: Boolean,
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
    private var discardResponse = false
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
      val controlValid = flushValid || restartOrStart
      val selectedRestartPc = if (restartValid) restartPc else startPc
      val reqValid = active && !waitingResponse && !packet.valid && !controlValid
      val respReady = waitingResponse && !packet.valid
      val out = packet.copy(valid = packet.valid && !controlValid)
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
        discardResponse = discardResponse,
        packetValid = packet.valid,
        reqFire = reqFire,
        respFire = respFire,
        outFire = outFire,
        advanceZero = advanceZero,
        currentPc = currentPc,
        issuedPc = issuedPc,
        nextPktUid = nextPktUid)

      if (controlValid) {
        val mustDrainOutstanding = waitingResponse && !respFire
        waitingResponse = mustDrainOutstanding
        discardResponse = mustDrainOutstanding
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
          discardResponse = false
          issuedPc = currentPc
          issuedUid = nextPktUid
          issuedPeId = peId
          issuedThreadId = threadId
          nextPktUid += 1
        }
        if (respFire) {
          waitingResponse = false
          if (discardResponse) {
            discardResponse = false
            packet = packet.copy(valid = false)
          } else {
            packet = Packet(
              valid = true,
              peId = issuedPeId,
              threadId = issuedThreadId,
              pc = issuedPc,
              window = respWindow,
              pktUid = issuedUid,
              checkpointId = (issuedUid & 0x3f).toInt)
          }
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
  val active = Output(Bool())
  val waitingResponse = Output(Bool())
  val packetValid = Output(Bool())
  val reqFire = Output(Bool())
  val respFire = Output(Bool())
  val outFire = Output(Bool())
  val advanceZero = Output(Bool())
  val currentPc = Output(UInt(p.pcWidth.W))
  val issuedPc = Output(UInt(p.pcWidth.W))
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
  io.active := source.io.active
  io.waitingResponse := source.io.waitingResponse
  io.packetValid := source.io.packetValid
  io.reqFire := source.io.reqFire
  io.respFire := source.io.respFire
  io.outFire := source.io.outFire
  io.advanceZero := source.io.advanceZero
  io.currentPc := source.io.currentPc
  io.issuedPc := source.io.issuedPc
  io.nextPktUid := source.io.nextPktUid
}

object ElaborateFrontendFetchPacketSourceProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrontendFetchPacketSourceProbe(InterfaceParams()),
    args,
    Array("--strip-debug-info", "--disable-all-randomization")
  )
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

  test("reference restart drains stale outstanding response before redirected request") {
    val source = new FrontendFetchPacketSourceReference.Model

    source.step(startValid = true, startPc = 0x1000)
    source.step(reqReady = true)
    val restart = source.step(restartValid = true, restartPc = 0x2000)
    assert(!restart.reqValid)
    assert(restart.waitingResponse)
    assert(restart.respReady)

    val afterRestart = source.step()
    assert(afterRestart.waitingResponse)
    assert(afterRestart.discardResponse)
    assert(afterRestart.respReady)
    assert(!afterRestart.reqValid)

    val stale = source.step(respValid = true, respWindow = 0xaaaa)
    assert(stale.respReady)
    assert(stale.respFire)

    val req = source.step(reqReady = true)
    assert(req.reqFire)
    assert(req.reqPc == 0x2000)
    assert(req.nextPktUid == 1)
  }

  test("reference discards stale response arriving on the restart cycle") {
    val source = new FrontendFetchPacketSourceReference.Model

    source.step(startValid = true, startPc = 0x1000)
    source.step(reqReady = true)
    val restartAndResponse =
      source.step(restartValid = true, restartPc = 0x2000, respValid = true, respWindow = 0xaaaa)

    assert(restartAndResponse.respReady)
    assert(restartAndResponse.respFire)

    val req = source.step(reqReady = true)
    assert(!req.waitingResponse)
    assert(!req.out.valid)
    assert(req.reqFire)
    assert(req.reqPc == 0x2000)
    assert(req.nextPktUid == 1)
  }

  test("reference holds redirected request until a delayed stale response drains") {
    val source = new FrontendFetchPacketSourceReference.Model

    source.step(startValid = true, startPc = 0x1000)
    source.step(reqReady = true)
    source.step(restartValid = true, restartPc = 0x2000)

    for (_ <- 0 until 3) {
      val hold = source.step(reqReady = true)
      assert(hold.waitingResponse)
      assert(hold.discardResponse)
      assert(hold.respReady)
      assert(!hold.reqValid)
      assert(!hold.out.valid)
      assert(hold.currentPc == 0x2000)
    }

    val stale = source.step(respValid = true, respWindow = 0xbbbb)
    assert(stale.respFire)

    val req = source.step(reqReady = true)
    assert(req.reqFire)
    assert(req.reqPc == 0x2000)
  }

  test("reference start drains stale outstanding response and resets next request uid") {
    val source = new FrontendFetchPacketSourceReference.Model

    source.step(startValid = true, startPc = 0x1000)
    source.step(reqReady = true)
    source.step(respValid = true, respWindow = 0x1111)
    source.step(outReady = true)
    source.step(reqReady = true)
    val start = source.step(startValid = true, startPc = 0x3000)

    assert(start.waitingResponse)
    assert(!start.reqValid)
    val stale = source.step(respValid = true, respWindow = 0x2222)
    assert(stale.respFire)
    val req = source.step(reqReady = true)
    assert(req.reqFire)
    assert(req.reqPc == 0x3000)
    assert(req.nextPktUid == 0)
  }

  test("reference start discards stale response arriving on the start cycle and resets next request uid") {
    val source = new FrontendFetchPacketSourceReference.Model

    source.step(startValid = true, startPc = 0x1000)
    source.step(reqReady = true)
    source.step(respValid = true, respWindow = 0x1111)
    source.step(outReady = true)
    source.step(reqReady = true)
    val startAndResponse =
      source.step(startValid = true, startPc = 0x3000, respValid = true, respWindow = 0x2222)

    assert(startAndResponse.respReady)
    assert(startAndResponse.respFire)
    assert(!startAndResponse.reqValid)

    val req = source.step(reqReady = true)
    assert(!req.waitingResponse)
    assert(!req.out.valid)
    assert(req.reqFire)
    assert(req.reqPc == 0x3000)
    assert(req.nextPktUid == 0)
  }

  test("reference flush without restart disables the source until the next start") {
    val source = new FrontendFetchPacketSourceReference.Model

    source.step(startValid = true, startPc = 0x1000)
    source.step(reqReady = true)
    source.step(flushValid = true)

    val idle = source.step(reqReady = true, respValid = true, respWindow = 0x1234)
    assert(!idle.reqValid)
    assert(idle.respReady)
    assert(idle.respFire)
    assert(!idle.out.valid)

    val restart = source.step(startValid = true, startPc = 0x3000)
    assert(!restart.reqValid)
    val req = source.step(reqReady = true)
    assert(req.reqFire)
    assert(req.reqPc == 0x3000)
  }

  test("reference flush discards stale response arriving on the flush cycle and stays inactive") {
    val source = new FrontendFetchPacketSourceReference.Model

    source.step(startValid = true, startPc = 0x1000)
    source.step(reqReady = true)
    val flushAndResponse = source.step(flushValid = true, respValid = true, respWindow = 0x1234)

    assert(flushAndResponse.respReady)
    assert(flushAndResponse.respFire)
    assert(!flushAndResponse.reqValid)

    val idle = source.step(reqReady = true)
    assert(!idle.waitingResponse)
    assert(!idle.out.valid)
    assert(!idle.active)
    assert(!idle.reqValid)

    source.step(startValid = true, startPc = 0x3000)
    val req = source.step(reqReady = true)
    assert(req.reqFire)
    assert(req.reqPc == 0x3000)
    assert(req.nextPktUid == 0)
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
    assert(io.issuedPc.getWidth == 64)
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
    assert(sv.contains("io_issuedPc"))
    assert(sv.contains("io_nextPktUid"))
  }
}
