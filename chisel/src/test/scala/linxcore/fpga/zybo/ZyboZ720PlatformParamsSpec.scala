package linxcore.fpga.zybo

import org.scalatest.funsuite.AnyFunSuite

class ZyboZ720PlatformParamsSpec extends AnyFunSuite {
  test("Linux-min profile preserves architecture widths while reducing storage") {
    val p = ZyboZ720PlatformParams.LinuxMinCore

    assert(p.robEntries == 32)
    assert(p.commitWidth == 2)
    assert(p.scalarBackend.gprPhysRegs == 64)
    assert(p.scalarBackend.gprMapQDepth == 64)
    assert(p.scalarBackend.gprWritePorts == 2)
    assert(p.scalarBackend.gprReadPorts == 3)
    assert(p.scalarBackend.scalarIssueBanks == 2)
    assert(p.scalarLsu.stqEntries == 8)
    assert(p.scalarLsu.commitQueueEntries == 8)
    assert(p.scalarLsu.commitIssueWidth == 1)
    assert(p.scalarLsu.scbEntries == 4)
    assert(p.scalarLsu.scbResponseBufferDepth == 2)
    assert(p.scalarLsu.liqEntries == 8)
    assert(p.scalarLsu.loadMissQueueEntries == 2)
    assert(p.scalarLsu.loadRefillQueueEntries == 2)
    assert(p.scalarLsu.resolveQueueEntries == 4)
    assert(p.scalarLsu.mdbSsitEntries == 4)
    assert(p.scalarLsu.mdbCommandQueueEntries == 4)
    assert(p.scalarLsu.mdbOutputQueueEntries == 4)
    assert(p.scalarLsu.mdbWaitPlanQueueEntries == 2)
    assert(p.scalarLsu.mdbRecoveryQueueEntries == 2)
    assert(p.scalarLsu.loadReturnQueueEntries == 1)
    assert(p.scalarLsu.loadReturnPipeCount == 1)
    assert(p.scalarLsu.l1dSets == 32)
    assert(p.scalarLsu.l1dWays == 2)
    assert(p.scalarLsu.addrWidth == 32)
    assert(p.scalarLsu.pcWidth == 64)
    assert(p.scalarLsu.dataWidth == 64)
    assert(p.scalarLsu.lineBytes == 64)
    assert(p.scalarLsu.mapQDepth == 16)
    assert(p.scalarLsu.stidCount == 1)
    assert(p.lsidWidth == 32)
  }

  test("Linux-min OOO profile satisfies all constructor constraints") {
    val p = ZyboZ720PlatformParams.LinuxMinOoo

    assert(p.stidCount == 1)
    assert(p.instructionDecodeWidth == 2)
    assert(p.decodedUopWidth == 4)
    assert(p.renameWidth == 2)
    assert(p.dispatchWidth == 2)
    assert(p.retireGroupWidth == 2)
    assert(p.robGroupsPerStid == 16)
    assert(p.robBankCount == 4)
    assert(p.robSubbankCount == 1)
    assert(p.robRecoveryScanGroupsPerCycle == 2)
    assert(p.robNonFlushScanGroupsPerCycle == 2)
    assert(p.brobEntriesPerStid == 32)
    assert(p.pPhysRegs == 64)
    assert(p.pTagBanks == 2)
    assert(p.pTagStagingDepthPerBank == 4)
    assert(p.pMapQDepthPerStid == 64)
    assert(p.tPhysRegs == 16)
    assert(p.uPhysRegs == 16)
    assert(p.tuMapQDepthPerStid == 16)
    assert(p.tuRetireSourceDepthPerStid == 64)
    assert(p.pcBufferEntries == 16)
    assert(p.pcBankCount == 2)
    assert(p.pcWritePorts == 2)
    assert(p.pcReadPorts == 2)
    assert(p.pcReadReplicaCount == 1)
    assert(p.iqClassCount == 8)
    assert(p.iqBankCount == 2)
    assert(p.iqEntriesPerBank == 8)
    assert(p.iqWritePortsPerBank == 1)
    assert(p.robCompletionBufferEntries == 4)
    assert(p.iexTerminalWidth == 1)
    assert(p.iexLoadCancelPorts == 1)
    assert(p.maxCommitStoreTokens == 16)
    assert(p.storeCommitBufferEntries == 16)
  }

