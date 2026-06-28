package linxcore.bctrl

import chisel3._
import chisel3.util.{Cat, log2Ceil}

object BID {
  val DefaultWidth: Int = 64

  def slotBits(entries: Int): Int = {
    require(entries > 1, "BID entries must be greater than one")
    require((entries & (entries - 1)) == 0, "BID entries must be a power of two")
    log2Ceil(entries)
  }

  def slot(id: UInt, entries: Int): UInt =
    id(slotBits(entries) - 1, 0)

  def uniq(id: UInt, entries: Int, bidWidth: Int = DefaultWidth): UInt = {
    val bits = slotBits(entries)
    require(bidWidth > bits, "BID width must include uniqueness bits above the slot")
    id(bidWidth - 1, bits)
  }

  def fromParts(uniq: UInt, slot: UInt, entries: Int, bidWidth: Int = DefaultWidth): UInt = {
    val bits = slotBits(entries)
    require(bidWidth > bits, "BID width must include uniqueness bits above the slot")
    Cat(uniq(bidWidth - bits - 1, 0), slot(bits - 1, 0))
  }

  def cmdTag(id: UInt): UInt =
    id(7, 0)

  def keepOnFlush(id: UInt, flushBid: UInt): Bool =
    id <= flushBid

  def killOnFlush(id: UInt, flushBid: UInt): Bool =
    id > flushBid
}
