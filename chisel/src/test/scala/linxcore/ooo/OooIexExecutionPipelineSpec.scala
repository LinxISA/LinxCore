package linxcore.ooo

import circt.stage.ChiselStage
import linxcore.params.ParamProfiles
import org.scalatest.funsuite.AnyFunSuite

/** Canonical private-cluster structural integration. Dynamic stage behavior is
  * covered by the focused dispatch-to-E1, execution-cluster, terminal, and operand
  * file suites so this gate does not rebuild the monolithic formal IQ in
  * Verilator.
  */
class OooIexExecutionPipelineSpec extends AnyFunSuite {
  private val core = ParamProfiles.W4

  test("installs canonical issue and execution owners without legacy ingress") {
    val profile = OooIexPhysicalProfile.fromCoreParams(core)
    val systemVerilog = ChiselStage.emitSystemVerilog(
      new OooIexExecutionPipeline(core))

    assert(profile.pickerFunctions.length == profile.params.iexReleaseWidth)
    assert(profile.params.iexTerminalWidth == 2)
    assert(systemVerilog.contains("module OooIexExecutionPipeline"))
    assert(systemVerilog.contains("OooIexPipeline issue"))
    assert(systemVerilog.contains("OooIexExecutionCluster execute"))
    assert("OooIexCanonicalLoadOwnership load".r
      .findAllMatchIn(systemVerilog).length == 1)
    assert(!systemVerilog.contains("OooIexS1Transaction"))
    assert(!systemVerilog.contains("OooDispatchRelease"))
  }
}
