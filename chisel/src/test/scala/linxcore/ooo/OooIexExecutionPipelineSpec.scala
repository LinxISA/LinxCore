package linxcore.ooo

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

/** Production-top structural integration. Dynamic stage behavior remains
  * covered by the focused S1-to-E1, execution-cluster, terminal, and operand
  * file suites so this gate does not rebuild the monolithic formal IQ in
  * Verilator.
  */
class OooIexExecutionPipelineSpec extends AnyFunSuite {
  private val base = OooParams(
    stidCount = 2,
    instructionDecodeWidth = 2,
    decodedUopWidth = 2,
    renameWidth = 2,
    dispatchWidth = 2,
    retireGroupWidth = 2,
    robGroupsPerStid = 8,
    robBankCount = 2,
    robRecoveryScanGroupsPerCycle = 2,
    robNonFlushScanGroupsPerCycle = 2,
    pcBufferEntries = 8,
    pcBankCount = 2,
    pcRecoveryScanGroupsPerCycle = 2,
    pcWritePorts = 2,
    iqBankCount = 8,
    iqEntriesPerBank = 1,
    iqWritePortsPerBank = 2,
    iqFreeSelectLeafEntries = 1,
    pMapQDepthPerStid = 4,
    tuMapQDepthPerStid = 4,
    tuRetireSourceDepthPerStid = 16)

  test("elaborates the formal fourteen-lane S1-to-W2 composition") {
    val profile = OooIexLinxPhysicalProfile(base)
    val systemVerilog = ChiselStage.emitSystemVerilog(
      new OooIexExecutionPipeline(profile))

    assert(profile.pickerFunctions.length == 14)
    assert(profile.params.iexReleaseWidth == 14)
    assert(profile.params.iexTerminalWidth == 2)
    assert(systemVerilog.contains("module OooIexExecutionPipeline"))
    assert(systemVerilog.contains("OooIexPipeline issue"))
    assert(systemVerilog.contains("OooIexExecutionCluster execute"))
  }
}
