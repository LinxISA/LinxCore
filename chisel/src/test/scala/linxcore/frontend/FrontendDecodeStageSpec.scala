package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.{BoundaryKind, DecodedUop, DispatchTarget, FrontendDecodePacket, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

object FrontendDecodeStageReference {
  final case class OperandShape(src: Vector[Option[Int]], dst: Option[Int], imm: Option[BigInt])

  private val Mask64 = (BigInt(1) << 64) - 1

  def decode(word: BigInt, lenBytes: Int): Option[FrontendOpcodeDecodeTable.Rule] =
    FrontendOpcodeDecodeTable.Rules.find(rule =>
      rule.lenBytes == lenBytes && ((word & rule.mask) == rule.value))

  private def sext(value: BigInt, width: Int): BigInt = {
    val mask = (BigInt(1) << width) - 1
    val sign = BigInt(1) << (width - 1)
    val clipped = value & mask
    val signed = if ((clipped & sign) != 0) clipped - (BigInt(1) << width) else clipped
    signed & Mask64
  }

  private def bitRange(word: BigInt, hi: Int, lo: Int): BigInt =
    (word >> lo) & ((BigInt(1) << (hi - lo + 1)) - 1)

  private def opcodeIs(rule: FrontendOpcodeDecodeTable.Rule, values: Int*): Boolean =
    values.contains(rule.opcode)

  def operands(word: BigInt, lenBytes: Int): Option[OperandShape] = decode(word, lenBytes).map { rule =>
    val src = Array.fill[Option[Int]](3)(None)
    var dst: Option[Int] = None
    var imm: Option[BigInt] = None

    val rd16 = bitRange(word, 15, 11).toInt
    val rs16 = bitRange(word, 10, 6).toInt
    val rd32 = bitRange(word, 11, 7).toInt
    val rs1_32 = bitRange(word, 19, 15).toInt
    val rs2_32 = bitRange(word, 24, 20).toInt
    val srcp32 = bitRange(word, 31, 27).toInt
    val main32 = word >> 16
    val rdHl = bitRange(main32, 11, 7).toInt
    val rs1Hl = bitRange(main32, 19, 15).toInt
    val rs2Hl = bitRange(main32, 24, 20).toInt

    val genericRd = if (lenBytes == 2) rd16 else if (lenBytes == 6) rdHl else rd32
    val genericRs1 = if (lenBytes == 2) rs16 else if (lenBytes == 6) rs1Hl else rs1_32
    val genericRs2 = if (lenBytes == 2) rd16 else if (lenBytes == 6) rs2Hl else rs2_32

    if (rule.rdKind == FrontendOpcodeDecodeTable.OperandREG) dst = Some(genericRd)
    if (rule.rs1Kind == FrontendOpcodeDecodeTable.OperandREG) src(0) = Some(genericRs1)
    if (rule.rs2Kind == FrontendOpcodeDecodeTable.OperandREG) src(1) = Some(genericRs2)

    if (opcodeIs(rule,
      FrontendOpcodeDecodeTable.OP_C_ADD,
      FrontendOpcodeDecodeTable.OP_C_ADDI,
      FrontendOpcodeDecodeTable.OP_C_AND,
      FrontendOpcodeDecodeTable.OP_C_OR,
      FrontendOpcodeDecodeTable.OP_C_SUB,
      FrontendOpcodeDecodeTable.OP_C_LDI,
      FrontendOpcodeDecodeTable.OP_C_LWI,
      FrontendOpcodeDecodeTable.OP_C_SEXT_W,
      FrontendOpcodeDecodeTable.OP_C_ZEXT_W,
      FrontendOpcodeDecodeTable.OP_C_CMP_EQI,
      FrontendOpcodeDecodeTable.OP_C_CMP_NEI)) {
      dst = Some(31)
    }
    if (opcodeIs(rule, FrontendOpcodeDecodeTable.OP_C_CMP_EQI, FrontendOpcodeDecodeTable.OP_C_CMP_NEI)) {
      src(0) = Some(24)
    }
    if (opcodeIs(rule, FrontendOpcodeDecodeTable.OP_C_SDI, FrontendOpcodeDecodeTable.OP_C_SWI)) {
      src(1) = Some(24)
    }
    if (opcodeIs(rule,
      FrontendOpcodeDecodeTable.OP_FENTRY,
      FrontendOpcodeDecodeTable.OP_FEXIT,
      FrontendOpcodeDecodeTable.OP_FRET_RA,
      FrontendOpcodeDecodeTable.OP_FRET_STK)) {
      src(0) = Some(rs1_32)
      src(1) = Some(rs2_32)
    }
    if (opcodeIs(rule, FrontendOpcodeDecodeTable.OP_BTEXT)) {
      src(0) = Some(rs1_32)
    }
    if (opcodeIs(rule, FrontendOpcodeDecodeTable.OP_SETRET)) {
      dst = Some(10)
    }
    if (opcodeIs(rule, FrontendOpcodeDecodeTable.OP_BLOAD, FrontendOpcodeDecodeTable.OP_BSTORE)) {
      dst = Some(rd32)
      src(0) = Some(rs1_32)
      src(1) = Some(rs2_32)
    }
    if (opcodeIs(rule,
      FrontendOpcodeDecodeTable.OP_MADD,
      FrontendOpcodeDecodeTable.OP_MADDW,
      FrontendOpcodeDecodeTable.OP_CSEL,
      FrontendOpcodeDecodeTable.OP_BIOR,
      FrontendOpcodeDecodeTable.OP_BLOAD,
      FrontendOpcodeDecodeTable.OP_BSTORE,
      FrontendOpcodeDecodeTable.OP_SB,
      FrontendOpcodeDecodeTable.OP_SH,
      FrontendOpcodeDecodeTable.OP_SW,
      FrontendOpcodeDecodeTable.OP_SD)) {
      src(2) = Some(srcp32)
    }

    rule.immKind match {
      case FrontendOpcodeDecodeTable.ImmUIMM12 => imm = Some(bitRange(word, 31, 20))
      case FrontendOpcodeDecodeTable.ImmSIMM12_20_S12 => imm = Some(sext(bitRange(word, 31, 20), 12))
      case FrontendOpcodeDecodeTable.ImmSIMM12_7_S5_25_7 =>
        imm = Some(sext((bitRange(word, 11, 7) << 7) | bitRange(word, 31, 25), 12))
      case FrontendOpcodeDecodeTable.ImmSIMM17 => imm = Some((sext(bitRange(word, 31, 15), 17) << 1) & Mask64)
      case FrontendOpcodeDecodeTable.ImmSIMM25 => imm = Some(sext(bitRange(word, 31, 7), 25))
      case FrontendOpcodeDecodeTable.ImmSIMM5_11_S5 => imm = Some(sext(bitRange(word, 15, 11), 5))
      case FrontendOpcodeDecodeTable.ImmSIMM5_6_S5 => imm = Some(sext(bitRange(word, 10, 6), 5))
      case FrontendOpcodeDecodeTable.ImmUIMM5 => imm = Some(bitRange(word, 10, 6))
      case FrontendOpcodeDecodeTable.ImmFENTRY_UIMM_HI =>
        imm = Some((bitRange(word, 11, 7) << 10) | (bitRange(word, 31, 25) << 3))
      case FrontendOpcodeDecodeTable.ImmIMM32 =>
        val pfx16 = bitRange(word, 15, 0)
        val main = word >> 16
        imm = Some(sext((bitRange(pfx16, 15, 4) << 20) | bitRange(main, 31, 12), 32))
      case FrontendOpcodeDecodeTable.ImmIMM20 =>
        if (opcodeIs(rule, FrontendOpcodeDecodeTable.OP_SETRET)) {
          imm = Some((bitRange(word, 31, 12) << 1) & Mask64)
        } else {
          imm = Some((sext(bitRange(word, 31, 12), 20) << 12) & Mask64)
        }
      case FrontendOpcodeDecodeTable.ImmSIMM12 =>
        if (lenBytes == 2) {
          imm = Some((sext(bitRange(word, 15, 4), 12) << 1) & Mask64)
        } else {
          imm = Some(sext(bitRange(word, 31, 20), 12))
        }
      case _ =>
    }

    OperandShape(src.toVector, dst, imm)
  }
}

class FrontendDecodeStageProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val d1 = Input(new FrontendDecodePacket(p))
  val slots = Input(Vec(p.decodeWidth, new F4Slot(p)))
  val validMask = Input(UInt(p.decodeWidth.W))
  val flushValid = Input(Bool())
  val out = Output(Vec(p.decodeWidth, new DecodedUop(p)))
  val outValidMask = Output(UInt(p.decodeWidth.W))
  val invalidOpcodeMask = Output(UInt(p.decodeWidth.W))
  val blockBoundaryMask = Output(UInt(p.decodeWidth.W))
  val blockStopMask = Output(UInt(p.decodeWidth.W))
  val loadMask = Output(UInt(p.decodeWidth.W))
  val storeMask = Output(UInt(p.decodeWidth.W))
}

class FrontendDecodeStageProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new FrontendDecodeStageProbeIO(p))
  val stage = Module(new FrontendDecodeStage(p))

  stage.io.d1 := io.d1
  stage.io.slots := io.slots
  stage.io.validMask := io.validMask
  stage.io.flushValid := io.flushValid

  io.out := stage.io.out
  io.outValidMask := stage.io.outValidMask
  io.invalidOpcodeMask := stage.io.invalidOpcodeMask
  io.blockBoundaryMask := stage.io.blockBoundaryMask
  io.blockStopMask := stage.io.blockStopMask
  io.loadMask := stage.io.loadMask
  io.storeMask := stage.io.storeMask
}

class FrontendDecodeStageSpec extends AnyFunSuite {
  import FrontendDecodeStageReference._

  test("generated opcode table preserves pyCircuit catalog IDs and rule count") {
    assert(FrontendOpcodeDecodeTable.RuleCount == 649)
    assert(FrontendOpcodeDecodeTable.OP_ADD == 58)
    assert(FrontendOpcodeDecodeTable.OP_LD == 342)
    assert(FrontendOpcodeDecodeTable.OP_SD == 381)
    assert(FrontendOpcodeDecodeTable.OP_BIOR == 416)
    assert(FrontendOpcodeDecodeTable.OP_C_BSTOP == 38)
    assert(FrontendOpcodeDecodeTable.OP_BSTOP == 578)
    assert(FrontendOpcodeDecodeTable.OperandREG == 1)
    assert(FrontendOpcodeDecodeTable.ImmUIMM12 != FrontendOpcodeDecodeTable.ImmSIMM12_20_S12)
  }

