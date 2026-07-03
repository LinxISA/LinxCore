package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2ReplayRowLifecycleReadyReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)
  final case class Row(
      valid: Boolean = false,
      resolved: Boolean = false,
      bid: Id = Id(valid = false),
      gid: Id = Id(valid = false),
      rid: Id = Id(valid = false),
      loadLsId: Id = Id(valid = false))

  final case class Result(
      active: Boolean,
      candidateValid: Boolean,
      slotIdentityValid: Boolean,
      resolvedRowMatch: Boolean,
      matchedMask: Int,
      matchCount: Int,
      rowClearIndex: Int,
      rowClearReady: Boolean,
      lifecycleReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoSlot: Boolean,
      blockedByInvalidSlotIdentity: Boolean,
      blockedByNoResolvedRow: Boolean,
      blockedByMultipleResolvedRows: Boolean,
      blockedByLifecycleClearDisabled: Boolean,
      invalidLifecycleClearWithoutRow: Boolean)

  private def equal(a: Id, b: Id): Boolean =
    a.valid && b.valid && a.wrap == b.wrap && a.value == b.value

  def apply(
      enable: Boolean,
      flush: Boolean,
      lifecycleClearEnable: Boolean,
      slotOccupied: Boolean,
      slotBid: Id,
      slotGid: Id,
      slotRid: Id,
      slotLoadLsId: Id,
      rows: Seq[Row]): Result = {
    val active = enable && !flush
    val candidateValid = active && slotOccupied
    val slotIdentityValid =
      slotBid.valid && slotGid.valid && slotRid.valid && slotLoadLsId.valid
    val candidateIdentityValid = candidateValid && slotIdentityValid
    val rawMask = rows.zipWithIndex.foldLeft(0) { case (mask, (row, index)) =>
      val matches =
        row.valid &&
          row.resolved &&
          equal(row.bid, slotBid) &&
          equal(row.gid, slotGid) &&
          equal(row.rid, slotRid) &&
          equal(row.loadLsId, slotLoadLsId)
      if (matches) mask | (1 << index) else mask
    }
    val matchedMask = if (candidateIdentityValid) rawMask else 0
    val matchCount = Integer.bitCount(matchedMask)
    val resolvedRowMatch = candidateIdentityValid && matchedMask != 0
    val multipleResolvedRows = candidateIdentityValid && matchCount > 1
    val rowClearReady = resolvedRowMatch && !multipleResolvedRows
    val rowClearIndex =
      if (rowClearReady) (0 until rows.size).find(index => (matchedMask & (1 << index)) != 0).get else 0

    Result(
      active = active,
      candidateValid = candidateValid,
      slotIdentityValid = candidateValid && slotIdentityValid,
      resolvedRowMatch = resolvedRowMatch,
      matchedMask = matchedMask,
      matchCount = if (candidateIdentityValid) matchCount else 0,
      rowClearIndex = rowClearIndex,
      rowClearReady = rowClearReady,
      lifecycleReady = rowClearReady && lifecycleClearEnable,
      blockedByDisabled = !enable && slotOccupied,
      blockedByFlush = enable && flush && slotOccupied,
      blockedByNoSlot = active && !slotOccupied,
      blockedByInvalidSlotIdentity = candidateValid && !slotIdentityValid,
      blockedByNoResolvedRow = candidateIdentityValid && matchedMask == 0,
      blockedByMultipleResolvedRows = multipleResolvedRows,
      blockedByLifecycleClearDisabled = rowClearReady && !lifecycleClearEnable,
      invalidLifecycleClearWithoutRow = lifecycleClearEnable && !rowClearReady)
  }
}

