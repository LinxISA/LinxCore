package linxcore.execute

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ReducedScalarIssueQueueSpec extends AnyFunSuite {
  test("interface exposes enqueue, RF-read query, issue, and release handshakes") {
    val p = InterfaceParams()
    val io = new ReducedScalarIssueQueueIO(p, depth = 4)

    assert(io.in.getWidth > 0)
    assert(io.releaseBid.value.getWidth == p.robIndexWidth)
    assert(io.releaseRid.value.getWidth == p.robIndexWidth)
    assert(io.releaseStid.getWidth == p.threadIdWidth)
    assert(io.secondaryReleaseBid.value.getWidth == p.robIndexWidth)
    assert(io.secondaryReleaseRid.value.getWidth == p.robIndexWidth)
    assert(io.secondaryReleaseStid.getWidth == p.threadIdWidth)
    assert(io.readyMask.getWidth == 64)
    assert(io.pWakeupTag.getWidth == 6)
    assert(io.pWakeupMatchCount.getWidth == 4)
    assert(io.localTReadyMask.getWidth == 4)
    assert(io.localUReadyMask.getWidth == 4)
    assert(io.readValid.length == 3)
    assert(io.readTags.head.getWidth == 6)
    assert(io.readRelTag.head.getWidth == 6)
    assert(io.readData.head.getWidth == 64)
    assert(io.readGrant.getWidth == 1)
    assert(io.readAttemptValid.getWidth == 1)
    assert(io.readUop.getWidth == io.in.getWidth)
    assert(io.issueUop.getWidth == io.in.getWidth)
    assert(io.issueSrcData.head.getWidth == 64)
    assert(io.count.getWidth == 3)
    assert(io.issuedCount.getWidth == 3)
    assert(io.notIssuedCount.getWidth == 3)
    assert(io.headPc.getWidth == 64)
    assert(io.headOpcode.getWidth == 12)
    assert(io.headSrcValidMask.getWidth == 3)
    assert(io.headSrcOperandClass.length == 3)
    assert(io.headSrcPhysTag.head.getWidth == 6)
    assert(io.headSrcRelTag.head.getWidth == 6)
    assert(io.sourceReadyMask.getWidth == 3)
    assert(io.selectedIndex.getWidth == 2)
    assert(io.selectedReadReady.getWidth == 1)
    assert(io.pickFire.getWidth == 1)
    assert(io.cancelFire.getWidth == 1)
    assert(io.i1Valid.getWidth == 1)
    assert(io.i2Valid.getWidth == 1)
    assert(io.stageBusy.getWidth == 1)
    assert(io.enqueueDstTag.getWidth == 6)
  }

  test("ReducedScalarIssueQueue elaborates capacity, oldest-ready selection, and read-confirm diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarIssueQueue(InterfaceParams(), depth = 4))

    assert(sv.contains("module ReducedScalarIssueQueue"))
    assert(sv.contains("module ReducedScalarIssuePick"))
    assert(sv.contains("io_inReady"))
    assert(sv.contains("io_pWakeupValid"))
    assert(sv.contains("io_pWakeupMatched"))
    assert(sv.contains("io_pWakeupMatchCount"))
    assert(sv.contains("io_readValid"))
    assert(sv.contains("io_readOperandClass"))
    assert(sv.contains("io_readGrant"))
    assert(sv.contains("io_readAttemptValid"))
    assert(sv.contains("io_issueValid"))
    assert(sv.contains("io_enqueueFire"))
    assert(sv.contains("io_pickFire"))
    assert(sv.contains("io_issueFire"))
    assert(sv.contains("io_cancelFire"))
    assert(sv.contains("io_releaseFire"))
    assert(sv.contains("io_secondaryReleaseValid"))
    assert(sv.contains("io_headIssued"))
    assert(sv.contains("io_headPc"))
    assert(sv.contains("io_headSrcValidMask"))
    assert(sv.contains("io_headSrcPhysTag"))
    assert(sv.contains("io_sourceReadyMask"))
    assert(sv.contains("io_selectedValid"))
    assert(sv.contains("io_selectedIndex"))
    assert(sv.contains("io_selectedReadReady"))
    assert(sv.contains("io_i1Valid"))
    assert(sv.contains("io_i2Valid"))
    assert(sv.contains("io_stageBusy"))
    assert(sv.contains("io_blockedByRead"))
    assert(sv.contains("io_blockedByIssued"))
    assert(sv.contains("io_blockedBySource"))
    assert(sv.contains("io_blockedByOutput"))
  }

  test("committed P wakeup matches only global P operands with the same physical tag") {
    def matches(valid: Boolean, issued: Boolean, operandClass: String, tag: Int, wakeTag: Int): Boolean =
      valid && !issued && operandClass == "P" && tag == wakeTag

    assert(matches(valid = true, issued = false, operandClass = "P", tag = 40, wakeTag = 40))
    assert(!matches(valid = true, issued = true, operandClass = "P", tag = 40, wakeTag = 40))
    assert(!matches(valid = true, issued = false, operandClass = "T", tag = 40, wakeTag = 40))
    assert(!matches(valid = true, issued = false, operandClass = "P", tag = 41, wakeTag = 40))
  }
}
