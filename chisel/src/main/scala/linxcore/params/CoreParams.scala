package linxcore.params

import chisel3.util.log2Ceil

final case class CoreParams(
    widths: WidthParams = WidthParams(),
    ifu: IFUParams = IFUParams(),
    ctu: CTUParams = CTUParams(),
    ooo: OOOParams = OOOParams(),
    iex: IEXParams = IEXParams(),
    lsu: LSUParams = LSUParams(),
    dtu: DTUParams = DTUParams(),
    pcWidth: Int = 64,
    instructionWidth: Int = 64,
    opcodeWidth: Int = 12,
    archRegWidth: Int = 6,
    lsidWidth: Int = 32,
    maxMemoryRequestsPerInstruction: Int = 2,
    peIdWidth: Int = 8,
    instructionIdWidth: Int = 64,
    transactionIdWidth: Int = 64,
    epochWidth: Int = 16,
    brobGenerationWidth: Int = 16,
    ridGenerationWidth: Int = 16,
    residentGenerationWidth: Int = 16,
    memoryTransactionIdWidth: Int = 64,
    memoryTransactionGenerationWidth: Int = 16,
    memoryAttemptGenerationWidth: Int = 16,
    trapCauseWidth: Int = 32,
    physicalAddressWidth: Int = 64,
    dataWidth: Int = 64,
    maxSourceOperands: Int = 4,
    maxDestinationOperands: Int = 2) {
  ParamChecks.validate(this)

  /** Native BID is the per-STID BROB slot; generation remains a separate field. */
  def nativeBidWidth: Int = ooo.nativeBidWidth
}
