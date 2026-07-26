package linxcore.frontend

import chisel3._
import chisel3.util.MuxLookup
import circt.stage.ChiselStage
import linxcore.common.{BoundaryKind, InterfaceParams}

class IfuBackendFeedbackBridgeProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val inValid = Input(Bool())
  val mode = Input(UInt(2.W))
  val resolveReady = Input(Bool())
  val recoveryReady = Input(Bool())

  val inReady = Output(Bool())
  val resolveValid = Output(Bool())
  val recoveryValid = Output(Bool())
  val pending = Output(Bool())
  val mispredict = Output(Bool())
  val resolveTarget = Output(UInt(p.pcWidth.W))
  val restartPc = Output(UInt(p.pcWidth.W))
  val recoveryReason = Output(UInt(IfuInnerFlushReason.getWidth.W))
  val ghrAppendValid = Output(Bool())
  val ghrAppendTaken = Output(Bool())
  val rasUpdate = Output(UInt(RasUpdateAction.getWidth.W))
}

/** Generated-RTL shell for type-specific post-B-F4 feedback. */
class IfuBackendFeedbackBridgeProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new IfuBackendFeedbackBridgeProbeIO(p))
  val bridge = Module(new IfuBackendFeedbackBridge(p))

  val isCall = io.mode === 2.U
  val isReturn = io.mode === 3.U
  val isConditional = !isCall && !isReturn
  val kind = Mux(isCall, BoundaryKind.Call, Mux(isReturn, BoundaryKind.Ret, BoundaryKind.Cond))
  val predictedTaken = Mux(io.mode === 1.U, false.B, true.B)
  val actualTarget = MuxLookup(io.mode, 0x4000.U)(
    Seq(1.U -> 0x5000.U, 2.U -> 0x3100.U, 3.U -> 0x7100.U))

  bridge.io.validation.valid := io.inValid
  bridge.io.validation.bits := 0.U.asTypeOf(bridge.io.validation.bits)
  bridge.io.validation.bits.uop.valid := true.B
  bridge.io.validation.bits.uop.peId := 3.U
  bridge.io.validation.bits.uop.threadId := 0.U
  bridge.io.validation.bits.uop.checkpointId := 7.U
  bridge.io.validation.bits.uop.uid.fetchPacketUid := 0x66.U
  bridge.io.validation.bits.uop.prediction.valid := true.B
  bridge.io.validation.bits.uop.prediction.predictionTag := 0x44.U
  bridge.io.validation.bits.uop.prediction.transactionId := 0x55.U
  bridge.io.validation.bits.uop.prediction.fetchPacketUid := 0x66.U
  bridge.io.validation.bits.uop.prediction.fetchSeq := 0x77.U
  bridge.io.validation.bits.uop.prediction.requestPc := 0x2000.U
  bridge.io.validation.bits.uop.prediction.taken := predictedTaken
  bridge.io.validation.bits.uop.prediction.branchPc := 0x2080.U
  bridge.io.validation.bits.uop.prediction.target := Mux(isReturn, 0x7000.U, 0x3000.U)
  bridge.io.validation.bits.uop.prediction.fallthroughPc := 0x2100.U
  bridge.io.validation.bits.uop.prediction.kind := kind
  bridge.io.validation.bits.uop.prediction.provider := PredictionProvider.LongTage.asUInt
  bridge.io.validation.bits.uop.prediction.stage := BSideStage.BF4.asUInt
  bridge.io.validation.bits.uop.prediction.confidence := 3.U
  bridge.io.validation.bits.uop.prediction.checkpointId := 7.U
  bridge.io.validation.bits.uop.prediction.epoch := 2.U
  bridge.io.validation.bits.point :=
    Mux(isCall, BranchValidationPoint.Dispatch, BranchValidationPoint.BruE1)
  bridge.io.validation.bits.setcKind := Mux(
    isCall,
    SetcValidationKind.None,
    Mux(isConditional, SetcValidationKind.Condition, SetcValidationKind.Target))
  bridge.io.validation.bits.actualTaken := true.B
  bridge.io.validation.bits.actualBranchPc := 0x2080.U
  bridge.io.validation.bits.actualTarget := actualTarget
  bridge.io.validation.bits.actualFallthroughPc := 0x2100.U
  bridge.io.validation.bits.actualKind := kind

  bridge.io.resolve.ready := io.resolveReady
  bridge.io.backendRecovery.ready := io.recoveryReady

  io.inReady := bridge.io.validation.ready
  io.resolveValid := bridge.io.resolve.valid
  io.recoveryValid := bridge.io.backendRecovery.valid
  io.pending := bridge.io.pending
  io.mispredict := bridge.io.pendingMispredict
  io.resolveTarget := bridge.io.resolve.bits.target
  io.restartPc := bridge.io.backendRecovery.bits.restartPc
  io.recoveryReason := bridge.io.backendRecovery.bits.reason.asUInt
  io.ghrAppendValid := bridge.io.backendRecovery.bits.ghrAppendValid
  io.ghrAppendTaken := bridge.io.backendRecovery.bits.ghrAppendTaken
  io.rasUpdate := bridge.io.backendRecovery.bits.rasUpdate.asUInt
}

object EmitIfuBackendFeedbackBridgeProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new IfuBackendFeedbackBridgeProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
