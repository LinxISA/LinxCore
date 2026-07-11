package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadInflightRowMutationApplyReference {
  import LoadInflightQueueReference._
  import LoadStoreForwardingReference.Store

  final case class Request(
      setWaitStatus: Boolean = false,
      keepRepickStatus: Boolean = false,
      clearReturnState: Boolean = false,
      lineWrite: Boolean = false,
      waitStoreWrite: Boolean = false,
      nextWaitStore: Option[Store] = None,
      nextLineData: BigInt = 0,
      nextValidMask: BigInt = 0,
      nextDataComplete: Boolean = false,
      nextScbReturned: Boolean = false,
      nextStqReturned: Boolean = false,
      nextStoreSourceReturned: Boolean = false)

  final case class Result(
      active: Boolean,
      applyValid: Boolean,
      nextRow: Row,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoRequest: Boolean,
      blockedByInvalidRow: Boolean,
      blockedByNotRepick: Boolean,
      invalidConflictingStatusWrite: Boolean,
      invalidWaitStoreWithoutWaitStatus: Boolean,
      invalidReturnWithoutSplitSources: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      requestValid: Boolean,
      rowValid: Boolean,
      row: Row,
      request: Request,
      allowWaitTarget: Boolean = false): Result = {
    val active = enable && !flush
    val requestActive = active && requestValid
    val rowRepick = rowValid && row.status == Repick
    val invalidConflictingStatus = request.setWaitStatus && request.keepRepickStatus
    val invalidWaitStore = request.nextWaitStore.nonEmpty && !request.setWaitStatus
    val invalidReturn =
      request.nextStoreSourceReturned && !(request.nextScbReturned && request.nextStqReturned)
    val validShape = !invalidConflictingStatus && !invalidWaitStore && !invalidReturn
    val rowWait = rowValid && row.status == Wait
    val rowEligible = rowRepick || (allowWaitTarget && rowWait)
    val applyValid = requestActive && rowEligible && validShape

    val next =
      if (!applyValid) {
        row
      } else {
        val status =
          if (request.setWaitStatus) Wait
          else if (request.keepRepickStatus) Repick
          else row.status
        val cleared = row.copy(status = status)
        val returned =
          if (request.clearReturnState) {
            cleared.copy(
              sourcesReturned = false,
              scbReturned = false,
              stqReturned = false,
              dataComplete = false)
          } else {
            cleared.copy(
              sourcesReturned = request.nextStoreSourceReturned,
              scbReturned = request.nextScbReturned,
              stqReturned = request.nextStqReturned,
              dataComplete = request.nextDataComplete)
          }
        val lined =
          if (request.lineWrite) {
            returned.copy(
              lineData = request.nextLineData,
              validMask = request.nextValidMask,
              dataComplete = request.nextDataComplete)
          } else {
            returned
          }
        if (request.waitStoreWrite) {
          lined.copy(waitStore = request.nextWaitStore)
        } else {
          lined
        }
      }

    Result(
      active = active,
      applyValid = applyValid,
      nextRow = next,
      blockedByDisabled = !enable && requestValid,
      blockedByFlush = enable && flush && requestValid,
      blockedByNoRequest = active && !requestValid,
      blockedByInvalidRow = requestActive && !rowValid,
      blockedByNotRepick = requestActive && rowValid && !rowEligible,
      invalidConflictingStatusWrite = requestActive && invalidConflictingStatus,
      invalidWaitStoreWithoutWaitStatus = requestActive && invalidWaitStore,
      invalidReturnWithoutSplitSources = requestActive && invalidReturn)
  }
}

class LoadInflightRowMutationApplySpec extends AnyFunSuite {
  import LoadInflightQueueReference._
  import LoadInflightRowMutationApplyReference._
  import LoadStoreForwardingReference.{Store, byteMask, lineData}
  import STQFlushPruneReference.Id

  private val baseRow = Row(
    status = Repick,
    loadId = Id(valid = true, value = 1),
    alloc = Alloc(bid = Id(value = 3), rid = Id(value = 4), loadLsId = Id(value = 5), pc = 0x4000, addr = 0x1008),
    lineData = lineData(Map(8 -> 0x11)),
    validMask = byteMask(8, 1),
    loadByteMask = byteMask(8, 2),
    dataComplete = true,
    sourcesReturned = true,
    scbReturned = true,
    stqReturned = true)

