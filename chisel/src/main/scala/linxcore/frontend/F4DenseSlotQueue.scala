package linxcore.frontend

import chisel3._
import chisel3.util.{PopCount, UIntToOH, log2Ceil}
import linxcore.common.{FrontendDecodePacket, InterfaceParams}

class F4DenseSlotQueueEntry(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  private val slotIndexWidth = math.max(1, log2Ceil(p.decodeWidth))

  val d1 = new FrontendDecodePacket(p)
  val slot = new F4Slot(p)
  val slotIndex = UInt(slotIndexWidth.W)
}

class F4DenseSlotQueueIO(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 8)
    extends Bundle {
  require(depth >= p.decodeWidth, "F4DenseSlotQueue depth must hold one full F4 window")
  require((depth & (depth - 1)) == 0, "F4DenseSlotQueue depth must be a power of two")

  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)
  private val slotCountWidth = log2Ceil(p.decodeWidth + 1)
  private val slotIndexWidth = math.max(1, log2Ceil(p.decodeWidth))

  val inD1 = Input(new FrontendDecodePacket(p))
  val inSlots = Input(Vec(p.decodeWidth, new F4Slot(p)))
  val inValidMask = Input(UInt(p.decodeWidth.W))
  val inReady = Output(Bool())

  val outD1 = Output(new FrontendDecodePacket(p))
  val outSlots = Output(Vec(p.decodeWidth, new F4Slot(p)))
  val outValidMask = Output(UInt(p.decodeWidth.W))
  val outReady = Input(Bool())

  val flushValid = Input(Bool())

  val inFire = Output(Bool())
  val outFire = Output(Bool())
  val inSlotCount = Output(UInt(slotCountWidth.W))
  val count = Output(UInt(countWidth.W))
  val headSlotIndex = Output(UInt(slotIndexWidth.W))
  val full = Output(Bool())
  val empty = Output(Bool())
}

class F4DenseSlotQueue(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 8)
    extends Module {
  require(depth >= p.decodeWidth, "F4DenseSlotQueue depth must hold one full F4 window")
  require((depth & (depth - 1)) == 0, "F4DenseSlotQueue depth must be a power of two")

  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)
  private val slotCountWidth = log2Ceil(p.decodeWidth + 1)

  val io = IO(new F4DenseSlotQueueIO(p, depth))

  val entries = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new F4DenseSlotQueueEntry(p)))))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  def advancePtr(ptr: UInt, amount: UInt): UInt = (ptr + amount)(ptrWidth - 1, 0)

  val inSlotCount = PopCount(io.inValidMask)
  val freeSlots = depth.U((countWidth + 1).W) - count.pad(countWidth + 1)
  val canAcceptPacket = !io.flushValid && inSlotCount =/= 0.U && freeSlots >= inSlotCount.pad(countWidth + 1)
  val headValid = !io.flushValid && count =/= 0.U
  val inFire = io.inD1.valid && canAcceptPacket
  val outFire = headValid && io.outReady

  val headEntry = entries(head)
  val zeroD1 = 0.U.asTypeOf(new FrontendDecodePacket(p))
  val zeroSlot = 0.U.asTypeOf(new F4Slot(p))

  io.inReady := canAcceptPacket
  io.outD1 := Mux(headValid, headEntry.d1, zeroD1)
  io.outD1.valid := headValid
  for (slot <- 0 until p.decodeWidth) {
    io.outSlots(slot) := zeroSlot
    when(headValid && headEntry.slotIndex === slot.U) {
      io.outSlots(slot) := headEntry.slot
      io.outSlots(slot).valid := true.B
    }
  }
  io.outValidMask := Mux(headValid, UIntToOH(headEntry.slotIndex, p.decodeWidth), 0.U(p.decodeWidth.W))
  io.inFire := inFire
  io.outFire := outFire
  io.inSlotCount := inSlotCount
  io.count := count
  io.headSlotIndex := headEntry.slotIndex
  io.full := count === depth.U
  io.empty := count === 0.U

  when(io.flushValid) {
    head := 0.U
    tail := 0.U
    count := 0.U
    for (idx <- 0 until depth) {
      entries(idx) := 0.U.asTypeOf(new F4DenseSlotQueueEntry(p))
    }
  }.otherwise {
    when(inFire) {
      for (slot <- 0 until p.decodeWidth) {
        val priorMask =
          if (slot == 0) {
            0.U(p.decodeWidth.W)
          } else {
            io.inValidMask(slot - 1, 0).pad(p.decodeWidth)
          }
        val writeOffset = PopCount(priorMask)
        val writePtr = advancePtr(tail, writeOffset)
        when(io.inValidMask(slot)) {
          val nextEntry = Wire(new F4DenseSlotQueueEntry(p))
          nextEntry := 0.U.asTypeOf(nextEntry)
          nextEntry.d1 := io.inD1
          nextEntry.d1.valid := true.B
          nextEntry.slot := io.inSlots(slot)
          nextEntry.slot.valid := true.B
          nextEntry.slotIndex := slot.U
          entries(writePtr) := nextEntry
        }
      }
      tail := advancePtr(tail, inSlotCount)
    }

    when(outFire) {
      entries(head) := 0.U.asTypeOf(new F4DenseSlotQueueEntry(p))
      head := advancePtr(head, 1.U)
    }

    count := count + Mux(inFire, inSlotCount.pad(countWidth), 0.U) -
      Mux(outFire, 1.U(countWidth.W), 0.U)
  }
}
