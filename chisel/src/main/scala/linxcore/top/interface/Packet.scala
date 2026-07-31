package linxcore.top.interface

import chisel3._
import chisel3.util.log2Ceil

object PrefixPacketContract {
  def countWidth(maxWidth: Int): Int = {
    require(maxWidth > 0, "packet width must be positive")
    math.max(1, log2Ceil(maxWidth + 1))
  }

  /** A count-only packet represents entries [0, count); holes are impossible. */
  def countIsLegal(count: UInt, maxWidth: Int): Bool =
    count <= maxWidth.U
}
