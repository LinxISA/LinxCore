package linxcore.execute

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ScalarIssueCandidateArbiterSpec extends AnyFunSuite {
  test("candidate arbiter exposes per-STID oldest and round-robin grant state") {
    val io = new ScalarIssueCandidateArbiterIO(InterfaceParams(), candidates = 2)

    assert(io.valid.length == 2)
    assert(io.stid.length == 2)
    assert(io.rid.length == 2)
    assert(io.grant.length == 2)
    assert(io.selectedIndex.getWidth == 1)
    assert(io.candidateCount.getWidth == 2)
    assert(io.rrBase.getWidth == 1)
  }

  test("candidate arbiter elaborates fair grant and invalid-RID diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new ScalarIssueCandidateArbiter(InterfaceParams(), candidates = 2))

    assert(sv.contains("module ScalarIssueCandidateArbiter"))
    assert(sv.contains("io_grant_0"))
    assert(sv.contains("io_selectedIndex"))
    assert(sv.contains("io_contended"))
    assert(sv.contains("io_invalidRid"))
    assert(sv.contains("rrBase"))
  }
}
