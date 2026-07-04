package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnStoreSnapshotResponseDrainIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val headValid = Input(Bool())
  val orderedResponse = Input(Bool())
  val headStale = Input(Bool())

  val active = Output(Bool())
  val dequeueReady = Output(Bool())
  val orderedConsumed = Output(Bool())
  val staleDropped = Output(Bool())
  val blockedByNoHead = Output(Bool())
  val blockedByNoAction = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val invalidStaleWithOrdered = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotResponseDrain extends Module {
  val io = IO(new LoadReplaySourceReturnStoreSnapshotResponseDrainIO)

  val active = io.enable && !io.flush
  val orderedConsumed = active && io.headValid && io.orderedResponse
  val staleDropped = active && io.headValid && !io.orderedResponse && io.headStale

  io.active := active
  io.dequeueReady := orderedConsumed || staleDropped
  io.orderedConsumed := orderedConsumed
  io.staleDropped := staleDropped
  io.blockedByNoHead := active && !io.headValid
  io.blockedByNoAction := active && io.headValid && !io.orderedResponse && !io.headStale
  io.blockedByDisabled := !io.enable && io.headValid
  io.blockedByFlush := io.enable && io.flush && io.headValid
  io.invalidStaleWithOrdered := active && io.headValid && io.orderedResponse && io.headStale
}
