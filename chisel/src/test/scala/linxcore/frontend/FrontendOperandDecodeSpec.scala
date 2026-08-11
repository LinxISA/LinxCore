package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class FrontendOperandDecodeSpec extends AnyFunSuite with ChiselSim {
  test("compact memory decode preserves its signed encoded offset") {
    simulate(new FrontendOperandDecode()) { dut =>
      dut.io.active.poke(true.B)
      dut.io.meta.poke(0.U.asTypeOf(dut.io.meta))
      dut.io.meta.lenBytes.poke(2.U)
      dut.io.meta.immKind.poke(FrontendOpcodeDecodeTable.ImmSIMM5_11_S5.U)

      for (opcode <- Seq(
        FrontendOpcodeDecodeTable.OP_C_LWI,
        FrontendOpcodeDecodeTable.OP_C_LDI,
        FrontendOpcodeDecodeTable.OP_C_SWI,
        FrontendOpcodeDecodeTable.OP_C_SDI)) {
        dut.io.meta.opcode.poke(opcode.U)
        dut.io.insn.poke((BigInt(0x1e) << 11).U)
        dut.io.immValid.expect(true.B)
        dut.io.imm.expect(BigInt("fffffffffffffffe", 16).U)
      }
    }
  }

  test("J decodes its signed halfword displacement as a byte offset") {
    simulate(new FrontendOperandDecode()) { dut =>
      dut.io.active.poke(true.B)
      dut.io.meta.poke(0.U.asTypeOf(dut.io.meta))
      dut.io.meta.opcode.poke(FrontendOpcodeDecodeTable.OP_J.U)
      dut.io.meta.lenBytes.poke(4.U)
      dut.io.meta.immKind.poke(FrontendOpcodeDecodeTable.ImmSIMM22.U)

      // simm22 = 2 is split as [11:7] @ [31:15]. J scales it by two.
      dut.io.insn.poke((BigInt(2) << 15).U)
      dut.io.immValid.expect(true.B)
      dut.io.imm.expect(4.U)

      // Preserve sign before applying the byte scaling.
      val negativeOne = ((BigInt(1) << 17) - 1) << 15 |
        ((BigInt(1) << 5) - 1) << 7
      dut.io.insn.poke(negativeOne.U)
      dut.io.immValid.expect(true.B)
      dut.io.imm.expect(BigInt("fffffffffffffffe", 16).U)
    }
  }
}
