package linxcore.rename

import chisel3._
import chisel3.util.{log2Ceil, PopCount}

import linxcore.common._
import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

class TULinkRelationCmapEntry(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8)
    extends Bundle {
  val bid = new ROBID(p.robEntries)
  val gid = new ROBID(p.robEntries)
  val seq = new ROBID(mapQDepth)
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
}

class TULinkRelationCmapIO(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val cmapDepth: Int = 8,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8)
    extends Bundle {
  private val countWidth = log2Ceil(cmapDepth + 1)

  val in = Input(new TULinkRetireSource(p, mapQDepth, stidWidth, peIdWidth))
  val clear = Input(Bool())
  val flush = Input(new FlushBus(
    p.robEntries, stidWidth = stidWidth, lsidWidth = p.lsidWidth))
  val cleanBlockValid = Input(Bool())
  val cleanBlockBid = Input(new ROBID(p.robEntries))
  val cleanGroupValid = Input(Bool())
  val cleanGroupBid = Input(new ROBID(p.robEntries))
  val cleanGroupGid = Input(new ROBID(p.robEntries))
  val commandReady = Input(Bool())

  val inReady = Output(Bool())
  val inAccepted = Output(Bool())
  val command = Output(new TULinkRetireCommand(mapQDepth, peIdWidth, stidWidth))
  val commandFire = Output(Bool())

  val unsupportedDst = Output(Bool())
  val preReleaseT = Output(Bool())
  val preReleaseU = Output(Bool())
  val pressureReleaseT = Output(Bool())
  val pressureReleaseU = Output(Bool())
  val pendingMark = Output(Bool())
  val pendingPostReleaseT = Output(Bool())
  val pendingPostReleaseU = Output(Bool())
  val tCount = Output(UInt(countWidth.W))
  val uCount = Output(UInt(countWidth.W))
  val cleanupActive = Output(Bool())
  val cleanupPruneTCount = Output(UInt(countWidth.W))
  val cleanupPruneUCount = Output(UInt(countWidth.W))
}

