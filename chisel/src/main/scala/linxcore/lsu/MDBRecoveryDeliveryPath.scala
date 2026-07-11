package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, Queue}

import linxcore.recovery.{ExecEngineType, FlushControl, FlushReq, FlushType, FullBidFlushReq}
import linxcore.rob.{ROBFullBidLookupRequest, ROBFullBidLookupResult, ROBID}

class MDBRecoveryDeliveryPathIO(
    val entries: Int,
    val recoveryQueueEntries: Int,
    val stidCount: Int,
    val bidWidth: Int,
    val addrWidth: Int,
    val pcWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val sizeWidth: Int)
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val candidateValid = Input(Bool())
  val conflictValid = Input(Bool())
  val nukeFlush = Input(Bool())
  val record = Input(new MDBConflictRecord(
    entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth))
  val recordReady = Input(Bool())

  val candidateAccepted = Output(Bool())
  val recordValid = Output(Bool())
  val recoveryPending = Output(Bool())
  val recoveryCount = Output(UInt(log2Ceil(recoveryQueueEntries + 1).W))
  val recoveryStidInRange = Output(Bool())

  val oldestValid = Input(Vec(stidCount, Bool()))
  val oldestBid = Input(Vec(stidCount, new ROBID(entries)))
  val oldestRid = Input(Vec(stidCount, new ROBID(entries)))
  val fullBidLookupRequest = Output(new ROBFullBidLookupRequest(entries, peIdWidth, stidWidth, tidWidth))
  val fullBidLookup = Input(new ROBFullBidLookupResult(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val source = Output(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val sourceReady = Input(Bool())
  val sourceAccepted = Output(Bool())
}

/** Retains an MDB recovery report atomically with its conflict record until promotion is accepted. */
class MDBRecoveryDeliveryPath(
    val entries: Int = 16,
    val recoveryQueueEntries: Int = 8,
    val stidCount: Int = 1,
    val bidWidth: Int = 64,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 7)
    extends Module {
  require(recoveryQueueEntries > 1 && (recoveryQueueEntries & (recoveryQueueEntries - 1)) == 0,
    "MDB recovery queue depth must be a power of two greater than one")
  require(stidCount > 0, "MDB recovery delivery must expose at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "MDB recovery STID count must fit stidWidth")

  val io = IO(new MDBRecoveryDeliveryPathIO(
    entries, recoveryQueueEntries, stidCount, bidWidth, addrWidth, pcWidth,
    peIdWidth, stidWidth, tidWidth, sizeWidth))

  val flushReq = Wire(new FlushReq(entries, peIdWidth, stidWidth, tidWidth))
  flushReq := 0.U.asTypeOf(flushReq)
  flushReq.valid := io.conflictValid
  flushReq.typ := Mux(io.nukeFlush, FlushType.NukeFlush, FlushType.InnerFlush)
  flushReq.peId := io.record.load.peId
  flushReq.tid := io.record.load.tid
  flushReq.stid := io.record.load.stid
  flushReq.bid := io.record.load.bid
  flushReq.gid := io.record.load.gid
  flushReq.rid := io.record.load.rid
  flushReq.lsId := io.record.load.lsId
  flushReq.execEngine := ExecEngineType.Scalar
  flushReq.fetchTpcValid := true.B
  flushReq.fetchTpc := io.record.load.pc
  flushReq.immediateFlush := false.B

  val recoveryQ = withReset(reset.asBool || io.flush || !io.enable) {
    Module(new Queue(new FlushReq(entries, peIdWidth, stidWidth, tidWidth), recoveryQueueEntries))
  }
  val transaction = Module(new MDBConflictTransactionControl)
  transaction.io.enable := io.enable && !io.flush
  transaction.io.candidateValid := io.candidateValid
  transaction.io.recordRequired := io.conflictValid
  transaction.io.waitPlanRequired := false.B
  transaction.io.recoveryRequired := io.conflictValid
  transaction.io.recordReady := io.recordReady
  transaction.io.waitPlanReady := true.B
  transaction.io.recoveryReady := recoveryQ.io.enq.ready

  recoveryQ.io.enq.valid := transaction.io.recoveryValid
  recoveryQ.io.enq.bits := flushReq

  val pending = FlushControl.annotate(recoveryQ.io.deq.bits)
  pending.req.valid := recoveryQ.io.deq.valid
  val recoveryBoundary = Module(new ScalarLSURecoveryBoundary(
    entries, stidCount, bidWidth, peIdWidth, stidWidth, tidWidth))
  recoveryBoundary.io.ringReq := pending
  recoveryBoundary.io.oldestValid := io.oldestValid
  recoveryBoundary.io.oldestBid := io.oldestBid
  recoveryBoundary.io.oldestRid := io.oldestRid
  recoveryBoundary.io.fullBidLookup := io.fullBidLookup
  recoveryBoundary.io.sourceReady := io.sourceReady
  recoveryQ.io.deq.ready := recoveryBoundary.io.ringReqReady && io.enable && !io.flush

  io.candidateAccepted := transaction.io.accepted
  io.recordValid := transaction.io.recordValid
  io.recoveryPending := recoveryQ.io.deq.valid
  io.recoveryCount := recoveryQ.io.count
  io.recoveryStidInRange := recoveryBoundary.io.stidInRange
  io.fullBidLookupRequest := recoveryBoundary.io.fullBidLookupRequest
  io.source := recoveryBoundary.io.source
  io.sourceAccepted := recoveryBoundary.io.sourceAccepted
}
