package linxcore.ooo

import chisel3._

/** Shared exact membership predicates for O7 recovery consumers.
  *
  * The grouped ROB has already resolved one request against its live ordered
  * window.  Downstream owners therefore consume the retained wrap-qualified
  * window instead of comparing native BID/RID magnitudes or building a CAM
  * over every killed ROB record for every physical queue row.
  */
object OooRecoveryMembership {
  private def memberOffset(
      p: OooParams,
      plan: OooResidencyRecoveryPlan,
      member: RobMemberKey): (UInt, Bool) = {
    val wrapsFromHead = member.group.ridSlot < plan.oldHead.ridSlot
    val offset = Wire(UInt(p.nonFlushPrefixCountWidth.W))
    offset := Mux(
      wrapsFromHead,
      p.robGroupsPerStid.U + member.group.ridSlot - plan.oldHead.ridSlot,
      member.group.ridSlot - plan.oldHead.ridSlot)
    (offset, wrapsFromHead)
  }

  private def memberMatchesPivot(
      plan: OooResidencyRecoveryPlan,
      member: RobMemberKey): Bool = {
    plan.pivot.group.valid &&
      member.group.asUInt === plan.pivot.group.asUInt &&
      member.bid.asUInt === plan.pivot.bid.asUInt &&
      member.brobGeneration === plan.pivot.brobGeneration &&
      member.residentGeneration === plan.pivot.residentGeneration
  }

  /** True only when `member` belongs to the exact old ROB window authorized
    * by `plan`.  The wrap-qualified ROB key is the group identity; native BID
    * magnitude is deliberately not used as an age comparator.
    */
  def memberInOldWindow(
      p: OooParams,
      plan: OooResidencyRecoveryPlan,
      member: RobMemberKey): Bool = {
    val (offset, wrapsFromHead) = memberOffset(p, plan, member)
    val expectedGeneration = plan.oldHead.ridGeneration + wrapsFromHead.asUInt

    plan.valid && plan.oldHead.valid && member.group.valid && member.bid.valid &&
      member.group.peId === plan.oldHead.peId &&
      member.group.stid === plan.oldHead.stid &&
      member.group.ridGeneration === expectedGeneration &&
      offset < plan.oldOccupied &&
      member.memberIndex < p.maxOrdinaryUopsPerGroup.U
  }

  /** True only when `member` belongs to the exact old ROB window authorized
    * by `plan` and lies in its killed physical-member suffix.
    */
  def memberKilled(
      p: OooParams,
      plan: OooResidencyRecoveryPlan,
      member: RobMemberKey): Bool = {
    val (offset, _) = memberOffset(p, plan, member)
    val exactWindowMember = memberInOldWindow(p, plan, member)

    val completeKilledGroup = exactWindowMember &&
      offset >= plan.newOccupied
    val partialPivotKilled = exactWindowMember &&
      plan.survivingPivotValid && offset === plan.pivotOffset &&
      memberMatchesPivot(plan, member) &&
      member.memberIndex >= plan.survivingPivotPhysicalMemberCount &&
      member.memberIndex < plan.pivotPhysicalMemberCount

    completeKilledGroup || partialPivotKilled
  }
}
