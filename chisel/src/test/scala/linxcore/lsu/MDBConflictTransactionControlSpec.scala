package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object MDBConflictTransactionControlReference {
  final case class Result(
      ready: Boolean,
      accepted: Boolean,
      recordValid: Boolean,
      waitPlanValid: Boolean,
      recoveryValid: Boolean)

  def apply(
      enable: Boolean,
      candidateValid: Boolean,
      recordRequired: Boolean,
      waitPlanRequired: Boolean,
      recoveryRequired: Boolean,
      recordReady: Boolean,
      waitPlanReady: Boolean,
      recoveryReady: Boolean): Result = {
    val ready = enable &&
      (!recordRequired || recordReady) &&
      (!waitPlanRequired || waitPlanReady) &&
      (!recoveryRequired || recoveryReady)
    val accepted = candidateValid && ready
    Result(
      ready,
      accepted,
      recordValid = accepted && recordRequired,
      waitPlanValid = accepted && waitPlanRequired,
      recoveryValid = accepted && recoveryRequired
    )
  }
}

class MDBConflictTransactionControlSpec extends AnyFunSuite {
  import MDBConflictTransactionControlReference._

  test("a conflict record and recovery report commit as one transaction") {
    val blocked = apply(
      enable = true,
      candidateValid = true,
      recordRequired = true,
      waitPlanRequired = false,
      recoveryRequired = true,
      recordReady = true,
      waitPlanReady = true,
      recoveryReady = false
    )
    assert(!blocked.ready)
    assert(!blocked.accepted)
    assert(!blocked.recordValid)
    assert(!blocked.recoveryValid)

    val accepted = apply(
      enable = true,
      candidateValid = true,
      recordRequired = true,
      waitPlanRequired = false,
      recoveryRequired = true,
      recordReady = true,
      waitPlanReady = true,
      recoveryReady = true
    )
    assert(accepted.accepted)
    assert(accepted.recordValid)
    assert(accepted.recoveryValid)
  }

  test("unrequired sinks cannot backpressure a non-conflicting probe") {
    val result = apply(
      enable = true,
      candidateValid = true,
      recordRequired = false,
      waitPlanRequired = false,
      recoveryRequired = false,
      recordReady = false,
      waitPlanReady = false,
      recoveryReady = false
    )
    assert(result.ready)
    assert(result.accepted)
    assert(!result.recordValid)
    assert(!result.waitPlanValid)
    assert(!result.recoveryValid)
  }

  test("wait-plan retention participates only when the decision requires it") {
    val result = apply(
      enable = true,
      candidateValid = true,
      recordRequired = false,
      waitPlanRequired = true,
      recoveryRequired = false,
      recordReady = true,
      waitPlanReady = false,
      recoveryReady = true
    )
    assert(!result.ready)
    assert(!result.waitPlanValid)
  }

  test("Chisel transaction controller elaborates explicit sink valids") {
    val sv = ChiselStage.emitSystemVerilog(new MDBConflictTransactionControl)
    assert(sv.contains("module MDBConflictTransactionControl"))
    assert(sv.contains("io_candidateReady"))
    assert(sv.contains("io_recordValid"))
    assert(sv.contains("io_recoveryValid"))
  }
}
