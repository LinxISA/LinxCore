package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.common.{CoreParams, ScalarLsuParams}
import org.scalatest.funsuite.AnyFunSuite

class ScalarLSUSpec extends AnyFunSuite {
  test("scalar LSU sizing separates ROB identity from store capacity") {
    val lsu = ScalarLsuParams(
      stqEntries = 8,
      commitQueueEntries = 4,
      commitIssueWidth = 1,
      scbEntries = 4,
      scbResponseBufferDepth = 2,
      mapQDepth = 8
    )
    val core = CoreParams(robEntries = 32, commitWidth = 2, scalarLsu = lsu)

    assert(core.robEntries == 32)
    assert(core.scalarLsu.stqEntries == 8)
    assert(core.scalarLsu.commitQueueEntries == 4)
  }

  test("Chisel ScalarLSU owns the parameterized STQ-to-SCB store path") {
    val lsu = ScalarLsuParams(
      stqEntries = 8,
      commitQueueEntries = 4,
      commitIssueWidth = 1,
      scbEntries = 4,
      scbResponseBufferDepth = 2,
      mapQDepth = 8
    )
    val sv = ChiselStage.emitSystemVerilog(
      new ScalarLSU(CoreParams(robEntries = 32, commitWidth = 2, scalarLsu = lsu))
    )

    assert(sv.contains("module ScalarLSU"))
    assert(sv.contains("module STQSCBCommitPath"))
    assert(sv.contains("module STQEntryBank"))
    assert(sv.contains("module STQCommitDrain"))
    assert(sv.contains("module SCBRowBank"))
    assert("""(?s)module ScalarLSU\(.*?input\s+\[4:0\]\s+io_flush_req_bid_value""".r.findFirstIn(sv).nonEmpty)
    assert(sv.contains("io_stqRows_7_valid"))
  }
}
