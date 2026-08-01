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

  test("ownership summary claims reductions only for parameters with current consumers") {
    val ownership = ZyboZ720PlatformParams.Smoke.ownership

    assert(ownership.claimsHardwareReduction("core.robEntries"))
    assert(ownership.consumersOf("core.robEntries").nonEmpty)
    assert(ownership.claimsHardwareReduction("core.scalarLsu.stqEntries"))

    assert(!ownership.claimsHardwareReduction("core.scalarBackend.gprWritePorts"))
    assert(ownership.consumersOf("core.scalarBackend.gprWritePorts").isEmpty)
    assert(!ownership.claimsHardwareReduction("core.scalarLsu.l1dSets"))
    assert(!ownership.claimsHardwareReduction("ooo.robGroupsPerStid"))
    assert(ownership.unpromotedParameters.contains("ooo.robGroupsPerStid"))
    assert(ownership.unpromotedParameters.contains("ooo.iqEntriesPerBank"))
    assert(ownership.unpromotedParameters.contains("ooo.storeCommitBufferEntries"))
    assert(ownership.parameterNames == ownership.parameterNames.sorted)
    assert(ownership.parameterNames.distinct == ownership.parameterNames)
    assert(ZyboZ720PlatformParams.LinuxNommu.ownership == ownership)
  }
}
