package linxcore.params

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
    lineBytes: Int = 64)
