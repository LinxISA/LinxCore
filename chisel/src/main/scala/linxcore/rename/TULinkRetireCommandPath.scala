package linxcore.rename

import chisel3._
import chisel3.util.{log2Ceil, PopCount}

import linxcore.common._
import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

class TULinkRetireCommandPathIO(
    val p: InterfaceParams = InterfaceParams(),
    val sourceWidth: Int = 2,
    val mapQDepth: Int = 32,
    val sourceQueueDepth: Int = 8,
    val cmapDepth: Int = 8,
    val stidWidth: Int = 8)
    extends Bundle {
  private val sourceCountWidth = log2Ceil(sourceWidth + 1)
  private val sourceQueueCountWidth = log2Ceil(sourceQueueDepth + 1)
  private val cmapCountWidth = log2Ceil(cmapDepth + 1)

  val sources = Input(Vec(sourceWidth, new TULinkRetireSource(p, mapQDepth, stidWidth)))
  val clear = Input(Bool())
  val flush = Input(new FlushBus(p.robEntries, stidWidth = stidWidth))
  val cleanBlockValid = Input(Bool())
  val cleanBlockBid = Input(new ROBID(p.robEntries))
  val cleanGroupValid = Input(Bool())
  val cleanGroupBid = Input(new ROBID(p.robEntries))
  val cleanGroupGid = Input(new ROBID(p.robEntries))
  val commandReady = Input(Bool())

  val sourceWindowReady = Output(Bool())
  val sourceValidMask = Output(UInt(sourceWidth.W))
  val sourceEnqueueCount = Output(UInt(sourceCountWidth.W))
  val sourceQueueCount = Output(UInt(sourceQueueCountWidth.W))
  val sourceQueueFull = Output(Bool())
  val sourceQueueEmpty = Output(Bool())
  val sourceDequeued = Output(Bool())

  val command = Output(new TULinkRetireCommand(mapQDepth))
  val commandFire = Output(Bool())
  val unsupportedDst = Output(Bool())
  val preReleaseT = Output(Bool())
  val preReleaseU = Output(Bool())
  val pressureReleaseT = Output(Bool())
  val pressureReleaseU = Output(Bool())
  val pendingMark = Output(Bool())
  val pendingPostReleaseT = Output(Bool())
  val pendingPostReleaseU = Output(Bool())
  val tCount = Output(UInt(cmapCountWidth.W))
  val uCount = Output(UInt(cmapCountWidth.W))
  val cleanupActive = Output(Bool())
  val sourcePruneCount = Output(UInt(sourceQueueCountWidth.W))
  val relationPruneTCount = Output(UInt(cmapCountWidth.W))
  val relationPruneUCount = Output(UInt(cmapCountWidth.W))
}

