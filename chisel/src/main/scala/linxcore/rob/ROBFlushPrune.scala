package linxcore.rob

import chisel3._
import chisel3.util.{log2Ceil, Mux1H, PopCount}
import linxcore.recovery.{FlushBus, FlushControl}

class ROBFlushPruneEntry(
    val entries: Int,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val status = ROBEntryStatus()
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val bid = new ROBID(entries)
  val rid = new ROBID(entries)
}

class ROBFlushPruneIO(val entries: Int, val peIdWidth: Int = 8, val stidWidth: Int = 8, val tidWidth: Int = 8)
    extends Bundle {
  private val ptrWidth = log2Ceil(entries)
  private val countWidth = log2Ceil(entries + 1)

  val flush = Input(new FlushBus(entries, peIdWidth, stidWidth, tidWidth))
  val deallocHead = Input(UInt(ptrWidth.W))
  val commitHead = Input(UInt(ptrWidth.W))
  val rows = Input(Vec(entries, new ROBFlushPruneEntry(entries, peIdWidth, stidWidth, tidWidth)))

  val directMatchMask = Output(UInt(entries.W))
  val pruneMask = Output(UInt(entries.W))
  val pruneBeforeCommitMask = Output(UInt(entries.W))
  val outstandingPruneMask = Output(UInt(entries.W))

  val firstPruneValid = Output(Bool())
  val firstPruneValue = Output(UInt(ptrWidth.W))
  val commitRebaseNeeded = Output(Bool())
  val commitRebaseValue = Output(UInt(ptrWidth.W))

  val residentDecrement = Output(UInt(countWidth.W))
  val outstandingDecrement = Output(UInt(countWidth.W))
}

class ROBFlushPrune(
    val entries: Int = 16,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  require(entries > 1, "ROB entries must be greater than one")
  require((entries & (entries - 1)) == 0, "ROB entries must be a power of two")

  private val ptrWidth = log2Ceil(entries)

  val io = IO(new ROBFlushPruneIO(entries, peIdWidth, stidWidth, tidWidth))

  private def wrapIndex(value: UInt, offset: Int): UInt = {
    val sum = value + offset.U
    Mux(sum >= entries.U, sum - entries.U, sum)(ptrWidth - 1, 0)
  }

  private def rowInScope(row: ROBFlushPruneEntry): Bool =
    (row.stid === io.flush.req.stid) &&
      (!io.flush.baseOnPE || (row.peId === io.flush.req.peId)) &&
      (!io.flush.baseOnThread || (row.tid === io.flush.req.tid))

  private def rowMatchesFlush(row: ROBFlushPruneEntry): Bool =
    io.flush.req.valid && row.valid && rowInScope(row) &&
      Mux(
        io.flush.baseOnBid,
        ROBID.lessEqual(io.flush.req.bid, row.bid),
        FlushControl.lessEqualBidRid(io.flush.req.bid, io.flush.req.rid, row.bid, row.rid)
      )

  val directByScan = Wire(Vec(entries, Bool()))
  val pruneByScan = Wire(Vec(entries, Bool()))
  val beforeCommitByScan = Wire(Vec(entries, Bool()))
  val outstandingByScan = Wire(Vec(entries, Bool()))
  val firstByScan = Wire(Vec(entries, Bool()))

  for (offset <- 0 until entries) {
    val idx = wrapIndex(io.deallocHead, offset)
    val row = io.rows(idx)
    val priorDirect =
      if (offset == 0) false.B else VecInit(directByScan.take(offset)).asUInt.orR
    val seenCommit =
      VecInit((0 to offset).map { priorOffset =>
        wrapIndex(io.deallocHead, priorOffset) === io.commitHead
      }).asUInt.orR

    directByScan(offset) := rowMatchesFlush(row)
    pruneByScan(offset) := row.valid && rowInScope(row) && (priorDirect || directByScan(offset))
    beforeCommitByScan(offset) := !seenCommit
    outstandingByScan(offset) := pruneByScan(offset) && ROBEntryStatus.osdActive(row.status)
    firstByScan(offset) := directByScan(offset) && !priorDirect
  }

  private def scanToPhysical(scanMask: Vec[Bool]): UInt =
    VecInit((0 until entries).map { phys =>
      VecInit((0 until entries).map { offset =>
        (wrapIndex(io.deallocHead, offset) === phys.U) && scanMask(offset)
      }).asUInt.orR
    }).asUInt

  val directMask = scanToPhysical(directByScan)
  val pruneMask = scanToPhysical(pruneByScan)
  val beforeCommitMask = scanToPhysical(VecInit((0 until entries).map { offset =>
    pruneByScan(offset) && beforeCommitByScan(offset)
  }))
  val outstandingMask = scanToPhysical(outstandingByScan)

  io.directMatchMask := directMask
  io.pruneMask := pruneMask
  io.pruneBeforeCommitMask := beforeCommitMask
  io.outstandingPruneMask := outstandingMask
  io.firstPruneValid := firstByScan.asUInt.orR
  io.firstPruneValue := Mux1H(
    firstByScan,
    (0 until entries).map(offset => wrapIndex(io.deallocHead, offset))
  )
  io.commitRebaseNeeded := beforeCommitMask.orR
  io.commitRebaseValue := Mux1H(
    VecInit((0 until entries).map { offset =>
      val priorPruneBefore =
        if (offset == 0) false.B
        else {
          VecInit((0 until offset).map { prior =>
            pruneByScan(prior) && beforeCommitByScan(prior)
          }).asUInt.orR
        }
      pruneByScan(offset) && beforeCommitByScan(offset) &&
        !priorPruneBefore
    }),
    (0 until entries).map(offset => wrapIndex(io.deallocHead, offset))
  )
  io.residentDecrement := PopCount(pruneByScan)
  io.outstandingDecrement := PopCount(outstandingByScan)
}