class LoadReplayReturnPipeW2ReplayRowLifecycleReadySpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2ReplayRowLifecycleReadyReference._

  private val slotBid = Id(valid = true, value = 2)
  private val slotGid = Id(valid = true, value = 1)
  private val slotRid = Id(valid = true, value = 5)
  private val slotLsId = Id(valid = true, value = 7)
  private val matchingRow = Row(
    valid = true,
    resolved = true,
    bid = slotBid,
    gid = slotGid,
    rid = slotRid,
    loadLsId = slotLsId)

  test("finds the unique resolved LIQ row matching the resident W2 slot identity") {
    val result = LoadReplayReturnPipeW2ReplayRowLifecycleReadyReference(
      enable = true,
      flush = false,
      lifecycleClearEnable = true,
      slotOccupied = true,
      slotBid = slotBid,
      slotGid = slotGid,
      slotRid = slotRid,
      slotLoadLsId = slotLsId,
      rows = Seq(Row(), matchingRow, Row(valid = true, resolved = false, bid = slotBid, gid = slotGid, rid = slotRid, loadLsId = slotLsId), Row()))

    assert(result.candidateValid)
    assert(result.slotIdentityValid)
    assert(result.resolvedRowMatch)
    assert(result.matchedMask == 0x2)
    assert(result.matchCount == 1)
    assert(result.rowClearIndex == 1)
    assert(result.rowClearReady)
    assert(result.lifecycleReady)
  }

  test("keeps the row lifecycle dormant while the live lifecycle clear arm is disabled") {
    val result = LoadReplayReturnPipeW2ReplayRowLifecycleReadyReference(
      enable = true,
      flush = false,
      lifecycleClearEnable = false,
      slotOccupied = true,
      slotBid = slotBid,
      slotGid = slotGid,
      slotRid = slotRid,
      slotLoadLsId = slotLsId,
      rows = Seq(Row(), matchingRow, Row(), Row()))

    assert(result.rowClearReady)
    assert(!result.lifecycleReady)
    assert(result.blockedByLifecycleClearDisabled)
  }

  test("requires valid slot identity before matching resolved rows") {
    val result = LoadReplayReturnPipeW2ReplayRowLifecycleReadyReference(
      enable = true,
      flush = false,
      lifecycleClearEnable = true,
      slotOccupied = true,
      slotBid = slotBid.copy(valid = false),
      slotGid = slotGid,
      slotRid = slotRid,
      slotLoadLsId = slotLsId,
      rows = Seq(Row(), matchingRow, Row(), Row()))

    assert(result.candidateValid)
    assert(!result.slotIdentityValid)
    assert(!result.resolvedRowMatch)
    assert(result.matchedMask == 0)
    assert(result.blockedByInvalidSlotIdentity)
    assert(result.invalidLifecycleClearWithoutRow)
  }

  test("reports absent and duplicate resolved-row matches") {
    val noMatch = LoadReplayReturnPipeW2ReplayRowLifecycleReadyReference(
      enable = true,
      flush = false,
      lifecycleClearEnable = false,
      slotOccupied = true,
      slotBid = slotBid,
      slotGid = slotGid,
      slotRid = slotRid,
      slotLoadLsId = slotLsId,
      rows = Seq(Row(), Row(valid = true, resolved = false, bid = slotBid, gid = slotGid, rid = slotRid, loadLsId = slotLsId), Row(), Row()))
    val duplicate = LoadReplayReturnPipeW2ReplayRowLifecycleReadyReference(
      enable = true,
      flush = false,
      lifecycleClearEnable = true,
      slotOccupied = true,
      slotBid = slotBid,
      slotGid = slotGid,
      slotRid = slotRid,
      slotLoadLsId = slotLsId,
      rows = Seq(matchingRow, Row(), matchingRow, Row()))

    assert(noMatch.blockedByNoResolvedRow)
    assert(!noMatch.rowClearReady)
    assert(duplicate.matchedMask == 0x5)
    assert(duplicate.matchCount == 2)
    assert(!duplicate.rowClearReady)
    assert(duplicate.blockedByMultipleResolvedRows)
    assert(duplicate.invalidLifecycleClearWithoutRow)
  }

  test("reports disabled flushed and empty-slot blockers") {
    val disabled = LoadReplayReturnPipeW2ReplayRowLifecycleReadyReference(
      enable = false,
      flush = false,
      lifecycleClearEnable = false,
      slotOccupied = true,
      slotBid = slotBid,
      slotGid = slotGid,
      slotRid = slotRid,
      slotLoadLsId = slotLsId,
      rows = Seq(matchingRow, Row(), Row(), Row()))
    val flushed = LoadReplayReturnPipeW2ReplayRowLifecycleReadyReference(
      enable = true,
      flush = true,
      lifecycleClearEnable = false,
      slotOccupied = true,
      slotBid = slotBid,
      slotGid = slotGid,
      slotRid = slotRid,
      slotLoadLsId = slotLsId,
      rows = Seq(matchingRow, Row(), Row(), Row()))
    val noSlot = LoadReplayReturnPipeW2ReplayRowLifecycleReadyReference(
      enable = true,
      flush = false,
      lifecycleClearEnable = false,
      slotOccupied = false,
      slotBid = slotBid,
      slotGid = slotGid,
      slotRid = slotRid,
      slotLoadLsId = slotLsId,
      rows = Seq(matchingRow, Row(), Row(), Row()))

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(noSlot.blockedByNoSlot)
    assert(!disabled.rowClearReady)
    assert(!flushed.rowClearReady)
    assert(!noSlot.rowClearReady)
  }

  test("Chisel LoadReplayReturnPipeW2ReplayRowLifecycleReady elaborates lifecycle diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2ReplayRowLifecycleReady(liqEntries = 4, idEntries = 8, storeEntries = 8))

    assert(sv.contains("module LoadReplayReturnPipeW2ReplayRowLifecycleReady"))
    assert(sv.contains("io_lifecycleReady"))
    assert(sv.contains("io_blockedByMultipleResolvedRows"))
  }
}
