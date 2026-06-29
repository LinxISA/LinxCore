package linxcore.rename

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ScalarTURenameBridgeReference {
  def accepts(
      inputValid: Boolean,
      scalarReady: Boolean,
      tuReady: Boolean,
      robReady: Boolean,
      outReady: Boolean,
      localUnsupported: Boolean): Boolean =
    inputValid && scalarReady && tuReady && robReady && outReady && !localUnsupported

  def scalarSourcePresentedToGpr(operandClass: String): Boolean =
    operandClass == "P"

  def preservesTUSource(operandClass: String): Boolean =
    operandClass == "T" || operandClass == "U"

  def localBlockCommitReady(
      externalCommitValid: Boolean,
      recoveryActive: Boolean,
      eventStid: Int = 0,
      localStid: Int = 0): Boolean =
    eventStid == localStid && !externalCommitValid && !recoveryActive

  def activeBankValid(activePe: Int, activeStid: Int, peCount: Int, stidCount: Int): Boolean =
    activePe >= 0 && activePe < peCount && activeStid >= 0 && activeStid < stidCount
}

class ScalarTURenameBridgeSpec extends AnyFunSuite {
  import ScalarTURenameBridgeReference._

  test("reference accepts scalar and T/U rename atomically") {
    assert(accepts(inputValid = true, scalarReady = true, tuReady = true, robReady = true, outReady = true, localUnsupported = false))
    assert(!accepts(inputValid = true, scalarReady = true, tuReady = false, robReady = true, outReady = true, localUnsupported = false))
    assert(!accepts(inputValid = true, scalarReady = true, tuReady = true, robReady = false, outReady = true, localUnsupported = false))
    assert(!accepts(inputValid = true, scalarReady = true, tuReady = true, robReady = true, outReady = false, localUnsupported = false))
    assert(!accepts(inputValid = true, scalarReady = true, tuReady = true, robReady = true, outReady = true, localUnsupported = true))
  }

  test("reference sanitizes non-GPR operands away from scalar rename while preserving T/U overlay ownership") {
    assert(scalarSourcePresentedToGpr("P"))
    assert(!scalarSourcePresentedToGpr("T"))
    assert(!scalarSourcePresentedToGpr("U"))
    assert(preservesTUSource("T"))
    assert(preservesTUSource("U"))
    assert(!preservesTUSource("CArg"))
  }

  test("reference keeps local block commit behind external maintenance") {
    assert(localBlockCommitReady(externalCommitValid = false, recoveryActive = false))
    assert(!localBlockCommitReady(externalCommitValid = true, recoveryActive = false))
    assert(!localBlockCommitReady(externalCommitValid = false, recoveryActive = true))
    assert(!localBlockCommitReady(externalCommitValid = false, recoveryActive = false, eventStid = 1, localStid = 0))
  }

  test("reference routes active T/U bank selection from explicit PE/STID sidebands") {
    assert(activeBankValid(activePe = 0, activeStid = 1, peCount = 1, stidCount = 2))
    assert(!activeBankValid(activePe = 1, activeStid = 1, peCount = 1, stidCount = 2))
    assert(!activeBankValid(activePe = 0, activeStid = 2, peCount = 1, stidCount = 2))
  }

  test("IO exposes scalar, T/U rename, ROB allocation, and cleanup surfaces") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val io = new ScalarTURenameBridgeIO(p, trace, mapQDepth = 8, scalarStidCount = 2)

