package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.{
  BoundaryKind,
  DecodedDestination,
  DecodedOperand,
  DecodedUop,
  DestinationKind,
  DispatchTarget,
  FrontendDecodePacket,
  InterfaceParams,
  OperandClass
}
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
    val cSetretAlias =
      lenBytes == 2 &&
        rule.opcode == FrontendOpcodeDecodeTable.OP_C_MOVI &&
        ((word & BigInt("f83f", 16)) == BigInt("5016", 16))
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
    if (opcodeIs(rule, FrontendOpcodeDecodeTable.OP_FENTRY)) {
      src(0) = Some(rs1_32)
      src(1) = None
      dst = Some(1)
    }
    if (opcodeIs(rule, FrontendOpcodeDecodeTable.OP_FRET_STK)) {
      src(0) = None
      src(1) = None
    }
    if (opcodeIs(rule,
      FrontendOpcodeDecodeTable.OP_SETC_ANDI,
      FrontendOpcodeDecodeTable.OP_SETC_EQI,
      FrontendOpcodeDecodeTable.OP_SETC_GEI,
      FrontendOpcodeDecodeTable.OP_SETC_GEUI,
      FrontendOpcodeDecodeTable.OP_SETC_LTI,
      FrontendOpcodeDecodeTable.OP_SETC_LTUI,
      FrontendOpcodeDecodeTable.OP_SETC_NEI,
      FrontendOpcodeDecodeTable.OP_SETC_ORI)) {
      dst = None
    }
    if (opcodeIs(rule, FrontendOpcodeDecodeTable.OP_BTEXT)) {
      src(0) = Some(rs1_32)
    }
    if (opcodeIs(rule, FrontendOpcodeDecodeTable.OP_SETRET) || cSetretAlias) {
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
      case FrontendOpcodeDecodeTable.ImmSIMM5_6_S5 =>
        if (cSetretAlias) {
          imm = Some((bitRange(word, 10, 6) << 1) & Mask64)
        } else {
          imm = Some(sext(bitRange(word, 10, 6), 5))
        }
      case FrontendOpcodeDecodeTable.ImmUIMM5 => imm = Some(bitRange(word, 10, 6))
      case FrontendOpcodeDecodeTable.ImmFENTRY_UIMM_HI =>
        imm = Some((bitRange(word, 11, 7) << 10) | (bitRange(word, 31, 25) << 3))
      case FrontendOpcodeDecodeTable.ImmIMM32 =>
        val pfx16 = bitRange(word, 15, 0)
        val main = word >> 16
        imm = Some(sext((bitRange(pfx16, 15, 4) << 20) | bitRange(main, 31, 12), 32))
      case FrontendOpcodeDecodeTable.ImmSIMM_4_S12_31_17 =>
        val pfx16 = bitRange(word, 15, 0)
        if (opcodeIs(rule,
          FrontendOpcodeDecodeTable.OP_HL_LB_PCR,
          FrontendOpcodeDecodeTable.OP_HL_LBU_PCR,
          FrontendOpcodeDecodeTable.OP_HL_LD_PCR,
          FrontendOpcodeDecodeTable.OP_HL_LH_PCR,
          FrontendOpcodeDecodeTable.OP_HL_LHU_PCR,
          FrontendOpcodeDecodeTable.OP_HL_LW_PCR,
          FrontendOpcodeDecodeTable.OP_HL_LWU_PCR)) {
          imm = Some(sext((bitRange(pfx16, 15, 4) << 17) | bitRange(word, 47, 31), 29))
        } else {
          imm = Some(sext((bitRange(pfx16, 15, 4) << 18) | (bitRange(word, 47, 31) << 1), 30))
        }
      case FrontendOpcodeDecodeTable.ImmSIMM_4_S12_23_5_36_12 =>
        val pfx16 = bitRange(word, 15, 0)
        imm = Some(sext((bitRange(pfx16, 15, 4) << 17) | (bitRange(word, 27, 23) << 12) | bitRange(word, 47, 36), 29))
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
    if (opcodeIs(rule,
      FrontendOpcodeDecodeTable.OP_SLLI,
      FrontendOpcodeDecodeTable.OP_SRLI,
      FrontendOpcodeDecodeTable.OP_SRAI)) {
      imm = Some(bitRange(word, 25, 20))
    }
    if (opcodeIs(rule, FrontendOpcodeDecodeTable.OP_C_LDI)) {
      imm = Some((sext(bitRange(word, 15, 11), 5) << 3) & Mask64)
    }
    if (opcodeIs(rule,
      FrontendOpcodeDecodeTable.OP_LB_PCR,
      FrontendOpcodeDecodeTable.OP_LBU_PCR,
      FrontendOpcodeDecodeTable.OP_LD_PCR,
      FrontendOpcodeDecodeTable.OP_LH_PCR,
      FrontendOpcodeDecodeTable.OP_LHU_PCR,
      FrontendOpcodeDecodeTable.OP_LW_PCR,
      FrontendOpcodeDecodeTable.OP_LWU_PCR)) {
      imm = Some(sext(bitRange(word, 31, 15), 17))
    }

    OperandShape(src.toVector, dst, imm)
  }
}

