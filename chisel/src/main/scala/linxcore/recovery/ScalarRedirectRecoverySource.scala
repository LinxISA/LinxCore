package linxcore.recovery

import chisel3._

import linxcore.bctrl.BID
import linxcore.rob.ROBID

class ScalarRedirectRecoveryEvent(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val orderWidth: Int = 64)
    extends Bundle {
  val valid = Bool()
  val blockBidValid = Bool()
  val blockBid = UInt(bidWidth.W)
  val bid = new ROBID(entries)
  val rid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val resolveLsIdValid = Bool()
  val stid = UInt(stidWidth.W)
  val peId = UInt(peIdWidth.W)
  val tid = UInt(tidWidth.W)
  val orderValid = Bool()
  val order = UInt(orderWidth.W)
}

class ScalarRedirectRecoverySourceIO(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val orderWidth: Int = 64)
    extends Bundle {
  val event = Input(new ScalarRedirectRecoveryEvent(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    orderWidth
  ))
  val eventReady = Output(Bool())
  val eventAccepted = Output(Bool())
  val source = Output(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val sourceReady = Input(Bool())
  val sourceAccepted = Output(Bool())
  val intentConsumed = Input(Bool())
  val cancel = Input(Bool())
  val pending = Output(Bool())
  val published = Output(Bool())
  val blockedByMissingIdentity = Output(Bool())
  val cleanupOrderValid = Output(Bool())
  val cleanupOrder = Output(UInt(orderWidth.W))
  val cleanupResolveLsIdValid = Output(Bool())
  val cleanupLsId = Output(new ROBID(entries))
}

/** Retains one scalar execute/marker redirect until central cleanup consumes it.
  *
  * The source publishes once into `RecoveryBackendControl`, then keeps order
  * and LSID sidecars stable for consumers that are not part of FlushReq.
  */
class ScalarRedirectRecoverySource(
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val orderWidth: Int = 64)
    extends Module {
  require(orderWidth > 0, "scalar redirect recovery order width must be positive")

  val io = IO(new ScalarRedirectRecoverySourceIO(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    orderWidth
  ))

  val retained = RegInit(0.U.asTypeOf(new ScalarRedirectRecoveryEvent(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    orderWidth
  )))
  val pending = RegInit(false.B)
  val published = RegInit(false.B)

  io.eventReady := !pending || io.intentConsumed
  io.eventAccepted := io.event.valid && io.eventReady && !io.cancel

  val source = WireDefault(0.U.asTypeOf(new FullBidFlushReq(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  )))
  val projectedBid = FullBidRecoveryBridge.fullBidToRobId(
    retained.blockBid,
    retained.blockBidValid,
    entries,
    bidWidth
  )
  val identityValid =
    retained.blockBidValid && retained.bid.valid && retained.rid.valid &&
      ROBID.equal(projectedBid, retained.bid)
  source.valid := pending && !published && identityValid && !io.cancel
  source.typ := FlushType.InnerFlush
  source.blockBid := retained.blockBid
  source.gid := ROBID.disabled(entries)
  source.rid := ROBID.inc(retained.rid)
  source.lsId := retained.lsId
  source.stid := retained.stid
  source.peId := retained.peId
  source.tid := retained.tid
  source.execEngine := ExecEngineType.Scalar
  source.fetchTpcValid := false.B
  source.immediateFlush := true.B
  io.source := source
  io.sourceAccepted := source.valid && io.sourceReady

  when(io.cancel) {
    retained := 0.U.asTypeOf(retained)
    pending := false.B
    published := false.B
  }.elsewhen(io.eventAccepted) {
    retained := io.event
    pending := true.B
    published := false.B
  }.elsewhen(io.intentConsumed) {
    retained := 0.U.asTypeOf(retained)
    pending := false.B
    published := false.B
  }.elsewhen(io.sourceAccepted) {
    published := true.B
  }

  io.pending := pending
  io.published := published
  io.blockedByMissingIdentity := pending && !identityValid
  io.cleanupOrderValid := pending && retained.orderValid
  io.cleanupOrder := retained.order
  io.cleanupResolveLsIdValid := pending && retained.resolveLsIdValid
  io.cleanupLsId := retained.lsId
}
