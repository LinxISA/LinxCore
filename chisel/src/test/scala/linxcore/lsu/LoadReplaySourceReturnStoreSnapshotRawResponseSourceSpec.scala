package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotRawResponseSourceReference {
  final case class Response(
      clusterId: Int = 0,
      entryId: Int = 0,
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
      candidate: Boolean,
      responseValid: Boolean,
      response: Option[Response],
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByLiveDisabled: Boolean,
      invalidDataWithWaitStore: Boolean,
      invalidDataValidWithoutRawData: Boolean,
      invalidSuppressedDataWithoutWait: Boolean,
      invalidSuppressedDataWithoutRawData: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveEnable: Boolean,
      rawValid: Boolean,
      clusterId: Int = 0,
      entryId: Int = 0,
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
      data: BigInt = 0): Result = {
    val active = enable && !flush
    val candidate = active && rawValid
    val responseValid = candidate && liveEnable
    val response =
      if (responseValid) {
        Some(Response(
          clusterId = clusterId,
          entryId = entryId,
          waitStore = waitStore,
          dataValid = dataValid,
          rawDataValid = rawDataValid,
          dataSuppressedByWait = dataSuppressedByWait,
          waitStoreIndex = waitStoreIndex,
          waitStoreBid = waitStoreBid,
          waitStoreRid = waitStoreRid,
          waitStoreLsId = waitStoreLsId,
          waitStorePc = waitStorePc,
          dataMask = dataMask,
          data = data))
      } else {
        None
      }

    Result(
      active = active,
      candidate = candidate,
      responseValid = responseValid,
      response = response,
      blockedByDisabled = !enable && rawValid,
      blockedByFlush = enable && flush && rawValid,
      blockedByLiveDisabled = candidate && !liveEnable,
      invalidDataWithWaitStore = candidate && waitStore && dataValid,
      invalidDataValidWithoutRawData = candidate && dataValid && !rawDataValid,
      invalidSuppressedDataWithoutWait = candidate && dataSuppressedByWait && !waitStore,
      invalidSuppressedDataWithoutRawData = candidate && dataSuppressedByWait && !rawDataValid)
  }
}

class LoadReplaySourceReturnStoreSnapshotRawResponseSourceSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotRawResponseSourceReference._

  test("live source preserves raw MemReqBus response sidebands") {
    val result = LoadReplaySourceReturnStoreSnapshotRawResponseSourceReference(
      enable = true,
      flush = false,
      liveEnable = true,
      rawValid = true,
      clusterId = 0,
      entryId = 3,
      waitStore = true,
      dataValid = false,
      rawDataValid = true,
      dataSuppressedByWait = true,
      waitStoreIndex = 6,
      waitStoreBid = 5,
      waitStoreRid = 7,
      waitStoreLsId = 2,
      waitStorePc = BigInt("40005700", 16),
      dataMask = BigInt("00ff", 16),
      data = BigInt("1122334455667788", 16))

    assert(result.active)
    assert(result.candidate)
    assert(result.responseValid)
    assert(result.response.exists(_.waitStoreRid == 7))
    assert(result.response.exists(_.dataSuppressedByWait))
    assert(result.response.exists(_.dataMask == BigInt("00ff", 16)))
  }

  test("live-disabled raw candidate is diagnosed but not exposed") {
    val result = LoadReplaySourceReturnStoreSnapshotRawResponseSourceReference(
      enable = true,
      flush = false,
      liveEnable = false,
      rawValid = true,
      clusterId = 0,
      entryId = 1,
      dataValid = true,
      rawDataValid = true)

    assert(result.candidate)
    assert(!result.responseValid)
    assert(result.response.isEmpty)
    assert(result.blockedByLiveDisabled)
  }

  test("disabled and flushed source suppress response visibility") {
    val disabled = LoadReplaySourceReturnStoreSnapshotRawResponseSourceReference(
      enable = false,
      flush = false,
      liveEnable = true,
      rawValid = true)
    val flushed = LoadReplaySourceReturnStoreSnapshotRawResponseSourceReference(
      enable = true,
      flush = true,
      liveEnable = true,
      rawValid = true)

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(!disabled.responseValid)
    assert(!flushed.responseValid)
  }

  test("raw payload consistency diagnostics remain visible on candidates") {
    val result = LoadReplaySourceReturnStoreSnapshotRawResponseSourceReference(
      enable = true,
      flush = false,
      liveEnable = false,
      rawValid = true,
      waitStore = true,
      dataValid = true,
      rawDataValid = false,
      dataSuppressedByWait = true)

    assert(result.invalidDataWithWaitStore)
    assert(result.invalidDataValidWithoutRawData)
    assert(result.invalidSuppressedDataWithoutRawData)
    assert(!result.invalidSuppressedDataWithoutWait)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotRawResponseSource elaborates source diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotRawResponseSource)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotRawResponseSource"))
    assert(sv.contains("io_liveEnable"))
    assert(sv.contains("io_response_waitStoreRid_value"))
    assert(sv.contains("io_blockedByLiveDisabled"))
    assert(sv.contains("io_invalidDataValidWithoutRawData"))
  }
}
