package linxcore.frontend

import chisel3._
import linxcore.common.InterfaceParams

class ReducedBfuPromotedRuntimeBodyEndOracleIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val flushValid = Input(Bool())

  val promoteValid = Input(Bool())
  val promoteHeaderPc = Input(UInt(p.pcWidth.W))
  val promoteHSizeBytes = Input(UInt(p.pcWidth.W))
  val promoteBodyEndPc = Input(UInt(p.pcWidth.W))

  val replayValid = Input(Bool())
  val replayHeaderPc = Input(UInt(p.pcWidth.W))
  val replayHSizeBytes = Input(UInt(p.pcWidth.W))
  val replayBSizeBytes = Input(UInt(p.pcWidth.W))

  val pending = Output(Bool())
  val captureFire = Output(Bool())
  val overwritePending = Output(Bool())
  val replayBodyEndPc = Output(UInt(p.pcWidth.W))
  val replayComparable = Output(Bool())
  val replayMatch = Output(Bool())
  val replayHeaderMismatch = Output(Bool())
  val replayHSizeMismatch = Output(Bool())
  val replayBodyEndMismatch = Output(Bool())
}

class ReducedBfuPromotedRuntimeBodyEndOracle(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuPromotedRuntimeBodyEndOracleIO(p))

  val pendingReg = RegInit(false.B)
  val headerPcReg = RegInit(0.U(p.pcWidth.W))
  val hsizeReg = RegInit(0.U(p.pcWidth.W))
  val bodyEndPcReg = RegInit(0.U(p.pcWidth.W))

  val replayBodyEndPc = (io.replayHeaderPc + 2.U + io.replayBSizeBytes)(p.pcWidth - 1, 0)

  val compareAllowed = !io.flushValid
  val pendingComparable = compareAllowed && pendingReg && io.replayValid
  val immediateComparable = compareAllowed && !pendingReg && io.promoteValid && io.replayValid
  val replayComparable = pendingComparable || immediateComparable
  val compareHeaderPc = Mux(pendingReg, headerPcReg, io.promoteHeaderPc)
  val compareHSizeBytes = Mux(pendingReg, hsizeReg, io.promoteHSizeBytes)
  val compareBodyEndPc = Mux(pendingReg, bodyEndPcReg, io.promoteBodyEndPc)
  val headerMatch = compareHeaderPc === io.replayHeaderPc
  val hsizeMatch = compareHSizeBytes === io.replayHSizeBytes
  val bodyEndMatch = compareBodyEndPc === replayBodyEndPc
  val replayMatch = replayComparable && headerMatch && hsizeMatch && bodyEndMatch
  val replayMismatch = replayComparable && !replayMatch
  val captureAfterPendingMatch = io.promoteValid && pendingComparable && replayMatch
  val captureIntoEmpty = io.promoteValid && !pendingReg && !immediateComparable
  val captureFire = !io.flushValid && (captureAfterPendingMatch || captureIntoEmpty)
  val overwritePending = !io.flushValid && pendingReg && io.promoteValid && !pendingComparable

  io.pending := pendingReg
  io.captureFire := captureFire
  io.overwritePending := overwritePending
  io.replayBodyEndPc := replayBodyEndPc
  io.replayComparable := replayComparable
  io.replayMatch := replayMatch
  io.replayHeaderMismatch := replayComparable && !headerMatch
  io.replayHSizeMismatch := replayComparable && !hsizeMatch
  io.replayBodyEndMismatch := replayMismatch && !bodyEndMatch

  when(io.flushValid) {
    pendingReg := false.B
    headerPcReg := 0.U
    hsizeReg := 0.U
    bodyEndPcReg := 0.U
  }.elsewhen(captureFire) {
    pendingReg := true.B
    headerPcReg := io.promoteHeaderPc
    hsizeReg := io.promoteHSizeBytes
    bodyEndPcReg := io.promoteBodyEndPc
  }.elsewhen(pendingComparable) {
    pendingReg := false.B
    headerPcReg := 0.U
    hsizeReg := 0.U
    bodyEndPcReg := 0.U
  }
}
