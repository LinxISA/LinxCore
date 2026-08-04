package linxcore.params

import linxcore.iex.OOOIEXLSUActivationParams
import org.scalatest.funsuite.AnyFunSuite

class SimulationParamProfilesSpec extends AnyFunSuite {
  private val widths = Seq(2, 4, 6, 8)

  test("simulation profiles preserve principal widths and fixed identity domains") {
    widths.foreach { width =>
      val main = ParamProfiles.forWidth(width)
      val sim = SimulationParamProfiles.forWidth(width)
      val prefixCapacity = if (width <= 2) 2 else if (width <= 4) 4 else 8
      val issueCapacity = math.max(4, prefixCapacity)

      ParamChecks.validate(sim)
      assert(sim.widths == WidthParams.uniform(width))
      assert(sim.ifu.fetchWidth == width)
      assert(sim.ifu.ctuTransferWidth == width)
      assert(sim.ctu.inputWidth == width)
      assert(sim.ctu.outputWidth == width)
      assert(sim.ooo.decodeWidth == width)
      assert(sim.ooo.renameWidth == width)
      assert(sim.ooo.d3PrefixWidth == width)
      assert(sim.ooo.dispatchWidth == width)
      assert(sim.ooo.retireWidth == width)
      assert(sim.iex.issueWidth == width)
      assert(sim.ooo.robGroupsPerStid == main.ooo.robGroupsPerStid)
      assert(sim.ooo.maxInstructionsPerRobGroup ==
        main.ooo.maxInstructionsPerRobGroup)
      assert(sim.ooo.maxUopsPerInstruction == main.ooo.maxUopsPerInstruction)
      assert(sim.ooo.robBankCount == prefixCapacity)
      assert(sim.ooo.pcRecoveryScanGroupsPerCycle == math.min(4,
        prefixCapacity))
      assert(sim.ooo.robGroupsPerStid %
        sim.ooo.pcRecoveryScanGroupsPerCycle == 0)
      assert(sim.ooo.gprPhysRegs == main.ooo.gprPhysRegs)
      assert(sim.ooo.gprMapQDepthPerStid == main.ooo.gprMapQDepthPerStid)
      assert(sim.ooo.tPhysRegs == main.ooo.tPhysRegs)
      assert(sim.ooo.uPhysRegs == main.ooo.uPhysRegs)
      assert(sim.ooo.tuMapQDepthPerStid == main.ooo.tuMapQDepthPerStid)
      assert(sim.iex.scalarIssueEntries == issueCapacity)
      assert(sim.iex.scalarIssueEntries / sim.iex.scalarIssueBanks >= 2)
      assert(sim.pcWidth == main.pcWidth)
      assert(sim.instructionWidth == main.instructionWidth)
      assert(sim.instructionIdWidth == main.instructionIdWidth)
      assert(sim.transactionIdWidth == main.transactionIdWidth)
      assert(sim.lsidWidth == main.lsidWidth)
      assert(sim.memoryTransactionIdWidth == main.memoryTransactionIdWidth)
      assert(sim.memoryTransactionGenerationWidth ==
        main.memoryTransactionGenerationWidth)
      assert(sim.memoryAttemptGenerationWidth ==
        main.memoryAttemptGenerationWidth)
      assert(sim.epochWidth == main.epochWidth)
      assert(sim.ridGenerationWidth == main.ridGenerationWidth)
      assert(sim.brobGenerationWidth == main.brobGenerationWidth)
    }
  }

  test("W4 simulation topology remains two one two two and two load two store") {
    val p = SimulationParamProfiles.W4

    assert((p.iex.aluPipes, p.iex.bruPipes, p.iex.aguPipes, p.iex.stdPipes) ==
      (2, 1, 2, 2))
    assert(p.iex.systemMulticycleQueues == 1)
    assert(p.iex.cmdIssueQueues == 1)
    assert((p.lsu.loadPipes, p.lsu.storePipes) == (2, 2))
    assert((p.ooo.pcWritePorts, p.ooo.pcReadPorts) == (3, 6))
  }

