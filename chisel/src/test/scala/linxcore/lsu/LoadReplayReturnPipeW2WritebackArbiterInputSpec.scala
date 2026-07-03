package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2WritebackArbiterInputReference {
  final case class Result(
      active: Boolean,
      candidateValid: Boolean,
      writeValid: Boolean,
      writeTag: Int,
      writeData: BigInt,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoPayload: Boolean,
      blockedByLiveDisabled: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveEnable: Boolean,
      firePayloadValid: Boolean,
      firePhysTag: Int,
      fireData: BigInt): Result = {
    val active = enable && !flush
    val candidateValid = active && firePayloadValid
    val writeValid = candidateValid && liveEnable

    Result(
      active = active,
      candidateValid = candidateValid,
      writeValid = writeValid,
      writeTag = if (writeValid) firePhysTag else 0,
      writeData = if (writeValid) fireData else 0,
      blockedByDisabled = !enable && firePayloadValid,
      blockedByFlush = enable && flush && firePayloadValid,
      blockedByNoPayload = active && !firePayloadValid,
      blockedByLiveDisabled = candidateValid && !liveEnable)
  }
}

class LoadReplayReturnPipeW2WritebackArbiterInputSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2WritebackArbiterInputReference._

  test("holds a valid W2 writeback payload behind the live gate") {
    val result = LoadReplayReturnPipeW2WritebackArbiterInputReference(
      enable = true,
      flush = false,
      liveEnable = false,
      firePayloadValid = true,
      firePhysTag = 42,
      fireData = BigInt("1122334455667788", 16))

    assert(result.active)
    assert(result.candidateValid)
    assert(!result.writeValid)
    assert(result.writeTag == 0)
    assert(result.writeData == 0)
    assert(result.blockedByLiveDisabled)
  }

  test("emits a writeback candidate when the live gate is enabled") {
    val result = LoadReplayReturnPipeW2WritebackArbiterInputReference(
      enable = true,
      flush = false,
      liveEnable = true,
      firePayloadValid = true,
      firePhysTag = 17,
      fireData = BigInt("8877665544332211", 16))

    assert(result.candidateValid)
    assert(result.writeValid)
    assert(result.writeTag == 17)
    assert(result.writeData == BigInt("8877665544332211", 16))
    assert(!result.blockedByLiveDisabled)
  }

  test("reports disabled and flushed payloads without stale write fields") {
    val disabled = LoadReplayReturnPipeW2WritebackArbiterInputReference(
      enable = false,
      flush = false,
      liveEnable = true,
      firePayloadValid = true,
      firePhysTag = 5,
      fireData = 0x1234)
    val flushed = LoadReplayReturnPipeW2WritebackArbiterInputReference(
      enable = true,
      flush = true,
      liveEnable = true,
      firePayloadValid = true,
      firePhysTag = 5,
      fireData = 0x1234)

    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)
    assert(!disabled.writeValid)
    assert(disabled.writeTag == 0)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)
    assert(!flushed.writeValid)
    assert(flushed.writeData == 0)
  }

  test("reports an active cycle with no fire payload") {
    val result = LoadReplayReturnPipeW2WritebackArbiterInputReference(
      enable = true,
      flush = false,
      liveEnable = true,
      firePayloadValid = false,
      firePhysTag = 5,
      fireData = 0x1234)

    assert(result.active)
    assert(!result.candidateValid)
    assert(!result.writeValid)
    assert(result.blockedByNoPayload)
  }

  test("Chisel LoadReplayReturnPipeW2WritebackArbiterInput elaborates writeback candidate fields") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2WritebackArbiterInput)

    assert(sv.contains("module LoadReplayReturnPipeW2WritebackArbiterInput"))
    assert(sv.contains("io_liveEnable"))
    assert(sv.contains("io_firePayloadValid"))
    assert(sv.contains("io_writeValid"))
    assert(sv.contains("io_writeTag"))
  }
}
