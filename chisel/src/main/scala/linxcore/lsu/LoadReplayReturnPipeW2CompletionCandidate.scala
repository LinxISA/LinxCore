package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.DestinationKind

class LoadReplayReturnPipeW2CompletionCandidateIO(
    val dataWidth: Int = 64,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val enable = Input(Bool())
  val flush = Input(Bool())
  val sideEffectsReady = Input(Bool())
  val slotOccupied = Input(Bool())
  val slotTargetIsAgu = Input(Bool())
  val slotTargetIsLda = Input(Bool())
  val slotPipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val slotDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))
  val slotData = Input(UInt(dataWidth.W))
  val slotWakeupRequired = Input(Bool())

  val candidateValid = Output(Bool())
  val targetValid = Output(Bool())
  val completeValid = Output(Bool())
  val clearSlot = Output(Bool())
  val targetIsAgu = Output(Bool())
  val targetIsLda = Output(Bool())
  val targetPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val resolveRequired = Output(Bool())
  val resolveValid = Output(Bool())
  val writebackRequired = Output(Bool())
  val writebackValid = Output(Bool())
  val writebackTag = Output(UInt(physRegWidth.W))
  val writebackData = Output(UInt(dataWidth.W))
  val writebackIgnoredNoDestination = Output(Bool())
  val writebackIgnoredNonGprDestination = Output(Bool())
  val wakeupRequired = Output(Bool())
  val wakeupValid = Output(Bool())
  val wakeupKind = Output(DestinationKind())
  val wakeupTag = Output(UInt(physRegWidth.W))
  val reducedGprWakeupValid = Output(Bool())
  val nonGprWakeup = Output(Bool())
  val suppressedWakeupNotRequired = Output(Bool())
  val ignoredNoWakeupDestination = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByInvalidTarget = Output(Bool())
  val blockedBySideEffects = Output(Bool())
}

class LoadReplayReturnPipeW2CompletionCandidate(
    val dataWidth: Int = 64,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(dataWidth > 0, "dataWidth must be positive")
  require(returnPipeCount > 0, "returnPipeCount must be positive")
  require(archRegWidth > 0, "archRegWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val io = IO(new LoadReplayReturnPipeW2CompletionCandidateIO(
    dataWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  val candidateValid = io.enable && !io.flush && io.slotOccupied
  val targetValid = io.slotTargetIsAgu ^ io.slotTargetIsLda
  val sideEffectCandidateValid = candidateValid && targetValid
  val completeValid = sideEffectCandidateValid && io.sideEffectsReady

  val hasDestination = io.slotDst.valid && (io.slotDst.kind =/= DestinationKind.None)
  val isGprDestination = hasDestination && (io.slotDst.kind === DestinationKind.Gpr)
  val writebackRequired = sideEffectCandidateValid && isGprDestination
  val wakeupRequired = sideEffectCandidateValid && io.slotWakeupRequired
  val wakeupValid = completeValid && io.slotWakeupRequired && hasDestination

  io.candidateValid := candidateValid
  io.targetValid := candidateValid && targetValid
  io.completeValid := completeValid
  io.clearSlot := completeValid
  io.targetIsAgu := sideEffectCandidateValid && io.slotTargetIsAgu
  io.targetIsLda := sideEffectCandidateValid && io.slotTargetIsLda
  io.targetPipeIndex := Mux(sideEffectCandidateValid, io.slotPipeIndex, 0.U(returnPipeIndexWidth.W))
  io.resolveRequired := sideEffectCandidateValid
  io.resolveValid := completeValid
  io.writebackRequired := writebackRequired
  io.writebackValid := completeValid && isGprDestination
  io.writebackTag := Mux(io.writebackValid, io.slotDst.physTag, 0.U)
  io.writebackData := Mux(io.writebackValid, io.slotData, 0.U)
  io.writebackIgnoredNoDestination := sideEffectCandidateValid && !hasDestination
  io.writebackIgnoredNonGprDestination := sideEffectCandidateValid && hasDestination && !isGprDestination
  io.wakeupRequired := wakeupRequired
  io.wakeupValid := wakeupValid
  io.wakeupKind := DestinationKind.None
  when(wakeupValid) {
    io.wakeupKind := io.slotDst.kind
  }
  io.wakeupTag := Mux(wakeupValid, io.slotDst.physTag, 0.U)
  io.reducedGprWakeupValid := wakeupValid && isGprDestination
  io.nonGprWakeup := wakeupValid && !isGprDestination
  io.suppressedWakeupNotRequired := sideEffectCandidateValid && !io.slotWakeupRequired
  io.ignoredNoWakeupDestination := wakeupRequired && !hasDestination
  io.blockedByDisabled := !io.enable && io.slotOccupied
  io.blockedByFlush := io.enable && io.flush && io.slotOccupied
  io.blockedByNoSlot := io.enable && !io.flush && !io.slotOccupied
  io.blockedByInvalidTarget := candidateValid && !targetValid
  io.blockedBySideEffects := sideEffectCandidateValid && !io.sideEffectsReady
}
