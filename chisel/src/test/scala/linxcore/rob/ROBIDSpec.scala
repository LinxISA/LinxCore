package linxcore.rob

import org.scalatest.funsuite.AnyFunSuite

final case class ROBIDValue(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)

object ROBIDReference {
  def inc(id: ROBIDValue, entries: Int): ROBIDValue =
    add(id, 1, entries)

  def add(id: ROBIDValue, offset: Int, entries: Int): ROBIDValue = {
    require(offset < entries)
    val sum = id.value + offset
    if (sum < entries) id.copy(value = sum)
    else id.copy(wrap = !id.wrap, value = sum % entries)
  }

  def sub(id: ROBIDValue, offset: Int, entries: Int): ROBIDValue = {
    require(offset < entries)
    if (id.value < offset) id.copy(wrap = !id.wrap, value = entries - (offset - id.value))
    else id.copy(value = id.value - offset)
  }

  def less(lhs: ROBIDValue, rhs: ROBIDValue): Boolean =
    if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

  def lessEqual(lhs: ROBIDValue, rhs: ROBIDValue): Boolean =
    less(lhs, rhs) || lhs == rhs

  def gap(newer: ROBIDValue, older: ROBIDValue, entries: Int): Int =
    if (newer.wrap == older.wrap) newer.value - older.value
    else (newer.value + entries) - older.value
}

class ROBIDSpec extends AnyFunSuite {
  test("ROBID reference arithmetic follows LinxCoreModel wrap behavior") {
    val n = 8
    assert(ROBIDReference.inc(ROBIDValue(value = 6), n) == ROBIDValue(value = 7))
    assert(ROBIDReference.inc(ROBIDValue(value = 7), n) == ROBIDValue(wrap = true, value = 0))
    assert(ROBIDReference.add(ROBIDValue(value = 6), 3, n) == ROBIDValue(wrap = true, value = 1))
    assert(ROBIDReference.sub(ROBIDValue(wrap = true, value = 1), 3, n) == ROBIDValue(value = 6))
  }

  test("ROBID reference ordering follows wrap-aware LinxCoreModel comparison") {
    val older = ROBIDValue(wrap = false, value = 7)
    val newer = ROBIDValue(wrap = true, value = 0)
    assert(ROBIDReference.less(older, newer))
    assert(!ROBIDReference.less(newer, older))
    assert(ROBIDReference.lessEqual(older, older))
  }

  test("ROBID reference gap follows LinxCoreModel CalGap direction") {
    assert(ROBIDReference.gap(ROBIDValue(value = 5), ROBIDValue(value = 3), 8) == 2)
    assert(ROBIDReference.gap(ROBIDValue(wrap = true, value = 1), ROBIDValue(value = 6), 8) == 3)
  }
}
