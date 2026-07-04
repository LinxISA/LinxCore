package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadInflightRowMutationPathReference {
  import LoadInflightQueueReference.{Repick, Row}
  import LoadInflightRowMutationApplyReference.{Request => ApplyRequest, Result => ApplyResult}
  import LoadInflightRowMutationRequestBridgeReference.{Request => BridgeRequest, Result => BridgeResult}
  import LoadInflightRowMutationWriteControlReference.{Conflicts, Result => ControlResult}
  import LoadStoreForwardingReference.Store

  final case class Result(
      bridge: BridgeResult,
      control: ControlResult,
      applyResult: ApplyResult,
      blockedByBridge: Boolean,
      blockedByControl: Boolean,
      blockedByApply: Boolean)

  def apply(
      sourceStoreEntries: Int,
      storeEntries: Int,
      enable: Boolean,
      flush: Boolean,
      requestValid: Boolean,
      rowValid: Boolean,
      row: Row,
      request: BridgeRequest,
      conflicts: Conflicts = Conflicts()): Result = {
    val bridge = LoadInflightRowMutationRequestBridgeReference(
      sourceStoreEntries = sourceStoreEntries,
      storeEntries = storeEntries,
      enable = enable,
      flush = flush,
      requestValid = requestValid,
      request = request)
    val control = LoadInflightRowMutationWriteControlReference(
      enable = enable,
      flush = flush,
      requestValid = bridge.bridgeValid,
      targetRowValid = rowValid,
      targetRowRepick = row.status == Repick,
      targetScbReturned = row.scbReturned,
      conflicts = conflicts)
    val nativeRequest = ApplyRequest(
      setWaitStatus = bridge.setWaitStatus,
      keepRepickStatus = bridge.keepRepickStatus,
      clearReturnState = bridge.clearReturnState,
      lineWrite = bridge.lineWrite,
      waitStoreWrite = bridge.waitStoreWrite,
      nextWaitStore =
        if (bridge.nextWaitStore) Some(Store(index = bridge.nativeStoreIndex))
        else None,
      nextLineData = bridge.nextLineData,
      nextValidMask = bridge.nextValidMask,
      nextDataComplete = bridge.nextDataComplete,
      nextScbReturned = bridge.nextScbReturned,
      nextStqReturned = bridge.nextStqReturned,
      nextStoreSourceReturned = bridge.nextStoreSourceReturned)
    val apply = LoadInflightRowMutationApplyReference(
      enable = enable,
      flush = flush,
      requestValid = control.writeEnable,
      rowValid = rowValid,
      row = row,
      request = nativeRequest)

    Result(
      bridge = bridge,
      control = control,
      applyResult = apply,
      blockedByBridge = requestValid && !bridge.bridgeValid,
      blockedByControl = bridge.bridgeValid && !control.writeEnable,
      blockedByApply = control.writeEnable && !apply.applyValid)
  }
}

class LoadInflightRowMutationPathSpec extends AnyFunSuite {
  import LoadInflightQueueReference._
  import LoadInflightRowMutationPathReference._
  import LoadInflightRowMutationRequestBridgeReference.{Request => BridgeRequest}
  import LoadInflightRowMutationWriteControlReference.Conflicts
  import LoadStoreForwardingReference.{byteMask, lineData}
  import STQFlushPruneReference.Id

  private val baseRow = Row(
    status = Repick,
    loadId = Id(valid = true, value = 1),
    alloc = Alloc(bid = Id(value = 3), rid = Id(value = 4), loadLsId = Id(value = 5), pc = 0x4000, addr = 0x1008),
    lineData = lineData(Map(8 -> 0x11, 9 -> 0x22)),
    validMask = byteMask(8, 2),
    loadByteMask = byteMask(8, 2),
    dataComplete = true,
    sourcesReturned = true,
    scbReturned = true,
    stqReturned = false)

  test("composes bridge control and apply for a wait-store rewait mutation") {
    val result = LoadInflightRowMutationPathReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = true,
      row = baseRow,
      request = BridgeRequest(
        targetMask = 0x2,
        targetIndex = 1,
        setWaitStatus = true,
        clearReturnState = true,
        lineWrite = true,
        waitStoreWrite = true,
        nextWaitStore = true,
        sourceStoreIndex = 2,
        nextLineData = 0,
        nextValidMask = 0))

