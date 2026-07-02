package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPublishReadyReference {
  final case class Result(
      candidateValid: Boolean,
      dataReady: Boolean,
      publishReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByNoCandidate: Boolean,
      blockedByData: Boolean,
      blockedByConsumer: Boolean)

  def apply(
      enable: Boolean,
      launchValid: Boolean,
      dataValid: Boolean,
      consumerReady: Boolean): Result = {
    val candidateValid = enable && launchValid
    val dataReady = candidateValid && dataValid

    Result(
      candidateValid = candidateValid,
      dataReady = dataReady,
      publishReady = dataReady && consumerReady,
      blockedByDisabled = !enable && launchValid,
      blockedByNoCandidate = enable && !launchValid,
      blockedByData = candidateValid && !dataValid,
      blockedByConsumer = dataReady && !consumerReady)
  }
}

class LoadReplayReturnPublishReadySpec extends AnyFunSuite {
  import LoadReplayReturnPublishReadyReference._

  test("publishes only when data and downstream consumers are ready") {
    val result = LoadReplayReturnPublishReadyReference(
      enable = true,
      launchValid = true,
      dataValid = true,
      consumerReady = true)

    assert(result.candidateValid)
    assert(result.dataReady)
    assert(result.publishReady)
    assert(!result.blockedByData)
    assert(!result.blockedByConsumer)
  }

  test("reports data blocking before consumer blocking") {
    val dataBlocked = LoadReplayReturnPublishReadyReference(
      enable = true,
      launchValid = true,
      dataValid = false,
      consumerReady = false)
    val consumerBlocked = LoadReplayReturnPublishReadyReference(
      enable = true,
      launchValid = true,
      dataValid = true,
      consumerReady = false)

    assert(!dataBlocked.publishReady)
    assert(dataBlocked.blockedByData)
    assert(!dataBlocked.blockedByConsumer)
    assert(!consumerBlocked.publishReady)
    assert(!consumerBlocked.blockedByData)
    assert(consumerBlocked.blockedByConsumer)
  }

  test("reports empty and disabled candidate diagnostics") {
    val empty = LoadReplayReturnPublishReadyReference(
      enable = true,
      launchValid = false,
      dataValid = true,
      consumerReady = true)
    val disabled = LoadReplayReturnPublishReadyReference(
      enable = false,
      launchValid = true,
      dataValid = true,
      consumerReady = true)

    assert(!empty.candidateValid)
    assert(!empty.dataReady)
    assert(!empty.publishReady)
    assert(empty.blockedByNoCandidate)
    assert(!disabled.candidateValid)
    assert(disabled.blockedByDisabled)
  }

  test("Chisel LoadReplayReturnPublishReady elaborates publish diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPublishReady)

    assert(sv.contains("module LoadReplayReturnPublishReady"))
    assert(sv.contains("io_dataValid"))
    assert(sv.contains("io_consumerReady"))
    assert(sv.contains("io_publishReady"))
    assert(sv.contains("io_blockedByData"))
    assert(sv.contains("io_blockedByConsumer"))
  }
}
