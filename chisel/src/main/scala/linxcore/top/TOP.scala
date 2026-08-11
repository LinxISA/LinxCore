package linxcore.top

import chisel3._
import linxcore.ctu.CTU
import linxcore.dtu.DTU
import linxcore.iex.IEX
import linxcore.ifu.IFU
import linxcore.lsu.LSU
import linxcore.ooo.OOO
import linxcore.params.CoreParams
import linxcore.top.interface.{TOPIO, TracePacket}

/** The one public core composition boundary.
  *
  * TOP is deliberately state-free. Every retained transaction and every
  * architectural decision remains in its public box owner; this module only
  * connects typed channels and projects external endpoints.
  */
class TOP(val p: CoreParams) extends Module {
  val io = IO(new TOPIO(p))

  val ifu = Module(new IFU(p))
  val ctu = Module(new CTU(p))
  val ooo = Module(new OOO(p))
  val iex = Module(new IEX(p))
  val lsu = Module(new LSU(p))
  val dtu = Module(new DTU(p))

  ctu.io.fromIfu <> ifu.io.toCtu
  ooo.io.fromCtu <> ctu.io.toOoo
  iex.io.ooo <> ooo.io.iex
  lsu.io.iex <> iex.io.lsu
  ifu.io.branchResolve <> iex.io.branchResolve

  ooo.io.recoveryToIfu <> ifu.io.recovery
  ooo.io.recoveryToCtu <> ctu.io.recovery
  ooo.io.recoveryToLsu <> lsu.io.recovery

  io.instructionMemoryRequest <> ifu.io.memoryRequest
  ifu.io.memoryResponse <> io.instructionMemoryResponse
  for (lane <- io.dataMemoryRequest.indices) {
    io.dataMemoryRequest(lane) <> lsu.io.memoryRequest(lane)
    lsu.io.memoryResponse(lane) <> io.dataMemoryResponse(lane)
  }

  iex.io.pInit <> io.pInit
  iex.io.bootstrapComplete := io.bootstrapComplete
  io.bootstrapReady := iex.io.bootstrapReady

  ooo.io.interrupt <> io.interrupt
  dtu.io.debugRequest <> io.debugRequest
  io.debugResponse <> dtu.io.debugResponse
  ooo.io.debugRequest <> dtu.io.controlRequest
  dtu.io.controlResponse <> ooo.io.debugResponse

  for (lane <- io.systemIssue.indices) {
    io.systemIssue(lane) <> ooo.io.systemIssue(lane)
  }
  io.cmdIssue <> iex.io.cmdIssue

  lsu.io.storeCommit <> ooo.io.storeCommit
  ooo.io.storeResolve <> lsu.io.storeResolve
  lsu.io.loadReissueRequest <> io.loadReissueRequest
  io.memoryFault <> lsu.io.memoryFault
  lsu.io.maintenance <> io.maintenance
  io.maintenanceResult <> lsu.io.maintenanceResult
  io.lsuQuiescent := lsu.io.quiescent
  io.lsuProtocolError := lsu.io.protocolError

  io.commit <> ooo.io.commit
  dtu.io.commitIn.valid := ooo.io.commit.fire
  dtu.io.commitIn.bits := ooo.io.commit.bits
  io.trap <> ooo.io.trap

  val traceSources = Seq(
    ifu.io.trace,
    ctu.io.trace,
    ooo.io.trace,
    iex.io.trace,
    lsu.io.trace)
  val tracePacker = Module(new TracePrefixPacker(p, traceSources.length))
  tracePacker.io.in.zip(traceSources).foreach { case (sink, source) =>
    sink <> source
  }
  dtu.io.traceIn <> tracePacker.io.out
  dtu.io.traceOverflowDropped := tracePacker.io.dropped
  io.trace <> dtu.io.traceOut
  io.performanceCounters := dtu.io.performanceCounters
}
