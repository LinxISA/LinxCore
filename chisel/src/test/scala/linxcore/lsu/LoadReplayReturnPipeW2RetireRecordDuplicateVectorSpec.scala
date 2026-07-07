package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordDuplicateVectorReference {
  final case class Result(
      active: Boolean,
      recordCandidate: Boolean,
      duplicateVectorValid: Boolean,
      returnSideEffectDuplicateBundle: Boolean,
      modelOrderDuplicateBundle: Boolean,
      partialDuplicateVector: Boolean,
      singleDuplicate: Boolean,
      multiDuplicate: Boolean,
      duplicateCount: Int,
      nextOwnerRequiresBundleTransfer: Boolean,
      blockedByNoRecord: Boolean,
      blockedByPreArmNotReady: Boolean,
      blockedByNoDuplicate: Boolean)

  def step(
      enable: Boolean,
      flush: Boolean,
      recordValid: Boolean,
      preArmModelOrderReady: Boolean,
      robDuplicatePhysicalComplete: Boolean,
      rfDuplicatePhysicalWriteback: Boolean,
      wakeupDuplicatePhysicalWakeup: Boolean,
      lifecycleClearDuplicatePhysicalClear: Boolean): Result = {
    val active = enable && !flush
    val recordCandidate = active && recordValid
    val count = Seq(
      robDuplicatePhysicalComplete,
      rfDuplicatePhysicalWriteback,
      wakeupDuplicatePhysicalWakeup,
      lifecycleClearDuplicatePhysicalClear).count(identity)
    val vectorValid = recordCandidate && preArmModelOrderReady && count != 0
    val returnBundle =
      vectorValid &&
        robDuplicatePhysicalComplete &&
        rfDuplicatePhysicalWriteback &&
        wakeupDuplicatePhysicalWakeup
    val modelBundle = returnBundle && lifecycleClearDuplicatePhysicalClear

    Result(
      active = active,
      recordCandidate = recordCandidate,
      duplicateVectorValid = vectorValid,
      returnSideEffectDuplicateBundle = returnBundle,
      modelOrderDuplicateBundle = modelBundle,
      partialDuplicateVector = vectorValid && !modelBundle,
      singleDuplicate = vectorValid && count == 1,
      multiDuplicate = vectorValid && count > 1,
      duplicateCount = if (vectorValid) count else 0,
      nextOwnerRequiresBundleTransfer = modelBundle,
      blockedByNoRecord = active && !recordValid,
      blockedByPreArmNotReady = recordCandidate && !preArmModelOrderReady,
      blockedByNoDuplicate = recordCandidate && preArmModelOrderReady && count == 0)
  }
}

class LoadReplayReturnPipeW2RetireRecordDuplicateVectorSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordDuplicateVectorReference._

  test("classifies all four physical duplicates as a model-order duplicate bundle") {
    val result = step(
      enable = true,
      flush = false,
      recordValid = true,
      preArmModelOrderReady = true,
      robDuplicatePhysicalComplete = true,
      rfDuplicatePhysicalWriteback = true,
      wakeupDuplicatePhysicalWakeup = true,
      lifecycleClearDuplicatePhysicalClear = true)

    assert(result.duplicateVectorValid)
    assert(result.returnSideEffectDuplicateBundle)
    assert(result.modelOrderDuplicateBundle)
    assert(result.nextOwnerRequiresBundleTransfer)
    assert(result.multiDuplicate)
    assert(result.duplicateCount == 4)
    assert(!result.partialDuplicateVector)
  }

  test("distinguishes partial duplicate vectors from full model-order bundles") {
    val result = step(
      enable = true,
      flush = false,
      recordValid = true,
      preArmModelOrderReady = true,
      robDuplicatePhysicalComplete = true,
      rfDuplicatePhysicalWriteback = false,
      wakeupDuplicatePhysicalWakeup = true,
      lifecycleClearDuplicatePhysicalClear = true)

    assert(result.duplicateVectorValid)
    assert(result.partialDuplicateVector)
    assert(!result.returnSideEffectDuplicateBundle)
    assert(!result.modelOrderDuplicateBundle)
    assert(result.multiDuplicate)
    assert(result.duplicateCount == 3)
  }

  test("classifies single duplicate as a narrow owner target") {
    val result = step(
      enable = true,
      flush = false,
      recordValid = true,
      preArmModelOrderReady = true,
      robDuplicatePhysicalComplete = false,
      rfDuplicatePhysicalWriteback = true,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = false)

    assert(result.duplicateVectorValid)
    assert(result.singleDuplicate)
    assert(!result.multiDuplicate)
    assert(result.duplicateCount == 1)
  }

  test("reports missing record, missing pre-arm evidence, and no duplicate separately") {
    val noRecord = step(
      enable = true,
      flush = false,
      recordValid = false,
      preArmModelOrderReady = true,
      robDuplicatePhysicalComplete = true,
      rfDuplicatePhysicalWriteback = true,
      wakeupDuplicatePhysicalWakeup = true,
      lifecycleClearDuplicatePhysicalClear = true)
    val noPreArm = step(
      enable = true,
      flush = false,
      recordValid = true,
      preArmModelOrderReady = false,
      robDuplicatePhysicalComplete = true,
      rfDuplicatePhysicalWriteback = true,
      wakeupDuplicatePhysicalWakeup = true,
      lifecycleClearDuplicatePhysicalClear = true)
    val noDuplicate = step(
      enable = true,
      flush = false,
      recordValid = true,
      preArmModelOrderReady = true,
      robDuplicatePhysicalComplete = false,
      rfDuplicatePhysicalWriteback = false,
      wakeupDuplicatePhysicalWakeup = false,
      lifecycleClearDuplicatePhysicalClear = false)

    assert(noRecord.blockedByNoRecord)
    assert(noPreArm.blockedByPreArmNotReady)
    assert(noDuplicate.blockedByNoDuplicate)
  }

  test("flush suppresses duplicate-vector classification") {
    val result = step(
      enable = true,
      flush = true,
      recordValid = true,
      preArmModelOrderReady = true,
      robDuplicatePhysicalComplete = true,
      rfDuplicatePhysicalWriteback = true,
      wakeupDuplicatePhysicalWakeup = true,
      lifecycleClearDuplicatePhysicalClear = true)

    assert(!result.active)
    assert(!result.recordCandidate)
    assert(!result.duplicateVectorValid)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordDuplicateVector elaborates") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RetireRecordDuplicateVector)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordDuplicateVector"))
    assert(sv.contains("io_modelOrderDuplicateBundle"))
    assert(sv.contains("io_nextOwnerRequiresBundleTransfer"))
  }
}
