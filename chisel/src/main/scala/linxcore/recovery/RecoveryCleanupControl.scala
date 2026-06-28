package linxcore.recovery

import chisel3._

import linxcore.bctrl.BID

class RecoveryCleanupIntent(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val flush = new FlushBus(entries, peIdWidth, stidWidth, tidWidth)
  val blockFlushValid = Bool()
  val blockFlushBid = UInt(bidWidth.W)
  val robPruneValid = Bool()
  val bctrlFlushValid = Bool()
  val bctrlReplayValid = Bool()
  val bctrlSimtRecoveredValid = Bool()
  val renameFlushValid = Bool()
  val renameReplayValid = Bool()
  val backendFlushValid = Bool()
  val reportQueueFlushValid = Bool()
  val frontendRestartValid = Bool()
  val peFanoutAll = Bool()
  val peFanoutSingle = Bool()
  val peFanoutId = UInt(peIdWidth.W)
  val vectorReplayValid = Bool()
  val vectorFlushValid = Bool()
  val mtcReplayValid = Bool()
  val mtcFlushValid = Bool()
  val lsuFlushValid = Bool()
  val stqFlushValid = Bool()
  val tileFlushValid = Bool()
  val globalFlush = Bool()
  val globalReplay = Bool()
  val peScopedReplay = Bool()
}

class RecoveryCleanupControlIO(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val req = Input(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val reqReady = Output(Bool())
  val intentReady = Input(Bool())
  val intent = Output(new RecoveryCleanupIntent(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val pending = Output(Bool())
  val accepted = Output(Bool())
  val consumed = Output(Bool())
}

class RecoveryCleanupControl(
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  val io = IO(new RecoveryCleanupControlIO(entries, bidWidth, peIdWidth, stidWidth, tidWidth))

  val pendingReq = RegInit(0.U.asTypeOf(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth)))
  val pendingValid = RegInit(false.B)

  io.reqReady := !pendingValid || io.intentReady
  io.accepted := io.req.valid && io.reqReady
  io.consumed := pendingValid && io.intentReady
  io.pending := pendingValid

  when(io.accepted) {
    pendingReq := io.req
    pendingValid := true.B
  }.elsewhen(io.intentReady) {
    pendingValid := false.B
  }

  val bridgeReq = Wire(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  bridgeReq := pendingReq
  bridgeReq.valid := pendingValid

  val bridge = Module(new FullBidRecoveryBridge(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  bridge.io.req := bridgeReq

  val requestIsFlush = FlushControl.isFlushType(bridge.io.robFlush.req.typ)
  val peScoped = bridge.io.robFlush.baseOnPE || bridge.io.robFlush.baseOnThread
  val globalFlush = pendingValid && requestIsFlush && !peScoped
  val globalReplay = pendingValid && !requestIsFlush && !peScoped
  val peScopedReplay = pendingValid && peScoped
  val peSingleFanout = pendingValid && (bridge.io.robFlush.simtReplay || bridge.io.robFlush.mtcReplay || bridge.io.robFlush.baseOnPE)
  val simtRecovered =
    peScopedReplay && (bridge.io.robFlush.simtReplay || bridge.io.robFlush.mtcReplay) &&
      (bridge.io.robFlush.req.typ =/= FlushType.SimtInnerFlush)

  io.intent.valid := pendingValid
  io.intent.flush := bridge.io.robFlush
  io.intent.blockFlushValid := globalFlush
  io.intent.blockFlushBid := bridge.io.blockFlushBid
  io.intent.robPruneValid := pendingValid
  io.intent.bctrlFlushValid := globalFlush
  io.intent.bctrlReplayValid := globalReplay || peScopedReplay
  io.intent.bctrlSimtRecoveredValid := simtRecovered
  io.intent.renameFlushValid := globalFlush
  io.intent.renameReplayValid := globalReplay || peScopedReplay
  io.intent.backendFlushValid := pendingValid
  io.intent.reportQueueFlushValid := pendingValid
  io.intent.frontendRestartValid := globalFlush
  io.intent.peFanoutAll := pendingValid && !peSingleFanout
  io.intent.peFanoutSingle := peSingleFanout
  io.intent.peFanoutId := bridge.io.robFlush.req.peId
  io.intent.vectorReplayValid := pendingValid && bridge.io.robFlush.simtReplay
  io.intent.vectorFlushValid := pendingValid && !bridge.io.robFlush.simtReplay
  io.intent.mtcReplayValid := pendingValid && bridge.io.robFlush.mtcReplay
  io.intent.mtcFlushValid := pendingValid && !bridge.io.robFlush.simtReplay && !bridge.io.robFlush.mtcReplay
  io.intent.lsuFlushValid := pendingValid
  io.intent.stqFlushValid := pendingValid
  io.intent.tileFlushValid := pendingValid
  io.intent.globalFlush := globalFlush
  io.intent.globalReplay := globalReplay
  io.intent.peScopedReplay := peScopedReplay
}