  test("reference decode uses the pyCircuit most-specific mask rule") {
    assert(decode(0x0000, lenBytes = 2).map(_.symbol).contains("OP_C_BSTOP"))
    assert(decode(0x0002, lenBytes = 2).map(_.symbol).contains("OP_C_BSTART_DIRECT"))
    assert(decode(0x00000001L, lenBytes = 4).map(_.symbol).contains("OP_BSTOP"))
    assert(decode(0x00002001L, lenBytes = 4).map(_.symbol).contains("OP_BSTART_STD_DIRECT"))
    assert(decode(0x00000005L, lenBytes = 4).map(_.symbol).contains("OP_ADD"))
    assert(decode(0x00003009L, lenBytes = 4).map(_.symbol).contains("OP_LD"))
    assert(decode(0x00003049L, lenBytes = 4).map(_.symbol).contains("OP_SD"))
    assert(decode(0x00000013L, lenBytes = 4).map(_.symbol).contains("OP_BIOR"))
  }

  test("reference decode classifies dispatch and block sidebands") {
    val add = decode(0x00000005L, lenBytes = 4).get
    val load = decode(0x00003009L, lenBytes = 4).get
    val store = decode(0x00003049L, lenBytes = 4).get
    val bstart = decode(0x00002001L, lenBytes = 4).get
    val bstop = decode(0x00000001L, lenBytes = 4).get

    assert(add.dispatch == DispatchTarget.Alu.asUInt.litValue.toInt)
    assert(load.dispatch == DispatchTarget.Lsu.asUInt.litValue.toInt)
    assert(load.isLoad)
    assert(store.dispatch == DispatchTarget.Lsu.asUInt.litValue.toInt)
    assert(store.isStore)
    assert(bstart.dispatch == DispatchTarget.Cmd.asUInt.litValue.toInt)
    assert(bstart.boundary == BoundaryKind.Direct.asUInt.litValue.toInt)
    assert(bstart.isBlockBoundary)
    assert(!bstart.isBlockStop)
    assert(bstop.isBlockStop)
  }

