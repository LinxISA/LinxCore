package linxcore.lsu

import chisel3._

class LoadInflightRowMutationWriteControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestValid = Input(Bool())
  val targetRowValid = Input(Bool())
  val targetRowRepick = Input(Bool())
  val targetScbReturned = Input(Bool())
  val e4UpdateConflict = Input(Bool())
  val clearResolvedConflict = Input(Bool())
  val replayWakeConflict = Input(Bool())
  val refillConflict = Input(Bool())
  val launchConflict = Input(Bool())
  val allocationConflict = Input(Bool())

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val targetEvidenceValid = Output(Bool())
  val writeConflict = Output(Bool())
  val writeEnable = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoRequest = Output(Bool())
  val blockedByInvalidRow = Output(Bool())
  val blockedByNotRepick = Output(Bool())
  val blockedByScbNotReturned = Output(Bool())
  val blockedByE4UpdateConflict = Output(Bool())
  val blockedByClearResolvedConflict = Output(Bool())
  val blockedByReplayWakeConflict = Output(Bool())
  val blockedByRefillConflict = Output(Bool())
  val blockedByLaunchConflict = Output(Bool())
  val blockedByAllocationConflict = Output(Bool())
}

class LoadInflightRowMutationWriteControl extends Module {
  val io = IO(new LoadInflightRowMutationWriteControlIO)

  val active = io.enable && !io.flush
  val requestActive = active && io.requestValid
  val targetEvidenceValid = io.targetRowValid && io.targetRowRepick && io.targetScbReturned
  val writeConflict =
    io.e4UpdateConflict ||
      io.clearResolvedConflict ||
      io.replayWakeConflict ||
      io.refillConflict ||
      io.launchConflict ||
      io.allocationConflict
  val writeEnable = requestActive && targetEvidenceValid && !writeConflict

  io.active := active
  io.requestActive := requestActive
  io.targetEvidenceValid := targetEvidenceValid
  io.writeConflict := writeConflict
  io.writeEnable := writeEnable
  io.blockedByDisabled := !io.enable && io.requestValid
  io.blockedByFlush := io.enable && io.flush && io.requestValid
  io.blockedByNoRequest := active && !io.requestValid
  io.blockedByInvalidRow := requestActive && !io.targetRowValid
  io.blockedByNotRepick := requestActive && io.targetRowValid && !io.targetRowRepick
  io.blockedByScbNotReturned := requestActive && io.targetRowValid && io.targetRowRepick && !io.targetScbReturned
  io.blockedByE4UpdateConflict := requestActive && io.e4UpdateConflict
  io.blockedByClearResolvedConflict := requestActive && io.clearResolvedConflict
  io.blockedByReplayWakeConflict := requestActive && io.replayWakeConflict
  io.blockedByRefillConflict := requestActive && io.refillConflict
  io.blockedByLaunchConflict := requestActive && io.launchConflict
  io.blockedByAllocationConflict := requestActive && io.allocationConflict
}
