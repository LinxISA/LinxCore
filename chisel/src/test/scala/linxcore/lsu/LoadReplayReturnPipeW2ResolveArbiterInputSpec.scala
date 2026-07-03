package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2ResolveArbiterInputReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)

  final case class Result(
      active: Boolean,
      candidateValid: Boolean,
      resolveValid: Boolean,
      targetIsAgu: Boolean,
      targetIsLda: Boolean,
      targetPipeIndex: Int,
      copiedBid: Id,
      copiedGid: Id,
      copiedRid: Id,
      copiedLoadLsId: Id,
      copiedPc: BigInt,
      copiedAddr: BigInt,
      copiedSize: Int,
      copiedData: BigInt,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoPayload: Boolean,
      blockedByLiveDisabled: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveEnable: Boolean,
      firePayloadValid: Boolean,
      fireTargetIsAgu: Boolean,
      fireTargetIsLda: Boolean,
      fireTargetPipeIndex: Int,
      fireBid: Id,
      fireGid: Id,
      fireRid: Id,
      fireLoadLsId: Id,
      firePc: BigInt,
      fireAddr: BigInt,
      fireSize: Int,
      fireData: BigInt): Result = {
    val active = enable && !flush
    val candidateValid = active && firePayloadValid
    val resolveValid = candidateValid && liveEnable
    val disabled = Id(valid = false)

    Result(
      active = active,
      candidateValid = candidateValid,
      resolveValid = resolveValid,
      targetIsAgu = resolveValid && fireTargetIsAgu,
      targetIsLda = resolveValid && fireTargetIsLda,
      targetPipeIndex = if (resolveValid) fireTargetPipeIndex else 0,
      copiedBid = if (resolveValid) fireBid else disabled,
      copiedGid = if (resolveValid) fireGid else disabled,
      copiedRid = if (resolveValid) fireRid else disabled,
      copiedLoadLsId = if (resolveValid) fireLoadLsId else disabled,
      copiedPc = if (resolveValid) firePc else 0,
      copiedAddr = if (resolveValid) fireAddr else 0,
      copiedSize = if (resolveValid) fireSize else 0,
      copiedData = if (resolveValid) fireData else 0,
      blockedByDisabled = !enable && firePayloadValid,
      blockedByFlush = enable && flush && firePayloadValid,
      blockedByNoPayload = active && !firePayloadValid,
      blockedByLiveDisabled = candidateValid && !liveEnable)
  }
}

class LoadReplayReturnPipeW2ResolveArbiterInputSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2ResolveArbiterInputReference._

  test("holds a valid W2 resolve payload behind the live gate") {
    val result = LoadReplayReturnPipeW2ResolveArbiterInputReference(
      enable = true,
      flush = false,
      liveEnable = false,
      firePayloadValid = true,
      fireTargetIsAgu = true,
      fireTargetIsLda = false,
      fireTargetPipeIndex = 0,
      fireBid = Id(valid = true, wrap = true, value = 3),
      fireGid = Id(value = 4),
      fireRid = Id(value = 5),
      fireLoadLsId = Id(value = 6),
      firePc = BigInt("4000", 16),
      fireAddr = BigInt("8080", 16),
      fireSize = 8,
      fireData = BigInt("1122334455667788", 16))

    assert(result.active)
    assert(result.candidateValid)
    assert(!result.resolveValid)
    assert(!result.copiedBid.valid)
    assert(result.copiedPc == 0)
    assert(result.blockedByLiveDisabled)
  }

  test("emits a live resolve input when the live gate is enabled") {
    val result = LoadReplayReturnPipeW2ResolveArbiterInputReference(
      enable = true,
      flush = false,
      liveEnable = true,
      firePayloadValid = true,
      fireTargetIsAgu = false,
      fireTargetIsLda = true,
      fireTargetPipeIndex = 0,
      fireBid = Id(value = 7),
      fireGid = Id(value = 8),
      fireRid = Id(value = 9),
      fireLoadLsId = Id(value = 10),
      firePc = BigInt("5000", 16),
      fireAddr = BigInt("9080", 16),
      fireSize = 4,
      fireData = BigInt("8877665544332211", 16))

    assert(result.candidateValid)
    assert(result.resolveValid)
    assert(!result.targetIsAgu)
    assert(result.targetIsLda)
    assert(result.copiedBid.value == 7)
    assert(result.copiedGid.value == 8)
    assert(result.copiedRid.value == 9)
    assert(result.copiedLoadLsId.value == 10)
    assert(result.copiedPc == BigInt("5000", 16))
    assert(result.copiedAddr == BigInt("9080", 16))
    assert(result.copiedSize == 4)
    assert(result.copiedData == BigInt("8877665544332211", 16))
  }

  test("reports disabled and flushed payloads without stale resolve fields") {
    val disabled = LoadReplayReturnPipeW2ResolveArbiterInputReference(
      enable = false,
      flush = false,
      liveEnable = true,
      firePayloadValid = true,
      fireTargetIsAgu = true,
      fireTargetIsLda = false,
      fireTargetPipeIndex = 0,
      fireBid = Id(value = 1),
      fireGid = Id(value = 2),
      fireRid = Id(value = 3),
      fireLoadLsId = Id(value = 4),
      firePc = 0x10,
      fireAddr = 0x20,
      fireSize = 4,
      fireData = 0x55)
    val flushed = LoadReplayReturnPipeW2ResolveArbiterInputReference(
      enable = true,
      flush = true,
      liveEnable = true,
      firePayloadValid = true,
      fireTargetIsAgu = true,
      fireTargetIsLda = false,
      fireTargetPipeIndex = 0,
      fireBid = Id(value = 1),
      fireGid = Id(value = 2),
      fireRid = Id(value = 3),
      fireLoadLsId = Id(value = 4),
      firePc = 0x10,
      fireAddr = 0x20,
      fireSize = 4,
      fireData = 0x55)

    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)
    assert(!disabled.resolveValid)
    assert(!disabled.copiedBid.valid)
    assert(flushed.blockedByFlush)
    assert(!flushed.resolveValid)
    assert(flushed.copiedAddr == 0)
  }

  test("reports an active cycle with no resolve fire payload") {
    val result = LoadReplayReturnPipeW2ResolveArbiterInputReference(
      enable = true,
      flush = false,
      liveEnable = true,
      firePayloadValid = false,
      fireTargetIsAgu = true,
      fireTargetIsLda = false,
      fireTargetPipeIndex = 0,
      fireBid = Id(value = 1),
      fireGid = Id(value = 2),
      fireRid = Id(value = 3),
      fireLoadLsId = Id(value = 4),
      firePc = 0x10,
      fireAddr = 0x20,
      fireSize = 4,
      fireData = 0x55)

    assert(result.active)
    assert(!result.candidateValid)
    assert(!result.resolveValid)
    assert(result.blockedByNoPayload)
  }

  test("Chisel LoadReplayReturnPipeW2ResolveArbiterInput elaborates resolve candidate fields") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2ResolveArbiterInput)

    assert(sv.contains("module LoadReplayReturnPipeW2ResolveArbiterInput"))
    assert(sv.contains("io_liveEnable"))
    assert(sv.contains("io_firePayloadValid"))
    assert(sv.contains("io_resolveValid"))
    assert(sv.contains("io_resolveBid"))
  }
}
