package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2WakeupFirePayloadReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)

  final case class Result(
      candidateValid: Boolean,
      payloadValid: Boolean,
      targetValid: Boolean,
      identityValid: Boolean,
      required: Boolean,
      destinationValid: Boolean,
      reducedGprWakeup: Boolean,
      nonGprWakeup: Boolean,
      fireValid: Boolean,
      targetIsAgu: Boolean,
      targetIsLda: Boolean,
      targetPipeIndex: Int,
      copiedBid: Id,
      copiedGid: Id,
      copiedRid: Id,
      copiedLoadLsId: Id,
      copiedPc: BigInt,
      copiedKind: String,
      copiedArchTag: Int,
      copiedRelTag: Int,
      copiedPhysTag: Int,
      copiedOldPhysTag: Int,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoFire: Boolean,
      blockedByNoPayload: Boolean,
      blockedByInvalidTarget: Boolean,
      blockedByInvalidIdentity: Boolean,
      blockedByWakeupNotRequired: Boolean,
      blockedByNoDestination: Boolean,
      invalidFireWithoutPayload: Boolean,
      invalidPayloadWithoutFire: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      wakeupFire: Boolean,
      wakeupPayloadValid: Boolean,
      wakeupTargetValid: Boolean,
      wakeupIdentityValid: Boolean,
      wakeupRequired: Boolean,
      wakeupDestinationValid: Boolean,
      wakeupReducedGpr: Boolean,
      wakeupNonGpr: Boolean,
      wakeupTargetIsAgu: Boolean,
      wakeupTargetIsLda: Boolean,
      wakeupTargetPipeIndex: Int,
      wakeupBid: Id,
      wakeupGid: Id,
      wakeupRid: Id,
      wakeupLoadLsId: Id,
      wakeupPc: BigInt,
      wakeupKind: String,
      wakeupArchTag: Int,
      wakeupRelTag: Int,
      wakeupPhysTag: Int,
      wakeupOldPhysTag: Int): Result = {
    val active = enable && !flush
    val candidateValid = active && wakeupFire
    val payloadValid = active && wakeupPayloadValid
    val fireValid = candidateValid && wakeupPayloadValid && wakeupTargetValid &&
      wakeupIdentityValid && wakeupRequired && wakeupDestinationValid
    val disabled = Id(valid = false)

    Result(
      candidateValid = candidateValid,
      payloadValid = payloadValid,
      targetValid = candidateValid && wakeupTargetValid,
      identityValid = candidateValid && wakeupTargetValid && wakeupIdentityValid,
      required = candidateValid && wakeupTargetValid && wakeupIdentityValid && wakeupRequired,
      destinationValid = candidateValid && wakeupTargetValid &&
        wakeupIdentityValid && wakeupRequired && wakeupDestinationValid,
      reducedGprWakeup = fireValid && wakeupReducedGpr,
      nonGprWakeup = fireValid && wakeupNonGpr,
      fireValid = fireValid,
      targetIsAgu = fireValid && wakeupTargetIsAgu,
      targetIsLda = fireValid && wakeupTargetIsLda,
      targetPipeIndex = if (fireValid) wakeupTargetPipeIndex else 0,
      copiedBid = if (fireValid) wakeupBid else disabled,
      copiedGid = if (fireValid) wakeupGid else disabled,
      copiedRid = if (fireValid) wakeupRid else disabled,
      copiedLoadLsId = if (fireValid) wakeupLoadLsId else disabled,
      copiedPc = if (fireValid) wakeupPc else 0,
      copiedKind = if (fireValid) wakeupKind else "None",
      copiedArchTag = if (fireValid) wakeupArchTag else 0,
      copiedRelTag = if (fireValid) wakeupRelTag else 0,
      copiedPhysTag = if (fireValid) wakeupPhysTag else 0,
      copiedOldPhysTag = if (fireValid) wakeupOldPhysTag else 0,
      blockedByDisabled = !enable && wakeupFire,
      blockedByFlush = enable && flush && wakeupFire,
      blockedByNoFire = active && wakeupPayloadValid && !wakeupFire,
      blockedByNoPayload = candidateValid && !wakeupPayloadValid,
      blockedByInvalidTarget = candidateValid && !wakeupTargetValid,
      blockedByInvalidIdentity = candidateValid && wakeupTargetValid && !wakeupIdentityValid,
      blockedByWakeupNotRequired = candidateValid && wakeupTargetValid &&
        wakeupIdentityValid && !wakeupRequired,
      blockedByNoDestination = candidateValid && wakeupTargetValid &&
        wakeupIdentityValid && wakeupRequired && !wakeupDestinationValid,
      invalidFireWithoutPayload = candidateValid && !wakeupPayloadValid,
      invalidPayloadWithoutFire = active && !wakeupFire && wakeupPayloadValid)
  }
}

