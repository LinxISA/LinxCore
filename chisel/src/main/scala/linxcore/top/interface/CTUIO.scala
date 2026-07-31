package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class CTUIO(val p: CoreParams) extends Bundle {
  val fromIfu = Flipped(Decoupled(new FetchedPacket(p)))
  val toOoo = Decoupled(new D1Packet(p))
  val recovery = Flipped(new RecoveryTargetIO(p))
  val trace = Decoupled(new TracePacket(p))
}
