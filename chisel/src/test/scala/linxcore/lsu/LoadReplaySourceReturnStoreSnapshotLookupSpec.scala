package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotLookupReference {
  import LoadReplaySourceReturnStoreSnapshotRequestPayloadReference.Payload
  import LoadStoreForwardingReference.{Query, Result => ForwardResult, Store, byteMask, forward}

  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      queryValid: Boolean,
      loadCrossesLine: Boolean,
      requestMaskMismatch: Boolean,
      forward: ForwardResult,
      waitStoreValid: Boolean,
      rawDataValid: Boolean,
      responseDataValid: Boolean,
      dataSuppressedByWait: Boolean,
      storeBypassComplete: Boolean)

  private def crossesLine(addr: BigInt, size: Int): Boolean =
    ((addr & 0x3f) + size) > 64

  private def lineAddr(addr: BigInt): BigInt =
    addr & ~BigInt(0x3f)

  def apply(
      enable: Boolean,
      flush: Boolean,
      request: Option[Payload],
      stores: Seq[Store],
      cacheData: BigInt = 0): Result = {
    val active = enable && !flush
    val requestActive = active && request.exists(_.valid)
    val loadCrosses = request.exists(req => requestActive && crossesLine(req.addr, req.size))
    val queryValid = request.exists(req => requestActive && req.size != 0 && !loadCrosses)
    val query = request match {
      case Some(req) =>
        Query(
          valid = queryValid,
          lineAddr = lineAddr(req.addr),
          byteOffset = (req.addr & 0x3f).toInt,
          size = req.size,
          youngestStoreId = STQFlushPruneReference.Id(value = req.bid),
          youngestStoreLsId = STQFlushPruneReference.Id(value = req.loadLsId),
          isTile = false)
      case None =>
        Query(valid = false)
    }
    val fwd = forward(query, stores, cacheData)
    val waitStoreValid = queryValid && fwd.waitStore.isDefined
    val rawDataValid = queryValid && fwd.forwardValid
    val responseDataValid = rawDataValid && !waitStoreValid
    val expectedRequestMask = request.map(req => byteMask((req.addr & 0x3f).toInt, req.size)).getOrElse(BigInt(0))

    Result(
      active = active,
      requestActive = requestActive,
      queryValid = queryValid,
      loadCrossesLine = loadCrosses,
      requestMaskMismatch = queryValid && request.exists(_.requestByteMask != expectedRequestMask),
      forward = fwd,
      waitStoreValid = waitStoreValid,
      rawDataValid = rawDataValid,
      responseDataValid = responseDataValid,
      dataSuppressedByWait = rawDataValid && waitStoreValid,
      storeBypassComplete = queryValid && fwd.storeBypassComplete)
  }
}

