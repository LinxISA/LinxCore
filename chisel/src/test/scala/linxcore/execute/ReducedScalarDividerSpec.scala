package linxcore.execute

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class ReducedScalarDividerSpec extends AnyFunSuite with ChiselSim {
  private val Mask64 = (BigInt(1) << 64) - 1

  private def clear(dut: ReducedScalarDivider): Unit = {
    dut.io.reqValid.poke(false.B)
    dut.io.lhs.poke(0.U)
    dut.io.rhs.poke(0.U)
    dut.io.signed.poke(false.B)
    dut.io.word.poke(false.B)
    dut.io.remainder.poke(false.B)
    dut.io.respReady.poke(false.B)
    dut.io.flush.poke(false.B)
  }

  private def divide(
      dut: ReducedScalarDivider,
      lhs: BigInt,
      rhs: BigInt,
      signed: Boolean,
      word: Boolean,
      remainder: Boolean,
      expected: BigInt): Unit = {
    dut.io.reqReady.expect(true.B)
    dut.io.reqValid.poke(true.B)
    dut.io.lhs.poke((lhs & Mask64).U)
    dut.io.rhs.poke((rhs & Mask64).U)
    dut.io.signed.poke(signed.B)
    dut.io.word.poke(word.B)
    dut.io.remainder.poke(remainder.B)
    dut.clock.step()
    dut.io.reqValid.poke(false.B)
    dut.clock.step(if (rhs == 0) 0 else if (word) 32 else 64)
    dut.io.respValid.expect(true.B)
    dut.io.result.expect((expected & Mask64).U)
    dut.io.respReady.poke(true.B)
    dut.clock.step()
    dut.io.respReady.poke(false.B)
    dut.io.respValid.expect(false.B)
  }

  test("iterative divider implements Sail scalar DIV and REM conventions") {
    simulate(new ReducedScalarDivider) { dut =>
      clear(dut)
      divide(dut, 100, 7, signed = false, word = false, remainder = false, expected = 14)
      divide(dut, 100, 7, signed = false, word = false, remainder = true, expected = 2)
      divide(dut, -100, 7, signed = true, word = false, remainder = false, expected = -14)
      divide(dut, -100, 7, signed = true, word = false, remainder = true, expected = -2)
      divide(dut, BigInt(1) << 63, -1, signed = true, word = false, remainder = false, expected = BigInt(1) << 63)
      divide(dut, BigInt(1) << 63, -1, signed = true, word = false, remainder = true, expected = 0)
      divide(dut, 0x1234, 0, signed = false, word = false, remainder = false, expected = 0)
      divide(dut, 0x1234, 0, signed = false, word = false, remainder = true, expected = 0x1234)
    }
  }

  test("word results always sign-extend, including DIVUW and REMUW") {
    simulate(new ReducedScalarDivider) { dut =>
      clear(dut)
      divide(dut, BigInt("fffffffe", 16), 1, signed = false, word = true, remainder = false, expected = BigInt("fffffffffffffffe", 16))
      divide(dut, BigInt("fffffffe", 16), BigInt("ffffffff", 16), signed = false, word = true, remainder = true, expected = BigInt("fffffffffffffffe", 16))
      divide(dut, BigInt("80000000", 16), -1, signed = true, word = true, remainder = false, expected = BigInt("ffffffff80000000", 16))
      divide(dut, BigInt("80000000", 16), -1, signed = true, word = true, remainder = true, expected = 0)
      divide(dut, BigInt("80000001", 16), 0, signed = false, word = true, remainder = true, expected = BigInt("ffffffff80000001", 16))
    }
  }

  test("flush cancels an in-flight division") {
    simulate(new ReducedScalarDivider) { dut =>
      clear(dut)
      dut.io.reqValid.poke(true.B)
      dut.io.lhs.poke(100.U)
      dut.io.rhs.poke(3.U)
      dut.clock.step()
      dut.io.reqValid.poke(false.B)
      dut.clock.step(5)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.respValid.expect(false.B)
      dut.io.reqReady.expect(true.B)
    }
  }
}
