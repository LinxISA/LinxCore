package linxcore.lsu

import chisel3._
import chisel3.util.Cat

class LoadReplayReturnPipeW2SideEffectRequestIO extends Bundle {
  val sideEffectCandidateValid = Input(Bool())
  val completeValid = Input(Bool())
  val resolveValid = Input(Bool())
  val writebackValid = Input(Bool())
  val wakeupValid = Input(Bool())

  val requestValid = Output(Bool())
  val resolveRequest = Output(Bool())
  val writebackRequest = Output(Bool())
  val wakeupRequest = Output(Bool())
  val requestMask = Output(UInt(3.W))
  val blockedByNoComplete = Output(Bool())
  val invalidCompleteWithoutCandidate = Output(Bool())
  val invalidCompleteWithoutResolve = Output(Bool())
  val invalidResolveWithoutComplete = Output(Bool())
  val invalidWritebackWithoutComplete = Output(Bool())
  val invalidWakeupWithoutComplete = Output(Bool())
}

class LoadReplayReturnPipeW2SideEffectRequest extends Module {
  val io = IO(new LoadReplayReturnPipeW2SideEffectRequestIO)

  val requestValid = io.completeValid && io.sideEffectCandidateValid
  val resolveRequest = requestValid && io.resolveValid
  val writebackRequest = requestValid && io.writebackValid
  val wakeupRequest = requestValid && io.wakeupValid

  io.requestValid := requestValid
  io.resolveRequest := resolveRequest
  io.writebackRequest := writebackRequest
  io.wakeupRequest := wakeupRequest
  io.requestMask := Cat(wakeupRequest, writebackRequest, resolveRequest)
  io.blockedByNoComplete := io.sideEffectCandidateValid && !io.completeValid
  io.invalidCompleteWithoutCandidate := io.completeValid && !io.sideEffectCandidateValid
  io.invalidCompleteWithoutResolve := requestValid && !io.resolveValid
  io.invalidResolveWithoutComplete := io.resolveValid && !io.completeValid
  io.invalidWritebackWithoutComplete := io.writebackValid && !io.completeValid
  io.invalidWakeupWithoutComplete := io.wakeupValid && !io.completeValid
}