class LoadReplaySourceReturnStoreSnapshotLookupSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotLookupReference._
  import LoadReplaySourceReturnStoreSnapshotRequestPayloadReference.Payload
  import LoadStoreForwardingReference.{Store, byteMask, lineData}

  private def request(addr: BigInt = 0x1008, size: Int = 4, bid: Int = 6, loadLsId: Int = 3): Payload =
    Payload(
      valid = true,
      clusterId = 0,
      entryId = 1,
      loadId = 1,
      bid = bid,
      gid = 1,
      rid = 4,
      loadLsId = loadLsId,
      pc = BigInt("40005600", 16),
      addr = addr,
      size = size,
      requestByteMask = byteMask((addr & 0x3f).toInt, size))

  test("ready resident store data becomes response data") {
    val req = request()
    val store = Store(
      index = 0,
      storeId = STQFlushPruneReference.Id(value = 4),
      storeLsId = STQFlushPruneReference.Id(value = 1),
      byteMask = byteMask(8, 4),
      data = lineData(Map(8 -> 0xaa, 9 -> 0xbb, 10 -> 0xcc, 11 -> 0xdd)))

    val result = LoadReplaySourceReturnStoreSnapshotLookupReference(
      enable = true,
      flush = false,
      request = Some(req),
      stores = Seq(store))

    assert(result.active)
    assert(result.queryValid)
    assert(result.forward.eligibleStoreMask == 0x1)
    assert(result.forward.forwardMask == byteMask(8, 4))
    assert(!result.waitStoreValid)
    assert(result.rawDataValid)
    assert(result.responseDataValid)
    assert(result.storeBypassComplete)
  }

  test("nearest not-ready store becomes wait-store response") {
    val req = request()
    val stores = Seq(
      Store(
        index = 0,
        storeId = STQFlushPruneReference.Id(value = 4),
        storeLsId = STQFlushPruneReference.Id(value = 1),
        byteMask = byteMask(8, 4),
        data = lineData(Map(8 -> 0x11, 9 -> 0x12, 10 -> 0x13, 11 -> 0x14))),
      Store(
        index = 1,
        dataReady = false,
        pc = 0x40005700,
        storeId = STQFlushPruneReference.Id(value = 5),
        storeLsId = STQFlushPruneReference.Id(value = 2),
        byteMask = byteMask(9, 2)))

    val result = LoadReplaySourceReturnStoreSnapshotLookupReference(
      enable = true,
      flush = false,
      request = Some(req),
      stores = stores)

    assert(result.forward.forwardMask == ((BigInt(1) << 8) | (BigInt(1) << 11)))
    assert(result.forward.waitMask == ((BigInt(1) << 9) | (BigInt(1) << 10)))
    assert(result.waitStoreValid)
    assert(result.rawDataValid)
    assert(!result.responseDataValid)
    assert(result.dataSuppressedByWait)
    assert(!result.storeBypassComplete)
    assert(result.forward.waitStore.exists(_.index == 1))
  }

  test("stores younger than the replay request snapshot are ignored") {
    val req = request(bid = 3, loadLsId = 2)
    val younger = Store(
      index = 0,
      storeId = STQFlushPruneReference.Id(value = 4),
      storeLsId = STQFlushPruneReference.Id(value = 0),
      byteMask = byteMask(8, 4),
      data = lineData(Map(8 -> 0xaa)))

    val result = LoadReplaySourceReturnStoreSnapshotLookupReference(
      enable = true,
      flush = false,
      request = Some(req),
      stores = Seq(younger))

    assert(result.queryValid)
    assert(result.forward.eligibleStoreMask == 0)
    assert(result.forward.uncoveredLoadMask == byteMask(8, 4))
    assert(!result.rawDataValid)
    assert(!result.responseDataValid)
    assert(!result.waitStoreValid)
  }

  test("disabled, flush, and cross-line requests suppress lookup query") {
    val req = request(addr = 0x103e, size = 4)
    val disabled = LoadReplaySourceReturnStoreSnapshotLookupReference(
      enable = false,
      flush = false,
      request = Some(req),
      stores = Seq.empty)
    val flushed = LoadReplaySourceReturnStoreSnapshotLookupReference(
      enable = true,
      flush = true,
      request = Some(req),
      stores = Seq.empty)
    val crossing = LoadReplaySourceReturnStoreSnapshotLookupReference(
      enable = true,
      flush = false,
      request = Some(req),
      stores = Seq.empty)

    assert(!disabled.active)
    assert(!disabled.queryValid)
    assert(!flushed.active)
    assert(!flushed.queryValid)
    assert(crossing.requestActive)
    assert(crossing.loadCrossesLine)
    assert(!crossing.queryValid)
  }

  test("request mask mismatch is reported for malformed payloads") {
    val req = request().copy(requestByteMask = BigInt(0))
    val result = LoadReplaySourceReturnStoreSnapshotLookupReference(
      enable = true,
      flush = false,
      request = Some(req),
      stores = Seq.empty)

    assert(result.queryValid)
    assert(result.requestMaskMismatch)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotLookup elaborates forwarding children") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotLookup(
      liqEntries = 4,
      idEntries = 4,
      clusterIdWidth = 2,
      entryIdWidth = 2))

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotLookup"))
    assert(sv.contains("ResidentStoreForwardStoreSnapshot"))
    assert(sv.contains("LoadStoreForwarding"))
    assert(sv.contains("io_waitStoreValid"))
    assert(sv.contains("io_forwardData"))
    assert(sv.contains("io_responseDataValid"))
    assert(sv.contains("io_dataSuppressedByWait"))
  }

  test("snapshot lookup separates STQ capacity from request identity sizing") {
    val io = new LoadReplaySourceReturnStoreSnapshotLookupIO(
      liqEntries = 4,
      idEntries = 8,
      clusterIdWidth = 2,
      entryIdWidth = 2,
      stqEntries = 16)

    assert(io.rows.length == 16)
    assert(io.eligibleStoreMask.getWidth == 16)
    assert(io.waitStore.storeIndex.getWidth == 4)
    assert(io.request.bid.value.getWidth == 3)
    assert(io.waitStore.storeId.value.getWidth == 3)
  }
}
