package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class OOOIO(val p: CoreParams) extends Bundle {
  val fromCtu = Flipped(Decoupled(new D1Packet(p)))
  val iex = new OOOIEXIO(p)
  val commit = Decoupled(new CommitTxn(p))
  val trap = Decoupled(new TrapEvent(p))
  val interrupt = Flipped(Valid(new InterruptRequest(p)))
  val recoveryToIfu = new RecoveryTargetIO(p)
  val recoveryToCtu = new RecoveryTargetIO(p)
  val recoveryToLsu = new RecoveryTargetIO(p)
  val systemIssue = Vec(p.iex.systemMulticycleQueues,
    Decoupled(new SystemIssueTxn(p)))
  val trace = Decoupled(new TracePacket(p))
}
