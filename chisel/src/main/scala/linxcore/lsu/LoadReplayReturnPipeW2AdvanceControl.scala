package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2AdvanceControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val livePromotionEnable = Input(Bool())
  val currentAdvanceReady = Input(Bool())
  val futureAdvanceReady = Input(Bool())
  val sameCycleReplaceReady = Input(Bool())
  val futureWriteAccept = Input(Bool())
  val writeCandidateValid = Input(Bool())

  val active = Output(Bool())
  val livePromotionActive = Output(Bool())
  val advanceEnable = Output(Bool())
  val replaceOnClear = Output(Bool())
  val usesFutureAdvance = Output(Bool())
  val currentMatchesFuture = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByLivePromotionDisabled = Output(Bool())
  val blockedByFutureAdvance = Output(Bool())
  val invalidReplaceWithoutFutureWrite = Output(Bool())
  val invalidFutureWriteWithoutAdvance = Output(Bool())
}

class LoadReplayReturnPipeW2AdvanceControl extends Module {
  val io = IO(new LoadReplayReturnPipeW2AdvanceControlIO)

  val active = io.enable && !io.flush
  val livePromotionActive = active && io.livePromotionEnable
  val advanceEnable =
    Mux(livePromotionActive, io.futureAdvanceReady, io.currentAdvanceReady)
  val replaceOnClear = livePromotionActive && io.sameCycleReplaceReady

  io.active := active
  io.livePromotionActive := livePromotionActive
  io.advanceEnable := advanceEnable
  io.replaceOnClear := replaceOnClear
  io.usesFutureAdvance :=
    livePromotionActive && io.futureAdvanceReady && !io.currentAdvanceReady
  io.currentMatchesFuture := io.currentAdvanceReady === io.futureAdvanceReady
  io.blockedByDisabled := !io.enable && io.writeCandidateValid
  io.blockedByFlush := io.enable && io.flush && io.writeCandidateValid
  io.blockedByLivePromotionDisabled :=
    active && !io.livePromotionEnable && io.sameCycleReplaceReady
  io.blockedByFutureAdvance :=
    livePromotionActive && io.writeCandidateValid && !io.futureAdvanceReady
  io.invalidReplaceWithoutFutureWrite :=
    replaceOnClear && !io.futureWriteAccept
  io.invalidFutureWriteWithoutAdvance :=
    livePromotionActive && io.futureWriteAccept && !advanceEnable
}
