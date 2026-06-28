package linxcore.commit

import org.scalatest.funsuite.AnyFunSuite

object CommitTraceReference {
  final case class Slot(
      valid: Boolean,
      slot: Int,
      pc: BigInt,
      insn: BigInt,
      length: Int,
      bid: BigInt = 0,
      gid: BigInt = 0,
      rid: BigInt = 0,
      blockBid: BigInt = 0)

  def emittedSlots(slots: Seq[Slot]): Seq[Slot] =
    slots.filter(_.valid)

  def duplicateCommitIdentity(slots: Seq[Slot]): Boolean = {
    val ids = emittedSlots(slots).map(s => (s.bid, s.gid, s.rid))
    ids.distinct.size != ids.size
  }

  def skippedCommitSlot(slots: Seq[Slot]): Boolean = {
    val fired = emittedSlots(slots).map(_.slot)
    fired.nonEmpty && fired != fired.min.to(fired.max)
  }
}

class CommitTraceSpec extends AnyFunSuite {
  test("CommitTraceParams preserves default Linx widths") {
    val p = CommitTraceParams()
    assert(p.commitWidth == 4)
    assert(p.slotWidth == 2)
    assert(p.insnWidth >= 48)
    assert(p.lenWidth == 4)
    assert(p.blockBidWidth == 64)
  }

  test("CommitTraceSchema matches the QEMU comparator mandatory fields") {
    val required = CommitTraceSchema.RequiredFields
    assert(required.head == "pc")
    assert(required.last == "next_pc")
    assert(required.contains("mem_is_store"))
    assert(required.contains("traparg0"))
    assert(required.distinct == required)
  }

  test("CommitTraceSchema keeps model identity separate from 64-bit block BID sideband") {
    assert(CommitTraceSchema.CommitInfoFields == Seq("bid", "gid", "rid"))
    assert(CommitTraceSchema.SidebandFields.contains("bid"))
    assert(CommitTraceSchema.SidebandFields.contains("block_bid"))
    assert(CommitTraceSchema.SidebandFields.contains("block_bid_valid"))
  }

  test("Commit trace reference emits only valid retiring slots") {
    val slots = Seq(
      CommitTraceReference.Slot(valid = true, slot = 0, pc = 0x1000, insn = 0x13, length = 4, rid = 0),
      CommitTraceReference.Slot(valid = false, slot = 1, pc = 0x1004, insn = 0x13, length = 4, rid = 1),
      CommitTraceReference.Slot(valid = true, slot = 2, pc = 0x1008, insn = 0x13, length = 4, rid = 2)
    )

    val emitted = CommitTraceReference.emittedSlots(slots)
    assert(emitted.map(_.slot) == Seq(0, 2))
    assert(CommitTraceReference.skippedCommitSlot(slots))
  }

  test("Commit trace reference catches duplicate model commit identity") {
    val slots = Seq(
      CommitTraceReference.Slot(valid = true, slot = 0, pc = 0x1000, insn = 0x13, length = 4, bid = 7, gid = 0, rid = 3),
      CommitTraceReference.Slot(valid = true, slot = 1, pc = 0x1004, insn = 0x13, length = 4, bid = 7, gid = 0, rid = 3)
    )

    assert(CommitTraceReference.duplicateCommitIdentity(slots))
  }
}
