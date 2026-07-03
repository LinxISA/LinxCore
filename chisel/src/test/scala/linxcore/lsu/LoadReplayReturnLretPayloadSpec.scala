package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnLretPayloadReference {
  final case class Id(valid: Boolean, wrap: Boolean, value: Int)
  final case class Dst(
      valid: Boolean = false,
      kind: Int = 0,
      archTag: Int = 0,
      relTag: Int = 0,
      physTag: Int = 0,
      oldPhysTag: Int = 0)

  final case class Result(
      candidateValid: Boolean,
      payloadValid: Boolean,
      payloadBid: Id,
      payloadGid: Id,
      payloadRid: Id,
      payloadLoadLsId: Id,
      payloadPc: BigInt,
      payloadAddr: BigInt,
      payloadSize: Int,
      payloadDst: Dst,
      payloadData: BigInt,
      payloadPipeIndex: Int,
      payloadSpecWakeup: Boolean,
      payloadStackValid: Boolean,
      wakeupRequired: Boolean,
      blockedByDisabled: Boolean,
      blockedByNoCandidate: Boolean,
      blockedByData: Boolean)

  private val DisabledId = Id(valid = false, wrap = false, value = 0)
  private val NoDst = Dst()

  def apply(
      enable: Boolean,
      launchValid: Boolean,
      dataValid: Boolean,
      selectedBid: Id,
      selectedGid: Id,
      selectedRid: Id,
      selectedLoadLsId: Id,
      selectedPc: BigInt,
      selectedAddr: BigInt,
      selectedSize: Int,
      selectedDst: Dst = NoDst,
      returnData: BigInt,
      returnPipeIndex: Int,
      specWakeup: Boolean,
      stackValid: Boolean): Result = {
    val candidateValid = enable && launchValid
    val payloadValid = candidateValid && dataValid

    Result(
      candidateValid = candidateValid,
      payloadValid = payloadValid,
      payloadBid = if (payloadValid) selectedBid else DisabledId,
      payloadGid = if (payloadValid) selectedGid else DisabledId,
      payloadRid = if (payloadValid) selectedRid else DisabledId,
      payloadLoadLsId = if (payloadValid) selectedLoadLsId else DisabledId,
      payloadPc = if (payloadValid) selectedPc else BigInt(0),
      payloadAddr = if (payloadValid) selectedAddr else BigInt(0),
      payloadSize = if (payloadValid) selectedSize else 0,
      payloadDst = if (payloadValid) selectedDst else NoDst,
      payloadData = if (payloadValid) returnData else BigInt(0),
      payloadPipeIndex = if (payloadValid) returnPipeIndex else 0,
      payloadSpecWakeup = payloadValid && specWakeup,
      payloadStackValid = payloadValid && stackValid,
      wakeupRequired = payloadValid && !specWakeup && !stackValid,
      blockedByDisabled = !enable && launchValid,
      blockedByNoCandidate = enable && !launchValid,
      blockedByData = candidateValid && !dataValid)
  }
}

class LoadReplayReturnLretPayloadSpec extends AnyFunSuite {
  import LoadReplayReturnLretPayloadReference._

  test("formats the selected-row LRET payload subset when return data is valid") {
    val result = LoadReplayReturnLretPayloadReference(
      enable = true,
      launchValid = true,
      dataValid = true,
      selectedBid = Id(valid = true, wrap = false, value = 2),
      selectedGid = Id(valid = true, wrap = false, value = 3),
      selectedRid = Id(valid = true, wrap = false, value = 4),
      selectedLoadLsId = Id(valid = true, wrap = true, value = 5),
      selectedPc = BigInt("1008", 16),
      selectedAddr = BigInt("2000", 16),
      selectedSize = 8,
      selectedDst = Dst(valid = true, kind = 1, archTag = 10, relTag = 10, physTag = 42, oldPhysTag = 10),
      returnData = BigInt("8877665544332211", 16),
      returnPipeIndex = 1,
      specWakeup = false,
      stackValid = false)

    assert(result.candidateValid)
    assert(result.payloadValid)
    assert(result.payloadBid.value == 2)
    assert(result.payloadGid.value == 3)
    assert(result.payloadRid.value == 4)
    assert(result.payloadLoadLsId.wrap)
    assert(result.payloadPc == BigInt("1008", 16))
    assert(result.payloadAddr == BigInt("2000", 16))
    assert(result.payloadSize == 8)
    assert(result.payloadDst.physTag == 42)
    assert(result.payloadData == BigInt("8877665544332211", 16))
    assert(result.payloadPipeIndex == 1)
    assert(result.wakeupRequired)
  }