  test("W8 directed profile uses minimum retained capacities for the proof") {
    val p = SimulationParamProfiles.W8

    assert(p.ifu.fetchBufferEntries == 8)
    assert(p.ifu.predictionCheckpointEntries == 8)
    assert(p.ctu.instructionBufferEntries == 8)
    assert(p.ctu.maxTemplateUops == 2)
    assert(p.ooo.robGroupsPerStid == ParamProfiles.W8.ooo.robGroupsPerStid)
    assert(p.ooo.maxInstructionsPerRobGroup ==
      ParamProfiles.W8.ooo.maxInstructionsPerRobGroup)
    assert(p.ooo.maxUopsPerInstruction ==
      ParamProfiles.W8.ooo.maxUopsPerInstruction)
    assert(p.ooo.robBankCount == 8)
    assert(p.ooo.brobEntriesPerStid == ParamProfiles.W8.ooo.brobEntriesPerStid)
    assert(p.ooo.pcBufferEntries == ParamProfiles.W8.ooo.pcBufferEntries)
    assert(p.ooo.pcBankCount == ParamProfiles.W8.ooo.pcBankCount)
    assert(p.ooo.pcRecoveryScanGroupsPerCycle == 4)
    assert(p.ooo.gprPhysRegs == ParamProfiles.W8.ooo.gprPhysRegs)
    assert(p.ooo.gprMapQDepthPerStid ==
      ParamProfiles.W8.ooo.gprMapQDepthPerStid)
    assert(p.ooo.tPhysRegs == ParamProfiles.W8.ooo.tPhysRegs)
    assert(p.ooo.uPhysRegs == ParamProfiles.W8.ooo.uPhysRegs)
    assert(p.ooo.tuMapQDepthPerStid ==
      ParamProfiles.W8.ooo.tuMapQDepthPerStid)
    assert(p.iex.scalarIssueEntries == 8)
    assert((p.lsu.loadQueueEntries, p.lsu.storeQueueEntries) == (2, 2))
    assert(p.lsu.loadReturnQueueEntries == 2)
    assert(p.lsu.storeCommitQueueEntries == 2)
    assert(p.lsu.scbEntries == 4)
  }

  test("identity-derived domains never narrow in simulation profiles") {
    val main = ParamProfiles.W8
    val sim = SimulationParamProfiles.W8

    assert(sim.nativeBidWidth == main.nativeBidWidth)
    assert(sim.ooo.robGroupsPerStid == main.ooo.robGroupsPerStid)
    assert(sim.ooo.maxInstructionsPerRobGroup ==
      main.ooo.maxInstructionsPerRobGroup)
    assert(sim.ooo.gprPhysRegs == main.ooo.gprPhysRegs)
    assert(sim.ooo.gprMapQDepthPerStid == main.ooo.gprMapQDepthPerStid)
    assert(sim.ooo.tPhysRegs == main.ooo.tPhysRegs)
    assert(sim.ooo.uPhysRegs == main.ooo.uPhysRegs)
    assert(sim.ooo.tuMapQDepthPerStid == main.ooo.tuMapQDepthPerStid)
    assert(sim.ooo.pcBufferEntries == main.ooo.pcBufferEntries)
  }

  test("W4 activation preserves every identity-defining production domain") {
    val main = ParamProfiles.W4
    val activation = OOOIEXLSUActivationParams.W4

    assert(activation.ooo.stidCount == main.ooo.stidCount)
    assert(activation.ooo.robGroupsPerStid == main.ooo.robGroupsPerStid)
    assert(activation.ooo.maxInstructionsPerRobGroup ==
      main.ooo.maxInstructionsPerRobGroup)
    assert(activation.ooo.maxUopsPerInstruction ==
      main.ooo.maxUopsPerInstruction)
    assert(activation.ooo.brobEntriesPerStid == main.ooo.brobEntriesPerStid)
    assert(activation.ooo.gprPhysRegs == main.ooo.gprPhysRegs)
    assert(activation.ooo.gprMapQDepthPerStid == main.ooo.gprMapQDepthPerStid)
    assert(activation.ooo.tPhysRegs == main.ooo.tPhysRegs)
    assert(activation.ooo.uPhysRegs == main.ooo.uPhysRegs)
    assert(activation.ooo.tuMapQDepthPerStid == main.ooo.tuMapQDepthPerStid)
    assert(activation.nativeBidWidth == main.nativeBidWidth)
    assert(activation.ridGenerationWidth == main.ridGenerationWidth)
    assert(activation.brobGenerationWidth == main.brobGenerationWidth)
    assert(activation.lsidWidth == main.lsidWidth)
    assert(activation.transactionIdWidth == main.transactionIdWidth)
    assert(activation.memoryTransactionIdWidth ==
      main.memoryTransactionIdWidth)
    assert(activation.memoryTransactionGenerationWidth ==
      main.memoryTransactionGenerationWidth)
    assert(activation.memoryAttemptGenerationWidth ==
      main.memoryAttemptGenerationWidth)
  }

  test("simulation capacity changes never mutate main profiles") {
    assert(ParamProfiles.W8.ooo.robGroupsPerStid == 64)
    assert(ParamProfiles.W8.ooo.maxInstructionsPerRobGroup == 4)
    assert(ParamProfiles.W8.ooo.brobEntriesPerStid == 256)
    assert(ParamProfiles.W8.ooo.pcBufferEntries == 64)
    assert(ParamProfiles.W8.ooo.gprPhysRegs == 128)
    assert(ParamProfiles.W8.ooo.gprMapQDepthPerStid == 256)
    assert(ParamProfiles.W8.iex.scalarIssueEntries == 64)
    assert(ParamProfiles.W8.lsu.loadQueueEntries == 16)
    assert(ParamProfiles.W8.lsu.storeQueueEntries == 16)
  }

  test("unsupported simulation width fails before publishing a profile") {
    val error = intercept[IllegalArgumentException] {
      SimulationParamProfiles.forWidth(3)
    }

    assert(error.getMessage.contains("supported widths are 2, 4, 6, and 8"))
  }
}
