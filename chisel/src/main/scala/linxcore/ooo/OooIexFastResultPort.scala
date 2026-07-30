package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}
import linxcore.common.OperandClass

class OooIexFastResultPortIO(val p: OooParams = OooParams())
    extends Bundle {
  val writeback = Flipped(Decoupled(new OooFastResolveWriteback(p)))
  val wakeup = Flipped(Decoupled(new OooIexWakeup(p)))
  val pWriteReady = Input(Bool())

  val pWrite = Valid(new OooIexPFileWrite(p))
  val issueWakeup = Valid(new OooIexWakeup(p))
  val accepted = Output(Bool())
  val rejected = Output(Bool())
}

/** Atomic adapter from the fast-resolve terminal fork into one dedicated
  * physical-register write port and one dedicated issue wakeup port.
  *
  * The output write payload is driven directly from the held Decoupled bits,
  * so PRF owner preflight may determine `pWriteReady` without a valid/ready
  * combinational loop.  The two producer branches receive the same ready and
  * can only fire together after their exact identities agree.
  */
class OooIexFastResultPort(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooIexFastResultPortIO(p))

  val identityExact = io.writeback.bits.stid === io.wakeup.bits.stid &&
    io.writeback.bits.epoch === io.wakeup.bits.epoch &&
    io.writeback.bits.ptag === io.wakeup.bits.ptag &&
    io.writeback.bits.ptagGeneration === io.wakeup.bits.ptagGeneration &&
    io.wakeup.bits.operandClass === OperandClass.P
  val pairValid = io.writeback.valid && io.wakeup.valid
  val offered = io.writeback.valid || io.wakeup.valid
  val commonReady = io.pWriteReady && identityExact

  io.writeback.ready := commonReady
  io.wakeup.ready := commonReady

  io.pWrite.valid := pairValid && identityExact && io.pWriteReady
  io.pWrite.bits.commit := true.B
  io.pWrite.bits.key.stid := io.writeback.bits.stid
  io.pWrite.bits.key.epoch := io.writeback.bits.epoch
  io.pWrite.bits.key.ptag := io.writeback.bits.ptag
  io.pWrite.bits.key.generation := io.writeback.bits.ptagGeneration
  io.pWrite.bits.data := io.writeback.bits.data

  io.issueWakeup.valid := pairValid && identityExact && io.pWriteReady
  io.issueWakeup.bits := io.wakeup.bits
  io.accepted := io.writeback.fire && io.wakeup.fire
  io.rejected := offered && (!pairValid || !identityExact)

  when(offered) {
    assert(pairValid && identityExact,
      "fast writeback and wakeup require one exact atomic identity")
  }
  when(io.accepted) {
    assert(io.pWrite.valid && io.issueWakeup.valid,
      "fast result acceptance must write PRF and publish wakeup together")
  }
}
