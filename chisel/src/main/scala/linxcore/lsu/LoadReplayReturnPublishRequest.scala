package linxcore.lsu

import chisel3._
import chisel3.util.Cat

class LoadReplayReturnPublishRequestIO extends Bundle {
  val publishFire = Input(Bool())
  val payloadValid = Input(Bool())
  val writebackRequired = Input(Bool())
  val wakeupRequired = Input(Bool())

  val requestValid = Output(Bool())
  val lretRequest = Output(Bool())
  val writebackRequest = Output(Bool())
  val wakeupRequest = Output(Bool())
  val requestMask = Output(UInt(3.W))
  val blockedByNoFire = Output(Bool())
  val invalidFireWithoutPayload = Output(Bool())
}

class LoadReplayReturnPublishRequest extends Module {
  val io = IO(new LoadReplayReturnPublishRequestIO)

  val requestValid = io.publishFire && io.payloadValid
  val writebackRequest = requestValid && io.writebackRequired
  val wakeupRequest = requestValid && io.wakeupRequired

  io.requestValid := requestValid
  io.lretRequest := requestValid
  io.writebackRequest := writebackRequest
  io.wakeupRequest := wakeupRequest
  io.requestMask := Cat(wakeupRequest, writebackRequest, requestValid)
  io.blockedByNoFire := io.payloadValid && !io.publishFire
  io.invalidFireWithoutPayload := io.publishFire && !io.payloadValid
}