    assert(io.in.src.length == 3)
    assert(io.activePeId.getWidth == 8)
    assert(io.activeStid.getWidth == 8)
    assert(io.out.dst.length == 1)
    assert(io.robAllocRow.identity.rid.getWidth == 32)
    assert(io.tuSrc.length == 3)
    assert(io.tuActivePeInRange.getWidth == 1)
    assert(io.tuActiveStidInRange.getWidth == 1)
    assert(io.tuActiveBankValid.getWidth == 1)
    assert(io.tuActivePeOH.getWidth == 1)
    assert(io.tuActiveStidOH.getWidth == 2)
    assert(io.tuTSeq.value.getWidth == 3)
    assert(io.tuUSeq.value.getWidth == 3)
    assert(io.tuDstValid.getWidth == 1)
    assert(io.tuSourceUnderflowMask.getWidth == 3)
    assert(io.tuRetirePeId.getWidth == 8)
    assert(io.tuRetireStid.getWidth == 8)
    assert(io.tuRetireAccepted.getWidth == 1)
    assert(io.tuRetireReleaseMismatch.getWidth == 1)
    assert(io.tuRetirePeInRange.getWidth == 1)
    assert(io.tuRetireStidInRange.getWidth == 1)
    assert(io.tuRetireBankValid.getWidth == 1)
    assert(io.tuRetirePeOH.getWidth == 1)
    assert(io.tuRetireStidOH.getWidth == 2)
    assert(io.tuLocalBlockCommitBid.value.getWidth == 3)
    assert(io.tuLocalBlockCommitStid.getWidth == 8)
    assert(io.tuLocalBlockCommitReady.getWidth == 1)
    assert(io.tuLocalBlockCommitAccepted.getWidth == 1)
    assert(io.tuLocalBlockCommitStidMatch.getWidth == 1)
    assert(io.tuLocalBlockCommitBlockedByStid.getWidth == 1)
    assert(io.tuLocalBlockCommitBlockedByBankReady.getWidth == 1)
    assert(io.tuLocalBlockCommitFanoutStidInRange.getWidth == 1)
    assert(io.tuLocalBlockCommitFanoutBlockedByStidRange.getWidth == 1)
    assert(io.tuLocalBlockCommitFanoutBlockedByBankReady.getWidth == 1)
    assert(io.tuLocalBlockCommitFanoutTargetPeMask.getWidth == 1)
    assert(io.tuLocalBlockCommitFanoutReadyPeMask.getWidth == 1)
    assert(io.tuCleanupSelectedFlushSource.tSeq.value.getWidth == 3)
    assert(io.tuCleanupSourceConflict.getWidth == 1)
  }

  test("ScalarTURenameBridge elaborates as scalar GPR rename plus T/U cleanup composition owner") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val sv = ChiselStage.emitSystemVerilog(
      new ScalarTURenameBridge(p = p, traceParams = trace, mapQDepth = 8, scalarStidCount = 2)
    )

    assert(sv.contains("module ScalarTURenameBridge"))
    assert(sv.contains("io_activePeId"))
    assert(sv.contains("io_activeStid"))
    assert(sv.contains("module ScalarDecodeRenameBridge"))
    assert(sv.contains("module TULinkLocalBankArray"))
    assert(sv.contains("module TULinkRecoveryCleanupPath"))
    assert(sv.contains("module TULinkLocalBlockCommitFanout"))
    assert(sv.contains("module TULinkRename"))
    assert(sv.contains("io_tuTSeq_value"))
    assert(sv.contains("io_tuUSeq_value"))
    assert(sv.contains("io_tuActiveBankValid"))
    assert(sv.contains("io_tuActiveStidOH"))
    assert(sv.contains("io_tuDstValid"))
    assert(sv.contains("io_tuRetirePeId"))
    assert(sv.contains("io_tuRetireStid"))
    assert(sv.contains("io_tuRetireBankValid"))
    assert(sv.contains("io_tuRetireAccepted"))
    assert(sv.contains("io_tuRetireReleaseMismatch"))
    assert(sv.contains("io_tuLocalBlockCommitStid"))
    assert(sv.contains("io_tuLocalBlockCommitReady"))
    assert(sv.contains("io_tuLocalBlockCommitAccepted"))
    assert(sv.contains("io_tuLocalBlockCommitStidMatch"))
    assert(sv.contains("io_tuLocalBlockCommitBlockedByStid"))
    assert(sv.contains("io_tuLocalBlockCommitFanoutBlockedByBankReady"))
    assert(sv.contains("io_tuCleanupSourceConflict"))
  }
}
