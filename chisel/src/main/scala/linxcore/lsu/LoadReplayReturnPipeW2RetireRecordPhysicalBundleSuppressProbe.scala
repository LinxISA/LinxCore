package linxcore.lsu

import chisel3._
import chisel3.util.Cat

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbeIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val probeEnable = Input(Bool())
  val atomicSuppressCandidate = Input(Bool())
  val suppressRobComplete = Input(Bool())
  val suppressRfWriteback = Input(Bool())
  val suppressWakeup = Input(Bool())
  val suppressLifecycleClear = Input(Bool())

  val active = Output(Bool())
  val suppressCandidate = Output(Bool())
  val probeEnabledCandidate = Output(Bool())
  val selected = Output(Bool())
  val selectedMask = Output(UInt(4.W))
  val allOrNoneInputMask = Output(Bool())
  val blockedByProbeDisabled = Output(Bool())
  val blockedByNoAtomicCandidate = Output(Bool())
  val blockedByPartialMask = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbe extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressProbeIO)

  val active = io.enable && !io.flush
  val inputMask = Cat(
    io.suppressLifecycleClear,
    io.suppressWakeup,
    io.suppressRfWriteback,
    io.suppressRobComplete)
  val allOrNoneInputMask = inputMask === 0.U || inputMask === "b1111".U
  val suppressCandidate = active && io.atomicSuppressCandidate
  val probeEnabledCandidate = suppressCandidate && io.probeEnable
  val selected =
    probeEnabledCandidate &&
      allOrNoneInputMask &&
      inputMask === "b1111".U

  io.active := active
  io.suppressCandidate := suppressCandidate
  io.probeEnabledCandidate := probeEnabledCandidate
  io.selected := selected
  io.selectedMask := Mux(selected, inputMask, 0.U)
  io.allOrNoneInputMask := allOrNoneInputMask
  io.blockedByProbeDisabled := suppressCandidate && !io.probeEnable
  io.blockedByNoAtomicCandidate := active && io.probeEnable && !io.atomicSuppressCandidate
  io.blockedByPartialMask := probeEnabledCandidate && !allOrNoneInputMask
}