class LoadReplayReturnPipeW2WakeupFirePayloadSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2WakeupFirePayloadReference._

  test("copies GPR wakeup payload only when fire and payload are both valid") {
    val result = LoadReplayReturnPipeW2WakeupFirePayloadReference(
      enable = true,
      flush = false,
      wakeupFire = true,
      wakeupPayloadValid = true,
      wakeupTargetValid = true,
      wakeupIdentityValid = true,
      wakeupRequired = true,
      wakeupDestinationValid = true,
      wakeupReducedGpr = true,
      wakeupNonGpr = false,
      wakeupTargetIsAgu = true,
      wakeupTargetIsLda = false,
      wakeupTargetPipeIndex = 0,
      wakeupBid = Id(valid = true, wrap = true, value = 3),
      wakeupGid = Id(valid = true, value = 4),
      wakeupRid = Id(valid = true, value = 5),
      wakeupLoadLsId = Id(valid = true, value = 6),
      wakeupPc = BigInt("4000", 16),
      wakeupKind = "Gpr",
      wakeupArchTag = 7,
      wakeupRelTag = 8,
      wakeupPhysTag = 42,
      wakeupOldPhysTag = 9)

    assert(result.candidateValid)
    assert(result.payloadValid)
    assert(result.targetValid)
    assert(result.identityValid)
    assert(result.required)
    assert(result.destinationValid)
    assert(result.reducedGprWakeup)
    assert(!result.nonGprWakeup)
    assert(result.fireValid)
    assert(result.targetIsAgu)
    assert(!result.targetIsLda)
    assert(result.copiedBid.wrap)
    assert(result.copiedBid.value == 3)
    assert(result.copiedRid.value == 5)
    assert(result.copiedLoadLsId.value == 6)
    assert(result.copiedPc == BigInt("4000", 16))
    assert(result.copiedKind == "Gpr")
    assert(result.copiedArchTag == 7)
    assert(result.copiedPhysTag == 42)
    assert(result.copiedOldPhysTag == 9)
  }

  test("preserves scalar local-link wakeups as non-GPR fire payloads") {
    val result = LoadReplayReturnPipeW2WakeupFirePayloadReference(
      enable = true,
      flush = false,
      wakeupFire = true,
      wakeupPayloadValid = true,
      wakeupTargetValid = true,
      wakeupIdentityValid = true,
      wakeupRequired = true,
      wakeupDestinationValid = true,
      wakeupReducedGpr = false,
      wakeupNonGpr = true,
      wakeupTargetIsAgu = false,
      wakeupTargetIsLda = true,
      wakeupTargetPipeIndex = 0,
      wakeupBid = Id(value = 1),
      wakeupGid = Id(value = 2),
      wakeupRid = Id(value = 3),
      wakeupLoadLsId = Id(value = 4),
      wakeupPc = 0x10,
      wakeupKind = "T",
      wakeupArchTag = 31,
      wakeupRelTag = 0,
      wakeupPhysTag = 6,
      wakeupOldPhysTag = 5)

    assert(result.fireValid)
    assert(!result.reducedGprWakeup)
    assert(result.nonGprWakeup)
    assert(result.targetIsLda)
    assert(result.copiedKind == "T")
    assert(result.copiedPhysTag == 6)
  }

  test("blocks a wakeup fire pulse without a wakeup payload") {
    val result = LoadReplayReturnPipeW2WakeupFirePayloadReference(
      enable = true,
      flush = false,
      wakeupFire = true,
      wakeupPayloadValid = false,
      wakeupTargetValid = true,
      wakeupIdentityValid = true,
      wakeupRequired = true,
      wakeupDestinationValid = true,
      wakeupReducedGpr = true,
      wakeupNonGpr = false,
      wakeupTargetIsAgu = true,
      wakeupTargetIsLda = false,
      wakeupTargetPipeIndex = 0,
      wakeupBid = Id(value = 1),
      wakeupGid = Id(value = 2),
      wakeupRid = Id(value = 3),
      wakeupLoadLsId = Id(value = 4),
      wakeupPc = 0x10,
      wakeupKind = "Gpr",
      wakeupArchTag = 5,
      wakeupRelTag = 0,
      wakeupPhysTag = 11,
      wakeupOldPhysTag = 2)

    assert(result.candidateValid)
    assert(!result.payloadValid)
    assert(!result.fireValid)
    assert(result.blockedByNoPayload)
    assert(result.invalidFireWithoutPayload)
    assert(!result.copiedBid.valid)
    assert(result.copiedPhysTag == 0)
  }

  test("reports wakeup payloads that did not receive a fire pulse") {
    val result = LoadReplayReturnPipeW2WakeupFirePayloadReference(
      enable = true,
      flush = false,
      wakeupFire = false,
      wakeupPayloadValid = true,
      wakeupTargetValid = true,
      wakeupIdentityValid = true,
      wakeupRequired = true,
      wakeupDestinationValid = true,
      wakeupReducedGpr = false,
      wakeupNonGpr = true,
      wakeupTargetIsAgu = false,
      wakeupTargetIsLda = true,
      wakeupTargetPipeIndex = 0,
      wakeupBid = Id(value = 1),
      wakeupGid = Id(value = 2),
      wakeupRid = Id(value = 3),
      wakeupLoadLsId = Id(value = 4),
      wakeupPc = 0x10,
      wakeupKind = "U",
      wakeupArchTag = 30,
      wakeupRelTag = 0,
      wakeupPhysTag = 12,
      wakeupOldPhysTag = 2)

    assert(!result.candidateValid)
    assert(result.payloadValid)
    assert(!result.fireValid)
    assert(result.blockedByNoFire)
    assert(result.invalidPayloadWithoutFire)
  }

  test("suppresses fires while disabled or flushed") {
    val disabled = LoadReplayReturnPipeW2WakeupFirePayloadReference(
      enable = false,
      flush = false,
      wakeupFire = true,
      wakeupPayloadValid = true,
      wakeupTargetValid = true,
      wakeupIdentityValid = true,
      wakeupRequired = true,
      wakeupDestinationValid = true,
      wakeupReducedGpr = true,
      wakeupNonGpr = false,
      wakeupTargetIsAgu = true,
      wakeupTargetIsLda = false,
      wakeupTargetPipeIndex = 0,
      wakeupBid = Id(value = 1),
      wakeupGid = Id(value = 2),
      wakeupRid = Id(value = 3),
      wakeupLoadLsId = Id(value = 4),
      wakeupPc = 0x10,
      wakeupKind = "Gpr",
      wakeupArchTag = 5,
      wakeupRelTag = 0,
      wakeupPhysTag = 11,
      wakeupOldPhysTag = 2)
    val flushed = LoadReplayReturnPipeW2WakeupFirePayloadReference(
      enable = true,
      flush = true,
      wakeupFire = true,
      wakeupPayloadValid = true,
      wakeupTargetValid = true,
      wakeupIdentityValid = true,
      wakeupRequired = true,
      wakeupDestinationValid = true,
      wakeupReducedGpr = true,
      wakeupNonGpr = false,
      wakeupTargetIsAgu = true,
      wakeupTargetIsLda = false,
      wakeupTargetPipeIndex = 0,
      wakeupBid = Id(value = 1),
      wakeupGid = Id(value = 2),
      wakeupRid = Id(value = 3),
      wakeupLoadLsId = Id(value = 4),
      wakeupPc = 0x10,
      wakeupKind = "Gpr",
      wakeupArchTag = 5,
      wakeupRelTag = 0,
      wakeupPhysTag = 11,
      wakeupOldPhysTag = 2)

    assert(!disabled.candidateValid)
    assert(!disabled.fireValid)
    assert(disabled.blockedByDisabled)
    assert(!flushed.fireValid)
    assert(flushed.blockedByFlush)
  }

  test("blocks invalid target identity wakeup-required and destination payloads") {
    val invalidTarget = LoadReplayReturnPipeW2WakeupFirePayloadReference(
      enable = true,
      flush = false,
      wakeupFire = true,
      wakeupPayloadValid = false,
      wakeupTargetValid = false,
      wakeupIdentityValid = true,
      wakeupRequired = true,
      wakeupDestinationValid = true,
      wakeupReducedGpr = true,
      wakeupNonGpr = false,
      wakeupTargetIsAgu = true,
      wakeupTargetIsLda = true,
      wakeupTargetPipeIndex = 0,
      wakeupBid = Id(value = 1),
      wakeupGid = Id(value = 2),
      wakeupRid = Id(value = 3),
      wakeupLoadLsId = Id(value = 4),
      wakeupPc = 0x10,
      wakeupKind = "Gpr",
      wakeupArchTag = 5,
      wakeupRelTag = 0,
      wakeupPhysTag = 11,
      wakeupOldPhysTag = 2)
    val invalidIdentity = LoadReplayReturnPipeW2WakeupFirePayloadReference(
      enable = true,
      flush = false,
      wakeupFire = true,
      wakeupPayloadValid = false,
      wakeupTargetValid = true,
      wakeupIdentityValid = false,
      wakeupRequired = true,
      wakeupDestinationValid = true,
      wakeupReducedGpr = true,
      wakeupNonGpr = false,
      wakeupTargetIsAgu = true,
      wakeupTargetIsLda = false,
      wakeupTargetPipeIndex = 0,
      wakeupBid = Id(value = 1),
      wakeupGid = Id(valid = false, value = 2),
      wakeupRid = Id(value = 3),
      wakeupLoadLsId = Id(value = 4),
      wakeupPc = 0x10,
      wakeupKind = "Gpr",
      wakeupArchTag = 5,
      wakeupRelTag = 0,
      wakeupPhysTag = 11,
      wakeupOldPhysTag = 2)
    val notRequired = LoadReplayReturnPipeW2WakeupFirePayloadReference(
      enable = true,
      flush = false,
      wakeupFire = true,
      wakeupPayloadValid = false,
      wakeupTargetValid = true,
      wakeupIdentityValid = true,
      wakeupRequired = false,
      wakeupDestinationValid = true,
      wakeupReducedGpr = true,
      wakeupNonGpr = false,
      wakeupTargetIsAgu = true,
      wakeupTargetIsLda = false,
      wakeupTargetPipeIndex = 0,
      wakeupBid = Id(value = 1),
      wakeupGid = Id(value = 2),
      wakeupRid = Id(value = 3),
      wakeupLoadLsId = Id(value = 4),
      wakeupPc = 0x10,
      wakeupKind = "Gpr",
      wakeupArchTag = 5,
      wakeupRelTag = 0,
      wakeupPhysTag = 11,
      wakeupOldPhysTag = 2)
    val noDestination = LoadReplayReturnPipeW2WakeupFirePayloadReference(
      enable = true,
      flush = false,
      wakeupFire = true,
      wakeupPayloadValid = false,
      wakeupTargetValid = true,
      wakeupIdentityValid = true,
      wakeupRequired = true,
      wakeupDestinationValid = false,
      wakeupReducedGpr = false,
      wakeupNonGpr = false,
      wakeupTargetIsAgu = true,
      wakeupTargetIsLda = false,
      wakeupTargetPipeIndex = 0,
      wakeupBid = Id(value = 1),
      wakeupGid = Id(value = 2),
      wakeupRid = Id(value = 3),
      wakeupLoadLsId = Id(value = 4),
      wakeupPc = 0x10,
      wakeupKind = "None",
      wakeupArchTag = 0,
      wakeupRelTag = 0,
      wakeupPhysTag = 0,
      wakeupOldPhysTag = 0)

    assert(!invalidTarget.fireValid)
    assert(invalidTarget.blockedByInvalidTarget)
    assert(invalidTarget.blockedByNoPayload)
    assert(!invalidIdentity.fireValid)
    assert(invalidIdentity.blockedByInvalidIdentity)
    assert(!notRequired.fireValid)
    assert(notRequired.blockedByWakeupNotRequired)
    assert(!noDestination.fireValid)
    assert(noDestination.blockedByNoDestination)
  }

  test("Chisel LoadReplayReturnPipeW2WakeupFirePayload elaborates fire-qualified diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2WakeupFirePayload)

    assert(sv.contains("module LoadReplayReturnPipeW2WakeupFirePayload"))
    assert(sv.contains("io_wakeupFire"))
    assert(sv.contains("io_wakeupPayloadValid"))
    assert(sv.contains("io_fireValid"))
    assert(sv.contains("io_fireBid_valid"))
    assert(sv.contains("io_firePhysTag"))
    assert(sv.contains("io_nonGprWakeup"))
    assert(sv.contains("io_invalidPayloadWithoutFire"))
  }
}
