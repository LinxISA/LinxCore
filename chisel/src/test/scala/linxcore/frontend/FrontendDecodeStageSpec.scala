package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.{BoundaryKind, DecodedUop, DispatchTarget, FrontendDecodePacket, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

object FrontendDecodeStageReference {
  def decode(word: BigInt, lenBytes: Int): Option[FrontendOpcodeDecodeTable.Rule] =
    FrontendOpcodeDecodeTable.Rules.find(rule =>
      rule.lenBytes == lenBytes && ((word & rule.mask) == rule.value))
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
  }

  test("FrontendDecodeStage elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new FrontendDecodeStageProbe(InterfaceParams()))

    assert(sv.contains("module FrontendDecodeStageProbe"))
    assert(sv.contains("FrontendDecodeStage"))
    assert(sv.contains("io_outValidMask"))
    assert(sv.contains("io_invalidOpcodeMask"))
    assert(sv.contains("io_blockBoundaryMask"))
    assert(sv.contains("io_loadMask"))
    assert(sv.contains("io_storeMask"))
  }
}
