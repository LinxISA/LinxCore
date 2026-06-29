package linxcore.execute

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ReducedScalarIssueQueueSpec extends AnyFunSuite {
  test("interface exposes enqueue, RF-read query, and issue handshakes") {
    val p = InterfaceParams()
    val io = new ReducedScalarIssueQueueIO(p, depth = 4)

    assert(io.in.getWidth > 0)
    assert(io.readValid.length == 3)
    assert(io.readTags.head.getWidth == 6)
    assert(io.readData.head.getWidth == 64)
    assert(io.issueUop.getWidth == io.in.getWidth)
    assert(io.issueSrcData.head.getWidth == 64)
    assert(io.count.getWidth == 3)
    assert(io.enqueueDstTag.getWidth == 6)
  }

  test("ReducedScalarIssueQueue elaborates capacity and source-block diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarIssueQueue(InterfaceParams(), depth = 4))

    assert(sv.contains("module ReducedScalarIssueQueue"))
    assert(sv.contains("io_inReady"))
    assert(sv.contains("io_readValid"))
    assert(sv.contains("io_issueValid"))
    assert(sv.contains("io_enqueueFire"))
    assert(sv.contains("io_issueFire"))
    assert(sv.contains("io_blockedBySource"))
    assert(sv.contains("io_blockedByOutput"))
  }
}
