package linxcore.bctrl

import org.scalatest.funsuite.AnyFunSuite

object BIDReference {
  def slotBits(entries: Int): Int = {
    require(entries > 1 && (entries & (entries - 1)) == 0)
    Integer.numberOfTrailingZeros(entries)
  }

  def fromParts(uniq: BigInt, slot: BigInt, entries: Int): BigInt =
    (uniq << slotBits(entries)) | (slot & (entries - 1))

  def slot(id: BigInt, entries: Int): BigInt =
    id & (entries - 1)

  def uniq(id: BigInt, entries: Int): BigInt =
    id >> slotBits(entries)

  def cmdTag(id: BigInt): BigInt =
    id & 0xff

  def keepOnFlush(id: BigInt, flushBid: BigInt): Boolean =
    id <= flushBid

  def killOnFlush(id: BigInt, flushBid: BigInt): Boolean =
    id > flushBid
}

object RefBlockType extends Enumeration {
  val Scalar, Engine = Value
}

object RefBrobStatus extends Enumeration {
  val Free, Allocated, Completed, Flushed = Value
}

final case class RefBrobEntry(
    bid: BigInt = 0,
    status: RefBrobStatus.Value = RefBrobStatus.Free,
    blockType: RefBlockType.Value = RefBlockType.Scalar,
    scalarDone: Boolean = false,
    engineDone: Boolean = false,
    exception: Boolean = false)

final class RefBrob(entries: Int) {
  private val table = Array.fill(entries)(RefBrobEntry())

  private def idx(bid: BigInt): Int =
    BIDReference.slot(bid, entries).toInt

  def entry(bid: BigInt): RefBrobEntry =
    table(idx(bid))

  def alloc(bid: BigInt, blockType: RefBlockType.Value): Boolean = {
    val i = idx(bid)
    if (table(i).status != RefBrobStatus.Free && table(i).status != RefBrobStatus.Flushed) {
      return false
    }
    table(i) = RefBrobEntry(bid = bid, status = RefBrobStatus.Allocated, blockType = blockType)
    true
  }

  def scalarDone(bid: BigInt, exception: Boolean = false): Unit = {
    val i = idx(bid)
    val cur = table(i)
    if (cur.status == RefBrobStatus.Free || cur.status == RefBrobStatus.Flushed || cur.bid != bid) {
      return
    }
    val next = cur.copy(scalarDone = true, exception = cur.exception || exception)
    table(i) = if (next.blockType == RefBlockType.Scalar || next.engineDone) {
      next.copy(status = RefBrobStatus.Completed)
    } else {
      next
    }
  }

  def engineDone(bid: BigInt, exception: Boolean = false): Unit = {
    val i = idx(bid)
    val cur = table(i)
    if (cur.status == RefBrobStatus.Free || cur.status == RefBrobStatus.Flushed || cur.bid != bid) {
      return
    }
    val next = cur.copy(engineDone = true, exception = cur.exception || exception)
    table(i) = if (next.scalarDone) next.copy(status = RefBrobStatus.Completed) else next
  }

  def flush(flushBid: BigInt): Unit =
    for (i <- table.indices) {
      if (table(i).status != RefBrobStatus.Free && BIDReference.killOnFlush(table(i).bid, flushBid)) {
        table(i) = table(i).copy(status = RefBrobStatus.Flushed, scalarDone = false, engineDone = false, exception = false)
      }
    }

  def retire(bid: BigInt): Unit = {
    val i = idx(bid)
    val cur = table(i)
    if (cur.status == RefBrobStatus.Completed && cur.bid == bid) {
      table(i) = RefBrobEntry()
    }
  }
}

class BROBSpec extends AnyFunSuite {
  test("BID uses low slot bits, high uniqueness bits, and low 8-bit command tag") {
    val entries = 128
    val bid = BIDReference.fromParts(uniq = 0x25, slot = 0x7f, entries)
    assert(BIDReference.slot(bid, entries) == 0x7f)
    assert(BIDReference.uniq(bid, entries) == 0x25)
    assert(BIDReference.cmdTag(bid) == 0xff)
  }

  test("BID flush keeps current-or-older full BID and kills younger full BID") {
    val entries = 128
    val flushBid = BIDReference.fromParts(uniq = 4, slot = 10, entries)
    val olderHigherSlot = BIDReference.fromParts(uniq = 3, slot = 120, entries)
    val current = flushBid
    val youngerLowerSlot = BIDReference.fromParts(uniq = 5, slot = 1, entries)

    assert(BIDReference.keepOnFlush(olderHigherSlot, flushBid))
    assert(BIDReference.keepOnFlush(current, flushBid))
    assert(BIDReference.killOnFlush(youngerLowerSlot, flushBid))
  }

  test("BROB reference completes scalar blocks on scalar completion") {
    val brob = new RefBrob(entries = 16)
    val bid = BIDReference.fromParts(uniq = 0, slot = 2, entries = 16)
    assert(brob.alloc(bid, RefBlockType.Scalar))
    brob.scalarDone(bid)
    assert(brob.entry(bid).status == RefBrobStatus.Completed)
  }

  test("BROB reference rejects stale same-slot completion and retire events") {
    val brob = new RefBrob(entries = 4)
    val live = BIDReference.fromParts(uniq = 1, slot = 1, entries = 4)
    val staleSameSlot = BIDReference.fromParts(uniq = 0, slot = 1, entries = 4)

    assert(brob.alloc(live, RefBlockType.Scalar))
    brob.scalarDone(staleSameSlot)
    assert(brob.entry(live).status == RefBrobStatus.Allocated)

    brob.scalarDone(live)
    assert(brob.entry(live).status == RefBrobStatus.Completed)
    brob.retire(staleSameSlot)
    assert(brob.entry(live).status == RefBrobStatus.Completed)
    brob.retire(live)
    assert(brob.entry(live).status == RefBrobStatus.Free)
  }

  test("BROB reference requires scalar and engine completion for engine blocks") {
    val brob = new RefBrob(entries = 16)
    val bid = BIDReference.fromParts(uniq = 0, slot = 3, entries = 16)
    assert(brob.alloc(bid, RefBlockType.Engine))
    brob.scalarDone(bid)
    assert(brob.entry(bid).status == RefBrobStatus.Allocated)
    brob.engineDone(bid)
    assert(brob.entry(bid).status == RefBrobStatus.Completed)
  }

  test("BROB reference flush preserves flush BID and clears younger BIDs") {
    val brob = new RefBrob(entries = 16)
    val keep = BIDReference.fromParts(uniq = 1, slot = 14, entries = 16)
    val flush = BIDReference.fromParts(uniq = 2, slot = 0, entries = 16)
    val kill = BIDReference.fromParts(uniq = 2, slot = 1, entries = 16)

    assert(brob.alloc(keep, RefBlockType.Scalar))
    assert(brob.alloc(flush, RefBlockType.Scalar))
    assert(brob.alloc(kill, RefBlockType.Engine))
    brob.flush(flush)

    assert(brob.entry(keep).status == RefBrobStatus.Allocated)
    assert(brob.entry(flush).status == RefBrobStatus.Allocated)
    assert(brob.entry(kill).status == RefBrobStatus.Flushed)
    assert(brob.alloc(kill, RefBlockType.Scalar))
    assert(brob.entry(kill).status == RefBrobStatus.Allocated)
  }
}
