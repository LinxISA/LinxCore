package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2WritebackFirePayloadReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)

  final case class Result(
      candidateValid: Boolean,
      payloadValid: Boolean,
      targetValid: Boolean,
      identityValid: Boolean,
      destinationValid: Boolean,
      gprDestination: Boolean,
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
      copiedData: BigInt,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoFire: Boolean,
      blockedByNoPayload: Boolean,
      blockedByInvalidTarget: Boolean,
      blockedByInvalidIdentity: Boolean,
      blockedByNoDestination: Boolean,
      blockedByNonGprDestination: Boolean,
      invalidFireWithoutPayload: Boolean,
      invalidPayloadWithoutFire: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      writebackFire: Boolean,
      writebackPayloadValid: Boolean,
      writebackTargetValid: Boolean,
      writebackIdentityValid: Boolean,
      writebackDestinationValid: Boolean,
      writebackGprDestination: Boolean,
      writebackTargetIsAgu: Boolean,
      writebackTargetIsLda: Boolean,
      writebackTargetPipeIndex: Int,
      writebackBid: Id,
      writebackGid: Id,
      writebackRid: Id,
      writebackLoadLsId: Id,
      writebackPc: BigInt,
      writebackKind: String,
      writebackArchTag: Int,
      writebackRelTag: Int,
      writebackPhysTag: Int,
      writebackOldPhysTag: Int,
      writebackData: BigInt): Result = {
    val active = enable && !flush
    val candidateValid = active && writebackFire
    val payloadValid = active && writebackPayloadValid
    val destinationShapeValid = writebackDestinationValid && writebackGprDestination
    val fireValid = candidateValid && writebackPayloadValid && writebackTargetValid &&
      writebackIdentityValid && destinationShapeValid
    val disabled = Id(valid = false)

    Result(
      candidateValid = candidateValid,
      payloadValid = payloadValid,
      targetValid = candidateValid && writebackTargetValid,
      identityValid = candidateValid && writebackTargetValid && writebackIdentityValid,
      destinationValid = candidateValid && writebackTargetValid &&
        writebackIdentityValid && writebackDestinationValid,
      gprDestination = candidateValid && writebackTargetValid &&
        writebackIdentityValid && destinationShapeValid,
      fireValid = fireValid,
      targetIsAgu = fireValid && writebackTargetIsAgu,
      targetIsLda = fireValid && writebackTargetIsLda,
      targetPipeIndex = if (fireValid) writebackTargetPipeIndex else 0,
      copiedBid = if (fireValid) writebackBid else disabled,
      copiedGid = if (fireValid) writebackGid else disabled,
      copiedRid = if (fireValid) writebackRid else disabled,
      copiedLoadLsId = if (fireValid) writebackLoadLsId else disabled,
      copiedPc = if (fireValid) writebackPc else 0,
      copiedKind = if (fireValid) writebackKind else "None",
      copiedArchTag = if (fireValid) writebackArchTag else 0,
      copiedRelTag = if (fireValid) writebackRelTag else 0,
      copiedPhysTag = if (fireValid) writebackPhysTag else 0,
      copiedOldPhysTag = if (fireValid) writebackOldPhysTag else 0,
      copiedData = if (fireValid) writebackData else 0,
      blockedByDisabled = !enable && writebackFire,
      blockedByFlush = enable && flush && writebackFire,
      blockedByNoFire = active && writebackPayloadValid && !writebackFire,
      blockedByNoPayload = candidateValid && !writebackPayloadValid,
      blockedByInvalidTarget = candidateValid && !writebackTargetValid,
      blockedByInvalidIdentity = candidateValid && writebackTargetValid && !writebackIdentityValid,
      blockedByNoDestination = candidateValid && writebackTargetValid &&
        writebackIdentityValid && !writebackDestinationValid,
      blockedByNonGprDestination = candidateValid && writebackTargetValid &&
        writebackIdentityValid && writebackDestinationValid && !writebackGprDestination,
      invalidFireWithoutPayload = candidateValid && !writebackPayloadValid,
      invalidPayloadWithoutFire = active && !writebackFire && writebackPayloadValid)
  }
}

