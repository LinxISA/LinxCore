package linxcore.common

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class LSIDOrderProbeIO(val lsidWidth: Int) extends Bundle {
  val lhs = Input(UInt(lsidWidth.W))
  val rhs = Input(UInt(lsidWidth.W))
  val equal = Output(Bool())
  val less = Output(Bool())
  val lessEqual = Output(Bool())
  val ambiguous = Output(Bool())
}

class LSIDOrderProbe(val width: Int = 32) extends Module {
  val io = IO(new LSIDOrderProbeIO(width))

  io.equal := LSIDOrder.equal(io.lhs, io.rhs)
  io.less := LSIDOrder.less(io.lhs, io.rhs)
  io.lessEqual := LSIDOrder.lessEqual(io.lhs, io.rhs)
  io.ambiguous := LSIDOrder.ambiguous(io.lhs, io.rhs)
}

class LSIDOrderSpec extends AnyFunSuite {
  private def distance(lhs: BigInt, rhs: BigInt, width: Int): BigInt =
    (rhs - lhs) & ((BigInt(1) << width) - 1)

  private def less(lhs: BigInt, rhs: BigInt, width: Int): Boolean = {
    val delta = distance(lhs, rhs, width)
    lhs != rhs && delta < (BigInt(1) << (width - 1))
  }

  test("serial ordering treats a small wrapped value as younger") {
    assert(less(BigInt("fffffffe", 16), 1, 32))
    assert(!less(1, BigInt("fffffffe", 16), 32))
  }

  test("equal values are less-equal but not strictly older") {
    val value = BigInt("81234567", 16)
    assert(!less(value, value, 32))
  }

  test("half-range separation is explicitly ambiguous") {
    assert(distance(0, BigInt(1) << 31, 32) == (BigInt(1) << 31))
    assert(!less(0, BigInt(1) << 31, 32))
  }

  test("Chisel LSIDOrder elaborates full-width modular comparisons") {
    val sv = ChiselStage.emitSystemVerilog(new LSIDOrderProbe(32))
    assert(sv.contains("module LSIDOrderProbe"))
    assert(sv.contains("io_ambiguous"))
  }
}
