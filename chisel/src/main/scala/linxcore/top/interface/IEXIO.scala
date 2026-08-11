package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

/** Fire-qualified observation of the existing terminal P-file mutation.
  * This is not an additional RF write port or state owner.
  */
class IEXTerminalPWriteObservation(val p: CoreParams) extends Bundle {
  val rob = new RobIdentity(p)
  val ptag = UInt(p.ooo.gprTagWidth.W)
  val generation = UInt(p.ooo.gprTagGenerationWidth.W)
  val value = UInt(p.dataWidth.W)
}

class IEXIO(val p: CoreParams) extends Bundle {
  val pInit = Flipped(Decoupled(new PFileInitTxn(p)))
  val bootstrapComplete = Input(Bool())
  val bootstrapReady = Output(Bool())
  val ooo = Flipped(new OOOIEXIO(p))
  val lsu = new IEXLSUIO(p)
  val branchResolve = Decoupled(new BranchResolveTxn(p))
  val cmdIssue = Decoupled(new CmdIssueTxn(p))
  val trace = Decoupled(new TracePacket(p))
  val terminalPWrite = Valid(new IEXTerminalPWriteObservation(p))
}
