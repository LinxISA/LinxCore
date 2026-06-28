package linxcore.rename

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object TULinkLocalBlockCommitFanoutReference {
  final case class Result(
      ready: Boolean,
      accepted: Boolean,
      stidInRange: Boolean,
      blockedByStidRange: Boolean,
      blockedByBankReady: Boolean,
      selectedStidOH: Int,
      selectedPeReadyMask: Int,
      targetPeMask: Int,
      bankValid: Vector[Vector[Boolean]])

  def fanout(valid: Boolean, eventStid: Int, bankReady: Vector[Vector[Boolean]]): Result = {
    require(bankReady.nonEmpty)
    val peCount = bankReady.length
    val stidCount = bankReady.head.length
    require(stidCount > 0)
    require(bankReady.forall(_.length == stidCount))

    val stidInRange = eventStid >= 0 && eventStid < stidCount
    val selectedReady = bankReady.map(peReady => stidInRange && peReady(eventStid))
    val ready = stidInRange && selectedReady.forall(identity)
    val accepted = valid && ready
    val bankValid = bankReady.indices.map { _ =>
      (0 until stidCount).map(stid => accepted && stid == eventStid).toVector
    }.toVector

    Result(
      ready = ready,
      accepted = accepted,
      stidInRange = stidInRange,
      blockedByStidRange = valid && !stidInRange,
      blockedByBankReady = valid && stidInRange && !selectedReady.forall(identity),
      selectedStidOH = if (stidInRange) 1 << eventStid else 0,
      selectedPeReadyMask = selectedReady.zipWithIndex.foldLeft(0) {
        case (mask, (isReady, idx)) => if (isReady) mask | (1 << idx) else mask
      },
      targetPeMask = if (valid && stidInRange) (1 << peCount) - 1 else 0,
      bankValid = bankValid)
  }
}

class TULinkLocalBlockCommitFanoutSpec extends AnyFunSuite {
  import TULinkLocalBlockCommitFanoutReference._

  test("reference broadcasts one selected STID to every scalar PE atomically") {
    val result = fanout(
      valid = true,
      eventStid = 1,
      bankReady = Vector(
        Vector(true, true),
        Vector(false, true),
        Vector(true, true)))

    assert(result.ready)
    assert(result.accepted)
    assert(result.stidInRange)
    assert(result.selectedStidOH == 0x2)
    assert(result.selectedPeReadyMask == 0x7)
    assert(result.targetPeMask == 0x7)
    assert(result.bankValid == Vector(
      Vector(false, true),
      Vector(false, true),
      Vector(false, true)))
  }

  test("reference waits for every selected PE bank before pulsing downstream valid") {
    val result = fanout(
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
    assert(result.selectedPeReadyMask == 0x5)
    assert(result.targetPeMask == 0x7)
    assert(result.bankValid.forall(_.forall(!_)))
  }

  test("reference rejects out-of-range STID without targeting any bank") {
    val result = fanout(
      valid = true,
      eventStid = 3,
      bankReady = Vector(
        Vector(true, true),
        Vector(true, true)))

    assert(!result.ready)
    assert(!result.accepted)
    assert(!result.stidInRange)
    assert(result.blockedByStidRange)
    assert(!result.blockedByBankReady)
    assert(result.selectedStidOH == 0)
    assert(result.selectedPeReadyMask == 0)
    assert(result.targetPeMask == 0)
    assert(result.bankValid.forall(_.forall(!_)))
  }

  test("IO exposes selected-STID fanout masks and per-bank commit payloads") {
    val p = InterfaceParams(robEntries = 8)
    val io = new TULinkLocalBlockCommitFanoutIO(p, peCount = 3, stidCount = 4, stidWidth = 3)

    assert(io.inBid.value.getWidth == 3)
    assert(io.inStid.getWidth == 3)
    assert(io.bankReady.length == 3)
    assert(io.bankReady(0).length == 4)
    assert(io.bankValid.length == 3)
    assert(io.bankValid(0).length == 4)
    assert(io.bankBid(0)(0).value.getWidth == 3)
    assert(io.bankStid(0)(0).getWidth == 3)
    assert(io.selectedStidOH.getWidth == 4)
    assert(io.selectedPeReadyMask.getWidth == 3)
    assert(io.targetPeMask.getWidth == 3)
  }

  test("TULinkLocalBlockCommitFanout elaborates as the SGPR block-commit fanout boundary") {
    val sv = ChiselStage.emitSystemVerilog(
      new TULinkLocalBlockCommitFanout(
        p = InterfaceParams(robEntries = 8),
        peCount = 2,
        stidCount = 2,
        stidWidth = 2)
    )

    assert(sv.contains("module TULinkLocalBlockCommitFanout"))
    assert(sv.contains("io_bankReady_0_0"))
    assert(sv.contains("io_bankValid_1_1"))
    assert(sv.contains("io_bankBid_0_0_value"))
    assert(sv.contains("io_bankStid_1_1"))
    assert(sv.contains("io_selectedStidOH"))
    assert(sv.contains("io_selectedPeReadyMask"))
    assert(sv.contains("io_blockedByBankReady"))
  }
}
