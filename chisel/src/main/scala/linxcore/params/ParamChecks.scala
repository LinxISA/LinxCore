package linxcore.params

object ParamChecks {
  private val supportedPrincipalWidths = Set(2, 4, 6, 8)

  private def isPowerOfTwo(value: Int): Boolean =
    value > 0 && (value & (value - 1)) == 0

  private def requirePrincipalWidth(name: String, value: Int): Unit =
    require(
      supportedPrincipalWidths.contains(value),
      s"$name must be one of 2, 4, 6, or 8")

  def validate(p: CoreParams): Unit = {
    requirePrincipalWidth("fetchWidth", p.widths.fetchWidth)
    requirePrincipalWidth("ctuOutputWidth", p.widths.ctuOutputWidth)
    requirePrincipalWidth("decodeWidth", p.widths.decodeWidth)
    requirePrincipalWidth("renameWidth", p.widths.renameWidth)
    requirePrincipalWidth("dispatchWidth", p.widths.dispatchWidth)
    requirePrincipalWidth("issueWidth", p.widths.issueWidth)
    require(p.widths.retireWidth > 0, "retire width must be positive")

    require(
      p.widths.fetchWidth >= p.widths.ctuOutputWidth,
      "IFU fetch width must cover CTU output")
    require(
      p.widths.ctuOutputWidth <= p.widths.decodeWidth,
      "OOO decode width must cover CTU output")
    require(
      p.widths.renameWidth >= p.widths.decodeWidth,
      "rename width must cover the decode prefix")
    require(
      p.widths.dispatchWidth >= p.ooo.d3PrefixWidth,
      "dispatch width must cover the D3 prefix")

    require(
      p.ifu.fetchWidth == p.widths.fetchWidth,
      "IFU fetch width must match WidthParams")
    require(
      p.ifu.ctuTransferWidth == p.widths.ctuOutputWidth,
      "IFU CTU-transfer width must match WidthParams")
    require(
      p.ctu.inputWidth == p.ifu.ctuTransferWidth,
      "CTU input width must match the IFU transfer width")
    require(
      p.ctu.outputWidth == p.widths.ctuOutputWidth,
      "CTU output width must match WidthParams")
    require(
      p.ooo.decodeWidth == p.widths.decodeWidth,
      "OOO decode width must match WidthParams")
    require(
      p.ooo.renameWidth == p.widths.renameWidth,
      "OOO rename width must match WidthParams")
    require(
      p.ooo.dispatchWidth == p.widths.dispatchWidth,
      "OOO dispatch width must match WidthParams")
    require(
      p.ooo.retireWidth == p.widths.retireWidth,
      "OOO retire width must match WidthParams")
    require(
      p.ooo.d3PrefixWidth <= p.ooo.renameWidth,
      "D3 prefix width must fit the rename width")
    require(
      p.iex.issueWidth == p.widths.issueWidth,
      "IEX issue width must match WidthParams")
    require(p.iex.issueQueueClasses > 0,
      "IEX must configure at least one Issue Queue class")
    require(p.iex.executionPipeKinds > 0,
      "IEX must configure at least one Execution pipe kind")

    require(p.ifu.instructionBits == 64, "IFU instruction container must be 64 bits")
    require(p.ctu.instructionBits == 64, "CTU instruction container must be 64 bits")
    require(p.instructionWidth == 64, "core instruction container must be 64 bits")
    require(p.pcWidth == 64, "LinxCore PC width must be 64 bits")
    require(p.opcodeWidth == 12, "canonical opcode identity must preserve 12 bits")
    require(p.archRegWidth >= 6, "architectural register width must cover reg6")
    require(p.lsidWidth >= 32, "LSID width must preserve at least 32 bits")
    require(
      p.maxMemoryRequestsPerInstruction >= 2,
      "memory request count must cover scalar pair operations")
    require(p.peIdWidth > 0, "PE identity width must be positive")
    require(p.instructionIdWidth > 0, "instruction identity width must be positive")
    require(p.transactionIdWidth > 0, "transaction identity width must be positive")
    require(p.epochWidth > 0, "instruction epoch width must be positive")
    require(p.brobGenerationWidth > 0, "BROB generation width must be positive")
    require(p.ridGenerationWidth > 0, "ROB generation width must be positive")
    require(
      p.residentGenerationWidth > 0,
      "resident generation width must be positive")
    require(
      p.memoryTransactionIdWidth > 0 &&
        p.memoryTransactionGenerationWidth > 0 &&
        p.memoryAttemptGenerationWidth > 0,
      "memory identities must carry transaction, generation, and attempt domains")
    require(p.trapCauseWidth > 0, "trap cause width must be positive")
    require(
      p.physicalAddressWidth == 64 && p.dataWidth == 64,
      "mainline scalar memory boundary is 64-bit")
    require(
      p.lsu.dTranslationEntries > 1 &&
        isPowerOfTwo(p.lsu.dTranslationEntries),
      "D-side translation entries must be a power of two greater than one")
    require(
      p.lsu.dTranslationPageBytes > p.lsu.lineBytes &&
        isPowerOfTwo(p.lsu.dTranslationPageBytes),
      "D-side translation page size must be a power of two above line size")
    require(
      p.lsu.dTranslationCounterBits > 0 &&
        p.lsu.dTranslationCounterBits < p.memoryTransactionIdWidth &&
        p.lsu.dTranslationCounterBits < p.memoryTransactionGenerationWidth,
      "D-side translation counter bits must fit below each public owner bit")
    require(
      p.lsu.lowerMemoryTransactionsPerLane >= p.lsu.loadMissQueueEntries,
      "lower-memory ledger per lane must cover the load miss queue")
    require(p.lsu.scbResponseBufferDepth > 0,
      "SCB response buffer depth must be positive")
    require(p.maxSourceOperands > 0, "source operand count must be positive")
    require(p.maxDestinationOperands > 0, "destination operand count must be positive")

    require(
      p.ifu.fetchBufferEntries >= p.ifu.fetchWidth,
      "IFU fetch buffer must hold one full fetch packet")
    require(
      p.ifu.predictionCheckpointEntries > 0,
      "IFU prediction checkpoint count must be positive")
    require(
      p.ifu.iSideStages == 5 && p.ifu.bSideStages == 5,
      "IFU I-SIDE and B-SIDE each have five architectural stages")
    require(
      isPowerOfTwo(p.ifu.lineBytes) && p.ifu.lineBytes >= p.dataWidth / 8,
      "IFU cache line size must be a power of two covering one memory beat")
    require(
      isPowerOfTwo(p.ifu.pageBytes) && p.ifu.pageBytes > p.ifu.lineBytes,
      "IFU page size must be a power of two larger than one cache line")
    require(
      isPowerOfTwo(p.ifu.itlbEntries),
      "IFU ITLB entry count must be a positive power of two")
    require(
      isPowerOfTwo(p.ifu.l1iSets),
      "IFU L1I set count must be a positive power of two")
    require(
      isPowerOfTwo(p.ifu.missEntries) &&
        isPowerOfTwo(p.ifu.joinEntries) &&
        p.ifu.missEntries >= p.ifu.joinEntries,
      "IFU miss capacity must cover the power-of-two prediction join capacity")
    require(
      isPowerOfTwo(p.ifu.maxGroupsPerTransaction),
      "IFU transaction group capacity must be a positive power of two")
    require(
      p.ifu.resetVector >= 0 && p.ifu.resetVector < (BigInt(1) << p.pcWidth),
      "IFU reset vector must fit the PC width")
    require(
      p.ctu.instructionBufferEntries >= p.ctu.outputWidth,
      "CTU instruction buffer must hold one output packet")
    require(p.ctu.maxTemplateUops > 0, "CTU template expansion limit must be positive")

    require(
      isPowerOfTwo(p.ooo.stidCount),
      "OOO STID count must be a positive power of two")
    require(
      isPowerOfTwo(p.ooo.stidIdentityEntries) &&
        p.ooo.stidIdentityEntries >= p.ooo.stidCount,
      "STID identity entries must be a power of two covering physical STIDs")
    require(
      isPowerOfTwo(p.ooo.robGroupsPerStid),
      "ROB groups per STID must be a positive power of two")
    require(
      isPowerOfTwo(p.ooo.robIdentityGroupsPerStid) &&
        p.ooo.robIdentityGroupsPerStid >= p.ooo.robGroupsPerStid,
      "ROB identity groups must be a power of two covering physical ROB groups")
    require(
      p.ooo.maxInstructionsPerRobGroup > 0,
      "ROB group instruction capacity must be positive")
    require(
      isPowerOfTwo(p.ooo.robIdentityMembersPerGroup) &&
        p.ooo.robIdentityMembersPerGroup >=
          p.ooo.maxInstructionsPerRobGroup,
      "ROB identity members must be a power of two covering physical group instruction capacity")
    require(
      p.ooo.maxUopsPerInstruction > 0,
      "decoded instruction uop capacity must be positive")
    require(
      isPowerOfTwo(p.ooo.uopIdentityEntriesPerInstruction) &&
        p.ooo.uopIdentityEntriesPerInstruction >=
          p.ooo.maxUopsPerInstruction &&
        p.ooo.uopIdentityEntriesPerInstruction >=
          p.ooo.robIdentityMembersPerGroup,
      "uop identity entries must be a power of two covering physical uops and ROB identity members")
    require(
      p.ooo.robCapacityPerStid >= p.ooo.d3PrefixWidth,
      "ROB capacity must hold one D3 prefix")
    require(
      isPowerOfTwo(p.ooo.robBankCount) &&
        p.ooo.robBankCount <= p.ooo.robGroupsPerStid,
      "ROB bank count must be a power of two within the group count")
    require(
      p.ooo.dispatchWidth <= p.ooo.robBankCount,
      "ROB banks must cover one dispatch prefix")
    require(
      isPowerOfTwo(p.ooo.brobEntriesPerStid) &&
        p.ooo.brobEntriesPerStid >= 2,
      "BROB entries per STID must be a power of two and at least 2")
    require(
      isPowerOfTwo(p.ooo.brobIdentityEntriesPerStid) &&
        p.ooo.brobIdentityEntriesPerStid >= p.ooo.brobEntriesPerStid,
      "BROB identity entries must be a power of two covering physical entries")
    require(
      isPowerOfTwo(p.ooo.pcBufferEntries) &&
        p.ooo.pcBufferEntries % p.ooo.stidCount == 0,
      "PC buffer entries must be a power of two evenly partitioned by STID")
    val pcEntriesPerStid = p.ooo.pcBufferEntries / p.ooo.stidCount
    require(
      isPowerOfTwo(p.ooo.pcBankCount) &&
        p.ooo.pcBankCount >= p.ooo.retireWidth &&
        p.ooo.pcBankCount <= pcEntriesPerStid &&
        pcEntriesPerStid % p.ooo.pcBankCount == 0,
      "PC buffer banks must cover retire width and divide each STID partition")
    require(
      p.ooo.pcWritePorts == 3 && p.ooo.pcWritePorts <= p.ooo.pcBankCount,
      "PC buffer exposes exactly three D3 PC-base write ports")
    require(
      p.ooo.pcReadPorts == 6 && p.ooo.pcReadReplicaCount == 3 &&
        p.ooo.pcReadPorts % p.ooo.pcReadReplicaCount == 0,
      "PC buffer exposes six reads through three exact PC-base replicas")
    require(
      p.ooo.pcRecoveryScanGroupsPerCycle > 0 &&
        p.ooo.pcOffsetWidth > 0 &&
        p.ooo.pcAllocationEpochWidth > 0,
      "PC buffer recovery, PC offset, and allocation epoch widths must be positive")
    require(p.ooo.gprArchRegs == 24, "Linx scalar GPR namespace contains 24 registers")
    require(
      isPowerOfTwo(p.ooo.gprPhysRegs) &&
        p.ooo.gprPhysRegs > p.ooo.stidCount * p.ooo.gprArchRegs,
      "physical GPR capacity must cover committed and speculative mappings")
    require(
      isPowerOfTwo(p.ooo.gprTagIdentityEntries) &&
        p.ooo.gprTagIdentityEntries >= p.ooo.gprPhysRegs,
      "GPR tag identity entries must cover the physical GPR capacity")
    val renameDestinationDemand =
      p.ooo.renameWidth * p.maxDestinationOperands
    require(
      p.ooo.gprPhysRegs - p.ooo.stidCount * p.ooo.gprArchRegs >=
        renameDestinationDemand,
      "speculative GPR capacity must cover one maximum rename prefix")
    require(
      p.ooo.gprTagGenerationWidth > 0,
      "GPR physical tag generation width must be positive")
    require(
      isPowerOfTwo(p.ooo.gprMapQDepthPerStid) &&
        p.ooo.gprMapQDepthPerStid >=
          p.ooo.renameWidth * p.maxDestinationOperands,
      "GPR MapQ depth must be a power of two covering one rename prefix")
    require(
      isPowerOfTwo(p.ooo.tPhysRegs) && isPowerOfTwo(p.ooo.uPhysRegs),
      "T and U physical namespaces must be powers of two")
    require(
      isPowerOfTwo(p.ooo.tTagIdentityEntries) &&
        isPowerOfTwo(p.ooo.uTagIdentityEntries) &&
        p.ooo.tTagIdentityEntries >= p.ooo.tPhysRegs &&
        p.ooo.uTagIdentityEntries >= p.ooo.uPhysRegs,
      "T and U tag identity entries must cover their physical namespaces")
    require(
      p.ooo.tPhysRegs >= renameDestinationDemand &&
        p.ooo.uPhysRegs >= renameDestinationDemand,
      "T and U physical namespaces must independently cover one maximum rename prefix")
    require(
      p.ooo.localSeqGenerationWidth > 0,
      "T/U local sequence generation width must be positive")
    require(
      isPowerOfTwo(p.ooo.tuMapQDepthPerStid) &&
        p.ooo.tuMapQDepthPerStid >= renameDestinationDemand,
      "T/U MapQ depth must be a power of two covering one maximum rename prefix")

    require(p.iex.aluPipes > 0, "ALU pipe count must be positive")
    require(p.iex.bruPipes > 0, "BRU pipe count must be positive")
    require(p.iex.aguPipes > 0, "AGU pipe count must be positive")
    require(p.iex.stdPipes > 0, "STD pipe count must be positive")
    require(
      p.iex.systemMulticycleQueues > 0,
      "system/multicycle queue count must be positive")
    require(p.iex.cmdIssueQueues > 0, "CMD issue queue count must be positive")
    require(
      isPowerOfTwo(p.iex.scalarIssueEntries),
      "scalar issue capacity must be a positive power of two")
    require(
      isPowerOfTwo(p.iex.scalarIssueBanks) &&
        p.iex.scalarIssueBanks <= p.iex.scalarIssueEntries,
      "scalar issue banks must be a power of two within issue capacity")
    require(p.iex.integerReadPorts > 0, "integer read-port count must be positive")
    require(p.iex.integerWritePorts > 0, "integer write-port count must be positive")

    require(p.lsu.loadPipes > 0, "load pipe count must be positive")
    require(p.lsu.storePipes > 0, "store pipe count must be positive")
    require(
      p.lsu.loadQueueEntries >= p.lsu.loadPipes,
      "load queue must cover all load pipes")
    require(
      p.lsu.storeQueueEntries >= p.lsu.storePipes,
      "store queue must cover all store pipes")
    require(
      p.lsu.loadReturnQueueEntries >= p.lsu.loadPipes,
      "load-return queue must cover all load pipes")
    require(
      p.lsu.storeCommitQueueEntries >= p.lsu.storePipes,
      "store-commit queue must cover all store pipes")
    require(
      p.lsu.scbEntries >= p.lsu.storePipes * 2,
      "SCB must cover one worst-case split-store batch")
    require(
      isPowerOfTwo(p.lsu.lineBytes),
      "cache line size must be a positive power of two")

    require(p.dtu.traceWidth > 0, "DTU trace width must be positive")
    require(
      p.dtu.traceWidth >= p.widths.fetchWidth,
      "DTU trace width must cover the IFU transfer")
    require(
      p.dtu.performanceCounterCount > 0,
      "DTU performance-counter count must be positive")
    require(
      isPowerOfTwo(p.dtu.traceBufferEntries),
      "DTU trace buffer must be a positive power of two")
  }
}
