package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotResponseQueueReference {
  final case class Response(
      clusterId: Int = 0,
      entryId: Int = 0,
      requestBid: Int = 0,
      requestGid: Int = 0,
      requestRid: Int = 0,
      requestLoadLsId: Int = 0,
      requestPeId: Int = 0,
      requestStid: Int = 0,
      requestTid: Int = 0,
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
      enqueueReady: Boolean,
      enqueueAccepted: Boolean,
      enqueueDropped: Boolean,
      headValid: Boolean,
      head: Option[Response],
      headConsumed: Boolean,
      pending: Boolean,
      full: Boolean,
      empty: Boolean,
      count: Int,
      precisePruneMask: Int,
      precisePruneCount: Int,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByPreciseFlush: Boolean,
      blockedByFull: Boolean,
      next: Vector[Response])

  def step(
      state: Vector[Response],
      depth: Int,
      enable: Boolean,
      flush: Boolean,
      preciseFlush: Option[STQFlushPruneReference.Flush] = None,
      enqueue: Option[Response] = None,
      dequeueReady: Boolean = false): Result = {
    val precisePruneActive = enable && !flush && preciseFlush.exists(_.valid)
    val active = enable && !flush && !precisePruneActive
    val residentHead = state.headOption
    val popResident = active && residentHead.isDefined && dequeueReady
    val enqueueReady = active && (state.size < depth || popResident)
    val enqueueAccepted = enqueue.isDefined && enqueueReady
    val bypassHead = residentHead.isEmpty && enqueueAccepted
    val head =
      if (!active) {
        None
      } else {
        residentHead.orElse(if (bypassHead) enqueue else None)
    }
    val headConsumed = head.isDefined && dequeueReady
    val storeEnqueue = enqueueAccepted && !(bypassHead && dequeueReady)
    val precisePruneBits =
      if (precisePruneActive) {
        state.map { response =>
          STQFlushPruneReference.matches(
            preciseFlush.get,
            STQFlushPruneReference.Row(
              valid = true,
              stid = response.requestStid,
              peId = response.requestPeId,
              tid = response.requestTid,
              bid = STQFlushPruneReference.Id(value = response.requestBid),
              gid = STQFlushPruneReference.Id(value = response.requestGid),
              lsId = STQFlushPruneReference.Id(value = response.requestLoadLsId)
            )
          )
        }
      } else {
        state.map(_ => false)
      }
    val next =
      if (flush) {
        Vector.empty
      } else {
        val afterPrune = state.zip(precisePruneBits).collect { case (response, false) => response }
        val afterPop = if (popResident) afterPrune.drop(1) else afterPrune
        if (storeEnqueue) afterPop :+ enqueue.get else afterPop
      }
    val precisePruneMask = precisePruneBits.zipWithIndex.foldLeft(0) { case (acc, (bit, index)) =>
      if (bit) acc | (1 << index) else acc
    }

    Result(
      enqueueReady = enqueueReady,
      enqueueAccepted = enqueueAccepted,
      enqueueDropped = enqueue.isDefined && !flush && !enqueueReady,
      headValid = head.isDefined,
      head = head,
      headConsumed = headConsumed,
      pending = next.nonEmpty,
      full = next.size == depth,
      empty = next.isEmpty,
      count = next.size,
      precisePruneMask = precisePruneMask,
      precisePruneCount = precisePruneBits.count(identity),
      blockedByDisabled = !enable && enqueue.isDefined,
      blockedByFlush = enable && flush && enqueue.isDefined,
      blockedByPreciseFlush = precisePruneActive && enqueue.isDefined,
      blockedByFull = enable && !flush && !precisePruneActive && enqueue.isDefined && !enqueueReady,
      next = next)
  }
}

