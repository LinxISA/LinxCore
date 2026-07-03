package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2WakeupArbiterInputReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)

  final case class Result(
      active: Boolean,
      candidateValid: Boolean,
      wakeupValid: Boolean,
      reducedGprWakeup: Boolean,
      nonGprWakeup: Boolean,
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
      blockedByNoPayload: Boolean,
      blockedByLiveDisabled: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveEnable: Boolean,
      firePayloadValid: Boolean,
      fireReducedGprWakeup: Boolean,
      fireNonGprWakeup: Boolean,
      fireTargetIsAgu: Boolean,
      fireTargetIsLda: Boolean,
      fireTargetPipeIndex: Int,
      fireBid: Id,
      fireGid: Id,
      fireRid: Id,
      fireLoadLsId: Id,
      firePc: BigInt,
      fireKind: String,
      fireArchTag: Int,
      fireRelTag: Int,
      firePhysTag: Int,
      fireOldPhysTag: Int): Result = {
    val active = enable && !flush
    val candidateValid = active && firePayloadValid
    val wakeupValid = candidateValid && liveEnable
    val disabled = Id(valid = false)

    Result(
      active = active,
      candidateValid = candidateValid,
      wakeupValid = wakeupValid,
      reducedGprWakeup = wakeupValid && fireReducedGprWakeup,
      nonGprWakeup = wakeupValid && fireNonGprWakeup,
      targetIsAgu = wakeupValid && fireTargetIsAgu,
      targetIsLda = wakeupValid && fireTargetIsLda,
      targetPipeIndex = if (wakeupValid) fireTargetPipeIndex else 0,
      copiedBid = if (wakeupValid) fireBid else disabled,
      copiedGid = if (wakeupValid) fireGid else disabled,
      copiedRid = if (wakeupValid) fireRid else disabled,
      copiedLoadLsId = if (wakeupValid) fireLoadLsId else disabled,
      copiedPc = if (wakeupValid) firePc else 0,
      copiedKind = if (wakeupValid) fireKind else "None",
      copiedArchTag = if (wakeupValid) fireArchTag else 0,
      copiedRelTag = if (wakeupValid) fireRelTag else 0,
      copiedPhysTag = if (wakeupValid) firePhysTag else 0,
      copiedOldPhysTag = if (wakeupValid) fireOldPhysTag else 0,
      blockedByDisabled = !enable && firePayloadValid,
      blockedByFlush = enable && flush && firePayloadValid,
      blockedByNoPayload = active && !firePayloadValid,
      blockedByLiveDisabled = candidateValid && !liveEnable)
  }
}

