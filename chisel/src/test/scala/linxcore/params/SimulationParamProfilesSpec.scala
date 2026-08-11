package linxcore.params

import linxcore.common.TemplateD3Constants
import linxcore.iex.OOOIEXLSUActivationParams
import linxcore.ooo.{OooParams => MechanismOooParams}
import org.scalatest.funsuite.AnyFunSuite

class SimulationParamProfilesSpec extends AnyFunSuite {
  private val widths = Seq(2, 4, 6, 8)

  test("simulation profiles preserve the main MDB failed-wait timeout") {
    widths.foreach { width =>
      val main = ParamProfiles.forWidth(width)
      val simulation = SimulationParamProfiles.forWidth(width)

      assert(main.lsu.mdbFailedWaitTimeoutCycles == 300)
      assert(simulation.lsu.mdbFailedWaitTimeoutCycles ==
        main.lsu.mdbFailedWaitTimeoutCycles)
    }
  }

  test("generic LSU and width profiles do not assume platform MMIO regions") {
    assert(LSUParams().physicalMemoryRegions.isEmpty)
    widths.foreach { width =>
      assert(ParamProfiles.forWidth(width).lsu.physicalMemoryRegions.isEmpty)
      assert(SimulationParamProfiles.forWidth(width).lsu.physicalMemoryRegions.isEmpty)
    }
  }

  test("natural_platform_normal_ram_noncacheable_devices_mmio") {
    val natural = NaturalPlatformParams(SimulationParamProfiles.W4)
    val regions = natural.lsu.physicalMemoryRegions

    assert(natural.lsu.defaultMemoryAttributes == MemoryAccessAttributes(
      readable = true, writable = true, cacheable = false, device = false))

    assert(regions.map(_.base) == Seq(
      BigInt("10000000", 16), BigInt("10009000", 16)))
    assert(regions.forall(_.mask == BigInt("fffffffffffff000", 16)))
    assert(regions.forall(region => region.attributes.readable &&
      region.attributes.writable && !region.attributes.cacheable &&
      region.attributes.device))
  }

  test("simulation profiles use the literal bounded IFU and DTU capacity table") {
    val expected = Map(
      2 -> (4, 4, 4, 4, 4),
      4 -> (4, 4, 4, 4, 4),
      6 -> (4, 4, 4, 4, 4),
      8 -> (4, 4, 4, 4, 4))

    widths.foreach { width =>
      val main = ParamProfiles.forWidth(width)
      val sim = SimulationParamProfiles.forWidth(width)
      val mainMechanism = MechanismOooParams.fromCoreParams(main)
      val simMechanism = MechanismOooParams.fromCoreParams(sim)
      val physicalCapacities = (
        sim.ifu.itlbEntries,
        sim.ifu.l1iSets,
        sim.ifu.missEntries,
        sim.ifu.joinEntries,
        sim.dtu.traceBufferEntries)

      ParamChecks.validate(sim)
      assert(physicalCapacities == expected(width))
      assert(sim.ifu.maxGroupsPerTransaction ==
        main.ifu.maxGroupsPerTransaction)
      assert(sim.dtu.performanceCounterCount ==
        main.dtu.performanceCounterCount)
      assert(sim.ctu.maxTemplateUops == main.ctu.maxTemplateUops)
      assert((sim.iex.aluPipes, sim.iex.bruPipes, sim.iex.aguPipes,
        sim.iex.stdPipes, sim.lsu.loadPipes, sim.lsu.storePipes) ==
        (main.iex.aluPipes, main.iex.bruPipes, main.iex.aguPipes,
          main.iex.stdPipes, main.lsu.loadPipes, main.lsu.storePipes))
      assert((simMechanism.stidWidth, simMechanism.ridSlotWidth,
        simMechanism.robMemberIndexWidth, simMechanism.nativeBidWidth,
        simMechanism.pTagWidth, simMechanism.tTagWidth,
        simMechanism.uTagWidth) ==
        (mainMechanism.stidWidth, mainMechanism.ridSlotWidth,
          mainMechanism.robMemberIndexWidth, mainMechanism.nativeBidWidth,
          mainMechanism.pTagWidth, mainMechanism.tTagWidth,
          mainMechanism.uTagWidth))
      assert((sim.lsidWidth, sim.transactionIdWidth,
        sim.memoryTransactionIdWidth, sim.memoryTransactionGenerationWidth,
        sim.memoryAttemptGenerationWidth) ==
        (main.lsidWidth, main.transactionIdWidth,
          main.memoryTransactionIdWidth,
          main.memoryTransactionGenerationWidth,
          main.memoryAttemptGenerationWidth))
    }
  }

