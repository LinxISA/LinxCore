package linxcore.execute

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ReducedScalarWritebackArbiterReference {
  final case class Result(
      writeValid: Boolean,
      writeTag: Int,
      writeData: BigInt,
      selectedExecute: Boolean,
      selectedReplay: Boolean,
      replayBlockedByDisabled: Boolean,
      replayBlockedByExecute: Boolean)

  def apply(
      executeValid: Boolean,
      executeTag: Int,
      executeData: BigInt,
      replayEnable: Boolean,
      replayValid: Boolean,
      replayTag: Int,
      replayData: BigInt): Result = {
    val replayCandidate = replayEnable && replayValid
    val selectedExecute = executeValid
    val selectedReplay = replayCandidate && !executeValid

    Result(
      writeValid = selectedExecute || selectedReplay,
      writeTag = if (selectedExecute) executeTag else if (selectedReplay) replayTag else 0,
      writeData = if (selectedExecute) executeData else if (selectedReplay) replayData else BigInt(0),
      selectedExecute = selectedExecute,
      selectedReplay = selectedReplay,
      replayBlockedByDisabled = !replayEnable && replayValid,
      replayBlockedByExecute = replayCandidate && executeValid)
  }
}

class ReducedScalarWritebackArbiterSpec extends AnyFunSuite {
  import ReducedScalarWritebackArbiterReference._

  test("selects execute writeback with priority over replay") {
    val result = ReducedScalarWritebackArbiterReference(
      executeValid = true,
      executeTag = 5,
      executeData = BigInt("1111222233334444", 16),
      replayEnable = true,
      replayValid = true,
      replayTag = 42,
      replayData = BigInt("aaaabbbbccccdddd", 16))

    assert(result.writeValid)
    assert(result.writeTag == 5)
    assert(result.writeData == BigInt("1111222233334444", 16))
    assert(result.selectedExecute)
    assert(!result.selectedReplay)
    assert(result.replayBlockedByExecute)
    assert(!result.replayBlockedByDisabled)
  }

  test("selects replay writeback when enabled and execute is idle") {
    val result = ReducedScalarWritebackArbiterReference(
      executeValid = false,
      executeTag = 5,
      executeData = 0x1111,
      replayEnable = true,
      replayValid = true,
      replayTag = 42,
      replayData = BigInt("aaaabbbbccccdddd", 16))

    assert(result.writeValid)
    assert(result.writeTag == 42)
    assert(result.writeData == BigInt("aaaabbbbccccdddd", 16))
    assert(!result.selectedExecute)
    assert(result.selectedReplay)
    assert(!result.replayBlockedByExecute)
    assert(!result.replayBlockedByDisabled)
  }

  test("blocks replay while disabled and suppresses stale write fields") {
    val result = ReducedScalarWritebackArbiterReference(
      executeValid = false,
      executeTag = 5,
      executeData = 0x1111,
      replayEnable = false,
      replayValid = true,
      replayTag = 42,
      replayData = 0x2222)

    assert(!result.writeValid)
    assert(result.writeTag == 0)
    assert(result.writeData == 0)
    assert(!result.selectedExecute)
    assert(!result.selectedReplay)
    assert(!result.replayBlockedByExecute)
    assert(result.replayBlockedByDisabled)
  }

  test("Chisel ReducedScalarWritebackArbiter elaborates arbitration diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarWritebackArbiter)

    assert(sv.contains("module ReducedScalarWritebackArbiter"))
    assert(sv.contains("io_executeValid"))
    assert(sv.contains("io_replayEnable"))
    assert(sv.contains("io_selectedExecute"))
    assert(sv.contains("io_selectedReplay"))
    assert(sv.contains("io_replayBlockedByExecute"))
  }
}
