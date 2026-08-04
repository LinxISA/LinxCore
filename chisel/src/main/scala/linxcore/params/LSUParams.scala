package linxcore.params

final case class MemoryAccessAttributes(
    readable: Boolean = true,
    writable: Boolean = true,
    cacheable: Boolean = true,
    device: Boolean = false)

/** Canonical physical protection and memory-attribute region. Earlier
  * entries have priority, and the same classifier is applied after both
  * identity and translated mappings.
  */
final case class PhysicalMemoryRegion(
    base: BigInt,
    mask: BigInt,
    attributes: MemoryAccessAttributes)

final case class LSUParams(
    loadPipes: Int = 2,
    storePipes: Int = 2,
    loadQueueEntries: Int = 16,
    storeQueueEntries: Int = 16,
    loadReturnQueueEntries: Int = 2,
    storeCommitQueueEntries: Int = 16,
    scbEntries: Int = 16,
    loadMissQueueEntries: Int = 8,
    loadRefillQueueEntries: Int = 4,
    resolveQueueEntries: Int = 8,
    mdbSsitEntries: Int = 16,
    mdbCommandQueueEntries: Int = 16,
    mdbOutputQueueEntries: Int = 16,
    mdbWaitPlanQueueEntries: Int = 8,
    mdbRecoveryQueueEntries: Int = 8,
    mdbFailedWaitTimeoutCycles: Int = 300,
    l1dSets: Int = 64,
    l1dWays: Int = 4,
    scbResponseBufferDepth: Int = 4,
    dTranslationEntries: Int = 4,
    dTranslationPageBytes: Int = 4096,
    dTranslationCounterBits: Int = 7,
    lowerMemoryTransactionsPerLane: Int = 16,
    defaultMemoryAttributes: MemoryAccessAttributes =
      MemoryAccessAttributes(),
    physicalMemoryRegions: Seq[PhysicalMemoryRegion] = Seq.empty,
    lineBytes: Int = 64)
