package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.{FrontendDecodePacket, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

object FrontendDecodeIngressReference {
  final case class Output(
      pushReady: Boolean,
      d1: FrontendInstructionBufferReference.Packet,
      slots: Seq[F4DecodeWindowReference.Slot],
      validMask: Int,
      slotCount: Int,
      totalLenBytes: Int,
      popFire: Boolean,
      ibCount: Int)

  final class Model(val depth: Int = 8) {
    private val ib = new FrontendInstructionBufferReference.Model(depth)

    def step(
        push: FrontendInstructionBufferReference.Packet = FrontendInstructionBufferReference.EmptyPacket,
        decodeReady: Boolean = false,
        flush: Boolean = false): Output = {
      val current = ib.step()
      val currentDecoded = F4DecodeWindowReference.decode(
        window = current.out.window,
        pc = current.out.pc,
        pktUid = current.out.pktUid,
        valid = current.out.valid,
        flush = flush)
      val popReady = decodeReady && currentDecoded.d1Valid
      val ibOut = ib.step(push = push, popReady = popReady, flush = flush)
      val decoded = F4DecodeWindowReference.decode(
        window = ibOut.out.window,
        pc = ibOut.out.pc,
        pktUid = ibOut.out.pktUid,
        valid = ibOut.out.valid,
        flush = flush)
      val d1 = ibOut.out.copy(valid = decoded.d1Valid)

      Output(
        pushReady = ibOut.pushReady,
        d1 = d1,
        slots = decoded.slots,
        validMask = decoded.validMask,
        slotCount = decoded.slotCount,
        totalLenBytes = decoded.totalLenBytes,
        popFire = ibOut.popFire,
        ibCount = ibOut.count)
    }
  }
}

class FrontendDecodeIngressProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val push = Input(new FrontendDecodePacket(p))
  val pushReady = Output(Bool())
  val decodeReady = Input(Bool())
  val flushValid = Input(Bool())
  val d1 = Output(new FrontendDecodePacket(p))
  val validMask = Output(UInt(p.decodeWidth.W))
  val slotCount = Output(UInt(3.W))
  val popFire = Output(Bool())
  val ibCount = Output(UInt(4.W))
}

class FrontendDecodeIngressProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new FrontendDecodeIngressProbeIO(p))
  val ingress = Module(new FrontendDecodeIngress(p, ibufDepth = 8))

  ingress.io.push := io.push
  ingress.io.decodeReady := io.decodeReady
  ingress.io.flushValid := io.flushValid

  io.pushReady := ingress.io.pushReady
  io.d1 := ingress.io.d1
  io.validMask := ingress.io.validMask
  io.slotCount := ingress.io.slotCount
  io.popFire := ingress.io.popFire
  io.ibCount := ingress.io.ibCount
}

class FrontendDecodeIngressSpec extends AnyFunSuite {
  private def u(value: Long): BigInt = BigInt(value)

  private def pack(chunks: Seq[(BigInt, Int)]): BigInt = {
    var shift = 0
    var window = BigInt(0)
    chunks.foreach { case (value, bytes) =>
      window |= (value & ((BigInt(1) << (bytes * 8)) - 1)) << shift
      shift += bytes * 8
    }
    window
  }

  private def packet(id: Int, window: BigInt): FrontendInstructionBufferReference.Packet =
    FrontendInstructionBufferReference.Packet(
      valid = true,
      pc = 0x8000 + id * 0x10,
      window = window,
      pktUid = 0x80 + id,
      checkpointId = id & 0x3f)

  test("reference exposes a pushed packet only after FIFO residency") {
    val ingress = new FrontendDecodeIngressReference.Model(depth = 4)
    val window = pack(Seq(u(0x0000) -> 2, u(0x0010) -> 2, u(0x0020) -> 2, u(0x0030) -> 2))

    val push = ingress.step(push = packet(1, window), decodeReady = true)
    assert(push.pushReady)
    assert(!push.d1.valid)
    assert(!push.popFire)

    val visible = ingress.step(decodeReady = false)
    assert(visible.d1.valid)
    assert(visible.validMask == 0xf)
    assert(visible.slotCount == 4)
    assert(visible.ibCount == 1)
  }

