package linxcore.ooo

import chisel3._

/** Retained issue-pipeline stage addressed by one late cancel request. */
object OooIexStageCancelPoint extends ChiselEnum {
  val I1, I2 = Value
}

/** Shared reason-mask contract for conflicts discovered after P1.
  *
  * The bit positions deliberately reuse [[OooIexIssueBlockReason]] so one
  * physical-resource owner can report the same condition before pick as an
  * early block or after pick as an exact stage cancel. Only pipe-local reasons
  * are legal at this boundary; global, power, class, and queue pressure must
  * remain early policy.
  */
object OooIexStageCancelReason {
  val AllowedBits: Seq[Int] = Seq(
    OooIexIssueBlockReason.DomainStructural,
    OooIexIssueBlockReason.LatencyReservation,
    OooIexIssueBlockReason.ReflowReservation,
    OooIexIssueBlockReason.SideDoorConflict,
    OooIexIssueBlockReason.ResultBusReservation)

  val AllowedMask: BigInt =
    AllowedBits.foldLeft(BigInt(0))((mask, bit) => mask | (BigInt(1) << bit))

  def legal(mask: UInt): Bool = {
    val allowed = AllowedMask.U(OooIexIssueBlockReason.Count.W)
    mask.orR && !(mask & ~allowed).orR
  }
}

/** Exact, backpressurable cancellation of one retained I1 or I2 transaction. */
class OooIexStageCancel(val p: OooParams = OooParams()) extends Bundle {
  val stage = OooIexStageCancelPoint()
  val member = new RobMemberKey(p)
  val reservation = new DispatchReservation(p)
  val reasonMask = UInt(OooIexIssueBlockReason.Count.W)
}

/** Typed evidence for a stale or malformed late-cancel request. */
class OooIexStageCancelReject(val p: OooParams = OooParams()) extends Bundle {
  val request = new OooIexStageCancel(p)
  val stageExact = Bool()
  val occupied = Bool()
  val identityExact = Bool()
  val reasonExact = Bool()
}
