package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.common.{CoreParams => ScalarCoreParams, ScalarLsuParams}
import linxcore.params.{ParamProfiles, SimulationParamProfiles}
import org.scalatest.funsuite.AnyFunSuite

class LSUMechanismSpec extends AnyFunSuite {
  private val widths = Seq(2, 4, 6, 8)

  private def unequalCore(width: Int): ScalarCoreParams = {
    val main = ParamProfiles.forWidth(width)
    val centralized = main.copy(
      ooo = main.ooo.copy(
        robGroupsPerStid = 8,
        maxInstructionsPerRobGroup = 1,
        robBankCount = 8),
      lsu = main.lsu.copy(
        loadQueueEntries = 4,
        storeQueueEntries = 16,
        loadReturnQueueEntries = 2,
        storeCommitQueueEntries = 4,
        scbEntries = 4),
      lsidWidth = 40)
    val projected = ScalarCoreParams.fromMainline(centralized)
    projected.copy(scalarLsu = projected.scalarLsu.copy(
      loadMissQueueEntries = 2,
      loadRefillQueueEntries = 2,
      resolveQueueEntries = 4,
      mdbSsitEntries = 4,
      mdbCommandQueueEntries = 4,
      mdbOutputQueueEntries = 4,
      mdbWaitPlanQueueEntries = 4,
      mdbRecoveryQueueEntries = 4,
      mdbFailedWaitTimeoutCycles = 8,
      l1dSets = 2,
      l1dWays = 2))
  }

  test("W2 W4 W6 and W8 elaborate two-pipe LSU with independent STQ ROB and LSID sizes") {
    widths.foreach { width =>
      val main = ParamProfiles.forWidth(width)
      val simulation = SimulationParamProfiles.forWidth(width)
      val core = unequalCore(width)
      val io = new ScalarLSULoadPathIO(core, core.scalarLsu)
      val chirrtl = ChiselStage.emitCHIRRTL(new ScalarLSULoadPath(
        core,
        useExternalStqForwarding = true,
        stqForwardRobEntries = 8,
        stqForwardTokenWidth = 40))

      assert((main.lsu.loadPipes, main.lsu.storePipes) == (2, 2))
      assert((simulation.lsu.loadPipes, simulation.lsu.storePipes) == (2, 2))
      assert(core.commitWidth == width)
      assert(core.scalarLsu.loadReturnPipeCount == 2)
      assert(core.scalarLsu.commitIssueWidth == 2)
      assert(core.scalarLsu.stqEntries == 16)
      assert(core.robEntries == 8)
      assert(core.lsidWidth == 40)
      assert(io.e2Stores.length == 16)
      assert(io.e2Stores.head.storeIndex.getWidth == 4)
      assert(io.alloc.bid.value.getWidth == 3)
      assert(io.alloc.bid.wrap.getWidth == 1)
      assert(io.alloc.loadLsIdFull.getWidth == 40)
      assert(io.alloc.returnPipeIndex.getWidth == 1)
      assert(io.loadReturn.w1ValidMask.getWidth == 2)
      assert(chirrtl.contains("module LoadInflightQueue"))
      assert(chirrtl.contains("module ScalarLSUMDBPath"))
      assert(chirrtl.contains("module LoadMissQueue"))
      assert(chirrtl.contains("module LoadRefillTransport"))
      assert(chirrtl.contains("module ScalarLSULoadReturnPipeline"))
      assert(chirrtl.contains("module ScalarL1D"))
      assert(chirrtl.contains("module STQLoadForwardResultPipeline"))
      assert(chirrtl.contains("stqForward_queries_1_valid"))
      assert(!chirrtl.contains("stqForward_queries_2_valid"))
    }
  }
}
