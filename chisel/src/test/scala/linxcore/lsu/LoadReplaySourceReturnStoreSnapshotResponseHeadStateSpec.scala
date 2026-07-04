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
      reducedHeadStale: Boolean,
      reducedHeadOneHot: Int,
      blockedByNoHead: Boolean,
      blockedByReducedDisabled: Boolean,
      blockedByUnsupportedCluster: Boolean,
      blockedByEntryOutOfRange: Boolean,
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
      externalHeadStale: Boolean = false): Result = {
    val active = enable && !flush
    val reducedCandidate = active && reducedEnable && headValid
    val clusterIsReduced = responseClusterId == 0
    val entryInRange = responseEntryId < liqEntries
    val rawOneHot = 1 << (responseEntryId & (liqEntries - 1))
    val reducedHeadTargetsRow = reducedCandidate && clusterIsReduced && entryInRange
    val repickMaskHit = (repickMask & rawOneHot) != 0
    val reducedHeadRepick = reducedHeadTargetsRow && repickMaskHit
    val reducedHeadStale = reducedHeadTargetsRow && !repickMaskHit
    val externalHeadStaleUsed = active && headValid && externalHeadStale

    Result(
      active = active,
      headStale = externalHeadStaleUsed || reducedHeadStale,
      externalHeadStaleUsed = externalHeadStaleUsed,
      reducedHeadTargetsRow = reducedHeadTargetsRow,
      reducedHeadRepick = reducedHeadRepick,
      reducedHeadStale = reducedHeadStale,
      reducedHeadOneHot = if (reducedHeadTargetsRow) rawOneHot else 0,
      blockedByNoHead = active && !headValid,
      blockedByReducedDisabled = active && headValid && !reducedEnable && !externalHeadStale,
      blockedByUnsupportedCluster = reducedCandidate && !clusterIsReduced,
      blockedByEntryOutOfRange = reducedCandidate && clusterIsReduced && !entryInRange,
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
      repickMask = 0x4)

    assert(result.reducedHeadTargetsRow)
    assert(result.reducedHeadRepick)
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
      repickMask = 0x4)

    assert(result.reducedHeadTargetsRow)
    assert(!result.reducedHeadRepick)
    assert(result.reducedHeadStale)
    assert(result.headStale)
    assert(result.reducedHeadOneHot == 0x2)
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
    assert(sv.contains("io_blockedByEntryOutOfRange"))
  }
}
