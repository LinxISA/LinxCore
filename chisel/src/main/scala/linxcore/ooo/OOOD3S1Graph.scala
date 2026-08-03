package linxcore.ooo

import chisel3._
import chisel3.util.{Arbiter, Decoupled, Mux1H, Valid}
import linxcore.params.CoreParams
import linxcore.top.interface._

/** Canonical production graph below the D2 boundary. The public OOO box and
  * the multi-STID reference test both use this exact owner composition.
  */
class OOOD3S1GraphIO(val p: CoreParams) extends Bundle {
  val fromD2 = Flipped(Decoupled(new D2AdmissionGroup(p)))
  val ridTailSlot = Output(Vec(p.ooo.stidCount,
    UInt(InterfaceWidth.index(p.ooo.robGroupsPerStid).W)))
  val ridTailGeneration = Output(Vec(p.ooo.stidCount,
    UInt(p.ridGenerationWidth.W)))
  val iex = new OOOIEXIO(p)
  val commit = Decoupled(new CommitTxn(p))
  val trap = Decoupled(new TrapEvent(p))
  val interrupt = Flipped(Valid(new InterruptRequest(p)))
  val recoveryToD1 = new RecoveryTargetIO(p)
  val recoveryToIfu = new RecoveryTargetIO(p)
  val recoveryToCtu = new RecoveryTargetIO(p)
  val recoveryToLsu = new RecoveryTargetIO(p)
  val trace = Decoupled(new TracePacket(p))
}

class OOOD3S1Graph(val p: CoreParams) extends Module {
  val io = IO(new OOOD3S1GraphIO(p))
  private def select[T <: Data](values: Vec[T], index: UInt): T =
    if (p.ooo.stidCount == 1) values(0) else values(index)

  val renu = Module(new RENU(p))
  val memoryOrder = Module(new OooMemoryOrderAllocator(p))
  val rob = Module(new ROB(p))
  val brob = Module(new BROB(p))
  val pcBuffer = Module(new OooPcBuffer(p))
  val dispatch = Module(new Dispatch(p))
  val commitControl = Module(new CommitControl(p))
  private val recoveryTargetCount = 9
  val recovery = Module(new RecoveryControl(p, recoveryTargetCount))

  val d2Stid = io.fromD2.bits.entries(0).uop.rob.stid
  val earlyRecoveryValid = recovery.io.robPrepare.valid ||
    rob.io.recoveryPrepared.valid
  val earlyRecoveryStid = Mux(recovery.io.robPrepare.valid,
    recovery.io.robPrepare.bits.trigger.stid,
    rob.io.recoveryPrepared.bits.trigger.stid)
  val d2RecoveryFenced = earlyRecoveryValid && d2Stid === earlyRecoveryStid
  memoryOrder.io.prepare.valid := io.fromD2.valid && !d2RecoveryFenced
  memoryOrder.io.prepare.bits := io.fromD2.bits
  renu.io.fromD2.valid := io.fromD2.valid && !d2RecoveryFenced &&
    memoryOrder.io.prepareReady
  renu.io.fromD2.bits := io.fromD2.bits
  io.fromD2.ready := !d2RecoveryFenced && renu.io.fromD2.ready &&
    memoryOrder.io.prepareReady
  memoryOrder.io.reserveFire := io.fromD2.fire
  memoryOrder.io.cancel := VecInit(Seq.fill(p.ooo.stidCount)(false.B))
  io.ridTailSlot := rob.io.ridTailSlot
  io.ridTailGeneration := rob.io.ridTailGeneration

  pcBuffer.io.prepare.valid := renu.io.candidate.valid
  pcBuffer.io.prepare.bits := renu.io.candidate.bits
  renu.io.prefixLimit.valid := pcBuffer.io.prepareReady
  renu.io.prefixLimit.bits.count := pcBuffer.io.prepared.count
  renu.io.prefixLimit.bits.groupCount := pcBuffer.io.prepared.groupCount

