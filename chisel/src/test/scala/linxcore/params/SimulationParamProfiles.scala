package linxcore.params

/** Capacity-bounded profiles for directed simulation only.
  *
  * Principal pipeline widths and fixed identity domains are inherited from
  * the corresponding main profile.  Only local storage capacities are
  * bounded, so these profiles must not be used as interface-width or
  * full-capacity closure evidence.
  */
object SimulationParamProfiles {
  private def nextPowerOfTwo(value: Int): Int =
    if (value <= 1) 1
    else 1 << (32 - Integer.numberOfLeadingZeros(value - 1))

  private def bounded(width: Int): CoreParams = {
    val main = ParamProfiles.forWidth(width)
    val prefixCapacity = nextPowerOfTwo(width)
    val robCapacity = nextPowerOfTwo(width + 1)
    val pcBankCount = if (width <= 4) 4 else 8
    val issueCapacity = math.max(4, prefixCapacity)
    val renameDestinationDemand = width * main.maxDestinationOperands
    val renameQueueCapacity = nextPowerOfTwo(renameDestinationDemand)
    val gprCapacity = nextPowerOfTwo(
      main.ooo.stidCount * main.ooo.gprArchRegs + renameDestinationDemand)

    main.copy(
      ifu = main.ifu.copy(
        fetchBufferEntries = prefixCapacity,
        predictionCheckpointEntries = prefixCapacity),
      ctu = main.ctu.copy(
        instructionBufferEntries = prefixCapacity,
        maxTemplateUops = 2),
      ooo = main.ooo.copy(
        robGroupsPerStid = robCapacity,
        maxInstructionsPerRobGroup = 1,
        maxUopsPerInstruction = 12,
        robBankCount = prefixCapacity,
        brobEntriesPerStid = math.max(2, prefixCapacity),
        pcBufferEntries = pcBankCount,
        pcBankCount = pcBankCount,
        pcRecoveryScanGroupsPerCycle = math.min(4, prefixCapacity),
        gprPhysRegs = gprCapacity,
        gprMapQDepthPerStid = renameQueueCapacity,
        tPhysRegs = renameQueueCapacity,
        uPhysRegs = renameQueueCapacity,
        tuMapQDepthPerStid = renameQueueCapacity),
      iex = main.iex.copy(scalarIssueEntries = issueCapacity),
      lsu = main.lsu.copy(
        loadQueueEntries = 2,
        storeQueueEntries = 2,
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
        mdbFailedWaitTimeoutCycles = 8,
        l1dSets = 2,
        l1dWays = 2))
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
