package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMaskIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveMaskEnable = Input(Bool())
  val eligibleRegisteredMask = Input(Bool())

  val maskCandidate = Output(Bool())
  val liveMaskCandidate = Output(Bool())
  val suppressRobComplete = Output(Bool())
  val suppressRfWriteback = Output(Bool())
  val suppressWakeup = Output(Bool())
  val suppressLifecycleClear = Output(Bool())
  val suppressMask = Output(UInt(4.W))
  val allOrNoneMask = Output(Bool())
  val blockedByLiveMaskDisabled = Output(Bool())
  val blockedByNoEligibleOwnership = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMask extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMaskIO)

  val active = io.enable && !io.flush
  val maskCandidate = active && io.eligibleRegisteredMask
  val liveMaskCandidate = maskCandidate && io.liveMaskEnable
  val suppressMask = Mux(liveMaskCandidate, "b1111".U, 0.U(4.W))

  io.maskCandidate := maskCandidate
  io.liveMaskCandidate := liveMaskCandidate
  io.suppressRobComplete := suppressMask(0)
  io.suppressRfWriteback := suppressMask(1)
  io.suppressWakeup := suppressMask(2)
  io.suppressLifecycleClear := suppressMask(3)
  io.suppressMask := suppressMask
  io.allOrNoneMask := suppressMask === 0.U || suppressMask === "b1111".U
  io.blockedByLiveMaskDisabled := maskCandidate && !io.liveMaskEnable
  io.blockedByNoEligibleOwnership := active && !io.eligibleRegisteredMask
}
