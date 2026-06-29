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
    assert(io.readyMask.getWidth == 64)
    assert(io.readValid.length == 3)
    assert(io.readTags.head.getWidth == 6)
    assert(io.readData.head.getWidth == 64)
    assert(io.issueUop.getWidth == io.in.getWidth)
    assert(io.issueSrcData.head.getWidth == 64)
    assert(io.count.getWidth == 3)
    assert(io.issuedCount.getWidth == 3)
    assert(io.notIssuedCount.getWidth == 3)
    assert(io.sourceReadyMask.getWidth == 3)
    assert(io.selectedIndex.getWidth == 2)
    assert(io.selectedReadReady.getWidth == 1)
    assert(io.enqueueDstTag.getWidth == 6)
  }

  test("ReducedScalarIssueQueue elaborates capacity, oldest-ready selection, and read-confirm diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarIssueQueue(InterfaceParams(), depth = 4))

    assert(sv.contains("module ReducedScalarIssueQueue"))
    assert(sv.contains("module ReducedScalarIssuePick"))
    assert(sv.contains("io_inReady"))
    assert(sv.contains("io_readValid"))
    assert(sv.contains("io_issueValid"))
    assert(sv.contains("io_enqueueFire"))
    assert(sv.contains("io_issueFire"))
    assert(sv.contains("io_releaseFire"))
    assert(sv.contains("io_headIssued"))
    assert(sv.contains("io_sourceReadyMask"))
    assert(sv.contains("io_selectedValid"))
    assert(sv.contains("io_selectedIndex"))
    assert(sv.contains("io_selectedReadReady"))
    assert(sv.contains("io_blockedByRead"))
    assert(sv.contains("io_blockedByIssued"))
    assert(sv.contains("io_blockedBySource"))
    assert(sv.contains("io_blockedByOutput"))
  }
}
