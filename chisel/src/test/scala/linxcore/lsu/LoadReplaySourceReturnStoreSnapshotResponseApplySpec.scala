package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotResponseApplyReference {
  final case class Response(
      valid: Boolean = false,
      waitStore: Boolean = false,
      dataValid: Boolean = false,
      rawDataValid: Boolean = false,
      dataSuppressedByWait: Boolean = false,
      waitStoreIndex: Int = 0,
      waitStoreBid: Int = 0,
      waitStoreRid: Int = 0,
      waitStoreLsId: Int = 0,
      waitStorePc: BigInt = 0,
      dataMask: BigInt = 0,
      data: BigInt = 0)

  final case class Result(
      active: Boolean,
      applyCandidate: Boolean,
      applyValid: Boolean,
      stqReturned: Boolean,
      targetMask: Int,
      waitStoreApply: Boolean,
      waitStoreIndex: Int,
      waitStoreBid: Int,
      waitStoreRid: Int,
      waitStoreLsId: Int,
      waitStorePc: BigInt,
      dataMergeApply: Boolean,
      dataNoMerge: Boolean,
      mergedValidMask: BigInt,
      mergedLineData: BigInt,
      mergedRequestComplete: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoOrderedResponse: Boolean,
      blockedByNotRepick: Boolean,
      blockedByWaitStore: Boolean,
      blockedByNoData: Boolean,
      invalidOrderedWithoutPayload: Boolean,
      invalidDataWithWaitStore: Boolean,
      invalidDataValidWithoutRawData: Boolean,
      invalidSuppressedDataWithoutWait: Boolean)

  private def mergeBytes(rowLineData: BigInt, responseData: BigInt, dataMask: BigInt, dataMerge: Boolean): BigInt = {
    var merged = BigInt(0)
    for (byte <- 0 until 64) {
      val lane =
        if (dataMerge && ((dataMask >> byte) & 1) == 1) {
          (responseData >> (byte * 8)) & 0xff
        } else {
          (rowLineData >> (byte * 8)) & 0xff
        }
      merged |= lane << (byte * 8)
    }
    merged
  }

  def apply(
      liqEntries: Int,
      enable: Boolean,
      flush: Boolean,
      orderedConsumed: Boolean,
      targetRepick: Boolean,
      targetOneHot: Int,
      response: Response,
      rowLineData: BigInt = 0,
      rowValidMask: BigInt = 0,
      rowRequestMask: BigInt = 0): Result = {
    val active = enable && !flush
    val rawResponse =
      orderedConsumed ||
        response.valid ||
        response.waitStore ||
        response.dataValid ||
        response.rawDataValid ||
        response.dataSuppressedByWait
    val applyCandidate = active && orderedConsumed && response.valid
    val applyValid = applyCandidate && targetRepick
    val waitStoreApply = applyValid && response.waitStore
    val dataMergeApply = applyValid && !response.waitStore && response.dataValid
    val dataNoMerge = applyValid && !response.waitStore && !response.dataValid
    val mergedValidMask = rowValidMask | (if (dataMergeApply) response.dataMask else BigInt(0))

    Result(
      active = active,
      applyCandidate = applyCandidate,
      applyValid = applyValid,
      stqReturned = applyValid,
      targetMask = if (applyValid) targetOneHot else 0,
      waitStoreApply = waitStoreApply,
      waitStoreIndex = if (waitStoreApply) response.waitStoreIndex else 0,
      waitStoreBid = if (waitStoreApply) response.waitStoreBid else 0,
      waitStoreRid = if (waitStoreApply) response.waitStoreRid else -1,
      waitStoreLsId = if (waitStoreApply) response.waitStoreLsId else 0,
      waitStorePc = if (waitStoreApply) response.waitStorePc else 0,
      dataMergeApply = dataMergeApply,
      dataNoMerge = dataNoMerge,
      mergedValidMask = mergedValidMask,
      mergedLineData = mergeBytes(rowLineData, response.data, response.dataMask, dataMergeApply),
      mergedRequestComplete = dataMergeApply && rowRequestMask != 0 && ((mergedValidMask & rowRequestMask) == rowRequestMask),
      blockedByDisabled = !enable && rawResponse,
      blockedByFlush = enable && flush && rawResponse,
      blockedByNoOrderedResponse = active && !orderedConsumed,
      blockedByNotRepick = applyCandidate && !targetRepick,
      blockedByWaitStore = waitStoreApply,
      blockedByNoData = dataNoMerge,
      invalidOrderedWithoutPayload = active && orderedConsumed && !response.valid,
      invalidDataWithWaitStore = applyValid && response.waitStore && response.dataValid,
      invalidDataValidWithoutRawData = applyValid && response.dataValid && !response.rawDataValid,
      invalidSuppressedDataWithoutWait = applyValid && response.dataSuppressedByWait && !response.waitStore)
  }
}

