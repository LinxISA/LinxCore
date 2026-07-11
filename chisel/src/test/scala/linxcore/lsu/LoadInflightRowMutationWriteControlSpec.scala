package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadInflightRowMutationWriteControlReference {
  final case class Conflicts(
      e4Update: Boolean = false,
      clearResolved: Boolean = false,
      replayWake: Boolean = false,
      refill: Boolean = false,
      launch: Boolean = false,
      allocation: Boolean = false)

  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      targetEvidenceValid: Boolean,
      writeConflict: Boolean,
      writeEnable: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoRequest: Boolean,
      blockedByInvalidRow: Boolean,
      blockedByNotRepick: Boolean,
      blockedByScbNotReturned: Boolean,
      blockedByE4UpdateConflict: Boolean,
      blockedByClearResolvedConflict: Boolean,
      blockedByReplayWakeConflict: Boolean,
      blockedByRefillConflict: Boolean,
      blockedByLaunchConflict: Boolean,
      blockedByAllocationConflict: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      requestValid: Boolean,
      targetRowValid: Boolean,
      targetRowRepick: Boolean,
      targetScbReturned: Boolean,
      targetRowWait: Boolean = false,
      allowWaitTarget: Boolean = false,
      requireScbReturned: Boolean = true,
      conflicts: Conflicts = Conflicts()): Result = {
    val active = enable && !flush
    val requestActive = active && requestValid
    val targetStatusValid = targetRowRepick || (allowWaitTarget && targetRowWait)
    val targetReturnEvidenceValid = !requireScbReturned || targetScbReturned
    val targetEvidenceValid = targetRowValid && targetStatusValid && targetReturnEvidenceValid
    val writeConflict =
      conflicts.e4Update ||
        conflicts.clearResolved ||
        conflicts.replayWake ||
        conflicts.refill ||
        conflicts.launch ||
        conflicts.allocation

    Result(
      active = active,
      requestActive = requestActive,
      targetEvidenceValid = targetEvidenceValid,
      writeConflict = writeConflict,
      writeEnable = requestActive && targetEvidenceValid && !writeConflict,
      blockedByDisabled = !enable && requestValid,
      blockedByFlush = enable && flush && requestValid,
      blockedByNoRequest = active && !requestValid,
      blockedByInvalidRow = requestActive && !targetRowValid,
      blockedByNotRepick = requestActive && targetRowValid && !targetStatusValid,
      blockedByScbNotReturned = requestActive && targetRowValid && targetStatusValid && !targetReturnEvidenceValid,
      blockedByE4UpdateConflict = requestActive && conflicts.e4Update,
      blockedByClearResolvedConflict = requestActive && conflicts.clearResolved,
      blockedByReplayWakeConflict = requestActive && conflicts.replayWake,
      blockedByRefillConflict = requestActive && conflicts.refill,
      blockedByLaunchConflict = requestActive && conflicts.launch,
      blockedByAllocationConflict = requestActive && conflicts.allocation)
  }
}

class LoadInflightRowMutationWriteControlSpec extends AnyFunSuite {
  import LoadInflightRowMutationWriteControlReference._

  test("enables a native mutation write for a valid repick row with SCB returned") {
    val result = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = false,
      requestValid = true,
      targetRowValid = true,
      targetRowRepick = true,
      targetScbReturned = true)

