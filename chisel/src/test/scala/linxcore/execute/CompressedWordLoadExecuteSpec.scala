package linxcore.execute

import circt.stage.ChiselStage
import linxcore.common.DestinationKind
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite

class CompressedWordLoadExecuteSpec extends AnyFunSuite {
  private val mask64 = (BigInt(1) << 64) - 1

  private def signed(value: BigInt, width: Int): BigInt = {
    val mask = (BigInt(1) << width) - 1
    val sign = BigInt(1) << (width - 1)
    val masked = value & mask
    if ((masked & sign) != 0) masked - (BigInt(1) << width) else masked
  }

  test("computes C.LWI address from signed simm5 scaled by four") {
    val base = BigInt("40010080", 16)
    val simm5 = BigInt("1d", 16)

    assert((base + (signed(simm5, 5) << 2)) == BigInt("40010074", 16))
  }

  test("sign extends C.LWI low word load result to architectural t") {
    val loadData = BigInt("0000000080010020", 16)

    assert((signed(loadData, 32) & mask64) == BigInt("ffffffff80010020", 16))
  }

  test("uses local T destination kind for architectural tag 31") {
    assert(DestinationKind.T.asUInt.litValue == 2)
  }

  test("keeps C.LDI immediate already scaled and returns full doubleword") {
    val base = BigInt("40010080", 16)
    val decodedImm = BigInt("ffffffffffffffe8", 16)
    val loadData = BigInt("8001002000010003", 16)

    assert(((base + signed(decodedImm, 64)) & mask64) == BigInt("40010068", 16))
    assert(ReducedScalarAluExecute.referenceResultWithLoad(
      FrontendOpcodeDecodeTable.OP_C_LDI,
      src0 = base,
      src1 = 0,
      imm = decodedImm,
      loadData = loadData).contains(loadData))
  }

  test("elaborates wrapper around real ReducedScalarAluExecute") {
    val sv = ChiselStage.emitSystemVerilog(new CompressedWordLoadExecuteProbe)

    assert(sv.contains("module CompressedWordLoadExecuteProbe"))
    assert(sv.contains("module ReducedScalarAluExecute"))
    assert(sv.contains("io_loadLookupValid"))
    assert(sv.contains("io_loadLookupDstKind"))
    assert(sv.contains("io_loadLookupDstRelTag"))
    assert(sv.contains("io_completeRowMemAddr"))
  }
}
