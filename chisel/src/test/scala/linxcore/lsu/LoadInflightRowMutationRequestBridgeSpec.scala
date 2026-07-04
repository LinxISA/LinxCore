package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadInflightRowMutationRequestBridgeReference {
  final case class Request(
      targetMask: Int = 0,
      targetIndex: Int = 0,
      setWaitStatus: Boolean = false,
      keepRepickStatus: Boolean = false,
      clearReturnState: Boolean = false,
      lineWrite: Boolean = false,
      waitStoreWrite: Boolean = false,
      nextWaitStore: Boolean = false,
      sourceStoreIndex: Int = 0,
      nextLineData: BigInt = 0,
      nextValidMask: BigInt = 0,
      nextDataComplete: Boolean = false,
      nextScbReturned: Boolean = false,
      nextStqReturned: Boolean = false,
      nextStoreSourceReturned: Boolean = false)

  final case class Result(
      active: Boolean,
      bridgeValid: Boolean,
      targetMask: Int,
      targetIndex: Int,
      setWaitStatus: Boolean,
      keepRepickStatus: Boolean,
      clearReturnState: Boolean,
      lineWrite: Boolean,
      waitStoreWrite: Boolean,
      nextWaitStore: Boolean,
      nativeStoreIndex: Int,
      nextLineData: BigInt,
      nextValidMask: BigInt,
      nextDataComplete: Boolean,
      nextScbReturned: Boolean,
      nextStqReturned: Boolean,
      nextStoreSourceReturned: Boolean,
      sourceStoreIndexFits: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoRequest: Boolean,
      invalidStoreIndexOutOfRange: Boolean,
      invalidConflictingStatusWrite: Boolean,
      invalidWaitStoreWithoutWaitStatus: Boolean,
      invalidReturnWithoutSplitSources: Boolean)

  def apply(
      sourceStoreEntries: Int,
      storeEntries: Int,
      enable: Boolean,
      flush: Boolean,
      requestValid: Boolean,
      request: Request): Result = {
    val active = enable && !flush
    val requestActive = active && requestValid
    val sourceStoreIndexFits =
      sourceStoreEntries <= storeEntries || request.sourceStoreIndex < storeEntries
    val invalidStoreIndexOutOfRange = request.nextWaitStore && !sourceStoreIndexFits
    val invalidConflictingStatusWrite = request.setWaitStatus && request.keepRepickStatus
    val invalidWaitStoreWithoutWaitStatus = request.nextWaitStore && !request.setWaitStatus
    val invalidReturnWithoutSplitSources =
      request.nextStoreSourceReturned && !(request.nextScbReturned && request.nextStqReturned)
    val payloadShapeValid =
      !invalidStoreIndexOutOfRange &&
        !invalidConflictingStatusWrite &&
        !invalidWaitStoreWithoutWaitStatus &&
        !invalidReturnWithoutSplitSources
    val bridgeValid = requestActive && payloadShapeValid

    Result(
      active = active,
      bridgeValid = bridgeValid,
      targetMask = if (bridgeValid) request.targetMask else 0,
      targetIndex = if (bridgeValid) request.targetIndex else 0,
      setWaitStatus = bridgeValid && request.setWaitStatus,
      keepRepickStatus = bridgeValid && request.keepRepickStatus,
      clearReturnState = bridgeValid && request.clearReturnState,
      lineWrite = bridgeValid && request.lineWrite,
      waitStoreWrite = bridgeValid && request.waitStoreWrite,
      nextWaitStore = bridgeValid && request.nextWaitStore,
      nativeStoreIndex = if (bridgeValid && request.nextWaitStore) request.sourceStoreIndex else 0,
      nextLineData = if (bridgeValid) request.nextLineData else 0,
      nextValidMask = if (bridgeValid) request.nextValidMask else 0,
      nextDataComplete = bridgeValid && request.nextDataComplete,
      nextScbReturned = bridgeValid && request.nextScbReturned,
      nextStqReturned = bridgeValid && request.nextStqReturned,
      nextStoreSourceReturned = bridgeValid && request.nextStoreSourceReturned,
      sourceStoreIndexFits = sourceStoreIndexFits,
      blockedByDisabled = !enable && requestValid,
      blockedByFlush = enable && flush && requestValid,
      blockedByNoRequest = active && !requestValid,
      invalidStoreIndexOutOfRange = requestActive && invalidStoreIndexOutOfRange,
      invalidConflictingStatusWrite = requestActive && invalidConflictingStatusWrite,
      invalidWaitStoreWithoutWaitStatus = requestActive && invalidWaitStoreWithoutWaitStatus,
      invalidReturnWithoutSplitSources = requestActive && invalidReturnWithoutSplitSources)
  }
}

class LoadInflightRowMutationRequestBridgeSpec extends AnyFunSuite {
  import LoadInflightRowMutationRequestBridgeReference._

