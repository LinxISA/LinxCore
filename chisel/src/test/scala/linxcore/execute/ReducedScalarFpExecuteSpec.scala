package linxcore.execute

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite

class ReducedScalarFpExecuteSpec extends AnyFunSuite with ChiselSim {
  private val Mask64 = (BigInt(1) << 64) - 1

  private def insn(dstType: Int, srcType: Int): BigInt =
    ReducedScalarFpExecute.scalarFpInsn(dstType, srcType)

  private def drive(
      dut: ReducedScalarFpExecute,
      opcode: Int,
      insnRaw: BigInt,
      srcL: BigInt,
      srcR: BigInt,
      expected: BigInt): Unit = {
    dut.io.inValid.poke(true.B)
    dut.io.outReady.poke(true.B)
    dut.io.flush.poke(false.B)
    dut.io.opcode.poke(opcode.U)
    dut.io.insnRaw.poke(insnRaw.U)
    dut.io.srcL.poke((srcL & Mask64).U)
    dut.io.srcR.poke((srcR & Mask64).U)
    dut.io.inReady.expect(true.B)
    dut.io.outValid.expect(true.B)
    dut.io.unsupported.expect(false.B)
    dut.io.outData.expect((expected & Mask64).U)
  }

  test("FEQ fd/fs is ordered, treats both signed zero encodings as equal, and returns false for NaN") {
    simulate(new ReducedScalarFpExecute) { dut =>
      drive(
        dut,
        FrontendOpcodeDecodeTable.OP_FEQ,
        insn(ReducedScalarFpExecute.DstTypeFd, ReducedScalarFpExecute.SrcTypeFd),
        BigInt("3ff0000000000000", 16),
        BigInt("3ff0000000000000", 16),
        1)
      drive(
        dut,
        FrontendOpcodeDecodeTable.OP_FEQ,
        insn(ReducedScalarFpExecute.DstTypeFd, ReducedScalarFpExecute.SrcTypeFd),
        BigInt("8000000000000000", 16),
        BigInt("0000000000000000", 16),
        1)
      drive(
        dut,
        FrontendOpcodeDecodeTable.OP_FEQ,
        insn(ReducedScalarFpExecute.DstTypeFs, ReducedScalarFpExecute.SrcTypeFs),
        BigInt("000000003f800000", 16),
        BigInt("000000003f800000", 16),
        1)
      drive(
        dut,
        FrontendOpcodeDecodeTable.OP_FEQ,
        insn(ReducedScalarFpExecute.DstTypeFs, ReducedScalarFpExecute.SrcTypeFs),
        BigInt("0000000080000000", 16),
        BigInt("0000000000000000", 16),
        1)
      drive(
        dut,
        FrontendOpcodeDecodeTable.OP_FEQ,
        insn(ReducedScalarFpExecute.DstTypeFs, ReducedScalarFpExecute.SrcTypeFs),
        BigInt("000000007fc00001", 16),
        BigInt("000000007fc00001", 16),
        0)
      drive(
        dut,
        FrontendOpcodeDecodeTable.OP_FEQ,
        insn(ReducedScalarFpExecute.DstTypeFd, ReducedScalarFpExecute.SrcTypeFd),
        BigInt("7ff8000000000001", 16),
        BigInt("7ff8000000000001", 16),
        0)
    }
  }

  test("FCVT fs2fd expands IEEE single-precision bit patterns into double precision") {
    simulate(new ReducedScalarFpExecute) { dut =>
      val op = FrontendOpcodeDecodeTable.OP_FCVT
      val fs2fd = insn(ReducedScalarFpExecute.DstTypeFd, ReducedScalarFpExecute.SrcTypeFs)
      Seq(
        BigInt("000000003f800000", 16) -> BigInt("3ff0000000000000", 16),
        BigInt("00000000c0200000", 16) -> BigInt("c004000000000000", 16),
        BigInt("0000000000000000", 16) -> BigInt("0000000000000000", 16),
        BigInt("0000000080000000", 16) -> BigInt("8000000000000000", 16),
        BigInt("0000000000000001", 16) -> BigInt("36a0000000000000", 16),
        BigInt("000000007f800000", 16) -> BigInt("7ff0000000000000", 16)
      ).foreach { case (src, expected) =>
        drive(dut, op, fs2fd, src, 0, expected)
      }
    }
  }

  test("UCVTF ud2fs rounds uint64 to f32 with round-to-nearest-even") {
    simulate(new ReducedScalarFpExecute) { dut =>
      val op = FrontendOpcodeDecodeTable.OP_UCVTF
      val ud2fs = insn(ReducedScalarFpExecute.DstTypeFs, ReducedScalarFpExecute.SrcTypeFd)
      Seq(
        BigInt(0),
        BigInt(1),
        (BigInt(1) << 24) - 1,
        BigInt(1) << 24,
        (BigInt(1) << 24) + 1,
        (BigInt(1) << 24) + 3,
        (BigInt(1) << 40) + (BigInt(1) << 16),
        (BigInt(1) << 63) - 1,
        BigInt(1) << 63,
        (BigInt(1) << 64) - 1
      ).foreach { value =>
        val expected = ReducedScalarFpExecute.referenceResult(op, ud2fs, value, 0).get
        drive(dut, op, ud2fs, value, 0, expected)
      }
    }
  }

  test("ready/valid contract is combinational and flush masks the same-cycle result") {
    simulate(new ReducedScalarFpExecute) { dut =>
      val feqFs = insn(ReducedScalarFpExecute.DstTypeFs, ReducedScalarFpExecute.SrcTypeFs)
      dut.io.inValid.poke(true.B)
      dut.io.outReady.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.opcode.poke(FrontendOpcodeDecodeTable.OP_FEQ.U)
      dut.io.insnRaw.poke(feqFs.U)
      dut.io.srcL.poke(BigInt("3f800000", 16).U)
      dut.io.srcR.poke(BigInt("3f800000", 16).U)
      dut.io.inReady.expect(false.B)
      dut.io.outValid.expect(true.B)
      dut.io.outData.expect(1.U)

      dut.io.outReady.poke(true.B)
      dut.io.flush.poke(true.B)
      dut.io.inReady.expect(true.B)
      dut.io.outValid.expect(false.B)

      dut.io.flush.poke(false.B)
      dut.io.outValid.expect(true.B)
      dut.io.outData.expect(1.U)
    }
  }

  test("unsupported encodings are reported without claiming subset completion") {
    simulate(new ReducedScalarFpExecute) { dut =>
      dut.io.inValid.poke(true.B)
      dut.io.outReady.poke(true.B)
      dut.io.flush.poke(false.B)
      dut.io.opcode.poke(FrontendOpcodeDecodeTable.OP_UCVTF.U)
      dut.io.insnRaw.poke(insn(ReducedScalarFpExecute.DstTypeFd, ReducedScalarFpExecute.SrcTypeFs).U)
      dut.io.srcL.poke(0.U)
      dut.io.srcR.poke(0.U)
      dut.io.outValid.expect(true.B)
      dut.io.unsupported.expect(true.B)
      dut.io.outData.expect(0.U)
    }
  }

  test("ReducedScalarFpExecute elaborates standalone synthesizable RTL") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarFpExecute)
    assert(sv.contains("module ReducedScalarFpExecute"))
    assert(!sv.contains("$realtobits"))
    assert(!sv.contains("DPI"))
  }
}
