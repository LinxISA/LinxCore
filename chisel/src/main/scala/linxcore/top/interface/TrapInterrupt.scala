package linxcore.top.interface

import chisel3._
import linxcore.params.CoreParams

object TrapKind extends ChiselEnum {
  val None, Exception, Interrupt, Debug = Value
}

class TrapEvent(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val kind = TrapKind()
  val instruction = new InstructionIdentity(p)
  val rob = new RobIdentity(p)
  val cause = UInt(p.trapCauseWidth.W)
  val targetPc = UInt(p.pcWidth.W)
  val tval = UInt(p.dataWidth.W)
}

class InterruptRequest(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val stid = UInt(InterfaceWidth.index(p.ooo.stidCount).W)
  val cause = UInt(p.trapCauseWidth.W)
  val priority = UInt(8.W)
}
