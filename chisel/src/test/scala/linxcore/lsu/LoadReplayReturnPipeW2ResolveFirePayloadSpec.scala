package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2ResolveFirePayloadReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)

  final case class Result(
      candidateValid: Boolean,
      payloadValid: Boolean,
      targetValid: Boolean,
      identityValid: Boolean,
      fireValid: Boolean,
      isComplete: Boolean,
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
      blockedByNoFire: Boolean,
      blockedByNoPayload: Boolean,
      blockedByIncomplete: Boolean,
      blockedByInvalidTarget: Boolean,
      blockedByInvalidBid: Boolean,
      blockedByInvalidGid: Boolean,
      blockedByInvalidRid: Boolean,
      blockedByInvalidIdentity: Boolean,
      invalidFireWithoutPayload: Boolean,
      invalidPayloadWithoutFire: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      resolveFire: Boolean,
      resolvePayloadValid: Boolean,
      resolveComplete: Boolean,
      resolveTargetIsAgu: Boolean,
      resolveTargetIsLda: Boolean,
      resolveTargetPipeIndex: Int,
      resolveBid: Id,
      resolveGid: Id,
      resolveRid: Id,
      resolveLoadLsId: Id,
      resolvePc: BigInt,
      resolveAddr: BigInt,
      resolveSize: Int,
      resolveData: BigInt): Result = {
    val active = enable && !flush
    val candidateValid = active && resolveFire
    val payloadValid = active && resolvePayloadValid
    val targetValid = resolveTargetIsAgu ^ resolveTargetIsLda
    val identityValid = resolveBid.valid && resolveGid.valid && resolveRid.valid
    val fireValid =
      candidateValid && resolvePayloadValid && resolveComplete && targetValid && identityValid
    val disabled = Id(valid = false)

    Result(
      candidateValid = candidateValid,
      payloadValid = payloadValid,
      targetValid = candidateValid && resolvePayloadValid && targetValid,
      identityValid = candidateValid && resolvePayloadValid && targetValid && identityValid,
      fireValid = fireValid,
      isComplete = fireValid,
      targetIsAgu = fireValid && resolveTargetIsAgu,
      targetIsLda = fireValid && resolveTargetIsLda,
      targetPipeIndex = if (fireValid) resolveTargetPipeIndex else 0,
      copiedBid = if (fireValid) resolveBid else disabled,
      copiedGid = if (fireValid) resolveGid else disabled,
      copiedRid = if (fireValid) resolveRid else disabled,
      copiedLoadLsId = if (fireValid) resolveLoadLsId else disabled,
      copiedPc = if (fireValid) resolvePc else 0,
      copiedAddr = if (fireValid) resolveAddr else 0,
      copiedSize = if (fireValid) resolveSize else 0,
      copiedData = if (fireValid) resolveData else 0,
      blockedByDisabled = !enable && resolveFire,
      blockedByFlush = enable && flush && resolveFire,
      blockedByNoFire = active && resolvePayloadValid && !resolveFire,
      blockedByNoPayload = candidateValid && !resolvePayloadValid,
      blockedByIncomplete = candidateValid && resolvePayloadValid && !resolveComplete,
      blockedByInvalidTarget = candidateValid && resolvePayloadValid && resolveComplete && !targetValid,
      blockedByInvalidBid =
        candidateValid && resolvePayloadValid && resolveComplete && targetValid && !resolveBid.valid,
      blockedByInvalidGid =
        candidateValid && resolvePayloadValid && resolveComplete && targetValid && !resolveGid.valid,
      blockedByInvalidRid =
        candidateValid && resolvePayloadValid && resolveComplete && targetValid && !resolveRid.valid,
      blockedByInvalidIdentity =
        candidateValid && resolvePayloadValid && resolveComplete && targetValid && !identityValid,
      invalidFireWithoutPayload = candidateValid && !resolvePayloadValid,
      invalidPayloadWithoutFire = active && !resolveFire && resolvePayloadValid)
  }
}

