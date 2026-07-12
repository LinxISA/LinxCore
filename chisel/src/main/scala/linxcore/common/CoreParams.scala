package linxcore.common

final case class CoreParams(
  robEntries: Int = 128,
  commitWidth: Int = 4,
  scalarLsu: ScalarLsuParams = ScalarLsuParams(),
  scalarBackend: ScalarBackendParams = ScalarBackendParams(),
  lsidWidth: Int = 32
) {
  require(robEntries > 1, "robEntries must be greater than one")
  require((robEntries & (robEntries - 1)) == 0, "robEntries must be a power of two")
  require(commitWidth > 0, "commitWidth must be positive")
  require(lsidWidth >= 32, "lsidWidth must preserve the Linx 32-bit memory-order contract")
}

final case class ScalarBackendParams(
  gprArchRegs: Int = 24,
  gprPhysRegs: Int = 128,
  gprWritePorts: Int = 2,
  scalarIssueBanks: Int = 2,
  gprReadPorts: Int = 3
) {
  require(gprArchRegs == 24,
    "LinxCoreModel scalar GPR namespace has 24 architectural registers")
  require(gprPhysRegs > gprArchRegs,
    "scalar physical GPR capacity must exceed the architectural namespace")
  require((gprPhysRegs & (gprPhysRegs - 1)) == 0,
    "scalar physical GPR capacity must be a power of two")
  require(gprWritePorts > 0, "scalar GPR write-port count must be positive")
  require(scalarIssueBanks > 1 && (scalarIssueBanks & (scalarIssueBanks - 1)) == 0,
    "scalar issue-bank count must be a power of two greater than one")
  require(gprReadPorts >= 3,
    "scalar GPR read-port count must cover one atomic three-source issue grant")
}

final case class ScalarLsuParams(
  stqEntries: Int = 16,
  commitQueueEntries: Int = 16,
  commitIssueWidth: Int = 2,
  scbEntries: Int = 16,
  scbResponseBufferDepth: Int = 4,
  liqEntries: Int = 16,
  loadMissQueueEntries: Int = 8,
  resolveQueueEntries: Int = 8,
  mdbSsitEntries: Int = 16,
  mdbCommandQueueEntries: Int = 16,
  mdbOutputQueueEntries: Int = 16,
  mdbWaitPlanQueueEntries: Int = 8,
  mdbRecoveryQueueEntries: Int = 8,
  loadReturnQueueEntries: Int = 2,
  loadReturnPipeCount: Int = 1,
  mdbFailedWaitTimeoutCycles: Int = 300,
  mdbReleaseWeight: Int = 25,
  mdbMaxWeight: Int = 3,
  mdbIncStep: Int = 1,
  mdbConfWidth: Int = 2,
  addrWidth: Int = 64,
  pcWidth: Int = 64,
  dataWidth: Int = 64,
  peIdWidth: Int = 8,
  stidWidth: Int = 8,
  tidWidth: Int = 8,
  sizeWidth: Int = 4,
  loadSizeWidth: Int = 7,
  archRegWidth: Int = 6,
  physRegWidth: Int = 7,
  simtLaneWidth: Int = 8,
  lineBytes: Int = 64,
  mapQDepth: Int = 32,
  stidCount: Int = 1
) {
  require(stidCount > 0, "stidCount must be positive")
  require(stqEntries > 1 && (stqEntries & (stqEntries - 1)) == 0,
    "stqEntries must be a power of two greater than one")
  require(commitQueueEntries > 1 && (commitQueueEntries & (commitQueueEntries - 1)) == 0,
    "commitQueueEntries must be a power of two greater than one")
  require(commitIssueWidth > 0 && commitIssueWidth <= commitQueueEntries,
    "commitIssueWidth must fit the commit queue")
  require(scbEntries >= commitIssueWidth * 2,
    "scbEntries must cover the worst-case split-store request batch")
  require(scbResponseBufferDepth > 0, "scbResponseBufferDepth must be positive")
  require(liqEntries > 1 && (liqEntries & (liqEntries - 1)) == 0,
    "liqEntries must be a power of two greater than one")
  require(loadMissQueueEntries > 1 && (loadMissQueueEntries & (loadMissQueueEntries - 1)) == 0,
    "loadMissQueueEntries must be a power of two greater than one")
  require(resolveQueueEntries > 1 && (resolveQueueEntries & (resolveQueueEntries - 1)) == 0,
    "resolveQueueEntries must be a power of two greater than one")
  require(mdbSsitEntries > 1 && (mdbSsitEntries & (mdbSsitEntries - 1)) == 0,
    "mdbSsitEntries must be a power of two greater than one")
  require(mdbCommandQueueEntries > 1 && (mdbCommandQueueEntries & (mdbCommandQueueEntries - 1)) == 0,
    "mdbCommandQueueEntries must be a power of two greater than one")
  require(mdbOutputQueueEntries > 1 && (mdbOutputQueueEntries & (mdbOutputQueueEntries - 1)) == 0,
    "mdbOutputQueueEntries must be a power of two greater than one")
  require(mdbWaitPlanQueueEntries > 1 && (mdbWaitPlanQueueEntries & (mdbWaitPlanQueueEntries - 1)) == 0,
    "mdbWaitPlanQueueEntries must be a power of two greater than one")
  require(mdbRecoveryQueueEntries > 1 && (mdbRecoveryQueueEntries & (mdbRecoveryQueueEntries - 1)) == 0,
    "mdbRecoveryQueueEntries must be a power of two greater than one")
  require(loadReturnQueueEntries > 0, "loadReturnQueueEntries must be positive")
  require(loadReturnPipeCount > 0, "loadReturnPipeCount must be positive")
  require(mdbFailedWaitTimeoutCycles > 0,
    "mdbFailedWaitTimeoutCycles must be positive")
  require(mdbReleaseWeight >= 0 && mdbReleaseWeight <= 100,
    "mdbReleaseWeight must be a percentage")
  require(mdbMaxWeight > 0, "mdbMaxWeight must be positive")
  require(mdbIncStep > 0, "mdbIncStep must be positive")
  require(mdbConfWidth > 0, "mdbConfWidth must be positive")
  require(addrWidth >= 7, "addrWidth must cover scalar cache-line split detection")
  require(pcWidth > 0, "pcWidth must be positive")
  require(dataWidth > 0 && dataWidth % 8 == 0, "dataWidth must contain whole bytes")
  require(peIdWidth > 0 && stidWidth > 0 && tidWidth > 0, "identity widths must be positive")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "stidCount must fit stidWidth")
  require(sizeWidth >= 4, "sizeWidth must encode scalar access sizes")
  require(loadSizeWidth >= 7, "loadSizeWidth must encode a full scalar cache line")
  require(archRegWidth >= 6, "archRegWidth must cover the Linx reg6 namespace")
  require(physRegWidth >= archRegWidth, "physRegWidth must cover architectural register tags")
  require(simtLaneWidth > 0, "simtLaneWidth must be positive")
  require(lineBytes > 1 && (lineBytes & (lineBytes - 1)) == 0,
    "lineBytes must be a power of two greater than one")
  require(mapQDepth > 1 && (mapQDepth & (mapQDepth - 1)) == 0,
    "mapQDepth must be a power of two greater than one")
}
