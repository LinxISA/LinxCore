package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, Mux1H, PopCount}

import linxcore.common.LSIDOrder
import linxcore.rob.ROBID

class STQCommitQueueEntry(
    val robEntries: Int,
    val stqEntries: Int,
    val lsidWidth: Int = 32,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val stqIndex = UInt(log2Ceil(stqEntries).W)
  val leaseGeneration = UInt(leaseGenerationWidth.W)
  val stid = UInt(stidWidth.W)
  val bid = new ROBID(robEntries)
  val lsId = UInt(lsidWidth.W)
  val storeIdValid = Bool()
  val storeId = UInt(lsidWidth.W)
  val exactOwner = new STQExactOwner(
    peIdWidth, stidWidth, nativeBidWidth, log2Ceil(robEntries),
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth)
}

class STQCommitIssue(
    robEntries: Int,
    stqEntries: Int,
    lsidWidth: Int = 32,
    peIdWidth: Int = 8,
    stidWidth: Int = 8,
    nativeBidWidth: Int = 8,
    ridGenerationWidth: Int = 8,
    brobGenerationWidth: Int = 8,
    memberIndexWidth: Int = 8,
    residentGenerationWidth: Int = 8,
    leaseGenerationWidth: Int = 8)
    extends STQCommitQueueEntry(
      robEntries, stqEntries, lsidWidth, peIdWidth, stidWidth, nativeBidWidth,
      ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
      residentGenerationWidth, leaseGenerationWidth)

class STQCommitQueueIO(
    val robEntries: Int,
    val stqEntries: Int,
    val queueEntries: Int,
    val issueWidth: Int,
    val lsidWidth: Int = 32,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8)
    extends Bundle {
  private val countWidth = log2Ceil(queueEntries + 1)

  val enqueueValid = Input(Bool())
  val enqueueIndex = Input(UInt(log2Ceil(stqEntries).W))
  val enqueueLeaseGeneration = Input(UInt(leaseGenerationWidth.W))
  val enqueueStid = Input(UInt(stidWidth.W))
  val enqueueBid = Input(new ROBID(robEntries))
  val enqueueLsId = Input(UInt(lsidWidth.W))
  val enqueueStoreIdValid = Input(Bool())
  val enqueueStoreId = Input(UInt(lsidWidth.W))
  val enqueueExactOwner = Input(new STQExactOwner(
    peIdWidth, stidWidth, nativeBidWidth, log2Ceil(robEntries),
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth))
  /** Architectural reset/abort only. Ordinary branch recovery must not clear
    * committed/non-flush tokens.
    */
  val flushValid = Input(Bool())
  val enqueueReady = Output(Bool())
  val enqueueAccepted = Output(Bool())
  val enqueueDuplicate = Output(Bool())
  val enqueueMalformed = Output(Bool())
  val enqueueInsertPosition = Output(UInt(countWidth.W))

  val issueEnable = Input(Bool())
  /** Readiness is qualified per queue token, after the drain owner revalidates
    * its exact STQ lease and semantic identity against the canonical row.
    */
  val readyMask = Input(UInt(queueEntries.W))
  val issue = Output(Vec(issueWidth, new STQCommitIssue(
    robEntries, stqEntries, lsidWidth, peIdWidth, stidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth)))
  val issueValidMask = Output(UInt(issueWidth.W))
  val issueCount = Output(UInt(log2Ceil(issueWidth + 1).W))

  val queued = Output(Vec(queueEntries, new STQCommitQueueEntry(
    robEntries, stqEntries, lsidWidth, peIdWidth, stidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth)))
  val queuedValidMask = Output(UInt(queueEntries.W))
  val queueCount = Output(UInt(countWidth.W))
  val empty = Output(Bool())
  val full = Output(Bool())
  val orderError = Output(Bool())
}

