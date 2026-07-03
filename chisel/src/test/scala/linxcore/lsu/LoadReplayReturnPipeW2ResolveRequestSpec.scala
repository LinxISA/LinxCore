package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2ResolveRequestReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)

  final case class Result(
      candidateValid: Boolean,
      targetValid: Boolean,
      resolveValid: Boolean,
      isComplete: Boolean,
      copiedBid: Id,
      copiedGid: Id,
      copiedRid: Id,
      copiedLoadLsId: Id,
      copiedPc: BigInt,
      copiedAddr: BigInt,
      copiedSize: Int,
      copiedData: BigInt,
      blockedByNoRequest: Boolean,
      blockedByNoSlot: Boolean,
      blockedByInvalidTarget: Boolean,
      blockedByInvalidBid: Boolean,
      blockedByInvalidGid: Boolean,
      blockedByInvalidRid: Boolean,
      blockedByInvalidIdentity: Boolean)

  def apply(
      resolveRequest: Boolean,
      slotOccupied: Boolean,
      slotTargetIsAgu: Boolean,
      slotTargetIsLda: Boolean,
      slotBid: Id,
      slotGid: Id,
      slotRid: Id,
      slotLoadLsId: Id,
      slotPc: BigInt,
      slotAddr: BigInt,
      slotSize: Int,
      slotData: BigInt): Result = {
    val candidateValid = resolveRequest && slotOccupied
    val targetValid = slotTargetIsAgu ^ slotTargetIsLda
    val identityValid = slotBid.valid && slotGid.valid && slotRid.valid
    val resolveValid = candidateValid && targetValid && identityValid
    val disabled = Id(valid = false)

    Result(
      candidateValid = candidateValid,
      targetValid = candidateValid && targetValid,
      resolveValid = resolveValid,
      isComplete = resolveValid,
      copiedBid = if (resolveValid) slotBid else disabled,
      copiedGid = if (resolveValid) slotGid else disabled,
      copiedRid = if (resolveValid) slotRid else disabled,
      copiedLoadLsId = if (resolveValid) slotLoadLsId else disabled,
      copiedPc = if (resolveValid) slotPc else 0,
      copiedAddr = if (resolveValid) slotAddr else 0,
      copiedSize = if (resolveValid) slotSize else 0,
      copiedData = if (resolveValid) slotData else 0,
      blockedByNoRequest = slotOccupied && !resolveRequest,
      blockedByNoSlot = resolveRequest && !slotOccupied,
      blockedByInvalidTarget = candidateValid && !targetValid,
      blockedByInvalidBid = candidateValid && targetValid && !slotBid.valid,
      blockedByInvalidGid = candidateValid && targetValid && !slotGid.valid,
      blockedByInvalidRid = candidateValid && targetValid && !slotRid.valid,
      blockedByInvalidIdentity = candidateValid && targetValid && !identityValid)
  }
}

class LoadReplayReturnPipeW2ResolveRequestSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2ResolveRequestReference._

  test("copies W2 identity data and destination when a resolve request is legal") {
    val result = LoadReplayReturnPipeW2ResolveRequestReference(
      resolveRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(valid = true, wrap = true, value = 2),
      slotGid = Id(valid = true, value = 5),
      slotRid = Id(valid = true, value = 7),
      slotLoadLsId = Id(valid = true, value = 9),
      slotPc = BigInt("1000", 16),
      slotAddr = BigInt("2008", 16),
      slotSize = 8,
      slotData = BigInt("8877665544332211", 16))

    assert(result.candidateValid)
    assert(result.targetValid)
    assert(result.resolveValid)
    assert(result.isComplete)
    assert(result.copiedBid.value == 2)
    assert(result.copiedBid.wrap)
    assert(result.copiedGid.value == 5)
    assert(result.copiedRid.value == 7)
    assert(result.copiedLoadLsId.value == 9)
    assert(result.copiedPc == BigInt("1000", 16))
    assert(result.copiedAddr == BigInt("2008", 16))
    assert(result.copiedSize == 8)
    assert(result.copiedData == BigInt("8877665544332211", 16))
  }

  test("keeps a resident W2 slot dormant until the post-completion resolve request fires") {
    val result = LoadReplayReturnPipeW2ResolveRequestReference(
      resolveRequest = false,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotAddr = 0x20,
      slotSize = 4,
      slotData = 0x55)

    assert(!result.candidateValid)
    assert(!result.resolveValid)
    assert(result.blockedByNoRequest)
    assert(!result.copiedRid.valid)
  }

  test("reports resolve requests that arrive without a resident W2 slot") {
    val result = LoadReplayReturnPipeW2ResolveRequestReference(
      resolveRequest = true,
      slotOccupied = false,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotAddr = 0x20,
      slotSize = 4,
      slotData = 0x55)

    assert(!result.candidateValid)
    assert(!result.resolveValid)
    assert(result.blockedByNoSlot)
  }

  test("blocks ambiguous or missing W2 pipe targets") {
    val bothTargets = LoadReplayReturnPipeW2ResolveRequestReference(
      resolveRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotAddr = 0x20,
      slotSize = 4,
      slotData = 0x55)
    val noTarget = bothTargets.copy(blockedByInvalidTarget = true)

    assert(bothTargets.candidateValid)
    assert(!bothTargets.targetValid)
    assert(!bothTargets.resolveValid)
    assert(bothTargets.blockedByInvalidTarget)
    assert(noTarget.blockedByInvalidTarget)
  }

  test("flags invalid resolve identity fields independently") {
    val result = LoadReplayReturnPipeW2ResolveRequestReference(
      resolveRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(valid = false, value = 1),
      slotGid = Id(valid = false, value = 2),
      slotRid = Id(valid = false, value = 3),
      slotLoadLsId = Id(valid = false, value = 4),
      slotPc = 0x10,
      slotAddr = 0x20,
      slotSize = 4,
      slotData = 0x55)

    assert(result.candidateValid)
    assert(result.targetValid)
    assert(!result.resolveValid)
    assert(result.blockedByInvalidBid)
    assert(result.blockedByInvalidGid)
    assert(result.blockedByInvalidRid)
    assert(result.blockedByInvalidIdentity)
  }

  test("Chisel LoadReplayReturnPipeW2ResolveRequest elaborates resolve payload diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2ResolveRequest)

    assert(sv.contains("module LoadReplayReturnPipeW2ResolveRequest"))
    assert(sv.contains("io_resolveRequest"))
    assert(sv.contains("io_slotOccupied"))
    assert(sv.contains("io_resolveValid"))
    assert(sv.contains("io_resolveData"))
    assert(sv.contains("io_blockedByInvalidIdentity"))
  }
}
