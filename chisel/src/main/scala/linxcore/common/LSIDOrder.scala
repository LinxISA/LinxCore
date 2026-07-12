package linxcore.common

import chisel3._

object LSIDOrder {
  private def requireCompatible(lhs: UInt, rhs: UInt): Int = {
    require(lhs.getWidth == rhs.getWidth, "LSID operands must have equal widths")
    require(lhs.getWidth >= 2, "LSID serial arithmetic requires at least two bits")
    lhs.getWidth
  }

  def equal(lhs: UInt, rhs: UInt): Bool = {
    requireCompatible(lhs, rhs)
    lhs === rhs
  }

  def ambiguous(lhs: UInt, rhs: UInt): Bool = {
    val width = requireCompatible(lhs, rhs)
    val distance = Wire(UInt(width.W))
    distance := rhs - lhs
    distance === (BigInt(1) << (width - 1)).U(width.W)
  }

  def less(lhs: UInt, rhs: UInt): Bool = {
    val width = requireCompatible(lhs, rhs)
    val distance = Wire(UInt(width.W))
    distance := rhs - lhs
    (lhs =/= rhs) && !distance(width - 1)
  }

  def lessEqual(lhs: UInt, rhs: UInt): Bool = equal(lhs, rhs) || less(lhs, rhs)
}
