package linxcore.recovery

import chisel3._
import chisel3.util.{Queue, log2Ceil}

import linxcore.bctrl.BID
import linxcore.rob.ROBID

class RecoveryProducerQueueIO(
    val queueEntries: Int,
    val entries: Int,
    val bidWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val lsidWidth: Int = 32)
    extends Bundle {
  val in = Input(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val inReady = Output(Bool())
  val inAccepted = Output(Bool())
  val source = Output(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val sourceReady = Input(Bool())
  val sourceAccepted = Output(Bool())
  val count = Output(UInt(math.max(1, log2Ceil(queueEntries + 1)).W))
}

/** Finite retention at a recovery producer boundary.
  *
  * The queue stores the exact full-BID request supplied by the trigger owner.
  * It never reconstructs implementation generation identity from ROB ring bits.
  */
class RecoveryProducerQueue(
    val queueEntries: Int = 2,
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Module {
  require(queueEntries > 0, "recovery producer queue must contain at least one entry")

  val io = IO(new RecoveryProducerQueueIO(
    queueEntries,
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))

  val queue = Module(new Queue(
    new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth),
    queueEntries,
    pipe = false,
    flow = false
  ))
  queue.io.enq.valid := io.in.valid
  queue.io.enq.bits := io.in
  queue.io.deq.ready := io.sourceReady

  io.inReady := queue.io.enq.ready
  io.inAccepted := queue.io.enq.fire
  io.source := queue.io.deq.bits
  io.source.valid := queue.io.deq.valid
  io.sourceAccepted := queue.io.deq.fire
  io.count := queue.io.count
}

class BccMispredictRecoveryEvent(
    val entries: Int,
    val bidWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val lsidWidth: Int = 32)
    extends Bundle {
  val valid = Bool()
  val recoveryBlockBid = UInt(bidWidth.W)
  val stid = UInt(stidWidth.W)
  val peId = UInt(peIdWidth.W)
  val tid = UInt(tidWidth.W)
  val execEngine = ExecEngineType()
}

class BccRecoverySourceIO(
    val queueEntries: Int,
    val entries: Int,
    val bidWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val lsidWidth: Int = 32)
    extends Bundle {
  val miss = Input(new BccMispredictRecoveryEvent(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val missReady = Output(Bool())
  val missAccepted = Output(Bool())
  val source = Output(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val sourceReady = Input(Bool())
  val sourceAccepted = Output(Bool())
  val pendingCount = Output(UInt(math.max(1, log2Ceil(queueEntries + 1)).W))
}

/** BCC miss-predict publication for Linx block control.
  *
  * `recoveryBlockBid` is the exact first invalid block selected by the BCC
  * owner. The source does not derive it by incrementing a projected ROBID.
  */
class BccRecoverySource(
    val queueEntries: Int = 2,
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Module {
  val io = IO(new BccRecoverySourceIO(
    queueEntries,
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))

  val retained = Module(new RecoveryProducerQueue(
    queueEntries,
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))
  val req = WireDefault(0.U.asTypeOf(new FullBidFlushReq(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth)))
  req.valid := io.miss.valid
  req.typ := FlushType.MissPredFlush
  req.blockBid := io.miss.recoveryBlockBid
  req.stid := io.miss.stid
  req.peId := io.miss.peId
  req.tid := io.miss.tid
  req.execEngine := io.miss.execEngine

  retained.io.in := req
  retained.io.sourceReady := io.sourceReady
  io.missReady := retained.io.inReady
  io.missAccepted := retained.io.inAccepted
  io.source := retained.io.source
  io.sourceAccepted := retained.io.sourceAccepted
  io.pendingCount := retained.io.count
}

class IexRecoveryEvent(
    val entries: Int,
    val bidWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val lsidWidth: Int = 32)
    extends Bundle {
  val valid = Bool()
  val recoveryBlockBid = UInt(bidWidth.W)
  val gid = new ROBID(entries)
  val rid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val lsIdFullValid = Bool()
  val lsIdFull = UInt(lsidWidth.W)
  val stid = UInt(stidWidth.W)
  val peId = UInt(peIdWidth.W)
  val tid = UInt(tidWidth.W)
  val execEngine = ExecEngineType()
  val fetchTpcValid = Bool()
  val fetchTpc = UInt(64.W)
}

class IexRecoverySourceIO(
    val queueEntries: Int,
    val entries: Int,
    val bidWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val lsidWidth: Int = 32)
    extends Bundle {
  val event = Input(new IexRecoveryEvent(entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val eventReady = Output(Bool())
  val eventAccepted = Output(Bool())
  val source = Output(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val sourceReady = Input(Bool())
  val sourceAccepted = Output(Bool())
  val pendingCount = Output(UInt(math.max(1, log2Ceil(queueEntries + 1)).W))
}

/** IEX slow-insert publication. The event is a Linx nuke, not an ARM
  * exception or exception-return operation.
  */
class IexSlowInsertRecoverySource(
    val queueEntries: Int = 2,
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Module {
  val io = IO(new IexRecoverySourceIO(
    queueEntries, entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val retained = Module(new RecoveryProducerQueue(
    queueEntries, entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val req = WireDefault(0.U.asTypeOf(new FullBidFlushReq(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth)))
  req.valid := io.event.valid
  req.typ := FlushType.NukeFlush
  req.blockBid := io.event.recoveryBlockBid
  req.gid := io.event.gid
  req.rid := io.event.rid
  req.lsId := io.event.lsId
  req.lsIdFullValid := io.event.lsIdFullValid
  req.lsIdFull := io.event.lsIdFull
  req.stid := io.event.stid
  req.peId := io.event.peId
  req.tid := io.event.tid
  req.execEngine := io.event.execEngine
  req.fetchTpcValid := io.event.fetchTpcValid
  req.fetchTpc := io.event.fetchTpc
  req.immediateFlush := true.B

  retained.io.in := req
  retained.io.sourceReady := io.sourceReady
  io.eventReady := retained.io.inReady
  io.eventAccepted := retained.io.inAccepted
  io.source := retained.io.source
  io.sourceAccepted := retained.io.sourceAccepted
  io.pendingCount := retained.io.count
}

class IexIqStallRecoverySourceIO(
    val queueEntries: Int,
    val entries: Int,
    val bidWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val stallThreshold: Int,
    val lsidWidth: Int = 32)
    extends Bundle {
  val stalled = Input(Bool())
  val progress = Input(Bool())
  val oldestBlockComplete = Input(Bool())
  val identityValid = Input(Bool())
  val recoveryBlockBid = Input(UInt(bidWidth.W))
  val stid = Input(UInt(stidWidth.W))
  val peId = Input(UInt(peIdWidth.W))
  val tid = Input(UInt(tidWidth.W))
  val source = Output(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val sourceReady = Input(Bool())
  val sourceAccepted = Output(Bool())
  val triggerCaptured = Output(Bool())
  val blockedByMissingIdentity = Output(Bool())
  val stallCount = Output(UInt(math.max(1, log2Ceil(stallThreshold + 1)).W))
  val pendingCount = Output(UInt(math.max(1, log2Ceil(queueEntries + 1)).W))
}

/** Per-STID IEX watchdog. It emits the exact next-block identity supplied by
  * the oldest-block owner after a configurable consecutive no-progress window.
  */
class IexIqStallRecoverySource(
    val stallThreshold: Int = 64,
    val queueEntries: Int = 2,
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Module {
  require(stallThreshold > 0, "IEX IQ stall threshold must be positive")

  private val stallCountWidth = math.max(1, log2Ceil(stallThreshold + 1))
  val io = IO(new IexIqStallRecoverySourceIO(
    queueEntries,
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    stallThreshold,
    lsidWidth
  ))

  val retained = Module(new RecoveryProducerQueue(
    queueEntries, entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val stallCount = RegInit(0.U(stallCountWidth.W))
  val capturePending = RegInit(false.B)
  val captured = RegInit(0.U.asTypeOf(new FullBidFlushReq(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth)))
  val waiting = io.stalled && !io.progress && !io.oldestBlockComplete
  val thresholdReached = waiting && (stallCount === (stallThreshold - 1).U)
  val capture = thresholdReached && io.identityValid && !capturePending

  when(!waiting || io.progress || io.oldestBlockComplete) {
    stallCount := 0.U
  }.elsewhen(capture) {
    stallCount := 0.U
  }.elsewhen(!capturePending && stallCount < (stallThreshold - 1).U) {
    stallCount := stallCount + 1.U
  }

  when(capture) {
    captured := 0.U.asTypeOf(captured)
    captured.valid := true.B
    captured.typ := FlushType.FastReplay
    captured.blockBid := io.recoveryBlockBid
    captured.stid := io.stid
    captured.peId := io.peId
    captured.tid := io.tid
    captured.execEngine := ExecEngineType.Scalar
    capturePending := true.B
  }.elsewhen(retained.io.inAccepted) {
    capturePending := false.B
  }

  retained.io.in := captured
  retained.io.in.valid := capturePending
  retained.io.sourceReady := io.sourceReady
  io.source := retained.io.source
  io.sourceAccepted := retained.io.sourceAccepted
  io.triggerCaptured := capture
  io.blockedByMissingIdentity := thresholdReached && !io.identityValid
  io.stallCount := stallCount
  io.pendingCount := retained.io.count
}

class PeMismatchRecoverySourceIO(
    val queueEntries: Int,
    val entries: Int,
    val bidWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val lsidWidth: Int = 32)
    extends Bundle {
  val mismatch = Input(new IexRecoveryEvent(entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val mismatchReady = Output(Bool())
  val mismatchAccepted = Output(Bool())
  val source = Output(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val sourceReady = Input(Bool())
  val sourceAccepted = Output(Bool())
  val pendingCount = Output(UInt(math.max(1, log2Ceil(queueEntries + 1)).W))
}

/** PE destination/stack mismatch publication. The compare owner must supply
  * exact Linx block and ROB identity; this adapter supplies no architectural
  * state or prediction semantics of its own.
  */
class PeMismatchRecoverySource(
    val queueEntries: Int = 2,
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Module {
  val io = IO(new PeMismatchRecoverySourceIO(
    queueEntries, entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val retained = Module(new RecoveryProducerQueue(
    queueEntries, entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val req = WireDefault(0.U.asTypeOf(new FullBidFlushReq(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth)))
  req.valid := io.mismatch.valid
  req.typ := FlushType.InnerFlush
  req.blockBid := io.mismatch.recoveryBlockBid
  req.gid := io.mismatch.gid
  req.rid := io.mismatch.rid
  req.lsId := io.mismatch.lsId
  req.lsIdFullValid := io.mismatch.lsIdFullValid
  req.lsIdFull := io.mismatch.lsIdFull
  req.stid := io.mismatch.stid
  req.peId := io.mismatch.peId
  req.tid := io.mismatch.tid
  req.execEngine := io.mismatch.execEngine
  req.fetchTpcValid := io.mismatch.fetchTpcValid
  req.fetchTpc := io.mismatch.fetchTpc

  retained.io.in := req
  retained.io.sourceReady := io.sourceReady
  io.mismatchReady := retained.io.inReady
  io.mismatchAccepted := retained.io.inAccepted
  io.source := retained.io.source
  io.sourceAccepted := retained.io.sourceAccepted
  io.pendingCount := retained.io.count
}
