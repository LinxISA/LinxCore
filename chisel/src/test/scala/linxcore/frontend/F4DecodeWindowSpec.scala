package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.{FrontendDecodePacket, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

object F4DecodeWindowReference {
  final case class Slot(valid: Boolean, offsetBytes: Int, lenBytes: Int, pc: BigInt, insnRaw: BigInt, uopUid: BigInt)
  final case class Result(slots: Seq[Slot], validMask: Int, slotCount: Int, totalLenBytes: Int, d1Valid: Boolean)

  private val WindowBytes = 8
  private val RawMask = (BigInt(1) << 64) - 1

  def instructionLengthBytes(insn: BigInt): Int = {
    val low4 = (insn & 0xf).toInt
    val header = (low4 >> 1) & 0x7

    if ((low4 & 0x1) == 0) {
      if (header == 0x7) 6 else 2
    } else if (header == 0x7) {
      8
    } else {
      4
    }
  }

  def decode(window: BigInt, pc: BigInt, pktUid: BigInt, valid: Boolean = true, flush: Boolean = false): Result = {
    val active = valid && !flush
    var offset = 0
    var total = 0
    var mask = 0

    val slots = (0 until 4).map { slot =>
      val shifted = (window >> (offset * 8)) & RawMask
      val len = instructionLengthBytes(shifted)
      val fits = active && offset < WindowBytes && len <= (WindowBytes - offset)
      val priorValid = slot == 0 || ((mask & (1 << (slot - 1))) != 0)
      val priorStop =
        slot > 0 &&
          slotsTakeStop(window, slots = slot, startOffset = 0)
      val slotValid = fits && priorValid && !priorStop

      if (slotValid) {
        val raw = shifted & ((BigInt(1) << (len * 8)) - 1)
        val slotResult = Slot(
          valid = true,
          offsetBytes = offset,
          lenBytes = len,
          pc = pc + offset,
          insnRaw = raw,
          uopUid = (pktUid << 3) | slot
        )
        mask |= 1 << slot
        offset += len
        total = offset
        slotResult
      } else {
        Slot(valid = false, offsetBytes = 0, lenBytes = 0, pc = 0, insnRaw = 0, uopUid = 0)
      }
    }

    Result(slots = slots, validMask = mask, slotCount = slots.count(_.valid), totalLenBytes = total, d1Valid = active)
  }

  private def slotsTakeStop(window: BigInt, slots: Int, startOffset: Int): Boolean = {
    var offset = startOffset
    var stop = false
    var idx = 0
    while (idx < slots && !stop) {
      val shifted = (window >> (offset * 8)) & RawMask
      val len = instructionLengthBytes(shifted)
      val fits = offset < WindowBytes && len <= (WindowBytes - offset)
      stop = fits && len == 2 && (shifted & 0xffff) == 0
      offset += (if (fits) len else 0)
      idx += 1
    }
    stop
  }
}

class F4DecodeWindowProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val in = Input(new FrontendDecodePacket(p))
  val flushValid = Input(Bool())
  val validMask = Output(UInt(p.decodeWidth.W))
  val totalLenBytes = Output(UInt(4.W))
  val slot0Valid = Output(Bool())
  val slot0LenBytes = Output(UInt(4.W))
}

class F4DecodeWindowProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new F4DecodeWindowProbeIO(p))

  val f4 = Module(new F4DecodeWindow(p))
  f4.io.in := io.in
  f4.io.flushValid := io.flushValid

  io.validMask := f4.io.validMask
  io.totalLenBytes := f4.io.totalLenBytes
  io.slot0Valid := f4.io.slots(0).valid
  io.slot0LenBytes := f4.io.slots(0).lenBytes
}

class F4DecodeWindowSpec extends AnyFunSuite {
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

  test("reference length rule matches LinxCoreModel CheckMInstSize low-bit encoding") {
    assert(F4DecodeWindowReference.instructionLengthBytes(0x0000) == 2)
    assert(F4DecodeWindowReference.instructionLengthBytes(0x000e) == 6)
    assert(F4DecodeWindowReference.instructionLengthBytes(0x0001) == 4)
    assert(F4DecodeWindowReference.instructionLengthBytes(0x000f) == 8)
  }

  test("reference slices four 16-bit instructions without compaction") {
    val window = pack(Seq(u(0x0010) -> 2, u(0x0020) -> 2, u(0x0030) -> 2, u(0x0040) -> 2))
    val decoded = F4DecodeWindowReference.decode(window = window, pc = 0x1000, pktUid = 0x25)

    assert(decoded.d1Valid)
    assert(decoded.validMask == 0xf)
    assert(decoded.slotCount == 4)
    assert(decoded.totalLenBytes == 8)
    assert(decoded.slots.map(_.offsetBytes) == Seq(0, 2, 4, 6))
    assert(decoded.slots.map(_.lenBytes) == Seq(2, 2, 2, 2))
    assert(decoded.slots.map(_.pc) == Seq(0x1000, 0x1002, 0x1004, 0x1006))
    assert(decoded.slots.map(_.uopUid) == Seq(0x128, 0x129, 0x12a, 0x12b))
  }