  test("constructor summary reserves the closing marker after all benchmark body uops") {
    val summary = ZyboZ720PlatformParams.ConstructorSummary

    assert(summary.benchmarkBodyUops == 16)
    assert(summary.closingMarkerUops == 1)
    assert(summary.requiredBenchmarkRobEntries == 17)
    assert(summary.configuredBenchmarkRobEntries == 32)
    assert(summary.benchmarkRobHasCapacity)
  }

  test("constructor summary agrees with generated geometry and boot addresses") {
    val summary = ZyboZ720PlatformParams.ConstructorSummary

    assert(summary.architectureWidthsPreserved)
    assert(summary.manifestGeometryMatches)
    assert(summary.manifestBootAddressesMatch)
    assert(summary.allChecksPass)
  }

  test("smoke and Linux NOMMU profiles carry explicit boot intent") {
    val smoke = ZyboZ720PlatformParams.Smoke
    val linux = ZyboZ720PlatformParams.LinuxNommu

    assert(smoke.intent == ZyboZ720PlatformParams.ProfileIntent.Smoke)
    assert(smoke.boot.pc == BigInt("00010000", 16))
    assert(smoke.boot.sp == BigInt("0003ff00", 16))
    assert(smoke.boot.a0 == 0)
    assert(smoke.boot.a1 == 0)
    assert(smoke.boot.initramfs.isEmpty)

    assert(linux.intent == ZyboZ720PlatformParams.ProfileIntent.LinuxNommu)
    assert(linux.boot.pc == BigInt("00010000", 16))
    assert(linux.boot.sp == BigInt("0ffef000", 16))
    assert(linux.boot.a0 == 0)
    assert(linux.boot.a1 == BigInt("0f000000", 16))
    assert(linux.boot.initramfs.contains(BigInt("08000000", 16)))
  }

