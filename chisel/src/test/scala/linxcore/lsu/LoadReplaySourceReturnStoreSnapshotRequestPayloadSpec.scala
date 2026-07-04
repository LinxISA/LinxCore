package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotRequestPayloadReference {
  final case class Payload(
      valid: Boolean = false,
      clusterId: Int = 0,
      entryId: Int = 0,
      loadId: Int = 0,
      bid: Int = 0,
      gid: Int = 0,
      rid: Int = 0,
      loadLsId: Int = 0,
      peId: Int = 0,
      stid: Int = 0,
      tid: Int = 0,
      pc: BigInt = 0,
      addr: BigInt = 0,
      size: Int = 0,
      requestByteMask: BigInt = 0)

  final case class Result(
      active: Boolean,
      captureCandidate: Boolean,
      payload: Payload,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoIssue: Boolean,
      blockedByNoSelected: Boolean,
      blockedByStaleRow: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      queryIssued: Boolean,
      selectedValid: Boolean,
      selectedRepick: Boolean,
      selectedClusterId: Int = 0,
      selectedEntryId: Int = 0,
      selectedLoadId: Int = 0,
      selectedBid: Int = 0,
      selectedGid: Int = 0,
      selectedRid: Int = 0,
      selectedLoadLsId: Int = 0,
      selectedPeId: Int = 0,
      selectedStid: Int = 0,
      selectedTid: Int = 0,
      selectedPc: BigInt = 0,
      selectedAddr: BigInt = 0,
      selectedSize: Int = 0,
      selectedRequestByteMask: BigInt = 0): Result = {
    val active = enable && !flush
    val captureCandidate = active && queryIssued
    val requestValid = captureCandidate && selectedValid && selectedRepick
    val payload =
      if (requestValid) {
        Payload(
          valid = true,
          clusterId = selectedClusterId,
          entryId = selectedEntryId,
          loadId = selectedLoadId,
          bid = selectedBid,
          gid = selectedGid,
          rid = selectedRid,
          loadLsId = selectedLoadLsId,
          peId = selectedPeId,
          stid = selectedStid,
          tid = selectedTid,
          pc = selectedPc,
          addr = selectedAddr,
          size = selectedSize,
          requestByteMask = selectedRequestByteMask)
      } else {
        Payload()
      }

    Result(
      active = active,
      captureCandidate = captureCandidate,
      payload = payload,
      blockedByDisabled = !enable && queryIssued,
      blockedByFlush = enable && flush && queryIssued,
      blockedByNoIssue = active && selectedValid && selectedRepick && !queryIssued,
      blockedByNoSelected = captureCandidate && !selectedValid,
      blockedByStaleRow = captureCandidate && selectedValid && !selectedRepick)
  }
}

class LoadReplaySourceReturnStoreSnapshotRequestPayloadSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotRequestPayloadReference._

  test("accepted query emits selected replay request payload") {
    val result = LoadReplaySourceReturnStoreSnapshotRequestPayloadReference(
      enable = true,
      flush = false,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = true,
      selectedClusterId = 0,
      selectedEntryId = 2,
      selectedLoadId = 2,
      selectedBid = 6,
      selectedGid = 1,
      selectedRid = 7,
      selectedLoadLsId = 9,
      selectedPeId = 2,
      selectedStid = 3,
      selectedTid = 4,
      selectedPc = BigInt("400055f2", 16),
      selectedAddr = BigInt("40012040", 16),
      selectedSize = 8,
      selectedRequestByteMask = BigInt("ff", 16) << 8)

    assert(result.active)
    assert(result.captureCandidate)
    assert(result.payload.valid)
    assert(result.payload.entryId == 2)
    assert(result.payload.loadId == 2)
    assert(result.payload.bid == 6)
    assert(result.payload.loadLsId == 9)
    assert(result.payload.peId == 2)
    assert(result.payload.stid == 3)
    assert(result.payload.tid == 4)
    assert(result.payload.pc == BigInt("400055f2", 16))
    assert(result.payload.addr == BigInt("40012040", 16))
    assert(result.payload.size == 8)
    assert(result.payload.requestByteMask == (BigInt("ff", 16) << 8))
  }

  test("selected request waits when query issue has not fired") {
    val result = LoadReplaySourceReturnStoreSnapshotRequestPayloadReference(
      enable = true,
      flush = false,
      queryIssued = false,
      selectedValid = true,
      selectedRepick = true)

    assert(!result.payload.valid)
    assert(result.blockedByNoIssue)
  }

  test("invalid or stale selected rows do not emit payload") {
    val noSelected = LoadReplaySourceReturnStoreSnapshotRequestPayloadReference(
      enable = true,
      flush = false,
      queryIssued = true,
      selectedValid = false,
      selectedRepick = false)
    val stale = LoadReplaySourceReturnStoreSnapshotRequestPayloadReference(
      enable = true,
      flush = false,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = false)

    assert(noSelected.blockedByNoSelected)
    assert(stale.blockedByStaleRow)
    assert(!noSelected.payload.valid)
    assert(!stale.payload.valid)
  }

  test("disabled and flushed states suppress request payload") {
    val disabled = LoadReplaySourceReturnStoreSnapshotRequestPayloadReference(
      enable = false,
      flush = false,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = true)
    val flushed = LoadReplaySourceReturnStoreSnapshotRequestPayloadReference(
      enable = true,
      flush = true,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = true)

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(!disabled.payload.valid)
    assert(!flushed.payload.valid)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotRequestPayload elaborates payload fields") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotRequestPayload)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotRequestPayload"))
    assert(sv.contains("io_request_peId"))
    assert(sv.contains("io_request_stid"))
    assert(sv.contains("io_request_tid"))
    assert(sv.contains("io_request_addr"))
    assert(sv.contains("io_request_requestByteMask"))
    assert(sv.contains("io_blockedByStaleRow"))
  }
}
