package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, Queue}
import linxcore.common.{BoundaryKind, InterfaceParams, RenamedUop}

object BranchValidationPoint extends ChiselEnum {
  val Dispatch, BruE1 = Value
}

object SetcValidationKind extends ChiselEnum {
  val None, Condition, Target = Value
}

/** Selects the backend owner for a completed SETC from its final B-F4 record.
  *
  * A target SETC paired with a cold `Fall` record still owns a dynamic-target
  * correction: `Fall` means B-SIDE did not identify the indirect boundary, not
  * that the computed target may be ignored. Direct and call targets are
  * validated at Dispatch, so their SETC forms must not fabricate a second BRU
  * recovery. Indirect, indirect-call, and return records remain BRU-owned.
  */
object SetcBranchValidationOwnership {
  def targetOwner(predictedKind: BoundaryKind.Type): Bool =
    predictedKind === BoundaryKind.Fall ||
      predictedKind === BoundaryKind.Ind ||
      predictedKind === BoundaryKind.ICall ||
      predictedKind === BoundaryKind.Ret

  def owns(isTarget: Bool, predictedKind: BoundaryKind.Type): Bool =
    Mux(isTarget, targetOwner(predictedKind), predictedKind === BoundaryKind.Cond)

  def actualKind(isTarget: Bool, predictedKind: BoundaryKind.Type): BoundaryKind.Type =
    Mux(
      isTarget,
      Mux(
        predictedKind === BoundaryKind.Ind ||
          predictedKind === BoundaryKind.ICall ||
          predictedKind === BoundaryKind.Ret,
        predictedKind,
        BoundaryKind.Ind),
      BoundaryKind.Cond)
}

/** One post-B-F4 validation result.
  *
  * `actualBranchPc` is the block-control PC carried by backend block context;
  * it is deliberately not inferred from the SETC instruction PC.
  */
class BackendBranchValidation(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val uop = new RenamedUop(p)
  val point = BranchValidationPoint()
  val setcKind = SetcValidationKind()
  val actualTaken = Bool()
  val actualBranchPc = UInt(p.pcWidth.W)
  val actualTarget = UInt(p.pcWidth.W)
  val actualFallthroughPc = UInt(p.pcWidth.W)
  val actualKind = BoundaryKind()
}

object BackendBranchValidationContract {
  def dispatchKind(event: BackendBranchValidation): Bool =
    event.actualKind === BoundaryKind.Fall ||
      event.actualKind === BoundaryKind.Direct ||
      event.actualKind === BoundaryKind.Call

  def bruKind(event: BackendBranchValidation): Bool =
    event.actualKind === BoundaryKind.Cond ||
      event.actualKind === BoundaryKind.Ind ||
      event.actualKind === BoundaryKind.ICall ||
      event.actualKind === BoundaryKind.Ret

  def pointMatchesKind(event: BackendBranchValidation): Bool =
    Mux(event.point === BranchValidationPoint.Dispatch, dispatchKind(event), bruKind(event))

  def mispredict(event: BackendBranchValidation): Bool = {
    val prediction = event.uop.prediction
    val comparesTarget =
      event.actualKind === BoundaryKind.Cond ||
        event.actualKind === BoundaryKind.Direct ||
        event.actualKind === BoundaryKind.Call ||
        event.actualKind === BoundaryKind.Ind ||
        event.actualKind === BoundaryKind.ICall ||
        event.actualKind === BoundaryKind.Ret
    val directionMismatch = prediction.taken =/= event.actualTaken
    val kindMismatch = prediction.kind =/= event.actualKind
    val branchPcMismatch = prediction.branchPc =/= event.actualBranchPc
    val targetMismatch = comparesTarget && prediction.target =/= event.actualTarget
    val fallthroughMismatch =
      event.actualKind === BoundaryKind.Fall &&
        prediction.fallthroughPc =/= event.actualFallthroughPc

    directionMismatch || kindMismatch || branchPcMismatch || targetMismatch || fallthroughMismatch
  }
}

class IfuBackendFeedbackBridgeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val validation = Flipped(Decoupled(new BackendBranchValidation(p)))
  val resolve = Decoupled(new BSideResolveUpdate(p))
  val backendRecovery = Decoupled(new IfuInnerFlush(p))

  val pending = Output(Bool())
  val pendingMispredict = Output(Bool())
}

/** Converts Dispatch/BRU validation into atomic B-SIDE training and recovery.
  *
  * Correct validation publishes only `resolve`. A mismatch publishes
  * `resolve(mispredict=true)` and the exact-keyed backend recovery in the same
  * cycle; neither output may advance alone under asymmetric backpressure.
  */
