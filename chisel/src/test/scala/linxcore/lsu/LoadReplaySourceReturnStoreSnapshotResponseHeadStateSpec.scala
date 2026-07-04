package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference {
  final case class Result(
      active: Boolean,
      headStale: Boolean,
      externalHeadStaleUsed: Boolean,
      reducedHeadTargetsRow: Boolean,
      reducedHeadRepick: Boolean,
      reducedHeadRowValid: Boolean,
      reducedHeadScbReturned: Boolean,
      reducedHeadApplyEligible: Boolean,
      reducedHeadStale: Boolean,
      reducedHeadOneHot: Int,
      blockedByNoHead: Boolean,
      blockedByReducedDisabled: Boolean,
      blockedByUnsupportedCluster: Boolean,
      blockedByEntryOutOfRange: Boolean,
      blockedByInvalidRow: Boolean,
      blockedByScbNotReturned: Boolean,
      blockedByStillRepick: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean)

  def apply(
      liqEntries: Int,
      enable: Boolean,
      flush: Boolean,
      reducedEnable: Boolean,
      headValid: Boolean,
      responseClusterId: Int,
      responseEntryId: Int,
      repickMask: Int,
      rowProofEnable: Boolean = false,
      rowValidMask: Int = 0,
      rowScbReturnedMask: Int = 0,
      externalHeadStale: Boolean = false): Result = {
    val active = enable && !flush
    val reducedCandidate = active && reducedEnable && headValid
    val clusterIsReduced = responseClusterId == 0
    val entryInRange = responseEntryId < liqEntries
    val rawOneHot = 1 << (responseEntryId & (liqEntries - 1))
    val reducedHeadTargetsRow = reducedCandidate && clusterIsReduced && entryInRange
    val repickMaskHit = (repickMask & rawOneHot) != 0
    val rowValidMaskHit = (rowValidMask & rawOneHot) != 0
    val rowScbReturnedMaskHit = (rowScbReturnedMask & rawOneHot) != 0
    val reducedHeadRepick = reducedHeadTargetsRow && repickMaskHit
    val reducedHeadRowValid = reducedHeadTargetsRow && (!rowProofEnable || rowValidMaskHit)
    val reducedHeadScbReturned = reducedHeadRepick && (!rowProofEnable || rowScbReturnedMaskHit)
    val reducedHeadApplyEligible = reducedHeadRepick && reducedHeadRowValid && reducedHeadScbReturned
    val reducedHeadStale = reducedHeadTargetsRow && !repickMaskHit
    val externalHeadStaleUsed = active && headValid && externalHeadStale

    Result(
      active = active,
      headStale = externalHeadStaleUsed || reducedHeadStale,
      externalHeadStaleUsed = externalHeadStaleUsed,
      reducedHeadTargetsRow = reducedHeadTargetsRow,
      reducedHeadRepick = reducedHeadRepick,
      reducedHeadRowValid = reducedHeadRowValid,
      reducedHeadScbReturned = reducedHeadScbReturned,
      reducedHeadApplyEligible = reducedHeadApplyEligible,
      reducedHeadStale = reducedHeadStale,
      reducedHeadOneHot = if (reducedHeadTargetsRow) rawOneHot else 0,
      blockedByNoHead = active && !headValid,
      blockedByReducedDisabled = active && headValid && !reducedEnable && !externalHeadStale,
      blockedByUnsupportedCluster = reducedCandidate && !clusterIsReduced,
      blockedByEntryOutOfRange = reducedCandidate && clusterIsReduced && !entryInRange,
      blockedByInvalidRow = reducedHeadTargetsRow && rowProofEnable && !rowValidMaskHit,
      blockedByScbNotReturned = reducedHeadRepick && rowProofEnable && !rowScbReturnedMaskHit,
      blockedByStillRepick = reducedHeadRepick,
      blockedByDisabled = !enable && headValid,
      blockedByFlush = enable && flush && headValid)
  }
}

