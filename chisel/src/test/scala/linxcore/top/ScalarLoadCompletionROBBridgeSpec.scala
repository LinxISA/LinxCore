package linxcore.top

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ScalarLoadCompletionROBBridgeReference {
  final case class Result(
      loadReady: Boolean,
      robComplete: Option[Int],
      loadSelected: Boolean,
      collision: Boolean,
      sameRowCollision: Boolean)

  def arbitrate(
      external: Option[Int],
      loadCandidate: Option[Int],
      loadResolveEnable: Boolean,
      exactCompleteReady: Boolean): Result = {
    val loadReady = loadResolveEnable && external.isEmpty && exactCompleteReady
    val selected = loadCandidate.nonEmpty && loadReady
    Result(
      loadReady = loadReady,
      robComplete = external.orElse(if (selected) loadCandidate else None),
      loadSelected = selected,
      collision = external.nonEmpty && loadCandidate.nonEmpty,
      sameRowCollision = external.nonEmpty && external == loadCandidate
    )
  }
}

class ScalarLoadCompletionROBBridgeSpec extends AnyFunSuite {
  import ScalarLoadCompletionROBBridgeReference._

  test("external completion has priority and holds a colliding scalar load") {
    val collision = arbitrate(Some(1), Some(2), loadResolveEnable = true, exactCompleteReady = true)
    assert(!collision.loadReady)
    assert(!collision.loadSelected)
    assert(collision.robComplete.contains(1))
    assert(collision.collision)
    assert(!collision.sameRowCollision)

    val retry = arbitrate(None, Some(2), loadResolveEnable = true, exactCompleteReady = true)
    assert(retry.loadReady && retry.loadSelected)
    assert(retry.robComplete.contains(2))
  }

  test("same-row external and scalar completion is a duplicate-owner violation") {
    val result = arbitrate(Some(3), Some(3), loadResolveEnable = true, exactCompleteReady = true)
    assert(!result.loadReady)
    assert(!result.loadSelected)
    assert(result.robComplete.contains(3))
    assert(result.collision && result.sameRowCollision)
  }

  test("disabled resolve ownership prevents scalar load completion") {
    val result = arbitrate(None, Some(3), loadResolveEnable = false, exactCompleteReady = true)
    assert(!result.loadReady)
    assert(!result.loadSelected)
    assert(result.robComplete.isEmpty)
  }

  test("stale or free exact RID holds scalar W2 completion") {
    val result = arbitrate(None, Some(3), loadResolveEnable = true, exactCompleteReady = false)
    assert(!result.loadReady)
    assert(!result.loadSelected)
    assert(result.robComplete.isEmpty)
  }

  test("bridge elaborates exact lookup and one shared completion sink") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarLoadCompletionROBBridge(entries = 8))
    assert(sv.contains("module ScalarLoadCompletionROBBridge"))
    assert(sv.contains("io_loadLookupRid_wrap"))
    assert(sv.contains("io_robLookupRowValid"))
    assert(sv.contains("io_loadResolveReady"))
    assert(sv.contains("io_robExactCompleteReady"))
    assert(sv.contains("io_robExactCompleteRid_wrap"))
    assert(sv.contains("io_robCompleteRobValue"))
    assert(sv.contains("io_sameRowCollision"))
    assert(sv.contains("io_protocolError"))
  }
}
