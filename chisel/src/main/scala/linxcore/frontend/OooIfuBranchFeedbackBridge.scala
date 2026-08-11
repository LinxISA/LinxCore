package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, Queue}
import linxcore.common.BoundaryKind
import linxcore.params.CoreParams
import linxcore.top.interface._

class OooIfuBranchFeedbackBridgeIO(val p: CoreParams) extends Bundle {
  val validation = Flipped(Decoupled(new BranchValidationTxn(p)))
  val resolve = Decoupled(new BranchResolveTxn(p))
  val recovery = Decoupled(new RecoveryEvent(p))
}

/** Retains one exact BRU validation until B-SIDE training and any required
  * precise recovery are accepted atomically.
  */
class OooIfuBranchFeedbackBridge(
    val p: CoreParams,
    val entries: Int = 2) extends Module {
  require(entries > 0)
  val io = IO(new OooIfuBranchFeedbackBridgeIO(p))

  private val queue = Module(new Queue(new BranchValidationTxn(p), entries,
    pipe = true, flow = false))
  queue.io.enq <> io.validation
  private val event = queue.io.deq.bits
  private val comparesTarget =
    event.actualKind === BoundaryKind.Direct ||
      event.actualKind === BoundaryKind.Call ||
      event.actualKind === BoundaryKind.Ind ||
      event.actualKind === BoundaryKind.ICall ||
      event.actualKind === BoundaryKind.Ret
  private val comparesFallthrough = event.actualKind === BoundaryKind.Fall
  private val mispredict =
    event.predictedTaken =/= event.actualTaken ||
      event.predictedKind =/= event.actualKind ||
      event.predictedBranchPc =/= event.actualBranchPc ||
      (comparesTarget && event.predictedTarget =/= event.actualTarget) ||
      (comparesFallthrough &&
        event.predictedFallthroughPc =/= event.actualFallthroughPc)

  io.resolve.bits := 0.U.asTypeOf(io.resolve.bits)
  io.resolve.bits.peId := event.instruction.peId
  io.resolve.bits.transactionId := event.predictionTransactionId
  io.resolve.bits.predictionTag := event.predictionTag
  io.resolve.bits.stid := event.instruction.stid
  io.resolve.bits.fetchPacketUid := event.fetchPacketUid
  io.resolve.bits.fetchSeq := event.fetchSeq
  io.resolve.bits.epoch := event.predictionEpoch
  io.resolve.bits.checkpointId := event.checkpointId
  io.resolve.bits.requestPc := event.requestPc
  io.resolve.bits.branchPc := event.actualBranchPc
  io.resolve.bits.fallthroughPc := event.actualFallthroughPc
  io.resolve.bits.taken := event.actualTaken
  io.resolve.bits.target := event.actualTarget
  io.resolve.bits.kind := event.actualKind
  io.resolve.bits.mispredict := mispredict

  io.recovery.bits := 0.U.asTypeOf(io.recovery.bits)
  io.recovery.bits.transactionId := event.executionTransactionId
  io.recovery.bits.cause := RecoveryCause.Branch
  io.recovery.bits.trigger := event.rob
  io.recovery.bits.instruction := event.instruction
  io.recovery.bits.redirectPc := Mux(
    event.actualTaken, event.actualTarget, event.actualFallthroughPc)

  private val pairReady = io.resolve.ready &&
    (!mispredict || io.recovery.ready)
  io.resolve.valid := queue.io.deq.valid &&
    (!mispredict || io.recovery.ready)
  io.recovery.valid := queue.io.deq.valid && mispredict && io.resolve.ready
  queue.io.deq.ready := pairReady

  when(queue.io.deq.valid) {
    assert(event.predictionValid,
      "backend branch validation requires a final prediction")
    when(event.kind === BranchValidationKind.Condition) {
      assert(event.actualKind === BoundaryKind.Cond,
        "condition validation must resolve a conditional boundary")
    }.otherwise {
      assert(event.actualKind === BoundaryKind.Ind ||
        event.actualKind === BoundaryKind.ICall ||
        event.actualKind === BoundaryKind.Ret,
        "target validation must resolve an indirect/call/return boundary")
      assert(event.actualTaken,
        "indirect/call/return target validation is unconditionally taken")
    }
  }
}