object FrontendRegAliasClassifyReference {
  final case class Source(valid: Boolean, cls: String, arch: Int, rel: Int)
  final case class Destination(valid: Boolean, kind: String, arch: Int, rel: Int)

  private val InvalidTag = 63

  def source(valid: Boolean, tag: Int): Source = {
    if (!valid) {
      Source(valid = false, "Invalid", tag, InvalidTag)
    } else if (tag >= 0 && tag < 24) {
      Source(valid = true, "P", tag, tag)
    } else if (tag >= 24 && tag <= 27) {
      Source(valid = true, "T", tag, tag - 24)
    } else if (tag >= 28 && tag <= 31) {
      Source(valid = true, "U", tag, tag - 28)
    } else {
      Source(valid = true, "Invalid", tag, InvalidTag)
    }
  }

  def destination(valid: Boolean, tag: Int): Destination = {
    if (!valid) {
      Destination(valid = false, "None", tag, InvalidTag)
    } else if (tag >= 0 && tag < 24) {
      Destination(valid = true, "Gpr", tag, tag)
    } else if (tag == 31) {
      Destination(valid = true, "T", tag, 0)
    } else if (tag == 30) {
      Destination(valid = true, "U", tag, 0)
    } else {
      Destination(valid = true, "None", tag, InvalidTag)
    }
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

class FrontendRegAliasClassifyProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val srcValid = Input(Bool())
  val srcTag = Input(UInt(p.archRegWidth.W))
  val dstValid = Input(Bool())
  val dstTag = Input(UInt(p.archRegWidth.W))
  val src = Output(new DecodedOperand(p))
  val dst = Output(new DecodedDestination(p))
}

class FrontendRegAliasClassifyProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new FrontendRegAliasClassifyProbeIO(p))

  io.src := FrontendRegAliasClassify.source(p, io.srcValid, io.srcTag)
  io.dst := FrontendRegAliasClassify.destination(p, io.dstValid, io.dstTag)
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

  test("frontend reg6 alias classifier follows LinxCoreModel scalar operand bounds") {
    import FrontendRegAliasClassifyReference._

    assert(FrontendRegAliasClassify.ScalarGprCount == 24)
    assert(FrontendRegAliasClassify.SourceTLeft == 24)
    assert(FrontendRegAliasClassify.SourceTRight == 27)
    assert(FrontendRegAliasClassify.SourceULeft == 28)
    assert(FrontendRegAliasClassify.SourceURight == 31)
    assert(FrontendRegAliasClassify.DestinationUQueueTag == 30)
    assert(FrontendRegAliasClassify.DestinationTQueueTag == 31)

    assert(source(valid = true, tag = 0) == Source(valid = true, cls = "P", arch = 0, rel = 0))
    assert(source(valid = true, tag = 23) == Source(valid = true, cls = "P", arch = 23, rel = 23))
    assert(source(valid = true, tag = 24) == Source(valid = true, cls = "T", arch = 24, rel = 0))
    assert(source(valid = true, tag = 27) == Source(valid = true, cls = "T", arch = 27, rel = 3))
    assert(source(valid = true, tag = 28) == Source(valid = true, cls = "U", arch = 28, rel = 0))
    assert(source(valid = true, tag = 31) == Source(valid = true, cls = "U", arch = 31, rel = 3))

    assert(destination(valid = true, tag = 23) == Destination(valid = true, kind = "Gpr", arch = 23, rel = 23))
    assert(destination(valid = true, tag = 30) == Destination(valid = true, kind = "U", arch = 30, rel = 0))
    assert(destination(valid = true, tag = 31) == Destination(valid = true, kind = "T", arch = 31, rel = 0))
    assert(destination(valid = true, tag = 24) == Destination(valid = true, kind = "None", arch = 24, rel = 63))

    assert(OperandClass.P.asUInt.litValue == 1)
    assert(OperandClass.T.asUInt.litValue == 2)
    assert(OperandClass.U.asUInt.litValue == 3)
    assert(DestinationKind.Gpr.asUInt.litValue == 1)
    assert(DestinationKind.T.asUInt.litValue == 2)
    assert(DestinationKind.U.asUInt.litValue == 3)

    val sv = ChiselStage.emitSystemVerilog(new FrontendRegAliasClassifyProbe(InterfaceParams()))
    assert(sv.contains("module FrontendRegAliasClassifyProbe"))
    assert(sv.contains("io_src_operandClass"))
    assert(sv.contains("io_dst_kind"))
  }

  test("reference decode uses the pyCircuit most-specific mask rule") {
    assert(decode(0x0000, lenBytes = 2).map(_.symbol).contains("OP_C_BSTOP"))
    assert(decode(0x0002, lenBytes = 2).map(_.symbol).contains("OP_C_BSTART_DIRECT"))
    assert(decode(0x5096, lenBytes = 2).map(_.symbol).contains("OP_C_MOVI"))
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

  test("generated opcode table carries store PCR, pair, and cache-maintain split metadata") {
    val sdPcr = FrontendOpcodeDecodeTable.Rules.find(_.symbol == "OP_SD_PCR").get
    val pair = FrontendOpcodeDecodeTable.Rules.find(_.symbol == "OP_HL_SDP").get
    val dczva = FrontendOpcodeDecodeTable.Rules.find(_.symbol == "OP_DC_ZVA").get

    assert(sdPcr.isStore)
    assert(sdPcr.isStorePcr)
    assert(!sdPcr.isLoadStorePair)
    assert(!sdPcr.cacheMaintainNoSplit)

    assert(pair.isLoadStorePair)
    assert(!pair.isStore)
    assert(!pair.cacheMaintainNoSplit)

    assert(dczva.cacheMaintainNoSplit)
    assert(!dczva.isStore)
    assert(!dczva.isLoadStorePair)
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

    val andiw = operands(BigInt("0ff1af35", 16), lenBytes = 4).get
    assert(andiw.dst.contains(30))
    assert(andiw.src(0).contains(3))
    assert(andiw.src(1).isEmpty)
    assert(andiw.imm.contains(255))

    val slli = operands(BigInt("003c7f95", 16), lenBytes = 4).get
    assert(slli.dst.contains(31))
    assert(slli.src(0).contains(24))
    assert(slli.src(1).isEmpty)
    assert(slli.imm.contains(3))

    val cLdiScaled = operands(0xf61aL, lenBytes = 2).get
    assert(cLdiScaled.dst.contains(31))
    assert(cLdiScaled.src(0).contains(24))
    assert(cLdiScaled.imm.contains(BigInt("fffffffffffffff0", 16)))

    val sd = operands(0x00003049L | (9L << 15) | (10L << 20) | (11L << 27), lenBytes = 4).get
    assert(sd.src(0).contains(9))
    assert(sd.src(1).contains(10))
    assert(sd.src(2).contains(11))
    assert(sd.dst.isEmpty)

    val bstart = operands(0x00002001L | (1L << 15), lenBytes = 4).get
    assert(bstart.dst.isEmpty)
    assert(bstart.src.forall(_.isEmpty))
    assert(bstart.imm.contains(2))

    val hlBstartCall = operands(BigInt("3c001000e", 16), lenBytes = 6).get
    assert(hlBstartCall.dst.isEmpty)
    assert(hlBstartCall.src.forall(_.isEmpty))
    assert(hlBstartCall.imm.contains(14))

    val hlLdPcr = operands(BigInt("539432b9000e", 16), lenBytes = 6).get
    assert(hlLdPcr.dst.contains(5))
    assert(hlLdPcr.src.forall(_.isEmpty))
    assert(hlLdPcr.imm.contains(BigInt("a728", 16)))

    val hlSdPcr = operands(BigInt("43a1b569000e", 16), lenBytes = 6).get
    assert(hlSdPcr.dst.isEmpty)
    assert(hlSdPcr.src(0).contains(3))
    assert(hlSdPcr.src(1).isEmpty)
    assert(hlSdPcr.imm.contains(BigInt("a43a", 16)))

    val ldPcr = operands(BigInt("012332b9", 16), lenBytes = 4).get
    assert(ldPcr.dst.contains(5))
    assert(ldPcr.src.forall(_.isEmpty))
    assert(ldPcr.imm.contains(0x246))

    val setcLtui = operands(BigInt("00326075", 16), lenBytes = 4).get
    assert(setcLtui.dst.isEmpty)
    assert(setcLtui.src(0).contains(4))
    assert(setcLtui.src(1).isEmpty)
    assert(setcLtui.imm.contains(3))

    val mulw = operands(BigInt("018e21c7", 16), lenBytes = 4).get
    assert(mulw.dst.contains(3))
    assert(mulw.src(0).contains(28))
    assert(mulw.src(1).contains(24))
    assert(mulw.imm.isEmpty)

    val csub = operands(BigInt("1158", 16), lenBytes = 2).get
    assert(csub.dst.contains(31))
    assert(csub.src(0).contains(5))
    assert(csub.src(1).contains(2))
    assert(csub.imm.isEmpty)

    val coremarkAndi = operands(BigInt("003c2f15", 16), lenBytes = 4).get
    assert(coremarkAndi.dst.contains(30))
    assert(coremarkAndi.src(0).contains(24))
    assert(coremarkAndi.src(1).isEmpty)
    assert(coremarkAndi.imm.contains(3))

    val swi = operands(BigInt("0041a059", 16), lenBytes = 4).get
    assert(swi.dst.isEmpty)
    assert(swi.src(0).contains(3))
    assert(swi.src(1).contains(4))
    assert(swi.imm.contains(0))

    val ori = operands(BigInt("018c3315", 16), lenBytes = 4).get
    assert(ori.dst.contains(6))
    assert(ori.src(0).contains(24))
    assert(ori.src(1).isEmpty)
    assert(ori.imm.contains(0x18))

    val sub = operands(BigInt("06629285", 16), lenBytes = 4).get
    assert(sub.dst.contains(5))
    assert(sub.src(0).contains(5))
    assert(sub.src(1).contains(6))
    assert(sub.imm.isEmpty)

    val mul = operands(BigInt("01cc01c7", 16), lenBytes = 4).get
    assert(mul.dst.contains(3))
    assert(mul.src(0).contains(24))
    assert(mul.src(1).contains(28))
    assert(mul.imm.isEmpty)

    val hlLui = operands(BigInt("1f97000e", 16), lenBytes = 6).get
    assert(hlLui.dst.contains(31))
    assert(hlLui.src.forall(_.isEmpty))
    assert(hlLui.imm.contains(1))

    val fentry = operands(BigInt("90a50041", 16), lenBytes = 4).get
    assert(fentry.dst.contains(1))
    assert(fentry.src(0).contains(10))
    assert(fentry.src(1).isEmpty)
    assert(fentry.imm.contains(576))

    val fretStkRa = operands(BigInt("02a53041", 16), lenBytes = 4).get
    assert(fretStkRa.dst.isEmpty)
    assert(fretStkRa.src.forall(_.isEmpty))
    assert(fretStkRa.imm.contains(8))

    val cAdd = operands(0x0008 | (6 << 6) | (7 << 11), lenBytes = 2).get
    assert(cAdd.dst.contains(31))
    assert(cAdd.src(0).contains(6))
    assert(cAdd.src(1).contains(7))

    val cMovi = operands(0x0016 | (0x1f << 6) | (5 << 11), lenBytes = 2).get
    assert(cMovi.dst.contains(5))
    assert(cMovi.imm.contains(BigInt("ffffffffffffffff", 16)))

    val cSetretAlias = operands(0x5096, lenBytes = 2).get
    assert(cSetretAlias.dst.contains(10))
    assert(cSetretAlias.imm.contains(4))
  }

  test("IO exposes decoded uops plus model-derived sideband masks") {
    val p = InterfaceParams()
    val io = new FrontendDecodeStageIO(p)
    val meta = new FrontendOpcodeMeta(p)

    assert(io.out.length == 4)
    assert(io.meta.length == 4)
    assert(io.d1.peId.getWidth == 8)
    assert(io.d1.threadId.getWidth == 8)
    assert(io.out(0).peId.getWidth == 8)
    assert(io.out(0).threadId.getWidth == 8)
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
    assert(meta.isLoadStorePair.getWidth == 1)
    assert(meta.isStorePcr.getWidth == 1)
    assert(meta.cacheMaintainNoSplit.getWidth == 1)
  }

  test("FrontendDecodeStage elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new FrontendDecodeStageProbe(InterfaceParams()))

    assert(sv.contains("module FrontendDecodeStageProbe"))
    assert(sv.contains("FrontendDecodeStage"))
    assert(sv.contains("FrontendOperandDecode"))
    assert(sv.contains("io_d1_peId"))
    assert(sv.contains("io_d1_threadId"))
    assert(sv.contains("io_outValidMask"))
    assert(sv.contains("io_invalidOpcodeMask"))
    assert(sv.contains("io_blockBoundaryMask"))
    assert(sv.contains("io_loadMask"))
    assert(sv.contains("io_storeMask"))
  }
}