  test("suppresses wakeup requirement for speculative and stack-valid rows") {
    val spec = LoadReplayReturnLretPayloadReference(
      enable = true,
      launchValid = true,
      dataValid = true,
      selectedBid = Id(valid = true, wrap = false, value = 1),
      selectedGid = Id(valid = true, wrap = false, value = 1),
      selectedRid = Id(valid = true, wrap = false, value = 1),
      selectedLoadLsId = Id(valid = true, wrap = false, value = 1),
      selectedPc = 0,
      selectedAddr = 0,
      selectedSize = 4,
      returnData = 0x1234,
      returnPipeIndex = 0,
      specWakeup = true,
      stackValid = false)
    val stack = LoadReplayReturnLretPayloadReference(
      enable = true,
      launchValid = true,
      dataValid = true,
      selectedBid = Id(valid = true, wrap = false, value = 1),
      selectedGid = Id(valid = true, wrap = false, value = 1),
      selectedRid = Id(valid = true, wrap = false, value = 1),
      selectedLoadLsId = Id(valid = true, wrap = false, value = 1),
      selectedPc = 0,
      selectedAddr = 0,
      selectedSize = 4,
      returnData = 0x1234,
      returnPipeIndex = 0,
      specWakeup = false,
      stackValid = true)

    assert(spec.payloadValid)
    assert(spec.payloadSpecWakeup)
    assert(!spec.wakeupRequired)
    assert(stack.payloadValid)
    assert(stack.payloadStackValid)
    assert(!stack.wakeupRequired)
  }

  test("reports data and candidate blockers without forwarding stale payload fields") {
    val dataBlocked = LoadReplayReturnLretPayloadReference(
      enable = true,
      launchValid = true,
      dataValid = false,
      selectedBid = Id(valid = true, wrap = true, value = 7),
      selectedGid = Id(valid = true, wrap = true, value = 7),
      selectedRid = Id(valid = true, wrap = true, value = 7),
      selectedLoadLsId = Id(valid = true, wrap = true, value = 7),
      selectedPc = 0xdead,
      selectedAddr = 0xbeef,
      selectedSize = 8,
      returnData = 0xffff,
      returnPipeIndex = 0,
      specWakeup = false,
      stackValid = false)
    val empty = LoadReplayReturnLretPayloadReference(
      enable = true,
      launchValid = false,
      dataValid = true,
      selectedBid = Id(valid = true, wrap = false, value = 1),
      selectedGid = Id(valid = true, wrap = false, value = 1),
      selectedRid = Id(valid = true, wrap = false, value = 1),
      selectedLoadLsId = Id(valid = true, wrap = false, value = 1),
      selectedPc = 0,
      selectedAddr = 0,
      selectedSize = 4,
      returnData = 0,
      returnPipeIndex = 0,
      specWakeup = false,
      stackValid = false)

    assert(!dataBlocked.payloadValid)
    assert(dataBlocked.blockedByData)
    assert(!dataBlocked.payloadBid.valid)
    assert(!dataBlocked.payloadDst.valid)
    assert(dataBlocked.payloadData == 0)
    assert(!empty.payloadValid)
    assert(empty.blockedByNoCandidate)
  }

  test("Chisel LoadReplayReturnLretPayload elaborates payload diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnLretPayload(returnPipeCount = 2))

    assert(sv.contains("module LoadReplayReturnLretPayload"))
    assert(sv.contains("io_payloadValid"))
    assert(sv.contains("io_payloadData"))
    assert(sv.contains("io_payloadDst_physTag"))
    assert(sv.contains("io_payloadPipeIndex"))
    assert(sv.contains("io_wakeupRequired"))
    assert(sv.contains("io_blockedByData"))
  }
}