  test("passes an in-range wait-store request into the LIQ-native shape") {
    val result = LoadInflightRowMutationRequestBridgeReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = false,
      requestValid = true,
      request = Request(
        targetMask = 0x4,
        targetIndex = 2,
        setWaitStatus = true,
        clearReturnState = true,
        lineWrite = true,
        waitStoreWrite = true,
        nextWaitStore = true,
        sourceStoreIndex = 3,
        nextLineData = BigInt("aabbccdd", 16),
        nextValidMask = BigInt("ff", 16)))

    assert(result.bridgeValid)
    assert(result.targetMask == 0x4)
    assert(result.targetIndex == 2)
    assert(result.setWaitStatus)
    assert(result.clearReturnState)
    assert(result.lineWrite)
    assert(result.waitStoreWrite)
    assert(result.nextWaitStore)
    assert(result.nativeStoreIndex == 3)
    assert(result.nextLineData == BigInt("aabbccdd", 16))
    assert(result.nextValidMask == BigInt("ff", 16))
    assert(result.sourceStoreIndexFits)
  }

  test("blocks wait-store indexes outside the native LIQ store-entry range") {
    val result = LoadInflightRowMutationRequestBridgeReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = false,
      requestValid = true,
      request = Request(
        targetMask = 0x2,
        targetIndex = 1,
        setWaitStatus = true,
        waitStoreWrite = true,
        nextWaitStore = true,
        sourceStoreIndex = 5,
        nextLineData = BigInt("1122", 16),
        nextValidMask = BigInt("3", 16)))

    assert(!result.bridgeValid)
    assert(!result.sourceStoreIndexFits)
    assert(result.invalidStoreIndexOutOfRange)
    assert(result.targetMask == 0)
    assert(result.nativeStoreIndex == 0)
    assert(result.nextLineData == 0)
    assert(result.nextValidMask == 0)
  }

  test("ignores source-store index range when no next wait-store is carried") {
    val result = LoadInflightRowMutationRequestBridgeReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = false,
      requestValid = true,
      request = Request(
        targetMask = 0x1,
        targetIndex = 0,
        keepRepickStatus = true,
        lineWrite = true,
        nextWaitStore = false,
        sourceStoreIndex = 6,
        nextLineData = BigInt("55aa", 16),
        nextValidMask = BigInt("f0", 16),
        nextDataComplete = true,
        nextScbReturned = true,
        nextStqReturned = true,
        nextStoreSourceReturned = true))

    assert(result.bridgeValid)
    assert(!result.sourceStoreIndexFits)
    assert(!result.invalidStoreIndexOutOfRange)
    assert(result.targetMask == 0x1)
    assert(result.nativeStoreIndex == 0)
    assert(result.nextDataComplete)
    assert(result.nextStoreSourceReturned)
  }

  test("reports disabled, flush, no-request, and malformed-payload blockers") {
    val disabled = LoadInflightRowMutationRequestBridgeReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = false,
      flush = false,
      requestValid = true,
      request = Request(setWaitStatus = true))
    val flushed = LoadInflightRowMutationRequestBridgeReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = true,
      requestValid = true,
      request = Request(setWaitStatus = true))
    val noRequest = LoadInflightRowMutationRequestBridgeReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = false,
      requestValid = false,
      request = Request())
    val conflictingStatus = LoadInflightRowMutationRequestBridgeReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = false,
      requestValid = true,
      request = Request(setWaitStatus = true, keepRepickStatus = true))
    val waitWithoutStatus = LoadInflightRowMutationRequestBridgeReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = false,
      requestValid = true,
      request = Request(nextWaitStore = true, sourceStoreIndex = 1))
    val returnedWithoutSplit = LoadInflightRowMutationRequestBridgeReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = false,
      requestValid = true,
      request = Request(
        keepRepickStatus = true,
        nextScbReturned = true,
        nextStoreSourceReturned = true))

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(noRequest.blockedByNoRequest)
    assert(conflictingStatus.invalidConflictingStatusWrite)
    assert(waitWithoutStatus.invalidWaitStoreWithoutWaitStatus)
    assert(returnedWithoutSplit.invalidReturnWithoutSplitSources)
    assert(!conflictingStatus.bridgeValid)
    assert(!waitWithoutStatus.bridgeValid)
    assert(!returnedWithoutSplit.bridgeValid)
  }

  test("Chisel LoadInflightRowMutationRequestBridge elaborates native request outputs") {
    val sv = ChiselStage.emitSystemVerilog(new LoadInflightRowMutationRequestBridge(
      liqEntries = 4,
      idEntries = 8,
      sourceStoreEntries = 8,
      storeEntries = 4
    ))

    assert(sv.contains("module LoadInflightRowMutationRequestBridge"))
    assert(sv.contains("io_bridgeValid"))
    assert(sv.contains("io_nativeNextWaitStoreInfoOut_storeIndex"))
    assert(sv.contains("io_nativeStoreIndexOut"))
    assert(sv.contains("io_sourceStoreIndexFits"))
    assert(sv.contains("io_invalidStoreIndexOutOfRange"))
    assert(sv.contains("io_invalidReturnWithoutSplitSources"))
  }
}
