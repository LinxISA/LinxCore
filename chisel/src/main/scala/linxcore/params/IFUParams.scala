package linxcore.params

final case class IFUParams(
    fetchWidth: Int = 4,
    ctuTransferWidth: Int = 4,
    instructionBits: Int = 64,
    fetchBufferEntries: Int = 32,
    predictionCheckpointEntries: Int = 32,
    iSideStages: Int = 5,
    bSideStages: Int = 5)
