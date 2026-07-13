package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.common.ScalarLsuParams
import org.scalatest.funsuite.AnyFunSuite

class ScalarL1DSpec extends AnyFunSuite {
  test("L1D sets and ways are independent scalar-LSU parameters") {
    val p = ScalarLsuParams(l1dSets = 32, l1dWays = 8)
    assert(p.l1dSets == 32)
    assert(p.l1dWays == 8)
  }

  test("invalid L1D geometry is rejected") {
    assertThrows[IllegalArgumentException](ScalarLsuParams(l1dSets = 3))
    assertThrows[IllegalArgumentException](ScalarLsuParams(l1dWays = 1))
  }

  test("ScalarL1D elaborates arrays permissions replacement and eviction") {
    val sv = ChiselStage.emitSystemVerilog(
      new ScalarL1D(sets = 8, ways = 4, scbEntries = 8))

    assert(sv.contains("module ScalarL1D"))
    assert(sv.contains("io_loadLookup_readHit"))
    assert(sv.contains("io_storeLookup_writeHit"))
    assert(sv.contains("io_refillDuplicate"))
    assert(sv.contains("io_eviction_dirty"))
    assert(sv.contains("io_residentCount"))
    assert(sv.contains("io_dirtyCount"))
    assert(sv.contains("io_protocolError"))
  }

  test("canonical scalar load path owns L1D instead of external base-line data") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarLSULoadPath())
    assert(sv.contains("module ScalarL1D"))
    assert(sv.contains("io_l1dEviction_valid"))
    assert(sv.contains("io_l1dRefillDuplicate"))
    val loadPathHeader = "(?s)module ScalarLSULoadPath\\(.*?\\);".r.findFirstIn(sv).get
    assert(!loadPathHeader.contains("io_e2BaseData"))
    assert(!loadPathHeader.contains("io_e2BaseValidMask"))
  }
}
