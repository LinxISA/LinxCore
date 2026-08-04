package linxcore.ooo

import chisel3._
import linxcore.params.{CoreParams, CTUParams, DTUParams, IEXParams, IFUParams,
  WidthParams}
import linxcore.top.interface.{RecoveryPlan, RecoveryPlanContract,
  InterfaceWidth, RobIdentity}

/** Exact value mapping used while the retained IEX mechanisms still carry
  * their private row payload.  Recovery age is never recomputed here: the OOO
  * owner publishes the killed suffix in one canonical [[RecoveryPlan]], and
  * every retention owner asks the canonical contract whether its exact ROB
  * identity belongs to that suffix.
  */
object OooRecoveryMembership {
  private def oldMemberOffset(
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

  private def oldMemberMatchesPivot(
      plan: OooResidencyRecoveryPlan,
      member: RobMemberKey): Bool =
    plan.pivot.group.valid &&
      member.group.asUInt === plan.pivot.group.asUInt &&
      member.bid.asUInt === plan.pivot.bid.asUInt &&
      member.brobGeneration === plan.pivot.brobGeneration &&
      member.residentGeneration === plan.pivot.residentGeneration

  /** Temporary predicate retained only for owners outside the current IEX
    * cutover.  It consumes their existing plan directly and never converts a
    * canonical plan back into the displaced representation.
    */
  def memberInOldWindow(
      p: OooParams,
      plan: OooResidencyRecoveryPlan,
      member: RobMemberKey): Bool = {
    val (offset, wrapsFromHead) = oldMemberOffset(p, plan, member)
    val expectedGeneration = plan.oldHead.ridGeneration + wrapsFromHead.asUInt

    plan.valid && plan.oldHead.valid && member.group.valid && member.bid.valid &&
      member.group.peId === plan.oldHead.peId &&
      member.group.stid === plan.oldHead.stid &&
      member.group.ridGeneration === expectedGeneration &&
      offset < plan.oldOccupied &&
      member.memberIndex < p.maxOrdinaryUopsPerGroup.U
  }

  /** Temporary overload for non-IEX owners awaiting their own atomic
    * recovery cutover.  Canonical IEX retention owners must use the overload
    * taking `CoreParams` and `RecoveryPlan` below.
    */
  def memberKilled(
      p: OooParams,
      plan: OooResidencyRecoveryPlan,
      member: RobMemberKey): Bool = {
    val (offset, _) = oldMemberOffset(p, plan, member)
    val exactWindowMember = memberInOldWindow(p, plan, member)
    val completeKilledGroup = exactWindowMember && offset >= plan.newOccupied
    val partialPivotKilled = exactWindowMember &&
      plan.survivingPivotValid && offset === plan.pivotOffset &&
      oldMemberMatchesPivot(plan, member) &&
      member.memberIndex >= plan.survivingPivotPhysicalMemberCount &&
      member.memberIndex < plan.pivotPhysicalMemberCount
    completeKilledGroup || partialPivotKilled
  }

  /** Canonical parameter view for private mechanisms whose callers have not
    * yet been lifted to `CoreParams`.  All identity-bearing widths and ROB
    * geometry are copied exactly from the mechanism parameter record.
    */
  def coreParams(p: OooParams): CoreParams = {
    val widths = WidthParams(
      fetchWidth = p.instructionDecodeWidth,
      ctuOutputWidth = p.instructionDecodeWidth,
      decodeWidth = p.instructionDecodeWidth,
      renameWidth = p.renameWidth,
      dispatchWidth = p.dispatchWidth,
      issueWidth = p.dispatchWidth,
      retireWidth = p.retireGroupWidth)
    CoreParams(
      widths = widths,
      ifu = IFUParams(fetchWidth = widths.fetchWidth,
        ctuTransferWidth = widths.ctuOutputWidth),
      ctu = CTUParams(inputWidth = widths.ctuOutputWidth,
        outputWidth = widths.ctuOutputWidth),
      ooo = p.toMainline,
      iex = IEXParams(issueWidth = widths.issueWidth),
      dtu = DTUParams(traceWidth = math.max(
        widths.retireWidth, widths.fetchWidth)),
      pcWidth = p.pcWidth,
      instructionWidth = p.instructionWidth,
      opcodeWidth = p.opcodeWidth,
      archRegWidth = p.archRegWidth,
      lsidWidth = p.lsidWidth,
      maxMemoryRequestsPerInstruction = p.maxMemoryRequestsPerInstruction,
      peIdWidth = p.peIdWidth,
      instructionIdWidth = p.instructionIdWidth,
      transactionIdWidth = p.transactionIdWidth,
      memoryTransactionIdWidth = p.memoryTransactionIdWidth,
      memoryTransactionGenerationWidth =
        p.memoryTransactionGenerationWidth,
      epochWidth = p.epochWidth,
      brobGenerationWidth = p.brobGenerationWidth,
      ridGenerationWidth = p.ridGenerationWidth,
      residentGenerationWidth = p.residentGenerationWidth,
      memoryAttemptGenerationWidth = p.loadGenerationWidth,
      trapCauseWidth = p.trapCauseWidth,
      maxSourceOperands = p.maxSourceOperands,
      maxDestinationOperands = p.maxDestinationOperands)
  }

  def requireCompatible(p: OooParams, core: CoreParams): Unit = {
    require(p.peIdWidth == core.peIdWidth &&
      p.stidWidth == core.ooo.stidWidth &&
      p.ridSlotWidth == core.ooo.ridSlotWidth &&
      p.ridGenerationWidth == core.ridGenerationWidth &&
      p.nativeBidWidth == core.nativeBidWidth &&
      p.brobGenerationWidth == core.brobGenerationWidth &&
      p.residentGenerationWidth == core.residentGenerationWidth &&
      p.memoryTransactionIdWidth == core.memoryTransactionIdWidth &&
      p.memoryTransactionGenerationWidth ==
        core.memoryTransactionGenerationWidth &&
      p.robMemberIndexWidth == core.ooo.robMemberIndexWidth,
      "private IEX ROB identity fields must cover canonical CoreParams")
    require(p.robGroupsPerStid == core.ooo.robGroupsPerStid &&
      p.stidCount == core.ooo.stidCount,
      "private IEX ROB geometry must equal canonical CoreParams")
  }

  def robIdentity(
      p: OooParams,
      core: CoreParams,
      member: RobMemberKey): RobIdentity = {
    requireCompatible(p, core)
    val identity = Wire(new RobIdentity(core))
    identity.peId := member.group.peId
    identity.stid := member.group.stid
    identity.ridSlot := member.group.ridSlot
    identity.ridGeneration := member.group.ridGeneration
    identity.memberIndex := member.memberIndex
    identity.residentGeneration := member.residentGeneration
    identity.bid := member.bid.value
    identity.brobGeneration := member.brobGeneration

    identity
  }

  def memberKilled(
      p: OooParams,
      core: CoreParams,
      plan: RecoveryPlan,
      member: RobMemberKey): Bool =
    member.group.valid && member.bid.valid &&
      member.memberIndex < core.ooo.maxInstructionsPerRobGroup.U &&
      RecoveryPlanContract.suffixMember(plan, robIdentity(p, core, member))
}
