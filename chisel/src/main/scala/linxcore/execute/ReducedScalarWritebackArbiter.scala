package linxcore.execute

import chisel3._

class ReducedScalarWritebackArbiterIO(
    val dataWidth: Int = 64,
    val physRegWidth: Int = 6)
    extends Bundle {
  val executeValid = Input(Bool())
  val executeTag = Input(UInt(physRegWidth.W))
  val executeData = Input(UInt(dataWidth.W))

  val replayEnable = Input(Bool())
  val replayValid = Input(Bool())
  val replayTag = Input(UInt(physRegWidth.W))
  val replayData = Input(UInt(dataWidth.W))

  val writeValid = Output(Bool())
  val writeTag = Output(UInt(physRegWidth.W))
  val writeData = Output(UInt(dataWidth.W))

  val selectedExecute = Output(Bool())
  val selectedReplay = Output(Bool())
  val replayBlockedByDisabled = Output(Bool())
  val replayBlockedByExecute = Output(Bool())
}

class ReducedScalarWritebackArbiter(
    val dataWidth: Int = 64,
    val physRegWidth: Int = 6)
    extends Module {
  require(dataWidth > 0, "dataWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  val io = IO(new ReducedScalarWritebackArbiterIO(dataWidth, physRegWidth))

  val replayCandidate = io.replayEnable && io.replayValid
  val selectedExecute = io.executeValid
  val selectedReplay = replayCandidate && !io.executeValid

  io.selectedExecute := selectedExecute
  io.selectedReplay := selectedReplay
  io.replayBlockedByDisabled := !io.replayEnable && io.replayValid
  io.replayBlockedByExecute := replayCandidate && io.executeValid

  io.writeValid := selectedExecute || selectedReplay
  io.writeTag := Mux(selectedExecute, io.executeTag, Mux(selectedReplay, io.replayTag, 0.U))
  io.writeData := Mux(selectedExecute, io.executeData, Mux(selectedReplay, io.replayData, 0.U))
}
