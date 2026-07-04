package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnReducedScalarShapeControlReference {
  final case class Result(
      active: Boolean,
      reducedScalarShapeValid: Boolean,
      reducedSingleLane: Boolean,
      scalarLoadPair: Boolean,
      vectorOrMemMultiLane: Boolean,
      retLaneBefore: Int,
      returnedLaneCount: Int,
      realReqCnt: Int,
      isMemIex: Boolean,
      isTload: Boolean,
      subInstCntBefore: Int,
      isVectorMachine: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean)

  def apply(enable: Boolean, flush: Boolean): Result = {
    val active = enable && !flush

    Result(
      active = active,
      reducedScalarShapeValid = active,
      reducedSingleLane = true,
      scalarLoadPair = false,
      vectorOrMemMultiLane = false,
      retLaneBefore = 0,
      returnedLaneCount = 1,
      realReqCnt = 1,
      isMemIex = false,
      isTload = false,
      subInstCntBefore = 0,
      isVectorMachine = false,
      blockedByDisabled = !enable,
      blockedByFlush = enable && flush)
  }
}

class LoadReplayReturnReducedScalarShapeControlSpec extends AnyFunSuite {
  import LoadReplayReturnReducedScalarShapeControlReference._

  test("publishes the ordinary scalar one-lane non-TLOAD shape when active") {
    val result = LoadReplayReturnReducedScalarShapeControlReference(
      enable = true,
      flush = false)

    assert(result.active)
    assert(result.reducedScalarShapeValid)
    assert(result.reducedSingleLane)
    assert(!result.scalarLoadPair)
    assert(!result.vectorOrMemMultiLane)
    assert(result.retLaneBefore == 0)
    assert(result.returnedLaneCount == 1)
    assert(result.realReqCnt == 1)
    assert(!result.isMemIex)
    assert(!result.isTload)
    assert(result.subInstCntBefore == 0)
    assert(!result.isVectorMachine)
  }

  test("reports disabled and flush blockers without changing shape constants") {
    val disabled = LoadReplayReturnReducedScalarShapeControlReference(
      enable = false,
      flush = false)
    assert(disabled.blockedByDisabled)
    assert(!disabled.active)
    assert(!disabled.reducedScalarShapeValid)
    assert(disabled.reducedSingleLane)
    assert(disabled.returnedLaneCount == 1)
    assert(disabled.realReqCnt == 1)

    val flushed = LoadReplayReturnReducedScalarShapeControlReference(
      enable = true,
      flush = true)
    assert(flushed.blockedByFlush)
    assert(!flushed.active)
    assert(!flushed.reducedScalarShapeValid)
    assert(flushed.reducedSingleLane)
    assert(!flushed.isVectorMachine)
  }

  test("Chisel LoadReplayReturnReducedScalarShapeControl elaborates shape diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnReducedScalarShapeControl(countWidth = 8))

    assert(sv.contains("module LoadReplayReturnReducedScalarShapeControl"))
    assert(sv.contains("io_reducedScalarShapeValid"))
    assert(sv.contains("io_reducedSingleLane"))
    assert(sv.contains("io_scalarLoadPair"))
    assert(sv.contains("io_vectorOrMemMultiLane"))
    assert(sv.contains("io_isMemIex"))
    assert(sv.contains("io_isVectorMachine"))
  }
}
