package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotRequestQueueReference {
  import LoadReplaySourceReturnStoreSnapshotRequestPayloadReference.Payload

  final case class Result(
      enqueueReady: Boolean,
      enqueueAccepted: Boolean,
      enqueueDropped: Boolean,
      headValid: Boolean,
      head: Option[Payload],
      headConsumed: Boolean,
      pending: Boolean,
      full: Boolean,
      empty: Boolean,
      count: Int,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByFull: Boolean,
      next: Vector[Payload])

  def step(
      state: Vector[Payload],
      depth: Int,
      enable: Boolean,
      flush: Boolean,
      enqueue: Option[Payload] = None,
      dequeueReady: Boolean = false): Result = {
    val active = enable && !flush
    val residentHead = state.headOption
    val popResident = active && residentHead.isDefined && dequeueReady
    val enqueueReady = active && (state.size < depth || popResident)
    val enqueueAccepted = enqueue.isDefined && enqueueReady
    val bypassHead = active && residentHead.isEmpty && enqueue.isDefined && state.size < depth
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

class LoadReplaySourceReturnStoreSnapshotRequestQueueSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotRequestPayloadReference.Payload
  import LoadReplaySourceReturnStoreSnapshotRequestQueueReference._

  private def request(idx: Int): Payload =
    Payload(
      valid = true,
      clusterId = 0,
      entryId = idx,
      loadId = idx,
      bid = 4 + idx,
      gid = 1,
      rid = 8 + idx,
      loadLsId = 16 + idx,
      peId = 2,
      stid = 3,
      tid = 4,
      pc = BigInt("400055f0", 16) + (idx * 4),
      addr = BigInt("40012000", 16) + (idx * 8),
      size = 8,
      requestByteMask = BigInt("ff", 16) << (idx * 8))

  test("stores selected replay requests in FIFO order") {
    val first = request(1)
    val second = request(2)

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
    assert(popFirst.head.exists(_.peId == 2))
    assert(popFirst.head.exists(_.stid == 3))
    assert(popFirst.head.exists(_.tid == 4))
    assert(popFirst.count == 1)

    val popSecond = step(popFirst.next, depth = 2, enable = true, flush = false, dequeueReady = true)
    assert(popSecond.headConsumed)
    assert(popSecond.head.contains(second))
    assert(popSecond.empty)
  }

  test("empty request queue can bypass and drain in the same cycle") {
    val req = request(3)
    val result = step(
      state = Vector.empty,
      depth = 2,
      enable = true,
      flush = false,
      enqueue = Some(req),
      dequeueReady = true)

    assert(result.enqueueReady)
    assert(result.enqueueAccepted)
    assert(result.headValid)
    assert(result.headConsumed)
    assert(result.head.contains(req))
    assert(result.empty)
  }

  test("simultaneous resident drain opens a full request slot") {
    val first = request(1)
    val second = request(2)

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

  test("full queue reports dropped replay request") {
    val resident = request(1)
    val dropped = request(2)

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

  test("flush and disabled states suppress request admission") {
    val resident = request(1)
    val incoming = request(2)

    val flushed = step(
      state = Vector(resident),
      depth = 2,
      enable = true,
      flush = true,
      enqueue = Some(incoming),
      dequeueReady = true)
    val disabled = step(
      state = Vector.empty,
      depth = 2,
      enable = false,
      flush = false,
      enqueue = Some(incoming))

    assert(!flushed.enqueueReady)
    assert(!flushed.enqueueAccepted)
    assert(!flushed.headValid)
    assert(flushed.blockedByFlush)
    assert(flushed.empty)
    assert(!disabled.enqueueReady)
    assert(!disabled.enqueueAccepted)
    assert(disabled.enqueueDropped)
    assert(disabled.blockedByDisabled)
    assert(!disabled.headValid)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotRequestQueue elaborates FIFO payload state") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotRequestQueue(depth = 2))

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotRequestQueue"))
    assert(sv.contains("io_enqueueAccepted"))
    assert(sv.contains("io_head_peId"))
    assert(sv.contains("io_head_stid"))
    assert(sv.contains("io_head_tid"))
    assert(sv.contains("io_head_valid"))
    assert(sv.contains("io_headConsumed"))
    assert(sv.contains("io_blockedByFull"))
  }
}
