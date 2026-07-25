package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, Valid, log2Ceil}
import linxcore.common.InterfaceParams

class IfuRedirectArbiterIO(
    val p: InterfaceParams = InterfaceParams(),
    val threadCount: Int = 1)
    extends Bundle {
  val epochSeed = Flipped(Valid(new IfuEpochSeed(p)))
  val backend = Flipped(Decoupled(new IfuInnerFlush(p)))
  val itlb = Flipped(Decoupled(new IfuInnerFlush(p)))
  val prediction = Flipped(Decoupled(new IfuInnerFlush(p)))
  val out = Decoupled(new IfuInnerFlush(p))

  val epochs = Output(Vec(threadCount, UInt(p.blockEpochWidth.W)))
  val acceptedBackend = Output(Bool())
  val acceptedItlb = Output(Bool())
  val acceptedPrediction = Output(Bool())
}

class IfuRedirectArbiter(
    val p: InterfaceParams = InterfaceParams(),
    val threadCount: Int = 1)
    extends Module {
  require(threadCount > 0 && (threadCount & (threadCount - 1)) == 0)
  require(threadCount <= (1 << p.threadIdWidth))

  private val threadIndexWidth = math.max(1, log2Ceil(threadCount))

  val io = IO(new IfuRedirectArbiterIO(p, threadCount))

  val epochs = RegInit(VecInit(Seq.fill(threadCount)(0.U(p.blockEpochWidth.W))))
  val pendingValid = RegInit(false.B)
  val pending = RegInit(0.U.asTypeOf(new IfuInnerFlush(p)))

  def threadIndex(threadId: UInt): UInt =
    if (threadCount == 1) 0.U(threadIndexWidth.W) else threadId(threadIndexWidth - 1, 0)

  val canAccept = !pendingValid
  val backendSelected = canAccept && io.backend.valid
  val itlbSelected = canAccept && !io.backend.valid && io.itlb.valid
  val predictionSelected =
    canAccept && !io.backend.valid && !io.itlb.valid && io.prediction.valid
  val epochSeedSelected =
    canAccept &&
      !io.backend.valid &&
      !io.itlb.valid &&
      !io.prediction.valid &&
      io.epochSeed.valid

  io.backend.ready := backendSelected
  io.itlb.ready := itlbSelected
  io.prediction.ready := predictionSelected

  val selected = Wire(new IfuInnerFlush(p))
  selected := 0.U.asTypeOf(selected)
  when(backendSelected) {
    selected := io.backend.bits
  }.elsewhen(itlbSelected) {
    selected := io.itlb.bits
  }.elsewhen(predictionSelected) {
    selected := io.prediction.bits
  }

  val selectedFire =
    io.backend.fire ||
      io.itlb.fire ||
      io.prediction.fire
  val selectedThreadSupported = selected.threadId < threadCount.U
  val selectedThread = threadIndex(selected.threadId)
  val nextEpoch = epochs(selectedThread) + 1.U

  io.out.valid := pendingValid
  io.out.bits := pending
  io.epochs := epochs
  io.acceptedBackend := io.backend.fire
  io.acceptedItlb := io.itlb.fire
  io.acceptedPrediction := io.prediction.fire

  when(io.out.fire) {
    pendingValid := false.B
  }

  when(selectedFire) {
    assert(selected.valid, "accepted redirect proposal must carry valid intent")
    assert(selectedThreadSupported, "accepted redirect proposal must target a supported STID")
    pendingValid := true.B
    pending := selected
    pending.valid := true.B
    pending.newEpoch := nextEpoch
    epochs(selectedThread) := nextEpoch
  }.elsewhen(epochSeedSelected && io.epochSeed.bits.threadId < threadCount.U) {
    epochs(threadIndex(io.epochSeed.bits.threadId)) := io.epochSeed.bits.epoch
  }
}