class LoadReplaySourceReturnStoreSnapshotResponseHeadStateSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference._

  test("reduced response head is not stale while the target row remains repick") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      reducedEnable = true,
      headValid = true,
      responseClusterId = 0,
      responseEntryId = 2,
      repickMask = 0x4,
      rowProofEnable = true,
      rowValidMask = 0x4,
      rowScbReturnedMask = 0x4)

    assert(result.reducedHeadTargetsRow)
    assert(result.reducedHeadRepick)
    assert(result.reducedHeadRowValid)
    assert(result.reducedHeadScbReturned)
    assert(result.reducedHeadApplyEligible)
    assert(!result.headStale)
    assert(result.reducedHeadOneHot == 0x4)
    assert(result.blockedByStillRepick)
  }

  test("reduced response head is stale when the target row is no longer repick") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      reducedEnable = true,
      headValid = true,
      responseClusterId = 0,
      responseEntryId = 1,
      repickMask = 0x4,
      rowProofEnable = true,
      rowValidMask = 0x2,
      rowScbReturnedMask = 0)

    assert(result.reducedHeadTargetsRow)
    assert(result.reducedHeadRowValid)
    assert(!result.reducedHeadRepick)
    assert(!result.reducedHeadApplyEligible)
    assert(result.reducedHeadStale)
    assert(result.headStale)
    assert(result.reducedHeadOneHot == 0x2)
  }

  test("reduced row proof blocks apply while SCB has not returned") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      reducedEnable = true,
      headValid = true,
      responseClusterId = 0,
      responseEntryId = 2,
      repickMask = 0x4,
      rowProofEnable = true,
      rowValidMask = 0x4,
      rowScbReturnedMask = 0)

    assert(result.reducedHeadTargetsRow)
    assert(result.reducedHeadRepick)
    assert(result.reducedHeadRowValid)
    assert(!result.reducedHeadScbReturned)
    assert(!result.reducedHeadApplyEligible)
    assert(!result.headStale)
    assert(result.blockedByScbNotReturned)
  }

  test("reduced row proof reports invalid row without changing stale drain semantics") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      reducedEnable = true,
      headValid = true,
      responseClusterId = 0,
      responseEntryId = 3,
      repickMask = 0,
      rowProofEnable = true,
      rowValidMask = 0,
      rowScbReturnedMask = 0)

    assert(result.reducedHeadTargetsRow)
    assert(!result.reducedHeadRowValid)
    assert(!result.reducedHeadRepick)
    assert(result.reducedHeadStale)
    assert(result.headStale)
    assert(result.blockedByInvalidRow)
  }

  test("unsupported cluster and out-of-range entry do not prove stale") {
    val wrongCluster = LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      reducedEnable = true,
      headValid = true,
      responseClusterId = 1,
      responseEntryId = 1,
      repickMask = 0)
    val outOfRange = LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      reducedEnable = true,
      headValid = true,
      responseClusterId = 0,
      responseEntryId = 7,
      repickMask = 0)

    assert(!wrongCluster.headStale)
    assert(wrongCluster.blockedByUnsupportedCluster)
    assert(!outOfRange.headStale)
    assert(outOfRange.blockedByEntryOutOfRange)
  }

  test("external stale proof can drive the shared head-stale output") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      reducedEnable = false,
      headValid = true,
      responseClusterId = 1,
      responseEntryId = 7,
      repickMask = 0,
      externalHeadStale = true)

    assert(result.externalHeadStaleUsed)
    assert(result.headStale)
    assert(!result.blockedByReducedDisabled)
  }

  test("disabled, flushed, no-head, and reduced-disabled states do not prove stale") {
    val disabled = LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference(
      liqEntries = 4,
      enable = false,
      flush = false,
      reducedEnable = true,
      headValid = true,
      responseClusterId = 0,
      responseEntryId = 0,
      repickMask = 0)
    val flushed = LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference(
      liqEntries = 4,
      enable = true,
      flush = true,
      reducedEnable = true,
      headValid = true,
      responseClusterId = 0,
      responseEntryId = 0,
      repickMask = 0)
    val noHead = LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      reducedEnable = true,
      headValid = false,
      responseClusterId = 0,
      responseEntryId = 0,
      repickMask = 0)
    val reducedDisabled = LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      reducedEnable = false,
      headValid = true,
      responseClusterId = 0,
      responseEntryId = 0,
      repickMask = 0)

    assert(!disabled.headStale)
    assert(disabled.blockedByDisabled)
    assert(!flushed.headStale)
    assert(flushed.blockedByFlush)
    assert(!noHead.headStale)
    assert(noHead.blockedByNoHead)
    assert(!reducedDisabled.headStale)
    assert(reducedDisabled.blockedByReducedDisabled)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotResponseHeadState elaborates reduced stale proof") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplaySourceReturnStoreSnapshotResponseHeadState(
        liqEntries = 4,
        clusterIdWidth = 2,
        entryIdWidth = 4
      ))

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotResponseHeadState"))
    assert(sv.contains("io_headStale"))
    assert(sv.contains("io_reducedHeadStale"))
    assert(sv.contains("io_reducedHeadApplyEligible"))
    assert(sv.contains("io_blockedByScbNotReturned"))
    assert(sv.contains("io_blockedByEntryOutOfRange"))
  }
}
