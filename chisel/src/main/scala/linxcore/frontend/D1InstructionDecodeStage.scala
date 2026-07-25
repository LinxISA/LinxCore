package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, PopCount}
import linxcore.common.{BranchPredictionSidecar, InterfaceParams}

class D1DecodedInstructionGroup(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val validMask = UInt(p.decodeWidth.W)
  val entries = Vec(p.decodeWidth, new linxcore.common.DecodedUop(p))
  val meta = Vec(p.decodeWidth, new FrontendOpcodeMeta(p))
  val invalidOpcodeMask = UInt(p.decodeWidth.W)
  val blockBoundaryMask = UInt(p.decodeWidth.W)
  val blockStopMask = UInt(p.decodeWidth.W)
  val loadMask = UInt(p.decodeWidth.W)
  val storeMask = UInt(p.decodeWidth.W)
}

class D1InstructionDecodeStageIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val in = Flipped(Decoupled(new D1InstructionGroup(p)))
  val flush = Input(new IfuInnerFlush(p))
  val out = Decoupled(new D1DecodedInstructionGroup(p))
}

/** Production four-wide D1 full-decode boundary.
  *
  * This module consumes fixed-64-bit Instruction Buffer entries directly. It
  * never reconstructs a byte window or converts the group through F4Slot.
  */
class D1InstructionDecodeStage(val p: InterfaceParams = InterfaceParams()) extends Module {
  require(p.decodeWidth == 4, "production D1 decode is four-wide")
  require(p.insnWidth == 64, "D1 consumes fixed 64-bit instruction containers")
  require(
    BSideStage.getWidth <= BranchPredictionSidecar.StageWidth,
    "common prediction stage encoding must contain every B-SIDE stage")
  require(
    PredictionProvider.getWidth <= BranchPredictionSidecar.ProviderWidth,
    "common prediction provider encoding must contain every provider")

  val io = IO(new D1InstructionDecodeStageIO(p))

  val surviving = Wire(Vec(p.decodeWidth, Bool()))
  val invalidOpcode = Wire(Vec(p.decodeWidth, Bool()))
  val blockBoundary = Wire(Vec(p.decodeWidth, Bool()))
  val blockStop = Wire(Vec(p.decodeWidth, Bool()))
  val load = Wire(Vec(p.decodeWidth, Bool()))
  val store = Wire(Vec(p.decodeWidth, Bool()))
  for (lane <- 0 until p.decodeWidth) {
    val entry = io.in.bits.entries(lane)
    val killed = IfuFlushContract.kills(entry.identity, entry.identity.fetchPacketUid, io.flush)
    surviving(lane) := io.in.bits.validMask(lane) && !killed

    val prediction = Wire(new BranchPredictionSidecar(p))
    prediction.valid := entry.prediction.valid
    prediction.predictionTag := entry.prediction.predictionTag
    prediction.taken := entry.prediction.taken
    prediction.branchPc := entry.prediction.branchPc
    prediction.target := entry.prediction.target
    prediction.fallthroughPc := entry.prediction.fallthroughPc
    prediction.kind := entry.prediction.kind
    prediction.provider := entry.prediction.provider.asUInt
    prediction.stage := entry.prediction.stage.asUInt
    prediction.confidence := entry.prediction.confidence
    prediction.checkpointId := entry.prediction.checkpointId
    prediction.epoch := entry.prediction.epoch

    val decode = Module(new FrontendInstructionDecodeLane(p))
    decode.io.active := io.in.valid && surviving(lane)
    decode.io.peId := entry.identity.peId
    decode.io.threadId := entry.identity.threadId
    decode.io.pc := entry.pc
    decode.io.insn := entry.insn
    decode.io.lenBytes := entry.lenBytes
    decode.io.isLastInBlock := entry.isBlockStop
    decode.io.checkpointId := entry.identity.checkpointId
    decode.io.instructionUid := entry.instructionUid
    decode.io.parentUid := entry.identity.fetchPacketUid
    decode.io.fetchPacketUid := entry.identity.fetchPacketUid
    decode.io.fetchSlot := entry.identity.fetchSlot
    decode.io.prediction := prediction

    when(io.in.valid && surviving(lane)) {
      assert(entry.prediction.valid, "every surviving D1 lane must carry a prediction record")
      assert(
        entry.prediction.stage === BSideStage.BF4,
        "D1 may only consume the final B-F4 prediction")
      assert(
        entry.prediction.checkpointId === entry.identity.checkpointId,
        "D1 prediction checkpoint must match instruction identity")
      assert(
        entry.prediction.epoch === entry.identity.epoch,
        "D1 prediction epoch must match instruction identity")
    }

    io.out.bits.entries(lane) := decode.io.out
    io.out.bits.meta(lane) := decode.io.meta
    invalidOpcode(lane) := decode.io.invalidOpcode
    blockBoundary(lane) := decode.io.blockBoundary
    blockStop(lane) := decode.io.blockStop
    load(lane) := decode.io.isLoad
    store(lane) := decode.io.isStore
  }

  val survivorMask = surviving.asUInt
  io.out.valid := io.in.valid && survivorMask.orR
  io.out.bits.validMask := survivorMask
  io.out.bits.invalidOpcodeMask := invalidOpcode.asUInt
  io.out.bits.blockBoundaryMask := blockBoundary.asUInt
  io.out.bits.blockStopMask := blockStop.asUInt
  io.out.bits.loadMask := load.asUInt
  io.out.bits.storeMask := store.asUInt
  io.in.ready := Mux(survivorMask.orR, io.out.ready, true.B)

  when(io.out.valid) {
    assert(
      io.out.bits.validMask === 0.U ||
        io.out.bits.validMask === 1.U ||
        io.out.bits.validMask === 3.U ||
        io.out.bits.validMask === 7.U ||
        io.out.bits.validMask === 15.U,
      "D1 decode may only publish a dense surviving prefix")
    assert((io.out.bits.validMask & io.out.bits.invalidOpcodeMask) === io.out.bits.invalidOpcodeMask)
  }
}
