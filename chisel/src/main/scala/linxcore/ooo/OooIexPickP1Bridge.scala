package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}

class OooIexPickP1BridgeIO(val p: OooParams = OooParams()) extends Bundle {
  val pick = Flipped(Decoupled(new OooIexPickToken(p)))

  // Readyless address/read boundary owned by the canonical IQ. The bridge
  // does not retain or replicate either scheduling state or payload state.
  val query = Output(new OooIexSlotQuery(p))
  val queryState = Input(OooIexIssueSlotState())
  val queryRow = Input(new OooIexIssueRow(p))

  val p1 = Decoupled(new OooIexP1Request(p))
  val repick = Valid(new OooIexReadRepick(p))
  val rejected = Valid(new OooIexPickJoinReject(p))
}

/** Exact picker-token to P1 payload join.
  *
  * The picker retains only an address and compact identity. This bridge uses
  * that address to read the canonical IQ row, proves the scheduling/payload
  * join, derives P1 PC controls from generated recipe metadata, and forwards
  * the complete row without adding a second payload owner.
  *
  * A malformed join is consumed only when the P1 lane reports capacity. The
  * same edge emits an exact repick, allowing the IQ claim and retry writes to
  * collapse back to not-in-flight without losing the resident row.
  */
class OooIexPickP1Bridge(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooIexPickP1BridgeIO(p))

  private def sameMember(left: RobMemberKey, right: RobMemberKey): Bool =
    left.asUInt === right.asUInt

  val token = io.pick.bits
  val row = io.queryRow
  io.query := token.query

  val residentExact = io.queryState === OooIexIssueSlotState.ResidentS3 &&
    row.valid && !row.schedule.inFlight
  val identityExact = token.candidate.peId === row.peId &&
    token.candidate.stid === row.stid &&
    token.candidate.epoch === row.epoch &&
    token.candidate.transactionId === row.transactionId &&
    sameMember(token.candidate.member, row.member) &&
    token.candidate.reservation.asUInt === row.reservation.asUInt &&
    token.query.uopClass === row.reservation.uopClass &&
    token.query.bank === row.reservation.bank &&
    token.query.entry === row.reservation.speculativeSlot
  val recipeExact = row.recipe.valid &&
    row.recipe.opcode === row.opcode &&
    row.recipe.disposition === OooOpcodeDisposition.Dispatch.U
  val pcParentIndexInRange =
    row.pcParentIndex < p.maxArchitecturalParentRefs.U
  val safePcParentIndex = Mux(pcParentIndexInRange, row.pcParentIndex, 0.U)
  val pcToken = row.parentPcTokens(safePcParentIndex)
  val rowDispatchClass = row.reservation.uopClass.asUInt +& 1.U
  val childPcReadRequired = row.recipe.pcReadRequired &&
    row.recipe.pcReadClass === rowDispatchClass
  val pcMetadataExact = !childPcReadRequired ||
    (row.pcParentIndexValid && pcParentIndexInRange &&
      row.pcParentIndex < row.parentCount && pcToken.valid)
  val joinExact = residentExact && identityExact && recipeExact &&
    pcMetadataExact

  io.p1.valid := io.pick.valid && joinExact
  io.p1.bits.row := row
  io.p1.bits.pcReadRequired := childPcReadRequired
  io.p1.bits.pcParentIndex := row.pcParentIndex

  // Even a malformed token waits for real lane capacity. This prevents a
  // bridge-side retry from colliding with an older I1 denial on one lane.
  io.pick.ready := io.p1.ready
  val rejectedFire = io.pick.fire && !joinExact
  io.repick.valid := rejectedFire
  io.repick.bits.member := token.candidate.member
  io.repick.bits.reservation := token.candidate.reservation
  io.rejected.valid := rejectedFire
  io.rejected.bits.token := token
  io.rejected.bits.residentExact := residentExact
  io.rejected.bits.identityExact := identityExact
  io.rejected.bits.recipeExact := recipeExact
  io.rejected.bits.pcMetadataExact := pcMetadataExact

  when(io.p1.fire) {
    assert(joinExact,
      "P1 may accept only an exact canonical scheduling/payload join")
  }
}
