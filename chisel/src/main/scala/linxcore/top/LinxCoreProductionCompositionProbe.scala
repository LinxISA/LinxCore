package linxcore.top

import chisel3._
import chisel3.util.Cat
import circt.stage.ChiselStage
import linxcore.common.{BoundaryKind, DecodedUop, InterfaceParams}
import linxcore.frontend._

class LinxCoreProductionCompositionProbeIO(val p: InterfaceParams = InterfaceParams())
    extends Bundle {
  val itlbRefillValid = Input(Bool())
  val startValid = Input(Bool())
  val startPc = Input(UInt(p.pcWidth.W))
  val boundaryMode = Input(Bool())
  val memoryRequestReady = Input(Bool())
  val memoryResponseValid = Input(Bool())
  val memoryResponseTag = Input(UInt(p.uopUidWidth.W))
  val memoryResponseLinePa = Input(UInt(p.pcWidth.W))
  val decodedReady = Input(Bool())
  val validateMispredict = Input(Bool())
  val correctedTarget = Input(UInt(p.pcWidth.W))

  val memoryRequestValid = Output(Bool())
  val memoryRequestTag = Output(UInt(p.uopUidWidth.W))
  val memoryRequestLinePa = Output(UInt(p.pcWidth.W))
  val memoryResponseReady = Output(Bool())
  val decodedValid = Output(Bool())
  val decodedValidMask = Output(UInt(p.decodeWidth.W))
  val decodedPc0 = Output(UInt(p.pcWidth.W))
  val decodedPc3 = Output(UInt(p.pcWidth.W))
  val decodedInsn0 = Output(UInt(p.insnWidth.W))
  val decodedPredictionFinal = Output(Bool())
  val decodedPredictionKind = Output(UInt(BoundaryKind.getWidth.W))
  val decodedPredictionTarget = Output(UInt(p.pcWidth.W))
  val validationReady = Output(Bool())
  val canonicalFlushValid = Output(Bool())
  val canonicalFlushReason = Output(UInt(IfuInnerFlushReason.getWidth.W))
  val canonicalRestartPc = Output(UInt(p.pcWidth.W))
  val canonicalNewEpoch = Output(UInt(p.blockEpochWidth.W))
  val feedbackPending = Output(Bool())
  val staleMemoryResponse = Output(Bool())
}

/** Generated-RTL shell for the complete promoted IFU composition. */
class LinxCoreProductionCompositionProbe(val p: InterfaceParams = InterfaceParams())
    extends Module {
  val io = IO(new LinxCoreProductionCompositionProbeIO(p))
  val composition = Module(
    new LinxCoreProductionComposition(
      p,
      threadCount = 1,
      lineBytes = 64,
      pageBytes = 4096,
      itlbEntries = 4,
      l1iSets = 4,
      missEntries = 4,
      joinEntries = 4,
      maxGroupsPerTransaction = 8,
      instructionBufferDepth = 16,
      lineBridgeEntries = 4,
      feedbackEntries = 2))

  composition.io.start.valid := io.startValid
  composition.io.start.bits.peId := 1.U
  composition.io.start.bits.threadId := 0.U
  composition.io.start.bits.pc := io.startPc
  composition.io.ptwRequest.ready := true.B
  composition.io.ptwRefill.valid := io.itlbRefillValid
  composition.io.ptwRefill.bits := 0.U.asTypeOf(composition.io.ptwRefill.bits)
  composition.io.ptwRefill.bits.vpn := 1.U
  composition.io.ptwRefill.bits.ppn := 2.U
  composition.io.ptwRefill.bits.executable := true.B
  composition.io.fetchFault.ready := true.B
  composition.io.invalidateItlb := false.B
  composition.io.invalidateL1I := false.B
  composition.io.d1ThreadId := 0.U

  val denseLine = Cat(Seq.fill(32)(0x1048.U(16.W)))
  val boundaryLine =
    Cat(Seq.fill(29)(0x1048.U(16.W)) ++ Seq(0.U(16.W), 0x1048.U(16.W), 0x0002.U(16.W)))
  composition.io.memoryRequest.ready := io.memoryRequestReady
  composition.io.memoryResponse.valid := io.memoryResponseValid
  composition.io.memoryResponse.bits.tag := io.memoryResponseTag
  composition.io.memoryResponse.bits.linePa := io.memoryResponseLinePa
  composition.io.memoryResponse.bits.lineData := Mux(io.boundaryMode, boundaryLine, denseLine)
  composition.io.decoded.ready := io.decodedReady

  val capturedValid = RegInit(false.B)
  val captured = RegInit(0.U.asTypeOf(new DecodedUop(p)))
  when(composition.io.decoded.fire) {
    capturedValid := true.B
    captured := composition.io.decoded.bits.entries(0)
  }

  composition.io.backendValidation.valid := io.validateMispredict && capturedValid
  composition.io.backendValidation.bits := 0.U.asTypeOf(composition.io.backendValidation.bits)
  val validation = composition.io.backendValidation.bits
  validation.uop.valid := captured.valid
  validation.uop.peId := captured.peId
  validation.uop.threadId := captured.threadId
  validation.uop.checkpointId := captured.checkpointId
  validation.uop.uid.fetchPacketUid := captured.uid.fetchPacketUid
  validation.uop.prediction := captured.prediction
  validation.point := BranchValidationPoint.Dispatch
  validation.setcKind := SetcValidationKind.None
  validation.actualTaken := true.B
  validation.actualBranchPc := captured.prediction.branchPc
  validation.actualTarget := io.correctedTarget
  validation.actualFallthroughPc := captured.prediction.fallthroughPc
  validation.actualKind := BoundaryKind.Direct
  when(composition.io.backendValidation.fire) {
    capturedValid := false.B
  }

  io.memoryRequestValid := composition.io.memoryRequest.valid
  io.memoryRequestTag := composition.io.memoryRequest.bits.tag
  io.memoryRequestLinePa := composition.io.memoryRequest.bits.linePa
  io.memoryResponseReady := composition.io.memoryResponse.ready
  io.decodedValid := composition.io.decoded.valid
  io.decodedValidMask := composition.io.decoded.bits.validMask
  io.decodedPc0 := composition.io.decoded.bits.entries(0).pc
  io.decodedPc3 := composition.io.decoded.bits.entries(3).pc
  io.decodedInsn0 := composition.io.decoded.bits.entries(0).insnRaw
  io.decodedPredictionFinal :=
    composition.io.decoded.bits.entries(0).prediction.stage === BSideStage.BF4.asUInt
  io.decodedPredictionKind := composition.io.decoded.bits.entries(0).prediction.kind.asUInt
  io.decodedPredictionTarget := composition.io.decoded.bits.entries(0).prediction.target
  io.validationReady := composition.io.backendValidation.ready && capturedValid
  io.canonicalFlushValid := composition.io.canonicalFlush.valid
  io.canonicalFlushReason := composition.io.canonicalFlush.bits.reason.asUInt
  io.canonicalRestartPc := composition.io.canonicalFlush.bits.restartPc
  io.canonicalNewEpoch := composition.io.canonicalFlush.bits.newEpoch
  io.feedbackPending := composition.io.feedbackPending
  io.staleMemoryResponse := composition.io.staleMemoryResponse
}

object EmitLinxCoreProductionCompositionProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new LinxCoreProductionCompositionProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
