package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2SideEffectRequestReference {
  final case class Result(
      requestValid: Boolean,
      resolveRequest: Boolean,
      writebackRequest: Boolean,
      wakeupRequest: Boolean,
      requestMask: Int,
      blockedByNoComplete: Boolean,
      invalidCompleteWithoutCandidate: Boolean,
      invalidCompleteWithoutResolve: Boolean,
      invalidResolveWithoutComplete: Boolean,
      invalidWritebackWithoutComplete: Boolean,
      invalidWakeupWithoutComplete: Boolean)

  def apply(
      sideEffectCandidateValid: Boolean,
      completeValid: Boolean,
      resolveValid: Boolean,
      writebackValid: Boolean,
      wakeupValid: Boolean): Result = {
    val requestValid = completeValid && sideEffectCandidateValid
    val resolveRequest = requestValid && resolveValid
    val writebackRequest = requestValid && writebackValid
    val wakeupRequest = requestValid && wakeupValid
    val requestMask =
      (if (wakeupRequest) 4 else 0) |
        (if (writebackRequest) 2 else 0) |
        (if (resolveRequest) 1 else 0)

    Result(
      requestValid = requestValid,
      resolveRequest = resolveRequest,
      writebackRequest = writebackRequest,
      wakeupRequest = wakeupRequest,
      requestMask = requestMask,
      blockedByNoComplete = sideEffectCandidateValid && !completeValid,
      invalidCompleteWithoutCandidate = completeValid && !sideEffectCandidateValid,
      invalidCompleteWithoutResolve = requestValid && !resolveValid,
      invalidResolveWithoutComplete = resolveValid && !completeValid,
      invalidWritebackWithoutComplete = writebackValid && !completeValid,
      invalidWakeupWithoutComplete = wakeupValid && !completeValid)
  }
}

class LoadReplayReturnPipeW2SideEffectRequestSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2SideEffectRequestReference._

  test("requests resolve writeback and wakeup when a completed W2 entry has every side effect") {
    val result = LoadReplayReturnPipeW2SideEffectRequestReference(
      sideEffectCandidateValid = true,
      completeValid = true,
      resolveValid = true,
      writebackValid = true,
      wakeupValid = true)

    assert(result.requestValid)
    assert(result.resolveRequest)
    assert(result.writebackRequest)
    assert(result.wakeupRequest)
    assert(result.requestMask == 0x7)
    assert(!result.blockedByNoComplete)
    assert(!result.invalidCompleteWithoutResolve)
  }

  test("always allows resolve-only completion when optional side effects are absent") {
    val result = LoadReplayReturnPipeW2SideEffectRequestReference(
      sideEffectCandidateValid = true,
      completeValid = true,
      resolveValid = true,
      writebackValid = false,
      wakeupValid = false)

    assert(result.requestValid)
    assert(result.resolveRequest)
    assert(!result.writebackRequest)
    assert(!result.wakeupRequest)
    assert(result.requestMask == 0x1)
  }

  test("does not request side effects before W2 completion fires") {
    val result = LoadReplayReturnPipeW2SideEffectRequestReference(
      sideEffectCandidateValid = true,
      completeValid = false,
      resolveValid = false,
      writebackValid = false,
      wakeupValid = false)

    assert(!result.requestValid)
    assert(!result.resolveRequest)
    assert(!result.writebackRequest)
    assert(!result.wakeupRequest)
    assert(result.requestMask == 0x0)
    assert(result.blockedByNoComplete)
  }

  test("flags illegal completed shapes without mutating request outputs") {
    val noCandidate = LoadReplayReturnPipeW2SideEffectRequestReference(
      sideEffectCandidateValid = false,
      completeValid = true,
      resolveValid = false,
      writebackValid = false,
      wakeupValid = false)
    val noResolve = LoadReplayReturnPipeW2SideEffectRequestReference(
      sideEffectCandidateValid = true,
      completeValid = true,
      resolveValid = false,
      writebackValid = true,
      wakeupValid = true)

    assert(!noCandidate.requestValid)
    assert(noCandidate.invalidCompleteWithoutCandidate)
    assert(noResolve.requestValid)
    assert(noResolve.invalidCompleteWithoutResolve)
    assert(!noResolve.resolveRequest)
    assert(noResolve.writebackRequest)
    assert(noResolve.wakeupRequest)
  }

  test("flags side-effect valid pulses that bypass completion") {
    val result = LoadReplayReturnPipeW2SideEffectRequestReference(
      sideEffectCandidateValid = false,
      completeValid = false,
      resolveValid = true,
      writebackValid = true,
      wakeupValid = true)

    assert(!result.requestValid)
    assert(result.invalidResolveWithoutComplete)
    assert(result.invalidWritebackWithoutComplete)
    assert(result.invalidWakeupWithoutComplete)
  }

  test("Chisel LoadReplayReturnPipeW2SideEffectRequest elaborates request diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2SideEffectRequest)

    assert(sv.contains("module LoadReplayReturnPipeW2SideEffectRequest"))
    assert(sv.contains("io_sideEffectCandidateValid"))
    assert(sv.contains("io_completeValid"))
    assert(sv.contains("io_resolveValid"))
    assert(sv.contains("io_writebackValid"))
    assert(sv.contains("io_wakeupValid"))
    assert(sv.contains("io_requestMask"))
    assert(sv.contains("io_invalidCompleteWithoutResolve"))
  }
}