class LoadReplaySourceReturnStoreSnapshotResponseApplySpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotResponseApplyReference._

  test("ordered wait-store response sets stq-returned and preserves wait identity without merging data") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseApplyReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      orderedConsumed = true,
      targetRepick = true,
      targetOneHot = 0x4,
      response = Response(
        valid = true,
        waitStore = true,
        rawDataValid = true,
        dataSuppressedByWait = true,
        waitStoreIndex = 3,
        waitStoreBid = 5,
        waitStoreRid = 9,
        waitStoreLsId = 2,
        waitStorePc = BigInt("40005700", 16),
        dataMask = BigInt("ff", 16),
        data = BigInt("ddccbbaa", 16)),
      rowLineData = BigInt("11223344", 16),
      rowValidMask = BigInt("0f", 16),
      rowRequestMask = BigInt("ff", 16))

    assert(result.applyValid)
    assert(result.stqReturned)
    assert(result.waitStoreApply)
    assert(result.blockedByWaitStore)
    assert(result.targetMask == 0x4)
    assert(result.waitStoreIndex == 3)
    assert(result.waitStoreBid == 5)
    assert(result.waitStoreRid == 9)
    assert(result.waitStoreLsId == 2)
    assert(result.waitStorePc == BigInt("40005700", 16))
    assert(!result.dataMergeApply)
    assert(result.mergedValidMask == BigInt("0f", 16))
    assert(result.mergedLineData == BigInt("11223344", 16))
  }

  test("ordered data response merges only payload-valid byte lanes and reports request completion") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseApplyReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      orderedConsumed = true,
      targetRepick = true,
      targetOneHot = 0x2,
      response = Response(
        valid = true,
        dataValid = true,
        rawDataValid = true,
        dataMask = BigInt("f0", 16),
        data = BigInt("ddccbbaa00000000", 16)),
      rowLineData = BigInt("0102030405060708", 16),
      rowValidMask = BigInt("0f", 16),
      rowRequestMask = BigInt("ff", 16))

    assert(result.applyValid)
    assert(result.stqReturned)
    assert(result.dataMergeApply)
    assert(!result.waitStoreApply)
    assert(result.mergedValidMask == BigInt("ff", 16))
    assert(result.mergedRequestComplete)
    assert(result.mergedLineData == BigInt("ddccbbaa05060708", 16))
  }

  test("ordered response without wait-store or data only marks the STQ source returned") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseApplyReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      orderedConsumed = true,
      targetRepick = true,
      targetOneHot = 0x1,
      response = Response(valid = true),
      rowValidMask = BigInt("03", 16),
      rowRequestMask = BigInt("ff", 16))

    assert(result.applyValid)
    assert(result.stqReturned)
    assert(result.dataNoMerge)
    assert(result.blockedByNoData)
    assert(!result.waitStoreApply)
    assert(!result.dataMergeApply)
    assert(result.mergedValidMask == BigInt("03", 16))
  }

  test("disabled, flushed, no-ordered-response, and non-repick targets block apply") {
    val response = Response(valid = true, dataValid = true, rawDataValid = true)
    val disabled = LoadReplaySourceReturnStoreSnapshotResponseApplyReference(
      liqEntries = 4,
      enable = false,
      flush = false,
      orderedConsumed = true,
      targetRepick = true,
      targetOneHot = 0x1,
      response = response)
    val flushed = LoadReplaySourceReturnStoreSnapshotResponseApplyReference(
      liqEntries = 4,
      enable = true,
      flush = true,
      orderedConsumed = true,
      targetRepick = true,
      targetOneHot = 0x1,
      response = response)
    val noOrdered = LoadReplaySourceReturnStoreSnapshotResponseApplyReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      orderedConsumed = false,
      targetRepick = true,
      targetOneHot = 0x1,
      response = response)
    val notRepick = LoadReplaySourceReturnStoreSnapshotResponseApplyReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      orderedConsumed = true,
      targetRepick = false,
      targetOneHot = 0x1,
      response = response)

    assert(!disabled.applyValid && disabled.blockedByDisabled)
    assert(!flushed.applyValid && flushed.blockedByFlush)
    assert(!noOrdered.applyValid && noOrdered.blockedByNoOrderedResponse)
    assert(!notRepick.applyValid && notRepick.blockedByNotRepick)
  }

  test("malformed ordered payloads report exact diagnostics") {
    val missingPayload = LoadReplaySourceReturnStoreSnapshotResponseApplyReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      orderedConsumed = true,
      targetRepick = true,
      targetOneHot = 0x1,
      response = Response(valid = false))
    val waitAndData = LoadReplaySourceReturnStoreSnapshotResponseApplyReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      orderedConsumed = true,
      targetRepick = true,
      targetOneHot = 0x1,
      response = Response(valid = true, waitStore = true, dataValid = true, rawDataValid = true))
    val dataWithoutRaw = LoadReplaySourceReturnStoreSnapshotResponseApplyReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      orderedConsumed = true,
      targetRepick = true,
      targetOneHot = 0x1,
      response = Response(valid = true, dataValid = true, rawDataValid = false))
    val suppressedWithoutWait = LoadReplaySourceReturnStoreSnapshotResponseApplyReference(
      liqEntries = 4,
      enable = true,
      flush = false,
      orderedConsumed = true,
      targetRepick = true,
      targetOneHot = 0x1,
      response = Response(valid = true, dataSuppressedByWait = true))

    assert(missingPayload.invalidOrderedWithoutPayload)
    assert(waitAndData.invalidDataWithWaitStore)
    assert(dataWithoutRaw.invalidDataValidWithoutRawData)
    assert(suppressedWithoutWait.invalidSuppressedDataWithoutWait)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotResponseApply elaborates apply intent signals") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplaySourceReturnStoreSnapshotResponseApply(
        liqEntries = 4,
        idEntries = 16,
        clusterIdWidth = 2,
        entryIdWidth = 4
      ))

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotResponseApply"))
    assert(sv.contains("io_stqReturned"))
    assert(sv.contains("io_waitStoreInfo_storeId_value"))
    assert(sv.contains("io_waitStoreRid_value"))
    assert(sv.contains("io_mergedValidMask"))
    assert(sv.contains("io_invalidDataValidWithoutRawData"))
  }
}
