package linxcore.execute

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ReducedScalarIssuePickSpec extends AnyFunSuite {
  test("interface exposes selected-row RF read and issue-confirm diagnostics") {
    val p = InterfaceParams()
    val io = new ReducedScalarIssuePickIO(p, depth = 4)

    assert(io.selectableMask.getWidth == 4)
    assert(io.entries.length == 4)
    assert(io.notIssuedCount.getWidth == 3)
    assert(io.flushValid.getWidth == 1)
    assert(io.readValid.length == 3)
    assert(io.readTags.head.getWidth == 6)
    assert(io.readData.head.getWidth == 64)
    assert(io.issueUop.getWidth == io.entries.head.getWidth)
    assert(io.issueSrcData.head.getWidth == 64)
    assert(io.pickFire.getWidth == 1)
    assert(io.cancelIndex.getWidth == 2)
    assert(io.i1Valid.getWidth == 1)
    assert(io.i2Valid.getWidth == 1)
    assert(io.selectedIndex.getWidth == 2)
  }

  test("ReducedScalarIssuePick elaborates pick, read-confirm, and block outputs") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarIssuePick(InterfaceParams(), depth = 4))

    assert(sv.contains("module ReducedScalarIssuePick"))
    assert(sv.contains("io_selectableMask"))
    assert(sv.contains("io_readValid"))
    assert(sv.contains("io_pickFire"))
    assert(sv.contains("io_issueValid"))
    assert(sv.contains("io_issueFire"))
    assert(sv.contains("io_cancelFire"))
    assert(sv.contains("io_i1Valid"))
    assert(sv.contains("io_i2Valid"))
    assert(sv.contains("io_stageBusy"))
    assert(sv.contains("io_selectedValid"))
    assert(sv.contains("io_selectedIndex"))
    assert(sv.contains("io_selectedReadReady"))
    assert(sv.contains("io_blockedByRead"))
    assert(sv.contains("io_blockedBySource"))
    assert(sv.contains("io_blockedByOutput"))
    assert(sv.contains("io_blockedByIssued"))
  }
}
