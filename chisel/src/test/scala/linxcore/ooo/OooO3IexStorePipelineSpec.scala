package linxcore.ooo

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class OooO3IexStorePipelineSpec extends AnyFunSuite {
  test("elaborates the closed O3 to IEX to canonical store-retirement path") {
    val p = OooParams(
      stidCount = 2,
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      retireGroupWidth = 2,
      robCompletionBufferEntries = 4,
      storeCommitBufferEntries = 8,
      maxInstPerRobGroup = 2,
      maxOrdinaryUopsPerGroup = 2,
      maxRecipeUops = 2,
      robGroupsPerStid = 4,
      robBankCount = 2,
      robRecoveryScanGroupsPerCycle = 2,
      robNonFlushScanGroupsPerCycle = 2,
      brobEntriesPerStid = 4,
      pPhysRegs = 64,
      pTagStagingDepthPerBank = 2,
      pMapQDepthPerStid = 4,
      tPhysRegs = 4,
      uPhysRegs = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 8,
      tuRelationDepthPerStid = 4,
      tuRelationReleaseThreshold = 2,
      pcBufferEntries = 4,
      pcBankCount = 2,
      pcRecoveryScanGroupsPerCycle = 2,
      pcWritePorts = 2,
      iqEntriesPerBank = 1,
      iqWritePortsPerBank = 2,
      iqFreeSelectLeafEntries = 1,
      iexLoadTrackEntries = 4)
    // The complete O3+IEX hierarchy intentionally exceeds the 4 GiB local
    // FIRRTL-to-SystemVerilog test budget.  CHIRRTL still elaborates every
    // Chisel owner and connection while the child SV gates cover lowering.
    val chirrtl = ChiselStage.emitCHIRRTL(
      new OooO3IexStorePipeline(
        OooIexLinxPhysicalProfile(p), stqEntries = 4))

    assert(chirrtl.contains("circuit OooO3IexStorePipeline"))
    assert(chirrtl.contains("inst o3 of OooO3RenameCoordinator"))
    assert(chirrtl.contains("inst completionBuffer of OooRobCompletionBuffer"))
    assert(chirrtl.contains("inst iex of OooIexExecutionStorePipeline"))
    assert(chirrtl.contains("inst fastResult of OooIexFastResultPort"))
    assert(chirrtl.contains("inst storeCommit of STQSCBCommitBackend"))
  }
}
