package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PopCount}

import linxcore.recovery.{FlushBus, FlushControl}
import linxcore.rob.ROBID

object STQEntryStatus extends ChiselEnum {
  val Wait, Commit, Miss, L2Wait, Idle, Resolved = Value
}

class STQFlushPruneEntry(
    val entries: Int,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val status = STQEntryStatus()
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val bid = new ROBID(entries)
  val gid = new ROBID(entries)
  val lsId = new ROBID(entries)
}

class STQFlushPruneIO(
    val entries: Int,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val robEntries: Int = 0)
    extends Bundle {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  private val countWidth = log2Ceil(entries + 1)

  val flush = Input(new FlushBus(identityEntries, peIdWidth, stidWidth, tidWidth))
  val rows = Input(Vec(entries, new STQFlushPruneEntry(identityEntries, peIdWidth, stidWidth, tidWidth)))

  val matchMask = Output(UInt(entries.W))
  val freeMask = Output(UInt(entries.W))
  val statusBlockedMask = Output(UInt(entries.W))
  val freeCount = Output(UInt(countWidth.W))
}

object STQFlushPrune {
  def lessEqualBidGroupLs(
      srcBid: ROBID,
      srcGid: ROBID,
      srcLsId: ROBID,
      dstBid: ROBID,
      dstGid: ROBID,
      dstLsId: ROBID): Bool =
    ROBID.less(srcBid, dstBid) ||
      (ROBID.equal(srcBid, dstBid) &&
        (ROBID.less(srcGid, dstGid) ||
          (ROBID.equal(srcGid, dstGid) && ROBID.lessEqual(srcLsId, dstLsId))))

  def matchesFlush(signal: FlushBus, row: STQFlushPruneEntry): Bool = {
    val sameStid = signal.req.stid === row.stid
    val samePe = !signal.baseOnPE || (signal.req.peId === row.peId)
    val sameThread = !signal.baseOnThread || (signal.req.tid === row.tid)
    val idMatch = Mux(
      signal.baseOnBid,
      ROBID.lessEqual(signal.req.bid, row.bid),
      Mux(
        signal.baseOnGroup,
        ROBID.lessEqual(signal.req.bid, row.bid) ||
          lessEqualBidGroupLs(signal.req.bid, signal.req.gid, signal.req.lsId, row.bid, row.gid, row.lsId),
        FlushControl.lessEqualBidRid(signal.req.bid, signal.req.lsId, row.bid, row.lsId)
      )
    )

    signal.req.valid && row.valid && sameStid && samePe && sameThread && idMatch
  }
}

class STQFlushPrune(
    val entries: Int = 16,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val robEntries: Int = 0)
    extends Module {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  require(entries > 1, "STQ entries must be greater than one")
  require((entries & (entries - 1)) == 0, "STQ entries must be a power of two")

  val io = IO(new STQFlushPruneIO(entries, peIdWidth, stidWidth, tidWidth, identityEntries))

  val matches = VecInit(io.rows.map(row => STQFlushPrune.matchesFlush(io.flush, row)))
  val free = VecInit(io.rows.zip(matches).map { case (row, matched) =>
    matched && (row.status === STQEntryStatus.Wait)
  })
  val statusBlocked = VecInit(io.rows.zip(matches).map { case (row, matched) =>
    matched && (row.status =/= STQEntryStatus.Wait)
  })

  io.matchMask := matches.asUInt
  io.freeMask := free.asUInt
  io.statusBlockedMask := statusBlocked.asUInt
  io.freeCount := PopCount(free)
}
