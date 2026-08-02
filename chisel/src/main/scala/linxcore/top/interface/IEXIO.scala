package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class IEXIO(val p: CoreParams) extends Bundle {
  val ooo = Flipped(new OOOIEXIO(p))
  val lsu = new IEXLSUIO(p)
  val cmdIssue = Decoupled(new CmdIssueTxn(p))
  val trace = Decoupled(new TracePacket(p))
}
