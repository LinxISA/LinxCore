package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2SideEffectFireCompleteReference {
  final case class Result(
      candidateValid: Boolean,
      observedFireMask: Int,
      payloadFireMask: Int,
      missingPayloadFireMask: Int,
      unexpectedPayloadFireMask: Int,
      payloadMatchesFire: Boolean,
      fireComplete: Boolean,
      futureClearEligible: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoFireVector: Boolean,
      blockedByNoFireSink: Boolean,
      blockedByPayloadMismatch: Boolean,
      invalidFireWithoutPayload: Boolean,
      invalidPayloadWithoutFire: Boolean)

  def mask(resolve: Boolean, writeback: Boolean, wakeup: Boolean): Int =
    (if (wakeup) 4 else 0) |
      (if (writeback) 2 else 0) |
      (if (resolve) 1 else 0)

  def apply(
      enable: Boolean,
      flush: Boolean,
      fireVectorValid: Boolean,
      fireMask: Int,
      resolveFirePayloadValid: Boolean,
      writebackFirePayloadValid: Boolean,
      wakeupFirePayloadValid: Boolean): Result = {
    val active = enable && !flush
    val fire = fireMask & 0x7
    val payload = mask(resolveFirePayloadValid, writebackFirePayloadValid, wakeupFirePayloadValid)
    val candidateValid = active && fireVectorValid
    val fireMaskHasSink = fire != 0
    val missingPayloadFireMask = fire & (~payload & 0x7)
    val unexpectedPayloadFireMask = payload & (~fire & 0x7)
    val payloadMatchesFire = payload == fire
    val fireComplete = candidateValid && fireMaskHasSink && payloadMatchesFire

    Result(
      candidateValid = candidateValid,
      observedFireMask = if (candidateValid) fire else 0,
      payloadFireMask = payload,
      missingPayloadFireMask = missingPayloadFireMask,
      unexpectedPayloadFireMask = unexpectedPayloadFireMask,
      payloadMatchesFire = payloadMatchesFire,
      fireComplete = fireComplete,
      futureClearEligible = fireComplete,
      blockedByDisabled = !enable && (fireVectorValid || payload != 0),
      blockedByFlush = enable && flush && (fireVectorValid || payload != 0),
      blockedByNoFireVector = active && !fireVectorValid && payload != 0,
      blockedByNoFireSink = candidateValid && !fireMaskHasSink,
      blockedByPayloadMismatch = candidateValid && !payloadMatchesFire,
      invalidFireWithoutPayload = candidateValid && missingPayloadFireMask != 0,
      invalidPayloadWithoutFire =
        (active && !fireVectorValid && payload != 0) ||
          (candidateValid && unexpectedPayloadFireMask != 0))
  }
}

class LoadReplayReturnPipeW2SideEffectFireCompleteSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2SideEffectFireCompleteReference._

  test("completes a full resolve writeback and wakeup fire payload set") {
    val result = LoadReplayReturnPipeW2SideEffectFireCompleteReference(
      enable = true,
      flush = false,
      fireVectorValid = true,
      fireMask = 0x7,
      resolveFirePayloadValid = true,
      writebackFirePayloadValid = true,
      wakeupFirePayloadValid = true)

    assert(result.candidateValid)
    assert(result.observedFireMask == 0x7)
    assert(result.payloadFireMask == 0x7)
    assert(result.payloadMatchesFire)
    assert(result.fireComplete)
    assert(result.futureClearEligible)
    assert(result.missingPayloadFireMask == 0x0)
    assert(result.unexpectedPayloadFireMask == 0x0)
  }

  test("completes a resolve-only fire without optional payloads") {
    val result = LoadReplayReturnPipeW2SideEffectFireCompleteReference(
      enable = true,
      flush = false,
      fireVectorValid = true,
      fireMask = 0x1,
      resolveFirePayloadValid = true,
      writebackFirePayloadValid = false,
      wakeupFirePayloadValid = false)

    assert(result.fireComplete)
    assert(result.payloadFireMask == 0x1)
    assert(!result.blockedByPayloadMismatch)
    assert(!result.invalidPayloadWithoutFire)
  }

  test("flags a fired sink whose downstream fire payload did not validate") {
    val result = LoadReplayReturnPipeW2SideEffectFireCompleteReference(
      enable = true,
      flush = false,
      fireVectorValid = true,
      fireMask = 0x7,
      resolveFirePayloadValid = true,
      writebackFirePayloadValid = false,
      wakeupFirePayloadValid = true)

    assert(!result.fireComplete)
    assert(result.missingPayloadFireMask == 0x2)
    assert(result.blockedByPayloadMismatch)
    assert(result.invalidFireWithoutPayload)
    assert(!result.invalidPayloadWithoutFire)
  }

  test("flags a downstream fire payload that was not in the fire mask") {
    val result = LoadReplayReturnPipeW2SideEffectFireCompleteReference(
      enable = true,
      flush = false,
      fireVectorValid = true,
      fireMask = 0x1,
      resolveFirePayloadValid = true,
      writebackFirePayloadValid = true,
      wakeupFirePayloadValid = false)

    assert(!result.fireComplete)
    assert(result.unexpectedPayloadFireMask == 0x2)
    assert(result.blockedByPayloadMismatch)
    assert(!result.invalidFireWithoutPayload)
    assert(result.invalidPayloadWithoutFire)
  }

  test("blocks disabled and flushed fire evidence without producing clear eligibility") {
    val disabled = LoadReplayReturnPipeW2SideEffectFireCompleteReference(
      enable = false,
      flush = false,
      fireVectorValid = true,
      fireMask = 0x1,
      resolveFirePayloadValid = true,
      writebackFirePayloadValid = false,
      wakeupFirePayloadValid = false)
    val flushed = LoadReplayReturnPipeW2SideEffectFireCompleteReference(
      enable = true,
      flush = true,
      fireVectorValid = true,
      fireMask = 0x1,
      resolveFirePayloadValid = true,
      writebackFirePayloadValid = false,
      wakeupFirePayloadValid = false)

    assert(!disabled.fireComplete)
    assert(disabled.blockedByDisabled)
    assert(!flushed.fireComplete)
    assert(flushed.blockedByFlush)
  }

  test("flags payload fire evidence that appears without a fire vector") {
    val result = LoadReplayReturnPipeW2SideEffectFireCompleteReference(
      enable = true,
      flush = false,
      fireVectorValid = false,
      fireMask = 0x0,
      resolveFirePayloadValid = true,
      writebackFirePayloadValid = false,
      wakeupFirePayloadValid = false)

    assert(!result.candidateValid)
    assert(!result.fireComplete)
    assert(result.blockedByNoFireVector)
    assert(result.invalidPayloadWithoutFire)
  }

  test("Chisel LoadReplayReturnPipeW2SideEffectFireComplete elaborates complete diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2SideEffectFireComplete)

    assert(sv.contains("module LoadReplayReturnPipeW2SideEffectFireComplete"))
    assert(sv.contains("io_fireVectorValid"))
    assert(sv.contains("io_payloadFireMask"))
    assert(sv.contains("io_fireComplete"))
    assert(sv.contains("io_futureClearEligible"))
    assert(sv.contains("io_invalidPayloadWithoutFire"))
  }
}
