package linxcore.lsu

import chisel3._
import circt.stage.ChiselStage

class LoadRefillTransportProbeIO extends Bundle {
  val hardFlush = Input(Bool())
  val hold = Input(Bool())
  val missValid = Input(Bool())
  val missLineAddr = Input(UInt(64.W))
  val missData = Input(UInt(512.W))
  val missReady = Output(Bool())
  val externalValid = Input(Bool())
  val externalLineAddr = Input(UInt(64.W))
  val externalData = Input(UInt(512.W))
  val externalReady = Output(Bool())
  val outValid = Output(Bool())
  val outReady = Input(Bool())
  val outLineAddr = Output(UInt(64.W))
  val outData = Output(UInt(512.W))
  val outFromMissQueue = Output(Bool())
  val outAccepted = Output(Bool())
  val validMask = Output(UInt(4.W))
  val count = Output(UInt(3.W))
  val dualIngressAccepted = Output(Bool())
  val protocolError = Output(Bool())
}

class LoadRefillTransportProbe extends Module {
  val io = IO(new LoadRefillTransportProbeIO)
  val transport = Module(new LoadRefillTransport(entries = 4))

  transport.io.hardFlush := io.hardFlush
  transport.io.hold := io.hold
  transport.io.missValid := io.missValid
  transport.io.miss := 0.U.asTypeOf(transport.io.miss)
  transport.io.miss.isRead := true.B
  transport.io.miss.lineAddr := io.missLineAddr
  transport.io.miss.data := io.missData
  transport.io.externalValid := io.externalValid
  transport.io.external := 0.U.asTypeOf(transport.io.external)
  transport.io.external.isRead := true.B
  transport.io.external.lineAddr := io.externalLineAddr
  transport.io.external.data := io.externalData
  transport.io.outReady := io.outReady

  io.missReady := transport.io.missReady
  io.externalReady := transport.io.externalReady
  io.outValid := transport.io.outValid
  io.outLineAddr := transport.io.out.lineAddr
  io.outData := transport.io.out.data
  io.outFromMissQueue := transport.io.outFromMissQueue
  io.outAccepted := transport.io.outAccepted
  io.validMask := transport.io.validMask
  io.count := transport.io.count
  io.dualIngressAccepted := transport.io.dualIngressAccepted
  io.protocolError := transport.io.protocolError
}

object EmitLoadRefillTransportProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new LoadRefillTransportProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
