package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class LoadReplayReturnPipeResidencyAdvanceLiveControlIO(val returnPipeCount: Int = 1)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestEnable = Input(Bool())
  val slotOccupied = Input(Bool())
  val slotTargetIsAgu = Input(Bool())
  val slotTargetIsLda = Input(Bool())
  val slotPipeIndex = Input(UInt(returnPipeIndexWidth.W))

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val advanceEvidenceValid = Output(Bool())
  val advanceEnable = Output(Bool())
  val evidenceTargetIsAgu = Output(Bool())
  val evidenceTargetIsLda = Output(Bool())
  val evidenceTargetPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByRequestDisabled = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByInvalidTarget = Output(Bool())
  val blockedByNoEvidence = Output(Bool())
}

class LoadReplayReturnPipeResidencyAdvanceLiveControl(val returnPipeCount: Int = 1)
    extends Module {
  require(returnPipeCount > 0, "returnPipeCount must be positive")

  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val io = IO(new LoadReplayReturnPipeResidencyAdvanceLiveControlIO(returnPipeCount))

  val active = io.enable && !io.flush
  val requestActive = active && io.requestEnable
  val targetValid = io.slotTargetIsAgu ^ io.slotTargetIsLda
  val rawEvidence = io.slotOccupied && targetValid
  val advanceEvidenceValid = active && rawEvidence
  val advanceEnable = requestActive && rawEvidence

  io.active := active
  io.requestActive := requestActive
  io.advanceEvidenceValid := advanceEvidenceValid
  io.advanceEnable := advanceEnable
  io.evidenceTargetIsAgu := advanceEvidenceValid && io.slotTargetIsAgu
  io.evidenceTargetIsLda := advanceEvidenceValid && io.slotTargetIsLda
  io.evidenceTargetPipeIndex := Mux(advanceEvidenceValid, io.slotPipeIndex, 0.U(returnPipeIndexWidth.W))
  io.blockedByDisabled := !io.enable && (io.requestEnable || rawEvidence)
  io.blockedByFlush := io.enable && io.flush && (io.requestEnable || rawEvidence)
  io.blockedByRequestDisabled := active && !io.requestEnable && rawEvidence
  io.blockedByNoSlot := requestActive && !io.slotOccupied
  io.blockedByInvalidTarget := requestActive && io.slotOccupied && !targetValid
  io.blockedByNoEvidence := requestActive && !rawEvidence
}
