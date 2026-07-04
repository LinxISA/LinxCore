package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotSelectedIdentityReference {
  final case class Result(
      active: Boolean,
      selectedValid: Boolean,
      selectedRepick: Boolean,
      selectedClusterId: Int,
      selectedEntryId: Int,
      selectedIndexOneHot: Int,
      repickMaskHit: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoLaunch: Boolean,
      blockedByNotRepick: Boolean)

  def apply(
      liqEntries: Int,
      enable: Boolean,
      flush: Boolean,
      launchValid: Boolean,
      launchIndex: Int,
      repickMask: Int): Result = {
    val active = enable && !flush
    val rawOneHot = 1 << launchIndex
    val selectedValid = active && launchValid
    val rawRepickHit = (repickMask & rawOneHot) != 0
    val selectedRepick = selectedValid && rawRepickHit

    Result(
      active = active,
      selectedValid = selectedValid,
      selectedRepick = selectedRepick,
      selectedClusterId = 0,
      selectedEntryId = launchIndex,
      selectedIndexOneHot = if (selectedValid) rawOneHot else 0,
      repickMaskHit = selectedValid && rawRepickHit,
      blockedByDisabled = !enable && launchValid,
      blockedByFlush = enable && flush && launchValid,
      blockedByNoLaunch = active && !launchValid,
      blockedByNotRepick = selectedValid && !rawRepickHit)
  }
}

class LoadReplaySourceReturnStoreSnapshotSelectedIdentitySpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotSelectedIdentityReference._

  test("disabled and flushed selected rows expose blockers and no identity") {
    val disabled = LoadReplaySourceReturnStoreSnapshotSelectedIdentityReference(
      liqEntries = 4,
      enable = false,
      flush = false,
      launchValid = true,
      launchIndex = 2,
      repickMask = 0x4)
    val flushed = LoadReplaySourceReturnStoreSnapshotSelectedIdentityReference(
      liqEntries = 4,
      enable = true,
      flush = true,
      launchValid = true,
      launchIndex = 2,
      repickMask = 0x4)

    assert(disabled.blockedByDisabled)
    assert(!disabled.selectedValid)
    assert(flushed.blockedByFlush)
    assert(!flushed.selectedRepick)
  }

  test("launch index projects to single reduced cluster and LIQ entry id") {
    val result = LoadReplaySourceReturnStoreSnapshotSelectedIdentityReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      launchValid = true,
      launchIndex = 3,
      repickMask = 0x8)

    assert(result.selectedValid)
    assert(result.selectedRepick)
    assert(result.selectedClusterId == 0)
    assert(result.selectedEntryId == 3)
    assert(result.selectedIndexOneHot == 0x8)
    assert(result.repickMaskHit)
  }

  test("pre-repick launch row stays valid but not response-ready") {
    val result = LoadReplaySourceReturnStoreSnapshotSelectedIdentityReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      launchValid = true,
      launchIndex = 1,
      repickMask = 0x4)

    assert(result.selectedValid)
    assert(!result.selectedRepick)
    assert(!result.repickMaskHit)
    assert(result.blockedByNotRepick)
  }

  test("active projection without a launch reports no selected row") {
    val result = LoadReplaySourceReturnStoreSnapshotSelectedIdentityReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      launchValid = false,
      launchIndex = 1,
      repickMask = 0x2)

    assert(result.active)
    assert(!result.selectedValid)
    assert(result.blockedByNoLaunch)
    assert(result.selectedIndexOneHot == 0)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotSelectedIdentity elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplaySourceReturnStoreSnapshotSelectedIdentity(
        liqEntries = 4,
        clusterIdWidth = 2,
        entryIdWidth = 4
      ))

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotSelectedIdentity"))
    assert(sv.contains("io_selectedRepick"))
    assert(sv.contains("io_selectedIndexOneHot"))
    assert(sv.contains("io_blockedByNotRepick"))
  }
}
