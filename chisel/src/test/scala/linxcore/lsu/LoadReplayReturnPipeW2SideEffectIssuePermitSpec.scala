package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2SideEffectIssuePermitReference {
  final case class Result(
      candidateValid: Boolean,
      issueArmed: Boolean,
      sinkReadyMask: Int,
      missingReadyMask: Int,
      acceptedMask: Int,
      allRequiredSinksReady: Boolean,
      issueAccepted: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoPlan: Boolean,
      blockedByNoRequiredSink: Boolean,
      blockedByResolveSink: Boolean,
      blockedByWritebackSink: Boolean,
      blockedByWakeupSink: Boolean)

  def mask(resolve: Boolean, writeback: Boolean, wakeup: Boolean): Int =
    (if (wakeup) 4 else 0) |
      (if (writeback) 2 else 0) |
      (if (resolve) 1 else 0)

  def apply(
      enable: Boolean,
      flush: Boolean,
      payloadPlanIssueValid: Boolean,
      requiredMask: Int,
      resolveSinkReady: Boolean,
      writebackSinkReady: Boolean,
      wakeupSinkReady: Boolean): Result = {
    val active = enable && !flush
    val candidateValid = active && payloadPlanIssueValid
    val hasRequiredSink = (requiredMask & 0x7) != 0
    val issueArmed = candidateValid && hasRequiredSink
    val resolveRequired = (requiredMask & 0x1) != 0
    val writebackRequired = (requiredMask & 0x2) != 0
    val wakeupRequired = (requiredMask & 0x4) != 0
    val resolveReady = !resolveRequired || resolveSinkReady
    val writebackReady = !writebackRequired || writebackSinkReady
    val wakeupReady = !wakeupRequired || wakeupSinkReady
    val allRequiredSinksReady = resolveReady && writebackReady && wakeupReady
    val issueAccepted = issueArmed && allRequiredSinksReady
    val missingResolve = issueArmed && resolveRequired && !resolveSinkReady
    val missingWriteback = issueArmed && writebackRequired && !writebackSinkReady
    val missingWakeup = issueArmed && wakeupRequired && !wakeupSinkReady

    Result(
      candidateValid = candidateValid,
      issueArmed = issueArmed,
      sinkReadyMask = mask(resolveSinkReady, writebackSinkReady, wakeupSinkReady),
      missingReadyMask = mask(missingResolve, missingWriteback, missingWakeup),
      acceptedMask = if (issueAccepted) requiredMask & 0x7 else 0,
      allRequiredSinksReady = allRequiredSinksReady,
      issueAccepted = issueAccepted,
      blockedByDisabled = !enable && payloadPlanIssueValid,
      blockedByFlush = enable && flush && payloadPlanIssueValid,
      blockedByNoPlan = active && !payloadPlanIssueValid,
      blockedByNoRequiredSink = candidateValid && !hasRequiredSink,
      blockedByResolveSink = missingResolve,
      blockedByWritebackSink = missingWriteback,
      blockedByWakeupSink = missingWakeup)
  }
}

class LoadReplayReturnPipeW2SideEffectIssuePermitSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2SideEffectIssuePermitReference._

  test("accepts a coherent full side-effect issue when every required sink is ready") {
    val result = LoadReplayReturnPipeW2SideEffectIssuePermitReference(
      enable = true,
      flush = false,
      payloadPlanIssueValid = true,
      requiredMask = 0x7,
      resolveSinkReady = true,
      writebackSinkReady = true,
      wakeupSinkReady = true)

    assert(result.candidateValid)
    assert(result.issueArmed)
    assert(result.allRequiredSinksReady)
    assert(result.issueAccepted)
    assert(result.sinkReadyMask == 0x7)
    assert(result.acceptedMask == 0x7)
    assert(result.missingReadyMask == 0x0)
  }

  test("accepts resolve-only issue without optional writeback or wakeup sink readiness") {
    val result = LoadReplayReturnPipeW2SideEffectIssuePermitReference(
      enable = true,
      flush = false,
      payloadPlanIssueValid = true,
      requiredMask = 0x1,
      resolveSinkReady = true,
      writebackSinkReady = false,
      wakeupSinkReady = false)

    assert(result.issueAccepted)
    assert(result.acceptedMask == 0x1)
    assert(!result.blockedByWritebackSink)
    assert(!result.blockedByWakeupSink)
  }

  test("reports live-gated sink blockers independently") {
    val result = LoadReplayReturnPipeW2SideEffectIssuePermitReference(
      enable = true,
      flush = false,
      payloadPlanIssueValid = true,
      requiredMask = 0x7,
      resolveSinkReady = true,
      writebackSinkReady = false,
      wakeupSinkReady = false)

    assert(!result.issueAccepted)
    assert(result.issueArmed)
    assert(!result.allRequiredSinksReady)
    assert(result.blockedByWritebackSink)
    assert(result.blockedByWakeupSink)
    assert(!result.blockedByResolveSink)
    assert(result.missingReadyMask == 0x6)
  }

  test("stays dormant until the payload plan proves coherent side effects") {
    val result = LoadReplayReturnPipeW2SideEffectIssuePermitReference(
      enable = true,
      flush = false,
      payloadPlanIssueValid = false,
      requiredMask = 0x7,
      resolveSinkReady = true,
      writebackSinkReady = true,
      wakeupSinkReady = true)

    assert(!result.candidateValid)
    assert(!result.issueArmed)
    assert(!result.issueAccepted)
    assert(result.blockedByNoPlan)
    assert(result.acceptedMask == 0x0)
  }

  test("blocks disabled and flushed plans without accepting sink readiness") {
    val disabled = LoadReplayReturnPipeW2SideEffectIssuePermitReference(
      enable = false,
      flush = false,
      payloadPlanIssueValid = true,
      requiredMask = 0x1,
      resolveSinkReady = true,
      writebackSinkReady = false,
      wakeupSinkReady = false)
    val flushed = LoadReplayReturnPipeW2SideEffectIssuePermitReference(
      enable = true,
      flush = true,
      payloadPlanIssueValid = true,
      requiredMask = 0x1,
      resolveSinkReady = true,
      writebackSinkReady = false,
      wakeupSinkReady = false)

    assert(!disabled.issueAccepted)
    assert(disabled.blockedByDisabled)
    assert(!flushed.issueAccepted)
    assert(flushed.blockedByFlush)
  }

  test("flags a coherent payload plan that carries no required W2 side effect") {
    val result = LoadReplayReturnPipeW2SideEffectIssuePermitReference(
      enable = true,
      flush = false,
      payloadPlanIssueValid = true,
      requiredMask = 0x0,
      resolveSinkReady = true,
      writebackSinkReady = true,
      wakeupSinkReady = true)

    assert(result.candidateValid)
    assert(!result.issueArmed)
    assert(!result.issueAccepted)
    assert(result.blockedByNoRequiredSink)
  }

  test("Chisel LoadReplayReturnPipeW2SideEffectIssuePermit elaborates compact accept diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2SideEffectIssuePermit)

    assert(sv.contains("module LoadReplayReturnPipeW2SideEffectIssuePermit"))
    assert(sv.contains("io_payloadPlanIssueValid"))
    assert(sv.contains("io_requiredMask"))
    assert(sv.contains("io_sinkReadyMask"))
    assert(sv.contains("io_missingReadyMask"))
    assert(sv.contains("io_issueAccepted"))
  }
}
