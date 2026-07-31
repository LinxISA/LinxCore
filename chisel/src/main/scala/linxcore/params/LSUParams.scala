package linxcore.params

final case class LSUParams(
    loadPipes: Int = 2,
    storePipes: Int = 2,
    loadQueueEntries: Int = 16,
    storeQueueEntries: Int = 16,
    loadReturnQueueEntries: Int = 2,
    storeCommitQueueEntries: Int = 16,
    scbEntries: Int = 16,
    lineBytes: Int = 64)