  test("reference holds the head packet until decode side is ready") {
    val ingress = new FrontendDecodeIngressReference.Model(depth = 4)
    val window = pack(Seq(u(0x0000_0001L) -> 4, u(0x0010) -> 2, u(0x0020) -> 2))

    ingress.step(push = packet(2, window))
    val stalled = ingress.step(decodeReady = false)
    assert(stalled.d1.valid)
    assert(!stalled.popFire)
    assert(stalled.ibCount == 1)

    val consumed = ingress.step(decodeReady = true)
    assert(consumed.popFire)
    assert(consumed.d1.valid)

    val empty = ingress.step()
    assert(!empty.d1.valid)
    assert(empty.ibCount == 0)
  }

  test("reference combines FIFO identity with F4 slot slicing") {
    val ingress = new FrontendDecodeIngressReference.Model(depth = 4)
    val window = pack(Seq(u(0x0000_0000_000eL) -> 6, u(0x0010) -> 2))

    ingress.step(push = packet(3, window))
    val out = ingress.step(decodeReady = true)

    assert(out.d1.valid)
    assert(out.d1.pc == 0x8030)
    assert(out.d1.pktUid == 0x83)
    assert(out.d1.checkpointId == 3)
    assert(out.validMask == 0x3)
    assert(out.slotCount == 2)
    assert(out.totalLenBytes == 8)
    assert(out.slots.map(_.offsetBytes) == Seq(0, 6, 0, 0))
    assert(out.slots.map(_.uopUid) == Seq(0x418, 0x419, 0, 0))
  }

  test("reference flush clears the FIFO and masks decoded visibility") {
    val ingress = new FrontendDecodeIngressReference.Model(depth = 4)
    val window = pack(Seq(u(0x0000) -> 2, u(0x0010) -> 2, u(0x0020) -> 2, u(0x0030) -> 2))

    ingress.step(push = packet(4, window))
    val flushed = ingress.step(decodeReady = true, flush = true)
    assert(!flushed.pushReady)
    assert(!flushed.d1.valid)
    assert(!flushed.popFire)
    assert(flushed.validMask == 0)

    val after = ingress.step()
    assert(!after.d1.valid)
    assert(after.ibCount == 0)
  }

  test("reference applies FIFO backpressure at ingress") {
    val ingress = new FrontendDecodeIngressReference.Model(depth = 2)
    val window = pack(Seq(u(0x0000) -> 2))

    assert(ingress.step(push = packet(5, window)).pushReady)
    assert(ingress.step(push = packet(6, window)).pushReady)
    val full = ingress.step(push = packet(7, window))
    assert(!full.pushReady)
    assert(full.ibCount == 2)
    assert(full.d1.pktUid == 0x85)
  }

  test("IO fields preserve ingress debug and F4 contracts") {
    val p = InterfaceParams()
    val io = new FrontendDecodeIngressIO(p, ibufDepth = 8)

    assert(io.push.peId.getWidth == 8)
    assert(io.push.threadId.getWidth == 8)
    assert(io.push.pc.getWidth == 64)
    assert(io.push.checkpointId.getWidth == 6)
    assert(io.d1.peId.getWidth == 8)
    assert(io.d1.threadId.getWidth == 8)
    assert(io.d1.pktUid.getWidth == 64)
    assert(io.validMask.getWidth == 4)
    assert(io.slotCount.getWidth == 3)
    assert(io.totalLenBytes.getWidth == 4)
    assert(io.ibHead.getWidth == 3)
    assert(io.ibTail.getWidth == 3)
    assert(io.ibCount.getWidth == 4)
  }

  test("FrontendDecodeIngress elaborates through Chisel with IB and F4 children") {
    val sv = ChiselStage.emitSystemVerilog(new FrontendDecodeIngressProbe(InterfaceParams()))

    assert(sv.contains("module FrontendDecodeIngressProbe"))
    assert(sv.contains("FrontendDecodeIngress"))
    assert(sv.contains("FrontendInstructionBuffer"))
    assert(sv.contains("F4DecodeWindow"))
    assert(sv.contains("io_pushReady"))
    assert(sv.contains("io_popFire"))
  }
}