  test("simulation profiles preserve principal widths and fixed identity domains") {
    widths.foreach { width =>
      val main = ParamProfiles.forWidth(width)
      val sim = SimulationParamProfiles.forWidth(width)
      val prefixCapacity = if (width <= 2) 2 else if (width <= 4) 4 else 8
      val robGroupCapacity = 16
      val issueCapacity = prefixCapacity

      ParamChecks.validate(sim)
      assert(sim.widths == WidthParams.uniform(width))
      assert(sim.ifu.fetchWidth == width)
      assert(sim.ifu.ctuTransferWidth == width)
      assert(sim.ctu.inputWidth == width)
      assert(sim.ctu.outputWidth == width)
      assert(sim.ctu.maxTemplateUops >= TemplateD3Constants.MaxRows)
      assert(sim.ooo.decodeWidth == width)
      assert(sim.ooo.renameWidth == width)
      assert(sim.ooo.d3PrefixWidth == width)
      assert(sim.ooo.dispatchWidth == width)
      assert(sim.ooo.retireWidth == width)
      assert(sim.ooo.storeCommitBufferEntries ==
        Integer.highestOneBit(
          width * sim.maxMemoryRequestsPerInstruction - 1) * 2)
      assert(sim.iex.issueWidth == width)
      assert(sim.ooo.robGroupsPerStid == robGroupCapacity)
      assert(sim.ooo.maxInstructionsPerRobGroup ==
        main.ooo.maxInstructionsPerRobGroup)
      assert(sim.ooo.maxUopsPerInstruction == 12)
      assert(sim.ooo.robBankCount == prefixCapacity)
      assert(sim.ooo.pcRecoveryScanGroupsPerCycle == math.min(4,
        robGroupCapacity))
      assert(sim.ooo.robGroupsPerStid %
        sim.ooo.pcRecoveryScanGroupsPerCycle == 0)
      assert(sim.ooo.gprPhysRegs <= main.ooo.gprPhysRegs)
      assert(sim.ooo.gprMapQDepthPerStid == math.max(16,
        Integer.highestOneBit(width * sim.maxDestinationOperands - 1) * 2))
      assert(sim.ooo.tPhysRegs == sim.ooo.gprMapQDepthPerStid)
      assert(sim.ooo.uPhysRegs == sim.ooo.gprMapQDepthPerStid)
      assert(sim.ooo.tuMapQDepthPerStid == sim.ooo.gprMapQDepthPerStid)
      assert(sim.iex.scalarIssueEntries == issueCapacity)
      assert(sim.iex.scalarIssueEntries / sim.iex.scalarIssueBanks >= 1)
      assert(sim.iex.stdPipes == sim.lsu.storePipes)
      assert(sim.lsu.storeQueueEntries ==
        Integer.highestOneBit(
          sim.iex.stdPipes * sim.maxMemoryRequestsPerInstruction - 1) * 2)
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

  test("bounded simulation ROB covers an open block plus one canonical template window") {
    widths.foreach { width =>
      val sim = SimulationParamProfiles.forWidth(width)

      assert(sim.ooo.robGroupsPerStid == 16)
      assert(sim.ooo.robCapacityPerStid >= 2 * sim.ctu.maxTemplateUops)
    }
  }

  test("bounded simulation rename retains block-closing forward progress") {
    widths.foreach { width =>
      val sim = SimulationParamProfiles.forWidth(width)

      assert(sim.ooo.gprMapQDepthPerStid >= 16)
      assert(sim.ooo.tuMapQDepthPerStid >= 16)
      assert(sim.ooo.gprPhysRegs >= sim.ooo.gprArchRegs + 16)
      assert(sim.ooo.tPhysRegs >= 16)
      assert(sim.ooo.uPhysRegs >= 16)
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
    assert(p.ctu.maxTemplateUops >= TemplateD3Constants.MaxRows)
    assert(p.ooo.robGroupsPerStid == 16)
    assert(p.ooo.maxInstructionsPerRobGroup == 4)
    assert(p.ooo.maxUopsPerInstruction == 12)
    assert(p.ooo.robBankCount == 8)
    assert(p.ooo.brobEntriesPerStid == 8)
    assert(p.ooo.pcBufferEntries == 8)
    assert(p.ooo.pcBankCount == 8)
    assert(p.ooo.pcRecoveryScanGroupsPerCycle == 4)
    assert(p.ooo.gprPhysRegs == 64)
    assert(p.ooo.gprMapQDepthPerStid == 16)
    assert(p.ooo.tPhysRegs == 16)
    assert(p.ooo.uPhysRegs == 16)
    assert(p.ooo.tuMapQDepthPerStid == 16)
    assert(p.iex.scalarIssueEntries == 8)
    assert((p.lsu.loadQueueEntries, p.lsu.storeQueueEntries) == (2, 4))
    assert(p.lsu.loadReturnQueueEntries == 2)
    assert(p.lsu.storeCommitQueueEntries == 2)
    assert(p.lsu.scbEntries == 4)
  }

  test("identity-derived domains never narrow in simulation profiles") {
    val main = ParamProfiles.W8
    val sim = SimulationParamProfiles.W8

    assert(sim.nativeBidWidth == main.nativeBidWidth)
    assert(sim.ooo.stidIdentityEntries == main.ooo.stidIdentityEntries)
    assert(sim.ooo.robIdentityGroupsPerStid ==
      main.ooo.robIdentityGroupsPerStid)
    assert(sim.ooo.robIdentityMembersPerGroup ==
      main.ooo.robIdentityMembersPerGroup)
    assert(sim.ooo.uopIdentityEntriesPerInstruction ==
      main.ooo.uopIdentityEntriesPerInstruction)
    assert(sim.ooo.brobIdentityEntriesPerStid ==
      main.ooo.brobIdentityEntriesPerStid)
    assert(sim.ooo.gprTagIdentityEntries == main.ooo.gprTagIdentityEntries)
    assert(sim.ooo.tTagIdentityEntries == main.ooo.tTagIdentityEntries)
    assert(sim.ooo.uTagIdentityEntries == main.ooo.uTagIdentityEntries)
    assert(sim.ooo.robGroupsPerStid < main.ooo.robGroupsPerStid)
    assert(sim.ooo.gprMapQDepthPerStid < main.ooo.gprMapQDepthPerStid)
    assert(sim.ooo.pcBufferEntries < main.ooo.pcBufferEntries)
  }

  test("W4 activation preserves public identity widths while bounding retained storage") {
    val main = ParamProfiles.W4
    val activation = OOOIEXLSUActivationParams.W4
    val mainMechanism = MechanismOooParams.fromCoreParams(main)
    val activationMechanism = MechanismOooParams.fromCoreParams(activation)

    assert(activation.ooo.stidIdentityEntries == main.ooo.stidIdentityEntries)
    assert(activation.ooo.robIdentityGroupsPerStid ==
      main.ooo.robIdentityGroupsPerStid)
    assert(activation.ooo.robIdentityMembersPerGroup ==
      main.ooo.robIdentityMembersPerGroup)
    assert(activation.ooo.uopIdentityEntriesPerInstruction ==
      main.ooo.uopIdentityEntriesPerInstruction)
    assert(activation.ooo.brobIdentityEntriesPerStid ==
      main.ooo.brobIdentityEntriesPerStid)
    assert(activation.ooo.gprTagIdentityEntries ==
      main.ooo.gprTagIdentityEntries)
    assert(activation.ooo.tTagIdentityEntries == main.ooo.tTagIdentityEntries)
    assert(activation.ooo.uTagIdentityEntries == main.ooo.uTagIdentityEntries)
    assert(activationMechanism.stidWidth == mainMechanism.stidWidth)
    assert(activationMechanism.ridSlotWidth == mainMechanism.ridSlotWidth)
    assert(activationMechanism.robMemberIndexWidth ==
      mainMechanism.robMemberIndexWidth)
    assert(activationMechanism.recipeUopCountWidth ==
      mainMechanism.recipeUopCountWidth)
    assert(activationMechanism.nativeBidWidth == mainMechanism.nativeBidWidth)
    assert(activationMechanism.pTagWidth == mainMechanism.pTagWidth)
    assert(activationMechanism.tTagWidth == mainMechanism.tTagWidth)
    assert(activationMechanism.uTagWidth == mainMechanism.uTagWidth)
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

    assert(activation.ooo.stidCount == 2)
    assert(activation.ooo.robGroupsPerStid == 8)
    assert(activation.ooo.maxInstructionsPerRobGroup == 1)
    assert(activation.ooo.maxUopsPerInstruction == 12)
    assert(activation.ooo.brobEntriesPerStid == 8)
    assert(activation.ooo.gprPhysRegs == 64)
    assert(activation.ooo.tPhysRegs == 8)
    assert(activation.ooo.uPhysRegs == 8)
    assert(activation.ooo.gprMapQDepthPerStid < main.ooo.gprMapQDepthPerStid)
    assert(activation.ooo.tuMapQDepthPerStid < main.ooo.tuMapQDepthPerStid)
    assert(activation.iex.scalarIssueEntries < main.iex.scalarIssueEntries)
    assert(activation.lsu.loadQueueEntries < main.lsu.loadQueueEntries)
    assert(activation.lsu.storeQueueEntries < main.lsu.storeQueueEntries)
  }

  test("W2 activation minimizes retained state without narrowing identities") {
    val main = ParamProfiles.W2
    val activation = OOOIEXLSUActivationParams.W2

    assert(activation.widths == main.widths)
    assert(activation.ooo.stidIdentityEntries == main.ooo.stidIdentityEntries)
    assert(activation.ooo.robIdentityGroupsPerStid ==
      main.ooo.robIdentityGroupsPerStid)
    assert(activation.ooo.robIdentityMembersPerGroup ==
      main.ooo.robIdentityMembersPerGroup)
    assert(activation.ooo.brobIdentityEntriesPerStid ==
      main.ooo.brobIdentityEntriesPerStid)
    assert(activation.ooo.gprTagIdentityEntries ==
      main.ooo.gprTagIdentityEntries)
    assert(activation.ooo.tTagIdentityEntries == main.ooo.tTagIdentityEntries)
    assert(activation.ooo.uTagIdentityEntries == main.ooo.uTagIdentityEntries)
    assert(activation.lsidWidth == main.lsidWidth)
    assert(activation.memoryTransactionIdWidth ==
      main.memoryTransactionIdWidth)
    assert(activation.memoryAttemptGenerationWidth ==
      main.memoryAttemptGenerationWidth)

    assert(activation.ooo.stidCount == 1)
    assert(activation.ooo.robGroupsPerStid == 8)
    assert(activation.ooo.maxInstructionsPerRobGroup == 1)
    assert(activation.ooo.robBankCount == 2)
    assert(activation.ooo.brobEntriesPerStid == 8)
    assert(activation.ooo.pcBufferEntries == 4)
    assert(activation.ooo.gprPhysRegs == 32)
    assert(activation.ooo.gprMapQDepthPerStid == 4)
    assert(activation.ooo.tPhysRegs == 4)
    assert(activation.ooo.uPhysRegs == 4)
    assert(activation.ooo.tuMapQDepthPerStid == 4)
    assert(activation.iex.scalarIssueEntries == 4)
    assert(activation.lsu.loadQueueEntries == 2)
    assert(activation.lsu.storeQueueEntries == 4)
  }

  test("physical STQ ROB and full LSID capacities remain independent") {
    val unequal = OOOIEXLSUActivationParams.W4.copy(
      ooo = OOOIEXLSUActivationParams.W4.ooo.copy(robGroupsPerStid = 8),
      lsu = OOOIEXLSUActivationParams.W4.lsu.copy(storeQueueEntries = 16),
      lsidWidth = 40)
    val mechanism = MechanismOooParams.fromCoreParams(unequal)

    ParamChecks.validate(unequal)
    assert(unequal.lsu.storeQueueEntries == 16)
    assert(unequal.ooo.robGroupsPerStid == 8)
    assert(unequal.ooo.robIdentityGroupsPerStid == 64)
    assert(unequal.lsidWidth == 40)
    assert(mechanism.ridSlotWidth == 6)
  }

  test("public and private ROB member widths ignore the larger recipe uop domain") {
    val core = ParamProfiles.W4.copy(
      ooo = ParamProfiles.W4.ooo.copy(
        robIdentityMembersPerGroup = 4,
        maxInstructionsPerRobGroup = 4,
        maxUopsPerInstruction = 32,
        uopIdentityEntriesPerInstruction = 32))
    val mechanism = MechanismOooParams.fromCoreParams(core)

    assert(core.ooo.robMemberIndexWidth == 2)
    assert(core.ooo.recipeUopIndexWidth == 5)
    assert(core.ooo.recipeUopCountWidth == 6)
    assert(mechanism.robMemberIndexWidth == 2)
    assert(mechanism.recipeUopIndexWidth == 5)
    assert(mechanism.recipeUopCountWidth == 6)
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
