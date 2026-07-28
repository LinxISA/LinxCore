package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}
import linxcore.common.InterfaceParams
import linxcore.frontend.{
  IfuInnerFlush,
  IfuInnerFlushReason,
  IfuPruneScope
}

object OooFrontendRecoveryState extends ChiselEnum {
  val Idle, RequestO3, WaitApply, WaitTerminal = Value
}

object OooFrontendRecoveryRejectReason extends ChiselEnum {
  val MalformedCommand, O3Aborted = Value
}

/** One recovery command joins the exact OOO suffix authority with the IFU
  * restart proposal that describes the same architectural event.
  */
class OooFrontendRecoveryCommand(
    val ifuP: InterfaceParams = InterfaceParams(),
    val oooP: OooParams = OooParams())
    extends Bundle {
  val ooo = new OooGlobalRecoveryRequest(oooP)
  val redirect = new IfuInnerFlush(ifuP)
}

class OooFrontendRecoveryCompletion(
    val ifuP: InterfaceParams = InterfaceParams(),
    val oooP: OooParams = OooParams())
    extends Bundle {
  val command = new OooFrontendRecoveryCommand(ifuP, oooP)
  val acceptedEpoch = UInt(ifuP.blockEpochWidth.W)
}

class OooFrontendRecoveryReject(
    val ifuP: InterfaceParams = InterfaceParams(),
    val oooP: OooParams = OooParams())
    extends Bundle {
  val command = new OooFrontendRecoveryCommand(ifuP, oooP)
  val reason = OooFrontendRecoveryRejectReason()
}

object OooFrontendRecoveryContract {
  /** IFU allocates `newEpoch`; every other field must echo the proposal. */
  def sameRedirectProposal(left: IfuInnerFlush, right: IfuInnerFlush): Bool =
    left.valid === right.valid &&
      left.peId === right.peId &&
      left.threadId === right.threadId &&
      left.transactionId === right.transactionId &&
      left.fetchSeq === right.fetchSeq &&
      left.oldEpoch === right.oldEpoch &&
      left.restartPc === right.restartPc &&
      left.checkpointId === right.checkpointId &&
      left.reason === right.reason &&
      left.scope === right.scope &&
      left.terminalSteer === right.terminalSteer &&
      left.terminalTaken === right.terminalTaken &&
      left.boundaryPc === right.boundaryPc &&
      left.boundaryFallthroughPc === right.boundaryFallthroughPc &&
      left.historyKeyValid === right.historyKeyValid &&
      left.predictionTag === right.predictionTag &&
      left.fetchPacketUid === right.fetchPacketUid &&
      left.ghrAction === right.ghrAction &&
      left.ghrAppendValid === right.ghrAppendValid &&
      left.ghrAppendTaken === right.ghrAppendTaken &&
      left.rasAction === right.rasAction &&
      left.rasUpdate === right.rasUpdate &&
      left.rasPushAddress === right.rasPushAddress
}

class OooFrontendRecoveryBridgeIO(
    val ifuP: InterfaceParams = InterfaceParams(),
    val oooP: OooParams = OooParams())
    extends Bundle {
  val in = Flipped(Decoupled(new OooFrontendRecoveryCommand(ifuP, oooP)))

  val o3Request = Decoupled(new OooGlobalRecoveryRequest(oooP))
  val o3Applied = Flipped(Valid(new OooGlobalRecoveryRequest(oooP)))
  val o3Completed = Flipped(Valid(new OooGlobalRecoveryRequest(oooP)))
  val o3Aborted = Flipped(Valid(new OooGlobalRecoveryRequest(oooP)))

  val ifuRedirect = Decoupled(new IfuInnerFlush(ifuP))
  val canonicalFlush = Flipped(Valid(new IfuInnerFlush(ifuP)))

  /** Fence is non-mutating; stageCancel fires only after O3 common apply. */
  val fence = Output(Vec(oooP.stidCount, Bool()))
  val stageCancel = Output(Vec(oooP.stidCount, Bool()))
  val busy = Output(Bool())
  val complete = Valid(new OooFrontendRecoveryCompletion(ifuP, oooP))
  val rejected = Valid(new OooFrontendRecoveryReject(ifuP, oooP))
}

/** R4 join between the OOO physical recovery transaction and canonical IFU
  * restart publication.
  *
  * Capture immediately fences the selected STID without mutating retained
  * frontend or D2/S1 state.  Only an exact O3 apply emits `stageCancel` and
  * releases the retained IFU redirect.  Completion waits for both exact O3
  * rebuild completion and the canonical IFU echo, in either order.
  */
