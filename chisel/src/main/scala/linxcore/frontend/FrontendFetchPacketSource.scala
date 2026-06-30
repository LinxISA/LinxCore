package linxcore.frontend

import chisel3._
import linxcore.common.{FrontendDecodePacket, InterfaceParams}

class FrontendFetchPacketSourceIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val startValid = Input(Bool())
  val startPc = Input(UInt(p.pcWidth.W))
  val restartValid = Input(Bool())
  val restartPc = Input(UInt(p.pcWidth.W))
  val flushValid = Input(Bool())

  val peId = Input(UInt(p.peIdWidth.W))
  val threadId = Input(UInt(p.threadIdWidth.W))

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

class FrontendFetchPacketSource(val p: InterfaceParams = InterfaceParams()) extends Module {
  require(p.windowWidth == 64, "FrontendFetchPacketSource emits the current 64-bit F4 packet window")
  require(p.checkpointWidth <= p.uopUidWidth, "checkpointId is derived from the packet uid low bits")

  val io = IO(new FrontendFetchPacketSourceIO(p))

  private val activeReg = RegInit(false.B)
  private val currentPcReg = RegInit(0.U(p.pcWidth.W))
  private val waitingResponseReg = RegInit(false.B)
  private val issuedPcReg = RegInit(0.U(p.pcWidth.W))
  private val issuedUidReg = RegInit(0.U(p.uopUidWidth.W))
  private val issuedPeIdReg = RegInit(0.U(p.peIdWidth.W))
  private val issuedThreadIdReg = RegInit(0.U(p.threadIdWidth.W))
  private val nextPktUidReg = RegInit(0.U(p.uopUidWidth.W))
  private val packetReg = RegInit(0.U.asTypeOf(new FrontendDecodePacket(p)))

  val restartOrStart = io.restartValid || io.startValid
  val restartPc = Mux(io.restartValid, io.restartPc, io.startPc)
  val reqValid = activeReg && !waitingResponseReg && !packetReg.valid && !io.flushValid && !restartOrStart
  val respReady = waitingResponseReg && !packetReg.valid && !io.flushValid && !restartOrStart
  val outValid = packetReg.valid && !io.flushValid && !restartOrStart
  val reqFire = reqValid && io.reqReady
  val respFire = io.respValid && respReady
  val outFire = outValid && io.outReady
  val advanceZero = outFire && io.advanceBytes === 0.U
  val advanceBytes = Mux(io.advanceBytes === 0.U, F4DecodeWindow.WindowBytes.U(4.W), io.advanceBytes)

  io.reqValid := reqValid
  io.reqPc := currentPcReg
  io.respReady := respReady

  io.out := packetReg
  io.out.valid := outValid

  io.active := activeReg
  io.waitingResponse := waitingResponseReg
  io.packetValid := packetReg.valid
  io.reqFire := reqFire
  io.respFire := respFire
  io.outFire := outFire
  io.advanceZero := advanceZero
  io.currentPc := currentPcReg
  io.issuedPc := issuedPcReg
  io.nextPktUid := nextPktUidReg

  when(io.flushValid || restartOrStart) {
    waitingResponseReg := false.B
    packetReg.valid := false.B
    when(restartOrStart) {
      activeReg := true.B
      currentPcReg := restartPc
      when(io.startValid && !io.restartValid) {
        nextPktUidReg := 0.U
      }
    }.otherwise {
      activeReg := false.B
    }
  }.otherwise {
    when(reqFire) {
      waitingResponseReg := true.B
      issuedPcReg := currentPcReg
      issuedUidReg := nextPktUidReg
      issuedPeIdReg := io.peId
      issuedThreadIdReg := io.threadId
      nextPktUidReg := nextPktUidReg + 1.U
    }

    when(respFire) {
      waitingResponseReg := false.B
      packetReg.valid := true.B
      packetReg.peId := issuedPeIdReg
      packetReg.threadId := issuedThreadIdReg
      packetReg.pc := issuedPcReg
      packetReg.window := io.respWindow
      packetReg.pktUid := issuedUidReg
      packetReg.checkpointId := issuedUidReg(p.checkpointWidth - 1, 0)
    }

    when(outFire) {
      packetReg.valid := false.B
      currentPcReg := packetReg.pc + advanceBytes
    }
  }
}
