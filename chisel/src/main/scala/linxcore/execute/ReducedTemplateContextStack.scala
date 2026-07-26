package linxcore.execute

import chisel3._
import chisel3.util.log2Ceil

class ReducedTemplateContextStackIO(
    val archRegs: Int,
    val physRegWidth: Int,
    val dataWidth: Int,
    val frameDepth: Int)
    extends Bundle {
  val flush = Input(Bool())
  val cancel = Input(Bool())

  val captureStart = Input(Bool())
  val captureStartArch = Input(UInt(log2Ceil(archRegs).W))
  val captureEndArch = Input(UInt(log2Ceil(archRegs).W))
  val captureMap = Input(Vec(archRegs, UInt(physRegWidth.W)))
  val captureReadValid = Output(Bool())
  val captureReadTag = Output(UInt(physRegWidth.W))
  val captureReadReady = Input(Bool())
  val captureReadData = Input(UInt(dataWidth.W))

  val restoreStart = Input(Bool())
  val restoreMap = Input(Vec(archRegs, UInt(physRegWidth.W)))
  val restoreWriteValid = Output(Bool())
  val restoreWriteTag = Output(UInt(physRegWidth.W))
  val restoreWriteData = Output(UInt(dataWidth.W))
  val restoreWriteReady = Input(Bool())

  val captureBusy = Output(Bool())
  val restoreBusy = Output(Bool())
  val restoreDone = Output(Bool())
  val frameCount = Output(UInt(log2Ceil(frameDepth + 1).W))
  val overflow = Output(Bool())
  val underflow = Output(Bool())
}

/**
  * Serialized register-context sidecar for the reduced template path.
  *
  * The architectural register ring follows LinxCoreModel: 2..23, wrapping
  * from 23 back to 2.  FENTRY captures the physical-map snapshot in ring
  * order; a returning FRET writes the saved values into its own rename-time
  * physical-map snapshot before the parent macro is allowed to execute.
  *
  * This sidecar is deliberately single-ported.  It makes the hardware timing
  * and ownership explicit while the production D3 path grows the equivalent
  * STORE/LOAD children and routes them through the LSU.
  */
