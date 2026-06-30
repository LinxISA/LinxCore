package linxcore.frontend

import chisel3._
import chisel3.util.log2Ceil
import circt.stage.ChiselStage
import linxcore.common.{FrontendDecodePacket, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

object F4DenseSlotQueueReference {
  final case class Slot(pc: BigInt, len: Int, slotIndex: Int, uopUid: BigInt)

  final class Model(depth: Int = 8) {
    private var queue = Vector.empty[Slot]

    def inReady(slotCount: Int): Boolean =
      slotCount > 0 && queue.size + slotCount <= depth

    def enqueue(slots: Seq[Slot]): Boolean = {
      if (!inReady(slots.size)) {
        return false
      }
      queue = queue ++ slots
      true
    }

    def dequeue(): Option[Slot] = {
      val head = queue.headOption
      queue = queue.drop(1)
      head
    }

    def flush(): Unit = {
      queue = Vector.empty
    }

    def count: Int = queue.size
  }
}

class F4DenseSlotQueueProbeIO(val p: InterfaceParams = InterfaceParams(), val depth: Int = 8) extends Bundle {
  val d1 = Input(new FrontendDecodePacket(p))
  val slots = Input(Vec(p.decodeWidth, new F4Slot(p)))
  val validMask = Input(UInt(p.decodeWidth.W))
  val outReady = Input(Bool())
  val flushValid = Input(Bool())
  val inReady = Output(Bool())
  val outValidMask = Output(UInt(p.decodeWidth.W))
  val inFire = Output(Bool())
  val outFire = Output(Bool())
  val inSlotCount = Output(UInt(log2Ceil(p.decodeWidth + 1).W))
  val count = Output(UInt(log2Ceil(depth + 1).W))
  val headSlotIndex = Output(UInt(math.max(1, log2Ceil(p.decodeWidth)).W))
}

class F4DenseSlotQueueProbe(val p: InterfaceParams = InterfaceParams(), val depth: Int = 8) extends Module {
  val io = IO(new F4DenseSlotQueueProbeIO(p, depth))
  val queue = Module(new F4DenseSlotQueue(p, depth))

  queue.io.inD1 := io.d1
  queue.io.inSlots := io.slots
  queue.io.inValidMask := io.validMask
  queue.io.outReady := io.outReady
  queue.io.flushValid := io.flushValid

  io.inReady := queue.io.inReady
  io.outValidMask := queue.io.outValidMask
  io.inFire := queue.io.inFire
  io.outFire := queue.io.outFire
  io.inSlotCount := queue.io.inSlotCount
  io.count := queue.io.count
  io.headSlotIndex := queue.io.headSlotIndex
}

class F4DenseSlotQueueSpec extends AnyFunSuite {
  test("reference preserves dense F4 slot order and flushes queued slots") {
    val model = new F4DenseSlotQueueReference.Model(depth = 4)
    val slots = Seq(
      F4DenseSlotQueueReference.Slot(pc = 0x1000, len = 2, slotIndex = 0, uopUid = 0x40),
      F4DenseSlotQueueReference.Slot(pc = 0x1002, len = 4, slotIndex = 1, uopUid = 0x41),
      F4DenseSlotQueueReference.Slot(pc = 0x1006, len = 2, slotIndex = 2, uopUid = 0x42)
    )

    assert(model.enqueue(slots))
    assert(model.count == 3)
    assert(model.dequeue().map(_.slotIndex).contains(0))
    assert(model.dequeue().map(_.pc).contains(0x1002))
    assert(model.count == 1)
    model.flush()
    assert(model.count == 0)
  }

  test("interface exposes packet capture and single-slot drain diagnostics") {
    val p = InterfaceParams()
    val io = new F4DenseSlotQueueIO(p, depth = 8)

    assert(io.inValidMask.getWidth == 4)
    assert(io.outValidMask.getWidth == 4)
    assert(io.inSlotCount.getWidth == 3)
    assert(io.count.getWidth == 4)
    assert(io.headSlotIndex.getWidth == 2)
  }

  test("F4DenseSlotQueue elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new F4DenseSlotQueueProbe(InterfaceParams(), depth = 8))

    assert(sv.contains("module F4DenseSlotQueueProbe"))
    assert(sv.contains("module F4DenseSlotQueue"))
    assert(sv.contains("io_inSlotCount"))
    assert(sv.contains("io_outValidMask"))
  }
}
