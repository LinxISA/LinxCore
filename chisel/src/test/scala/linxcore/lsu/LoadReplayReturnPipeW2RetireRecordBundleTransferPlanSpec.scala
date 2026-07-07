package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordBundleTransferPlanReference {
  final case class Result(
      active: Boolean,
      recordCandidate: Boolean,
      preArmReady: Boolean,
      modelOrderBundle: Boolean,
      defaultTransferCandidate: Boolean,
      requiresPhysicalBundleSuppression: Boolean,
      defaultPromotionAlreadyReady: Boolean,
      blockedByNoRecord: Boolean,
      blockedByPreArmNotReady: Boolean,
      blockedByNoDuplicateVector: Boolean,
      blockedByPartialDuplicate: Boolean,
      blockedByProbeActive: Boolean)

  def step(
      enable: Boolean,
      flush: Boolean,
      recordValid: Boolean,
      preArmModelOrderReady: Boolean,
      defaultPromotionReady: Boolean,
      duplicateVectorValid: Boolean,
      modelOrderDuplicateBundle: Boolean,
      partialDuplicateVector: Boolean,
      probeActive: Boolean): Result = {
    val active = enable && !flush
    val recordCandidate = active && recordValid
    val preArmReady = recordCandidate && preArmModelOrderReady
    val modelBundle = preArmReady && duplicateVectorValid && modelOrderDuplicateBundle
    val alreadyReady = preArmReady && defaultPromotionReady
    val transferCandidate = modelBundle && !alreadyReady && !probeActive

    Result(
      active = active,
      recordCandidate = recordCandidate,
      preArmReady = preArmReady,
      modelOrderBundle = modelBundle,
      defaultTransferCandidate = transferCandidate,
      requiresPhysicalBundleSuppression = transferCandidate,
      defaultPromotionAlreadyReady = alreadyReady,
      blockedByNoRecord = active && !recordValid,
      blockedByPreArmNotReady = recordCandidate && !preArmModelOrderReady,
      blockedByNoDuplicateVector = preArmReady && !defaultPromotionReady && !duplicateVectorValid,
      blockedByPartialDuplicate = preArmReady && duplicateVectorValid && partialDuplicateVector,
      blockedByProbeActive = modelBundle && !alreadyReady && probeActive)
  }
}

class LoadReplayReturnPipeW2RetireRecordBundleTransferPlanSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordBundleTransferPlanReference._

  test("allows diagnostic transfer only for a full model-order duplicate bundle") {
    val result = step(
      enable = true,
      flush = false,
      recordValid = true,
      preArmModelOrderReady = true,
      defaultPromotionReady = false,
      duplicateVectorValid = true,
      modelOrderDuplicateBundle = true,
      partialDuplicateVector = false,
      probeActive = false)

    assert(result.defaultTransferCandidate)
    assert(result.requiresPhysicalBundleSuppression)
    assert(result.modelOrderBundle)
    assert(!result.defaultPromotionAlreadyReady)
  }

  test("does not request transfer when default promotion is already ready") {
    val result = step(
      enable = true,
      flush = false,
      recordValid = true,
      preArmModelOrderReady = true,
      defaultPromotionReady = true,
      duplicateVectorValid = false,
      modelOrderDuplicateBundle = false,
      partialDuplicateVector = false,
      probeActive = false)

    assert(result.defaultPromotionAlreadyReady)
    assert(!result.defaultTransferCandidate)
    assert(!result.blockedByNoDuplicateVector)
  }

  test("reports a partial duplicate as not eligible for bundle transfer") {
    val result = step(
      enable = true,
      flush = false,
      recordValid = true,
      preArmModelOrderReady = true,
      defaultPromotionReady = false,
      duplicateVectorValid = true,
      modelOrderDuplicateBundle = false,
      partialDuplicateVector = true,
      probeActive = false)

    assert(result.blockedByPartialDuplicate)
    assert(!result.defaultTransferCandidate)
  }

  test("reports missing record, missing pre-arm evidence, and missing duplicate vector") {
    val noRecord = step(
      enable = true,
      flush = false,
      recordValid = false,
      preArmModelOrderReady = true,
      defaultPromotionReady = false,
      duplicateVectorValid = true,
      modelOrderDuplicateBundle = true,
      partialDuplicateVector = false,
      probeActive = false)
    val noPreArm = step(
      enable = true,
      flush = false,
      recordValid = true,
      preArmModelOrderReady = false,
      defaultPromotionReady = false,
      duplicateVectorValid = true,
      modelOrderDuplicateBundle = true,
      partialDuplicateVector = false,
      probeActive = false)
    val noVector = step(
      enable = true,
      flush = false,
      recordValid = true,
      preArmModelOrderReady = true,
      defaultPromotionReady = false,
      duplicateVectorValid = false,
      modelOrderDuplicateBundle = false,
      partialDuplicateVector = false,
      probeActive = false)

    assert(noRecord.blockedByNoRecord)
    assert(noPreArm.blockedByPreArmNotReady)
    assert(noVector.blockedByNoDuplicateVector)
  }

  test("probe-active cycles block default transfer planning") {
    val result = step(
      enable = true,
      flush = false,
      recordValid = true,
      preArmModelOrderReady = true,
      defaultPromotionReady = false,
      duplicateVectorValid = true,
      modelOrderDuplicateBundle = true,
      partialDuplicateVector = false,
      probeActive = true)

    assert(result.blockedByProbeActive)
    assert(!result.defaultTransferCandidate)
  }

  test("flush suppresses transfer planning") {
    val result = step(
      enable = true,
      flush = true,
      recordValid = true,
      preArmModelOrderReady = true,
      defaultPromotionReady = false,
      duplicateVectorValid = true,
      modelOrderDuplicateBundle = true,
      partialDuplicateVector = false,
      probeActive = false)

    assert(!result.active)
    assert(!result.recordCandidate)
    assert(!result.defaultTransferCandidate)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordBundleTransferPlan elaborates") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RetireRecordBundleTransferPlan)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordBundleTransferPlan"))
    assert(sv.contains("io_defaultTransferCandidate"))
    assert(sv.contains("io_requiresPhysicalBundleSuppression"))
  }
}
