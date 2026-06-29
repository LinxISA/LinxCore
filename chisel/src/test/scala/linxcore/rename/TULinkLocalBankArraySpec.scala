package linxcore.rename

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object TULinkLocalBankArrayReference {
  final case class ActiveSelection(
      peInRange: Boolean,
      stidInRange: Boolean,
      activeBankValid: Boolean,
      peOH: Int,
      stidOH: Int)

  def select(activePe: Int, activeStid: Int, peCount: Int, stidCount: Int): ActiveSelection = {
    val peInRange = activePe >= 0 && activePe < peCount
    val stidInRange = activeStid >= 0 && activeStid < stidCount
    ActiveSelection(
      peInRange = peInRange,
      stidInRange = stidInRange,
      activeBankValid = peInRange && stidInRange,
      peOH = if (peInRange) 1 << activePe else 0,
      stidOH = if (stidInRange) 1 << activeStid else 0)
  }
}

class TULinkLocalBankArraySpec extends AnyFunSuite {
  import TULinkLocalBankArrayReference._

  test("reference selects exactly one active PE/STID bank for reduced rename traffic") {
    val selected = select(activePe = 1, activeStid = 2, peCount = 3, stidCount = 4)

    assert(selected.peInRange)
    assert(selected.stidInRange)
    assert(selected.activeBankValid)
    assert(selected.peOH == 0x2)
    assert(selected.stidOH == 0x4)

    val outOfRange = select(activePe = 3, activeStid = 0, peCount = 3, stidCount = 4)
    assert(!outOfRange.peInRange)
    assert(outOfRange.stidInRange)
    assert(!outOfRange.activeBankValid)
    assert(outOfRange.peOH == 0)
    assert(outOfRange.stidOH == 0x1)
  }

  test("reference keeps retire PE/STID selection independent from active rename selection") {
    val active = select(activePe = 0, activeStid = 0, peCount = 2, stidCount = 2)
    val retire = select(activePe = 1, activeStid = 1, peCount = 2, stidCount = 2)

    assert(active.activeBankValid)
    assert(retire.activeBankValid)
    assert(active.peOH == 0x1)
    assert(active.stidOH == 0x1)
    assert(retire.peOH == 0x2)
    assert(retire.stidOH == 0x2)
  }

  test("reference keeps selected-STID local block commit atomic across every PE bank") {
    val result = TULinkLocalBlockCommitFanoutReference.fanout(
      valid = true,
      eventStid = 1,
      bankReady = Vector(
        Vector(true, true),
        Vector(true, false),
        Vector(true, true)))

    assert(!result.ready)
    assert(!result.accepted)
    assert(result.stidInRange)
    assert(result.blockedByBankReady)
    assert(result.targetPeMask == 0x7)
    assert(result.selectedPeReadyMask == 0x5)

    val unblocked = TULinkLocalBlockCommitFanoutReference.fanout(
      valid = true,
      eventStid = 1,
      bankReady = Vector(
        Vector(false, true),
        Vector(true, true),
        Vector(true, true)))
    assert(unblocked.ready)
    assert(unblocked.accepted)
    assert(unblocked.bankValid == Vector(
      Vector(false, true),
      Vector(false, true),
      Vector(false, true)))
  }

  test("IO exposes active-bank, fanout, and per-bank occupancy surfaces") {
    val p = InterfaceParams(robEntries = 8)
    val io = new TULinkLocalBankArrayIO(
      p = p,
      localRegsT = 8,
      localRegsU = 8,
      mapQDepth = 8,
      peCount = 2,
      stidCount = 3,
      peIdWidth = 2,
      stidWidth = 2)

    assert(io.activePeId.getWidth == 2)
    assert(io.activeStid.getWidth == 2)
    assert(io.activePeOH.getWidth == 2)
    assert(io.activeStidOH.getWidth == 3)
    assert(io.retirePeId.getWidth == 2)
    assert(io.retireStid.getWidth == 2)
    assert(io.retirePeInRange.getWidth == 1)
    assert(io.retireStidInRange.getWidth == 1)
    assert(io.retireBankValid.getWidth == 1)
    assert(io.retirePeOH.getWidth == 2)
    assert(io.retireStidOH.getWidth == 3)
    assert(io.localBlockCommitFanoutTargetPeMask.getWidth == 2)
    assert(io.localBlockCommitFanoutReadyPeMask.getWidth == 2)
    assert(io.localBlockCommitBlockedByBankReady.getWidth == 1)
    assert(io.bankTUsedEntries.length == 2)
    assert(io.bankTUsedEntries(0).length == 3)
    assert(io.bankUUsedEntries(1)(2).getWidth == 4)
    assert(io.tSeq.value.getWidth == 3)
    assert(io.uSeq.value.getWidth == 3)
  }

  test("TULinkLocalBankArray elaborates as SGPR bank groups behind block-commit fanout") {
    val sv = ChiselStage.emitSystemVerilog(
      new TULinkLocalBankArray(
        p = InterfaceParams(robEntries = 8),
        localRegsT = 8,
        localRegsU = 8,
        mapQDepth = 8,
        peCount = 2,
        stidCount = 2,
        peIdWidth = 2,
        stidWidth = 2)
    )

    assert(sv.contains("module TULinkLocalBankArray"))
    assert(sv.contains("module TULinkRecoveryCleanupPath"))
    assert(sv.contains("module TULinkLocalBlockCommitFanout"))
    assert(sv.contains("io_activePeOH"))
    assert(sv.contains("io_activeStidOH"))
    assert(sv.contains("io_retirePeId"))
    assert(sv.contains("io_retireStid"))
    assert(sv.contains("io_retireBankValid"))
    assert(sv.contains("io_bankTUsedEntries_1_1"))
    assert(sv.contains("io_localBlockCommitFanoutBlockedByBankReady"))
  }
}
