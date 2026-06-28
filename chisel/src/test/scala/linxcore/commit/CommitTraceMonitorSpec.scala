package linxcore.commit

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object CommitTraceMonitorReference {
  final case class Slot(
      valid: Boolean,
      slot: Int,
      bid: BigInt = 0,
      gid: BigInt = 0,
      rid: BigInt = 0,
      wbValid: Boolean = false,
      src0Valid: Boolean = false,
      src1Valid: Boolean = false,
      dstValid: Boolean = false,
      memValid: Boolean = false,
      trapValid: Boolean = false)

  final case class Result(
      validMask: Int,
      validCount: Int,
      skippedSlot: Boolean,
      duplicateIdentity: Boolean,
      slotMismatch: Boolean,
      invalidSideEffect: Boolean) {
    def contractError: Boolean =
      skippedSlot || duplicateIdentity || slotMismatch || invalidSideEffect
  }

  private def sideEffect(slot: Slot): Boolean =
    slot.wbValid || slot.src0Valid || slot.src1Valid ||
      slot.dstValid || slot.memValid || slot.trapValid

  def analyze(slots: Seq[Slot]): Result = {
    val validMask = slots.zipWithIndex.foldLeft(0) {
      case (mask, (slot, index)) => if (slot.valid) mask | (1 << index) else mask
    }
    val validCount = slots.count(_.valid)
    val skippedSlot = slots.indices.exists { index =>
      index > 0 && slots(index).valid && !slots.take(index).forall(_.valid)
    }
    val liveIds = slots.filter(_.valid).map(slot => (slot.bid, slot.gid, slot.rid))
    val duplicateIdentity = liveIds.distinct.size != liveIds.size
    val slotMismatch = slots.zipWithIndex.exists {
      case (slot, index) => slot.valid && slot.slot != index
    }
    val invalidSideEffect = slots.exists(slot => !slot.valid && sideEffect(slot))

    Result(
      validMask = validMask,
      validCount = validCount,
      skippedSlot = skippedSlot,
      duplicateIdentity = duplicateIdentity,
      slotMismatch = slotMismatch,
      invalidSideEffect = invalidSideEffect
    )
  }
}

class CommitTraceMonitorSpec extends AnyFunSuite {
  import CommitTraceMonitorReference._

  test("reference accepts contiguous unique commit windows") {
    val result = analyze(
      Seq(
        Slot(valid = true, slot = 0, bid = 7, rid = 0),
        Slot(valid = true, slot = 1, bid = 7, rid = 1),
        Slot(valid = false, slot = 0),
        Slot(valid = false, slot = 0)
      )
    )

    assert(result.validMask == 0x3)
    assert(result.validCount == 2)
    assert(!result.contractError)
  }

  test("reference flags skipped slots and slot label mismatches") {
    val result = analyze(
      Seq(
        Slot(valid = true, slot = 0, bid = 7, rid = 0),
        Slot(valid = false, slot = 0),
        Slot(valid = true, slot = 1, bid = 7, rid = 2),
        Slot(valid = false, slot = 0)
      )
    )

    assert(result.validMask == 0x5)
    assert(result.validCount == 2)
    assert(result.skippedSlot)
    assert(result.slotMismatch)
    assert(result.contractError)
  }

  test("reference flags duplicate CommitInfo identities") {
    val result = analyze(
      Seq(
        Slot(valid = true, slot = 0, bid = 3, gid = 1, rid = 9),
        Slot(valid = true, slot = 1, bid = 3, gid = 1, rid = 9)
      )
    )

    assert(result.duplicateIdentity)
    assert(result.contractError)
  }

  test("reference flags side effects on invalid fixed-width slots") {
    val result = analyze(
      Seq(
        Slot(valid = true, slot = 0, bid = 3, rid = 0),
        Slot(valid = false, slot = 0, memValid = true)
      )
    )

    assert(result.invalidSideEffect)
    assert(result.contractError)
  }

  test("Chisel CommitTraceMonitor elaborates with contract-error outputs") {
    val sv = ChiselStage.emitSystemVerilog(
      new CommitTraceMonitor(CommitTraceParams(commitWidth = 4))
    )

    assert(sv.contains("module CommitTraceMonitor"))
    assert(sv.contains("contractError"))
    assert(sv.contains("duplicateIdentity"))
  }
}
