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

  test("installs one scalar load-store path without duplicating load or STQ ownership") {
    val profile = OooIexLinxPhysicalProfile(base)
    val systemVerilog = ChiselStage.emitSystemVerilog(
      new OooIexExecutionStorePipeline(profile, stqEntries = 4))

    assert(profile.pickerFunctions.length == 14)
    assert(profile.params.iexReleaseWidth == 14)
    assert(profile.params.iexTerminalWidth == 2)
    assert(systemVerilog.contains("module OooIexExecutionStorePipeline"))
    assert(systemVerilog.contains("OooIexExecutionPipeline execution"))
    assert(systemVerilog.contains(
      "OooIexScalarLoadStorePath scalarLoadStore"))
    assert(systemVerilog.contains("OooIexStoreStqFabric store"))
    assert(systemVerilog.contains("STQSCBCommitBackend storeCommit"))
    assert(systemVerilog.contains("OooIexPipeline issue"))
    assert(systemVerilog.contains("OooIexExecutionCluster execute"))
    assert("OooIexCanonicalLoadOwnership load".r
      .findAllMatchIn(systemVerilog).length == 1)
    assert("OooIexStoreStqFabric store".r
      .findAllMatchIn(systemVerilog).length == 1)
  }
}
