package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, Mux1H, PopCount, PriorityEncoder}

import linxcore.rob.ROBID

class STQCommitQueueEntry(val robEntries: Int, val stqEntries: Int) extends Bundle {
  val valid = Bool()
  val stqIndex = UInt(log2Ceil(stqEntries).W)
  val bid = new ROBID(robEntries)
  val lsId = new ROBID(robEntries)
}

class STQCommitIssue(val robEntries: Int, val stqEntries: Int) extends Bundle {
  val valid = Bool()
  val stqIndex = UInt(log2Ceil(stqEntries).W)
  val bid = new ROBID(robEntries)
  val lsId = new ROBID(robEntries)
}

class STQCommitQueueIO(
    val robEntries: Int,
    val stqEntries: Int,
    val queueEntries: Int,
    val issueWidth: Int)
    extends Bundle {
  private val countWidth = log2Ceil(queueEntries + 1)

  val enqueueValid = Input(Bool())
  val enqueueIndex = Input(UInt(log2Ceil(stqEntries).W))
  val enqueueBid = Input(new ROBID(robEntries))
  val enqueueLsId = Input(new ROBID(robEntries))
  val flushValid = Input(Bool())
  val enqueueReady = Output(Bool())
  val enqueueAccepted = Output(Bool())
  val enqueueDuplicate = Output(Bool())
  val enqueueInsertPosition = Output(UInt(countWidth.W))

  val issueEnable = Input(Bool())
  val readyMask = Input(UInt(stqEntries.W))
  val issue = Output(Vec(issueWidth, new STQCommitIssue(robEntries, stqEntries)))
  val issueValidMask = Output(UInt(issueWidth.W))
  val issueCount = Output(UInt(log2Ceil(issueWidth + 1).W))

  val queued = Output(Vec(queueEntries, new STQCommitQueueEntry(robEntries, stqEntries)))
  val queuedValidMask = Output(UInt(queueEntries.W))
  val queueCount = Output(UInt(countWidth.W))
  val empty = Output(Bool())
  val full = Output(Bool())
  val orderError = Output(Bool())
}

object STQCommitQueue {
  def lessEqualBidLs(srcBid: ROBID, srcLsId: ROBID, dstBid: ROBID, dstLsId: ROBID): Bool =
    ROBID.less(srcBid, dstBid) || (ROBID.equal(srcBid, dstBid) && ROBID.lessEqual(srcLsId, dstLsId))
}

