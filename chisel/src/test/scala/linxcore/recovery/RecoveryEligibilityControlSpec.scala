package linxcore.recovery

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object RecoveryEligibilityControlReference {
  final case class Id(wrap: Boolean = false, value: Int = 0)

  def less(lhs: Id, rhs: Id): Boolean =
    if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

  def lessEqual(lhs: Id, rhs: Id): Boolean = lhs == rhs || less(lhs, rhs)

  def eligible(
      requestBid: Id,
      requestRid: Id,
      oldestBid: Id,
      oldestRid: Id,
      baseOnBid: Boolean,
      oldestValid: Boolean,
      immediate: Boolean): Boolean =
    immediate || (oldestValid &&
      (if (baseOnBid) lessEqual(requestBid, oldestBid)
       else less(requestBid, oldestBid) || (requestBid == oldestBid && lessEqual(requestRid, oldestRid))))
}

class RecoveryEligibilityControlSpec extends AnyFunSuite {
  import RecoveryEligibilityControlReference._

  test("non-immediate recovery waits until its BID becomes oldest") {
    assert(!eligible(Id(value = 2), Id(), Id(value = 1), Id(), baseOnBid = true, oldestValid = true, immediate = false))
    assert(eligible(Id(value = 2), Id(), Id(value = 2), Id(), baseOnBid = true, oldestValid = true, immediate = false))
  }

  test("RID recovery waits within the same block and immediate recovery bypasses age") {
    assert(!eligible(Id(value = 2), Id(value = 2), Id(value = 2), Id(value = 1), baseOnBid = false, oldestValid = true, immediate = false))
    assert(eligible(Id(value = 2), Id(value = 2), Id(value = 2), Id(value = 2), baseOnBid = false, oldestValid = true, immediate = false))
    assert(eligible(Id(value = 7), Id(value = 7), Id(), Id(), baseOnBid = false, oldestValid = false, immediate = true))
  }

  test("wrap-qualified BID order controls eligibility") {
    assert(eligible(Id(wrap = false, value = 7), Id(), Id(wrap = true, value = 0), Id(), baseOnBid = true, oldestValid = true, immediate = false))
    assert(!eligible(Id(wrap = true, value = 1), Id(), Id(wrap = true, value = 0), Id(), baseOnBid = true, oldestValid = true, immediate = false))
  }

  test("Chisel recovery eligibility elaborates oldest BID and RID policy") {
    val sv = ChiselStage.emitSystemVerilog(new RecoveryEligibilityControl(entries = 8))
    assert(sv.contains("module RecoveryEligibilityControl"))
    assert(sv.contains("io_eligible"))
    assert(sv.contains("io_blockedByAge"))
  }
}