class OooFrontendRecoveryBridge(
    val ifuP: InterfaceParams = InterfaceParams(),
    val oooP: OooParams = OooParams())
    extends Module {
  require(oooP.stidCount <= (1 << ifuP.threadIdWidth))
  require(oooP.peIdWidth == ifuP.peIdWidth)
  require(oooP.stidWidth <= ifuP.threadIdWidth)
  require(oooP.epochWidth == ifuP.blockEpochWidth)

  val io = IO(new OooFrontendRecoveryBridgeIO(ifuP, oooP))

  val state = RegInit(OooFrontendRecoveryState.Idle)
  val command = RegInit(0.U.asTypeOf(new OooFrontendRecoveryCommand(ifuP, oooP)))
  val redirectSent = RegInit(false.B)
  val o3CompletedSeen = RegInit(false.B)
  val canonicalSeen = RegInit(false.B)
  val canonicalEpoch = RegInit(0.U(ifuP.blockEpochWidth.W))

  private def commandWellFormed(candidate: OooFrontendRecoveryCommand): Bool = {
    val key = candidate.ooo.rename.key
    val redirect = candidate.redirect
    val stidInRange = key.member.group.stid < oooP.stidCount.U
    val expectedScope = Mux(
      candidate.ooo.rename.killTrigger,
      IfuPruneScope.KillTriggerAndYounger,
      IfuPruneScope.PreserveTriggerKillYounger)
    val expectedReason = Mux(
      key.cause === OooRecoveryCause.Branch,
      IfuInnerFlushReason.BruRecovery,
      IfuInnerFlushReason.OooRecovery)

    key.member.group.valid && key.member.bid.valid &&
      stidInRange && candidate.ooo.triggerMemberCount.orR && redirect.valid &&
      redirect.peId === key.member.group.peId &&
      redirect.threadId === key.member.group.stid &&
      redirect.oldEpoch === key.epoch &&
      redirect.scope === expectedScope && redirect.reason === expectedReason
  }

  val idle = state === OooFrontendRecoveryState.Idle
  val offeredCommandWellFormed = commandWellFormed(io.in.bits)
  val acceptedMalformed = io.in.fire && !offeredCommandWellFormed
  val acceptedCommand = io.in.fire && offeredCommandWellFormed
  val activeStid = command.ooo.rename.key.member.group.stid

  io.in.ready := idle
  io.o3Request.valid := state === OooFrontendRecoveryState.RequestO3
  io.o3Request.bits := command.ooo

  val exactO3Applied = state === OooFrontendRecoveryState.WaitApply &&
    io.o3Applied.valid && io.o3Applied.bits.asUInt === command.ooo.asUInt
  val exactO3Completed = state === OooFrontendRecoveryState.WaitTerminal &&
    io.o3Completed.valid && io.o3Completed.bits.asUInt === command.ooo.asUInt
  val exactO3Aborted = state === OooFrontendRecoveryState.WaitApply &&
    io.o3Aborted.valid && io.o3Aborted.bits.asUInt === command.ooo.asUInt

  io.ifuRedirect.valid :=
    state === OooFrontendRecoveryState.WaitTerminal && !redirectSent
  io.ifuRedirect.bits := command.redirect
  val ifuRedirectFire = io.ifuRedirect.fire
  val exactCanonicalFlush = state === OooFrontendRecoveryState.WaitTerminal &&
    (redirectSent || ifuRedirectFire) && io.canonicalFlush.valid &&
    OooFrontendRecoveryContract.sameRedirectProposal(
      io.canonicalFlush.bits,
      command.redirect)

  val terminalO3Complete = o3CompletedSeen || exactO3Completed
  val terminalCanonical = canonicalSeen || exactCanonicalFlush
  val terminalComplete = state === OooFrontendRecoveryState.WaitTerminal &&
    terminalO3Complete && terminalCanonical

  io.fence.foreach(_ := false.B)
  io.stageCancel.foreach(_ := false.B)
  for (stid <- 0 until oooP.stidCount) {
    io.fence(stid) :=
      (state =/= OooFrontendRecoveryState.Idle && activeStid === stid.U) ||
        (idle && io.in.valid && offeredCommandWellFormed &&
          io.in.bits.ooo.rename.key.member.group.stid === stid.U)
    io.stageCancel(stid) := exactO3Applied && activeStid === stid.U
  }

  io.busy := state =/= OooFrontendRecoveryState.Idle
  io.complete.valid := terminalComplete
  io.complete.bits.command := command
  io.complete.bits.acceptedEpoch := Mux(
    exactCanonicalFlush,
    io.canonicalFlush.bits.newEpoch,
    canonicalEpoch)

  io.rejected.valid := acceptedMalformed || exactO3Aborted
  io.rejected.bits.command := Mux(acceptedMalformed, io.in.bits, command)
  io.rejected.bits.reason := Mux(
    acceptedMalformed,
    OooFrontendRecoveryRejectReason.MalformedCommand,
    OooFrontendRecoveryRejectReason.O3Aborted)

  when(acceptedCommand) {
    command := io.in.bits
    redirectSent := false.B
    o3CompletedSeen := false.B
    canonicalSeen := false.B
    canonicalEpoch := 0.U
    state := OooFrontendRecoveryState.RequestO3
  }

  when(io.o3Request.fire) {
    state := OooFrontendRecoveryState.WaitApply
  }

  when(exactO3Applied) {
    state := OooFrontendRecoveryState.WaitTerminal
  }.elsewhen(exactO3Aborted) {
    state := OooFrontendRecoveryState.Idle
  }

  when(ifuRedirectFire) {
    redirectSent := true.B
  }
  when(exactO3Completed) {
    o3CompletedSeen := true.B
  }
  when(exactCanonicalFlush) {
    canonicalSeen := true.B
    canonicalEpoch := io.canonicalFlush.bits.newEpoch
  }
  when(terminalComplete) {
    state := OooFrontendRecoveryState.Idle
    redirectSent := false.B
    o3CompletedSeen := false.B
    canonicalSeen := false.B
  }

  when(io.o3Applied.valid && state === OooFrontendRecoveryState.WaitApply) {
    assert(io.o3Applied.bits.asUInt === command.ooo.asUInt,
      "active frontend recovery must observe only its exact O3 apply")
  }
  when(io.o3Completed.valid && state === OooFrontendRecoveryState.WaitApply) {
    assert(false.B, "O3 recovery cannot complete before common apply")
  }
  when(io.o3Aborted.valid && state === OooFrontendRecoveryState.WaitTerminal) {
    assert(false.B, "an applied O3 recovery cannot subsequently abort")
  }
}