class STQCommitQueue(
    val robEntries: Int = 16,
    val stqEntries: Int = 16,
    val queueEntries: Int = 16,
    val issueWidth: Int = 2)
    extends Module {
  require(robEntries > 1, "ROB entries must be greater than one")
  require(stqEntries > 1, "STQ entries must be greater than one")
  require(queueEntries > 1, "STQ commit queue entries must be greater than one")
  require(issueWidth > 0, "STQ commit issue width must be nonzero")
  require(issueWidth <= queueEntries, "STQ commit issue width cannot exceed queue depth")
  require((robEntries & (robEntries - 1)) == 0, "ROB entries must be a power of two")
  require((stqEntries & (stqEntries - 1)) == 0, "STQ entries must be a power of two")
  require((queueEntries & (queueEntries - 1)) == 0, "STQ commit queue entries must be a power of two")

  private val countWidth = log2Ceil(queueEntries + 1)

  val io = IO(new STQCommitQueueIO(robEntries, stqEntries, queueEntries, issueWidth))

  private def zeroEntry: STQCommitQueueEntry = {
    val entry = Wire(new STQCommitQueueEntry(robEntries, stqEntries))
    entry := 0.U.asTypeOf(entry)
    entry
  }

  private def zeroIssue: STQCommitIssue = {
    val issue = Wire(new STQCommitIssue(robEntries, stqEntries))
    issue := 0.U.asTypeOf(issue)
    issue
  }

  private def enqueueEntry: STQCommitQueueEntry = {
    val entry = Wire(new STQCommitQueueEntry(robEntries, stqEntries))
    entry.valid := true.B
    entry.stqIndex := io.enqueueIndex
    entry.bid := io.enqueueBid
    entry.lsId := io.enqueueLsId
    entry
  }

  val emptyQueue = VecInit(Seq.fill(queueEntries)(zeroEntry))
  val queue = RegInit(emptyQueue)
  val count = RegInit(0.U(countWidth.W))

  val readyVec = Wire(Vec(queueEntries, Bool()))
  val readyRank = Wire(Vec(queueEntries, UInt(countWidth.W)))
  val issueSelected = Wire(Vec(queueEntries, Bool()))

  for (slot <- 0 until queueEntries) {
    readyVec(slot) := queue(slot).valid && io.issueEnable && !io.flushValid && io.readyMask(queue(slot).stqIndex)
    if (slot == 0) {
      readyRank(slot) := 0.U
    } else {
      readyRank(slot) := PopCount((0 until slot).map(readyVec(_)))
    }
    issueSelected(slot) := readyVec(slot) && (readyRank(slot) < issueWidth.U)
  }

  for (lane <- 0 until issueWidth) {
    val laneHit = VecInit((0 until queueEntries).map(slot => readyVec(slot) && (readyRank(slot) === lane.U)))
    io.issue(lane) := zeroIssue
    io.issue(lane).valid := laneHit.asUInt.orR
    io.issue(lane).stqIndex := Mux1H(laneHit, queue.map(_.stqIndex))
    io.issue(lane).bid := Mux1H(laneHit, queue.map(_.bid))
    io.issue(lane).lsId := Mux1H(laneHit, queue.map(_.lsId))
  }

  val issueMaskVec = VecInit(io.issue.map(_.valid))
  io.issueValidMask := issueMaskVec.asUInt
  io.issueCount := PopCount(issueMaskVec)

  val keptVec = Wire(Vec(queueEntries, Bool()))
  val keptRank = Wire(Vec(queueEntries, UInt(countWidth.W)))
  val compacted = Wire(Vec(queueEntries, new STQCommitQueueEntry(robEntries, stqEntries)))
  for (slot <- 0 until queueEntries) {
    keptVec(slot) := queue(slot).valid && !issueSelected(slot)
    if (slot == 0) {
      keptRank(slot) := 0.U
    } else {
      keptRank(slot) := PopCount((0 until slot).map(keptVec(_)))
    }
  }

  for (dst <- 0 until queueEntries) {
    compacted(dst) := zeroEntry
    for (src <- 0 until queueEntries) {
      when(keptVec(src) && (keptRank(src) === dst.U)) {
        compacted(dst) := queue(src)
      }
    }
  }

  val keptCount = count - io.issueCount
  val duplicateVec =
    VecInit((0 until queueEntries).map(slot => compacted(slot).valid && (compacted(slot).stqIndex === io.enqueueIndex)))
  io.enqueueDuplicate := io.enqueueValid && !io.flushValid && duplicateVec.asUInt.orR
  io.enqueueReady := !io.flushValid && !io.enqueueDuplicate && (keptCount < queueEntries.U)
  io.enqueueAccepted := io.enqueueValid && io.enqueueReady

  val insertBeforeVec = VecInit((0 until queueEntries).map { slot =>
    compacted(slot).valid && STQCommitQueue.lessEqualBidLs(io.enqueueBid, io.enqueueLsId, compacted(slot).bid, compacted(slot).lsId)
  })
  val firstInsertPosition = Wire(UInt(countWidth.W))
  firstInsertPosition := 0.U
  when(insertBeforeVec.asUInt.orR) {
    firstInsertPosition := PriorityEncoder(insertBeforeVec.asUInt)
  }.otherwise {
    firstInsertPosition := keptCount
  }

  io.enqueueInsertPosition := firstInsertPosition

  val nextQueue = Wire(Vec(queueEntries, new STQCommitQueueEntry(robEntries, stqEntries)))
  for (dst <- 0 until queueEntries) {
    nextQueue(dst) := compacted(dst)
    when(io.enqueueAccepted) {
      if (dst == 0) {
        when(firstInsertPosition === 0.U) {
          nextQueue(dst) := enqueueEntry
        }.otherwise {
          nextQueue(dst) := compacted(dst)
        }
      } else {
        when(dst.U < firstInsertPosition) {
          nextQueue(dst) := compacted(dst)
        }.elsewhen(dst.U === firstInsertPosition) {
          nextQueue(dst) := enqueueEntry
        }.otherwise {
          nextQueue(dst) := compacted(dst - 1)
        }
      }
    }
  }

  when(io.flushValid) {
    queue := emptyQueue
    count := 0.U
  }.otherwise {
    queue := nextQueue
    count := keptCount + io.enqueueAccepted.asUInt
  }

  val queuedValidVec = VecInit(queue.map(_.valid))
  val packedError = Wire(Vec(queueEntries, Bool()))
  for (slot <- 0 until queueEntries) {
    if (slot == queueEntries - 1) {
      packedError(slot) := false.B
    } else {
      packedError(slot) := !queue(slot).valid && queue.drop(slot + 1).map(_.valid).reduce(_ || _)
    }
  }
  val sortedError = Wire(Vec(queueEntries - 1, Bool()))
  for (slot <- 0 until queueEntries - 1) {
    sortedError(slot) :=
      queue(slot).valid && queue(slot + 1).valid &&
        !STQCommitQueue.lessEqualBidLs(queue(slot).bid, queue(slot).lsId, queue(slot + 1).bid, queue(slot + 1).lsId)
  }

  for (slot <- 0 until queueEntries) {
    io.queued(slot) := queue(slot)
  }
  io.queuedValidMask := queuedValidVec.asUInt
  io.queueCount := count
  io.empty := count === 0.U
  io.full := count === queueEntries.U
  io.orderError := packedError.asUInt.orR || sortedError.asUInt.orR
}
