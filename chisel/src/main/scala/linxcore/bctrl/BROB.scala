package linxcore.bctrl

import chisel3._
import chisel3.util.Mux1H

object BrobStatus extends ChiselEnum {
  val Free, Allocated, Dispatched, Renaming, Renamed, Issued, ParRunning, Running,
      Completed, Retired, NeedFlush, NeedReplay, Flushed, Mispred, Exception, Terminate = Value
}

class BrobEntryMeta(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32)
    extends Bundle {
  val bid = UInt(bidWidth.W)
  val status = BrobStatus()
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val peId = UInt(peIdWidth.W)
  val blockType = UInt(blockTypeWidth.W)
  val needsEngine = Bool()
  val scalarDone = Bool()
  val engineDone = Bool()
  val exception = Bool()
  val trapCause = UInt(trapCauseWidth.W)
}

object BrobEntryMeta {
  def isAllocated(status: BrobStatus.Type): Bool =
    (status =/= BrobStatus.Free) && (status =/= BrobStatus.Flushed)

  def isComplete(meta: BrobEntryMeta): Bool =
    meta.scalarDone && (!meta.needsEngine || meta.engineDone)
}

class BrobMetaTrackerIO(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32)
    extends Bundle {
  val allocValid = Input(Bool())
  val allocBid = Input(UInt(bidWidth.W))
  val allocStid = Input(UInt(stidWidth.W))
  val allocTid = Input(UInt(tidWidth.W))
  val allocPeId = Input(UInt(peIdWidth.W))
  val allocBlockType = Input(UInt(blockTypeWidth.W))
  val allocNeedsEngine = Input(Bool())
  val allocReady = Output(Bool())

  val scalarDoneValid = Input(Bool())
  val scalarDoneBid = Input(UInt(bidWidth.W))
  val scalarDoneStid = Input(UInt(stidWidth.W))
  val scalarTrapValid = Input(Bool())
  val scalarTrapCause = Input(UInt(trapCauseWidth.W))

  val engineDoneValid = Input(Bool())
  val engineDoneBid = Input(UInt(bidWidth.W))
  val engineDoneStid = Input(UInt(stidWidth.W))
  val engineTrapValid = Input(Bool())
  val engineTrapCause = Input(UInt(trapCauseWidth.W))

  val retireValid = Input(Bool())
  val retireBid = Input(UInt(bidWidth.W))
  val retireStid = Input(UInt(stidWidth.W))

  val flushValid = Input(Bool())
  val flushBid = Input(UInt(bidWidth.W))
  val flushStid = Input(UInt(stidWidth.W))

  val queryBid = Input(UInt(bidWidth.W))
  val queryStid = Input(UInt(stidWidth.W))
  val query = Output(new BrobEntryMeta(entries, bidWidth, peIdWidth, stidWidth, tidWidth, blockTypeWidth, trapCauseWidth))
  val queryAllocated = Output(Bool())
  val queryComplete = Output(Bool())

  val allocatedMask = Output(UInt(entries.W))
  val completeMask = Output(UInt(entries.W))
  val pendingMask = Output(UInt(entries.W))
}

