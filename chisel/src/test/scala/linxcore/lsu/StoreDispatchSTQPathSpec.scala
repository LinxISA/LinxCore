package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object StoreDispatchSTQPathReference {
  import STQEntryBankReference._
  import STQInsertProbeReference._
  import StoreDispatchToSTQReference.Decision

  final case class PathDecision(staProbe: Result, stdProbe: Result, dispatch: Decision)

  def decide(
      rows: Seq[Option[Entry]],
      staReq: Request,
      stdReq: Request,
      staValid: Boolean,
      stdValid: Boolean,
      staExecValid: Boolean,
      stdExecValid: Boolean,
      flush: Boolean = false,
      flushApplied: Boolean = false): PathDecision = {
    val staCandidate = !flush && staValid && staExecValid
    val stdCandidate = !flush && stdValid && stdExecValid
    val staProbe = probe(rows, staReq, valid = staCandidate, flushApplied = flushApplied)
    val stdProbe = probe(rows, stdReq, valid = stdCandidate, flushApplied = flushApplied)
    val dispatch = StoreDispatchToSTQReference.decide(
      staValid = staValid,
      stdValid = stdValid,
      staExecValid = staExecValid,
      stdExecValid = stdExecValid,
      staInsertReady = staProbe.ready,
      stdInsertReady = stdProbe.ready,
      flush = flush)

    PathDecision(staProbe, stdProbe, dispatch)
  }
}

class StoreDispatchSTQPathSpec extends AnyFunSuite {
  import STQEntryBankReference._
  import STQFlushPruneReference.Id
  import StoreDispatchSTQPathReference._

  private def req(n: Int, storeType: StoreType = All, bid: Int = 0, lsId: Int = 0): Request =
    Request(
      storeType = storeType,
      bid = Id(value = bid),
      gid = Id(value = 0),
      rid = Id(value = n),
      lsId = Id(value = lsId),
      stid = 1,
      peId = 2,
      tid = 3,
      addr = 0x1000 + n * 8,
      data = 0x2000 + n,
      size = 8
    )

  private def waitEntry(request: Request): Entry =
    Entry(
      status = Wait,
      req = request,
      addrReady = request.storeType == All || request.storeType == Addr,
      dataReady = request.storeType == All || request.storeType == Data)

  test("reference path lets mergeable STD bypass a present STA when the STQ is allocation-full") {
    val rows = Seq(
      Some(waitEntry(req(0, storeType = Addr, bid = 3, lsId = 2))),
      Some(waitEntry(req(1, storeType = All, bid = 4, lsId = 0))))

    val result = decide(
      rows = rows,
      staReq = req(2, storeType = Addr, bid = 5, lsId = 0),
      stdReq = req(3, storeType = Data, bid = 3, lsId = 2),
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true)

    assert(!result.staProbe.ready)
    assert(result.stdProbe.ready)
    assert(result.stdProbe.canMerge)
    assert(!result.dispatch.selectedSta)
    assert(result.dispatch.selectedStd)
    assert(result.dispatch.blockedByStaInsert)
    assert(result.dispatch.stdBypassStaBlocked)
  }

  test("reference path keeps STA priority when both candidates can insert") {
    val rows = Seq(None, None)
    val result = decide(
      rows = rows,
      staReq = req(0, storeType = All, bid = 1, lsId = 0),
      stdReq = req(1, storeType = All, bid = 1, lsId = 1),
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true)

    assert(result.staProbe.ready)
    assert(result.stdProbe.ready)
    assert(result.dispatch.selectedSta)
    assert(!result.dispatch.selectedStd)
    assert(!result.dispatch.stdBypassStaBlocked)
  }

  test("reference path suppresses dispatch candidates during flush") {
    val rows = Seq(None, None)
    val result = decide(
      rows = rows,
      staReq = req(0),
      stdReq = req(1),
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true,
      flush = true)

    assert(!result.dispatch.staCandidate)
    assert(!result.dispatch.stdCandidate)
    assert(!result.dispatch.selectedSta)
    assert(!result.dispatch.selectedStd)
    assert(!result.dispatch.blockedByStaExec)
    assert(!result.dispatch.blockedByStdExec)
  }

  test("StoreDispatchSTQPath IO preserves queue, request, and STQ counter widths") {
    val p = InterfaceParams(robEntries = 8)
    val io = new StoreDispatchSTQPathIO(p, queueDepth = 4, entries = 8)

    assert(io.queueFlushValid.getWidth == 1)
    assert(io.staReady.getWidth == 1)
    assert(io.stdReady.getWidth == 1)
    assert(io.staQueueCount.getWidth == 3)
    assert(io.stdQueueCount.getWidth == 3)
    assert(io.markCommitIndex.getWidth == 3)
    assert(io.staRequest.bid.value.getWidth == 3)
    assert(io.stdRequest.lsId.value.getWidth == 3)
    assert(io.staRequest.tSeq.value.getWidth == 5)
    assert(io.staRequest.pc.getWidth == 64)
    assert(io.staIn.tSeq.value.getWidth == 5)
    assert(io.stdQueue.uSeq.value.getWidth == 5)
    assert(io.lsuTULinkSource.tSeq.value.getWidth == 5)
    assert(io.lsuTULinkSourceMatched.getWidth == 1)
    assert(io.stqResidentCount.getWidth == 4)
    assert(io.stqOutstandingWaitCount.getWidth == 4)
    assert(io.stqRows.length == 8)
    assert(io.stqRows.head.pc.getWidth == 64)
    assert(io.staExec.addr.getWidth == 64)
  }

  test("StoreDispatchSTQPath elaborates with queues, probes, bridge, and STQ bank") {
    val sv = ChiselStage.emitSystemVerilog(new StoreDispatchSTQPath(InterfaceParams(robEntries = 8), queueDepth = 4, entries = 8))

    assert(sv.contains("module StoreDispatchSTQPath"))
    assert(sv.contains("StoreDispatchQueues"))
    assert(sv.contains("io_queueFlushValid"))
    assert(sv.contains("StoreDispatchToSTQ"))
    assert(sv.contains("STQInsertProbe"))
    assert(sv.contains("STQEntryBank"))
    assert(sv.contains("io_staIn_tSeq_value"))
    assert(sv.contains("io_stdQueue_uSeq_value"))
    assert(sv.contains("io_stqRows_0_pc"))
    assert(sv.contains("io_lsuTULinkSource_valid"))
    assert(sv.contains("io_lsuTULinkSourceMatched"))
    assert(sv.contains("io_stdBypassStaBlocked"))
  }
}
