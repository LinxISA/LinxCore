package linxcore.lsu

import chisel3._

import linxcore.common.DestinationKind

class LoadReplayReturnWritebackCandidateIO(
    val dataWidth: Int = 64,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  val enable = Input(Bool())
  val payloadValid = Input(Bool())
  val payloadDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))
  val payloadData = Input(UInt(dataWidth.W))

  val candidateValid = Output(Bool())
  val writeValid = Output(Bool())
  val writeTag = Output(UInt(physRegWidth.W))
  val writeData = Output(UInt(dataWidth.W))
  val ignoredNoDestination = Output(Bool())
  val ignoredNonGprDestination = Output(Bool())
  val blockedByDisabled = Output(Bool())
}

class LoadReplayReturnWritebackCandidate(
    val dataWidth: Int = 64,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(dataWidth > 0, "dataWidth must be positive")
  require(archRegWidth > 0, "archRegWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  val io = IO(new LoadReplayReturnWritebackCandidateIO(dataWidth, archRegWidth, physRegWidth))

  val candidateValid = io.enable && io.payloadValid
  val hasDestination = io.payloadDst.valid && (io.payloadDst.kind =/= DestinationKind.None)
  val isGprDestination = hasDestination && (io.payloadDst.kind === DestinationKind.Gpr)

  io.candidateValid := candidateValid
  io.writeValid := candidateValid && isGprDestination
  io.writeTag := Mux(io.writeValid, io.payloadDst.physTag, 0.U)
  io.writeData := Mux(io.writeValid, io.payloadData, 0.U)
  io.ignoredNoDestination := candidateValid && !hasDestination
  io.ignoredNonGprDestination := candidateValid && hasDestination && !isGprDestination
  io.blockedByDisabled := !io.enable && io.payloadValid
}
