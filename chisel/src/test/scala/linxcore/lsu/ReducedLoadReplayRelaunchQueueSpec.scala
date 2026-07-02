package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ReducedLoadReplayRelaunchQueueReference {
  import ReducedLoadWaitReplaySlotReference.{Id, Relaunch}

  final case class Step(
      enqueueReady: Boolean,
      enqueueAccepted: Boolean,
      enqueueDropped: Boolean,
      outValid: Boolean,
      out: Option[Relaunch],
      outFire: Boolean,
      pending: Boolean,
      full: Boolean,
      empty: Boolean,
      count: Int)

  final class Model(depth: Int) {
    private var queue = Vector.empty[Relaunch]

    def step(
        flush: Boolean = false,
        enqueue: Option[Relaunch] = None,
        outReady: Boolean = false): Step = {
      val oldHead = if (flush) None else queue.headOption
      val outValid = oldHead.isDefined
      val outFire = outValid && outReady
      val enqueueReady = !flush && (queue.size < depth || outFire)
      val enqueueAccepted = enqueue.isDefined && enqueueReady
      val enqueueDropped = enqueue.isDefined && !flush && !enqueueReady

      val next =
        if (flush) {
          Vector.empty[Relaunch]
        } else {
          val afterPop = if (outFire) queue.drop(1) else queue
          if (enqueueAccepted) afterPop :+ enqueue.get else afterPop
        }
      queue = next

      Step(
        enqueueReady = enqueueReady,
        enqueueAccepted = enqueueAccepted,
        enqueueDropped = enqueueDropped,
        outValid = outValid,
        out = oldHead,
        outFire = outFire,
        pending = queue.nonEmpty,
        full = queue.size == depth,
        empty = queue.isEmpty,
        count = queue.size)
    }
  }

  def id(value: Int, wrap: Boolean = false): Id =
    Id(valid = true, wrap = wrap, value = value)

  def relaunch(pc: BigInt, addr: BigInt, lsId: Int): Relaunch =
    Relaunch(
      pc = pc,
      addr = addr,
      size = 8,
      returnSignExtend = lsId == 1,
      bid = id(6),
      lsId = id(lsId),
      gid = id(2),
      rid = id(7),
      youngestStoreId = id(6),
      youngestStoreLsId = id(lsId))
}

class ReducedLoadReplayRelaunchQueueSpec extends AnyFunSuite {
  import ReducedLoadReplayRelaunchQueueReference._

  test("stores relaunch candidates in FIFO order") {
    val model = new Model(depth = 2)
    val first = relaunch(pc = 0x4000, addr = 0x1000, lsId = 1)
    val second = relaunch(pc = 0x4010, addr = 0x1080, lsId = 2)

    val enqueueFirst = model.step(enqueue = Some(first))
    assert(enqueueFirst.enqueueAccepted)
    assert(!enqueueFirst.outValid)
    assert(enqueueFirst.pending)

    val enqueueSecond = model.step(enqueue = Some(second))
    assert(enqueueSecond.enqueueAccepted)
    assert(enqueueSecond.out.contains(first))
    assert(enqueueSecond.full)

    val popFirst = model.step(outReady = true)
    assert(popFirst.outFire)
    assert(popFirst.out.contains(first))
    assert(popFirst.out.exists(_.returnSignExtend))
    assert(popFirst.count == 1)

    val popSecond = model.step(outReady = true)
    assert(popSecond.outFire)
    assert(popSecond.out.contains(second))
    assert(popSecond.out.exists(!_.returnSignExtend))
    assert(popSecond.empty)
  }

  test("simultaneous dequeue opens space for a new relaunch candidate") {
    val model = new Model(depth = 1)
    val first = relaunch(pc = 0x4000, addr = 0x1000, lsId = 1)
    val second = relaunch(pc = 0x4010, addr = 0x1080, lsId = 2)

    assert(model.step(enqueue = Some(first)).enqueueAccepted)
    val simultaneous = model.step(enqueue = Some(second), outReady = true)

    assert(simultaneous.enqueueReady)
    assert(simultaneous.enqueueAccepted)
    assert(!simultaneous.enqueueDropped)
    assert(simultaneous.outFire)
    assert(simultaneous.out.contains(first))
    assert(simultaneous.full)

    val next = model.step()
    assert(next.out.contains(second))
  }

  test("drops a one-cycle candidate when the queue is full and not drained") {
    val model = new Model(depth = 1)
    val first = relaunch(pc = 0x4000, addr = 0x1000, lsId = 1)
    val second = relaunch(pc = 0x4010, addr = 0x1080, lsId = 2)

    assert(model.step(enqueue = Some(first)).enqueueAccepted)
    val dropped = model.step(enqueue = Some(second))

    assert(!dropped.enqueueReady)
    assert(!dropped.enqueueAccepted)
    assert(dropped.enqueueDropped)
    assert(dropped.out.contains(first))
    assert(dropped.full)
  }

  test("flush clears queued relaunch candidates and suppresses admission") {
    val model = new Model(depth = 2)
    val first = relaunch(pc = 0x4000, addr = 0x1000, lsId = 1)
    val second = relaunch(pc = 0x4010, addr = 0x1080, lsId = 2)

    assert(model.step(enqueue = Some(first)).enqueueAccepted)
    val flushed = model.step(flush = true, enqueue = Some(second), outReady = true)

    assert(!flushed.enqueueReady)
    assert(!flushed.enqueueAccepted)
    assert(!flushed.enqueueDropped)
    assert(!flushed.outValid)
    assert(flushed.empty)
  }

  test("Chisel ReducedLoadReplayRelaunchQueue elaborates with queue diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedLoadReplayRelaunchQueue(idEntries = 8, depth = 2))

    assert(sv.contains("module ReducedLoadReplayRelaunchQueue"))
    assert(sv.contains("io_enqueueAccepted"))
    assert(sv.contains("io_enqueueDropped"))
    assert(sv.contains("io_out_valid"))
    assert(sv.contains("io_out_returnSignExtend"))
    assert(sv.contains("io_out_gid_value"))
    assert(sv.contains("io_out_rid_value"))
    assert(sv.contains("io_out_loadLsId_value"))
    assert(sv.contains("io_out_youngestStoreId_value"))
    assert(sv.contains("io_out_youngestStoreLsId_value"))
    assert(sv.contains("io_count"))
  }
}
