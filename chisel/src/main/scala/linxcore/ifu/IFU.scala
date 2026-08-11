package linxcore.ifu

import chisel3._
import linxcore.params.CoreParams
import linxcore.top.interface.IFUIO

/** Public IFU box. All implementation state remains below this typed shell. */
class IFU(val p: CoreParams) extends Module {
  val io = IO(new IFUIO(p))
  val iSide = Module(new ISide(p))

  io.toCtu <> iSide.io.toCtu
  io.memoryRequest <> iSide.io.memoryRequest
  iSide.io.memoryResponse <> io.memoryResponse
  iSide.io.branchResolve <> io.branchResolve

  iSide.io.recovery.prepare <> io.recovery.prepare
  io.recovery.prepared <> iSide.io.recovery.prepared
  iSide.io.recovery.apply := io.recovery.apply
  iSide.io.recovery.abort := io.recovery.abort

  io.trace <> iSide.io.trace
}
