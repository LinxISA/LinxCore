package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.{BoundaryKind, InterfaceParams}

class D1InstructionDecodeProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val inValid = Input(Bool())
  val outReady = Input(Bool())
  val flushValid = Input(Bool())

  val inReady = Output(Bool())
  val outValid = Output(Bool())
  val validMask = Output(UInt(p.decodeWidth.W))
  val instructionUid = Output(Vec(p.decodeWidth, UInt(p.uopUidWidth.W)))
  val predictionTag = Output(Vec(p.decodeWidth, UInt(p.uopUidWidth.W)))
  val predictionTarget = Output(Vec(p.decodeWidth, UInt(p.pcWidth.W)))
  val predictionFinal = Output(Vec(p.decodeWidth, Bool()))
  val decodedValid = Output(Vec(p.decodeWidth, Bool()))
}

/** Small generated-RTL shell for the production D1 group decoder. */
class D1InstructionDecodeProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new D1InstructionDecodeProbeIO(p))
  val decode = Module(new D1InstructionDecodeStage(p))

  decode.io.in.valid := io.inValid
  decode.io.in.bits := 0.U.asTypeOf(decode.io.in.bits)
  decode.io.in.bits.validMask := "b1111".U
  for (lane <- 0 until p.decodeWidth) {
    val entry = decode.io.in.bits.entries(lane)
    entry.pc := (0x2000 + lane * 2).U
    entry.instructionUid := (0x900 + lane).U
    entry.insn := (0x0008 | (lane + 1) << 6 | (lane + 2) << 11).U
    entry.lenBytes := 2.U
    entry.identity.peId := 3.U
    entry.identity.threadId := 0.U
    entry.identity.fetchPacketUid := (100 + lane).U
    entry.identity.fetchSeq := (10 + lane).U
    entry.identity.fetchSlot := lane.U
    entry.identity.checkpointId := (7 + lane).U
    entry.identity.epoch := 2.U
    entry.prediction.valid := true.B
    entry.prediction.predictionTag := (0x400 + lane).U
    entry.prediction.taken := (lane % 2 == 1).B
    entry.prediction.branchPc := (0x2080 + lane * 4).U
    entry.prediction.target := (0x3000 + lane * 8).U
    entry.prediction.fallthroughPc := (0x2082 + lane * 4).U
    entry.prediction.kind := BoundaryKind.Cond
    entry.prediction.provider := PredictionProvider.LongTage
    entry.prediction.stage := BSideStage.BF4
    entry.prediction.confidence := 3.U
    entry.prediction.checkpointId := (7 + lane).U
    entry.prediction.epoch := 2.U
  }

  decode.io.flush := 0.U.asTypeOf(decode.io.flush)
  decode.io.flush.valid := io.flushValid
  decode.io.flush.threadId := 0.U
  decode.io.flush.transactionId := 102.U
  decode.io.flush.fetchSeq := 12.U
  decode.io.flush.oldEpoch := 2.U
  decode.io.flush.scope := IfuPruneScope.KillTriggerAndYounger
  decode.io.out.ready := io.outReady

  io.inReady := decode.io.in.ready
  io.outValid := decode.io.out.valid
  io.validMask := decode.io.out.bits.validMask
  for (lane <- 0 until p.decodeWidth) {
    io.instructionUid(lane) := decode.io.out.bits.entries(lane).uid.uid
    io.predictionTag(lane) := decode.io.out.bits.entries(lane).prediction.predictionTag
    io.predictionTarget(lane) := decode.io.out.bits.entries(lane).prediction.target
    io.predictionFinal(lane) :=
      decode.io.out.bits.entries(lane).prediction.stage === BSideStage.BF4.asUInt
    io.decodedValid(lane) := decode.io.out.bits.entries(lane).valid
  }
}

object EmitD1InstructionDecodeProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new D1InstructionDecodeProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