class ReducedTemplateContextStack(
    val archRegs: Int = 24,
    val physRegWidth: Int = 6,
    val dataWidth: Int = 64,
    val frameDepth: Int = 32,
    val maxSavedRegs: Int = 22)
    extends Module {
  require(archRegs == 24, "Linx scalar template register ring is r2..r23")
  require(frameDepth > 1, "template context stack must hold nested frames")
  require(maxSavedRegs == 22, "template register ring contains 22 registers")

  private val archWidth = log2Ceil(archRegs)
  private val frameWidth = log2Ceil(frameDepth)
  private val countWidth = log2Ceil(frameDepth + 1)
  private val ordinalWidth = log2Ceil(maxSavedRegs)

  val io = IO(new ReducedTemplateContextStackIO(archRegs, physRegWidth, dataWidth, frameDepth))

  private def nextRingArch(arch: UInt): UInt =
    Mux(arch === 23.U, 2.U(archWidth.W), arch + 1.U)

  val contexts = Reg(Vec(frameDepth, Vec(maxSavedRegs, UInt(dataWidth.W))))
  val frameStartArch = Reg(Vec(frameDepth, UInt(archWidth.W)))
  val frameSavedCount = Reg(Vec(frameDepth, UInt((ordinalWidth + 1).W)))
  val frameCountReg = RegInit(0.U(countWidth.W))

  val captureBusyReg = RegInit(false.B)
  val captureFrame = Reg(UInt(frameWidth.W))
  val captureArch = Reg(UInt(archWidth.W))
  val captureEnd = Reg(UInt(archWidth.W))
  val captureOrdinal = Reg(UInt(ordinalWidth.W))
  val captureMapReg = Reg(Vec(archRegs, UInt(physRegWidth.W)))

  val restoreBusyReg = RegInit(false.B)
  val restoreFrame = Reg(UInt(frameWidth.W))
  val restoreArch = Reg(UInt(archWidth.W))
  val restoreOrdinal = Reg(UInt(ordinalWidth.W))
  val restoreCount = Reg(UInt((ordinalWidth + 1).W))
  val restoreMapReg = Reg(Vec(archRegs, UInt(physRegWidth.W)))
  val restoreDoneReg = RegInit(false.B)
  val overflowReg = RegInit(false.B)
  val underflowReg = RegInit(false.B)

  val captureAccepted =
    io.captureStart && !captureBusyReg && !restoreBusyReg && frameCountReg =/= frameDepth.U
  val restoreAccepted =
    io.restoreStart && !captureBusyReg && !restoreBusyReg && frameCountReg =/= 0.U

  io.captureReadValid := captureBusyReg
  io.captureReadTag := Mux(captureBusyReg, captureMapReg(captureArch), 0.U)
  val captureFire = io.captureReadValid && io.captureReadReady
  val captureLast = captureArch === captureEnd || captureOrdinal === (maxSavedRegs - 1).U

  io.restoreWriteValid := restoreBusyReg
  io.restoreWriteTag := Mux(restoreBusyReg, restoreMapReg(restoreArch), 0.U)
  io.restoreWriteData := Mux(restoreBusyReg, contexts(restoreFrame)(restoreOrdinal), 0.U)
  val restoreFire = io.restoreWriteValid && io.restoreWriteReady
  val restoreLast = restoreOrdinal + 1.U === restoreCount

  when(io.flush) {
    frameCountReg := 0.U
    captureBusyReg := false.B
    restoreBusyReg := false.B
    restoreDoneReg := false.B
    overflowReg := false.B
    underflowReg := false.B
  }.elsewhen(io.cancel) {
    // A suffix recovery may kill an in-flight FENTRY/FRET without changing
    // the committed template stack. Capture publishes a frame only on its
    // final read and restore pops a frame only on its final write, so aborting
    // the serialized transaction here preserves the previous frameCount.
    captureBusyReg := false.B
    restoreBusyReg := false.B
    restoreDoneReg := false.B
  }.otherwise {
    restoreDoneReg := false.B

    when(io.captureStart && frameCountReg === frameDepth.U) {
      overflowReg := true.B
    }
    when(io.restoreStart && frameCountReg === 0.U) {
      underflowReg := true.B
    }

    when(captureAccepted) {
      captureBusyReg := true.B
      captureFrame := frameCountReg(frameWidth - 1, 0)
      captureArch := io.captureStartArch
      captureEnd := io.captureEndArch
      captureOrdinal := 0.U
      captureMapReg := io.captureMap
      frameStartArch(frameCountReg(frameWidth - 1, 0)) := io.captureStartArch
    }.elsewhen(captureFire) {
      contexts(captureFrame)(captureOrdinal) := io.captureReadData
      when(captureLast) {
        frameSavedCount(captureFrame) := captureOrdinal + 1.U
        frameCountReg := frameCountReg + 1.U
        captureBusyReg := false.B
      }.otherwise {
        captureArch := nextRingArch(captureArch)
        captureOrdinal := captureOrdinal + 1.U
      }
    }

    when(restoreAccepted) {
      val topFrame = frameCountReg - 1.U
      restoreBusyReg := true.B
      restoreFrame := topFrame(frameWidth - 1, 0)
      restoreArch := frameStartArch(topFrame(frameWidth - 1, 0))
      restoreOrdinal := 0.U
      restoreCount := frameSavedCount(topFrame(frameWidth - 1, 0))
      restoreMapReg := io.restoreMap
    }.elsewhen(restoreFire) {
      when(restoreLast) {
        frameCountReg := frameCountReg - 1.U
        restoreBusyReg := false.B
        restoreDoneReg := true.B
      }.otherwise {
        restoreArch := nextRingArch(restoreArch)
        restoreOrdinal := restoreOrdinal + 1.U
      }
    }
  }

  io.captureBusy := captureBusyReg
  io.restoreBusy := restoreBusyReg
  io.restoreDone := restoreDoneReg
  io.frameCount := frameCountReg
  io.overflow := overflowReg
  io.underflow := underflowReg
}
