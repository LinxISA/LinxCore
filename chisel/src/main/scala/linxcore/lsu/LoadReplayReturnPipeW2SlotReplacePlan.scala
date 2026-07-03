package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2SlotReplacePlanIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val slotOccupied = Input(Bool())
  val writeValid = Input(Bool())
  val writeTargetIsAgu = Input(Bool())
  val writeTargetIsLda = Input(Bool())
  val clearIntent = Input(Bool())
  val liveClear = Input(Bool())
  val futureAdvanceReady = Input(Bool())
  val currentSlotAccepted = Input(Bool())
  val currentSlotBlockedByClear = Input(Bool())

  val active = Output(Bool())
  val writeTargetValid = Output(Bool())
  val emptyWriteEligible = Output(Bool())
  val sameCycleReplaceEligible = Output(Bool())
  val sameCycleReplaceReady = Output(Bool())
  val futureWriteAccept = Output(Bool())
  val currentMatchesFutureWrite = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoWrite = Output(Bool())
  val blockedByInvalidTarget = Output(Bool())
  val blockedByOccupiedNoClear = Output(Bool())
  val blockedByLiveClearDisabled = Output(Bool())
  val blockedByCurrentStorage = Output(Bool())
  val blockedByCurrentClearPriority = Output(Bool())
  val invalidFutureAdvanceWithoutLiveClear = Output(Bool())
  val invalidCurrentAcceptWithoutFuture = Output(Bool())
}

class LoadReplayReturnPipeW2SlotReplacePlan extends Module {
  val io = IO(new LoadReplayReturnPipeW2SlotReplacePlanIO)

  val active = io.enable && !io.flush
  val writeTargetValid = io.writeTargetIsAgu ^ io.writeTargetIsLda
  val writeCandidate = active && io.writeValid
  val emptyWriteEligible = writeCandidate && !io.slotOccupied && writeTargetValid
  val sameCycleReplaceEligible =
    writeCandidate && io.slotOccupied && writeTargetValid && io.clearIntent
  val sameCycleReplaceReady =
    sameCycleReplaceEligible && io.liveClear && io.futureAdvanceReady
  val futureWriteAccept = emptyWriteEligible || sameCycleReplaceReady

  io.active := active
  io.writeTargetValid := writeTargetValid
  io.emptyWriteEligible := emptyWriteEligible
  io.sameCycleReplaceEligible := sameCycleReplaceEligible
  io.sameCycleReplaceReady := sameCycleReplaceReady
  io.futureWriteAccept := futureWriteAccept
  io.currentMatchesFutureWrite := io.currentSlotAccepted === futureWriteAccept
  io.blockedByDisabled := !io.enable && io.writeValid
  io.blockedByFlush := io.enable && io.flush && io.writeValid
  io.blockedByNoWrite := active && !io.writeValid
  io.blockedByInvalidTarget := writeCandidate && !writeTargetValid
  io.blockedByOccupiedNoClear :=
    writeCandidate && io.slotOccupied && writeTargetValid && !io.clearIntent
  io.blockedByLiveClearDisabled := sameCycleReplaceEligible && !io.liveClear
  io.blockedByCurrentStorage := futureWriteAccept && !io.currentSlotAccepted
  io.blockedByCurrentClearPriority :=
    sameCycleReplaceReady && io.currentSlotBlockedByClear
  io.invalidFutureAdvanceWithoutLiveClear :=
    active && io.slotOccupied && io.futureAdvanceReady && !io.liveClear
  io.invalidCurrentAcceptWithoutFuture :=
    io.currentSlotAccepted && !futureWriteAccept
}
