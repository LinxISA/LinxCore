package linxcore.params

final case class IFUParams(
    fetchWidth: Int = 4,
    ctuTransferWidth: Int = 4,
    instructionBits: Int = 64,
    fetchBufferEntries: Int = 32,
    predictionCheckpointEntries: Int = 32,
    iSideStages: Int = 5,
    bSideStages: Int = 5,
    lineBytes: Int = 64,
    pageBytes: Int = 4096,
    itlbEntries: Int = 16,
    l1iSets: Int = 64,
    missEntries: Int = 8,
    joinEntries: Int = 8,
    maxGroupsPerTransaction: Int = 8,
    resetVector: BigInt = 0)
