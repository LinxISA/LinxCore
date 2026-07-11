package linxcore.recovery

import chisel3._
import chisel3.util.{log2Ceil, Mux1H}

import linxcore.bctrl.BID

object RecoveryNonLsuProducerBank {
  val BccSource: Int = 0
  val IexSlowSource: Int = 1
  val IexIqStallSource: Int = 2
  val PeMismatchSource: Int = 3
  val SourceCount: Int = 4
}

class IexIqStallRecoveryIdentityIO(
    val stidCount: Int,
    val bidWidth: Int,
    val stidWidth: Int)
    extends Bundle {
  val stid = Input(UInt(stidWidth.W))
  val oldestValid = Input(Vec(stidCount, Bool()))
  val oldestBlockComplete = Input(Vec(stidCount, Bool()))
  val blockCommitPointer = Input(Vec(stidCount, UInt(bidWidth.W)))
  val stidInRange = Output(Bool())
  val identityValid = Output(Bool())
  val selectedOldestBlockComplete = Output(Bool())
  val recoveryBlockBid = Output(UInt(bidWidth.W))
}

/** Resolves the model IQ-watchdog replay identity from authoritative BROB state. */
class IexIqStallRecoveryIdentity(
    val stidCount: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8)
    extends Module {
  require(stidCount > 0, "watchdog identity requires at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "watchdog STID count must fit stidWidth")

  val io = IO(new IexIqStallRecoveryIdentityIO(stidCount, bidWidth, stidWidth))
  val stidMatch = VecInit((0 until stidCount).map(stid => io.stid === stid.U))
  val selectedOldestValid = Mux1H(stidMatch, io.oldestValid)
  val selectedOldestComplete = Mux1H(stidMatch, io.oldestBlockComplete)
  val selectedCommitPointer = Mux1H(stidMatch, io.blockCommitPointer)

  io.stidInRange := stidMatch.asUInt.orR
  io.identityValid := io.stidInRange && selectedOldestValid
  io.selectedOldestBlockComplete :=
    !io.stidInRange || !selectedOldestValid || selectedOldestComplete
  io.recoveryBlockBid := selectedCommitPointer + 1.U
}

class RecoveryNonLsuProducerBankIO(
    val queueEntries: Int,
    val stallThreshold: Int,
    val entries: Int,
    val bidWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int)
    extends Bundle {
  import RecoveryNonLsuProducerBank._

  val bccMiss = Input(new BccMispredictRecoveryEvent(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val bccReady = Output(Bool())
  val bccAccepted = Output(Bool())

  val iexSlow = Input(new IexRecoveryEvent(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val iexSlowReady = Output(Bool())
  val iexSlowAccepted = Output(Bool())

  val iexIqStalled = Input(Bool())
  val iexIqProgress = Input(Bool())
  val iexIqOldestBlockComplete = Input(Bool())
  val iexIqIdentityValid = Input(Bool())
  val iexIqRecoveryBlockBid = Input(UInt(bidWidth.W))
  val iexIqStid = Input(UInt(stidWidth.W))
  val iexIqPeId = Input(UInt(peIdWidth.W))
  val iexIqTid = Input(UInt(tidWidth.W))
  val iexIqTriggerCaptured = Output(Bool())
  val iexIqBlockedByMissingIdentity = Output(Bool())
  val iexIqStallCount = Output(UInt(math.max(1, log2Ceil(stallThreshold + 1)).W))

  val peMismatch = Input(new IexRecoveryEvent(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val peMismatchReady = Output(Bool())
  val peMismatchAccepted = Output(Bool())

  val sources = Output(Vec(SourceCount,
    new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth)))
  val sourceReady = Input(Vec(SourceCount, Bool()))
  val sourceAccepted = Output(Vec(SourceCount, Bool()))
  val pendingMask = Output(UInt(SourceCount.W))
}

/** Production bank for the model-derived non-LSU recovery families.
  *
  * The bank owns finite retention and request typing. Trigger owners must supply
  * exact Linx full-pointer identity; no source derives generation from a ring
  * slot, and the bank introduces no foreign architectural state.
  */
class RecoveryNonLsuProducerBank(
    val queueEntries: Int = 2,
    val stallThreshold: Int = 64,
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  import RecoveryNonLsuProducerBank._

  val io = IO(new RecoveryNonLsuProducerBankIO(
    queueEntries,
    stallThreshold,
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

  val bcc = Module(new BccRecoverySource(
    queueEntries, entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val slow = Module(new IexSlowInsertRecoverySource(
    queueEntries, entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val stall = Module(new IexIqStallRecoverySource(
    stallThreshold, queueEntries, entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val pe = Module(new PeMismatchRecoverySource(
    queueEntries, entries, bidWidth, peIdWidth, stidWidth, tidWidth))

  bcc.io.miss := io.bccMiss
  slow.io.event := io.iexSlow
  stall.io.stalled := io.iexIqStalled
  stall.io.progress := io.iexIqProgress
  stall.io.oldestBlockComplete := io.iexIqOldestBlockComplete
  stall.io.identityValid := io.iexIqIdentityValid
  stall.io.recoveryBlockBid := io.iexIqRecoveryBlockBid
  stall.io.stid := io.iexIqStid
  stall.io.peId := io.iexIqPeId
  stall.io.tid := io.iexIqTid
  pe.io.mismatch := io.peMismatch

  io.sources(BccSource) := bcc.io.source
  io.sources(IexSlowSource) := slow.io.source
  io.sources(IexIqStallSource) := stall.io.source
  io.sources(PeMismatchSource) := pe.io.source
  bcc.io.sourceReady := io.sourceReady(BccSource)
  slow.io.sourceReady := io.sourceReady(IexSlowSource)
  stall.io.sourceReady := io.sourceReady(IexIqStallSource)
  pe.io.sourceReady := io.sourceReady(PeMismatchSource)

  io.bccReady := bcc.io.missReady
  io.bccAccepted := bcc.io.missAccepted
  io.iexSlowReady := slow.io.eventReady
  io.iexSlowAccepted := slow.io.eventAccepted
  io.iexIqTriggerCaptured := stall.io.triggerCaptured
  io.iexIqBlockedByMissingIdentity := stall.io.blockedByMissingIdentity
  io.iexIqStallCount := stall.io.stallCount
  io.peMismatchReady := pe.io.mismatchReady
  io.peMismatchAccepted := pe.io.mismatchAccepted
  io.sourceAccepted := VecInit(
    bcc.io.sourceAccepted,
    slow.io.sourceAccepted,
    stall.io.sourceAccepted,
    pe.io.sourceAccepted
  )
  io.pendingMask := VecInit(
    bcc.io.pendingCount.orR,
    slow.io.pendingCount.orR,
    stall.io.pendingCount.orR,
    pe.io.pendingCount.orR
  ).asUInt
}
