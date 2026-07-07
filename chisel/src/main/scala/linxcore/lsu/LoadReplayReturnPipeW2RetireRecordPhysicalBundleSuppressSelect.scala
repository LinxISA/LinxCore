package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelectIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val promoteEnable = Input(Bool())
  val planAtomicSuppressCandidate = Input(Bool())
  val planSuppressMask = Input(UInt(4.W))
  val planAllOrNoneSuppress = Input(Bool())
  val probeSelected = Input(Bool())
  val probeSelectedMask = Input(UInt(4.W))
  val probeAllOrNoneInputMask = Input(Bool())

  val planPromotedCandidate = Output(Bool())
  val selected = Output(Bool())
  val selectedMask = Output(UInt(4.W))
  val allOrNoneInputMask = Output(Bool())
  val selectedFromProbe = Output(Bool())
  val selectedFromPromotion = Output(Bool())
  val blockedByPromoteDisabled = Output(Bool())
  val blockedByNoPlanCandidate = Output(Bool())
  val blockedByPartialPlanMask = Output(Bool())
  val invalidProbePromotionMaskMismatch = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelect extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelectIO)

  val active = io.enable && !io.flush
  val planCandidate = active && io.planAtomicSuppressCandidate
  val planFullMask = io.planAllOrNoneSuppress && io.planSuppressMask === "b1111".U
  val planPromotedCandidate = planCandidate && io.promoteEnable && planFullMask
  val probeFullCandidate =
    active && io.probeSelected && io.probeAllOrNoneInputMask && io.probeSelectedMask === "b1111".U

  val selected = probeFullCandidate || planPromotedCandidate
  val selectedMask = Mux(selected, "b1111".U, 0.U(4.W))

  io.planPromotedCandidate := planPromotedCandidate
  io.selected := selected
  io.selectedMask := selectedMask
  io.allOrNoneInputMask := selectedMask === 0.U || selectedMask === "b1111".U
  io.selectedFromProbe := probeFullCandidate
  io.selectedFromPromotion := planPromotedCandidate
  io.blockedByPromoteDisabled := planCandidate && !io.promoteEnable
  io.blockedByNoPlanCandidate := active && io.promoteEnable && !io.planAtomicSuppressCandidate
  io.blockedByPartialPlanMask := planCandidate && io.promoteEnable && !planFullMask
  io.invalidProbePromotionMaskMismatch :=
    active && io.probeSelected && planPromotedCandidate && io.probeSelectedMask =/= io.planSuppressMask
}
