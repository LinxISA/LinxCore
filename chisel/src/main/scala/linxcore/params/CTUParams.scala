package linxcore.params

final case class CTUParams(
    inputWidth: Int = 4,
    outputWidth: Int = 4,
    instructionBits: Int = 64,
    instructionBufferEntries: Int = 32,
    maxTemplateUops: Int = 32)
