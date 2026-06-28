package linxcore.frontend

import chisel3._
import chisel3.util.{MuxCase, MuxLookup, PopCount, log2Ceil}
import linxcore.common.{FrontendDecodePacket, InterfaceParams}

class F4Slot(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  require(p.insnWidth == p.windowWidth, "F4Slot currently carries the full 64-bit fetch window payload")

  val valid = Bool()
  val pc = UInt(p.pcWidth.W)
  val offsetBytes = UInt(4.W)
  val lenBytes = UInt(4.W)
  val insnRaw = UInt(p.insnWidth.W)
  val uopUid = UInt(p.uopUidWidth.W)
}

class F4DecodeWindowIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val in = Input(new FrontendDecodePacket(p))
  val flushValid = Input(Bool())
  val d1 = Output(new FrontendDecodePacket(p))
  val slots = Output(Vec(p.decodeWidth, new F4Slot(p)))
  val validMask = Output(UInt(p.decodeWidth.W))
  val slotCount = Output(UInt(log2Ceil(p.decodeWidth + 1).W))
  val totalLenBytes = Output(UInt(4.W))
}

object F4DecodeWindow {
  val WindowBytes: Int = 8

  def instructionLengthBytes(insnLowBits: UInt): UInt = {
    val wideEscape = insnLowBits(3, 1) === "b111".U

    Mux(
      insnLowBits(0) === 0.U,
      Mux(wideEscape, 6.U(4.W), 2.U(4.W)),
      Mux(wideEscape, 8.U(4.W), 4.U(4.W))
    )
  }

  def lowBytesMask(lenBytes: UInt): UInt =
    MuxLookup(lenBytes, "hffff_ffff_ffff_ffff".U(64.W))(
      Seq(
        2.U -> "h0000_0000_0000_ffff".U(64.W),
        4.U -> "h0000_0000_ffff_ffff".U(64.W),
        6.U -> "h0000_ffff_ffff_ffff".U(64.W),
        8.U -> "hffff_ffff_ffff_ffff".U(64.W)
      )
    )
}

class F4DecodeWindow(val p: InterfaceParams = InterfaceParams()) extends Module {
  require(p.decodeWidth == 4, "F4DecodeWindow models the current 4-slot 8-byte frontend window")
  require(p.windowWidth == 64, "F4DecodeWindow expects the current 64-bit frontend window")
  require(p.insnWidth == 64, "F4DecodeWindow emits a 64-bit raw instruction payload")

  val io = IO(new F4DecodeWindowIO(p))

  val active = io.in.valid && !io.flushValid
  val offsets = Wire(Vec(p.decodeWidth, UInt(4.W)))
  val lens = Wire(Vec(p.decodeWidth, UInt(4.W)))
  val valids = Wire(Vec(p.decodeWidth, Bool()))
  val shiftedWindows = Wire(Vec(p.decodeWidth, UInt(p.windowWidth.W)))
  val rawInsns = Wire(Vec(p.decodeWidth, UInt(p.insnWidth.W)))
  val endBytes = Wire(Vec(p.decodeWidth, UInt(4.W)))

  io.d1 := io.in
  io.d1.valid := active

  offsets(0) := 0.U

  for (slot <- 0 until p.decodeWidth) {
    shiftedWindows(slot) := (io.in.window >> (offsets(slot) << 3))(p.windowWidth - 1, 0)
    lens(slot) := F4DecodeWindow.instructionLengthBytes(shiftedWindows(slot))

    val remainingBytes = F4DecodeWindow.WindowBytes.U(4.W) - offsets(slot)
    val fitsWindow = offsets(slot) < F4DecodeWindow.WindowBytes.U && lens(slot) <= remainingBytes
    val priorValid = if (slot == 0) true.B else valids(slot - 1)

    valids(slot) := active && priorValid && fitsWindow
    rawInsns(slot) := shiftedWindows(slot) & F4DecodeWindow.lowBytesMask(lens(slot))
    val slotPc = (io.in.pc + offsets(slot))(p.pcWidth - 1, 0)
    val slotEndBytes = (offsets(slot) + lens(slot))(3, 0)

    endBytes(slot) := slotEndBytes

    io.slots(slot).valid := valids(slot)
    io.slots(slot).pc := Mux(valids(slot), slotPc, 0.U)
    io.slots(slot).offsetBytes := Mux(valids(slot), offsets(slot), 0.U)
    io.slots(slot).lenBytes := Mux(valids(slot), lens(slot), 0.U)
    io.slots(slot).insnRaw := Mux(valids(slot), rawInsns(slot), 0.U)
    io.slots(slot).uopUid := Mux(valids(slot), ((io.in.pktUid << 3) | slot.U)(p.uopUidWidth - 1, 0), 0.U)

    if (slot + 1 < p.decodeWidth) {
      offsets(slot + 1) := (offsets(slot) + Mux(valids(slot), lens(slot), 0.U))(3, 0)
    }
  }

  io.validMask := valids.asUInt
  io.slotCount := PopCount(valids)
  io.totalLenBytes := MuxCase(0.U(4.W), (0 until p.decodeWidth).reverse.map(slot => valids(slot) -> endBytes(slot)))
}