object STQCommitQueue {
  def lessEqualBidLs(
      srcBid: ROBID,
      srcLsId: ROBID,
      dstBid: ROBID,
      dstLsId: ROBID): Bool =
    ROBID.less(srcBid, dstBid) ||
      (ROBID.equal(srcBid, dstBid) && ROBID.lessEqual(srcLsId, dstLsId))

  def lessEqualBidLs(
      srcBid: ROBID,
      srcLsId: UInt,
      dstBid: ROBID,
      dstLsId: UInt): Bool =
    ROBID.less(srcBid, dstBid) ||
      (ROBID.equal(srcBid, dstBid) && LSIDOrder.lessEqual(srcLsId, dstLsId))
}

/** Exact committed-store token owner.
  *
  * Queue position is storage only. Per-STID full store-ID order determines
  * eligibility, and full LSID order must agree. A ready younger store never
  * bypasses an older stalled store in the same STID; peer STIDs may advance.
  */
class STQCommitQueue(
    val robEntries: Int = 16,
    val stqEntries: Int = 16,
    val queueEntries: Int = 16,
    val issueWidth: Int = 2,
    val lsidWidth: Int = 32,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8)
    extends Module {
  require(robEntries > 1, "ROB entries must be greater than one")
  require(stqEntries > 1, "STQ entries must be greater than one")
  require(queueEntries > 1, "STQ commit queue entries must be greater than one")
  require(issueWidth > 0, "STQ commit issue width must be nonzero")
  require(issueWidth <= queueEntries,
    "STQ commit issue width cannot exceed queue depth")
  require((robEntries & (robEntries - 1)) == 0,
    "ROB entries must be a power of two")
  require((stqEntries & (stqEntries - 1)) == 0,
    "STQ entries must be a power of two")
  require((queueEntries & (queueEntries - 1)) == 0,
    "STQ commit queue entries must be a power of two")
  require(lsidWidth >= 2,
    "LSID width must support modular serial ordering")
  require(BigInt(queueEntries) < (BigInt(1) << (lsidWidth - 1)),
    "live commit tokens must fit within half of the serial namespace")

  private val countWidth = log2Ceil(queueEntries + 1)
  private type Entry = STQCommitQueueEntry

  val io = IO(new STQCommitQueueIO(
    robEntries, stqEntries, queueEntries, issueWidth, lsidWidth, peIdWidth,
    stidWidth, nativeBidWidth, ridGenerationWidth, brobGenerationWidth,
    memberIndexWidth, residentGenerationWidth, leaseGenerationWidth))

  private def newEntry: Entry = new STQCommitQueueEntry(
    robEntries, stqEntries, lsidWidth, peIdWidth, stidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth)

  private def zeroEntry: Entry = {
    val entry = Wire(newEntry)
    entry := 0.U.asTypeOf(entry)
    entry
  }

  private def serialOlder(left: UInt, right: UInt): Bool = {
    val delta = (right - left)(lsidWidth - 1, 0)
    delta.orR && !delta(lsidWidth - 1)
  }

  private def shapeExact(entry: Entry): Bool =
    entry.valid && entry.bid.valid && entry.storeIdValid &&
      entry.exactOwner.valid && entry.exactOwner.nativeBidValid &&
      entry.exactOwner.stid === entry.stid

  private def sameOwner(left: Entry, right: Entry): Bool =
    left.exactOwner.asUInt === right.exactOwner.asUInt

  private def sameLease(left: Entry, right: Entry): Bool =
    left.stqIndex === right.stqIndex &&
      left.leaseGeneration === right.leaseGeneration

  private def enqueueEntry: Entry = {
    val entry = Wire(newEntry)
    entry := 0.U.asTypeOf(entry)
    entry.valid := true.B
    entry.stqIndex := io.enqueueIndex
    entry.leaseGeneration := io.enqueueLeaseGeneration
    entry.stid := io.enqueueStid
    entry.bid := io.enqueueBid
    entry.lsId := io.enqueueLsId
    entry.storeIdValid := io.enqueueStoreIdValid
    entry.storeId := io.enqueueStoreId
    entry.exactOwner := io.enqueueExactOwner
    entry
  }

  private def zeroIssue: STQCommitIssue = {
    val issue = Wire(new STQCommitIssue(
      robEntries, stqEntries, lsidWidth, peIdWidth, stidWidth, nativeBidWidth,
      ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
      residentGenerationWidth, leaseGenerationWidth))
    issue := 0.U.asTypeOf(issue)
    issue
  }

  val queue = RegInit(VecInit(Seq.fill(queueEntries)(zeroEntry)))
  val count = RegInit(0.U(countWidth.W))

  val entryExact = Wire(Vec(queueEntries, Bool()))
  val stidMalformed = Wire(Vec(queueEntries, Bool()))
  val hasOlder = Wire(Vec(queueEntries, Bool()))
  for (slot <- 0 until queueEntries) {
    entryExact(slot) := !queue(slot).valid || shapeExact(queue(slot))
    val peerErrors = (0 until queueEntries).filter(_ != slot).map { other =>
      val sameStid = queue(slot).valid && queue(other).valid &&
        queue(slot).stid === queue(other).stid
      val sameStoreId = queue(slot).storeId === queue(other).storeId
      val ownerCollision = sameStoreId &&
        (!sameOwner(queue(slot), queue(other)) ||
          !sameLease(queue(slot), queue(other)))
      val storeRelation = serialOlder(queue(other).storeId, queue(slot).storeId)
      val lsidRelation = serialOlder(queue(other).lsId, queue(slot).lsId)
      sameStid && ((!entryExact(other)) || ownerCollision ||
        (!sameStoreId && (storeRelation =/= lsidRelation)))
    }
    val malformedPeer = if (peerErrors.isEmpty) false.B else peerErrors.reduce(_ || _)
    stidMalformed(slot) := queue(slot).valid &&
      (!entryExact(slot) || malformedPeer)
    val olderPeers = (0 until queueEntries).filter(_ != slot).map { other =>
      queue(slot).valid && queue(other).valid && entryExact(slot) &&
        entryExact(other) && queue(slot).stid === queue(other).stid &&
        serialOlder(queue(other).storeId, queue(slot).storeId)
    }
    hasOlder(slot) := (if (olderPeers.isEmpty) false.B
      else olderPeers.reduce(_ || _))
  }

  val readyVec = Wire(Vec(queueEntries, Bool()))
  val readyRank = Wire(Vec(queueEntries, UInt(countWidth.W)))
  val issueSelected = Wire(Vec(queueEntries, Bool()))
  for (slot <- 0 until queueEntries) {
    readyVec(slot) := queue(slot).valid && entryExact(slot) &&
      !stidMalformed(slot) && !hasOlder(slot) && io.issueEnable &&
      !io.flushValid && io.readyMask(slot)
    readyRank(slot) := (if (slot == 0) 0.U
      else PopCount((0 until slot).map(readyVec(_))))
    issueSelected(slot) := readyVec(slot) && readyRank(slot) < issueWidth.U
  }

  for (lane <- 0 until issueWidth) {
    val laneHit = VecInit((0 until queueEntries).map { slot =>
      readyVec(slot) && readyRank(slot) === lane.U
    })
    io.issue(lane) := zeroIssue
    io.issue(lane).valid := laneHit.asUInt.orR
    io.issue(lane).stqIndex := Mux1H(laneHit, queue.map(_.stqIndex))
    io.issue(lane).leaseGeneration :=
      Mux1H(laneHit, queue.map(_.leaseGeneration))
    io.issue(lane).stid := Mux1H(laneHit, queue.map(_.stid))
    io.issue(lane).bid := Mux1H(laneHit, queue.map(_.bid))
    io.issue(lane).lsId := Mux1H(laneHit, queue.map(_.lsId))
    io.issue(lane).storeIdValid :=
      Mux1H(laneHit, queue.map(_.storeIdValid))
    io.issue(lane).storeId := Mux1H(laneHit, queue.map(_.storeId))
    io.issue(lane).exactOwner := Mux1H(laneHit, queue.map(_.exactOwner))
  }

  val issueMaskVec = VecInit(io.issue.map(_.valid))
  io.issueValidMask := issueMaskVec.asUInt
  io.issueCount := PopCount(issueMaskVec)

  val keptVec = Wire(Vec(queueEntries, Bool()))
  val keptRank = Wire(Vec(queueEntries, UInt(countWidth.W)))
  val compacted = Wire(Vec(queueEntries, newEntry))
  for (slot <- 0 until queueEntries) {
    keptVec(slot) := queue(slot).valid && !issueSelected(slot)
    keptRank(slot) := (if (slot == 0) 0.U
      else PopCount((0 until slot).map(keptVec(_))))
  }
  for (dst <- 0 until queueEntries) {
    compacted(dst) := zeroEntry
    for (src <- 0 until queueEntries) {
      when(keptVec(src) && keptRank(src) === dst.U) {
        compacted(dst) := queue(src)
      }
    }
  }

  val keptCount = count - io.issueCount
  val incoming = enqueueEntry
  val incomingShapeExact = shapeExact(incoming)
  val duplicateVec = VecInit((0 until queueEntries).map { slot =>
    queue(slot).valid &&
      (sameLease(queue(slot), incoming) ||
        (queue(slot).stid === incoming.stid &&
          queue(slot).storeId === incoming.storeId))
  })
  io.enqueueMalformed := io.enqueueValid && !io.flushValid &&
    !incomingShapeExact
  io.enqueueDuplicate := io.enqueueValid && !io.flushValid &&
    incomingShapeExact && duplicateVec.asUInt.orR
  io.enqueueReady := !io.flushValid && incomingShapeExact &&
    !io.enqueueDuplicate && keptCount < queueEntries.U
  io.enqueueAccepted := io.enqueueValid && io.enqueueReady
  io.enqueueInsertPosition := keptCount

  val nextQueue = Wire(Vec(queueEntries, newEntry))
  for (dst <- 0 until queueEntries) {
    nextQueue(dst) := compacted(dst)
    when(io.enqueueAccepted && dst.U === keptCount) {
      nextQueue(dst) := incoming
    }
  }

  when(io.flushValid) {
    queue := VecInit(Seq.fill(queueEntries)(zeroEntry))
    count := 0.U
  }.otherwise {
    queue := nextQueue
    count := keptCount + io.enqueueAccepted.asUInt
  }

  val queuedValidVec = VecInit(queue.map(_.valid))
  val packedError = Wire(Vec(queueEntries, Bool()))
  for (slot <- 0 until queueEntries) {
    packedError(slot) := (if (slot == queueEntries - 1) false.B
      else !queue(slot).valid &&
        queue.drop(slot + 1).map(_.valid).reduce(_ || _))
    io.queued(slot) := queue(slot)
  }
  io.queuedValidMask := queuedValidVec.asUInt
  io.queueCount := count
  io.empty := count === 0.U
  io.full := count === queueEntries.U
  io.orderError := packedError.asUInt.orR || stidMalformed.asUInt.orR

  for (lane <- 0 until issueWidth) {
    when(io.issue(lane).valid) {
      assert(io.issue(lane).exactOwner.valid &&
        io.issue(lane).exactOwner.stid === io.issue(lane).stid &&
        io.issue(lane).storeIdValid,
        "a commit issue must carry one exact store token")
    }
    for (other <- lane + 1 until issueWidth) {
      assert(!(io.issue(lane).valid && io.issue(other).valid &&
        io.issue(lane).stid === io.issue(other).stid),
        "at most one oldest committed store may issue per STID per cycle")
    }
  }
}
