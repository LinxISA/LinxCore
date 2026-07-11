package linxcore.common

final case class CoreParams(
  robEntries: Int = 128,
  commitWidth: Int = 4,
  scalarLsu: ScalarLsuParams = ScalarLsuParams()
) {
  require(robEntries > 1, "robEntries must be greater than one")
  require((robEntries & (robEntries - 1)) == 0, "robEntries must be a power of two")
  require(commitWidth > 0, "commitWidth must be positive")
}

final case class ScalarLsuParams(
  stqEntries: Int = 16,
  commitQueueEntries: Int = 16,
  commitIssueWidth: Int = 2,
  scbEntries: Int = 16,
  scbResponseBufferDepth: Int = 4,
  liqEntries: Int = 16,
  resolveQueueEntries: Int = 8,
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
  mapQDepth: Int = 32
) {
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
  require(resolveQueueEntries > 1 && (resolveQueueEntries & (resolveQueueEntries - 1)) == 0,
    "resolveQueueEntries must be a power of two greater than one")
  require(addrWidth >= 7, "addrWidth must cover scalar cache-line split detection")
  require(pcWidth > 0, "pcWidth must be positive")
  require(dataWidth > 0 && dataWidth % 8 == 0, "dataWidth must contain whole bytes")
  require(peIdWidth > 0 && stidWidth > 0 && tidWidth > 0, "identity widths must be positive")
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
