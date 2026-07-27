package linxcore.ooo

import chisel3._
import chisel3.util.Decoupled

class OooO3RenameCoordinatorIO(val p: OooParams = OooParams()) extends Bundle {
  val reserve = Flipped(Decoupled(new OooD2GroupedTransaction(p)))
  val cancel = Input(Vec(p.stidCount, Bool()))

  val preparedValid = Output(Bool())
  val prepared = Output(new OooPRenamePreparedTransaction(p))
  val publishPermit = Input(Bool())
  val publishFire = Output(Bool())

  val completion = Flipped(Decoupled(new OooRobMemberCompletion(p)))
  val commit = Decoupled(new OooRobCommitBatch(p))
  val ptagReturn = Flipped(Decoupled(new OooPTagReturnBatch(p)))

  val queryStid = Input(UInt(p.stidWidth.W))
  val queryAtag = Input(UInt(p.archRegWidth.W))
  val speculativeMapping = Output(new PMapPayload(p))
  val committedMapping = Output(new PMapPayload(p))
  val mapQUsed = Output(Vec(p.stidCount, UInt(p.pMapQCountWidth.W)))

  val pcReadTokens = Input(Vec(p.pcReadPorts, new PcBufferToken(p)))
  val pcReadValid = Output(Vec(p.pcReadPorts, Bool()))
  val pcRead = Output(Vec(p.pcReadPorts, UInt(p.pcWidth.W)))

  val ptagFreeCount = Output(UInt(p.countWidth(p.pPhysRegs).W))
  val ptagProvisionalCount = Output(UInt(p.countWidth(p.pPhysRegs).W))
  val ptagPublishedCount = Output(UInt(p.countWidth(p.pPhysRegs).W))
  val robOccupiedGroups = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.robGroupsPerStid).W)))
}

/** Atomic D3/S1 seam extended through PTag allocation and P rename.
  *
  * D3 and the PTag staging pool claim on the same reserve handshake. Later,
  * ROB/BROB/PC publication, PTag publication, SMAP update, and MapQ insertion
  * occur on the same terminal fire. Dispatch remains the external permit owner
  * until O5 adds exact IQ reservations.
  */
class OooO3RenameCoordinator(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooO3RenameCoordinatorIO(p))

  val o3 = Module(new OooRobBrobPcCoordinator(p))
  val ptag = Module(new OooPTagStagingPool(p))
  val prename = Module(new OooProductionPRename(p))

  ptag.io.prepare.valid := io.reserve.valid
  ptag.io.prepare.bits := io.reserve.bits
  o3.io.reserve.valid := io.reserve.valid && ptag.io.prepareReady
  o3.io.reserve.bits := io.reserve.bits
  io.reserve.ready := o3.io.reserve.ready && ptag.io.prepareReady
  // D3 intentionally consumes stale virtual plans with zero mutation. Do not
  // turn that input handshake into a PTag claim: only a non-stale D3 reserve
  // may acquire the matching lease.
  ptag.io.reserveFire := io.reserve.fire && !o3.io.d3StaleRejected.valid

  o3.io.cancel := io.cancel
  ptag.io.cancel := io.cancel

  val preparedStid = o3.io.prepared.request.reservation.transaction.plan.stid
  val preparedStidInRange = preparedStid < p.stidCount.U
  val safePreparedStid = Mux(preparedStidInRange, preparedStid, 0.U)
  prename.io.prepare.valid := o3.io.preparedValid
  prename.io.prepare.bits := o3.io.prepared
  prename.io.ptagLease := ptag.io.provisional(safePreparedStid)

  io.preparedValid := o3.io.preparedValid && prename.io.prepareReady
  io.prepared := prename.io.prepared
  o3.io.publishPermit := io.publishPermit && prename.io.prepareReady
  prename.io.publishFire := o3.io.publishFire
  io.publishFire := o3.io.publishFire

  ptag.io.publish.valid := o3.io.publishFire
  ptag.io.publish.bits.stid := preparedStid
  ptag.io.publish.bits.transactionId :=
    o3.io.prepared.request.reservation.transaction.plan.transactionId
  when(o3.io.publishFire) {
    assert(io.preparedValid,
      "O3 P rename publication requires one exact prepared transaction")
    assert(!ptag.io.publishRejected.valid,
      "O3 P rename publication must publish the retained exact PTag lease")
  }

  o3.io.completion <> io.completion

  // O4.2 does not yet own CMAP/MapQ retirement. Seal both terminal interfaces
  // instead of allowing ROB state or a still-referenced PTag to retire early.
  // O4.3 replaces these constants with one retained exact commit transaction.
  io.commit.valid := false.B
  io.commit.bits := o3.io.commit.bits
  o3.io.commit.ready := false.B
  ptag.io.release.valid := false.B
  ptag.io.release.bits := 0.U.asTypeOf(ptag.io.release.bits)
  io.ptagReturn.ready := false.B

  prename.io.queryStid := io.queryStid
  prename.io.queryAtag := io.queryAtag
  io.speculativeMapping := prename.io.speculativeMapping
  io.committedMapping := prename.io.committedMapping
  io.mapQUsed := prename.io.mapQUsed

  o3.io.pcReadTokens := io.pcReadTokens
  io.pcReadValid := o3.io.pcReadValid
  io.pcRead := o3.io.pcRead

  io.ptagFreeCount := ptag.io.freeCount
  io.ptagProvisionalCount := ptag.io.provisionalCount
  io.ptagPublishedCount := ptag.io.publishedCount
  io.robOccupiedGroups := o3.io.robOccupiedGroups
}