class LoadReplaySourceReturnStoreSnapshotResponseQueueSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotResponseQueueReference._

  test("stores raw STQ responses in FIFO order") {
    val first = Response(
      clusterId = 0,
      entryId = 1,
      requestBid = 4,
      requestGid = 1,
      requestRid = 6,
      requestLoadLsId = 8,
      requestPeId = 2,
      requestStid = 3,
      requestTid = 4,
      dataValid = true,
      rawDataValid = true,
      dataMask = BigInt("ff", 16),
      data = BigInt("ddccbbaa", 16))
    val second = Response(
      clusterId = 0,
      entryId = 2,
      requestBid = 5,
      requestGid = 1,
      requestRid = 7,
      requestLoadLsId = 9,
      requestPeId = 5,
      requestStid = 6,
      requestTid = 7,
      waitStore = true,
      rawDataValid = true,
      dataSuppressedByWait = true,
      waitStoreIndex = 1,
      waitStoreBid = 5,
      waitStoreRid = 9,
      waitStoreLsId = 2,
      waitStorePc = BigInt("40005700", 16),
      dataMask = BigInt("0f", 16),
      data = BigInt("44332211", 16))

    val enqueueFirst = step(Vector.empty, depth = 2, enable = true, flush = false, enqueue = Some(first))
    assert(enqueueFirst.enqueueAccepted)
    assert(enqueueFirst.head.contains(first))
    assert(enqueueFirst.count == 1)

    val enqueueSecond = step(enqueueFirst.next, depth = 2, enable = true, flush = false, enqueue = Some(second))
    assert(enqueueSecond.enqueueAccepted)
    assert(enqueueSecond.head.contains(first))
    assert(enqueueSecond.full)

    val popFirst = step(enqueueSecond.next, depth = 2, enable = true, flush = false, dequeueReady = true)
    assert(popFirst.headConsumed)
    assert(popFirst.head.contains(first))
    assert(popFirst.head.exists(_.requestBid == 4))
    assert(popFirst.head.exists(_.requestLoadLsId == 8))
    assert(popFirst.head.exists(_.requestPeId == 2))
    assert(popFirst.head.exists(_.requestStid == 3))
    assert(popFirst.head.exists(_.requestTid == 4))
    assert(popFirst.head.exists(_.dataMask == BigInt("ff", 16)))
    assert(popFirst.count == 1)

    val popSecond = step(popFirst.next, depth = 2, enable = true, flush = false, dequeueReady = true)
    assert(popSecond.headConsumed)
    assert(popSecond.head.contains(second))
    assert(popSecond.head.exists(_.requestRid == 7))
    assert(popSecond.head.exists(_.requestPeId == 5))
    assert(popSecond.head.exists(_.requestStid == 6))
    assert(popSecond.head.exists(_.requestTid == 7))
    assert(popSecond.head.exists(_.waitStoreRid == 9))
    assert(popSecond.head.exists(_.dataSuppressedByWait))
    assert(popSecond.empty)
  }

  test("empty queue bypass can be consumed in the same cycle") {
    val response = Response(clusterId = 0, entryId = 3, dataValid = true)
    val result = step(
      state = Vector.empty,
      depth = 2,
      enable = true,
      flush = false,
      enqueue = Some(response),
      dequeueReady = true)

    assert(result.enqueueReady)
    assert(result.enqueueAccepted)
    assert(result.headValid)
    assert(result.headConsumed)
    assert(result.head.contains(response))
    assert(result.empty)
  }

  test("simultaneous resident dequeue opens a full queue slot") {
    val first = Response(clusterId = 0, entryId = 1, dataValid = true)
    val second = Response(clusterId = 0, entryId = 2, dataValid = true)

    val result = step(
      state = Vector(first),
      depth = 1,
      enable = true,
      flush = false,
      enqueue = Some(second),
      dequeueReady = true)

    assert(result.enqueueReady)
    assert(result.enqueueAccepted)
    assert(result.headConsumed)
    assert(result.head.contains(first))
    assert(result.next == Vector(second))
    assert(result.full)
  }

  test("full queue reports dropped one-cycle raw response") {
    val resident = Response(clusterId = 0, entryId = 1, dataValid = true)
    val dropped = Response(clusterId = 0, entryId = 2, waitStore = true)

    val result = step(
      state = Vector(resident),
      depth = 1,
      enable = true,
      flush = false,
      enqueue = Some(dropped),
      dequeueReady = false)

    assert(!result.enqueueReady)
    assert(!result.enqueueAccepted)
    assert(result.enqueueDropped)
    assert(result.blockedByFull)
    assert(result.next == Vector(resident))
  }

  test("flush clears queued responses and suppresses admission") {
    val resident = Response(clusterId = 0, entryId = 1, dataValid = true)
    val incoming = Response(clusterId = 0, entryId = 2, dataValid = true)

    val result = step(
      state = Vector(resident),
      depth = 2,
      enable = true,
      flush = true,
      enqueue = Some(incoming),
      dequeueReady = true)

    assert(!result.enqueueReady)
    assert(!result.enqueueAccepted)
    assert(!result.headValid)
    assert(result.blockedByFlush)
    assert(result.empty)
  }

  test("precise flush prunes matching responses and compacts survivors") {
    val oldSameThread = Response(
      clusterId = 0,
      entryId = 0,
      requestBid = 1,
      requestLoadLsId = 1,
      requestPeId = 1,
      requestStid = 2,
      requestTid = 3)
    val matchSameBid = Response(
      clusterId = 0,
      entryId = 1,
      requestBid = 1,
      requestLoadLsId = 2,
      requestPeId = 1,
      requestStid = 2,
      requestTid = 3)
    val matchNewerBid = Response(
      clusterId = 0,
      entryId = 2,
      requestBid = 2,
      requestLoadLsId = 0,
      requestPeId = 1,
      requestStid = 2,
      requestTid = 3)
    val otherStid = Response(
      clusterId = 0,
      entryId = 3,
      requestBid = 3,
      requestLoadLsId = 0,
      requestPeId = 1,
      requestStid = 1,
      requestTid = 3)
    val incoming = Response(clusterId = 0, entryId = 4)

    val result = step(
      state = Vector(oldSameThread, matchSameBid, matchNewerBid, otherStid),
      depth = 4,
      enable = true,
      flush = false,
      preciseFlush = Some(STQFlushPruneReference.Flush(
        stid = 2,
        peId = 1,
        tid = 3,
        bid = STQFlushPruneReference.Id(value = 1),
        lsId = STQFlushPruneReference.Id(value = 2),
        baseOnPE = true,
        baseOnThread = true)),
      enqueue = Some(incoming),
      dequeueReady = true)

    assert(!result.enqueueReady)
    assert(!result.enqueueAccepted)
    assert(result.enqueueDropped)
    assert(!result.headValid)
    assert(result.blockedByPreciseFlush)
    assert(result.precisePruneMask == 0x6)
    assert(result.precisePruneCount == 2)
    assert(result.next == Vector(oldSameThread, otherStid))
  }

  test("disabled queue diagnoses raw response without exposing a head") {
    val response = Response(clusterId = 0, entryId = 1, dataValid = true)
    val result = step(
      state = Vector.empty,
      depth = 2,
      enable = false,
      flush = false,
      enqueue = Some(response))

    assert(!result.enqueueReady)
    assert(!result.enqueueAccepted)
    assert(result.enqueueDropped)
    assert(result.blockedByDisabled)
    assert(!result.headValid)
    assert(result.empty)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotResponseQueue elaborates FIFO state") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplaySourceReturnStoreSnapshotResponseQueue(
        idEntries = 16,
        clusterIdWidth = 2,
        entryIdWidth = 4,
        depth = 2
      ))

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotResponseQueue"))
    assert(sv.contains("io_enqueueAccepted"))
    assert(sv.contains("io_preciseFlush_req_valid"))
    assert(sv.contains("io_precisePruneMask"))
    assert(sv.contains("io_precisePruneCount"))
    assert(sv.contains("io_headValid"))
    assert(sv.contains("io_headConsumed"))
    assert(sv.contains("io_headRequestBid"))
    assert(sv.contains("io_headRequestLoadLsId"))
    assert(sv.contains("io_headRequestPeId"))
    assert(sv.contains("io_headRequestStid"))
    assert(sv.contains("io_headRequestTid"))
    assert(sv.contains("io_headWaitStoreRid"))
    assert(sv.contains("io_headDataMask"))
    assert(sv.contains("io_blockedByPreciseFlush"))
    assert(sv.contains("io_blockedByFull"))
  }
}
