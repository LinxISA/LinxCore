package linxcore.rename

import chisel3._
import chisel3.util.{log2Ceil, PopCount}

import linxcore.common._
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

  private def zeroCommand: TULinkRetireCommand =
    0.U.asTypeOf(new TULinkRetireCommand(mapQDepth))

  val sourceQueue = RegInit(VecInit(Seq.fill(sourceQueueDepth)(zeroSource)))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  val sourceValidMask = VecInit(io.sources.map(_.valid)).asUInt
  val sourceEnqueueCount = PopCount(sourceValidMask)
  val freeCount = sourceQueueDepth.U(countWidth.W) - count
  val sourceWindowReady = !io.clear && (freeCount >= sourceWidth.U)
  val enqueueCount = Mux(sourceWindowReady, sourceEnqueueCount, 0.U)
  val queueNonEmpty = count =/= 0.U

  val relation = Module(new TULinkRelationCmap(
    p = p,
    mapQDepth = mapQDepth,
    cmapDepth = cmapDepth,
    releaseThreshold = releaseThreshold,
    stidWidth = stidWidth
  ))

  val relationInput = Wire(new TULinkRetireSource(p, mapQDepth, stidWidth))
  relationInput := Mux(queueNonEmpty, sourceQueue(head), zeroSource)
  relationInput.valid := queueNonEmpty && !io.clear
  relation.io.in := relationInput
  relation.io.clear := io.clear
  relation.io.commandReady := io.commandReady && !io.clear

  val dequeueFire = relation.io.inAccepted

  when(io.clear) {
    for (idx <- 0 until sourceQueueDepth) {
      sourceQueue(idx) := zeroSource
    }
    head := 0.U
    tail := 0.U
    count := 0.U
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

  io.command := Mux(io.clear, zeroCommand, relation.io.command)
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
}
