package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class LSUStructuralBlockIntegrationSpec extends AnyFunSuite {
  test("public-LSU-retains-and-retries-structural-STQ-results") {
    val systemVerilog = ChiselStage.emitSystemVerilog(
      new LSU(SimulationParamProfiles.W4))

    assert(systemVerilog.contains("module LoadStructuralBlockPolicy"))
    assert(systemVerilog.contains("io_iex_loadRepick_0_bits_structural"))
    assert(systemVerilog.contains("io_iex_loadRebindApply_0_bits_structural"))
    assert(!systemVerilog.contains(
      ".io_structuralRetryValid\n      (_structuralBlock_io_retry_valid)"))
    assert(systemVerilog.contains(".io_hardBlock_ready\n      (_structuralBlock_io_hardBlock_ready)"))
  }
}
