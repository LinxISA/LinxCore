package linxcore.params

/** Capacity-bounded profiles for directed simulation only.
  *
  * Principal pipeline widths and fixed identity domains are inherited from
  * the corresponding main profile. Only local storage capacities are bounded.
  */
object SimulationParamProfiles {
  private def nextPowerOfTwo(value: Int): Int =
    if (value <= 1) 1
    else 1 << (32 - Integer.numberOfLeadingZeros(value - 1))

  private def bounded(width: Int): CoreParams = {
    val main = ParamProfiles.forWidth(width)
    val prefixCapacity = nextPowerOfTwo(width)
    // Keep one physical row per IQ bank at W2. The two-bank topology is
    // retained for the canonical picker fanout, but a second row per bank is
    // not required by any identity or width contract and doubles the largest
    // generated issue graph during bounded natural runs.
    val issueCapacity = prefixCapacity
    // Rename must admit the block-closing suffix even while an older open
    // block retains its speculative mappings. A width-only MapQ (four rows at
    // W2) can fill before that suffix reaches D3 and deadlock against BROB
    // closure. Sixteen rows is the bounded forward-progress floor; it remains
    // one quarter of the main profile.
    val destinationCapacity = math.max(16, nextPowerOfTwo(
      width * main.maxDestinationOperands))
    val pcCapacity = math.max(4, prefixCapacity)
    // An open block can retain one complete 32-uop template window before its
    // closing boundary becomes visible. Sixteen four-member groups preserve
    // that forward-progress margin while remaining below the 64-group main
    // profile. BROB capacity counts blocks rather than ROB groups and remains
    // independently bounded.
    val robGroupCapacity = math.max(16, prefixCapacity)
    val brobCapacity = math.max(8, prefixCapacity)
    val gprPhysicalCapacity = nextPowerOfTwo(
      main.ooo.stidCount * main.ooo.gprArchRegs + destinationCapacity)

    main.copy(
      ifu = main.ifu.copy(
        fetchBufferEntries = math.max(4, prefixCapacity),
        predictionCheckpointEntries = prefixCapacity,
        itlbEntries = 4,
        l1iSets = 4,
        missEntries = 4,
        joinEntries = 4),
      ctu = main.ctu.copy(
        instructionBufferEntries = prefixCapacity),
      ooo = main.ooo.copy(
        storeCommitBufferEntries = nextPowerOfTwo(
          main.ooo.retireWidth * main.maxMemoryRequestsPerInstruction),
        robGroupsPerStid = robGroupCapacity,
        maxInstructionsPerRobGroup = main.ooo.maxInstructionsPerRobGroup,
        maxUopsPerInstruction = 12,
        robBankCount = prefixCapacity,
        brobEntriesPerStid = brobCapacity,
        pcBufferEntries = pcCapacity,
        pcBankCount = pcCapacity,
        pcRecoveryScanGroupsPerCycle = math.min(4, robGroupCapacity),
        gprPhysRegs = gprPhysicalCapacity,
        gprMapQDepthPerStid = destinationCapacity,
        tPhysRegs = destinationCapacity,
        uPhysRegs = destinationCapacity,
        tuMapQDepthPerStid = destinationCapacity),
      iex = main.iex.copy(scalarIssueEntries = issueCapacity),
      lsu = main.lsu.copy(
        loadQueueEntries = 2,
        storeQueueEntries = nextPowerOfTwo(
          main.iex.stdPipes * main.maxMemoryRequestsPerInstruction),
        loadReturnQueueEntries = 2,
        storeCommitQueueEntries = 2,
        scbEntries = 4,
        loadMissQueueEntries = 2,
        loadRefillQueueEntries = 2,
        resolveQueueEntries = 4,
        mdbSsitEntries = 4,
        mdbCommandQueueEntries = 4,
        mdbOutputQueueEntries = 4,
        mdbWaitPlanQueueEntries = 4,
        mdbRecoveryQueueEntries = 4,
        l1dSets = 2,
        l1dWays = 2,
        scbResponseBufferDepth = 2,
        dTranslationEntries = 2,
        dTranslationCounterBits = 2,
        lowerMemoryTransactionsPerLane = 4),
      dtu = main.dtu.copy(traceBufferEntries = 4))
  }

  val W2: CoreParams = bounded(2)
  val W4: CoreParams = bounded(4)
  val W6: CoreParams = bounded(6)
  val W8: CoreParams = bounded(8)

  def forWidth(width: Int): CoreParams = width match {
    case 2 => W2
    case 4 => W4
    case 6 => W6
    case 8 => W8
    case _ =>
      throw new IllegalArgumentException(
        s"unsupported simulation width $width; supported widths are 2, 4, 6, and 8")
  }
}
