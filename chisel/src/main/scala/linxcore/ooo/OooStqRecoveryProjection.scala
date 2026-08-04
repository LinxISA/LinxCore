package linxcore.ooo

import chisel3._

import linxcore.lsu.{STQEntryBankRow, STQEntryStatus}
import linxcore.params.CoreParams
import linxcore.top.interface.{RecoveryPhase, RecoveryPlan,
  RecoveryPlanContract}

class OooStqRecoveryProjectionIO(
    val core: CoreParams,
    val stqEntries: Int)
    extends Bundle {
  val p: OooParams = OooIexPhysicalProfile.fromCoreParams(core).params
  val prepareValid = Input(Bool())
  val prepare = Input(new RecoveryPlan(core))
  val rows = Input(Vec(stqEntries, new STQEntryBankRow(
    p.robIdentityGroupsPerStid,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.stidWidth,
    mapQDepth = p.tuMapQDepthPerStid,
    lsidWidth = p.lsidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    leaseGenerationWidth = p.executeSlotGenerationWidth)))
  val freeMask = Output(UInt(stqEntries.W))
  val statusBlockedMask = Output(UInt(stqEntries.W))
  val malformedMask = Output(UInt(stqEntries.W))
  val rejected = Output(Bool())
}

/** Projects one canonical ROB-authorized suffix onto canonical STQ rows. */
class OooStqRecoveryProjection(
    val core: CoreParams,
    val stqEntries: Int = 16)
    extends Module {
  val p: OooParams = OooIexPhysicalProfile.fromCoreParams(core).params
  OooRecoveryMembership.requireCompatible(p, core)
  val io = IO(new OooStqRecoveryProjectionIO(core, stqEntries))

  val planLegal = io.prepareValid &&
    io.prepare.phase === RecoveryPhase.Prepare &&
    io.prepare.trigger.stid < p.stidCount.U &&
    RecoveryPlanContract.legalSuffixWindow(io.prepare)

  val killed = Wire(Vec(stqEntries, Bool()))
  val malformed = Wire(Vec(stqEntries, Bool()))
  val blocked = Wire(Vec(stqEntries, Bool()))
  for (index <- 0 until stqEntries) {
    val row = io.rows(index)
    val owner = row.exactOwner
    val member = Wire(new RobMemberKey(p))
    member := 0.U.asTypeOf(member)
    member.group.valid := owner.valid
    member.group.peId := owner.peId
    member.group.stid := owner.stid
    member.group.ridSlot := owner.ridSlot
    member.group.ridGeneration := owner.ridGeneration
    member.bid.valid := owner.nativeBidValid
    member.bid.value := owner.nativeBid
    member.brobGeneration := owner.brobGeneration
    member.memberIndex := owner.memberIndex
    member.residentGeneration := owner.residentGeneration

    val ownerConsistent = owner.valid && owner.nativeBidValid &&
      owner.peId === row.peId && owner.stid === row.stid &&
      row.storeIdFullValid
    val targetScope = planLegal && row.valid &&
      row.stid === io.prepare.trigger.stid &&
      row.peId === io.prepare.trigger.peId
    malformed(index) := targetScope && !ownerConsistent
    killed(index) := targetScope && ownerConsistent &&
      OooRecoveryMembership.memberKilled(p, core, io.prepare, member)
    blocked(index) := killed(index) &&
      (row.status =/= STQEntryStatus.Wait)
  }

  val rejected = (io.prepareValid && !planLegal) || malformed.asUInt.orR
  io.freeMask := Mux(rejected, 0.U,
    VecInit((0 until stqEntries).map(index =>
      killed(index) && !blocked(index))).asUInt)
  io.statusBlockedMask := blocked.asUInt
  io.malformedMask := malformed.asUInt
  io.rejected := rejected
}
