package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2WakeupRequestReference {
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
      wakeupRequired: Boolean,
      destinationValid: Boolean,
      wakeupValid: Boolean,
      reducedGprWakeupValid: Boolean,
      nonGprWakeup: Boolean,
      targetIsAgu: Boolean,
      targetIsLda: Boolean,
      copiedBid: Id,
      copiedGid: Id,
      copiedRid: Id,
      copiedLoadLsId: Id,
      copiedPc: BigInt,
      copiedDst: Dst,
      blockedByNoRequest: Boolean,
      blockedByNoSlot: Boolean,
      blockedByInvalidTarget: Boolean,
      blockedByInvalidBid: Boolean,
      blockedByInvalidGid: Boolean,
      blockedByInvalidRid: Boolean,
      blockedByInvalidIdentity: Boolean,
      blockedByWakeupNotRequired: Boolean,
      blockedByNoDestination: Boolean)

  def apply(
      wakeupRequest: Boolean,
      slotOccupied: Boolean,
      slotTargetIsAgu: Boolean,
      slotTargetIsLda: Boolean,
      slotBid: Id,
      slotGid: Id,
      slotRid: Id,
      slotLoadLsId: Id,
      slotPc: BigInt,
      slotDst: Dst,
      slotWakeupRequired: Boolean): Result = {
    val candidateValid = wakeupRequest && slotOccupied
    val targetValid = slotTargetIsAgu ^ slotTargetIsLda
    val identityValid = slotBid.valid && slotGid.valid && slotRid.valid
    val hasDestination = slotDst.valid && slotDst.kind != "None"
    val wakeupRequired = candidateValid && targetValid && identityValid && slotWakeupRequired
    val wakeupValid = wakeupRequired && hasDestination
    val isGprDestination = hasDestination && slotDst.kind == "Gpr"
    val disabled = Id(valid = false)
    val noDst = Dst(valid = false, kind = "None")

    Result(
      candidateValid = candidateValid,
      targetValid = candidateValid && targetValid,
      identityValid = candidateValid && targetValid && identityValid,
      wakeupRequired = wakeupRequired,
      destinationValid = wakeupRequired && hasDestination,
      wakeupValid = wakeupValid,
      reducedGprWakeupValid = wakeupValid && isGprDestination,
      nonGprWakeup = wakeupValid && !isGprDestination,
      targetIsAgu = wakeupValid && slotTargetIsAgu,
      targetIsLda = wakeupValid && slotTargetIsLda,
      copiedBid = if (wakeupValid) slotBid else disabled,
      copiedGid = if (wakeupValid) slotGid else disabled,
      copiedRid = if (wakeupValid) slotRid else disabled,
      copiedLoadLsId = if (wakeupValid) slotLoadLsId else disabled,
      copiedPc = if (wakeupValid) slotPc else 0,
      copiedDst = if (wakeupValid) slotDst else noDst,
      blockedByNoRequest = slotOccupied && !wakeupRequest,
      blockedByNoSlot = wakeupRequest && !slotOccupied,
      blockedByInvalidTarget = candidateValid && !targetValid,
      blockedByInvalidBid = candidateValid && targetValid && !slotBid.valid,
      blockedByInvalidGid = candidateValid && targetValid && !slotGid.valid,
      blockedByInvalidRid = candidateValid && targetValid && !slotRid.valid,
      blockedByInvalidIdentity = candidateValid && targetValid && !identityValid,
      blockedByWakeupNotRequired = candidateValid && targetValid && identityValid && !slotWakeupRequired,
      blockedByNoDestination = wakeupRequired && !hasDestination)
  }
}

