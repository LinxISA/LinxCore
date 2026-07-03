package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2WritebackRequestReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)
  final case class Dst(
      valid: Boolean = true,
      kind: String = "Gpr",
      archTag: Int = 0,
      relTag: Int = 0,
      physTag: Int = 0,
      oldPhysTag: Int = 0)

  final case class Result(
      candidateValid: Boolean,
      targetValid: Boolean,
      identityValid: Boolean,
      destinationValid: Boolean,
      gprDestination: Boolean,
      writebackValid: Boolean,
      targetIsAgu: Boolean,
      targetIsLda: Boolean,
      copiedBid: Id,
      copiedGid: Id,
      copiedRid: Id,
      copiedLoadLsId: Id,
      copiedPc: BigInt,
      copiedDst: Dst,
      copiedData: BigInt,
      blockedByNoRequest: Boolean,
      blockedByNoSlot: Boolean,
      blockedByInvalidTarget: Boolean,
      blockedByInvalidBid: Boolean,
      blockedByInvalidGid: Boolean,
      blockedByInvalidRid: Boolean,
      blockedByInvalidIdentity: Boolean,
      blockedByNoDestination: Boolean,
      blockedByNonGprDestination: Boolean)

  def apply(
      writebackRequest: Boolean,
      slotOccupied: Boolean,
      slotTargetIsAgu: Boolean,
      slotTargetIsLda: Boolean,
      slotBid: Id,
      slotGid: Id,
      slotRid: Id,
      slotLoadLsId: Id,
      slotPc: BigInt,
      slotDst: Dst,
      slotData: BigInt): Result = {
    val candidateValid = writebackRequest && slotOccupied
    val targetValid = slotTargetIsAgu ^ slotTargetIsLda
    val identityValid = slotBid.valid && slotGid.valid && slotRid.valid
    val hasDestination = slotDst.valid && slotDst.kind != "None"
    val isGprDestination = hasDestination && slotDst.kind == "Gpr"
    val writebackValid = candidateValid && targetValid && identityValid && isGprDestination
    val disabled = Id(valid = false)
    val noDst = Dst(valid = false, kind = "None")

    Result(
      candidateValid = candidateValid,
      targetValid = candidateValid && targetValid,
      identityValid = candidateValid && targetValid && identityValid,
      destinationValid = candidateValid && targetValid && identityValid && hasDestination,
      gprDestination = candidateValid && targetValid && identityValid && isGprDestination,
      writebackValid = writebackValid,
      targetIsAgu = writebackValid && slotTargetIsAgu,
      targetIsLda = writebackValid && slotTargetIsLda,
      copiedBid = if (writebackValid) slotBid else disabled,
      copiedGid = if (writebackValid) slotGid else disabled,
      copiedRid = if (writebackValid) slotRid else disabled,
      copiedLoadLsId = if (writebackValid) slotLoadLsId else disabled,
      copiedPc = if (writebackValid) slotPc else 0,
      copiedDst = if (writebackValid) slotDst else noDst,
      copiedData = if (writebackValid) slotData else 0,
      blockedByNoRequest = slotOccupied && !writebackRequest,
      blockedByNoSlot = writebackRequest && !slotOccupied,
      blockedByInvalidTarget = candidateValid && !targetValid,
      blockedByInvalidBid = candidateValid && targetValid && !slotBid.valid,
      blockedByInvalidGid = candidateValid && targetValid && !slotGid.valid,
      blockedByInvalidRid = candidateValid && targetValid && !slotRid.valid,
      blockedByInvalidIdentity = candidateValid && targetValid && !identityValid,
      blockedByNoDestination = candidateValid && targetValid && identityValid && !hasDestination,
      blockedByNonGprDestination = candidateValid && targetValid && identityValid &&
        hasDestination && !isGprDestination)
  }
}

