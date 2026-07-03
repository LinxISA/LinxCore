package linxcore.lsu

import chisel3._

class LoadReplayReturnTloadCompletionCandidateIO(val countWidth: Int = 8) extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val laneCompletionValid = Input(Bool())
  val isMemIex = Input(Bool())
  val isTload = Input(Bool())
  val subInstCntBefore = Input(UInt(countWidth.W))

  val candidateValid = Output(Bool())
  val tloadCandidateValid = Output(Bool())
  val subInstCntAfter = Output(UInt(countWidth.W))
  val tileScbSendValid = Output(Bool())
  val tileScbIsLast = Output(Bool())
  val completeValid = Output(Bool())
  val readyForPipeInsert = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoLaneCompletion = Output(Bool())
  val blockedByInvalidSubInstCnt = Output(Bool())
  val blockedByTloadPending = Output(Bool())
}

class LoadReplayReturnTloadCompletionCandidate(val countWidth: Int = 8) extends Module {
  require(countWidth > 0, "countWidth must be positive")

  val io = IO(new LoadReplayReturnTloadCompletionCandidateIO(countWidth))

  val candidateValid = io.enable && !io.flush && io.laneCompletionValid
  val tloadCandidateValid = candidateValid && io.isMemIex && io.isTload
  val decrementValid = tloadCandidateValid && io.subInstCntBefore =/= 0.U
  val subInstCntAfter = Mux(decrementValid, io.subInstCntBefore - 1.U, io.subInstCntBefore)
  val tloadComplete = decrementValid && subInstCntAfter === 0.U
  val completeValid = candidateValid && (!tloadCandidateValid || tloadComplete)

  io.candidateValid := candidateValid
  io.tloadCandidateValid := tloadCandidateValid
  io.subInstCntAfter := Mux(candidateValid, subInstCntAfter, 0.U)
  io.tileScbSendValid := decrementValid
  io.tileScbIsLast := decrementValid && subInstCntAfter === 0.U
  io.completeValid := completeValid
  io.readyForPipeInsert := completeValid
  io.blockedByDisabled := !io.enable && io.laneCompletionValid
  io.blockedByFlush := io.enable && io.flush && io.laneCompletionValid
  io.blockedByNoLaneCompletion := io.enable && !io.flush && !io.laneCompletionValid
  io.blockedByInvalidSubInstCnt := tloadCandidateValid && io.subInstCntBefore === 0.U
  io.blockedByTloadPending := decrementValid && subInstCntAfter =/= 0.U
}
