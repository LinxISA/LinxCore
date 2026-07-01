package linxcore.frontend

import chisel3._
import linxcore.common.InterfaceParams

class ReducedBfuPendingRuntimeBodyEndCandidateIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val pendingValid = Input(Bool())
  val pendingHeaderPc = Input(UInt(p.pcWidth.W))
  val pendingHSizeBytes = Input(UInt(p.pcWidth.W))
  val pendingBodyEndPc = Input(UInt(p.pcWidth.W))

  val headerActive = Input(Bool())
  val activeHeaderPc = Input(UInt(p.pcWidth.W))

  val replayValid = Input(Bool())
  val replayHeaderPc = Input(UInt(p.pcWidth.W))
  val replayHSizeBytes = Input(UInt(p.pcWidth.W))
  val replayBSizeBytes = Input(UInt(p.pcWidth.W))

  val candidateValid = Output(Bool())
  val candidateHeaderPc = Output(UInt(p.pcWidth.W))
  val candidateHSizeBytes = Output(UInt(p.pcWidth.W))
  val candidateBodyEndPc = Output(UInt(p.pcWidth.W))

  val pendingWithoutActiveHeader = Output(Bool())
  val activeHeaderMismatch = Output(Bool())
  val replayBodyEndPc = Output(UInt(p.pcWidth.W))
  val replayComparable = Output(Bool())
  val replayMatch = Output(Bool())
  val replayHeaderMismatch = Output(Bool())
  val replayHSizeMismatch = Output(Bool())
  val replayBodyEndMismatch = Output(Bool())
}

class ReducedBfuPendingRuntimeBodyEndCandidate(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuPendingRuntimeBodyEndCandidateIO(p))

  val activeHeaderMatch = io.headerActive && io.pendingHeaderPc === io.activeHeaderPc
  val candidateValid = io.pendingValid && activeHeaderMatch
  val replayBodyEndPc = (io.replayHeaderPc + 2.U + io.replayBSizeBytes)(p.pcWidth - 1, 0)
  val replayComparable = candidateValid && io.replayValid
  val headerMatch = io.pendingHeaderPc === io.replayHeaderPc
  val hsizeMatch = io.pendingHSizeBytes === io.replayHSizeBytes
  val bodyEndMatch = io.pendingBodyEndPc === replayBodyEndPc
  val replayMatch = replayComparable && headerMatch && hsizeMatch && bodyEndMatch

  io.candidateValid := candidateValid
  io.candidateHeaderPc := io.pendingHeaderPc
  io.candidateHSizeBytes := io.pendingHSizeBytes
  io.candidateBodyEndPc := io.pendingBodyEndPc
  io.pendingWithoutActiveHeader := io.pendingValid && !io.headerActive
  io.activeHeaderMismatch := io.pendingValid && io.headerActive && !activeHeaderMatch
  io.replayBodyEndPc := replayBodyEndPc
  io.replayComparable := replayComparable
  io.replayMatch := replayMatch
  io.replayHeaderMismatch := replayComparable && !headerMatch
  io.replayHSizeMismatch := replayComparable && !hsizeMatch
  io.replayBodyEndMismatch := replayComparable && !bodyEndMatch
}
