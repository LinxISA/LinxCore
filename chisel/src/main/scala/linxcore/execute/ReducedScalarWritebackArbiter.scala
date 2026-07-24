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

  val serviceEnable = Input(Bool())
  val serviceValid = Input(Bool())
  val serviceTag = Input(UInt(physRegWidth.W))
  val serviceData = Input(UInt(dataWidth.W))

  val templateEnable = Input(Bool())
  val templateValid = Input(Bool())
  val templateTag = Input(UInt(physRegWidth.W))
  val templateData = Input(UInt(dataWidth.W))

  val writeValid = Output(Bool())
  val writeTag = Output(UInt(physRegWidth.W))
  val writeData = Output(UInt(dataWidth.W))

  val selectedExecute = Output(Bool())
  val selectedReplay = Output(Bool())
  val selectedService = Output(Bool())
  val selectedTemplate = Output(Bool())
  val replayBlockedByDisabled = Output(Bool())
  val replayBlockedByExecute = Output(Bool())
  val serviceBlockedByDisabled = Output(Bool())
  val serviceBlockedByExecute = Output(Bool())
  val serviceBlockedByReplay = Output(Bool())
  val templateBlockedByDisabled = Output(Bool())
  val templateBlockedByExecute = Output(Bool())
  val templateBlockedByReplay = Output(Bool())
  val templateBlockedByService = Output(Bool())
}

class ReducedScalarWritebackArbiter(
    val dataWidth: Int = 64,
    val physRegWidth: Int = 6)
    extends Module {
  require(dataWidth > 0, "dataWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  val io = IO(new ReducedScalarWritebackArbiterIO(dataWidth, physRegWidth))

  val replayCandidate = io.replayEnable && io.replayValid
  val serviceCandidate = io.serviceEnable && io.serviceValid
  val templateCandidate = io.templateEnable && io.templateValid
  val selectedExecute = io.executeValid
  val selectedReplay = replayCandidate && !io.executeValid
  val selectedService = serviceCandidate && !io.executeValid && !replayCandidate
  val selectedTemplate = templateCandidate && !io.executeValid && !replayCandidate && !serviceCandidate

  io.selectedExecute := selectedExecute
  io.selectedReplay := selectedReplay
  io.selectedService := selectedService
  io.selectedTemplate := selectedTemplate
  io.replayBlockedByDisabled := !io.replayEnable && io.replayValid
  io.replayBlockedByExecute := replayCandidate && io.executeValid
  io.serviceBlockedByDisabled := !io.serviceEnable && io.serviceValid
  io.serviceBlockedByExecute := serviceCandidate && io.executeValid
  io.serviceBlockedByReplay := serviceCandidate && !io.executeValid && replayCandidate
  io.templateBlockedByDisabled := !io.templateEnable && io.templateValid
  io.templateBlockedByExecute := templateCandidate && io.executeValid
  io.templateBlockedByReplay := templateCandidate && !io.executeValid && replayCandidate
  io.templateBlockedByService :=
    templateCandidate && !io.executeValid && !replayCandidate && serviceCandidate

  io.writeValid := selectedExecute || selectedReplay || selectedService || selectedTemplate
  io.writeTag := Mux(
    selectedExecute,
    io.executeTag,
    Mux(
      selectedReplay,
      io.replayTag,
      Mux(selectedService, io.serviceTag, Mux(selectedTemplate, io.templateTag, 0.U))))
  io.writeData := Mux(
    selectedExecute,
    io.executeData,
    Mux(
      selectedReplay,
      io.replayData,
      Mux(selectedService, io.serviceData, Mux(selectedTemplate, io.templateData, 0.U))))
}
