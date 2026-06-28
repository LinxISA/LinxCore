package linxcore.rob

import chisel3._
import chisel3.util._

class ROBID(val entries: Int) extends Bundle {
  require(entries > 1, "ROBID entries must be greater than one")
  require((entries & (entries - 1)) == 0, "ROBID entries must be a power of two")

  val valid = Bool()
  val wrap = Bool()
  val value = UInt(log2Ceil(entries).W)
}

object ROBID {
  def zero(entries: Int): ROBID = {
    val out = Wire(new ROBID(entries))
    out.valid := true.B
    out.wrap := false.B
    out.value := 0.U
    out
  }

  def disabled(entries: Int): ROBID = {
    val out = zero(entries)
    out.valid := false.B
    out
  }

  def add(id: ROBID, offset: UInt): ROBID = {
    val entries = id.entries
    val out = Wire(new ROBID(entries))
    val sum = id.value +& offset
    val wraps = sum >= entries.U

    out.valid := id.valid
    out.wrap := id.wrap ^ wraps
    out.value := Mux(wraps, sum - entries.U, sum)(log2Ceil(entries) - 1, 0)
    out
  }

  def inc(id: ROBID): ROBID = add(id, 1.U)

  def sub(id: ROBID, offset: UInt): ROBID = {
    val entries = id.entries
    val out = Wire(new ROBID(entries))
    val wraps = id.value < offset

    out.valid := id.valid
    out.wrap := id.wrap ^ wraps
    out.value := Mux(wraps, entries.U - (offset - id.value), id.value - offset)(
      log2Ceil(entries) - 1,
      0
    )
    out
  }

  def equal(lhs: ROBID, rhs: ROBID): Bool = {
    (lhs.wrap === rhs.wrap) && (lhs.value === rhs.value)
  }

  def less(lhs: ROBID, rhs: ROBID): Bool = {
    Mux(lhs.wrap === rhs.wrap, lhs.value < rhs.value, lhs.value > rhs.value)
  }

  def lessEqual(lhs: ROBID, rhs: ROBID): Bool = less(lhs, rhs) || equal(lhs, rhs)

  def greater(lhs: ROBID, rhs: ROBID): Bool = {
    Mux(lhs.wrap === rhs.wrap, lhs.value > rhs.value, lhs.value < rhs.value)
  }

  def greaterEqual(lhs: ROBID, rhs: ROBID): Bool = greater(lhs, rhs) || equal(lhs, rhs)

  def gap(newer: ROBID, older: ROBID): UInt = {
    val entries = newer.entries
    val raw = Wire(UInt((log2Ceil(entries) + 1).W))
    raw := Mux(
      newer.wrap === older.wrap,
      newer.value - older.value,
      newer.value + entries.U - older.value
    )
    raw
  }
}
