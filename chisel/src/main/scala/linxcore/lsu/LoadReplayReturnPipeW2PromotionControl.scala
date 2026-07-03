package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2PromotionControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val promotionRequested = Input(Bool())
  val slotOccupied = Input(Bool())
  val clearIntent = Input(Bool())
  val writeCandidateValid = Input(Bool())

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val emptyPromotion = Output(Bool())
  val sameCyclePromotion = Output(Bool())
  val livePromotionEnable = Output(Bool())
  val liveClearEnable = Output(Bool())
  val advanceLivePromotionEnable = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByPromotionDisabled = Output(Bool())
  val blockedByClearIntent = Output(Bool())
  val invalidClearIntentWithoutSlot = Output(Bool())
}

class LoadReplayReturnPipeW2PromotionControl extends Module {
  val io = IO(new LoadReplayReturnPipeW2PromotionControlIO)

  val active = io.enable && !io.flush
  val requestActive = active && io.promotionRequested
  val validClearIntent = io.slotOccupied && io.clearIntent
  val emptyPromotion = requestActive && !io.slotOccupied && !io.clearIntent
  val sameCyclePromotion = requestActive && validClearIntent
  val livePromotionEnable = emptyPromotion || sameCyclePromotion

  io.active := active
  io.requestActive := requestActive
  io.emptyPromotion := emptyPromotion
  io.sameCyclePromotion := sameCyclePromotion
  io.livePromotionEnable := livePromotionEnable
  io.liveClearEnable := sameCyclePromotion
  io.advanceLivePromotionEnable := livePromotionEnable
  io.blockedByDisabled := !io.enable && (io.clearIntent || io.writeCandidateValid)
  io.blockedByFlush := io.enable && io.flush && (io.clearIntent || io.writeCandidateValid)
  io.blockedByPromotionDisabled :=
    active && !io.promotionRequested && (io.clearIntent || io.writeCandidateValid)
  io.blockedByClearIntent := requestActive && io.slotOccupied && !io.clearIntent
  io.invalidClearIntentWithoutSlot := active && !io.slotOccupied && io.clearIntent
}
