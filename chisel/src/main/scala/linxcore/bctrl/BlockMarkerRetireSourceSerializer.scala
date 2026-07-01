package linxcore.bctrl

import chisel3._
import chisel3.util.{log2Ceil, PopCount}

import linxcore.common.{BlockMarkerRetireSource, InterfaceParams}

class BlockMarkerRetireSourceSerializerIO(
    val p: InterfaceParams = InterfaceParams(),
    val sourceWidth: Int = 4,
    val sourceQueueDepth: Int = 8,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8)
    extends Bundle {
  private val sourceCountWidth = log2Ceil(sourceWidth + 1)
  private val sourceQueueCountWidth = log2Ceil(sourceQueueDepth + 1)

  val sources = Input(Vec(sourceWidth, new BlockMarkerRetireSource(
    entries = p.robEntries,
    blockBidWidth = p.blockBidWidth,
    pcWidth = p.pcWidth,
    insnWidth = p.insnWidth,
    lenWidth = p.lenWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth
  )))
  val clear = Input(Bool())
  val outReady = Input(Bool())

  val sourceWindowReady = Output(Bool())
  val sourceValidMask = Output(UInt(sourceWidth.W))
  val sourceEnqueueCount = Output(UInt(sourceCountWidth.W))
  val sourceQueueCount = Output(UInt(sourceQueueCountWidth.W))
  val sourceQueueFull = Output(Bool())
  val sourceQueueEmpty = Output(Bool())
  val sourceDequeued = Output(Bool())
  val out = Output(new BlockMarkerRetireSource(
    entries = p.robEntries,
    blockBidWidth = p.blockBidWidth,
    pcWidth = p.pcWidth,
    insnWidth = p.insnWidth,
    lenWidth = p.lenWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth
  ))
}

class BlockMarkerRetireSourceSerializer(
    val p: InterfaceParams = InterfaceParams(),
    val sourceWidth: Int = 4,
    val sourceQueueDepth: Int = 8,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8)
    extends Module {
  require(sourceWidth > 0, "marker retire source width must be positive")
  require(sourceQueueDepth > 1, "marker retire source queue depth must be greater than one")
  require(sourceQueueDepth >= sourceWidth, "marker retire source queue must hold at least one full source window")
  require((sourceQueueDepth & (sourceQueueDepth - 1)) == 0, "marker retire source queue depth must be a power of two")

  private val ptrWidth = log2Ceil(sourceQueueDepth)
  private val countWidth = log2Ceil(sourceQueueDepth + 1)

  val io = IO(new BlockMarkerRetireSourceSerializerIO(
    p = p,
    sourceWidth = sourceWidth,
    sourceQueueDepth = sourceQueueDepth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth
  ))

  private def sourceType: BlockMarkerRetireSource = new BlockMarkerRetireSource(
    entries = p.robEntries,
    blockBidWidth = p.blockBidWidth,
    pcWidth = p.pcWidth,
    insnWidth = p.insnWidth,
    lenWidth = p.lenWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth
  )

  private def zeroSource: BlockMarkerRetireSource =
    0.U.asTypeOf(sourceType)

  private def addPtr(ptr: UInt, amount: UInt): UInt = {
    val sum = ptr +& amount
    val depth = sourceQueueDepth.U(sum.getWidth.W)
    Mux(sum >= depth, sum - depth, sum)(ptrWidth - 1, 0)
  }

  val sourceQueue = RegInit(VecInit(Seq.fill(sourceQueueDepth)(zeroSource)))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  val sourceValidMask = VecInit(io.sources.map(_.valid)).asUInt
  val sourceValidCount = PopCount(sourceValidMask)
  val freeCount = sourceQueueDepth.U(countWidth.W) - count
  val sourceWindowReady = !io.clear && (freeCount >= sourceWidth.U)
  val enqueueCount = Mux(sourceWindowReady, sourceValidCount, 0.U)
  val queueNonEmpty = count =/= 0.U
  val dequeueFire = queueNonEmpty && io.outReady && !io.clear

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
        if (slot == 0) 0.U else PopCount(VecInit((0 until slot).map(idx => io.sources(idx).valid)).asUInt)
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

  val outSource = Wire(sourceType)
  outSource := Mux(queueNonEmpty && !io.clear, sourceQueue(head), zeroSource)
  outSource.valid := queueNonEmpty && !io.clear

  io.sourceWindowReady := sourceWindowReady
  io.sourceValidMask := sourceValidMask
  io.sourceEnqueueCount := enqueueCount
  io.sourceQueueCount := count
  io.sourceQueueFull := count === sourceQueueDepth.U
  io.sourceQueueEmpty := count === 0.U
  io.sourceDequeued := dequeueFire
  io.out := outSource
}
