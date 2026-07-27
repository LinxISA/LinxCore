package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}

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
  val pCommitBusy = Output(Bool())
  val pCommitRejected = Valid(new OooPRenameCommitReject(p))
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
  for (stid <- 0 until p.stidCount) {
    o3.io.publishEligible(stid) := !prename.io.commitBusy ||
      prename.io.commitStid =/= stid.U
  }

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

  val exposedPrepareValid = RegInit(false.B)
  val exposedPrepareStid = RegInit(0.U(p.stidWidth.W))
  val exposedPrepareCanceled = exposedPrepareValid &&
    io.cancel(exposedPrepareStid)
  when(io.publishFire || exposedPrepareCanceled) {
    exposedPrepareValid := false.B
  }.elsewhen(io.preparedValid) {
    exposedPrepareValid := true.B
    exposedPrepareStid := preparedStid
  }

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

  val commitStid = o3.io.commit.bits.release.firstGroup.stid
  val commitStidInRange = commitStid < p.stidCount.U
  // A fully prepared row that was exposed in a prior cycle cannot be withdrawn.
  // Let that exact row publish first. The O3 coordinator also prevents a raw
  // same-STID ROB commit from exposing a new D3 grant in the first place.
  // Merely provisional, unprepared rows do not block older commit.
  val commitConflictsExposedPrepare = exposedPrepareValid &&
    commitStidInRange && exposedPrepareStid === commitStid
  prename.io.commitPrepare.valid := o3.io.commit.valid &&
    !commitConflictsExposedPrepare
  prename.io.commitPrepare.bits := o3.io.commit.bits
  prename.io.ptagReturn <> ptag.io.release
  io.commit.valid := o3.io.commit.valid && prename.io.commitReady
  io.commit.bits := o3.io.commit.bits
  o3.io.commit.ready := io.commit.ready && prename.io.commitReady
  val sharedCommitFire = io.commit.valid && io.commit.ready
  prename.io.commitFire := sharedCommitFire

  // External recovery return remains sealed until O6 supplies exact killed-row
  // authority. Architectural commit return is wired privately above.
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
  io.pCommitBusy := prename.io.commitBusy
  io.pCommitRejected := prename.io.commitRejected
}
