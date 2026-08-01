package linxcore.ooo

import chisel3._
import chisel3.util.{Arbiter, Decoupled, Valid}
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

  val renu = Module(new RENU(p))
  val rob = Module(new ROB(p))
  val brob = Module(new BROB(p))
  val dispatch = Module(new Dispatch(p))
  val commitControl = Module(new CommitControl(p))
  private val recoveryTargetCount = 8
  val recovery = Module(new RecoveryControl(p, recoveryTargetCount))

  renu.io.fromD2 <> io.fromD2
  io.ridTailSlot := rob.io.ridTailSlot
  io.ridTailGeneration := rob.io.ridTailGeneration

  rob.io.prepare.valid := renu.io.toD3.valid
  rob.io.prepare.bits := renu.io.toD3.bits
  brob.io.prepare.valid := renu.io.toD3.valid
  brob.io.prepare.bits := renu.io.toD3.bits
  val residencyPreviewReady = rob.io.prepare.ready && brob.io.prepare.ready
  dispatch.io.in.valid := renu.io.toD3.valid && residencyPreviewReady
  dispatch.io.in.bits := renu.io.toD3.bits
  rob.io.brobPrepared := brob.io.prepared
  brob.io.robPrepared := rob.io.prepared
  dispatch.io.robPrepared := rob.io.prepared
  dispatch.io.brobPrepared := brob.io.prepared
  val d3Ready = residencyPreviewReady && dispatch.io.in.ready
  val d3Fire = renu.io.toD3.valid && d3Ready
  renu.io.toD3.ready := d3Ready
  rob.io.publishFire := d3Fire
  brob.io.publishFire := d3Fire

  io.iex.aluDispatch <> dispatch.io.iex.aluDispatch
  io.iex.bruDispatch <> dispatch.io.iex.bruDispatch
  io.iex.aguDispatch <> dispatch.io.iex.aguDispatch
  io.iex.storeDispatch <> dispatch.io.iex.storeDispatch
  io.iex.systemDispatch <> dispatch.io.iex.systemDispatch
  io.iex.cmdDispatch <> dispatch.io.iex.cmdDispatch

  val completionArb = Module(new Arbiter(new CompletionTxn(p), p.widths.issueWidth))
  for (lane <- 0 until p.widths.issueWidth) {
    completionArb.io.in(lane) <> io.iex.completion(lane)
  }
  rob.io.completion <> completionArb.io.out

  rob.io.commit.ready := true.B
  commitControl.io.rob.valid := rob.io.commit.valid
  commitControl.io.rob.bits := rob.io.commit.bits
  val interrupts = Wire(Vec(p.ooo.stidCount, new InterruptRequest(p)))
  for (stid <- 0 until p.ooo.stidCount) {
    interrupts(stid) := io.interrupt.bits
    interrupts(stid).valid := io.interrupt.valid &&
      io.interrupt.bits.valid && io.interrupt.bits.stid === stid.U
  }
  commitControl.io.interrupts := interrupts
  commitControl.io.interruptBoundaryValid := rob.io.commit.bits.headValid
  commitControl.io.interruptBoundary := rob.io.commit.bits.head

  val releaseProbe = rob.io.commit.valid && rob.io.commit.bits.count =/= 0.U
  rob.io.release.valid := releaseProbe
  rob.io.release.bits := commitControl.io.out.bits.robRelease
  renu.io.release.valid := releaseProbe
  renu.io.release.bits := commitControl.io.out.bits.rename
  brob.io.release.valid := releaseProbe
  brob.io.release.bits := commitControl.io.out.bits.brobRelease
  commitControl.io.robReleaseReady := rob.io.releaseReady
  commitControl.io.renameReleaseReady := renu.io.releaseReady
  commitControl.io.brobReleaseReady := brob.io.releaseReady

  io.commit.valid := commitControl.io.out.valid &&
    commitControl.io.out.bits.commit.count =/= 0.U
  io.commit.bits := commitControl.io.out.bits.commit
  io.trap.valid := commitControl.io.out.valid &&
    commitControl.io.out.bits.trap.valid
  io.trap.bits := commitControl.io.out.bits.trap
  commitControl.io.out.ready :=
    (!io.commit.valid || io.commit.ready) && (!io.trap.valid || io.trap.ready)
  val commitFire = commitControl.io.out.fire
  rob.io.commitApply := commitFire
  rob.io.releaseApply := commitFire
  renu.io.releaseApply := commitFire
  brob.io.releaseApply := commitFire
  when(commitFire && commitControl.io.out.bits.commit.count =/= 0.U) {
    assert(rob.io.releaseReady && renu.io.releaseReady && brob.io.releaseReady,
      "canonical commit must atomically apply every owner release")
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
  recovery.io.robPrepared.valid := rob.io.recoveryPrepared.valid
  recovery.io.robPrepared.bits := rob.io.recoveryPrepared.bits
  rob.io.recoveryAbort := recovery.io.robAbort
  rob.io.recoveryApply.valid := recovery.io.targets(0).apply.valid
  rob.io.recoveryApply.bits := recovery.io.targets(0).apply.bits

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
  connectTarget(recovery.io.targets(3), dispatch.io.recovery)
  connectTarget(recovery.io.targets(4), io.iex.recovery)
  connectTarget(recovery.io.targets(5), io.recoveryToIfu)
  connectTarget(recovery.io.targets(6), io.recoveryToCtu)
  connectTarget(recovery.io.targets(7), io.recoveryToLsu)

  io.trace.valid := false.B
  io.trace.bits := 0.U.asTypeOf(io.trace.bits)
}
