package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

object RecoveryCause extends ChiselEnum {
  val Branch, Exception, Interrupt, MemoryOrder, Debug, CtuCancel = Value
}

object RecoveryPhase extends ChiselEnum {
  val Prepare, Apply, Abort = Value
}

class RecoveryEvent(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val cause = RecoveryCause()
  val trigger = new RobIdentity(p)
  val instruction = new InstructionIdentity(p)
  val redirectPc = UInt(p.pcWidth.W)
  val trap = new TrapEvent(p)
}

class RecoveryPlan(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val phase = RecoveryPhase()
  val cause = RecoveryCause()
  val trigger = new RobIdentity(p)
  val survivingTailValid = Bool()
  val survivingTail = new RobIdentity(p)
  val redirectPc = UInt(p.pcWidth.W)
  val newEpoch = UInt(p.epochWidth.W)
  val firstKilledValid = Bool()
  val firstKilled = new RobIdentity(p)
  val lastKilled = new RobIdentity(p)
  val killedGroupCount =
    UInt(PrefixPacketContract.countWidth(p.ooo.robGroupsPerStid).W)
  val killedMemberCount =
    UInt(PrefixPacketContract.countWidth(p.ooo.robCapacityPerStid).W)
}

/** OOO-facing recovery target protocol.
  *
  * The target prepares without mutation. Apply or abort is a later terminal
  * broadcast carrying the same transaction identity.
  */
class RecoveryTargetIO(val p: CoreParams) extends Bundle {
  val prepare = Decoupled(new RecoveryPlan(p))
  val prepared = Flipped(Decoupled(new RecoveryPlan(p)))
  val apply = Valid(new RecoveryPlan(p))
  val abort = Valid(new RecoveryPlan(p))
}
