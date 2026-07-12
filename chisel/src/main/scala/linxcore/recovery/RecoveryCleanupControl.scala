package linxcore.recovery

import chisel3._

import linxcore.bctrl.BID

class RecoveryCleanupIntent(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val blockBidWidth = BID.slotBits(entries)

  val valid = Bool()
  val flush = new FlushBus(entries, peIdWidth, stidWidth, tidWidth, lsidWidth)
  val blockFlushValid = Bool()
  val blockFlushBid = UInt(blockBidWidth.W)
  val blockFlushPointerValid = Bool()
  val blockFlushPointer = UInt(bidWidth.W)
  val blockFlushInclusive = Bool()
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
    val tidWidth: Int = 8,
    val sourceCount: Int = 1,
    val lsidWidth: Int = 32)
    extends Bundle {
  val req = Input(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val reqProvenance = Input(new RecoveryProvenance(sourceCount))
  val reqReady = Output(Bool())
  val ringReq = Input(new FlushBus(entries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val ringReqReady = Output(Bool())
  val intentReady = Input(Bool())
  val intent = Output(new RecoveryCleanupIntent(entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val pending = Output(Bool())
  val accepted = Output(Bool())
  val fullAccepted = Output(Bool())
  val ringAccepted = Output(Bool())
  val consumed = Output(Bool())
  val intentProvenance = Output(new RecoveryProvenance(sourceCount))
  val consumedProvenanceMask = Output(UInt(sourceCount.W))
  val consumedPayloadSourceMask = Output(UInt(sourceCount.W))
}

class RecoveryCleanupControl(
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sourceCount: Int = 1,
    val lsidWidth: Int = 32)
    extends Module {
  val io = IO(new RecoveryCleanupControlIO(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    sourceCount,
    lsidWidth
  ))

  val pendingReq = RegInit(0.U.asTypeOf(new FullBidFlushReq(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth)))
  val pendingRingReq = RegInit(0.U.asTypeOf(new FlushBus(
    entries, peIdWidth, stidWidth, tidWidth, lsidWidth)))
  val pendingIsRing = RegInit(false.B)
  val pendingValid = RegInit(false.B)
  val pendingProvenance = RegInit(0.U.asTypeOf(new RecoveryProvenance(sourceCount)))

  val sourceReady = !pendingValid || io.intentReady
  io.reqReady := sourceReady
  io.ringReqReady := sourceReady && !io.req.valid
  io.fullAccepted := io.req.valid && sourceReady
  io.ringAccepted := io.ringReq.req.valid && io.ringReqReady
  io.accepted := io.fullAccepted || io.ringAccepted
  io.consumed := pendingValid && io.intentReady
  io.pending := pendingValid
  io.intentProvenance := pendingProvenance
  io.consumedProvenanceMask := Mux(io.consumed, pendingProvenance.causeMask, 0.U)
  io.consumedPayloadSourceMask := Mux(
    io.consumed && pendingProvenance.payloadSourceValid,
    RecoveryProvenance.oneHot(pendingProvenance.payloadSource, sourceCount),
    0.U
  )

  when(io.fullAccepted) {
    pendingReq := io.req
    pendingProvenance := io.reqProvenance
    pendingIsRing := false.B
    pendingValid := true.B
  }.elsewhen(io.ringAccepted) {
    pendingRingReq := io.ringReq
    pendingIsRing := true.B
    pendingProvenance := 0.U.asTypeOf(pendingProvenance)
    pendingValid := true.B
  }.elsewhen(io.intentReady) {
    pendingValid := false.B
  }

  val bridgeReq = Wire(new FullBidFlushReq(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  bridgeReq := pendingReq
  bridgeReq.valid := pendingValid && !pendingIsRing

  val bridge = Module(new FullBidRecoveryBridge(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  bridge.io.req := bridgeReq

  val activeFlush = Wire(new FlushBus(entries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  activeFlush := bridge.io.robFlush
  when(pendingIsRing) {
    activeFlush := pendingRingReq
  }

  val requestIsFlush = FlushControl.isFlushType(activeFlush.req.typ)
  val peScoped = activeFlush.baseOnPE || activeFlush.baseOnThread
  val globalFlush = pendingValid && requestIsFlush && !peScoped
  val globalReplay = pendingValid && !requestIsFlush && !peScoped
  val peScopedReplay = pendingValid && peScoped
  val peSingleFanout = pendingValid && (activeFlush.simtReplay || activeFlush.mtcReplay || activeFlush.baseOnPE)
  val simtRecovered =
    peScopedReplay && (activeFlush.simtReplay || activeFlush.mtcReplay) &&
      (activeFlush.req.typ =/= FlushType.SimtInnerFlush)

  io.intent.valid := pendingValid
  io.intent.flush := activeFlush
  io.intent.blockFlushValid := globalFlush && !pendingIsRing
  io.intent.blockFlushBid := BID.slot(bridge.io.blockFlushBid, entries)
  io.intent.blockFlushPointerValid := pendingValid && !pendingIsRing
  io.intent.blockFlushPointer := bridge.io.blockFlushBid
  io.intent.blockFlushInclusive :=
    globalFlush && !pendingIsRing && (pendingReq.typ === FlushType.MissPredFlush)
  io.intent.robPruneValid := pendingValid
  io.intent.bctrlFlushValid := globalFlush && !pendingIsRing
  io.intent.bctrlReplayValid := globalReplay || peScopedReplay
  io.intent.bctrlSimtRecoveredValid := simtRecovered
  io.intent.renameFlushValid := globalFlush && !pendingIsRing
  io.intent.renameReplayValid := globalReplay || peScopedReplay
  io.intent.backendFlushValid := pendingValid
  io.intent.reportQueueFlushValid := pendingValid
  io.intent.frontendRestartValid := globalFlush
  io.intent.peFanoutAll := pendingValid && !peSingleFanout
  io.intent.peFanoutSingle := peSingleFanout
  io.intent.peFanoutId := activeFlush.req.peId
  io.intent.vectorReplayValid := pendingValid && activeFlush.simtReplay
  io.intent.vectorFlushValid := pendingValid && !activeFlush.simtReplay
  io.intent.mtcReplayValid := pendingValid && activeFlush.mtcReplay
  io.intent.mtcFlushValid := pendingValid && !activeFlush.simtReplay && !activeFlush.mtcReplay
  io.intent.lsuFlushValid := pendingValid
  io.intent.stqFlushValid := pendingValid
  io.intent.tileFlushValid := pendingValid
  io.intent.globalFlush := globalFlush
  io.intent.globalReplay := globalReplay
  io.intent.peScopedReplay := peScopedReplay
}
