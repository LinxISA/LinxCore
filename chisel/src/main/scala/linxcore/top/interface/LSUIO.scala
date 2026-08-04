package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class LSUIO(val p: CoreParams) extends Bundle {
  val iex = Flipped(new IEXLSUIO(p))
  val storeCommit = Flipped(Decoupled(
    new StoreCommitAuthorizationTxn(p)))
  val storeClassify = Flipped(Decoupled(
    new StoreMemoryClassifyTxn(p)))
  val loadReissueRequest = Flipped(Decoupled(new LoadReplayRequestTxn(p)))
  val memoryRequest = Vec(p.lsu.loadPipes + p.lsu.storePipes,
    Decoupled(new MemoryRequestTxn(p)))
  val memoryResponse = Flipped(Vec(p.lsu.loadPipes + p.lsu.storePipes,
    Decoupled(new MemoryResponseTxn(p))))
  val recovery = Flipped(new RecoveryTargetIO(p))
  val trace = Decoupled(new TracePacket(p))
  val quiescent = Output(Bool())
}
