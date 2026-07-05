package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PopCount}

import linxcore.recovery.{FlushBus, FlushControl}
import linxcore.rob.ROBID

class LoadResolveQueueEntry(
    val liqEntries: Int,
    val idEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Bundle {
  val valid = Bool()
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val record = new LoadHitRecord(liqEntries, idEntries, addrWidth, lineBytes, sizeWidth)
}

class LoadResolveQueueIO(
    val queueEntries: Int,
    val liqEntries: Int,
    val idEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Bundle {
  private val countWidth = log2Ceil(queueEntries + 1)

  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(idEntries, peIdWidth, stidWidth, tidWidth))
  val flushPruneMask = Output(UInt(queueEntries.W))
  val flushPruneCount = Output(UInt(countWidth.W))

  val pushValid = Input(Bool())
  val pushPeId = Input(UInt(peIdWidth.W))
  val pushStid = Input(UInt(stidWidth.W))
  val pushTid = Input(UInt(tidWidth.W))
  val pushRecord = Input(new LoadHitRecord(liqEntries, idEntries, addrWidth, lineBytes, sizeWidth))
  val pushReady = Output(Bool())
  val pushAccepted = Output(Bool())
  val pushInsertIndex = Output(UInt(countWidth.W))

  val retireValid = Input(Bool())
  val retireBid = Input(new ROBID(idEntries))
  val retireLsId = Input(new ROBID(idEntries))
  val retireMask = Output(UInt(queueEntries.W))
  val retireCount = Output(UInt(countWidth.W))

  val entries = Output(Vec(queueEntries, new LoadResolveQueueEntry(
    liqEntries,
    idEntries,
    addrWidth,
    pcWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lineBytes,
    sizeWidth
  )))
  val conflictRows = Output(Vec(queueEntries, new MDBConflictLoadEntry(
    idEntries,
    addrWidth,
    pcWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    sizeWidth
  )))
  val validMask = Output(UInt(queueEntries.W))
  val count = Output(UInt(countWidth.W))
  val empty = Output(Bool())
  val full = Output(Bool())
}

class LoadResolveQueue(
    val queueEntries: Int = 8,
    val liqEntries: Int = 16,
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Module {
  require(queueEntries > 1, "LoadResolveQueue needs at least two entries")
  require((queueEntries & (queueEntries - 1)) == 0, "LoadResolveQueue entries must be a power of two")
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require(idEntries > 1, "ID entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(lineBytes == 64, "LoadResolveQueue currently carries 64-byte scalar line records")

  private val countWidth = log2Ceil(queueEntries + 1)

  val io = IO(new LoadResolveQueueIO(
    queueEntries,
    liqEntries,
    idEntries,
    addrWidth,
    pcWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lineBytes,
    sizeWidth
  ))

  private def zeroEntry: LoadResolveQueueEntry = {
    val entry = Wire(new LoadResolveQueueEntry(
      liqEntries,
      idEntries,
      addrWidth,
      pcWidth,
      peIdWidth,
      stidWidth,
      tidWidth,
      lineBytes,
      sizeWidth
    ))
    entry := 0.U.asTypeOf(entry)
    entry.record.loadId := ROBID.disabled(liqEntries)
    entry.record.bid := ROBID.disabled(idEntries)
    entry.record.gid := ROBID.disabled(idEntries)
    entry.record.rid := ROBID.disabled(idEntries)
    entry.record.loadLsId := ROBID.disabled(idEntries)
    entry
  }

  private def pushEntry: LoadResolveQueueEntry = {
    val entry = Wire(new LoadResolveQueueEntry(
      liqEntries,
      idEntries,
      addrWidth,
      pcWidth,
      peIdWidth,
      stidWidth,
      tidWidth,
      lineBytes,
      sizeWidth
    ))
    entry := zeroEntry
    entry.valid := true.B
    entry.peId := io.pushPeId
    entry.stid := io.pushStid
    entry.tid := io.pushTid
    entry.record := io.pushRecord
    entry
  }

  private def zeroConflict: MDBConflictLoadEntry = {
    val row = Wire(new MDBConflictLoadEntry(idEntries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth))
    row := 0.U.asTypeOf(row)
    row.bid := ROBID.disabled(idEntries)
    row.gid := ROBID.disabled(idEntries)
    row.rid := ROBID.disabled(idEntries)
    row.lsId := ROBID.disabled(idEntries)
    row
  }

  private def lessEqualBidLs(srcBid: ROBID, srcLsId: ROBID, dstBid: ROBID, dstLsId: ROBID): Bool =
    ROBID.less(srcBid, dstBid) || (ROBID.equal(srcBid, dstBid) && ROBID.lessEqual(srcLsId, dstLsId))

  private def flushMatchesEntry(signal: FlushBus, entry: LoadResolveQueueEntry): Bool = {
    val sameStid = signal.req.stid === entry.stid
    val samePe = !signal.baseOnPE || (signal.req.peId === entry.peId)
    val sameThread = !signal.baseOnThread || (signal.req.tid === entry.tid)
    val idMatch = Mux(
      signal.baseOnBid,
      ROBID.lessEqual(signal.req.bid, entry.record.bid),
      Mux(
        signal.baseOnGroup,
        ROBID.lessEqual(signal.req.bid, entry.record.bid) ||
          STQFlushPrune.lessEqualBidGroupLs(
            signal.req.bid,
            signal.req.gid,
            signal.req.lsId,
            entry.record.bid,
            entry.record.gid,
            entry.record.loadLsId),
        FlushControl.lessEqualBidRid(signal.req.bid, signal.req.lsId, entry.record.bid, entry.record.loadLsId)
      )
    )

    signal.req.valid && entry.valid && sameStid && samePe && sameThread && idMatch
  }

  private def toConflict(entry: LoadResolveQueueEntry): MDBConflictLoadEntry = {
    val row = Wire(new MDBConflictLoadEntry(idEntries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth))
    row := zeroConflict
    row.valid := entry.valid
    row.resolved := entry.valid
    row.isTile := false.B
    row.peId := entry.peId
    row.stid := entry.stid
    row.tid := entry.tid
    row.bid := entry.record.bid
    row.gid := entry.record.gid
    row.rid := entry.record.rid
    row.lsId := entry.record.loadLsId
    row.pc := entry.record.pc
    row.addr := entry.record.addr
    row.size := entry.record.size
    row
  }

  val queue = RegInit(VecInit(Seq.fill(queueEntries)(zeroEntry)))
  val count = RegInit(0.U(countWidth.W))

  val retireVec = Wire(Vec(queueEntries, Bool()))
  val flushPruneVec = Wire(Vec(queueEntries, Bool()))
  val removeVec = Wire(Vec(queueEntries, Bool()))
  val keptVec = Wire(Vec(queueEntries, Bool()))
  val keptRank = Wire(Vec(queueEntries, UInt(countWidth.W)))
  val compacted = Wire(Vec(queueEntries, new LoadResolveQueueEntry(
    liqEntries,
    idEntries,
    addrWidth,
    pcWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lineBytes,
    sizeWidth
  )))

  for (slot <- 0 until queueEntries) {
    retireVec(slot) :=
      queue(slot).valid && io.retireValid &&
        lessEqualBidLs(queue(slot).record.bid, queue(slot).record.loadLsId, io.retireBid, io.retireLsId)
    flushPruneVec(slot) := flushMatchesEntry(io.preciseFlush, queue(slot))
    removeVec(slot) := retireVec(slot) || flushPruneVec(slot)
    keptVec(slot) := queue(slot).valid && !removeVec(slot)
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

  val retireCount = PopCount(retireVec)
  val flushPruneCount = PopCount(flushPruneVec)
  val removeCount = PopCount(removeVec)
  val keptCount = count - removeCount
  val pushReady = !io.flush && (keptCount < queueEntries.U)
  val pushAccepted = io.pushValid && pushReady

  val nextQueue = Wire(Vec(queueEntries, new LoadResolveQueueEntry(
    liqEntries,
    idEntries,
    addrWidth,
    pcWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lineBytes,
    sizeWidth
  )))
  for (dst <- 0 until queueEntries) {
    nextQueue(dst) := compacted(dst)
    when(pushAccepted && (keptCount === dst.U)) {
      nextQueue(dst) := pushEntry
    }
  }

  when(io.flush) {
    queue := VecInit(Seq.fill(queueEntries)(zeroEntry))
    count := 0.U
  }.otherwise {
    queue := nextQueue
    count := keptCount + pushAccepted.asUInt
  }

  val validVec = Wire(Vec(queueEntries, Bool()))
  for (slot <- 0 until queueEntries) {
    validVec(slot) := queue(slot).valid
    io.entries(slot) := queue(slot)
    io.conflictRows(slot) := toConflict(queue(slot))
  }

  io.pushReady := pushReady
  io.pushAccepted := pushAccepted
  io.pushInsertIndex := keptCount
  io.retireMask := retireVec.asUInt
  io.retireCount := retireCount
  io.flushPruneMask := flushPruneVec.asUInt
  io.flushPruneCount := flushPruneCount
  io.validMask := validVec.asUInt
  io.count := count
  io.empty := count === 0.U
  io.full := count === queueEntries.U
}