class BrobMetaTracker(
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val stidCount: Int = 1,
    val tidWidth: Int = 8,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32)
    extends Module {
  require(entries > 1, "BROB entries must be greater than one")
  require((entries & (entries - 1)) == 0, "BROB entries must be a power of two")
  require(bidWidth > BID.slotBits(entries), "BID width must include uniqueness bits")
  require(stidCount > 0, "BROB must track at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "BROB STID count must fit stidWidth")

  val io = IO(new BrobMetaTrackerIO(entries, bidWidth, peIdWidth, stidWidth, tidWidth, blockTypeWidth, trapCauseWidth))

  private def resetMeta: BrobEntryMeta = {
    val meta = Wire(new BrobEntryMeta(entries, bidWidth, peIdWidth, stidWidth, tidWidth, blockTypeWidth, trapCauseWidth))
    meta := 0.U.asTypeOf(meta)
    meta.status := BrobStatus.Free
    meta
  }

  val table = RegInit(VecInit(Seq.fill(stidCount)(VecInit(Seq.fill(entries)(resetMeta)))))

  private def matchesStid(stid: UInt): Vec[Bool] =
    VecInit((0 until stidCount).map(idx => stid === idx.U(stidWidth.W)))

  private def selectMeta(stidMatch: Vec[Bool], slot: UInt): BrobEntryMeta =
    Mux1H(stidMatch, table.map(_(slot)))

  val allocSlot = BID.slot(io.allocBid, entries)
  val scalarSlot = BID.slot(io.scalarDoneBid, entries)
  val engineSlot = BID.slot(io.engineDoneBid, entries)
  val retireSlot = BID.slot(io.retireBid, entries)
  val querySlot = BID.slot(io.queryBid, entries)
  val allocStidMatch = matchesStid(io.allocStid)
  val scalarStidMatch = matchesStid(io.scalarDoneStid)
  val engineStidMatch = matchesStid(io.engineDoneStid)
  val retireStidMatch = matchesStid(io.retireStid)
  val flushStidMatch = matchesStid(io.flushStid)
  val queryStidMatch = matchesStid(io.queryStid)
  val allocEntry = selectMeta(allocStidMatch, allocSlot)
  val scalarEntry = selectMeta(scalarStidMatch, scalarSlot)
  val engineEntry = selectMeta(engineStidMatch, engineSlot)
  val retireEntry = selectMeta(retireStidMatch, retireSlot)
  val scalarHit = scalarStidMatch.asUInt.orR &&
    BrobEntryMeta.isAllocated(scalarEntry.status) && scalarEntry.bid === io.scalarDoneBid
  val engineHit = engineStidMatch.asUInt.orR &&
    BrobEntryMeta.isAllocated(engineEntry.status) && engineEntry.bid === io.engineDoneBid
  val retireHit = retireStidMatch.asUInt.orR &&
    retireEntry.status === BrobStatus.Completed && retireEntry.bid === io.retireBid

  io.allocReady := allocStidMatch.asUInt.orR &&
    ((allocEntry.status === BrobStatus.Free) || (allocEntry.status === BrobStatus.Flushed))

  when(io.allocValid && io.allocReady) {
    for (stid <- 0 until stidCount) {
      when(allocStidMatch(stid)) {
        table(stid)(allocSlot).bid := io.allocBid
        table(stid)(allocSlot).status := BrobStatus.Allocated
        table(stid)(allocSlot).stid := io.allocStid
        table(stid)(allocSlot).tid := io.allocTid
        table(stid)(allocSlot).peId := io.allocPeId
        table(stid)(allocSlot).blockType := io.allocBlockType
        table(stid)(allocSlot).needsEngine := io.allocNeedsEngine
        table(stid)(allocSlot).scalarDone := false.B
        table(stid)(allocSlot).engineDone := false.B
        table(stid)(allocSlot).exception := false.B
        table(stid)(allocSlot).trapCause := 0.U
      }
    }
  }

  when(io.scalarDoneValid && scalarHit) {
    for (stid <- 0 until stidCount) {
      when(scalarStidMatch(stid)) {
        table(stid)(scalarSlot).scalarDone := true.B
        when(io.scalarTrapValid && !table(stid)(scalarSlot).exception) {
          table(stid)(scalarSlot).exception := true.B
          table(stid)(scalarSlot).trapCause := io.scalarTrapCause
        }
        when(!table(stid)(scalarSlot).needsEngine || table(stid)(scalarSlot).engineDone) {
          table(stid)(scalarSlot).status := BrobStatus.Completed
        }
      }
    }
  }

  when(io.engineDoneValid && engineHit) {
    for (stid <- 0 until stidCount) {
      when(engineStidMatch(stid)) {
        table(stid)(engineSlot).engineDone := true.B
        when(io.engineTrapValid && !table(stid)(engineSlot).exception) {
          table(stid)(engineSlot).exception := true.B
          table(stid)(engineSlot).trapCause := io.engineTrapCause
        }
        when(table(stid)(engineSlot).scalarDone) {
          table(stid)(engineSlot).status := BrobStatus.Completed
        }
      }
    }
  }

  when(io.retireValid && retireHit) {
    for (stid <- 0 until stidCount) {
      when(retireStidMatch(stid)) {
        table(stid)(retireSlot) := resetMeta
      }
    }
  }

  when(io.flushValid) {
    for (stid <- 0 until stidCount) {
      for (idx <- 0 until entries) {
        when(flushStidMatch(stid) && BrobEntryMeta.isAllocated(table(stid)(idx).status) &&
          BID.killOnFlush(table(stid)(idx).bid, io.flushBid)) {
          table(stid)(idx).status := BrobStatus.Flushed
          table(stid)(idx).scalarDone := false.B
          table(stid)(idx).engineDone := false.B
          table(stid)(idx).exception := false.B
          table(stid)(idx).trapCause := 0.U
        }
      }
    }
  }

  io.query := selectMeta(queryStidMatch, querySlot)
  io.queryAllocated := queryStidMatch.asUInt.orR && BrobEntryMeta.isAllocated(io.query.status)
  io.queryComplete := BrobEntryMeta.isComplete(io.query)

  val allocatedBits = Wire(Vec(entries, Bool()))
  val completeBits = Wire(Vec(entries, Bool()))
  val pendingBits = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    val entry = selectMeta(queryStidMatch, idx.U)
    allocatedBits(idx) := queryStidMatch.asUInt.orR && BrobEntryMeta.isAllocated(entry.status)
    completeBits(idx) := queryStidMatch.asUInt.orR && BrobEntryMeta.isComplete(entry)
    pendingBits(idx) := allocatedBits(idx) && !completeBits(idx)
  }

  io.allocatedMask := allocatedBits.asUInt
  io.completeMask := completeBits.asUInt
  io.pendingMask := pendingBits.asUInt
}