class IfuBackendFeedbackBridge(
    val p: InterfaceParams = InterfaceParams(),
    val entries: Int = 2)
    extends Module {
  require(entries > 0)

  val io = IO(new IfuBackendFeedbackBridgeIO(p))

  val queue = Module(new Queue(new BackendBranchValidation(p), entries, pipe = true, flow = false))
  queue.io.enq <> io.validation

  val event = queue.io.deq.bits
  val prediction = event.uop.prediction
  val dispatchKind = BackendBranchValidationContract.dispatchKind(event)
  val bruKind = BackendBranchValidationContract.bruKind(event)
  val pointMatchesKind = BackendBranchValidationContract.pointMatchesKind(event)
  val mispredict = BackendBranchValidationContract.mispredict(event)

  io.resolve.bits := 0.U.asTypeOf(io.resolve.bits)
  io.resolve.bits.peId := event.uop.peId
  io.resolve.bits.transactionId := prediction.transactionId
  io.resolve.bits.predictionTag := prediction.predictionTag
  io.resolve.bits.threadId := event.uop.threadId
  io.resolve.bits.fetchPacketUid := prediction.fetchPacketUid
  io.resolve.bits.fetchSeq := prediction.fetchSeq
  io.resolve.bits.epoch := prediction.epoch
  io.resolve.bits.checkpointId := prediction.checkpointId
  io.resolve.bits.requestPc := prediction.requestPc
  io.resolve.bits.branchPc := event.actualBranchPc
  io.resolve.bits.fallthroughPc := event.actualFallthroughPc
  io.resolve.bits.taken := event.actualTaken
  io.resolve.bits.target := event.actualTarget
  io.resolve.bits.kind := event.actualKind
  io.resolve.bits.mispredict := mispredict

  io.backendRecovery.bits := 0.U.asTypeOf(io.backendRecovery.bits)
  io.backendRecovery.bits.valid := true.B
  io.backendRecovery.bits.peId := event.uop.peId
  io.backendRecovery.bits.threadId := event.uop.threadId
  io.backendRecovery.bits.transactionId := prediction.transactionId
  io.backendRecovery.bits.fetchSeq := prediction.fetchSeq
  io.backendRecovery.bits.oldEpoch := prediction.epoch
  io.backendRecovery.bits.restartPc :=
    Mux(event.actualTaken, event.actualTarget, event.actualFallthroughPc)
  io.backendRecovery.bits.checkpointId := prediction.checkpointId
  io.backendRecovery.bits.reason := IfuInnerFlushReason.BruRecovery
  io.backendRecovery.bits.scope := IfuPruneScope.KillAllThreadState
  io.backendRecovery.bits.historyKeyValid := true.B
  io.backendRecovery.bits.predictionTag := prediction.predictionTag
  io.backendRecovery.bits.fetchPacketUid := prediction.fetchPacketUid
  io.backendRecovery.bits.ghrAction := GhrRecoveryAction.RestoreTrigger
  io.backendRecovery.bits.ghrAppendValid := event.actualKind === BoundaryKind.Cond
  io.backendRecovery.bits.ghrAppendTaken := event.actualTaken
  io.backendRecovery.bits.rasAction := RasRecoveryAction.RestoreTrigger
  io.backendRecovery.bits.rasUpdate := Mux(
    !event.actualTaken,
    RasUpdateAction.None,
    Mux(
      event.actualKind === BoundaryKind.Call || event.actualKind === BoundaryKind.ICall,
      RasUpdateAction.Push,
      Mux(event.actualKind === BoundaryKind.Ret, RasUpdateAction.Pop, RasUpdateAction.None)))
  io.backendRecovery.bits.rasPushAddress := event.actualFallthroughPc

  val pairReady = io.resolve.ready && (!mispredict || io.backendRecovery.ready)
  io.resolve.valid := queue.io.deq.valid && (!mispredict || io.backendRecovery.ready)
  io.backendRecovery.valid := queue.io.deq.valid && mispredict && io.resolve.ready
  queue.io.deq.ready := pairReady

  io.pending := queue.io.deq.valid
  io.pendingMispredict := queue.io.deq.valid && mispredict

  when(queue.io.deq.valid) {
    assert(event.uop.valid, "backend branch validation must carry a valid renamed uop")
    assert(prediction.valid, "backend branch validation requires a final prediction")
    assert(prediction.stage === BSideStage.BF4.asUInt, "backend may validate only B-F4 records")
    assert(pointMatchesKind, "branch kind reached the wrong validation owner")
    when(event.point === BranchValidationPoint.Dispatch) {
      assert(event.setcKind === SetcValidationKind.None, "Dispatch must not resolve SETC")
    }.elsewhen(event.actualKind === BoundaryKind.Cond) {
      assert(
        event.setcKind === SetcValidationKind.Condition,
        "conditional blocks must resolve with SETC condition")
    }.otherwise {
      assert(
        event.setcKind === SetcValidationKind.Target,
        "indirect, indirect-call, and return blocks must resolve with SETC target")
    }
    assert(prediction.checkpointId === event.uop.checkpointId)
    assert(prediction.fetchPacketUid === event.uop.uid.fetchPacketUid)
    when(dispatchKind && event.actualKind =/= BoundaryKind.Fall) {
      assert(event.actualTaken, "direct branch and call are unconditionally taken")
    }
    when(event.actualKind === BoundaryKind.Fall) {
      assert(!event.actualTaken, "fall-through is not taken")
    }
    when(bruKind && event.actualKind =/= BoundaryKind.Cond) {
      assert(event.actualTaken, "indirect branch, indirect call, and return are unconditionally taken")
    }
  }
}
