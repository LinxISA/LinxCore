package linxcore.bctrl

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object BrobStoreCountPublisherReference {
  final case class Event(stid: Int, bid: Int, explicit: Boolean)

  def select(scalar: Option[Event], explicit: Option[Event]): Option[Event] =
    (scalar, explicit) match {
      case (Some(s), Some(e)) if s.stid == e.stid && s.bid == e.bid => Some(e)
      case (Some(s), Some(_)) => Some(s)
      case (Some(s), None) => Some(s)
      case (None, Some(e)) => Some(e)
      case _ => None
    }

  def killed(head: Int, live: Int, firstKilled: Int, bid: Int, mask: Int): Boolean = {
    val distance = (bid - head) & mask
    val firstDistance = (firstKilled - head) & mask
    distance < live && distance >= firstDistance
  }
}

class BrobStoreCountPublisherSpec extends AnyFunSuite {
  import BrobStoreCountPublisherReference._

  test("same-block explicit count overrides scalar count closure") {
    val scalar = Event(0, 7, explicit = false)
    val explicit = Event(0, 7, explicit = true)
    assert(select(Some(scalar), Some(explicit)).contains(explicit))
  }

  test("different-block collision publishes scalar closure first") {
    val scalar = Event(0, 7, explicit = false)
    val explicit = Event(0, 8, explicit = true)
    assert(select(Some(scalar), Some(explicit)).contains(scalar))
  }

  test("recovery kill classification follows bounded modular live order") {
    assert(killed(head = 14, live = 3, firstKilled = 15, bid = 15, mask = 15))
    assert(killed(head = 14, live = 3, firstKilled = 15, bid = 0, mask = 15))
    assert(!killed(head = 14, live = 3, firstKilled = 15, bid = 14, mask = 15))
    assert(!killed(head = 14, live = 3, firstKilled = 15, bid = 1, mask = 15))
  }

  test("BrobStoreCountPublisher elaborates independent retained sources") {
    val sv = ChiselStage.emitSystemVerilog(new BrobStoreCountPublisher(
      entries = 8,
      bidWidth = 16,
      stidWidth = 2,
      stidCount = 2,
      storeCountWidth = 32))

    assert(sv.contains("module BrobStoreCountPublisher"))
    assert(sv.contains("scalarPendingValid"))
    assert(sv.contains("explicitPendingValid"))
    assert(sv.contains("io_sameBlockCollision"))
    assert(sv.contains("io_explicitInputCanceled"))
  }
}
