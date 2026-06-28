package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.{FrontendDecodePacket, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

object FrontendInstructionBufferReference {
  final case class Packet(valid: Boolean, pc: BigInt, window: BigInt, pktUid: BigInt, checkpointId: BigInt)
  final case class Output(
      pushReady: Boolean,
      out: Packet,
      popFire: Boolean,
      head: Int,
      tail: Int,
      count: Int)

  val EmptyPacket: Packet = Packet(valid = false, pc = 0, window = 0, pktUid = 0, checkpointId = 0)

  final class Model(val depth: Int = 8) {
    require(depth > 0 && (depth & (depth - 1)) == 0)

    private var entries = Vector.fill(depth)(EmptyPacket)
    private var head = 0
    private var tail = 0
    private var count = 0

    def step(push: Packet = EmptyPacket, popReady: Boolean = false, flush: Boolean = false): Output = {
      val full = count == depth
      val empty = count == 0
      val outBefore = if (empty) EmptyPacket else entries(head).copy(valid = entries(head).valid && !flush)
      val pushReady = !flush && !full
      val pushFire = push.valid && pushReady
      val popFire = outBefore.valid && popReady

      val observed = Output(
        pushReady = pushReady,
        out = outBefore,
        popFire = popFire,
        head = head,
        tail = tail,
        count = count)

      if (flush) {
        entries = Vector.fill(depth)(EmptyPacket)
        head = 0
        tail = 0
        count = 0
      } else {
        if (pushFire) {
          entries = entries.updated(tail, push)
          tail = (tail + 1) & (depth - 1)
        }
        if (popFire) {
          entries = entries.updated(head, entries(head).copy(valid = false))
          head = (head + 1) & (depth - 1)
        }
        if (pushFire && !popFire) {
          count += 1
        } else if (!pushFire && popFire) {
          count -= 1
        }
      }

      observed
    }

    def snapshot: Output = step()
  }
}

class FrontendInstructionBufferProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val push = Input(new FrontendDecodePacket(p))
  val pushReady = Output(Bool())
  val popReady = Input(Bool())
  val out = Output(new FrontendDecodePacket(p))
  val popFire = Output(Bool())
  val flushValid = Input(Bool())
  val count = Output(UInt(4.W))
}

class FrontendInstructionBufferProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new FrontendInstructionBufferProbeIO(p))
  val ib = Module(new FrontendInstructionBuffer(p, depth = 8))

  ib.io.push := io.push
  ib.io.popReady := io.popReady
  ib.io.flushValid := io.flushValid

  io.pushReady := ib.io.pushReady
  io.out := ib.io.out
  io.popFire := ib.io.popFire
  io.count := ib.io.count
}

class FrontendInstructionBufferSpec extends AnyFunSuite {
  private def packet(id: Int): FrontendInstructionBufferReference.Packet =
    FrontendInstructionBufferReference.Packet(
      valid = true,
      pc = 0x1000 + id * 0x10,
      window = BigInt("aaaabbbbcccc0000", 16) + id,
      pktUid = 0x40 + id,
      checkpointId = id & 0x3f)

  test("reference preserves FIFO order and packet identity") {
    val ib = new FrontendInstructionBufferReference.Model(depth = 4)

    assert(ib.step(push = packet(1)).pushReady)
    assert(ib.step(push = packet(2)).out.pktUid == 0x41)

    val first = ib.step(popReady = true)
    assert(first.popFire)
    assert(first.out.pc == 0x1010)
    assert(first.out.pktUid == 0x41)
    assert(first.out.checkpointId == 1)

    val second = ib.step(popReady = true)
    assert(second.popFire)
    assert(second.out.pc == 0x1020)
    assert(second.out.pktUid == 0x42)
    assert(second.out.checkpointId == 2)
  }

  test("reference keeps count stable for simultaneous push and pop") {
    val ib = new FrontendInstructionBufferReference.Model(depth = 4)

    ib.step(push = packet(1))
    val both = ib.step(push = packet(2), popReady = true)
    assert(both.popFire)
    assert(both.count == 1)

    val after = ib.snapshot
    assert(after.count == 1)
    assert(after.out.pktUid == 0x42)
  }

  test("reference applies backpressure when full") {
    val ib = new FrontendInstructionBufferReference.Model(depth = 2)

    assert(ib.step(push = packet(1)).pushReady)
    assert(ib.step(push = packet(2)).pushReady)
    val full = ib.step(push = packet(3))
    assert(!full.pushReady)
    assert(full.count == 2)
    assert(full.out.pktUid == 0x41)
  }

  test("reference flush clears occupancy and masks visible output") {
    val ib = new FrontendInstructionBufferReference.Model(depth = 4)

    ib.step(push = packet(1))
    ib.step(push = packet(2))
    val flushed = ib.step(popReady = true, flush = true)
    assert(!flushed.pushReady)
    assert(!flushed.out.valid)
    assert(!flushed.popFire)

    val after = ib.snapshot
    assert(after.count == 0)
    assert(after.head == 0)
    assert(after.tail == 0)
    assert(!after.out.valid)
  }

  test("IO fields preserve the frontend packet contract") {
    val p = InterfaceParams()
    val io = new FrontendInstructionBufferIO(p, depth = 8)

    assert(io.push.pc.getWidth == 64)
    assert(io.push.window.getWidth == 64)
    assert(io.push.pktUid.getWidth == 64)
    assert(io.push.checkpointId.getWidth == 6)
    assert(io.out.checkpointId.getWidth == 6)
    assert(io.head.getWidth == 3)
    assert(io.tail.getWidth == 3)
    assert(io.count.getWidth == 4)
  }

  test("FrontendInstructionBuffer elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new FrontendInstructionBufferProbe(InterfaceParams()))

    assert(sv.contains("module FrontendInstructionBufferProbe"))
    assert(sv.contains("FrontendInstructionBuffer"))
    assert(sv.contains("io_pushReady"))
    assert(sv.contains("io_popFire"))
    assert(sv.contains("io_count"))
  }
}