  val d3StidRaw = renu.io.toD3.bits.entries(0).uop.decoded.rob.stid
  val d3Stid = Mux(d3StidRaw < p.ooo.stidCount.U, d3StidRaw, 0.U)
  val liveMemoryOrder = select(memoryOrder.io.provisional, d3Stid)
  val liveMemoryOrderLanes = select(memoryOrder.io.provisionalLanes, d3Stid)
  val stateAfterPrefix = Wire(new MemoryOrderState(p))
  stateAfterPrefix := liveMemoryOrder.after
  val nextMemoryLane = Mux1H((0 until p.ooo.d3PrefixWidth).map { lane =>
    (renu.io.toD3.bits.count === lane.U) -> liveMemoryOrderLanes(lane)
  })
  when(renu.io.toD3.bits.count < liveMemoryOrder.count) {
    stateAfterPrefix.lsid := nextMemoryLane.firstLsid
    stateAfterPrefix.lid := nextMemoryLane.firstLid
    stateAfterPrefix.sid := nextMemoryLane.firstSid
    stateAfterPrefix.yostValid := nextMemoryLane.yostValid
    stateAfterPrefix.yostLsid := nextMemoryLane.yostLsid
    stateAfterPrefix.yostSid := nextMemoryLane.yostSid
    stateAfterPrefix.yoldValid := nextMemoryLane.yoldValid
    stateAfterPrefix.yoldLsid := nextMemoryLane.yoldLsid
    stateAfterPrefix.yoldLid := nextMemoryLane.yoldLid
  }
  val d3WithMemory = Wire(new D3RenameGroup(p))
  d3WithMemory := renu.io.toD3.bits
  d3WithMemory.memoryOrder := liveMemoryOrder
  d3WithMemory.memoryOrder.count := renu.io.toD3.bits.count
  d3WithMemory.memoryOrder.after := stateAfterPrefix
  for (lane <- 0 until p.ooo.d3PrefixWidth) {
    when(lane.U < renu.io.toD3.bits.count) {
      d3WithMemory.entries(lane).memoryOrder := liveMemoryOrderLanes(lane)
    }
  }
  val d3WithPc = Wire(new D3RenameGroup(p))
  d3WithPc := d3WithMemory
  for (lane <- 0 until p.ooo.d3PrefixWidth) {
    when(lane.U < pcBuffer.io.prepared.count) {
      d3WithPc.entries(lane).pcBufferIndexOffset :=
        pcBuffer.io.prepared.lanes(lane)
    }
  }
  memoryOrder.io.publishPrepare.valid := renu.io.toD3.valid
  memoryOrder.io.publishPrepare.bits := d3WithPc
  rob.io.prepare.valid := renu.io.toD3.valid
  rob.io.prepare.bits := d3WithPc
  brob.io.prepare.valid := renu.io.toD3.valid
  brob.io.prepare.bits := d3WithPc
  val residencyPreviewReady = rob.io.prepare.ready && brob.io.prepare.ready &&
    pcBuffer.io.prepareReady
  val d3RecoveryFenced = earlyRecoveryValid && d3StidRaw === earlyRecoveryStid
  dispatch.io.in.valid := renu.io.toD3.valid && residencyPreviewReady &&
    memoryOrder.io.publishReady && !d3RecoveryFenced
  dispatch.io.in.bits := d3WithPc
  rob.io.brobPrepared := brob.io.prepared
  brob.io.robPrepared := rob.io.prepared
  renu.io.publicationIdentity.valid :=
    renu.io.toD3.valid && residencyPreviewReady
  renu.io.publicationIdentity.bits := rob.io.prepared
  dispatch.io.robPrepared := rob.io.prepared
  dispatch.io.brobPrepared := brob.io.prepared
  val d3Ready = !d3RecoveryFenced && residencyPreviewReady &&
    dispatch.io.in.ready && memoryOrder.io.publishReady
  val d3Fire = renu.io.toD3.valid && d3Ready
  renu.io.toD3.ready := d3Ready
  rob.io.publishFire := d3Fire
  brob.io.publishFire := d3Fire
  pcBuffer.io.publishFire := d3Fire
  memoryOrder.io.publishFire := d3Fire
  rob.io.publicationTransactionBase := dispatch.io.publicationTransactionBase
  when(d3Fire) {
    assert(renu.io.publicationIdentity.valid,
      "canonical RENU publication requires ROB-prepared full identities")
  }
  assert(dispatch.io.in.fire === d3Fire,
    "Dispatch reservation must share the unique D3 publication fire")

