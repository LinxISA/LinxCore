package linxcore.frontend

import chisel3._
import linxcore.common.InterfaceParams

class ReducedBfuResolvedBodyEndPendingIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val flushValid = Input(Bool())

  val captureValid = Input(Bool())
  val captureHeaderPc = Input(UInt(p.pcWidth.W))
  val captureHSizeBytes = Input(UInt(p.pcWidth.W))
  val captureBodyEndPc = Input(UInt(p.pcWidth.W))

  val candidateValid = Input(Bool())
  val candidateHeaderPc = Input(UInt(p.pcWidth.W))
  val candidateHSizeBytes = Input(UInt(p.pcWidth.W))
  val candidateBSizeBytes = Input(UInt(p.pcWidth.W))

  val consumeValid = Input(Bool())

  val runtimeValid = Output(Bool())
  val runtimeHeaderPc = Output(UInt(p.pcWidth.W))
  val runtimeHSizeBytes = Output(UInt(p.pcWidth.W))
  val runtimeBodyEndPc = Output(UInt(p.pcWidth.W))

  val pending = Output(Bool())
  val pendingHeaderPc = Output(UInt(p.pcWidth.W))
  val pendingHSizeBytes = Output(UInt(p.pcWidth.W))
  val pendingBodyEndPc = Output(UInt(p.pcWidth.W))
  val captureFire = Output(Bool())
  val consumeFire = Output(Bool())
  val dropMismatch = Output(Bool())
  val candidateComparable = Output(Bool())
  val candidateMatch = Output(Bool())
  val candidateMismatch = Output(Bool())
}

class ReducedBfuResolvedBodyEndPending(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuResolvedBodyEndPendingIO(p))

  val pendingReg = RegInit(false.B)
  val headerPcReg = RegInit(0.U(p.pcWidth.W))
  val hsizeReg = RegInit(0.U(p.pcWidth.W))
  val bodyEndPcReg = RegInit(0.U(p.pcWidth.W))

  val candidateBodyEndPc =
    (io.candidateHeaderPc + 2.U + io.candidateBSizeBytes)(p.pcWidth - 1, 0)
  val candidateComparable = pendingReg && io.candidateValid
  val candidateMatch =
    candidateComparable &&
      headerPcReg === io.candidateHeaderPc &&
      hsizeReg === io.candidateHSizeBytes &&
      bodyEndPcReg === candidateBodyEndPc
  val candidateMismatch = candidateComparable && !candidateMatch
  val runtimeValid = pendingReg && candidateMatch
  val consumeFire = pendingReg && io.consumeValid

  io.runtimeValid := runtimeValid
  io.runtimeHeaderPc := headerPcReg
  io.runtimeHSizeBytes := hsizeReg
  io.runtimeBodyEndPc := bodyEndPcReg
  io.pending := pendingReg
  io.pendingHeaderPc := headerPcReg
  io.pendingHSizeBytes := hsizeReg
  io.pendingBodyEndPc := bodyEndPcReg
  io.captureFire := io.captureValid && !io.flushValid
  io.consumeFire := consumeFire
  io.dropMismatch := candidateMismatch
  io.candidateComparable := candidateComparable
  io.candidateMatch := candidateMatch
  io.candidateMismatch := candidateMismatch

  when(io.flushValid) {
    pendingReg := false.B
    headerPcReg := 0.U
    hsizeReg := 0.U
    bodyEndPcReg := 0.U
  }.elsewhen(io.captureValid) {
    pendingReg := true.B
    headerPcReg := io.captureHeaderPc
    hsizeReg := io.captureHSizeBytes
    bodyEndPcReg := io.captureBodyEndPc
  }.elsewhen(consumeFire || candidateMismatch) {
    pendingReg := false.B
    headerPcReg := 0.U
    hsizeReg := 0.U
    bodyEndPcReg := 0.U
  }
}