    assert(result.active)
    assert(result.requestActive)
    assert(result.targetEvidenceValid)
    assert(!result.writeConflict)
    assert(result.writeEnable)
  }

  test("blocks missing rows non-repick rows and rows without prior SCB return") {
    val invalidRow = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = false,
      requestValid = true,
      targetRowValid = false,
      targetRowRepick = false,
      targetScbReturned = false)
    val waitRow = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = false,
      requestValid = true,
      targetRowValid = true,
      targetRowRepick = false,
      targetScbReturned = true)
    val noScb = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = false,
      requestValid = true,
      targetRowValid = true,
      targetRowRepick = true,
      targetScbReturned = false)

    assert(invalidRow.blockedByInvalidRow)
    assert(waitRow.blockedByNotRepick)
    assert(noScb.blockedByScbNotReturned)
    assert(!invalidRow.writeEnable)
    assert(!waitRow.writeEnable)
    assert(!noScb.writeEnable)
  }

  test("blocks every same-cycle writer conflict independently") {
    val e4 = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = false,
      requestValid = true,
      targetRowValid = true,
      targetRowRepick = true,
      targetScbReturned = true,
      conflicts = Conflicts(e4Update = true))
    val clear = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = false,
      requestValid = true,
      targetRowValid = true,
      targetRowRepick = true,
      targetScbReturned = true,
      conflicts = Conflicts(clearResolved = true))
    val replay = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = false,
      requestValid = true,
      targetRowValid = true,
      targetRowRepick = true,
      targetScbReturned = true,
      conflicts = Conflicts(replayWake = true))
    val refill = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = false,
      requestValid = true,
      targetRowValid = true,
      targetRowRepick = true,
      targetScbReturned = true,
      conflicts = Conflicts(refill = true))
    val launch = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = false,
      requestValid = true,
      targetRowValid = true,
      targetRowRepick = true,
      targetScbReturned = true,
      conflicts = Conflicts(launch = true))
    val allocation = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = false,
      requestValid = true,
      targetRowValid = true,
      targetRowRepick = true,
      targetScbReturned = true,
      conflicts = Conflicts(allocation = true))

    assert(e4.blockedByE4UpdateConflict)
    assert(clear.blockedByClearResolvedConflict)
    assert(replay.blockedByReplayWakeConflict)
    assert(refill.blockedByRefillConflict)
    assert(launch.blockedByLaunchConflict)
    assert(allocation.blockedByAllocationConflict)
    assert(Seq(e4, clear, replay, refill, launch, allocation).forall(result => result.writeConflict && !result.writeEnable))
  }

  test("admits an MDB wait mutation on a resident Wait row without return evidence") {
    val result = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = false,
      requestValid = true,
      targetRowValid = true,
      targetRowRepick = false,
      targetScbReturned = false,
      targetRowWait = true,
      allowWaitTarget = true,
      requireScbReturned = false)

    assert(result.targetEvidenceValid)
    assert(result.writeEnable)
    assert(!result.blockedByNotRepick)
    assert(!result.blockedByScbNotReturned)
  }

  test("reports disabled flush and no-request blockers without enabling writes") {
    val disabled = LoadInflightRowMutationWriteControlReference(
      enable = false,
      flush = false,
      requestValid = true,
      targetRowValid = true,
      targetRowRepick = true,
      targetScbReturned = true)
    val flushed = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = true,
      requestValid = true,
      targetRowValid = true,
      targetRowRepick = true,
      targetScbReturned = true)
    val noRequest = LoadInflightRowMutationWriteControlReference(
      enable = true,
      flush = false,
      requestValid = false,
      targetRowValid = true,
      targetRowRepick = true,
      targetScbReturned = true)

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(noRequest.blockedByNoRequest)
    assert(!disabled.writeEnable)
    assert(!flushed.writeEnable)
    assert(!noRequest.writeEnable)
  }

  test("Chisel LoadInflightRowMutationWriteControl elaborates admission diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadInflightRowMutationWriteControl)

    assert(sv.contains("module LoadInflightRowMutationWriteControl"))
    assert(sv.contains("io_targetEvidenceValid"))
    assert(sv.contains("io_writeConflict"))
    assert(sv.contains("io_writeEnable"))
    assert(sv.contains("io_blockedByScbNotReturned"))
    assert(sv.contains("io_blockedByE4UpdateConflict"))
    assert(sv.contains("io_blockedByAllocationConflict"))
  }
}
