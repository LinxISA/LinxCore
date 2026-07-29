package linxcore.ooo

import chisel3._

import linxcore.lsu.{STQEntryBankRow, STQEntryStatus}

class OooStqRecoveryProjectionIO(
    val p: OooParams,
    val stqEntries: Int)
    extends Bundle {
  val recoveryValid = Input(Bool())
  val recovery = Input(new OooResidencyRecoveryPlan(p))
  val rows = Input(Vec(stqEntries, new STQEntryBankRow(
    p.robGroupsPerStid,
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

/** Projects the ROB-authorized residency suffix onto canonical STQ rows.
  * Compatibility rows without an exact owner remain on the legacy flush path.
  */
class OooStqRecoveryProjection(
    val p: OooParams = OooParams(),
    val stqEntries: Int = 16)
    extends Module {
  val io = IO(new OooStqRecoveryProjectionIO(p, stqEntries))

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
    val targetScope = io.recoveryValid && io.recovery.valid && row.valid &&
      owner.valid && row.stid === io.recovery.oldHead.stid &&
      row.peId === io.recovery.oldHead.peId
    malformed(index) := targetScope && !ownerConsistent
    killed(index) := targetScope && ownerConsistent &&
      OooRecoveryMembership.memberKilled(p, io.recovery, member)
    blocked(index) := killed(index) &&
      (row.status =/= STQEntryStatus.Wait)
  }

  val rejected = malformed.asUInt.orR
  io.freeMask := Mux(rejected, 0.U,
    VecInit((0 until stqEntries).map(index =>
      killed(index) && !blocked(index))).asUInt)
  io.statusBlockedMask := blocked.asUInt
  io.malformedMask := malformed.asUInt
  io.rejected := rejected
}
