package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotResponseQueueReference {
  final case class Response(
      clusterId: Int = 0,
      entryId: Int = 0,
      waitStore: Boolean = false,
      dataValid: Boolean = false)

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
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByFull: Boolean,
      next: Vector[Response])

  def step(
      state: Vector[Response],
      depth: Int,
      enable: Boolean,
      flush: Boolean,
      enqueue: Option[Response] = None,
      dequeueReady: Boolean = false): Result = {
    val active = enable && !flush
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
    val next =
      if (flush) {
        Vector.empty
      } else {
        val afterPop = if (popResident) state.drop(1) else state
        if (storeEnqueue) afterPop :+ enqueue.get else afterPop
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
      blockedByDisabled = !enable && enqueue.isDefined,
      blockedByFlush = enable && flush && enqueue.isDefined,
      blockedByFull = enable && !flush && enqueue.isDefined && !enqueueReady,
      next = next)
  }
}

class LoadReplaySourceReturnStoreSnapshotResponseQueueSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotResponseQueueReference._

  test("stores raw STQ responses in FIFO order") {
    val first = Response(clusterId = 0, entryId = 1, dataValid = true)
    val second = Response(clusterId = 0, entryId = 2, waitStore = true)

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
    assert(popFirst.count == 1)

    val popSecond = step(popFirst.next, depth = 2, enable = true, flush = false, dequeueReady = true)
    assert(popSecond.headConsumed)
    assert(popSecond.head.contains(second))
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
        clusterIdWidth = 2,
        entryIdWidth = 4,
        depth = 2
      ))

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotResponseQueue"))
    assert(sv.contains("io_enqueueAccepted"))
    assert(sv.contains("io_headValid"))
    assert(sv.contains("io_headConsumed"))
    assert(sv.contains("io_blockedByFull"))
  }
}
