package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2WritebackArbiterInputIO(
    val dataWidth: Int = 64,
    val physRegWidth: Int = 6)
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveEnable = Input(Bool())
  val firePayloadValid = Input(Bool())
  val firePhysTag = Input(UInt(physRegWidth.W))
  val fireData = Input(UInt(dataWidth.W))

  val active = Output(Bool())
  val candidateValid = Output(Bool())
  val writeValid = Output(Bool())
  val writeTag = Output(UInt(physRegWidth.W))
  val writeData = Output(UInt(dataWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoPayload = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2WritebackArbiterInput(
    val dataWidth: Int = 64,
    val physRegWidth: Int = 6)
    extends Module {
  require(dataWidth > 0, "dataWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  val io = IO(new LoadReplayReturnPipeW2WritebackArbiterInputIO(dataWidth, physRegWidth))

  val active = io.enable && !io.flush
  val candidateValid = active && io.firePayloadValid
  val writeValid = candidateValid && io.liveEnable

  io.active := active
  io.candidateValid := candidateValid
  io.writeValid := writeValid
  io.writeTag := Mux(writeValid, io.firePhysTag, 0.U)
  io.writeData := Mux(writeValid, io.fireData, 0.U)
  io.blockedByDisabled := !io.enable && io.firePayloadValid
  io.blockedByFlush := io.enable && io.flush && io.firePayloadValid
  io.blockedByNoPayload := active && !io.firePayloadValid
  io.blockedByLiveDisabled := candidateValid && !io.liveEnable
}