class LoadReplayReturnPipeW2WritebackFirePayloadSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2WritebackFirePayloadReference._

  test("copies writeback payload only when fire and payload are both valid") {
    val result = LoadReplayReturnPipeW2WritebackFirePayloadReference(
      enable = true,
      flush = false,
      writebackFire = true,
      writebackPayloadValid = true,
      writebackTargetValid = true,
      writebackIdentityValid = true,
      writebackDestinationValid = true,
      writebackGprDestination = true,
      writebackTargetIsAgu = true,
      writebackTargetIsLda = false,
      writebackTargetPipeIndex = 0,
      writebackBid = Id(valid = true, wrap = true, value = 3),
      writebackGid = Id(valid = true, value = 4),
      writebackRid = Id(valid = true, value = 5),
      writebackLoadLsId = Id(valid = true, value = 6),
      writebackPc = BigInt("4000", 16),
      writebackKind = "Gpr",
      writebackArchTag = 7,
      writebackRelTag = 8,
      writebackPhysTag = 42,
      writebackOldPhysTag = 9,
      writebackData = BigInt("1122334455667788", 16))

    assert(result.candidateValid)
    assert(result.payloadValid)
    assert(result.targetValid)
    assert(result.identityValid)
    assert(result.destinationValid)
    assert(result.gprDestination)
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
    assert(result.copiedData == BigInt("1122334455667788", 16))
  }

  test("blocks a fire pulse without a writeback payload") {
    val result = LoadReplayReturnPipeW2WritebackFirePayloadReference(
      enable = true,
      flush = false,
      writebackFire = true,
      writebackPayloadValid = false,
      writebackTargetValid = true,
      writebackIdentityValid = true,
      writebackDestinationValid = true,
      writebackGprDestination = true,
      writebackTargetIsAgu = true,
      writebackTargetIsLda = false,
      writebackTargetPipeIndex = 0,
      writebackBid = Id(value = 1),
      writebackGid = Id(value = 2),
      writebackRid = Id(value = 3),
      writebackLoadLsId = Id(value = 4),
      writebackPc = 0x10,
      writebackKind = "Gpr",
      writebackArchTag = 5,
      writebackRelTag = 0,
      writebackPhysTag = 11,
      writebackOldPhysTag = 2,
      writebackData = 0x55)

    assert(result.candidateValid)
    assert(!result.payloadValid)
    assert(!result.fireValid)
    assert(result.blockedByNoPayload)
    assert(result.invalidFireWithoutPayload)
    assert(!result.copiedBid.valid)
    assert(result.copiedPhysTag == 0)
  }

  test("reports writeback payloads that did not receive a fire pulse") {
    val result = LoadReplayReturnPipeW2WritebackFirePayloadReference(
      enable = true,
      flush = false,
      writebackFire = false,
      writebackPayloadValid = true,
      writebackTargetValid = true,
      writebackIdentityValid = true,
      writebackDestinationValid = true,
      writebackGprDestination = true,
      writebackTargetIsAgu = false,
      writebackTargetIsLda = true,
      writebackTargetPipeIndex = 0,
      writebackBid = Id(value = 1),
      writebackGid = Id(value = 2),
      writebackRid = Id(value = 3),
      writebackLoadLsId = Id(value = 4),
      writebackPc = 0x10,
      writebackKind = "Gpr",
      writebackArchTag = 5,
      writebackRelTag = 0,
      writebackPhysTag = 11,
      writebackOldPhysTag = 2,
      writebackData = 0x55)

    assert(!result.candidateValid)
    assert(result.payloadValid)
    assert(!result.fireValid)
    assert(result.blockedByNoFire)
    assert(result.invalidPayloadWithoutFire)
  }

  test("suppresses fires while disabled or flushed") {
    val disabled = LoadReplayReturnPipeW2WritebackFirePayloadReference(
      enable = false,
      flush = false,
      writebackFire = true,
      writebackPayloadValid = true,
      writebackTargetValid = true,
      writebackIdentityValid = true,
      writebackDestinationValid = true,
      writebackGprDestination = true,
      writebackTargetIsAgu = true,
      writebackTargetIsLda = false,
      writebackTargetPipeIndex = 0,
      writebackBid = Id(value = 1),
      writebackGid = Id(value = 2),
      writebackRid = Id(value = 3),
      writebackLoadLsId = Id(value = 4),
      writebackPc = 0x10,
      writebackKind = "Gpr",
      writebackArchTag = 5,
      writebackRelTag = 0,
      writebackPhysTag = 11,
      writebackOldPhysTag = 2,
      writebackData = 0x55)
    val flushed = LoadReplayReturnPipeW2WritebackFirePayloadReference(
      enable = true,
      flush = true,
      writebackFire = true,
      writebackPayloadValid = true,
      writebackTargetValid = true,
      writebackIdentityValid = true,
      writebackDestinationValid = true,
      writebackGprDestination = true,
      writebackTargetIsAgu = true,
      writebackTargetIsLda = false,
      writebackTargetPipeIndex = 0,
      writebackBid = Id(value = 1),
      writebackGid = Id(value = 2),
      writebackRid = Id(value = 3),
      writebackLoadLsId = Id(value = 4),
      writebackPc = 0x10,
      writebackKind = "Gpr",
      writebackArchTag = 5,
      writebackRelTag = 0,
      writebackPhysTag = 11,
      writebackOldPhysTag = 2,
      writebackData = 0x55)

    assert(!disabled.candidateValid)
    assert(!disabled.fireValid)
    assert(disabled.blockedByDisabled)
    assert(!flushed.fireValid)
    assert(flushed.blockedByFlush)
  }

  test("blocks invalid target and identity payloads") {
    val invalidTarget = LoadReplayReturnPipeW2WritebackFirePayloadReference(
      enable = true,
      flush = false,
      writebackFire = true,
      writebackPayloadValid = false,
      writebackTargetValid = false,
      writebackIdentityValid = true,
      writebackDestinationValid = true,
      writebackGprDestination = true,
      writebackTargetIsAgu = true,
      writebackTargetIsLda = true,
      writebackTargetPipeIndex = 0,
      writebackBid = Id(value = 1),
      writebackGid = Id(value = 2),
      writebackRid = Id(value = 3),
      writebackLoadLsId = Id(value = 4),
      writebackPc = 0x10,
      writebackKind = "Gpr",
      writebackArchTag = 5,
      writebackRelTag = 0,
      writebackPhysTag = 11,
      writebackOldPhysTag = 2,
      writebackData = 0x55)
    val invalidIdentity = LoadReplayReturnPipeW2WritebackFirePayloadReference(
      enable = true,
      flush = false,
      writebackFire = true,
      writebackPayloadValid = false,
      writebackTargetValid = true,
      writebackIdentityValid = false,
      writebackDestinationValid = true,
      writebackGprDestination = true,
      writebackTargetIsAgu = true,
      writebackTargetIsLda = false,
      writebackTargetPipeIndex = 0,
      writebackBid = Id(value = 1),
      writebackGid = Id(valid = false, value = 2),
      writebackRid = Id(value = 3),
      writebackLoadLsId = Id(value = 4),
      writebackPc = 0x10,
      writebackKind = "Gpr",
      writebackArchTag = 5,
      writebackRelTag = 0,
      writebackPhysTag = 11,
      writebackOldPhysTag = 2,
      writebackData = 0x55)

    assert(!invalidTarget.fireValid)
    assert(invalidTarget.blockedByInvalidTarget)
    assert(invalidTarget.blockedByNoPayload)
    assert(!invalidIdentity.fireValid)
    assert(invalidIdentity.blockedByInvalidIdentity)
    assert(invalidIdentity.blockedByNoPayload)
  }

  test("blocks missing and non-GPR writeback destinations") {
    val noDestination = LoadReplayReturnPipeW2WritebackFirePayloadReference(
      enable = true,
      flush = false,
      writebackFire = true,
      writebackPayloadValid = false,
      writebackTargetValid = true,
      writebackIdentityValid = true,
      writebackDestinationValid = false,
      writebackGprDestination = false,
      writebackTargetIsAgu = false,
      writebackTargetIsLda = true,
      writebackTargetPipeIndex = 0,
      writebackBid = Id(value = 1),
      writebackGid = Id(value = 2),
      writebackRid = Id(value = 3),
      writebackLoadLsId = Id(value = 4),
      writebackPc = 0x10,
      writebackKind = "None",
      writebackArchTag = 0,
      writebackRelTag = 0,
      writebackPhysTag = 0,
      writebackOldPhysTag = 0,
      writebackData = 0x55)
    val nonGpr = LoadReplayReturnPipeW2WritebackFirePayloadReference(
      enable = true,
      flush = false,
      writebackFire = true,
      writebackPayloadValid = false,
      writebackTargetValid = true,
      writebackIdentityValid = true,
      writebackDestinationValid = true,
      writebackGprDestination = false,
      writebackTargetIsAgu = false,
      writebackTargetIsLda = true,
      writebackTargetPipeIndex = 0,
      writebackBid = Id(value = 1),
      writebackGid = Id(value = 2),
      writebackRid = Id(value = 3),
      writebackLoadLsId = Id(value = 4),
      writebackPc = 0x10,
      writebackKind = "T",
      writebackArchTag = 0,
      writebackRelTag = 0,
      writebackPhysTag = 6,
      writebackOldPhysTag = 0,
      writebackData = 0x55)

    assert(!noDestination.destinationValid)
    assert(!noDestination.fireValid)
    assert(noDestination.blockedByNoDestination)
    assert(!nonGpr.gprDestination)
    assert(!nonGpr.fireValid)
    assert(nonGpr.blockedByNonGprDestination)
  }

  test("Chisel LoadReplayReturnPipeW2WritebackFirePayload elaborates fire-qualified diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2WritebackFirePayload)

    assert(sv.contains("module LoadReplayReturnPipeW2WritebackFirePayload"))
    assert(sv.contains("io_writebackFire"))
    assert(sv.contains("io_writebackPayloadValid"))
    assert(sv.contains("io_fireValid"))
    assert(sv.contains("io_fireBid_valid"))
    assert(sv.contains("io_firePhysTag"))
    assert(sv.contains("io_invalidPayloadWithoutFire"))
  }
}