class LoadReplayReturnPipeW2WakeupArbiterInputSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2WakeupArbiterInputReference._

  test("holds a valid W2 wakeup payload behind the live gate") {
    val result = LoadReplayReturnPipeW2WakeupArbiterInputReference(
      enable = true,
      flush = false,
      liveEnable = false,
      firePayloadValid = true,
      fireReducedGprWakeup = true,
      fireNonGprWakeup = false,
      fireTargetIsAgu = true,
      fireTargetIsLda = false,
      fireTargetPipeIndex = 0,
      fireBid = Id(valid = true, wrap = true, value = 3),
      fireGid = Id(value = 4),
      fireRid = Id(value = 5),
      fireLoadLsId = Id(value = 6),
      firePc = BigInt("4000", 16),
      fireKind = "ScalarGpr",
      fireArchTag = 2,
      fireRelTag = 3,
      firePhysTag = 9,
      fireOldPhysTag = 7)

    assert(result.active)
    assert(result.candidateValid)
    assert(!result.wakeupValid)
    assert(!result.reducedGprWakeup)
    assert(!result.copiedBid.valid)
    assert(result.copiedPhysTag == 0)
    assert(result.blockedByLiveDisabled)
  }

  test("emits a live wakeup input when the live gate is enabled") {
    val result = LoadReplayReturnPipeW2WakeupArbiterInputReference(
      enable = true,
      flush = false,
      liveEnable = true,
      firePayloadValid = true,
      fireReducedGprWakeup = true,
      fireNonGprWakeup = false,
      fireTargetIsAgu = false,
      fireTargetIsLda = true,
      fireTargetPipeIndex = 0,
      fireBid = Id(value = 7),
      fireGid = Id(value = 8),
      fireRid = Id(value = 9),
      fireLoadLsId = Id(value = 10),
      firePc = BigInt("5000", 16),
      fireKind = "ScalarGpr",
      fireArchTag = 11,
      fireRelTag = 12,
      firePhysTag = 13,
      fireOldPhysTag = 14)

    assert(result.candidateValid)
    assert(result.wakeupValid)
    assert(result.reducedGprWakeup)
    assert(!result.nonGprWakeup)
    assert(!result.targetIsAgu)
    assert(result.targetIsLda)
    assert(result.copiedBid.value == 7)
    assert(result.copiedGid.value == 8)
    assert(result.copiedRid.value == 9)
    assert(result.copiedLoadLsId.value == 10)
    assert(result.copiedPc == BigInt("5000", 16))
    assert(result.copiedKind == "ScalarGpr")
    assert(result.copiedArchTag == 11)
    assert(result.copiedRelTag == 12)
    assert(result.copiedPhysTag == 13)
    assert(result.copiedOldPhysTag == 14)
  }

  test("reports disabled and flushed payloads without stale wakeup fields") {
    val disabled = LoadReplayReturnPipeW2WakeupArbiterInputReference(
      enable = false,
      flush = false,
      liveEnable = true,
      firePayloadValid = true,
      fireReducedGprWakeup = true,
      fireNonGprWakeup = false,
      fireTargetIsAgu = true,
      fireTargetIsLda = false,
      fireTargetPipeIndex = 0,
      fireBid = Id(value = 1),
      fireGid = Id(value = 2),
      fireRid = Id(value = 3),
      fireLoadLsId = Id(value = 4),
      firePc = 0x10,
      fireKind = "ScalarGpr",
      fireArchTag = 5,
      fireRelTag = 6,
      firePhysTag = 7,
      fireOldPhysTag = 8)
    val flushed = LoadReplayReturnPipeW2WakeupArbiterInputReference(
      enable = true,
      flush = true,
      liveEnable = true,
      firePayloadValid = true,
      fireReducedGprWakeup = true,
      fireNonGprWakeup = false,
      fireTargetIsAgu = true,
      fireTargetIsLda = false,
      fireTargetPipeIndex = 0,
      fireBid = Id(value = 1),
      fireGid = Id(value = 2),
      fireRid = Id(value = 3),
      fireLoadLsId = Id(value = 4),
      firePc = 0x10,
      fireKind = "ScalarGpr",
      fireArchTag = 5,
      fireRelTag = 6,
      firePhysTag = 7,
      fireOldPhysTag = 8)

    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)
    assert(!disabled.wakeupValid)
    assert(!disabled.copiedBid.valid)
    assert(flushed.blockedByFlush)
    assert(!flushed.wakeupValid)
    assert(flushed.copiedPhysTag == 0)
  }

  test("reports an active cycle with no wakeup fire payload") {
    val result = LoadReplayReturnPipeW2WakeupArbiterInputReference(
      enable = true,
      flush = false,
      liveEnable = true,
      firePayloadValid = false,
      fireReducedGprWakeup = true,
      fireNonGprWakeup = false,
      fireTargetIsAgu = true,
      fireTargetIsLda = false,
      fireTargetPipeIndex = 0,
      fireBid = Id(value = 1),
      fireGid = Id(value = 2),
      fireRid = Id(value = 3),
      fireLoadLsId = Id(value = 4),
      firePc = 0x10,
      fireKind = "ScalarGpr",
      fireArchTag = 5,
      fireRelTag = 6,
      firePhysTag = 7,
      fireOldPhysTag = 8)

    assert(result.active)
    assert(!result.candidateValid)
    assert(!result.wakeupValid)
    assert(result.blockedByNoPayload)
  }

  test("Chisel LoadReplayReturnPipeW2WakeupArbiterInput elaborates wakeup candidate fields") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2WakeupArbiterInput)

    assert(sv.contains("module LoadReplayReturnPipeW2WakeupArbiterInput"))
    assert(sv.contains("io_liveEnable"))
    assert(sv.contains("io_firePayloadValid"))
    assert(sv.contains("io_wakeupValid"))
    assert(sv.contains("io_wakeupPhysTag"))
  }
}
