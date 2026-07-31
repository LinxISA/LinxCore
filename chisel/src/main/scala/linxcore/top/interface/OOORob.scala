package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class OOORobPreparedEntry(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val rob = new RobIdentity(p)
  val commit = new CommitEntry(p)
  val rename = new RenameCommitReleaseEntry(p)
}

class OOORobPrepared(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W)
  val entries = Vec(p.ooo.d3PrefixWidth, new OOORobPreparedEntry(p))
}

class OOORobReleaseEntry(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val rob = new RobIdentity(p)
}

class OOORobReleaseTxn(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.widths.retireWidth).W)
  val lanes = Vec(p.widths.retireWidth, new OOORobReleaseEntry(p))
}

class OOORobCommitPreviewEntry(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val commit = new CommitEntry(p)
  val rename = new RenameCommitReleaseEntry(p)
}

class OOORobCommitPreview(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.widths.retireWidth).W)
  val entries = Vec(p.widths.retireWidth, new OOORobCommitPreviewEntry(p))
  val headValid = Bool()
  val head = new RobIdentity(p)
  val headTrap = new TrapEvent(p)
}

class BROBPreparedEntry(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val stid = UInt(InterfaceWidth.index(p.ooo.stidCount).W)
  val bid = UInt(p.nativeBidWidth.W)
  val brobGeneration = UInt(p.brobGenerationWidth.W)
  val allocated = Bool()
}

class BROBPrepared(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W)
  val entries = Vec(p.ooo.d3PrefixWidth, new BROBPreparedEntry(p))
}

class BROBReleaseTxn(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.widths.retireWidth).W)
  val entries = Vec(p.widths.retireWidth, new RobIdentity(p))
}

class CommitControlTxn(val p: CoreParams) extends Bundle {
  val commit = new CommitTxn(p)
  val rename = new RenameCommitReleaseTxn(p)
  val robRelease = new OOORobReleaseTxn(p)
  val brobRelease = new BROBReleaseTxn(p)
  val trap = new TrapEvent(p)
}

class RecoveryCandidateLookup(val p: CoreParams) extends Bundle {
  val event = new RecoveryEvent(p)
}

class RecoveryCandidateStatus(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val trigger = new RobIdentity(p)
  val eligible = Bool()
  val rejected = Bool()
  val ageToken = UInt(p.transactionIdWidth.W)
  val headTrap = Bool()
}

object RecoveryAge {
  def tokenWidth(p: CoreParams): Int = p.transactionIdWidth

  def requireUnambiguousWindow(p: CoreParams): Unit = {
    val tokenSpace = BigInt(1) << tokenWidth(p)
    val maxLiveWindow =
      BigInt(p.ooo.stidCount) * BigInt(p.ooo.robCapacityPerStid)
    require(
      tokenSpace > (maxLiveWindow * 2),
      "recovery age token space must be more than twice the global live ROB window")
  }

  def older(a: UInt, b: UInt): Bool = {
    val diff = b - a
    diff =/= 0.U && !diff(diff.getWidth - 1)
  }
}

object RecoveryPlanContract {
  private def memberOrdinal(id: RobIdentity): UInt =
    Cat(id.ridGeneration, id.ridSlot, id.memberIndex)

  def sameRobRequest(response: RecoveryPlan, request: RecoveryPlan): Bool =
    response.phase === RecoveryPhase.Prepare &&
      request.phase === RecoveryPhase.Prepare &&
      response.transactionId === request.transactionId &&
      response.cause === request.cause &&
      response.trigger.asUInt === request.trigger.asUInt &&
      response.redirectPc === request.redirectPc &&
      response.newEpoch === request.newEpoch

  def sameTransactionIgnoringPhase(a: RecoveryPlan, b: RecoveryPlan): Bool =
    a.transactionId === b.transactionId &&
      a.cause === b.cause &&
      a.trigger.asUInt === b.trigger.asUInt &&
      a.survivingTailValid === b.survivingTailValid &&
      a.survivingTail.asUInt === b.survivingTail.asUInt &&
      a.redirectPc === b.redirectPc &&
      a.newEpoch === b.newEpoch &&
      a.firstKilledValid === b.firstKilledValid &&
      a.firstKilled.asUInt === b.firstKilled.asUInt &&
      a.lastKilled.asUInt === b.lastKilled.asUInt &&
      a.killedGroupCount === b.killedGroupCount &&
      a.killedMemberCount === b.killedMemberCount

  def suffixMember(plan: RecoveryPlan, member: RobIdentity): Bool = {
    val sameIdentitySpace =
      member.peId === plan.trigger.peId && member.stid === plan.trigger.stid
    val first = memberOrdinal(plan.firstKilled)
    val last = memberOrdinal(plan.lastKilled)
    val current = memberOrdinal(member)
    val noWrap = first <= last
    val inWindow = Mux(noWrap,
      current >= first && current <= last,
      current >= first || current <= last)
    plan.firstKilledValid && sameIdentitySpace && inWindow
  }

  def legalSuffixWindow(plan: RecoveryPlan): Bool = {
    val empty = !plan.firstKilledValid &&
      plan.killedGroupCount === 0.U && plan.killedMemberCount === 0.U
    val nonEmpty = plan.firstKilledValid &&
      plan.killedGroupCount =/= 0.U &&
      plan.killedMemberCount =/= 0.U &&
      plan.firstKilled.stid === plan.trigger.stid &&
      plan.lastKilled.stid === plan.trigger.stid
    empty || nonEmpty
  }
}

class RecoveryPlanContractProbeIO(val p: CoreParams) extends Bundle {
  val a = Input(new RecoveryPlan(p))
  val b = Input(new RecoveryPlan(p))
  val member = Input(new RobIdentity(p))
  val sameIgnoringPhase = Output(Bool())
  val memberKilled = Output(Bool())
  val legalWindow = Output(Bool())
}

class RecoveryPlanContractProbe(val p: CoreParams) extends Module {
  val io = IO(new RecoveryPlanContractProbeIO(p))
  io.sameIgnoringPhase :=
    RecoveryPlanContract.sameTransactionIgnoringPhase(io.a, io.b)
  io.memberKilled := RecoveryPlanContract.suffixMember(io.a, io.member)
  io.legalWindow := RecoveryPlanContract.legalSuffixWindow(io.a)
}
