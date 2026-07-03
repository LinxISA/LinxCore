package linxcore.lsu

import chisel3._

import linxcore.common.DestinationKind

class LoadReplayReturnWakeupCandidateIO(
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  val enable = Input(Bool())
  val payloadValid = Input(Bool())
  val payloadWakeupRequired = Input(Bool())
  val payloadDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))

  val candidateValid = Output(Bool())
  val wakeupRequired = Output(Bool())
  val wakeupValid = Output(Bool())
  val wakeupKind = Output(DestinationKind())
  val wakeupTag = Output(UInt(physRegWidth.W))
  val reducedGprWakeupValid = Output(Bool())
  val nonGprWakeup = Output(Bool())
  val suppressedWakeupNotRequired = Output(Bool())
  val ignoredNoDestination = Output(Bool())
  val blockedByDisabled = Output(Bool())
}

class LoadReplayReturnWakeupCandidate(
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(archRegWidth > 0, "archRegWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  val io = IO(new LoadReplayReturnWakeupCandidateIO(archRegWidth, physRegWidth))

  val candidateValid = io.enable && io.payloadValid
  val wakeupRequired = candidateValid && io.payloadWakeupRequired
  val hasDestination = io.payloadDst.valid && (io.payloadDst.kind =/= DestinationKind.None)
  val isGprDestination = hasDestination && (io.payloadDst.kind === DestinationKind.Gpr)
  val wakeupValid = wakeupRequired && hasDestination

  io.candidateValid := candidateValid
  io.wakeupRequired := wakeupRequired
  io.wakeupValid := wakeupValid
  io.wakeupKind := DestinationKind.None
  when(wakeupValid) {
    io.wakeupKind := io.payloadDst.kind
  }
  io.wakeupTag := Mux(wakeupValid, io.payloadDst.physTag, 0.U)
  io.reducedGprWakeupValid := wakeupValid && isGprDestination
  io.nonGprWakeup := wakeupValid && !isGprDestination
  io.suppressedWakeupNotRequired := candidateValid && !io.payloadWakeupRequired
  io.ignoredNoDestination := wakeupRequired && !hasDestination
  io.blockedByDisabled := !io.enable && io.payloadValid
}