    assert(result.bridge.bridgeValid)
    assert(result.control.writeEnable)
    assert(result.applyResult.applyValid)
    assert(!result.blockedByBridge)
    assert(!result.blockedByControl)
    assert(!result.blockedByApply)
    assert(result.applyResult.nextRow.status == Wait)
    assert(result.applyResult.nextRow.waitStore.exists(_.index == 2))
    assert(!result.applyResult.nextRow.sourcesReturned)
    assert(!result.applyResult.nextRow.scbReturned)
    assert(!result.applyResult.nextRow.stqReturned)
    assert(!result.applyResult.nextRow.dataComplete)
    assert(result.applyResult.nextRow.loadId == baseRow.loadId)
    assert(result.applyResult.nextRow.alloc.pc == baseRow.alloc.pc)
  }

  test("composes the data-merge path without leaving repick") {
    val mergedData = lineData(Map(8 -> 0xaa, 9 -> 0xbb, 10 -> 0xcc))
    val result = LoadInflightRowMutationPathReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = true,
      row = baseRow.copy(sourcesReturned = false, stqReturned = false),
      request = BridgeRequest(
        targetMask = 0x2,
        targetIndex = 1,
        keepRepickStatus = true,
        lineWrite = true,
        nextWaitStore = false,
        sourceStoreIndex = 6,
        nextLineData = mergedData,
        nextValidMask = byteMask(8, 3),
        nextDataComplete = true,
        nextScbReturned = true,
        nextStqReturned = true,
        nextStoreSourceReturned = true))

    assert(result.bridge.bridgeValid)
    assert(!result.bridge.sourceStoreIndexFits)
    assert(result.control.writeEnable)
    assert(result.applyResult.applyValid)
    assert(result.applyResult.nextRow.status == Repick)
    assert(result.applyResult.nextRow.lineData == mergedData)
    assert(result.applyResult.nextRow.validMask == byteMask(8, 3))
    assert(result.applyResult.nextRow.dataComplete)
    assert(result.applyResult.nextRow.sourcesReturned)
    assert(result.applyResult.nextRow.scbReturned)
    assert(result.applyResult.nextRow.stqReturned)
  }

  test("blocks an out-of-range wait-store request before control or apply") {
    val result = LoadInflightRowMutationPathReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = true,
      row = baseRow,
      request = BridgeRequest(
        targetMask = 0x2,
        targetIndex = 1,
        setWaitStatus = true,
        waitStoreWrite = true,
        nextWaitStore = true,
        sourceStoreIndex = 6))

    assert(result.blockedByBridge)
    assert(result.bridge.invalidStoreIndexOutOfRange)
    assert(!result.bridge.bridgeValid)
    assert(!result.control.writeEnable)
    assert(!result.applyResult.applyValid)
    assert(result.applyResult.nextRow == baseRow)
  }

  test("blocks target evidence and same-cycle writer conflicts before apply") {
    val noScb = LoadInflightRowMutationPathReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = true,
      row = baseRow.copy(scbReturned = false),
      request = BridgeRequest(targetMask = 0x2, targetIndex = 1, keepRepickStatus = true))
    val e4Conflict = LoadInflightRowMutationPathReference(
      sourceStoreEntries = 8,
      storeEntries = 4,
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = true,
      row = baseRow,
      request = BridgeRequest(targetMask = 0x2, targetIndex = 1, keepRepickStatus = true),
      conflicts = Conflicts(e4Update = true))

    assert(noScb.blockedByControl)
    assert(noScb.control.blockedByScbNotReturned)
    assert(!noScb.applyResult.applyValid)
    assert(e4Conflict.blockedByControl)
    assert(e4Conflict.control.blockedByE4UpdateConflict)
    assert(e4Conflict.control.writeConflict)
    assert(!e4Conflict.applyResult.applyValid)
    assert(e4Conflict.applyResult.nextRow == baseRow)
  }

  test("Chisel LoadInflightRowMutationPath elaborates the composed boundary") {
    val sv = ChiselStage.emitSystemVerilog(new LoadInflightRowMutationPath(
      liqEntries = 4,
      idEntries = 8,
      sourceStoreEntries = 8,
      storeEntries = 4
    ))

    assert(sv.contains("module LoadInflightRowMutationPath"))
    assert(sv.contains("io_bridgeValid"))
    assert(sv.contains("io_targetEvidenceValid"))
    assert(sv.contains("io_writeEnable"))
    assert(sv.contains("io_applyValid"))
    assert(sv.contains("io_nextRow_status"))
    assert(sv.contains("io_bridgeInvalidStoreIndexOutOfRange"))
    assert(sv.contains("io_controlBlockedByE4UpdateConflict"))
  }
}