class LoadReplayReturnPipeW2ResolveFirePayloadSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2ResolveFirePayloadReference._

  test("copies resolve payload only when fire and payload are both valid") {
    val result = LoadReplayReturnPipeW2ResolveFirePayloadReference(
      enable = true,
      flush = false,
      resolveFire = true,
      resolvePayloadValid = true,
      resolveComplete = true,
      resolveTargetIsAgu = true,
      resolveTargetIsLda = false,
      resolveTargetPipeIndex = 0,
      resolveBid = Id(valid = true, wrap = true, value = 3),
      resolveGid = Id(valid = true, value = 4),
      resolveRid = Id(valid = true, value = 5),
      resolveLoadLsId = Id(valid = true, value = 6),
      resolvePc = BigInt("4000", 16),
      resolveAddr = BigInt("8080", 16),
      resolveSize = 8,
      resolveData = BigInt("1122334455667788", 16))

    assert(result.candidateValid)
    assert(result.payloadValid)
    assert(result.targetValid)
    assert(result.identityValid)
    assert(result.fireValid)
    assert(result.isComplete)
    assert(result.targetIsAgu)
    assert(!result.targetIsLda)
    assert(result.copiedBid.wrap)
    assert(result.copiedBid.value == 3)
    assert(result.copiedRid.value == 5)
    assert(result.copiedLoadLsId.value == 6)
    assert(result.copiedPc == BigInt("4000", 16))
    assert(result.copiedAddr == BigInt("8080", 16))
    assert(result.copiedSize == 8)
    assert(result.copiedData == BigInt("1122334455667788", 16))
  }

  test("blocks a fire pulse without a resolve payload") {
    val result = LoadReplayReturnPipeW2ResolveFirePayloadReference(
      enable = true,
      flush = false,
      resolveFire = true,
      resolvePayloadValid = false,
      resolveComplete = false,
      resolveTargetIsAgu = true,
      resolveTargetIsLda = false,
      resolveTargetPipeIndex = 0,
      resolveBid = Id(value = 1),
      resolveGid = Id(value = 2),
      resolveRid = Id(value = 3),
      resolveLoadLsId = Id(value = 4),
      resolvePc = 0x10,
      resolveAddr = 0x20,
      resolveSize = 4,
      resolveData = 0x55)

    assert(result.candidateValid)
    assert(!result.payloadValid)
    assert(!result.fireValid)
    assert(result.blockedByNoPayload)
    assert(result.invalidFireWithoutPayload)
    assert(!result.copiedBid.valid)
  }

  test("reports resolve payloads that did not receive a fire pulse") {
    val result = LoadReplayReturnPipeW2ResolveFirePayloadReference(
      enable = true,
      flush = false,
      resolveFire = false,
      resolvePayloadValid = true,
      resolveComplete = true,
      resolveTargetIsAgu = false,
      resolveTargetIsLda = true,
      resolveTargetPipeIndex = 0,
      resolveBid = Id(value = 1),
      resolveGid = Id(value = 2),
      resolveRid = Id(value = 3),
      resolveLoadLsId = Id(value = 4),
      resolvePc = 0x10,
      resolveAddr = 0x20,
      resolveSize = 4,
      resolveData = 0x55)

    assert(!result.candidateValid)
    assert(result.payloadValid)
    assert(!result.fireValid)
    assert(result.blockedByNoFire)
    assert(result.invalidPayloadWithoutFire)
  }

  test("suppresses fires while disabled or flushed") {
    val disabled = LoadReplayReturnPipeW2ResolveFirePayloadReference(
      enable = false,
      flush = false,
      resolveFire = true,
      resolvePayloadValid = true,
      resolveComplete = true,
      resolveTargetIsAgu = true,
      resolveTargetIsLda = false,
      resolveTargetPipeIndex = 0,
      resolveBid = Id(value = 1),
      resolveGid = Id(value = 2),
      resolveRid = Id(value = 3),
      resolveLoadLsId = Id(value = 4),
      resolvePc = 0x10,
      resolveAddr = 0x20,
      resolveSize = 4,
      resolveData = 0x55)
    val flushed = LoadReplayReturnPipeW2ResolveFirePayloadReference(
      enable = true,
      flush = true,
      resolveFire = true,
      resolvePayloadValid = true,
      resolveComplete = true,
      resolveTargetIsAgu = true,
      resolveTargetIsLda = false,
      resolveTargetPipeIndex = 0,
      resolveBid = Id(value = 1),
      resolveGid = Id(value = 2),
      resolveRid = Id(value = 3),
      resolveLoadLsId = Id(value = 4),
      resolvePc = 0x10,
      resolveAddr = 0x20,
      resolveSize = 4,
      resolveData = 0x55)

    assert(!disabled.candidateValid)
    assert(!disabled.fireValid)
    assert(disabled.blockedByDisabled)
    assert(!flushed.fireValid)
    assert(flushed.blockedByFlush)
  }

  test("blocks incomplete, invalid-target, and invalid-identity payloads") {
    val incomplete = LoadReplayReturnPipeW2ResolveFirePayloadReference(
      enable = true,
      flush = false,
      resolveFire = true,
      resolvePayloadValid = true,
      resolveComplete = false,
      resolveTargetIsAgu = true,
      resolveTargetIsLda = false,
      resolveTargetPipeIndex = 0,
      resolveBid = Id(value = 1),
      resolveGid = Id(value = 2),
      resolveRid = Id(value = 3),
      resolveLoadLsId = Id(value = 4),
      resolvePc = 0x10,
      resolveAddr = 0x20,
      resolveSize = 4,
      resolveData = 0x55)
    val invalidTarget = LoadReplayReturnPipeW2ResolveFirePayloadReference(
      enable = true,
      flush = false,
      resolveFire = true,
      resolvePayloadValid = true,
      resolveComplete = true,
      resolveTargetIsAgu = true,
      resolveTargetIsLda = true,
      resolveTargetPipeIndex = 0,
      resolveBid = Id(value = 1),
      resolveGid = Id(value = 2),
      resolveRid = Id(value = 3),
      resolveLoadLsId = Id(value = 4),
      resolvePc = 0x10,
      resolveAddr = 0x20,
      resolveSize = 4,
      resolveData = 0x55)
    val invalidIdentity = LoadReplayReturnPipeW2ResolveFirePayloadReference(
      enable = true,
      flush = false,
      resolveFire = true,
      resolvePayloadValid = true,
      resolveComplete = true,
      resolveTargetIsAgu = true,
      resolveTargetIsLda = false,
      resolveTargetPipeIndex = 0,
      resolveBid = Id(value = 1),
      resolveGid = Id(valid = false, value = 2),
      resolveRid = Id(value = 3),
      resolveLoadLsId = Id(value = 4),
      resolvePc = 0x10,
      resolveAddr = 0x20,
      resolveSize = 4,
      resolveData = 0x55)

    assert(!incomplete.fireValid)
    assert(incomplete.blockedByIncomplete)
    assert(!invalidTarget.fireValid)
    assert(invalidTarget.blockedByInvalidTarget)
    assert(!invalidIdentity.fireValid)
    assert(invalidIdentity.blockedByInvalidGid)
    assert(invalidIdentity.blockedByInvalidIdentity)
  }

  test("Chisel LoadReplayReturnPipeW2ResolveFirePayload elaborates fire-qualified diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2ResolveFirePayload)

    assert(sv.contains("module LoadReplayReturnPipeW2ResolveFirePayload"))
    assert(sv.contains("io_resolveFire"))
    assert(sv.contains("io_resolvePayloadValid"))
    assert(sv.contains("io_fireValid"))
    assert(sv.contains("io_fireBid_valid"))
    assert(sv.contains("io_fireRid_value"))
    assert(sv.contains("io_invalidPayloadWithoutFire"))
  }
}
