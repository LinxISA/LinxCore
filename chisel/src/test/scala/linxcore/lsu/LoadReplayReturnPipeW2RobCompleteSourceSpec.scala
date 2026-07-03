package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RobCompleteSourceReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)

  final case class Result(
      sinkReady: Boolean,
      active: Boolean,
      candidateValid: Boolean,
      completeValid: Boolean,
      completeRobValue: Int,
      completeRowValid: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoResolve: Boolean,
      blockedByInvalidRid: Boolean,
      blockedByExecute: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      resolveValid: Boolean,
      resolveRid: Id,
      executeCompleteValid: Boolean,
      completeRowInputValid: Boolean = false): Result = {
    val active = enable && !flush
    val candidateValid = active && resolveValid
    val legalCandidate = candidateValid && resolveRid.valid
    val sinkReady = !executeCompleteValid
    val completeValid = legalCandidate && sinkReady

    Result(
      sinkReady = sinkReady,
      active = active,
      candidateValid = candidateValid,
      completeValid = completeValid,
      completeRobValue = if (completeValid) resolveRid.value else 0,
      completeRowValid = completeValid && completeRowInputValid,
      blockedByDisabled = !enable && resolveValid,
      blockedByFlush = enable && flush && resolveValid,
      blockedByNoResolve = active && !resolveValid,
      blockedByInvalidRid = candidateValid && !resolveRid.valid,
      blockedByExecute = legalCandidate && executeCompleteValid)
  }
}

class LoadReplayReturnPipeW2RobCompleteSourceSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RobCompleteSourceReference._

  test("emits a ROB completion for a live resolve when execute is idle") {
    val result = LoadReplayReturnPipeW2RobCompleteSourceReference(
      enable = true,
      flush = false,
      resolveValid = true,
      resolveRid = Id(valid = true, wrap = false, value = 5),
      executeCompleteValid = false)

    assert(result.sinkReady)
    assert(result.active)
    assert(result.candidateValid)
    assert(result.completeValid)
    assert(result.completeRobValue == 5)
    assert(!result.completeRowValid)
    assert(!result.blockedByExecute)
  }

  test("reports execute-port pressure without emitting replay completion") {
    val result = LoadReplayReturnPipeW2RobCompleteSourceReference(
      enable = true,
      flush = false,
      resolveValid = true,
      resolveRid = Id(valid = true, value = 6),
      executeCompleteValid = true)

    assert(!result.sinkReady)
    assert(result.candidateValid)
    assert(!result.completeValid)
    assert(result.completeRobValue == 0)
    assert(result.blockedByExecute)
  }

  test("passes a row-fill input only with an emitted replay completion") {
    val emitted = LoadReplayReturnPipeW2RobCompleteSourceReference(
      enable = true,
      flush = false,
      resolveValid = true,
      resolveRid = Id(valid = true, value = 4),
      executeCompleteValid = false,
      completeRowInputValid = true)
    val blocked = LoadReplayReturnPipeW2RobCompleteSourceReference(
      enable = true,
      flush = false,
      resolveValid = true,
      resolveRid = Id(valid = true, value = 4),
      executeCompleteValid = true,
      completeRowInputValid = true)

    assert(emitted.completeValid)
    assert(emitted.completeRowValid)
    assert(!blocked.completeValid)
    assert(!blocked.completeRowValid)
  }

  test("suppresses disabled, flushed, and invalid-RID candidates") {
    val disabled = LoadReplayReturnPipeW2RobCompleteSourceReference(
      enable = false,
      flush = false,
      resolveValid = true,
      resolveRid = Id(value = 1),
      executeCompleteValid = false)
    val flushed = LoadReplayReturnPipeW2RobCompleteSourceReference(
      enable = true,
      flush = true,
      resolveValid = true,
      resolveRid = Id(value = 1),
      executeCompleteValid = false)
    val invalidRid = LoadReplayReturnPipeW2RobCompleteSourceReference(
      enable = true,
      flush = false,
      resolveValid = true,
      resolveRid = Id(valid = false, value = 1),
      executeCompleteValid = false)

    assert(disabled.blockedByDisabled)
    assert(!disabled.completeValid)
    assert(flushed.blockedByFlush)
    assert(!flushed.completeValid)
    assert(invalidRid.candidateValid)
    assert(invalidRid.blockedByInvalidRid)
    assert(!invalidRid.completeValid)
  }

  test("reports an active cycle with no live resolve") {
    val result = LoadReplayReturnPipeW2RobCompleteSourceReference(
      enable = true,
      flush = false,
      resolveValid = false,
      resolveRid = Id(value = 1),
      executeCompleteValid = false)

    assert(result.active)
    assert(!result.candidateValid)
    assert(!result.completeValid)
    assert(result.blockedByNoResolve)
  }

  test("Chisel LoadReplayReturnPipeW2RobCompleteSource elaborates completion fields") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RobCompleteSource(
        idEntries = 8,
        traceParams = CommitTraceParams(robValueWidth = 3)))

    assert(sv.contains("module LoadReplayReturnPipeW2RobCompleteSource"))
    assert(sv.contains("io_resolveValid"))
    assert(sv.contains("io_executeCompleteValid"))
    assert(sv.contains("io_completeRowInputValid"))
    assert(sv.contains("io_completeValid"))
    assert(sv.contains("io_completeRobValue"))
    assert(sv.contains("io_blockedByExecute"))
  }
}