  io.iex.aluDispatch <> dispatch.io.iex.aluDispatch
  io.iex.bruDispatch <> dispatch.io.iex.bruDispatch
  io.iex.aguDispatch <> dispatch.io.iex.aguDispatch
  io.iex.storeDispatch <> dispatch.io.iex.storeDispatch
  io.iex.systemDispatch <> dispatch.io.iex.systemDispatch
  io.iex.cmdDispatch <> dispatch.io.iex.cmdDispatch
  pcBuffer.io.readAddress := io.iex.pcBufferReadAddress
  io.iex.pcBufferReadPcBase := pcBuffer.io.readPcBase

  val completionArb = Module(new Arbiter(new RobResolveTxn(p), p.widths.issueWidth))
  for (lane <- 0 until p.widths.issueWidth) {
    completionArb.io.in(lane) <> io.iex.robResolve(lane)
  }
  rob.io.completion <> completionArb.io.out

  commitControl.io.rob.valid := rob.io.commit.valid
  commitControl.io.rob.bits := rob.io.commit.bits
  commitControl.io.residentHeads := rob.io.residentHeads
  val interrupts = Wire(Vec(p.ooo.stidCount, new InterruptRequest(p)))
  for (stid <- 0 until p.ooo.stidCount) {
    interrupts(stid) := io.interrupt.bits
    interrupts(stid).valid := io.interrupt.valid &&
      io.interrupt.bits.valid && io.interrupt.bits.stid === stid.U
  }
  commitControl.io.interrupts := interrupts
  commitControl.io.interruptBoundaryValid := rob.io.commit.bits.headValid
  commitControl.io.interruptBoundary := rob.io.commit.bits.head
  for (stid <- 0 until p.ooo.stidCount) {
    commitControl.io.recoveryFence(stid) :=
      (recovery.io.robPrepare.valid &&
        recovery.io.robPrepare.bits.trigger.stid === stid.U) ||
      (rob.io.recoveryPrepared.valid &&
        rob.io.recoveryPrepared.bits.trigger.stid === stid.U)
  }
  commitControl.io.robNoflushReady <> io.iex.robNoflushReady
  io.iex.robNoflush <> commitControl.io.robNoflush
  io.iex.systemIssue.foreach(_.ready := false.B)

  val releaseProbe = rob.io.commit.valid && rob.io.commit.bits.count =/= 0.U
  pcBuffer.io.commitPreview.valid := releaseProbe
  pcBuffer.io.commitPreview.bits := commitControl.io.out.bits.commit
  rob.io.release.valid := releaseProbe
  rob.io.release.bits := commitControl.io.out.bits.robRelease
  renu.io.release.valid := releaseProbe
  renu.io.release.bits := commitControl.io.out.bits.rename
  brob.io.release.valid := releaseProbe
  brob.io.release.bits := commitControl.io.out.bits.brobRelease
  commitControl.io.robReleaseReady := rob.io.releaseReady
  commitControl.io.renameReleaseReady := renu.io.releaseReady
  commitControl.io.brobReleaseReady := brob.io.releaseReady
  commitControl.io.pcBufferCommitReady := pcBuffer.io.commitReady

  io.commit.valid := commitControl.io.out.valid &&
    commitControl.io.out.bits.commit.count =/= 0.U
  io.commit.bits := commitControl.io.out.bits.commit
  io.trap.valid := commitControl.io.out.valid &&
    commitControl.io.out.bits.trap.valid
  io.trap.bits := commitControl.io.out.bits.trap
  commitControl.io.out.ready :=
    (!io.commit.valid || io.commit.ready) && (!io.trap.valid || io.trap.ready)
  val commitFire = commitControl.io.out.fire
  rob.io.commit.ready := true.B
  rob.io.commitApply := commitFire
  rob.io.releaseApply := commitFire
  renu.io.releaseApply := commitFire
  brob.io.releaseApply := commitFire
  pcBuffer.io.commitApply := commitFire
  when(commitFire && commitControl.io.out.bits.commit.count =/= 0.U) {
    assert(rob.io.releaseReady && renu.io.releaseReady && brob.io.releaseReady &&
      pcBuffer.io.commitReady,
      "canonical commit must atomically apply every owner release")
    assert(commitControl.io.out.bits.rename.count ===
      commitControl.io.out.bits.commit.count)
    assert(commitControl.io.out.bits.robRelease.count ===
      commitControl.io.out.bits.commit.count)
    assert(commitControl.io.out.bits.brobRelease.count ===
      commitControl.io.out.bits.commit.count)
    for (lane <- 0 until p.widths.retireWidth) {
      when(lane.U < commitControl.io.out.bits.commit.count) {
        val identity = commitControl.io.out.bits.commit.entries(lane).rob
        assert(commitControl.io.out.bits.rename.lanes(lane).rob.asUInt ===
          identity.asUInt,
          "canonical rename release must use the retained commit identity")
        assert(commitControl.io.out.bits.robRelease.lanes(lane).rob.asUInt ===
          identity.asUInt,
          "canonical ROB release must use the retained commit identity")
        assert(commitControl.io.out.bits.brobRelease.entries(lane).asUInt ===
          identity.asUInt,
          "canonical BROB release must use the retained commit identity")
      }
    }
  }