class TULinkRelationCmap(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val cmapDepth: Int = 8,
    val releaseThreshold: Int = 4,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8)
    extends Module {
  require(mapQDepth > 1 && (mapQDepth & (mapQDepth - 1)) == 0, "T/U mapQ depth must be a power of two")
  require(cmapDepth > 1 && (cmapDepth & (cmapDepth - 1)) == 0, "relation cmap depth must be a power of two")
  require(releaseThreshold >= 0 && releaseThreshold < cmapDepth, "release threshold must fit relation cmap")

  private val ptrWidth = log2Ceil(cmapDepth)
  private val countWidth = log2Ceil(cmapDepth + 1)

  val io = IO(new TULinkRelationCmapIO(p, mapQDepth, cmapDepth, peIdWidth, stidWidth))

  private def zeroEntry: TULinkRelationCmapEntry =
    0.U.asTypeOf(new TULinkRelationCmapEntry(p, mapQDepth, peIdWidth, stidWidth))

  private def zeroCommand: TULinkRetireCommand =
    0.U.asTypeOf(new TULinkRetireCommand(mapQDepth, peIdWidth, stidWidth))

  private def incPtr(ptr: UInt): UInt =
    Mux(ptr === (cmapDepth - 1).U, 0.U(ptrWidth.W), ptr + 1.U)(ptrWidth - 1, 0)

  private def decPtr(ptr: UInt): UInt =
    Mux(ptr === 0.U, (cmapDepth - 1).U(ptrWidth.W), ptr - 1.U)(ptrWidth - 1, 0)

  private def addPtr(ptr: UInt, amount: UInt): UInt = {
    val sum = ptr +& amount
    val depth = cmapDepth.U(sum.getWidth.W)
    Mux(sum >= depth, sum - depth, sum)(ptrWidth - 1, 0)
  }

  private def tailFromCount(nextCount: UInt): UInt =
    Mux(nextCount === cmapDepth.U, 0.U(ptrWidth.W), nextCount(ptrWidth - 1, 0))

  private def sameBidGid(entry: TULinkRelationCmapEntry): Bool =
    ROBID.equal(entry.bid, io.in.bid) && ROBID.equal(entry.gid, io.in.gid)

  private def flushMatches(entry: TULinkRelationCmapEntry): Bool =
    Mux(io.flush.baseOnBid, ROBID.lessEqual(io.flush.req.bid, entry.bid), ROBID.less(io.flush.req.bid, entry.bid))

  private def cleanMatches(entry: TULinkRelationCmapEntry): Bool =
    (io.cleanBlockValid && ROBID.equal(entry.bid, io.cleanBlockBid)) ||
      (io.cleanGroupValid && ROBID.equal(entry.bid, io.cleanGroupBid) && ROBID.equal(entry.gid, io.cleanGroupGid))

  val tEntries = RegInit(VecInit(Seq.fill(cmapDepth)(zeroEntry)))
  val uEntries = RegInit(VecInit(Seq.fill(cmapDepth)(zeroEntry)))
  val tHead = RegInit(0.U(ptrWidth.W))
  val uHead = RegInit(0.U(ptrWidth.W))
  val tTail = RegInit(0.U(ptrWidth.W))
  val uTail = RegInit(0.U(ptrWidth.W))
  val tCount = RegInit(0.U(countWidth.W))
  val uCount = RegInit(0.U(countWidth.W))

  val pendingMarkValid = RegInit(false.B)
  val pendingMarkKind = RegInit(DestinationKind.None)
  val pendingMarkSeq = RegInit(0.U.asTypeOf(new ROBID(mapQDepth)))
  val pendingMarkPeId = RegInit(0.U(peIdWidth.W))
  val pendingMarkStid = RegInit(0.U(stidWidth.W))
  val pendingPostReleaseT = RegInit(false.B)
  val pendingPostReleaseU = RegInit(false.B)
  val cleanupActive = io.flush.req.valid || io.cleanBlockValid || io.cleanGroupValid

  def cleanupPruneVec(
      entries: Vec[TULinkRelationCmapEntry],
      head: UInt,
      count: UInt): Vec[Bool] = {
    val validVec = Wire(Vec(cmapDepth, Bool()))
    val cleanVec = Wire(Vec(cmapDepth, Bool()))
    val flushVec = Wire(Vec(cmapDepth, Bool()))
    val pruneVec = Wire(Vec(cmapDepth, Bool()))

    for (offset <- 0 until cmapDepth) {
      val entry = entries(addPtr(head, offset.U))
      validVec(offset) := offset.U < count
      cleanVec(offset) := validVec(offset) && cleanMatches(entry)
      flushVec(offset) := validVec(offset) && io.flush.req.valid && flushMatches(entry)
    }
    for (offset <- 0 until cmapDepth) {
      val newerFlush =
        if (offset == cmapDepth - 1) true.B
        else (offset + 1 until cmapDepth).map(idx => !validVec(idx) || flushVec(idx)).reduce(_ && _)
      pruneVec(offset) := cleanVec(offset) || (flushVec(offset) && newerFlush)
    }
    pruneVec
  }

  def compactEntries(
      entries: Vec[TULinkRelationCmapEntry],
      head: UInt,
      count: UInt,
      pruneVec: Vec[Bool]): (Vec[TULinkRelationCmapEntry], UInt) = {
    val nextEntries = Wire(Vec(cmapDepth, new TULinkRelationCmapEntry(p, mapQDepth, peIdWidth, stidWidth)))
    nextEntries := VecInit(Seq.fill(cmapDepth)(zeroEntry))
    val keepVec = Wire(Vec(cmapDepth, Bool()))

    for (offset <- 0 until cmapDepth) {
      val valid = offset.U < count
      val keep = valid && !pruneVec(offset)
      keepVec(offset) := keep
      val keptBefore = Wire(UInt(countWidth.W))
      keptBefore := (if (offset == 0) 0.U else PopCount(VecInit(keepVec.take(offset)).asUInt))
      when(keep) {
        nextEntries(keptBefore(ptrWidth - 1, 0)) := entries(addPtr(head, offset.U))
      }
    }

    (nextEntries, PopCount(keepVec.asUInt))
  }

  private def pendingSeqPruned(
      entries: Vec[TULinkRelationCmapEntry],
      head: UInt,
      count: UInt,
      pruneVec: Vec[Bool],
      seq: ROBID): Bool = {
    VecInit((0 until cmapDepth).map { offset =>
      val entry = entries(addPtr(head, offset.U))
      (offset.U < count) && pruneVec(offset) && ROBID.equal(entry.seq, seq)
    }).asUInt.orR
  }

  val tPruneVec = cleanupPruneVec(tEntries, tHead, tCount)
  val uPruneVec = cleanupPruneVec(uEntries, uHead, uCount)
  val tPruneCount = PopCount(tPruneVec.asUInt)
  val uPruneCount = PopCount(uPruneVec.asUInt)
  val (tCompactedEntries, tCompactedCount) = compactEntries(tEntries, tHead, tCount, tPruneVec)
  val (uCompactedEntries, uCompactedCount) = compactEntries(uEntries, uHead, uCount, uPruneVec)
  val pendingMarkPruned =
    pendingMarkValid &&
      Mux(
        pendingMarkKind === DestinationKind.T,
        pendingSeqPruned(tEntries, tHead, tCount, tPruneVec, pendingMarkSeq),
        Mux(
          pendingMarkKind === DestinationKind.U,
          pendingSeqPruned(uEntries, uHead, uCount, uPruneVec, pendingMarkSeq),
          false.B
        )
      )

  val inDstT = io.in.valid && io.in.dstValid && (io.in.dstKind === DestinationKind.T)
  val inDstU = io.in.valid && io.in.dstValid && (io.in.dstKind === DestinationKind.U)
  val unsupportedDst = io.in.valid && io.in.dstValid && !inDstT && !inDstU

  val tBack = tEntries(decPtr(tTail))
  val uBack = uEntries(decPtr(uTail))
  val tNonEmpty = tCount =/= 0.U
  val uNonEmpty = uCount =/= 0.U
  val preReleaseT = io.in.valid && tNonEmpty && (io.in.isLast || !sameBidGid(tBack))
  val preReleaseU = io.in.valid && uNonEmpty && (io.in.isLast || !sameBidGid(uBack))
  val capacityReleaseT = inDstT && (tCount === cmapDepth.U)
  val capacityReleaseU = inDstU && (uCount === cmapDepth.U)
  val pressureReleaseT = inDstT && (tCount >= releaseThreshold.U)
  val pressureReleaseU = inDstU && (uCount >= releaseThreshold.U)
  val pendingAny = pendingMarkValid || pendingPostReleaseT || pendingPostReleaseU

  val command = Wire(new TULinkRetireCommand(mapQDepth, peIdWidth, stidWidth))
  command := zeroCommand

  when(pendingMarkValid) {
    command.valid := true.B
    command.kind := pendingMarkKind
    command.seq := pendingMarkSeq
    command.dealloc := false.B
    command.peId := pendingMarkPeId
    command.stid := pendingMarkStid
  }.elsewhen(pendingPostReleaseT || preReleaseT || capacityReleaseT) {
    command.valid := tNonEmpty
    command.kind := DestinationKind.T
    command.seq := tEntries(tHead).seq
    command.dealloc := true.B
    command.peId := tEntries(tHead).peId
    command.stid := tEntries(tHead).stid
  }.elsewhen(pendingPostReleaseU || preReleaseU || capacityReleaseU) {
    command.valid := uNonEmpty
    command.kind := DestinationKind.U
    command.seq := uEntries(uHead).seq
    command.dealloc := true.B
    command.peId := uEntries(uHead).peId
    command.stid := uEntries(uHead).stid
  }

  val commandFire = command.valid && io.commandReady && !cleanupActive
  val commandReleasesT =
    commandFire && !pendingMarkValid && (command.kind === DestinationKind.T) && command.dealloc
  val commandReleasesU =
    commandFire && !pendingMarkValid && (command.kind === DestinationKind.U) && command.dealloc

  val dstQueueFull = (inDstT && (tCount === cmapDepth.U) && !commandReleasesT) ||
    (inDstU && (uCount === cmapDepth.U) && !commandReleasesU)
  val inReady = !io.clear && !cleanupActive && !pendingAny && !preReleaseT && !preReleaseU &&
    !capacityReleaseT && !capacityReleaseU && !unsupportedDst && !dstQueueFull
  val inAccepted = io.in.valid && inReady

  when(io.clear) {
    tHead := 0.U
    uHead := 0.U
    tTail := 0.U
    uTail := 0.U
    tCount := 0.U
    uCount := 0.U
    pendingMarkValid := false.B
    pendingMarkKind := DestinationKind.None
    pendingMarkSeq := 0.U.asTypeOf(new ROBID(mapQDepth))
    pendingMarkPeId := 0.U
    pendingMarkStid := 0.U
    pendingPostReleaseT := false.B
    pendingPostReleaseU := false.B
  }.elsewhen(cleanupActive) {
    tEntries := tCompactedEntries
    uEntries := uCompactedEntries
    tHead := 0.U
    uHead := 0.U
    tTail := tailFromCount(tCompactedCount)
    uTail := tailFromCount(uCompactedCount)
    tCount := tCompactedCount
    uCount := uCompactedCount
    when(pendingMarkPruned) {
      pendingMarkValid := false.B
      pendingMarkKind := DestinationKind.None
      pendingMarkSeq := 0.U.asTypeOf(new ROBID(mapQDepth))
      pendingMarkPeId := 0.U
      pendingMarkStid := 0.U
    }
    pendingPostReleaseT := pendingPostReleaseT && (tCompactedCount =/= 0.U)
    pendingPostReleaseU := pendingPostReleaseU && (uCompactedCount =/= 0.U)
  }.otherwise {
    when(commandFire && pendingMarkValid) {
      pendingMarkValid := false.B
    }.elsewhen(commandReleasesT) {
      tHead := incPtr(tHead)
      tCount := tCount - 1.U
      when(pendingPostReleaseT) {
        pendingPostReleaseT := false.B
      }
    }.elsewhen(commandReleasesU) {
      uHead := incPtr(uHead)
      uCount := uCount - 1.U
      when(pendingPostReleaseU) {
        pendingPostReleaseU := false.B
      }
    }

    when(inAccepted && inDstT) {
      val entry = Wire(new TULinkRelationCmapEntry(p, mapQDepth, peIdWidth, stidWidth))
      entry.bid := io.in.bid
      entry.gid := io.in.gid
      entry.seq := io.in.tSeq
      entry.peId := io.in.peId
      entry.stid := io.in.stid
      tEntries(tTail) := entry
      tTail := incPtr(tTail)
      tCount := tCount + 1.U
      pendingMarkValid := true.B
      pendingMarkKind := DestinationKind.T
      pendingMarkSeq := io.in.tSeq
      pendingMarkPeId := io.in.peId
      pendingMarkStid := io.in.stid
      pendingPostReleaseT := io.in.isLast || pressureReleaseT
    }.elsewhen(inAccepted && inDstU) {
      val entry = Wire(new TULinkRelationCmapEntry(p, mapQDepth, peIdWidth, stidWidth))
      entry.bid := io.in.bid
      entry.gid := io.in.gid
      entry.seq := io.in.uSeq
      entry.peId := io.in.peId
      entry.stid := io.in.stid
      uEntries(uTail) := entry
      uTail := incPtr(uTail)
      uCount := uCount + 1.U
      pendingMarkValid := true.B
      pendingMarkKind := DestinationKind.U
      pendingMarkSeq := io.in.uSeq
      pendingMarkPeId := io.in.peId
      pendingMarkStid := io.in.stid
      pendingPostReleaseU := io.in.isLast || pressureReleaseU
    }
  }

  io.inReady := inReady
  io.inAccepted := inAccepted
  io.command := Mux(io.clear || cleanupActive, zeroCommand, command)
  io.commandFire := commandFire
  io.unsupportedDst := unsupportedDst
  io.preReleaseT := preReleaseT
  io.preReleaseU := preReleaseU
  io.pressureReleaseT := pressureReleaseT
  io.pressureReleaseU := pressureReleaseU
  io.pendingMark := pendingMarkValid
  io.pendingPostReleaseT := pendingPostReleaseT
  io.pendingPostReleaseU := pendingPostReleaseU
  io.tCount := tCount
  io.uCount := uCount
  io.cleanupActive := cleanupActive
  io.cleanupPruneTCount := tPruneCount
  io.cleanupPruneUCount := uPruneCount
}
