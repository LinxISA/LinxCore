package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotRowStatePlanReference {
  final case class Result(
      active: Boolean,
      planValid: Boolean,
      rewaitApply: Boolean,
      dataMergePlan: Boolean,
      dataNoMergePlan: Boolean,
      setWaitStatus: Boolean,
      keepRepickStatus: Boolean,
      clearReturnState: Boolean,
      lineWrite: Boolean,
      waitStoreWrite: Boolean,
      nextWaitStore: Boolean,
      nextLineData: BigInt,
      nextValidMask: BigInt,
      nextDataComplete: Boolean,
      nextScbReturned: Boolean,
      nextStqReturned: Boolean,
      nextStoreSourceReturned: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoApply: Boolean,
      invalidApplyWithoutStqReturned: Boolean,
      invalidStqReturnedWithoutApply: Boolean,
      invalidStqApplyWithoutScb: Boolean,
      invalidWaitStoreWithMerge: Boolean,
      invalidMergeAndNoData: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      applyValid: Boolean,
      applyStqReturned: Boolean,
      waitStoreApply: Boolean = false,
      dataMergeApply: Boolean = false,
      dataNoMerge: Boolean = false,
      priorScbReturned: Boolean = false,
      priorStqReturned: Boolean = false,
      priorLineData: BigInt = 0,
      priorValidMask: BigInt = 0,
      priorRequestComplete: Boolean = false,
      mergedLineData: BigInt = 0,
      mergedValidMask: BigInt = 0,
      mergedRequestComplete: Boolean = false): Result = {
    val active = enable && !flush
    val rawIntent = applyValid || applyStqReturned || waitStoreApply || dataMergeApply || dataNoMerge
    val planCandidate = active && applyValid
    val planValid = planCandidate && applyStqReturned
    val dataNoMergePlan = planValid && !waitStoreApply && dataNoMerge
    val dataNoMergeRewait = dataNoMergePlan && !priorRequestComplete
    val rewaitApply = planValid && (waitStoreApply || dataNoMergeRewait)
    val dataMergePlan = planValid && !waitStoreApply && dataMergeApply
    val nextScbReturned = if (rewaitApply) false else priorScbReturned
    val nextStqReturned = if (rewaitApply) false else if (planValid) true else priorStqReturned
    val nextLineData = if (rewaitApply) BigInt(0) else if (dataMergePlan) mergedLineData else priorLineData
    val nextValidMask = if (rewaitApply) BigInt(0) else if (dataMergePlan) mergedValidMask else priorValidMask
    val nextDataComplete =
      planValid && !rewaitApply && (if (dataMergePlan) mergedRequestComplete else priorRequestComplete)

    Result(
      active = active,
      planValid = planValid,
      rewaitApply = rewaitApply,
      dataMergePlan = dataMergePlan,
      dataNoMergePlan = dataNoMergePlan,
      setWaitStatus = rewaitApply,
      keepRepickStatus = planValid && !rewaitApply,
      clearReturnState = rewaitApply,
      lineWrite = rewaitApply || dataMergePlan,
      waitStoreWrite = planValid,
      nextWaitStore = planValid && waitStoreApply,
      nextLineData = nextLineData,
      nextValidMask = nextValidMask,
      nextDataComplete = nextDataComplete,
      nextScbReturned = nextScbReturned,
      nextStqReturned = nextStqReturned,
      nextStoreSourceReturned = planValid && !rewaitApply && nextScbReturned && nextStqReturned,
      blockedByDisabled = !enable && rawIntent,
      blockedByFlush = enable && flush && rawIntent,
      blockedByNoApply = active && rawIntent && !applyValid,
      invalidApplyWithoutStqReturned = planCandidate && !applyStqReturned,
      invalidStqReturnedWithoutApply = active && applyStqReturned && !applyValid,
      invalidStqApplyWithoutScb = planValid && !priorScbReturned,
      invalidWaitStoreWithMerge = planValid && waitStoreApply && dataMergeApply,
      invalidMergeAndNoData = planValid && dataMergeApply && dataNoMerge)
  }
}

