package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class IFUIO(val p: CoreParams) extends Bundle {
  val toCtu = Decoupled(new FetchedPacket(p))
  val memoryRequest = Decoupled(new MemoryRequestTxn(p))
  val memoryResponse = Flipped(Decoupled(new MemoryResponseTxn(p)))
  val branchResolve = Flipped(Decoupled(new BranchResolveTxn(p)))
  val recovery = Flipped(new RecoveryTargetIO(p))
  val trace = Decoupled(new TracePacket(p))
}
