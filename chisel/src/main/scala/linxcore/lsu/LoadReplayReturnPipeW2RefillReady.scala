package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RefillReadyIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val slotOccupied = Input(Bool())
  val currentAdvanceReady = Input(Bool())
  val clearIntent = Input(Bool())
  val liveClear = Input(Bool())

  val active = Output(Bool())
  val emptyReady = Output(Bool())
  val sameCycleRefillEligible = Output(Bool())
  val sameCycleRefillReady = Output(Bool())
  val futureAdvanceReady = Output(Bool())
  val currentMatchesFuture = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByOccupied = Output(Bool())
  val blockedByLiveClearDisabled = Output(Bool())
  val invalidLiveClearWithoutIntent = Output(Bool())
}

class LoadReplayReturnPipeW2RefillReady extends Module {
  val io = IO(new LoadReplayReturnPipeW2RefillReadyIO)

  val active = io.enable && !io.flush
  val emptyReady = active && !io.slotOccupied
  val sameCycleRefillEligible = active && io.slotOccupied && io.clearIntent
  val sameCycleRefillReady = active && io.slotOccupied && io.liveClear
  val futureAdvanceReady = emptyReady || sameCycleRefillReady

  io.active := active
  io.emptyReady := emptyReady
  io.sameCycleRefillEligible := sameCycleRefillEligible
  io.sameCycleRefillReady := sameCycleRefillReady
  io.futureAdvanceReady := futureAdvanceReady
  io.currentMatchesFuture := io.currentAdvanceReady === futureAdvanceReady
  io.blockedByDisabled := !io.enable && io.slotOccupied
  io.blockedByFlush := io.enable && io.flush && io.slotOccupied
  io.blockedByOccupied := active && io.slotOccupied && !io.liveClear
  io.blockedByLiveClearDisabled := sameCycleRefillEligible && !io.liveClear
  io.invalidLiveClearWithoutIntent := active && io.liveClear && !io.clearIntent
}