class LoadReplayReturnPipeW2WritebackRequestSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2WritebackRequestReference._

  test("copies W2 GPR writeback identity, destination, and data when legal") {
    val result = LoadReplayReturnPipeW2WritebackRequestReference(
      writebackRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(valid = true, wrap = true, value = 2),
      slotGid = Id(valid = true, value = 5),
      slotRid = Id(valid = true, value = 7),
      slotLoadLsId = Id(valid = true, value = 9),
      slotPc = BigInt("1000", 16),
      slotDst = Dst(valid = true, kind = "Gpr", archTag = 3, relTag = 0, physTag = 42, oldPhysTag = 8),
      slotData = BigInt("8877665544332211", 16))

    assert(result.candidateValid)
    assert(result.targetValid)
    assert(result.identityValid)
    assert(result.destinationValid)
    assert(result.gprDestination)
    assert(result.writebackValid)
    assert(result.targetIsAgu)
    assert(!result.targetIsLda)
    assert(result.copiedBid.value == 2)
    assert(result.copiedBid.wrap)
    assert(result.copiedGid.value == 5)
    assert(result.copiedRid.value == 7)
    assert(result.copiedLoadLsId.value == 9)
    assert(result.copiedPc == BigInt("1000", 16))
    assert(result.copiedDst.archTag == 3)
    assert(result.copiedDst.physTag == 42)
    assert(result.copiedDst.oldPhysTag == 8)
    assert(result.copiedData == BigInt("8877665544332211", 16))
  }

  test("keeps a resident W2 slot dormant until the writeback request fires") {
    val result = LoadReplayReturnPipeW2WritebackRequestReference(
      writebackRequest = false,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(physTag = 11),
      slotData = 0x55)

    assert(!result.candidateValid)
    assert(!result.writebackValid)
    assert(result.blockedByNoRequest)
    assert(!result.copiedRid.valid)
    assert(!result.copiedDst.valid)
  }

  test("reports writeback requests that arrive without a resident W2 slot") {
    val result = LoadReplayReturnPipeW2WritebackRequestReference(
      writebackRequest = true,
      slotOccupied = false,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(physTag = 11),
      slotData = 0x55)

    assert(!result.candidateValid)
    assert(!result.writebackValid)
    assert(result.blockedByNoSlot)
  }

  test("blocks ambiguous or missing W2 pipe targets") {
    val bothTargets = LoadReplayReturnPipeW2WritebackRequestReference(
      writebackRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(physTag = 11),
      slotData = 0x55)
    val noTarget = LoadReplayReturnPipeW2WritebackRequestReference(
      writebackRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = false,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(physTag = 11),
      slotData = 0x55)

    assert(bothTargets.candidateValid)
    assert(!bothTargets.targetValid)
    assert(!bothTargets.writebackValid)
    assert(bothTargets.blockedByInvalidTarget)
    assert(noTarget.candidateValid)
    assert(!noTarget.targetValid)
    assert(!noTarget.writebackValid)
    assert(noTarget.blockedByInvalidTarget)
  }

  test("flags invalid writeback identity fields independently") {
    val result = LoadReplayReturnPipeW2WritebackRequestReference(
      writebackRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(valid = false, value = 1),
      slotGid = Id(valid = false, value = 2),
      slotRid = Id(valid = false, value = 3),
      slotLoadLsId = Id(valid = true, value = 4),
      slotPc = 0x10,
      slotDst = Dst(physTag = 11),
      slotData = 0x55)

    assert(result.candidateValid)
    assert(result.targetValid)
    assert(!result.identityValid)
    assert(!result.writebackValid)
    assert(result.blockedByInvalidBid)
    assert(result.blockedByInvalidGid)
    assert(result.blockedByInvalidRid)
    assert(result.blockedByInvalidIdentity)
  }

  test("blocks missing and non-GPR destinations") {
    val missing = LoadReplayReturnPipeW2WritebackRequestReference(
      writebackRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(valid = false, kind = "None"),
      slotData = 0x55)
    val local = LoadReplayReturnPipeW2WritebackRequestReference(
      writebackRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(valid = true, kind = "T", physTag = 6),
      slotData = 0x55)

    assert(!missing.destinationValid)
    assert(!missing.writebackValid)
    assert(missing.blockedByNoDestination)
    assert(!local.gprDestination)
    assert(!local.writebackValid)
    assert(local.blockedByNonGprDestination)
  }

  test("Chisel LoadReplayReturnPipeW2WritebackRequest elaborates writeback payload diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2WritebackRequest)

    assert(sv.contains("module LoadReplayReturnPipeW2WritebackRequest"))
    assert(sv.contains("io_writebackRequest"))
    assert(sv.contains("io_slotOccupied"))
    assert(sv.contains("io_writebackValid"))
    assert(sv.contains("io_writebackPhysTag"))
    assert(sv.contains("io_blockedByNonGprDestination"))
  }
}
