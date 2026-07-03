package linxcore.lsu

import chisel3._

class LoadReplayReturnLaneCompletionCandidateIO(val countWidth: Int = 8) extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val resolveValid = Input(Bool())
  val scalarLoadPair = Input(Bool())
  val vectorOrMemMultiLane = Input(Bool())
  val retLaneBefore = Input(UInt(countWidth.W))
  val returnedLaneCount = Input(UInt(countWidth.W))
  val realReqCnt = Input(UInt(countWidth.W))

  val candidateValid = Output(Bool())
  val retLaneAfterResolve = Output(UInt((countWidth + 1).W))
  val requiresAllLanes = Output(Bool())
  val completeValid = Output(Bool())
  val readyForPipeInsert = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoResolve = Output(Bool())
  val blockedByZeroReturnedLanes = Output(Bool())
  val blockedByInvalidRealReqCnt = Output(Bool())
  val blockedByScalarLoadPairIncomplete = Output(Bool())
  val blockedByVectorMemIncomplete = Output(Bool())
}

class LoadReplayReturnLaneCompletionCandidate(val countWidth: Int = 8) extends Module {
  require(countWidth > 0, "countWidth must be positive")

  val io = IO(new LoadReplayReturnLaneCompletionCandidateIO(countWidth))

  val candidateValid = io.enable && !io.flush && io.resolveValid
  val retLaneAfterResolve = io.retLaneBefore +& io.returnedLaneCount
  val hasReturnedLane = io.returnedLaneCount =/= 0.U
  val requiresAllLanes = io.scalarLoadPair || io.vectorOrMemMultiLane
  val hasValidReqCnt = !requiresAllLanes || (io.realReqCnt =/= 0.U)
  val allLanesReturned = !requiresAllLanes || (retLaneAfterResolve >= io.realReqCnt)
  val completeValid = candidateValid && hasReturnedLane && hasValidReqCnt && allLanesReturned

  io.candidateValid := candidateValid
  io.retLaneAfterResolve := Mux(candidateValid, retLaneAfterResolve, 0.U)
  io.requiresAllLanes := candidateValid && requiresAllLanes
  io.completeValid := completeValid
  io.readyForPipeInsert := completeValid
  io.blockedByDisabled := !io.enable && io.resolveValid
  io.blockedByFlush := io.enable && io.flush && io.resolveValid
  io.blockedByNoResolve := io.enable && !io.flush && !io.resolveValid
  io.blockedByZeroReturnedLanes := candidateValid && !hasReturnedLane
  io.blockedByInvalidRealReqCnt := candidateValid && requiresAllLanes && !hasValidReqCnt
  io.blockedByScalarLoadPairIncomplete :=
    candidateValid && io.scalarLoadPair && hasValidReqCnt && hasReturnedLane &&
      (retLaneAfterResolve < io.realReqCnt)
  io.blockedByVectorMemIncomplete :=
    candidateValid && !io.scalarLoadPair && io.vectorOrMemMultiLane && hasValidReqCnt &&
      hasReturnedLane && (retLaneAfterResolve < io.realReqCnt)
}
