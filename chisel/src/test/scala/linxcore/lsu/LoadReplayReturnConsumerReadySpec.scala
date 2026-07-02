package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnConsumerReadyReference {
  final case class Result(
      candidateValid: Boolean,
      wakeupRequired: Boolean,
      consumerReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByNoCandidate: Boolean,
      blockedBySources: Boolean,
      blockedByLretSink: Boolean,
      blockedByWakeupSink: Boolean)

  def apply(
      enable: Boolean,
      launchValid: Boolean,
      sourcesReturned: Boolean,
      specWakeup: Boolean,
      stackValid: Boolean,
      lretSinkReady: Boolean,
      wakeupSinkReady: Boolean): Result = {
    val candidateValid = enable && launchValid
    val wakeupRequired = candidateValid && !specWakeup && !stackValid

    Result(
      candidateValid = candidateValid,
      wakeupRequired = wakeupRequired,
      consumerReady = enable && lretSinkReady && (!wakeupRequired || wakeupSinkReady),
      blockedByDisabled = !enable && launchValid,
      blockedByNoCandidate = enable && !launchValid,
      blockedBySources = candidateValid && !sourcesReturned,
      blockedByLretSink = candidateValid && sourcesReturned && !lretSinkReady,
      blockedByWakeupSink = candidateValid && sourcesReturned && lretSinkReady && wakeupRequired && !wakeupSinkReady)
  }
}

class LoadReplayReturnConsumerReadySpec extends AnyFunSuite {
  import LoadReplayReturnConsumerReadyReference._

  test("requires both LRET and wakeup sinks for normal replay returns") {
    val result = LoadReplayReturnConsumerReadyReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      specWakeup = false,
      stackValid = false,
      lretSinkReady = true,
      wakeupSinkReady = true)

    assert(result.candidateValid)
    assert(result.wakeupRequired)
    assert(result.consumerReady)
    assert(!result.blockedByLretSink)
    assert(!result.blockedByWakeupSink)
  }

  test("still requires the LRET sink for speculative wakeup rows") {
    val result = LoadReplayReturnConsumerReadyReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      specWakeup = true,
      stackValid = false,
      lretSinkReady = true,
      wakeupSinkReady = false)

    assert(!result.wakeupRequired)
    assert(result.consumerReady)
    assert(!result.blockedByWakeupSink)
  }

  test("still requires the LRET sink for stack-valid rows") {
    val result = LoadReplayReturnConsumerReadyReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      specWakeup = false,
      stackValid = true,
      lretSinkReady = true,
      wakeupSinkReady = false)

    assert(!result.wakeupRequired)
    assert(result.consumerReady)
    assert(!result.blockedByWakeupSink)
  }

  test("reports LRET sink blocking before wakeup sink blocking") {
    val result = LoadReplayReturnConsumerReadyReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      specWakeup = false,
      stackValid = false,
      lretSinkReady = false,
      wakeupSinkReady = false)

    assert(!result.consumerReady)
    assert(result.blockedByLretSink)
    assert(!result.blockedByWakeupSink)
  }

  test("reports wakeup sink blocking only after source return and LRET readiness") {
    val blocked = LoadReplayReturnConsumerReadyReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      specWakeup = false,
      stackValid = false,
      lretSinkReady = true,
      wakeupSinkReady = false)
    val sourceWait = LoadReplayReturnConsumerReadyReference(
      enable = true,
      launchValid = true,
      sourcesReturned = false,
      specWakeup = false,
      stackValid = false,
      lretSinkReady = true,
      wakeupSinkReady = false)

    assert(!blocked.consumerReady)
    assert(blocked.blockedByWakeupSink)
    assert(!sourceWait.blockedByWakeupSink)
    assert(sourceWait.blockedBySources)
  }

  test("reports empty and disabled candidate diagnostics without claiming a row wakeup") {
    val empty = LoadReplayReturnConsumerReadyReference(
      enable = true,
      launchValid = false,
      sourcesReturned = true,
      specWakeup = false,
      stackValid = false,
      lretSinkReady = true,
      wakeupSinkReady = false)
    val disabled = LoadReplayReturnConsumerReadyReference(
      enable = false,
      launchValid = true,
      sourcesReturned = true,
      specWakeup = false,
      stackValid = false,
      lretSinkReady = true,
      wakeupSinkReady = true)

    assert(!empty.candidateValid)
    assert(!empty.wakeupRequired)
    assert(empty.blockedByNoCandidate)
    assert(!disabled.candidateValid)
    assert(disabled.blockedByDisabled)
  }

  test("Chisel LoadReplayReturnConsumerReady elaborates consumer diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnConsumerReady)

    assert(sv.contains("module LoadReplayReturnConsumerReady"))
    assert(sv.contains("io_specWakeup"))
    assert(sv.contains("io_stackValid"))
    assert(sv.contains("io_lretSinkReady"))
    assert(sv.contains("io_wakeupSinkReady"))
    assert(sv.contains("io_consumerReady"))
    assert(sv.contains("io_blockedByLretSink"))
    assert(sv.contains("io_blockedByWakeupSink"))
  }
}