  test("reference operand decode mirrors the pyCircuit scalar field layout") {
    val add = operands(0x00000005L | (3L << 7) | (4L << 15) | (5L << 20), lenBytes = 4).get
    assert(add.dst.contains(3))
    assert(add.src(0).contains(4))
    assert(add.src(1).contains(5))
    assert(add.imm.isEmpty)

    val addi = operands(0x00000015L | (6L << 7) | (7L << 15) | (0x7ffL << 20), lenBytes = 4).get
    assert(addi.dst.contains(6))
    assert(addi.src(0).contains(7))
    assert(addi.imm.contains(0x7ff))

    val andi = operands(0x00002015L | (1L << 7) | (2L << 15) | (0xfffL << 20), lenBytes = 4).get
    assert(andi.dst.contains(1))
    assert(andi.src(0).contains(2))
    assert(andi.imm.contains(BigInt("ffffffffffffffff", 16)))

    val sd = operands(0x00003049L | (9L << 15) | (10L << 20) | (11L << 27), lenBytes = 4).get
    assert(sd.src(0).contains(9))
    assert(sd.src(1).contains(10))
    assert(sd.src(2).contains(11))
    assert(sd.dst.isEmpty)

    val bstart = operands(0x00002001L | (1L << 15), lenBytes = 4).get
    assert(bstart.dst.isEmpty)
    assert(bstart.src.forall(_.isEmpty))
    assert(bstart.imm.contains(2))

    val cAdd = operands(0x0008 | (6 << 6) | (7 << 11), lenBytes = 2).get
    assert(cAdd.dst.contains(31))
    assert(cAdd.src(0).contains(6))
    assert(cAdd.src(1).contains(7))

    val cMovi = operands(0x0016 | (0x1f << 6) | (5 << 11), lenBytes = 2).get
    assert(cMovi.dst.contains(5))
    assert(cMovi.imm.contains(BigInt("ffffffffffffffff", 16)))
  }

  test("IO exposes decoded uops plus model-derived sideband masks") {
    val p = InterfaceParams()
    val io = new FrontendDecodeStageIO(p)
    val meta = new FrontendOpcodeMeta(p)

    assert(io.out.length == 4)
    assert(io.meta.length == 4)
    assert(io.outValidMask.getWidth == 4)
    assert(io.invalidOpcodeMask.getWidth == 4)
    assert(io.blockBoundaryMask.getWidth == 4)
    assert(io.blockStopMask.getWidth == 4)
    assert(io.loadMask.getWidth == 4)
    assert(io.storeMask.getWidth == 4)
    assert(meta.opcode.getWidth == 12)
    assert(meta.lenBytes.getWidth == 4)
    assert(meta.majorCategory.getWidth == 4)
    assert(meta.rdKind.getWidth == 2)
    assert(meta.rs1Kind.getWidth == 2)
    assert(meta.rs2Kind.getWidth == 2)
    assert(meta.immKind.getWidth == 6)
  }

  test("FrontendDecodeStage elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new FrontendDecodeStageProbe(InterfaceParams()))

    assert(sv.contains("module FrontendDecodeStageProbe"))
    assert(sv.contains("FrontendDecodeStage"))
    assert(sv.contains("FrontendOperandDecode"))
    assert(sv.contains("io_outValidMask"))
    assert(sv.contains("io_invalidOpcodeMask"))
    assert(sv.contains("io_blockBoundaryMask"))
    assert(sv.contains("io_loadMask"))
    assert(sv.contains("io_storeMask"))
  }
}
