package linxcore.bctrl

import chisel3._

object BrobStatus extends ChiselEnum {
  val Free, Allocated, Dispatched, Renaming, Renamed, Issued, ParRunning, Running,
      Completed, Retired, NeedFlush, NeedReplay, Flushed, Mispred, Exception, Terminate = Value
}

class BrobEntryMeta(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32)
    extends Bundle {
  val bid = UInt(bidWidth.W)
  val status = BrobStatus()
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
    val tidWidth: Int = 8,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32)
    extends Bundle {
  val allocValid = Input(Bool())
  val allocBid = Input(UInt(bidWidth.W))
  val allocTid = Input(UInt(tidWidth.W))
  val allocPeId = Input(UInt(peIdWidth.W))
  val allocBlockType = Input(UInt(blockTypeWidth.W))
  val allocNeedsEngine = Input(Bool())
  val allocReady = Output(Bool())

  val scalarDoneValid = Input(Bool())
  val scalarDoneBid = Input(UInt(bidWidth.W))
  val scalarTrapValid = Input(Bool())
  val scalarTrapCause = Input(UInt(trapCauseWidth.W))

  val engineDoneValid = Input(Bool())
  val engineDoneBid = Input(UInt(bidWidth.W))
  val engineTrapValid = Input(Bool())
  val engineTrapCause = Input(UInt(trapCauseWidth.W))

  val retireValid = Input(Bool())
  val retireBid = Input(UInt(bidWidth.W))

  val flushValid = Input(Bool())
  val flushBid = Input(UInt(bidWidth.W))

  val queryBid = Input(UInt(bidWidth.W))
  val query = Output(new BrobEntryMeta(entries, bidWidth, peIdWidth, tidWidth, blockTypeWidth, trapCauseWidth))
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
    val tidWidth: Int = 8,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32)
    extends Module {
  require(entries > 1, "BROB entries must be greater than one")
  require((entries & (entries - 1)) == 0, "BROB entries must be a power of two")
  require(bidWidth > BID.slotBits(entries), "BID width must include uniqueness bits")

  val io = IO(new BrobMetaTrackerIO(entries, bidWidth, peIdWidth, tidWidth, blockTypeWidth, trapCauseWidth))

  private def resetMeta: BrobEntryMeta = {
    val meta = Wire(new BrobEntryMeta(entries, bidWidth, peIdWidth, tidWidth, blockTypeWidth, trapCauseWidth))
    meta := 0.U.asTypeOf(meta)
    meta.status := BrobStatus.Free
    meta
  }

  val table = RegInit(VecInit(Seq.fill(entries)(resetMeta)))

  val allocSlot = BID.slot(io.allocBid, entries)
  val scalarSlot = BID.slot(io.scalarDoneBid, entries)
  val engineSlot = BID.slot(io.engineDoneBid, entries)
  val retireSlot = BID.slot(io.retireBid, entries)
  val querySlot = BID.slot(io.queryBid, entries)
  val scalarHit =
    BrobEntryMeta.isAllocated(table(scalarSlot).status) && table(scalarSlot).bid === io.scalarDoneBid
  val engineHit =
    BrobEntryMeta.isAllocated(table(engineSlot).status) && table(engineSlot).bid === io.engineDoneBid
  val retireHit = table(retireSlot).status === BrobStatus.Completed && table(retireSlot).bid === io.retireBid

  io.allocReady := (table(allocSlot).status === BrobStatus.Free) || (table(allocSlot).status === BrobStatus.Flushed)

  when(io.allocValid && io.allocReady) {
    table(allocSlot).bid := io.allocBid
    table(allocSlot).status := BrobStatus.Allocated
    table(allocSlot).tid := io.allocTid
    table(allocSlot).peId := io.allocPeId
    table(allocSlot).blockType := io.allocBlockType
    table(allocSlot).needsEngine := io.allocNeedsEngine
    table(allocSlot).scalarDone := false.B
    table(allocSlot).engineDone := false.B
    table(allocSlot).exception := false.B
    table(allocSlot).trapCause := 0.U
  }

  when(io.scalarDoneValid && scalarHit) {
    table(scalarSlot).scalarDone := true.B
    when(io.scalarTrapValid && !table(scalarSlot).exception) {
      table(scalarSlot).exception := true.B
      table(scalarSlot).trapCause := io.scalarTrapCause
    }
    when(!table(scalarSlot).needsEngine || table(scalarSlot).engineDone) {
      table(scalarSlot).status := BrobStatus.Completed
    }
  }

  when(io.engineDoneValid && engineHit) {
    table(engineSlot).engineDone := true.B
    when(io.engineTrapValid && !table(engineSlot).exception) {
      table(engineSlot).exception := true.B
      table(engineSlot).trapCause := io.engineTrapCause
    }
    when(table(engineSlot).scalarDone) {
      table(engineSlot).status := BrobStatus.Completed
    }
  }

  when(io.retireValid && retireHit) {
    table(retireSlot) := resetMeta
  }

  when(io.flushValid) {
    for (idx <- 0 until entries) {
      when(BrobEntryMeta.isAllocated(table(idx).status) && BID.killOnFlush(table(idx).bid, io.flushBid)) {
        table(idx).status := BrobStatus.Flushed
        table(idx).scalarDone := false.B
        table(idx).engineDone := false.B
        table(idx).exception := false.B
        table(idx).trapCause := 0.U
      }
    }
  }

  io.query := table(querySlot)
  io.queryAllocated := BrobEntryMeta.isAllocated(io.query.status)
  io.queryComplete := BrobEntryMeta.isComplete(io.query)

  val allocatedBits = Wire(Vec(entries, Bool()))
  val completeBits = Wire(Vec(entries, Bool()))
  val pendingBits = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    allocatedBits(idx) := BrobEntryMeta.isAllocated(table(idx).status)
    completeBits(idx) := BrobEntryMeta.isComplete(table(idx))
    pendingBits(idx) := allocatedBits(idx) && !completeBits(idx)
  }

  io.allocatedMask := allocatedBits.asUInt
  io.completeMask := completeBits.asUInt
  io.pendingMask := pendingBits.asUInt
}