class LoadReplayReturnPipeW2WakeupRequestSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2WakeupRequestReference._

  test("copies W2 GPR wakeup identity and destination when legal") {
    val result = LoadReplayReturnPipeW2WakeupRequestReference(
      wakeupRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(valid = true, wrap = true, value = 2),
      slotGid = Id(valid = true, value = 5),
      slotRid = Id(valid = true, value = 7),
      slotLoadLsId = Id(valid = true, value = 9),
      slotPc = BigInt("1000", 16),
      slotDst = Dst(valid = true, kind = "Gpr", archTag = 3, relTag = 0, physTag = 42, oldPhysTag = 8),
      slotWakeupRequired = true)

    assert(result.candidateValid)
    assert(result.targetValid)
    assert(result.identityValid)
    assert(result.wakeupRequired)
    assert(result.destinationValid)
    assert(result.wakeupValid)
    assert(result.reducedGprWakeupValid)
    assert(!result.nonGprWakeup)
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
  }

  test("preserves scalar local-link wakeup payloads as non-GPR wakeups") {
    val result = LoadReplayReturnPipeW2WakeupRequestReference(
      wakeupRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(valid = true, kind = "T", archTag = 31, relTag = 0, physTag = 6, oldPhysTag = 5),
      slotWakeupRequired = true)

    assert(result.wakeupValid)
    assert(!result.reducedGprWakeupValid)
    assert(result.nonGprWakeup)
    assert(result.targetIsLda)
    assert(result.copiedDst.kind == "T")
    assert(result.copiedDst.physTag == 6)
  }

  test("keeps a resident W2 slot dormant until the wakeup request fires") {
    val result = LoadReplayReturnPipeW2WakeupRequestReference(
      wakeupRequest = false,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(physTag = 11),
      slotWakeupRequired = true)

    assert(!result.candidateValid)
    assert(!result.wakeupValid)
    assert(result.blockedByNoRequest)
    assert(!result.copiedRid.valid)
    assert(!result.copiedDst.valid)
  }

  test("reports wakeup requests that arrive without a resident W2 slot") {
    val result = LoadReplayReturnPipeW2WakeupRequestReference(
      wakeupRequest = true,
      slotOccupied = false,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(physTag = 11),
      slotWakeupRequired = true)

    assert(!result.candidateValid)
    assert(!result.wakeupValid)
    assert(result.blockedByNoSlot)
  }

  test("blocks ambiguous or missing W2 pipe targets") {
    val bothTargets = LoadReplayReturnPipeW2WakeupRequestReference(
      wakeupRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(physTag = 11),
      slotWakeupRequired = true)
    val noTarget = LoadReplayReturnPipeW2WakeupRequestReference(
      wakeupRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = false,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(physTag = 11),
      slotWakeupRequired = true)

    assert(bothTargets.candidateValid)
    assert(!bothTargets.targetValid)
    assert(!bothTargets.wakeupValid)
    assert(bothTargets.blockedByInvalidTarget)
    assert(noTarget.candidateValid)
    assert(!noTarget.targetValid)
    assert(!noTarget.wakeupValid)
    assert(noTarget.blockedByInvalidTarget)
  }

  test("flags invalid wakeup identity fields independently") {
    val result = LoadReplayReturnPipeW2WakeupRequestReference(
      wakeupRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(valid = false, value = 1),
      slotGid = Id(valid = false, value = 2),
      slotRid = Id(valid = false, value = 3),
      slotLoadLsId = Id(valid = true, value = 4),
      slotPc = 0x10,
      slotDst = Dst(physTag = 11),
      slotWakeupRequired = true)

    assert(result.candidateValid)
    assert(result.targetValid)
    assert(!result.identityValid)
    assert(!result.wakeupValid)
    assert(result.blockedByInvalidBid)
    assert(result.blockedByInvalidGid)
    assert(result.blockedByInvalidRid)
    assert(result.blockedByInvalidIdentity)
  }

  test("blocks requests that are not required or have no destination") {
    val notRequired = LoadReplayReturnPipeW2WakeupRequestReference(
      wakeupRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(physTag = 11),
      slotWakeupRequired = false)
    val missing = LoadReplayReturnPipeW2WakeupRequestReference(
      wakeupRequest = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotLoadLsId = Id(value = 4),
      slotPc = 0x10,
      slotDst = Dst(valid = false, kind = "None"),
      slotWakeupRequired = true)

    assert(!notRequired.wakeupRequired)
    assert(!notRequired.wakeupValid)
    assert(notRequired.blockedByWakeupNotRequired)
    assert(missing.wakeupRequired)
    assert(!missing.destinationValid)
    assert(!missing.wakeupValid)
    assert(missing.blockedByNoDestination)
  }

  test("Chisel LoadReplayReturnPipeW2WakeupRequest elaborates wakeup payload diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2WakeupRequest)

    assert(sv.contains("module LoadReplayReturnPipeW2WakeupRequest"))
    assert(sv.contains("io_wakeupRequest"))
    assert(sv.contains("io_slotOccupied"))
    assert(sv.contains("io_wakeupValid"))
    assert(sv.contains("io_reducedGprWakeupValid"))
    assert(sv.contains("io_blockedByWakeupNotRequired"))
  }
}
