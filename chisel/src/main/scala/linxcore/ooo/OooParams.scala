package linxcore.ooo

import chisel3.util.log2Ceil

/** Independent production OOO sizing contract.
  *
  * IFU fetch geometry deliberately does not appear here. The IFU may continue
  * to fetch four entries while the OOO ingress gathers 2, 4, or 6 fixed-width
  * instruction containers for the selected STID.
  */
final case class OooParams(
    stidCount: Int = 4,
    instructionDecodeWidth: Int = 4,
    decodedUopWidth: Int = 8,
    renameWidth: Int = 8,
    dispatchWidth: Int = 8,
    retireGroupWidth: Int = 4,
    maxInstPerRobGroup: Int = 4,
    maxOrdinaryUopsPerGroup: Int = 12,
    robGroupsPerStid: Int = 64,
    brobEntriesPerStid: Int = 256,
    pArchRegs: Int = 24,
    pPhysRegs: Int = 128,
    pTagBanks: Int = 2,
    pTagMinimumSpeculativePerStid: Int = 8,
    pMapQDepthPerStid: Int = 256,
    pcBufferEntries: Int = 64,
    pcOffsetWidth: Int = 7,
    pcWritePorts: Int = 3,
    pcReadPorts: Int = 6,
    iqClassCount: Int = 8,
    iqBankCount: Int = 8,
    iqEntriesPerBank: Int = 32,
    iqWritePortsPerBank: Int = 3,
    maxArchitecturalParentRefs: Int = 3,
    peIdWidth: Int = 8,
    pcWidth: Int = 64,
    instructionWidth: Int = 64,
    instructionLengthWidth: Int = 4,
    opcodeWidth: Int = 12,
    archRegWidth: Int = 6,
    localTagWidth: Int = 8,
    lsidWidth: Int = 32,
    transactionIdWidth: Int = 64,
    instructionIdWidth: Int = 64,
    templateGroupIdWidth: Int = 64,
    predictionTagWidth: Int = 64,
    checkpointWidth: Int = 6,
    epochWidth: Int = 8,
    ridGenerationWidth: Int = 8,
    brobGenerationWidth: Int = 8,
    residentGenerationWidth: Int = 8,
    reservationEpochWidth: Int = 8) {
  private def isPowerOfTwo(value: Int): Boolean =
    value > 0 && (value & (value - 1)) == 0

  require(isPowerOfTwo(stidCount), "stidCount must be a positive power of two")
  require(Set(2, 4, 6).contains(instructionDecodeWidth),
    "instructionDecodeWidth must be one of 2, 4, or 6")
  require(decodedUopWidth >= instructionDecodeWidth,
    "decodedUopWidth must contain the instruction decode prefix")
  require(renameWidth > 0 && dispatchWidth > 0 && retireGroupWidth > 0,
    "rename, dispatch, and retire widths must be positive")
  require(maxInstPerRobGroup > 0,
    "ROB group instruction cap must be positive")
  require(maxOrdinaryUopsPerGroup >= maxInstPerRobGroup,
    "ROB group member cap must cover its architectural parents")
  require(isPowerOfTwo(robGroupsPerStid),
    "ROB groups per STID must be a power of two")
  require(isPowerOfTwo(brobEntriesPerStid),
    "BROB entries per STID must be a power of two")
  require(pArchRegs == 24, "Linx scalar P namespace contains 24 registers")
  require(isPowerOfTwo(pPhysRegs) && pPhysRegs > stidCount * pArchRegs,
    "PTag namespace must cover committed mappings for every STID plus speculation")
  require(pTagMinimumSpeculativePerStid > 0,
    "every STID must receive a nonzero speculative PTag guarantee")
  require(pPhysRegs >= stidCount * (pArchRegs + pTagMinimumSpeculativePerStid),
    "PTag namespace must cover committed mappings and per-STID minimum guarantees")
  require(isPowerOfTwo(pTagBanks) && pPhysRegs % pTagBanks == 0,
    "PTag banks must evenly partition the physical namespace")
  require(isPowerOfTwo(pMapQDepthPerStid),
    "P MapQ depth per STID must be a power of two")
  require(isPowerOfTwo(pcBufferEntries), "PC buffer entries must be a power of two")
  require(pcOffsetWidth >= 7, "PC byte offset must cover variable 2/4/6/8-byte rows")
  require(pcWritePorts > 0 && pcReadPorts > 0, "PC buffer port counts must be positive")
  require(iqClassCount > 0 && isPowerOfTwo(iqBankCount),
    "IQ class count must be positive and bank count must be a power of two")
  require(isPowerOfTwo(iqEntriesPerBank),
    "IQ entries per bank must be a power of two")
  require(iqWritePortsPerBank > 0, "every IQ bank needs a write port")
  require(maxArchitecturalParentRefs >= 3,
    "BSTART + carrier + BSTOP fusion needs three parent references")
  require(pcWidth == 64 && instructionWidth == 64,
    "production OOO consumes 64-bit PC and fixed 64-bit instruction containers")
  require(instructionLengthWidth >= 4, "instruction length must encode 2/4/6/8 bytes")
  require(opcodeWidth == 12, "generated Linx opcode IDs are 12 bits")
  require(archRegWidth == 6, "architectural register IDs use the reg6 namespace")
  require(lsidWidth >= 32, "full LSID must preserve at least 32 bits")

  def countWidth(maximum: Int): Int = math.max(1, log2Ceil(maximum + 1))
  def stidWidth: Int = math.max(1, log2Ceil(stidCount))
  def ridSlotWidth: Int = log2Ceil(robGroupsPerStid)
  def nativeBidWidth: Int = log2Ceil(brobEntriesPerStid)
  def pTagWidth: Int = log2Ceil(pPhysRegs)
  def pTagBankWidth: Int = math.max(1, log2Ceil(pTagBanks))
  def pMapQIndexWidth: Int = log2Ceil(pMapQDepthPerStid)
  def pcBufferIndexWidth: Int = log2Ceil(pcBufferEntries)
  def iqBankWidth: Int = log2Ceil(iqBankCount)
  def iqEntryWidth: Int = log2Ceil(iqEntriesPerBank)
  def iqWritePortWidth: Int = math.max(1, log2Ceil(iqWritePortsPerBank))
  def instructionCountWidth: Int = countWidth(instructionDecodeWidth)
  def decodedUopCountWidth: Int = countWidth(decodedUopWidth)
  def renameCountWidth: Int = countWidth(renameWidth)
  def dispatchCountWidth: Int = countWidth(dispatchWidth)
  def robGroupCountWidth: Int = countWidth(instructionDecodeWidth)
  def robMemberIndexWidth: Int = math.max(1, log2Ceil(maxOrdinaryUopsPerGroup))
  def robMemberCountWidth: Int = countWidth(maxOrdinaryUopsPerGroup)
  def architecturalParentCountWidth: Int = countWidth(maxArchitecturalParentRefs)
}