  recovery.io.events(0) <> io.iex.recoveryEvent
  recovery.io.events(1).valid := false.B
  recovery.io.events(1).bits := 0.U.asTypeOf(recovery.io.events(1).bits)
  recovery.io.interrupts := interrupts
  recovery.io.interruptBoundaryValid := rob.io.commit.bits.headValid
  recovery.io.interruptBoundary := rob.io.commit.bits.head
  recovery.io.abort := false.B
  rob.io.recoveryCandidate := recovery.io.robCandidate
  recovery.io.robCandidateStatus := rob.io.recoveryCandidateStatus
  rob.io.recoveryPrepare.valid := recovery.io.robPrepare.valid
  rob.io.recoveryPrepare.bits := recovery.io.robPrepare.bits
  recovery.io.robPrepare.ready := rob.io.recoveryPrepare.ready
  memoryOrder.io.recoveryPrepare.valid := rob.io.recoveryPrepared.valid
  memoryOrder.io.recoveryPrepare.bits := rob.io.memoryRecoveryPrepared
  recovery.io.robPrepared.valid := rob.io.recoveryPrepared.valid &&
    memoryOrder.io.recoveryPrepareReady
  recovery.io.robPrepared.bits := rob.io.recoveryPrepared.bits
  rob.io.recoveryAbort := recovery.io.robAbort
  rob.io.recoveryApply.valid := recovery.io.targets(0).apply.valid
  rob.io.recoveryApply.bits := recovery.io.targets(0).apply.bits
  memoryOrder.io.recoveryFire := recovery.io.targets(0).apply.valid &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      recovery.io.targets(0).apply.bits, rob.io.recoveryPrepared.bits)
  when(rob.io.recoveryPrepared.valid) {
    assert(memoryOrder.io.recoveryPrepareReady,
      "ROB recovery memory snapshots must match the live OOO serial tail")
  }

  private def connectTarget(
      controller: RecoveryTargetIO,
      target: RecoveryTargetIO): Unit = {
    target.prepare.valid := controller.prepare.valid
    target.prepare.bits := controller.prepare.bits
    controller.prepare.ready := target.prepare.ready
    controller.prepared.valid := target.prepared.valid
    controller.prepared.bits := target.prepared.bits
    target.prepared.ready := controller.prepared.ready
    target.apply.valid := controller.apply.valid
    target.apply.bits := controller.apply.bits
    target.abort.valid := controller.abort.valid
    target.abort.bits := controller.abort.bits
  }

  connectTarget(recovery.io.targets(0), io.recoveryToD1)
  connectTarget(recovery.io.targets(1), renu.io.recovery)
  brob.io.recoveryPrepare.valid := recovery.io.targets(2).prepare.valid
  brob.io.recoveryPrepare.bits := recovery.io.targets(2).prepare.bits
  recovery.io.targets(2).prepare.ready := brob.io.recoveryPrepare.ready
  recovery.io.targets(2).prepared.valid := brob.io.recoveryPrepared.valid
  recovery.io.targets(2).prepared.bits := brob.io.recoveryPrepared.bits
  brob.io.recoveryApply := recovery.io.targets(2).apply
  brob.io.recoveryAbort := recovery.io.targets(2).abort
  connectTarget(recovery.io.targets(3), pcBuffer.io.recovery)
  connectTarget(recovery.io.targets(4), dispatch.io.recovery)
  connectTarget(recovery.io.targets(5), io.iex.recovery)
  connectTarget(recovery.io.targets(6), io.recoveryToIfu)
  connectTarget(recovery.io.targets(7), io.recoveryToCtu)
  connectTarget(recovery.io.targets(8), io.recoveryToLsu)

  io.trace.valid := false.B
  io.trace.bits := 0.U.asTypeOf(io.trace.bits)
}
