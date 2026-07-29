package linxcore.ooo

import chisel3.util.log2Ceil

/** Independent OOO sizing contract.
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
    maxRecipeUops: Int = 32,
    robGroupsPerStid: Int = 64,
    robBankCount: Int = 8,
    robSubbankCount: Int = 2,
    robRecoveryScanGroupsPerCycle: Int = 8,
    robNonFlushScanGroupsPerCycle: Int = 8,
    brobEntriesPerStid: Int = 256,
    pArchRegs: Int = 24,
    pPhysRegs: Int = 128,
    pTagBanks: Int = 2,
    pTagStagingDepthPerBank: Int = 8,
    pTagReturnWidth: Int = 8,
    pTagMinimumSpeculativePerStid: Int = 8,
    pMapQDepthPerStid: Int = 256,
    pMapQSubbankCount: Int = 2,
    tPhysRegs: Int = 32,
    uPhysRegs: Int = 32,
    tuMapQDepthPerStid: Int = 32,
    tuRetireSourceDepthPerStid: Int = 512,
    tuRelationDepthPerStid: Int = 8,
    tuRelationReleaseThreshold: Int = 4,
    localSeqGenerationWidth: Int = 8,
    pcBufferEntries: Int = 64,
    pcBankCount: Int = 4,
    pcRecoveryScanGroupsPerCycle: Int = 4,
    pcOffsetWidth: Int = 7,
    pcWritePorts: Int = 3,
    pcReadPorts: Int = 6,
    pcReadReplicaCount: Int = 3,
    iqClassCount: Int = 8,
    iqBankCount: Int = 8,
    iqEntriesPerBank: Int = 32,
    iqWritePortsPerBank: Int = 3,
    iqFreeSelectLeafEntries: Int = 4,
    iexRecoveryScanEntriesPerBankPerCycle: Int = 1,
    iexIssueDomainCount: Int = 1,
    iexReleaseWidth: Int = 1,
    iexPReadPorts: Int = 6,
    iexTReadPorts: Int = 4,
    iexUReadPorts: Int = 4,
    iexPWritePorts: Int = 4,
    iexTWritePorts: Int = 4,
    iexUWritePorts: Int = 4,
    iexWakeupPorts: Int = 8,
    iexBypassPorts: Int = 12,
    iexLoadCancelPorts: Int = 4,
    iexLoadTrackEntries: Int = 16,
    maxArchitecturalParentRefs: Int = 3,
    maxSourceOperands: Int = 4,
    maxDestinationOperands: Int = 2,
    maxDispatchWritesPerInstruction: Int = 2,
    maxMemoryRequestsPerInstruction: Int = 2,
    peIdWidth: Int = 8,
    pcWidth: Int = 64,
    instructionWidth: Int = 64,
    instructionLengthWidth: Int = 4,
    opcodeWidth: Int = 12,
    archRegWidth: Int = 6,
    trapCauseWidth: Int = 32,
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
    pTagGenerationWidth: Int = 8,
    loadGenerationWidth: Int = 8,
    executeSlotGenerationWidth: Int = 8,
    reservationEpochWidth: Int = 8) {
  private def isPowerOfTwo(value: Int): Boolean =
    value > 0 && (value & (value - 1)) == 0
  private def allocatableTagsInBank(bank: Int): Int =
    (stidCount * pArchRegs until pPhysRegs).count(_ % pTagBanks == bank)

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
  require(maxRecipeUops >= maxOrdinaryUopsPerGroup,
    "recipe count must cover ordinary and multi-group CTU expansion")
  require(isPowerOfTwo(robGroupsPerStid),
    "ROB groups per STID must be a power of two")
  require(isPowerOfTwo(robBankCount),
    "ROB bank count must be a positive power of two")
  require(isPowerOfTwo(robSubbankCount),
    "ROB subbank count must be a positive power of two")
  require(isPowerOfTwo(robRecoveryScanGroupsPerCycle) &&
    robRecoveryScanGroupsPerCycle <= robBankCount,
    "ROB recovery scan width must be a power of two within the physical bank count")
  require(isPowerOfTwo(robNonFlushScanGroupsPerCycle) &&
    robNonFlushScanGroupsPerCycle <= robBankCount,
    "ROB non-flush scan width must be a power of two within the physical bank count")
  require(instructionDecodeWidth <= robBankCountEffective &&
    retireGroupWidth <= robBankCountEffective,
    "ROB banks must cover one publication and retirement prefix without a bank collision")
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
  require((0 until pTagBanks).forall { bank =>
    allocatableTagsInBank(bank) >=
      (decodedUopWidth * maxDestinationOperands + pTagBanks - 1) / pTagBanks
  }, "every PTag bank must cover its worst-case balanced D3 claim")
  require(pTagStagingDepthPerBank >=
    (decodedUopWidth * maxDestinationOperands + pTagBanks - 1) / pTagBanks,
    "each PTag staging bank must cover the worst-case balanced D3 destination demand")
  require(pTagReturnWidth > 0,
    "PTag return width must be positive")
  require(pTagGenerationWidth > 0,
    "PTag allocation generation width must be positive")
  require(isPowerOfTwo(pMapQDepthPerStid),
    "P MapQ depth per STID must be a power of two")
  require(isPowerOfTwo(pMapQSubbankCount) &&
    pMapQSubbankCount <= pMapQDepthPerStid,
    "P MapQ subbank count must be a power of two within the per-STID depth")
  require(pMapQDepthPerStid % pMapQSubbankCount == 0,
    "P MapQ subbanks must evenly partition the per-STID depth")
  require(isPowerOfTwo(tPhysRegs) && isPowerOfTwo(uPhysRegs) &&
    tPhysRegs >= 2 && uPhysRegs >= 2,
    "T and U physical namespaces must be powers of two with at least two entries")
  require(isPowerOfTwo(tuMapQDepthPerStid),
    "T/U MapQ depth per STID must be a power of two")
  require(tuMapQDepthPerStid >= decodedUopWidth * maxDestinationOperands,
    "T/U MapQ must retain one worst-case D3 destination bundle")
  require(isPowerOfTwo(tuRetireSourceDepthPerStid) &&
    tuRetireSourceDepthPerStid >= robGroupsPerStid * decodedUopWidth,
    "T/U retire source storage must cover every live logical ROB row")
  require(isPowerOfTwo(tuRelationDepthPerStid) &&
    tuRelationDepthPerStid > 1,
    "T/U relation CMAP depth must be a power of two greater than one")
  require(tuRelationReleaseThreshold >= 0 &&
    tuRelationReleaseThreshold < tuRelationDepthPerStid,
    "T/U relation pressure threshold must fit the relation CMAP")
  require(localTagWidth >= log2Ceil(math.max(tPhysRegs, uPhysRegs)),
    "localTagWidth must address both T and U physical namespaces")
  require(localSeqGenerationWidth > 0,
    "local sequence generation width must be positive")
  require(isPowerOfTwo(pcBufferEntries), "PC buffer entries must be a power of two")
  require(pcBufferEntries % stidCount == 0 &&
    isPowerOfTwo(pcBufferEntries / stidCount) && pcBufferEntries / stidCount >= 2,
    "fixed PC-buffer partitions must divide evenly into power-of-two STID slices")
  require(isPowerOfTwo(pcBankCount) &&
    pcBankCount <= pcBufferEntries / stidCount &&
    (pcBufferEntries / stidCount) % pcBankCount == 0,
    "PC-buffer banks must evenly partition every STID slice")
  require(isPowerOfTwo(pcRecoveryScanGroupsPerCycle) &&
    pcRecoveryScanGroupsPerCycle <= robGroupsPerStid &&
    robGroupsPerStid % pcRecoveryScanGroupsPerCycle == 0,
    "PC recovery scan width must be a power-of-two divisor of the ROB window")
  require(pcOffsetWidth >= 7, "PC byte offset must cover variable 2/4/6/8-byte rows")
  require(pcWritePorts > 0 && pcReadPorts > 0, "PC buffer port counts must be positive")
  require(pcReadReplicaCount > 0 &&
    pcReadReplicaCount <= pcReadPorts &&
    pcReadPorts % pcReadReplicaCount == 0 &&
    pcReadPorts / pcReadReplicaCount <= 2,
    "PC-buffer read replicas must evenly map every logical port onto at most two reads per replica")
  require(pcWritePorts <= pcBankCount && retireGroupWidth <= pcBankCount,
    "PC-buffer banks must cover one allocation and retirement prefix without a bank collision")
  require(iqClassCount == 8 && isPowerOfTwo(iqBankCount),
    "IQ class vector has eight classes and bank count must be a power of two")
  require(isPowerOfTwo(iqEntriesPerBank),
    "IQ entries per bank must be a power of two")
  require(iqWritePortsPerBank > 0, "every IQ bank needs a write port")
  require(isPowerOfTwo(iqFreeSelectLeafEntries) &&
    iqFreeSelectLeafEntries <= 8 &&
    iqEntriesPerBank % math.min(iqFreeSelectLeafEntries,
      iqEntriesPerBank) == 0 &&
    iqEntriesPerBank / math.min(iqFreeSelectLeafEntries,
      iqEntriesPerBank) <= 8,
    "IQ free selection must fit a bounded eight-group by eight-entry hierarchy")
  require(isPowerOfTwo(iexRecoveryScanEntriesPerBankPerCycle) &&
    iexRecoveryScanEntriesPerBankPerCycle <= iqEntriesPerBank &&
    iqEntriesPerBank % iexRecoveryScanEntriesPerBankPerCycle == 0,
    "IEX recovery scan width must be a power-of-two divisor of each IQ bank")
  require(iexIssueDomainCount > 0 &&
    iexIssueDomainCount <= math.min(8, iqClassCount * iqBankCount),
    "IEX issue-domain count must fit the bounded physical topology")
  require(iexReleaseWidth > 0 && iexReleaseWidth <= iexIssueDomainCount,
    "IEX release width must fit the physical issue-domain count")
  require(iexPReadPorts > 0 && iexTReadPorts > 0 && iexUReadPorts > 0,
    "IEX P/T/U read-port counts must be positive")
  require(iexPWritePorts > 0 && iexTWritePorts > 0 && iexUWritePorts > 0,
    "IEX P/T/U write-port counts must be positive")
  require(iexWakeupPorts > 0,
    "the IEX boundary needs at least one wakeup port")
  require(iexBypassPorts > 0,
    "the IEX boundary needs at least one bypass candidate")
  require(iexLoadCancelPorts > 0,
    "the IEX boundary needs at least one load-cancel port")
  require(isPowerOfTwo(iexLoadTrackEntries),
    "load tracking entries must be a positive power of two")
  require(loadGenerationWidth > 0,
    "speculative load attempts need a positive generation width")
  require(executeSlotGenerationWidth > 0,
    "retained execute slots need a positive generation width")
  require(maxArchitecturalParentRefs >= 3,
    "BSTART + carrier + BSTOP fusion needs three parent references")
  require(maxSourceOperands >= 4 && maxDestinationOperands >= 2,
    "pair stores need four sources and pair loads need two destinations")
  require(maxDispatchWritesPerInstruction >= 2 && maxMemoryRequestsPerInstruction >= 2,
    "pair/store recipes need two dispatch writes and two memory requests")
  require(pcWidth == 64 && instructionWidth == 64,
    "OOO consumes 64-bit PC and fixed 64-bit instruction containers")
  require(instructionLengthWidth >= 4, "instruction length must encode 2/4/6/8 bytes")
  require(opcodeWidth == 12, "generated Linx opcode IDs are 12 bits")
  require(archRegWidth == 6, "architectural register IDs use the reg6 namespace")
  require(trapCauseWidth >= 32, "precise OOO trap causes must preserve 32 bits")
  require(lsidWidth >= 32, "full LSID must preserve at least 32 bits")

  def countWidth(maximum: Int): Int = math.max(1, log2Ceil(maximum + 1))
  def stidWidth: Int = math.max(1, log2Ceil(stidCount))
  def ridSlotWidth: Int = log2Ceil(robGroupsPerStid)
  def robBankCountEffective: Int = math.min(robBankCount, robGroupsPerStid)
  def robSubbankCountEffective: Int = math.min(
    robSubbankCount,
    robGroupsPerStid / robBankCountEffective)
  def robRowsPerSubbank: Int =
    robGroupsPerStid / (robBankCountEffective * robSubbankCountEffective)
  def robBankSelectionBits: Int = log2Ceil(robBankCountEffective)
  def robSubbankSelectionBits: Int = log2Ceil(robSubbankCountEffective)
  def robBankIndexWidth: Int = math.max(1, robBankSelectionBits)
  def robSubbankIndexWidth: Int = math.max(1, robSubbankSelectionBits)
  def robSubbankRowIndexWidth: Int = math.max(1, log2Ceil(robRowsPerSubbank))
  def robRecoveryScanGroupsPerCycleEffective: Int =
    math.min(robRecoveryScanGroupsPerCycle, robBankCountEffective)
  def robRecoveryScanCycles: Int =
    robGroupsPerStid / robRecoveryScanGroupsPerCycleEffective
  def robRecoveryScanCursorWidth: Int =
    math.max(1, log2Ceil(robRecoveryScanCycles))
  def robNonFlushScanGroupsPerCycleEffective: Int =
    math.min(robNonFlushScanGroupsPerCycle, robBankCountEffective)
  def robNonFlushScanCycles: Int =
    robGroupsPerStid / robNonFlushScanGroupsPerCycleEffective
  def nativeBidWidth: Int = log2Ceil(brobEntriesPerStid)
  def brobCountWidth: Int = countWidth(brobEntriesPerStid)
  def brobLiveGroupCountWidth: Int = countWidth(robGroupsPerStid)
  def pTagWidth: Int = log2Ceil(pPhysRegs)
  def pTagBankWidth: Int = math.max(1, log2Ceil(pTagBanks))
  def pTagAllocationWidth: Int = decodedUopWidth * maxDestinationOperands
  def pTagReturnCountWidth: Int = countWidth(pTagReturnWidth)
  def pMapQIndexWidth: Int = log2Ceil(pMapQDepthPerStid)
  def pMapQCountWidth: Int = countWidth(pMapQDepthPerStid)
  def pMapQSubbankIndexBits: Int = log2Ceil(pMapQSubbankCount)
  def pMapQRowsPerSubbank: Int = pMapQDepthPerStid / pMapQSubbankCount
  def tuMapQIndexWidth: Int = log2Ceil(tuMapQDepthPerStid)
  def tuMapQCountWidth: Int = countWidth(tuMapQDepthPerStid)
  def tuAllocationWidth: Int = decodedUopWidth * maxDestinationOperands
  def tuRetireSourceIndexWidth: Int = log2Ceil(tuRetireSourceDepthPerStid)
  def tuRetireSourceCountWidth: Int = countWidth(tuRetireSourceDepthPerStid)
  def tuRelationIndexWidth: Int = log2Ceil(tuRelationDepthPerStid)
  def tuRelationCountWidth: Int = countWidth(tuRelationDepthPerStid)
  def maxCommitTURetireSources: Int = retireGroupWidth * decodedUopWidth
  def commitTURetireSourceCountWidth: Int = countWidth(maxCommitTURetireSources)
  def maxCommitMapQRows: Int =
    retireGroupWidth * maxOrdinaryUopsPerGroup * maxDestinationOperands
  def commitMapQRowCountWidth: Int = countWidth(maxCommitMapQRows)
  def pcBufferIndexWidth: Int = log2Ceil(pcBufferEntries)
  def pcEntriesPerStid: Int = pcBufferEntries / stidCount
  def pcPartitionIndexWidth: Int = log2Ceil(pcEntriesPerStid)
  def pcPartitionCountWidth: Int = countWidth(pcEntriesPerStid)
  def pcBankSelectionBits: Int = log2Ceil(pcBankCount)
  def pcBankIndexWidth: Int = math.max(1, pcBankSelectionBits)
  def pcRowsPerBank: Int = pcEntriesPerStid / pcBankCount
  def pcBankRowIndexWidth: Int = math.max(1, log2Ceil(pcRowsPerBank))
  def pcReadPortsPerReplica: Int = pcReadPorts / pcReadReplicaCount
  def pcRecoveryScanCycles: Int =
    robGroupsPerStid / pcRecoveryScanGroupsPerCycle
  def pcRecoveryScanCursorWidth: Int =
    math.max(1, log2Ceil(pcRecoveryScanCycles))
  def iqBankWidth: Int = log2Ceil(iqBankCount)
  def iqEntryWidth: Int = log2Ceil(iqEntriesPerBank)
  def iqBankEntryCountWidth: Int = countWidth(iqEntriesPerBank)
  def iqWritePortWidth: Int = math.max(1, log2Ceil(iqWritePortsPerBank))
  def iqFreeSelectLeafEntriesEffective: Int =
    math.min(iqFreeSelectLeafEntries, iqEntriesPerBank)
  def iqFreeSelectGroupCount: Int =
    iqEntriesPerBank / iqFreeSelectLeafEntriesEffective
  def iexRecoveryScanCycles: Int =
    iqEntriesPerBank / iexRecoveryScanEntriesPerBankPerCycle
  def iexRecoveryScanCursorWidth: Int =
    math.max(1, log2Ceil(iexRecoveryScanCycles))
  def iexIssueDomainWidth: Int = math.max(1, log2Ceil(iexIssueDomainCount))
  def iexPReadPortWidth: Int = math.max(1, log2Ceil(iexPReadPorts))
  def iexTReadPortWidth: Int = math.max(1, log2Ceil(iexTReadPorts))
  def iexUReadPortWidth: Int = math.max(1, log2Ceil(iexUReadPorts))
  def instructionCountWidth: Int = countWidth(instructionDecodeWidth)
  def decodedUopCountWidth: Int = countWidth(decodedUopWidth)
  def renameCountWidth: Int = countWidth(renameWidth)
  def dispatchCountWidth: Int = countWidth(dispatchWidth)
  def robGroupCountWidth: Int = countWidth(instructionDecodeWidth)
  def nonFlushPrefixCountWidth: Int = countWidth(robGroupsPerStid)
  def robReleaseCountWidth: Int = countWidth(retireGroupWidth)
  def robGroupIndexWidth: Int = math.max(1, log2Ceil(instructionDecodeWidth))
  def robGroupParentDemandWidth: Int =
    countWidth(maxInstPerRobGroup + maxArchitecturalParentRefs)
  private def maxLogicalUopsPerParent: Int =
    math.max(maxOrdinaryUopsPerGroup, maxRecipeUops)
  def robMemberIndexWidth: Int = math.max(1, log2Ceil(maxLogicalUopsPerParent))
  def robMemberCountWidth: Int = countWidth(maxLogicalUopsPerParent)
  def decodedUopIndexWidth: Int = math.max(1, log2Ceil(decodedUopWidth))
  def recipeUopCountWidth: Int = countWidth(maxRecipeUops)
  def architecturalParentCountWidth: Int = countWidth(maxArchitecturalParentRefs)
  def sourceCountWidth: Int = countWidth(maxSourceOperands)
  def destinationCountWidth: Int = countWidth(maxDestinationOperands)
  def destinationDemandWidth: Int =
    countWidth(decodedUopWidth * maxDestinationOperands)
  def dispatchDemandWidth: Int =
    countWidth(decodedUopWidth * maxDispatchWritesPerInstruction)
  def memoryDemandWidth: Int =
    countWidth(decodedUopWidth * maxMemoryRequestsPerInstruction)
}