  test("wait-store mutation returns a repick row to wait and clears split return state") {
    val wait = Store(index = 2, storeId = Id(value = 7), pc = 0x4560)
    val result = LoadInflightRowMutationApplyReference(
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = true,
      row = baseRow,
      request = Request(
        setWaitStatus = true,
        clearReturnState = true,
        lineWrite = true,
        waitStoreWrite = true,
        nextWaitStore = Some(wait),
        nextLineData = 0,
        nextValidMask = 0))

    assert(result.applyValid)
    assert(result.nextRow.status == Wait)
    assert(result.nextRow.waitStore.contains(wait))
    assert(!result.nextRow.sourcesReturned)
    assert(!result.nextRow.scbReturned)
    assert(!result.nextRow.stqReturned)
    assert(!result.nextRow.dataComplete)
    assert(result.nextRow.validMask == 0)
    assert(result.nextRow.loadId == baseRow.loadId)
    assert(result.nextRow.alloc.pc == baseRow.alloc.pc)
  }

  test("data-merge mutation keeps repick status and stores final split return state") {
    val mergedData = lineData(Map(8 -> 0xaa, 9 -> 0xbb))
    val result = LoadInflightRowMutationApplyReference(
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = true,
      row = baseRow.copy(sourcesReturned = false, stqReturned = false),
      request = Request(
        keepRepickStatus = true,
        lineWrite = true,
        nextLineData = mergedData,
        nextValidMask = byteMask(8, 2),
        nextDataComplete = true,
        nextScbReturned = true,
        nextStqReturned = true,
        nextStoreSourceReturned = true))

    assert(result.applyValid)
    assert(result.nextRow.status == Repick)
    assert(result.nextRow.lineData == mergedData)
    assert(result.nextRow.validMask == byteMask(8, 2))
    assert(result.nextRow.dataComplete)
    assert(result.nextRow.sourcesReturned)
    assert(result.nextRow.scbReturned)
    assert(result.nextRow.stqReturned)
  }

  test("applies an MDB wait mutation before a Wait row has launched") {
    val wait = Store(index = 1, storeId = Id(value = 2), pc = 0x3000)
    val result = LoadInflightRowMutationApplyReference(
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = true,
      row = baseRow.copy(status = Wait, scbReturned = false),
      request = Request(
        setWaitStatus = true,
        clearReturnState = true,
        lineWrite = true,
        waitStoreWrite = true,
        nextWaitStore = Some(wait)),
      allowWaitTarget = true)

    assert(result.applyValid)
    assert(result.nextRow.status == Wait)
    assert(result.nextRow.waitStore.contains(wait))
    assert(!result.nextRow.scbReturned)
  }

  test("blocks missing or non-repick rows without mutating the image") {
    val invalid = LoadInflightRowMutationApplyReference(
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = false,
      row = baseRow,
      request = Request(keepRepickStatus = true))
    val waitRow = LoadInflightRowMutationApplyReference(
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = true,
      row = baseRow.copy(status = Wait),
      request = Request(keepRepickStatus = true))

    assert(invalid.blockedByInvalidRow)
    assert(!invalid.applyValid)
    assert(invalid.nextRow == baseRow)
    assert(waitRow.blockedByNotRepick)
    assert(!waitRow.applyValid)
    assert(waitRow.nextRow.status == Wait)
  }

  test("reports impossible request shapes before apply") {
    val conflicting = LoadInflightRowMutationApplyReference(
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = true,
      row = baseRow,
      request = Request(setWaitStatus = true, keepRepickStatus = true))
    val waitWithoutStatus = LoadInflightRowMutationApplyReference(
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = true,
      row = baseRow,
      request = Request(keepRepickStatus = true, nextWaitStore = Some(Store(index = 0))))
    val returnedWithoutSplit = LoadInflightRowMutationApplyReference(
      enable = true,
      flush = false,
      requestValid = true,
      rowValid = true,
      row = baseRow,
      request = Request(
        keepRepickStatus = true,
        nextScbReturned = true,
        nextStqReturned = false,
        nextStoreSourceReturned = true))

    assert(conflicting.invalidConflictingStatusWrite)
    assert(waitWithoutStatus.invalidWaitStoreWithoutWaitStatus)
    assert(returnedWithoutSplit.invalidReturnWithoutSplitSources)
    assert(!conflicting.applyValid)
    assert(!waitWithoutStatus.applyValid)
    assert(!returnedWithoutSplit.applyValid)
  }

  test("Chisel LoadInflightRowMutationApply elaborates LIQ row mutation preview") {
    val sv = ChiselStage.emitSystemVerilog(new LoadInflightRowMutationApply(
      liqEntries = 4,
      idEntries = 8,
      storeEntries = 4
    ))

    assert(sv.contains("module LoadInflightRowMutationApply"))
    assert(sv.contains("io_row_status"))
    assert(sv.contains("io_nextRow_status"))
    assert(sv.contains("io_nextScbReturned"))
    assert(sv.contains("io_nextRow_scbReturned"))
    assert(sv.contains("io_blockedByNotRepick"))
    assert(sv.contains("io_invalidReturnWithoutSplitSources"))
  }
}