  test("ownership summary leaves every configured reduction unpromoted without hierarchy evidence") {
    val ownership = ZyboZ720PlatformParams.Smoke.ownership
    val expectedUnpromoted = Set(
      "core.commitWidth",
      "core.lsidWidth",
      "core.robEntries",
      "core.scalarBackend.gprMapQDepth",
      "core.scalarBackend.gprPhysRegs",
      "core.scalarBackend.gprReadPorts",
      "core.scalarBackend.gprWritePorts",
      "core.scalarBackend.scalarIssueBanks",
      "core.scalarLsu.addrWidth",
      "core.scalarLsu.commitIssueWidth",
      "core.scalarLsu.commitQueueEntries",
      "core.scalarLsu.dataWidth",
      "core.scalarLsu.l1dSets",
      "core.scalarLsu.l1dWays",
      "core.scalarLsu.lineBytes",
      "core.scalarLsu.liqEntries",
      "core.scalarLsu.loadMissQueueEntries",
      "core.scalarLsu.loadRefillQueueEntries",
      "core.scalarLsu.loadReturnPipeCount",
      "core.scalarLsu.loadReturnQueueEntries",
      "core.scalarLsu.mapQDepth",
      "core.scalarLsu.mdbCommandQueueEntries",
      "core.scalarLsu.mdbOutputQueueEntries",
      "core.scalarLsu.mdbRecoveryQueueEntries",
      "core.scalarLsu.mdbSsitEntries",
      "core.scalarLsu.mdbWaitPlanQueueEntries",
      "core.scalarLsu.pcWidth",
      "core.scalarLsu.resolveQueueEntries",
      "core.scalarLsu.scbEntries",
      "core.scalarLsu.scbResponseBufferDepth",
      "core.scalarLsu.stidCount",
      "core.scalarLsu.stqEntries",
      "ooo.brobEntriesPerStid",
      "ooo.decodedUopWidth",
      "ooo.dispatchWidth",
      "ooo.iexBypassPorts",
      "ooo.iexLoadCancelPorts",
      "ooo.iexPReadPorts",
      "ooo.iexPWritePorts",
      "ooo.iexTReadPorts",
      "ooo.iexTWritePorts",
      "ooo.iexTerminalWidth",
      "ooo.iexUReadPorts",
      "ooo.iexUWritePorts",
      "ooo.iexWakeupPorts",
      "ooo.instructionDecodeWidth",
      "ooo.iqBankCount",
      "ooo.iqClassCount",
      "ooo.iqEntriesPerBank",
      "ooo.iqWritePortsPerBank",
      "ooo.maxCommitStoreTokens",
      "ooo.pMapQDepthPerStid",
      "ooo.pPhysRegs",
      "ooo.pTagBanks",
      "ooo.pTagReturnWidth",
      "ooo.pTagStagingDepthPerBank",
      "ooo.pcBankCount",
      "ooo.pcBufferEntries",
      "ooo.pcReadPorts",
      "ooo.pcReadReplicaCount",
      "ooo.pcRecoveryScanGroupsPerCycle",
      "ooo.pcWritePorts",
      "ooo.renameWidth",
      "ooo.retireGroupWidth",
      "ooo.robBankCount",
      "ooo.robCompletionBufferEntries",
      "ooo.robGroupsPerStid",
      "ooo.robNonFlushScanGroupsPerCycle",
      "ooo.robRecoveryScanGroupsPerCycle",
      "ooo.robSubbankCount",
      "ooo.stidCount",
      "ooo.storeCommitBufferEntries",
      "ooo.tPhysRegs",
      "ooo.tuMapQDepthPerStid",
      "ooo.tuRetireSourceDepthPerStid",
      "ooo.uPhysRegs"
    )

    assert(ownership.parameterNames.toSet == expectedUnpromoted)
    assert(ownership.unpromotedParameters.toSet == expectedUnpromoted)
    assert(expectedUnpromoted.forall(parameter =>
      !ownership.claimsHardwareReduction(parameter)))
    assert(ownership.parameterNames == ownership.parameterNames.sorted)
    assert(ownership.parameterNames.distinct == ownership.parameterNames)
    assert(ZyboZ720PlatformParams.LinuxNommu.ownership.parameterNames ==
      ownership.parameterNames)
    assert(ZyboZ720PlatformParams.LinuxNommu.ownership.unpromotedParameters ==
      ownership.unpromotedParameters)
  }

  test("advisory consumer hints cannot promote a parameter") {
    val ownership = ZyboZ720PlatformParams.OwnershipSummary
    val entriesWithHints = ownership.entries.filter(_.consumers.nonEmpty)

    assert(entriesWithHints.nonEmpty)
    assert(entriesWithHints.forall(_.status ==
      ZyboZ720PlatformParams.OwnershipStatus.Unpromoted))
    assert(entriesWithHints.forall(entry =>
      !ownership.claimsHardwareReduction(entry.parameter)))
  }

  test("hierarchy evidence must match the exact profile intent Core and OOO values") {
    val smoke = ZyboZ720PlatformParams.Smoke.ownership
    val core = ZyboZ720PlatformParams.LinuxMinCore
    val ooo = ZyboZ720PlatformParams.LinuxMinOoo

    assert(smoke.matchesExactProfile(
      ZyboZ720PlatformParams.ProfileIntent.Smoke, core, ooo))
    assert(!smoke.matchesExactProfile(
      ZyboZ720PlatformParams.ProfileIntent.LinuxNommu, core, ooo))
    assert(!smoke.matchesExactProfile(
      ZyboZ720PlatformParams.ProfileIntent.Smoke,
      core.copy(robEntries = 64),
      ooo))
    assert(!smoke.matchesExactProfile(
      ZyboZ720PlatformParams.ProfileIntent.Smoke,
      core,
      ooo.copy(robGroupsPerStid = 32, tuRetireSourceDepthPerStid = 128)))
  }
}