class TULinkRetireCommandPath(
    val p: InterfaceParams = InterfaceParams(),
    val sourceWidth: Int = 2,
    val mapQDepth: Int = 32,
    val sourceQueueDepth: Int = 8,
    val cmapDepth: Int = 8,
    val releaseThreshold: Int = 4,
    val stidWidth: Int = 8)
    extends Module {
  require(sourceWidth > 0, "retire source width must be positive")
  require(sourceQueueDepth > 1, "retire source queue depth must be greater than one")
  require(sourceQueueDepth >= sourceWidth, "retire source queue must hold at least one full source window")
  require((sourceQueueDepth & (sourceQueueDepth - 1)) == 0, "retire source queue depth must be a power of two")
  require(mapQDepth > 1 && (mapQDepth & (mapQDepth - 1)) == 0, "T/U mapQ depth must be a power of two")
  require(cmapDepth > 1 && (cmapDepth & (cmapDepth - 1)) == 0, "relation cmap depth must be a power of two")

  private val ptrWidth = log2Ceil(sourceQueueDepth)
  private val countWidth = log2Ceil(sourceQueueDepth + 1)

  val io = IO(new TULinkRetireCommandPathIO(
    p = p,
    sourceWidth = sourceWidth,
    mapQDepth = mapQDepth,
    sourceQueueDepth = sourceQueueDepth,
    cmapDepth = cmapDepth,
    stidWidth = stidWidth
  ))

  private def zeroSource: TULinkRetireSource =
    0.U.asTypeOf(new TULinkRetireSource(p, mapQDepth, stidWidth))

  private def addPtr(ptr: UInt, amount: UInt): UInt = {
    val sum = ptr +& amount
    val depth = sourceQueueDepth.U(sum.getWidth.W)
    Mux(sum >= depth, sum - depth, sum)(ptrWidth - 1, 0)
  }

  private def tailFromCount(nextCount: UInt): UInt =
    Mux(nextCount === sourceQueueDepth.U, 0.U(ptrWidth.W), nextCount(ptrWidth - 1, 0))

  private def zeroCommand: TULinkRetireCommand =
    0.U.asTypeOf(new TULinkRetireCommand(mapQDepth))

  private def flushMatches(source: TULinkRetireSource): Bool =
    Mux(io.flush.baseOnBid, ROBID.lessEqual(io.flush.req.bid, source.bid), ROBID.less(io.flush.req.bid, source.bid))

  private def cleanMatches(source: TULinkRetireSource): Bool =
    (io.cleanBlockValid && ROBID.equal(source.bid, io.cleanBlockBid)) ||
      (io.cleanGroupValid && ROBID.equal(source.bid, io.cleanGroupBid) && ROBID.equal(source.gid, io.cleanGroupGid))

  val sourceQueue = RegInit(VecInit(Seq.fill(sourceQueueDepth)(zeroSource)))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  val sourceValidMask = VecInit(io.sources.map(_.valid)).asUInt
  val sourceEnqueueCount = PopCount(sourceValidMask)
  val freeCount = sourceQueueDepth.U(countWidth.W) - count
  val cleanupActive = io.flush.req.valid || io.cleanBlockValid || io.cleanGroupValid
  val sourceWindowReady = !io.clear && !cleanupActive && (freeCount >= sourceWidth.U)
  val enqueueCount = Mux(sourceWindowReady, sourceEnqueueCount, 0.U)
  val queueNonEmpty = count =/= 0.U

  val sourceValidVec = Wire(Vec(sourceQueueDepth, Bool()))
  val sourceCleanVec = Wire(Vec(sourceQueueDepth, Bool()))
  val sourceFlushVec = Wire(Vec(sourceQueueDepth, Bool()))
  val sourcePruneVec = Wire(Vec(sourceQueueDepth, Bool()))
  for (offset <- 0 until sourceQueueDepth) {
    val source = sourceQueue(addPtr(head, offset.U))
    sourceValidVec(offset) := offset.U < count
    sourceCleanVec(offset) := sourceValidVec(offset) && cleanMatches(source)
    sourceFlushVec(offset) := sourceValidVec(offset) && io.flush.req.valid && flushMatches(source)
  }
  for (offset <- 0 until sourceQueueDepth) {
    val newerFlush =
      if (offset == sourceQueueDepth - 1) true.B
      else (offset + 1 until sourceQueueDepth).map(idx => !sourceValidVec(idx) || sourceFlushVec(idx)).reduce(_ && _)
    sourcePruneVec(offset) := sourceCleanVec(offset) || (sourceFlushVec(offset) && newerFlush)
  }

  val sourceCompacted = Wire(Vec(sourceQueueDepth, new TULinkRetireSource(p, mapQDepth, stidWidth)))
  sourceCompacted := VecInit(Seq.fill(sourceQueueDepth)(zeroSource))
  val sourceKeepVec = Wire(Vec(sourceQueueDepth, Bool()))
  for (offset <- 0 until sourceQueueDepth) {
    val keep = sourceValidVec(offset) && !sourcePruneVec(offset)
    sourceKeepVec(offset) := keep
    val keptBefore = Wire(UInt(countWidth.W))
    keptBefore := (if (offset == 0) 0.U else PopCount(VecInit(sourceKeepVec.take(offset)).asUInt))
    when(keep) {
      sourceCompacted(keptBefore(ptrWidth - 1, 0)) := sourceQueue(addPtr(head, offset.U))
    }
  }
  val sourcePruneCount = PopCount(sourcePruneVec.asUInt)
  val sourceCompactedCount = PopCount(sourceKeepVec.asUInt)

  val relation = Module(new TULinkRelationCmap(
    p = p,
    mapQDepth = mapQDepth,
    cmapDepth = cmapDepth,
    releaseThreshold = releaseThreshold,
    stidWidth = stidWidth
  ))

  val relationInput = Wire(new TULinkRetireSource(p, mapQDepth, stidWidth))
  relationInput := Mux(queueNonEmpty, sourceQueue(head), zeroSource)
  relationInput.valid := queueNonEmpty && !io.clear && !cleanupActive
  relation.io.in := relationInput
  relation.io.clear := io.clear
  relation.io.flush := io.flush
  relation.io.cleanBlockValid := io.cleanBlockValid
  relation.io.cleanBlockBid := io.cleanBlockBid
  relation.io.cleanGroupValid := io.cleanGroupValid
  relation.io.cleanGroupBid := io.cleanGroupBid
  relation.io.cleanGroupGid := io.cleanGroupGid
  relation.io.commandReady := io.commandReady && !io.clear && !cleanupActive

  val dequeueFire = relation.io.inAccepted

  when(io.clear) {
    for (idx <- 0 until sourceQueueDepth) {
      sourceQueue(idx) := zeroSource
    }
    head := 0.U
    tail := 0.U
    count := 0.U
  }.elsewhen(cleanupActive) {
    sourceQueue := sourceCompacted
    head := 0.U
    tail := tailFromCount(sourceCompactedCount)
    count := sourceCompactedCount
  }.otherwise {
    for (slot <- 0 until sourceWidth) {
      val priorValidCount =
        if (slot == 0) 0.U else PopCount(sourceValidMask(slot - 1, 0))
      val writeIndex = addPtr(tail, priorValidCount)
      when(sourceWindowReady && io.sources(slot).valid) {
        sourceQueue(writeIndex) := io.sources(slot)
      }
    }

    when(dequeueFire) {
      head := addPtr(head, 1.U)
    }
    when(enqueueCount =/= 0.U) {
      tail := addPtr(tail, enqueueCount)
    }
    count := count + enqueueCount - dequeueFire.asUInt
  }

  io.sourceWindowReady := sourceWindowReady
  io.sourceValidMask := sourceValidMask
  io.sourceEnqueueCount := enqueueCount
  io.sourceQueueCount := count
  io.sourceQueueFull := count === sourceQueueDepth.U
  io.sourceQueueEmpty := count === 0.U
  io.sourceDequeued := dequeueFire

  io.command := Mux(io.clear || cleanupActive, zeroCommand, relation.io.command)
  io.commandFire := relation.io.commandFire
  io.unsupportedDst := relation.io.unsupportedDst
  io.preReleaseT := relation.io.preReleaseT
  io.preReleaseU := relation.io.preReleaseU
  io.pressureReleaseT := relation.io.pressureReleaseT
  io.pressureReleaseU := relation.io.pressureReleaseU
  io.pendingMark := relation.io.pendingMark
  io.pendingPostReleaseT := relation.io.pendingPostReleaseT
  io.pendingPostReleaseU := relation.io.pendingPostReleaseU
  io.tCount := relation.io.tCount
  io.uCount := relation.io.uCount
  io.cleanupActive := cleanupActive || relation.io.cleanupActive
  io.sourcePruneCount := sourcePruneCount
  io.relationPruneTCount := relation.io.cleanupPruneTCount
  io.relationPruneUCount := relation.io.cleanupPruneUCount
}
