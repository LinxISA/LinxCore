package linxcore.backend

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedRobCompletionArbiterReference {
  final case class Result(
      completeValid: Boolean,
      completeRobValue: Int,
      completeRowValid: Boolean,
      selectedExecute: Boolean,
      selectedReplay: Boolean,
      replayBlockedByExecute: Boolean)

  def apply(
      executeCompleteValid: Boolean,
      executeCompleteRobValue: Int,
      executeCompleteRowValid: Boolean,
      replayCompleteValid: Boolean,
      replayCompleteRobValue: Int,
      replayCompleteRowValid: Boolean): Result = {
    val selectedExecute = executeCompleteValid
    val selectedReplay = replayCompleteValid && !executeCompleteValid

    Result(
      completeValid = selectedExecute || selectedReplay,
      completeRobValue =
        if (selectedExecute) executeCompleteRobValue else if (selectedReplay) replayCompleteRobValue else 0,
      completeRowValid =
        if (selectedExecute) executeCompleteRowValid else if (selectedReplay) replayCompleteRowValid else false,
      selectedExecute = selectedExecute,
      selectedReplay = selectedReplay,
      replayBlockedByExecute = replayCompleteValid && executeCompleteValid)
  }
}

class ReducedRobCompletionArbiterSpec extends AnyFunSuite {
  import ReducedRobCompletionArbiterReference._

  test("selects execute completion with priority over replay") {
    val result = ReducedRobCompletionArbiterReference(
      executeCompleteValid = true,
      executeCompleteRobValue = 2,
      executeCompleteRowValid = true,
      replayCompleteValid = true,
      replayCompleteRobValue = 5,
      replayCompleteRowValid = false)

    assert(result.completeValid)
    assert(result.completeRobValue == 2)
    assert(result.completeRowValid)
    assert(result.selectedExecute)
    assert(!result.selectedReplay)
    assert(result.replayBlockedByExecute)
  }

  test("selects replay completion when execute is idle") {
    val result = ReducedRobCompletionArbiterReference(
      executeCompleteValid = false,
      executeCompleteRobValue = 2,
      executeCompleteRowValid = true,
      replayCompleteValid = true,
      replayCompleteRobValue = 5,
      replayCompleteRowValid = false)

    assert(result.completeValid)
    assert(result.completeRobValue == 5)
    assert(!result.completeRowValid)
    assert(!result.selectedExecute)
    assert(result.selectedReplay)
    assert(!result.replayBlockedByExecute)
  }

  test("suppresses stale fields when both sources are idle") {
    val result = ReducedRobCompletionArbiterReference(
      executeCompleteValid = false,
      executeCompleteRobValue = 2,
      executeCompleteRowValid = true,
      replayCompleteValid = false,
      replayCompleteRobValue = 5,
      replayCompleteRowValid = true)

    assert(!result.completeValid)
    assert(result.completeRobValue == 0)
    assert(!result.completeRowValid)
    assert(!result.selectedExecute)
    assert(!result.selectedReplay)
  }

  test("Chisel ReducedRobCompletionArbiter elaborates source arbitration") {
    val sv = ChiselStage.emitSystemVerilog(
      new ReducedRobCompletionArbiter(
        ptrWidth = 3,
        traceParams = CommitTraceParams(robValueWidth = 3)))

    assert(sv.contains("module ReducedRobCompletionArbiter"))
    assert(sv.contains("io_executeCompleteValid"))
    assert(sv.contains("io_replayCompleteValid"))
    assert(sv.contains("io_selectedExecute"))
    assert(sv.contains("io_selectedReplay"))
    assert(sv.contains("io_replayBlockedByExecute"))
  }
}