  test("reference terminates a packet after C.BSTOP") {
    val window = pack(Seq(u(0x0000) -> 2, u(0x1002) -> 2, u(0x0000) -> 2))
    val decoded = F4DecodeWindowReference.decode(window = window, pc = 0x7000, pktUid = 0x15)

    assert(decoded.validMask == 0x1)
    assert(decoded.slotCount == 1)
    assert(decoded.totalLenBytes == 2)
    assert(decoded.slots.head.pc == 0x7000)
    assert(decoded.slots.head.insnRaw == 0)
  }

  test("reference accepts mixed 32-bit and 16-bit instructions") {
    val window = pack(Seq(u(0x0000_0001L) -> 4, u(0x0010) -> 2, u(0x0020) -> 2))
    val decoded = F4DecodeWindowReference.decode(window = window, pc = 0x2000, pktUid = 0x10)

    assert(decoded.validMask == 0x7)
    assert(decoded.slotCount == 3)
    assert(decoded.totalLenBytes == 8)
    assert(decoded.slots.map(_.offsetBytes) == Seq(0, 4, 6, 0))
    assert(decoded.slots.map(_.lenBytes) == Seq(4, 2, 2, 0))
  }

  test("reference accepts 48-bit plus 16-bit window fill") {
    val window = pack(Seq(u(0x0000_0000_000eL) -> 6, u(0x0010) -> 2))
    val decoded = F4DecodeWindowReference.decode(window = window, pc = 0x3000, pktUid = 0x11)

    assert(decoded.validMask == 0x3)
    assert(decoded.slotCount == 2)
    assert(decoded.totalLenBytes == 8)
    assert(decoded.slots.map(_.offsetBytes) == Seq(0, 6, 0, 0))
    assert(decoded.slots.map(_.lenBytes) == Seq(6, 2, 0, 0))
  }

  test("reference accepts a single 64-bit instruction") {
    val window = pack(Seq(u(0x1000_0000_0000_000fL) -> 8))
    val decoded = F4DecodeWindowReference.decode(window = window, pc = 0x4000, pktUid = 0x12)

    assert(decoded.validMask == 0x1)
    assert(decoded.slotCount == 1)
    assert(decoded.totalLenBytes == 8)
    assert(decoded.slots.map(_.offsetBytes) == Seq(0, 0, 0, 0))
    assert(decoded.slots.map(_.lenBytes) == Seq(8, 0, 0, 0))
  }

  test("reference stops when the next instruction does not fit in the 8-byte window") {
    val window = pack(Seq(u(0x0000_0001L) -> 4, u(0x0000_0000_000eL) -> 4))
    val decoded = F4DecodeWindowReference.decode(window = window, pc = 0x5000, pktUid = 0x13)

    assert(decoded.validMask == 0x1)
    assert(decoded.slotCount == 1)
    assert(decoded.totalLenBytes == 4)
    assert(decoded.slots.map(_.lenBytes) == Seq(4, 0, 0, 0))
  }

  test("reference flush suppresses D1 and all decoded slots") {
    val window = pack(Seq(u(0x0000) -> 2, u(0x0010) -> 2, u(0x0020) -> 2, u(0x0030) -> 2))
    val decoded = F4DecodeWindowReference.decode(window = window, pc = 0x6000, pktUid = 0x14, flush = true)

    assert(!decoded.d1Valid)
    assert(decoded.validMask == 0)
    assert(decoded.slotCount == 0)
    assert(decoded.totalLenBytes == 0)
    assert(decoded.slots.forall(!_.valid))
  }

  test("F4 slot fields preserve model-derived length and packet identity widths") {
    val p = InterfaceParams()
    val io = new F4DecodeWindowIO(p)
    val slot = new F4Slot(p)

    assert(io.in.peId.getWidth == 8)
    assert(io.in.threadId.getWidth == 8)
    assert(io.d1.peId.getWidth == 8)
    assert(io.d1.threadId.getWidth == 8)
    assert(slot.pc.getWidth == 64)
    assert(slot.offsetBytes.getWidth == 4)
    assert(slot.lenBytes.getWidth == 4)
    assert(slot.insnRaw.getWidth == 64)
    assert(slot.uopUid.getWidth == 64)
    assert(slot.isLastInBlock.getWidth == 1)
  }

  test("F4DecodeWindow elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new F4DecodeWindowProbe(InterfaceParams()))

    assert(sv.contains("module F4DecodeWindowProbe"))
    assert(sv.contains("F4DecodeWindow"))
    assert(sv.contains("io_validMask"))
    assert(sv.contains("io_totalLenBytes"))
  }
}