class LoadReplaySourceReturnStoreSnapshotRowStatePlanSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotRowStatePlanReference._

  test("wait-store response rewrites the row to wait and clears return/data state") {
    val result = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = true,
      flush = false,
      applyValid = true,
      applyStqReturned = true,
      waitStoreApply = true,
      priorScbReturned = true,
      priorLineData = BigInt("11223344", 16),
      priorValidMask = BigInt("ff", 16),
      priorRequestComplete = true)

    assert(result.planValid)
    assert(result.rewaitApply)
    assert(result.setWaitStatus)
    assert(result.clearReturnState)
    assert(result.lineWrite)
    assert(result.waitStoreWrite)
    assert(result.nextWaitStore)
    assert(result.nextLineData == 0)
    assert(result.nextValidMask == 0)
    assert(!result.nextDataComplete)
    assert(!result.nextScbReturned)
    assert(!result.nextStqReturned)
    assert(!result.nextStoreSourceReturned)
  }

  test("data response keeps repick state, sets STQ returned, and uses merged row image") {
    val result = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = true,
      flush = false,
      applyValid = true,
      applyStqReturned = true,
      dataMergeApply = true,
      priorScbReturned = true,
      priorLineData = BigInt("0102030405060708", 16),
      priorValidMask = BigInt("0f", 16),
      mergedLineData = BigInt("ddccbbaa05060708", 16),
      mergedValidMask = BigInt("ff", 16),
      mergedRequestComplete = true)

    assert(result.planValid)
    assert(!result.rewaitApply)
    assert(result.dataMergePlan)
    assert(result.keepRepickStatus)
    assert(result.lineWrite)
    assert(!result.nextWaitStore)
    assert(result.nextLineData == BigInt("ddccbbaa05060708", 16))
    assert(result.nextValidMask == BigInt("ff", 16))
    assert(result.nextDataComplete)
    assert(result.nextScbReturned)
    assert(result.nextStqReturned)
    assert(result.nextStoreSourceReturned)
  }

  test("complete no-data response only marks the STQ side returned") {
    val result = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = true,
      flush = false,
      applyValid = true,
      applyStqReturned = true,
      dataNoMerge = true,
      priorScbReturned = true,
      priorLineData = BigInt("99887766", 16),
      priorValidMask = BigInt("0f", 16),
      priorRequestComplete = true)

    assert(result.planValid)
    assert(result.dataNoMergePlan)
    assert(result.keepRepickStatus)
    assert(!result.lineWrite)
    assert(result.nextLineData == BigInt("99887766", 16))
    assert(result.nextValidMask == BigInt("0f", 16))
    assert(result.nextDataComplete)
    assert(result.nextScbReturned)
    assert(result.nextStqReturned)
    assert(result.nextStoreSourceReturned)
  }

  test("incomplete no-data response rewrites to WAIT and clears return state") {
    val result = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = true,
      flush = false,
      applyValid = true,
      applyStqReturned = true,
      dataNoMerge = true,
      priorScbReturned = true,
      priorLineData = BigInt("99887766", 16),
      priorValidMask = BigInt("0f", 16),
      priorRequestComplete = false)

    assert(result.planValid)
    assert(result.dataNoMergePlan)
    assert(result.rewaitApply)
    assert(result.setWaitStatus)
    assert(!result.keepRepickStatus)
    assert(result.clearReturnState)
    assert(result.lineWrite)
    assert(result.waitStoreWrite)
    assert(!result.nextWaitStore)
    assert(result.nextLineData == 0)
    assert(result.nextValidMask == 0)
    assert(!result.nextDataComplete)
    assert(!result.nextScbReturned)
    assert(!result.nextStqReturned)
    assert(!result.nextStoreSourceReturned)
  }

  test("disabled, flushed, and unaccepted intents do not create a row plan") {
    val disabled = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = false,
      flush = false,
      applyValid = true,
      applyStqReturned = true)
    val flushed = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = true,
      flush = true,
      applyValid = true,
      applyStqReturned = true)
    val noApply = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = true,
      flush = false,
      applyValid = false,
      applyStqReturned = false,
      dataNoMerge = true)

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(noApply.blockedByNoApply)
    assert(!disabled.planValid)
    assert(!flushed.planValid)
    assert(!noApply.planValid)
  }

  test("invalid diagnostics catch missing STQ event, SCB order, and overlapping response classes") {
    val noStqEvent = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = true,
      flush = false,
      applyValid = true,
      applyStqReturned = false)
    val noApply = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = true,
      flush = false,
      applyValid = false,
      applyStqReturned = true)
    val noScb = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = true,
      flush = false,
      applyValid = true,
      applyStqReturned = true,
      dataNoMerge = true,
      priorScbReturned = false)
    val waitAndMerge = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = true,
      flush = false,
      applyValid = true,
      applyStqReturned = true,
      waitStoreApply = true,
      dataMergeApply = true,
      priorScbReturned = true)
    val mergeAndNoData = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = true,
      flush = false,
      applyValid = true,
      applyStqReturned = true,
      dataMergeApply = true,
      dataNoMerge = true,
      priorScbReturned = true)

    assert(noStqEvent.invalidApplyWithoutStqReturned)
    assert(noApply.invalidStqReturnedWithoutApply)
    assert(noScb.invalidStqApplyWithoutScb)
    assert(waitAndMerge.invalidWaitStoreWithMerge)
    assert(mergeAndNoData.invalidMergeAndNoData)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotRowStatePlan elaborates row-state diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotRowStatePlan)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotRowStatePlan"))
    assert(sv.contains("io_nextScbReturned"))
    assert(sv.contains("io_nextStqReturned"))
    assert(sv.contains("io_nextStoreSourceReturned"))
    assert(sv.contains("io_invalidStqApplyWithoutScb"))
    assert(sv.contains("io_nextWaitStoreRid_value"))
  }
}
