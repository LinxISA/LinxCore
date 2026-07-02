package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedStoreExecResultBridgeReference {
  final case class Key(bid: Int, rid: Int, stid: Int = 0)
  final case class Result(key: Key, addr: BigInt, data: BigInt, size: Int)
  final case class Head(key: Key, splitHalf: Boolean = false)
  final case class StepResult(
      staMatch: Boolean,
      stdMatch: Boolean,
      captureFire: Boolean,
      captureBlocked: Boolean,
      pending: Vector[Result])

  final class Model(depth: Int) {
    private var pending = Vector.empty[Result]

    def step(
        complete: Option[Result] = None,
        staHead: Option[Head] = None,
        stdHead: Option[Head] = None,
        staConsumed: Boolean = false,
        stdConsumed: Boolean = false): StepResult = {
      val staMatch = staHead.exists(head => pending.exists(_.key == head.key))
      val stdMatch = stdHead.exists(head => pending.exists(_.key == head.key))
      val afterConsume = pending.filterNot { result =>
        val staClears = staConsumed && staHead.exists(head => head.key == result.key && !head.splitHalf)
        val stdClears = stdConsumed && stdHead.exists(_.key == result.key)
        staClears || stdClears
      }
      val duplicate = complete.exists(result => afterConsume.exists(_.key == result.key))
      val captureFire = complete.nonEmpty && !duplicate && afterConsume.size < depth
      val captureBlocked = complete.nonEmpty && !duplicate && afterConsume.size >= depth
      pending = if (captureFire) afterConsume :+ complete.get else afterConsume
      StepResult(staMatch, stdMatch, captureFire, captureBlocked, pending)
    }
  }
}

class ReducedStoreExecResultBridgeSpec extends AnyFunSuite {
  import ReducedStoreExecResultBridgeReference._

  test("reference captures stores and only matches the current STA or STD queue head") {
    val model = new Model(depth = 4)
    val store0 = Result(Key(bid = 1, rid = 3), addr = 0x1000, data = 0x1234, size = 8)

    val captured = model.step(complete = Some(store0))
    assert(captured.captureFire)
    assert(captured.pending.map(_.key) == Vector(store0.key))

    val miss = model.step(staHead = Some(Head(Key(bid = 1, rid = 4))))
    assert(!miss.staMatch)

    val hit = model.step(staHead = Some(Head(store0.key)))
    assert(hit.staMatch)
    assert(!hit.stdMatch)
  }

  test("reference keeps split-store data after STA and releases it after matching STD") {
    val model = new Model(depth = 4)
    val split = Result(Key(bid = 2, rid = 5), addr = 0x2080, data = 0xaabb, size = 4)

    assert(model.step(complete = Some(split)).captureFire)
    val sta = model.step(staHead = Some(Head(split.key, splitHalf = true)), staConsumed = true)
    assert(sta.staMatch)
    assert(sta.pending.map(_.key) == Vector(split.key))

    val std = model.step(stdHead = Some(Head(split.key, splitHalf = true)), stdConsumed = true)
    assert(std.stdMatch)
    assert(std.pending.isEmpty)
  }

  test("reference releases an unsplit store after STA consumption") {
    val model = new Model(depth = 4)
    val unsplit = Result(Key(bid = 4, rid = 1), addr = 0x3000, data = 0x55, size = 1)

    assert(model.step(complete = Some(unsplit)).captureFire)
    val consumed = model.step(staHead = Some(Head(unsplit.key)), staConsumed = true)
    assert(consumed.staMatch)
    assert(consumed.pending.isEmpty)
  }

  test("reference reports overflow when no buffered result slot is free") {
    val model = new Model(depth = 2)

    assert(model.step(complete = Some(Result(Key(0, 0), 0x1000, 0, 8))).captureFire)
    assert(model.step(complete = Some(Result(Key(0, 1), 0x1008, 1, 8))).captureFire)
    val blocked = model.step(complete = Some(Result(Key(0, 2), 0x1010, 2, 8)))
    assert(blocked.captureBlocked)
    assert(blocked.pending.map(_.key) == Vector(Key(0, 0), Key(0, 1)))
  }

  test("ReducedStoreExecResultBridge elaborates matched STA and STD result outputs") {
    val sv = ChiselStage.emitSystemVerilog(
      new ReducedStoreExecResultBridge(
        p = InterfaceParams(robEntries = 8),
        traceParams = CommitTraceParams(commitWidth = 2, robValueWidth = 3),
        bufferEntries = 4,
        mapQDepth = 8)
    )

    assert(sv.contains("module ReducedStoreExecResultBridge"))
    assert(sv.contains("io_completeStoreValid"))
    assert(sv.contains("io_captureFire"))
    assert(sv.contains("io_captureBlocked"))
    assert(sv.contains("io_captureDuplicate"))
    assert(sv.contains("io_staExec_valid"))
    assert(sv.contains("io_stdExec_valid"))
    assert(sv.contains("io_staMatch"))
    assert(sv.contains("io_stdMatch"))
    assert(sv.contains("io_bufferCount"))
  }
}
