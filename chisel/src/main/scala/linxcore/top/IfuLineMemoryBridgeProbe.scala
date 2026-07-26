package linxcore.top

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams

class IfuLineMemoryBridgeProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val requestValid = Input(Bool())
  val transactionId = Input(UInt(p.uopUidWidth.W))
  val packetUid = Input(UInt(p.uopUidWidth.W))
  val fetchSeq = Input(UInt(p.uopUidWidth.W))
  val lineVa = Input(UInt(p.pcWidth.W))
  val linePa = Input(UInt(p.pcWidth.W))
  val memoryRequestReady = Input(Bool())
  val memoryResponseValid = Input(Bool())
  val memoryResponseTag = Input(UInt(p.uopUidWidth.W))
  val memoryResponseData = Input(UInt(64.W))
  val refillReady = Input(Bool())

  val requestReady = Output(Bool())
  val memoryRequestValid = Output(Bool())
  val memoryRequestTag = Output(UInt(p.uopUidWidth.W))
  val memoryRequestLinePa = Output(UInt(p.pcWidth.W))
  val memoryResponseReady = Output(Bool())
  val refillValid = Output(Bool())
  val refillTransactionId = Output(UInt(p.uopUidWidth.W))
  val refillPacketUid = Output(UInt(p.uopUidWidth.W))
  val refillFetchSeq = Output(UInt(p.uopUidWidth.W))
  val refillLineVa = Output(UInt(p.pcWidth.W))
  val refillLinePa = Output(UInt(p.pcWidth.W))
  val refillData = Output(UInt(64.W))
  val outstandingCount = Output(UInt(3.W))
  val staleResponse = Output(Bool())
}

/** Generated-RTL shell for the tagged production IFU memory adapter. */
class IfuLineMemoryBridgeProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new IfuLineMemoryBridgeProbeIO(p))
  val bridge = Module(new IfuLineMemoryBridge(p, entries = 4, lineBytes = 64))

  bridge.io.ifuRequest.valid := io.requestValid
  bridge.io.ifuRequest.bits := 0.U.asTypeOf(bridge.io.ifuRequest.bits)
  bridge.io.ifuRequest.bits.request.pc := io.lineVa + 6.U
  bridge.io.ifuRequest.bits.request.lineVa := io.lineVa
  bridge.io.ifuRequest.bits.request.transactionId := io.transactionId
  bridge.io.ifuRequest.bits.request.identity.peId := 3.U
  bridge.io.ifuRequest.bits.request.identity.threadId := 1.U
  bridge.io.ifuRequest.bits.request.identity.fetchPacketUid := io.packetUid
  bridge.io.ifuRequest.bits.request.identity.fetchSeq := io.fetchSeq
  bridge.io.ifuRequest.bits.request.identity.checkpointId := 9.U
  bridge.io.ifuRequest.bits.request.identity.epoch := 4.U
  bridge.io.ifuRequest.bits.linePa := io.linePa

  bridge.io.memoryRequest.ready := io.memoryRequestReady
  bridge.io.memoryResponse.valid := io.memoryResponseValid
  bridge.io.memoryResponse.bits.tag := io.memoryResponseTag
  bridge.io.memoryResponse.bits.linePa := Mux(
    io.memoryResponseTag === 0.U,
    0x8000.U,
    Mux(io.memoryResponseTag === 1.U, 0x9000.U, 0.U))
  bridge.io.memoryResponse.bits.lineData := io.memoryResponseData
  bridge.io.ifuRefill.ready := io.refillReady

  io.requestReady := bridge.io.ifuRequest.ready
  io.memoryRequestValid := bridge.io.memoryRequest.valid
  io.memoryRequestTag := bridge.io.memoryRequest.bits.tag
  io.memoryRequestLinePa := bridge.io.memoryRequest.bits.linePa
  io.memoryResponseReady := bridge.io.memoryResponse.ready
  io.refillValid := bridge.io.ifuRefill.valid
  io.refillTransactionId := bridge.io.ifuRefill.bits.transactionId
  io.refillPacketUid := bridge.io.ifuRefill.bits.fetchPacketUid
  io.refillFetchSeq := bridge.io.ifuRefill.bits.fetchSeq
  io.refillLineVa := bridge.io.ifuRefill.bits.lineVa
  io.refillLinePa := bridge.io.ifuRefill.bits.linePa
  io.refillData := bridge.io.ifuRefill.bits.lineData(63, 0)
  io.outstandingCount := bridge.io.outstandingCount
  io.staleResponse := bridge.io.staleResponse
}

object EmitIfuLineMemoryBridgeProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new IfuLineMemoryBridgeProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
