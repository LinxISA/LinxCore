package linxcore.fpga.zybo

import linxcore.common.{CoreParams, ScalarBackendParams, ScalarLsuParams}
import linxcore.ooo.OooParams

object ZyboZ720PlatformParams {
  sealed trait ProfileIntent
  object ProfileIntent {
    case object Smoke extends ProfileIntent
    case object LinuxNommu extends ProfileIntent
  }

  final case class BootParams(
      pc: BigInt,
      sp: BigInt,
      a0: BigInt,
      a1: BigInt,
      initramfs: Option[BigInt])

  sealed trait OwnershipStatus {
    def claimsHardwareReduction: Boolean
  }
  object OwnershipStatus {
    case object Consumed extends OwnershipStatus {
      override val claimsHardwareReduction = true
    }
    case object Unpromoted extends OwnershipStatus {
      override val claimsHardwareReduction = false
    }
  }

  /** A source-level design hint, not proof that this profile reached hardware. */
  final case class AdvisoryConsumerAnchor(
      ownerClass: String,
      constructorArgument: String,
      sourcePath: String)

  /** Evidence capable of promoting a parameter must be backed by an exact-profile
    * Chisel elaboration and its generated hierarchy. Task 3 deliberately defines
    * no implementation: a later promotion must add a typed, validated evidence
    * owner rather than supplying a boolean or source filename.
    */
  sealed trait GeneratedHierarchyArtifact
  sealed trait ExactProfileHierarchyEvidence {
    def intent: ProfileIntent
    def core: CoreParams
    def ooo: OooParams
    def generatedHierarchy: GeneratedHierarchyArtifact
    def parameterNames: Set[String]
  }

  final case class ParameterOwnership private (
      parameter: String,
      configuredValue: Int,
      consumers: Vector[AdvisoryConsumerAnchor],
      private val expectedIntent: ProfileIntent,
      private val expectedCore: CoreParams,
      private val expectedOoo: OooParams,
      hierarchyEvidence: Option[ExactProfileHierarchyEvidence]) {
    val status: OwnershipStatus =
      if (hierarchyEvidence.exists { evidence =>
        evidence.intent == expectedIntent &&
          evidence.core == expectedCore &&
          evidence.ooo == expectedOoo &&
          evidence.generatedHierarchy != null &&
          evidence.parameterNames.contains(parameter)
      })
        OwnershipStatus.Consumed
      else OwnershipStatus.Unpromoted
  }

  final case class OwnershipReport private (
      profileIntent: ProfileIntent,
      exactCore: CoreParams,
      exactOoo: OooParams,
      entries: Vector[ParameterOwnership]) {
    require(entries.map(_.parameter) == entries.map(_.parameter).sorted,
      "ownership entries must have deterministic parameter ordering")
    require(entries.map(_.parameter).distinct == entries.map(_.parameter),
      "ownership entries must name each parameter exactly once")

    val parameterNames: Vector[String] = entries.map(_.parameter)
    val unpromotedParameters: Vector[String] =
      entries.collect { case entry if entry.status == OwnershipStatus.Unpromoted => entry.parameter }

    def consumersOf(parameter: String): Vector[AdvisoryConsumerAnchor] =
      entries.find(_.parameter == parameter).map(_.consumers).getOrElse(Vector.empty)

    def claimsHardwareReduction(parameter: String): Boolean =
      entries.find(_.parameter == parameter).exists(_.status.claimsHardwareReduction)

    def matchesExactProfile(
        candidateIntent: ProfileIntent,
        candidateCore: CoreParams,
        candidateOoo: OooParams): Boolean =
      candidateIntent == profileIntent && candidateCore == exactCore && candidateOoo == exactOoo
  }

  final case class ConstructorCheckSummary(
      benchmarkBodyUops: Int,
      closingMarkerUops: Int,
      configuredBenchmarkRobEntries: Int,
      architectureWidthsPreserved: Boolean,
      manifestGeometryMatches: Boolean,
      manifestBootAddressesMatch: Boolean) {
    val requiredBenchmarkRobEntries: Int = benchmarkBodyUops + closingMarkerUops
    val benchmarkRobHasCapacity: Boolean =
      configuredBenchmarkRobEntries >= requiredBenchmarkRobEntries
    val allChecksPass: Boolean =
      benchmarkRobHasCapacity &&
        architectureWidthsPreserved &&
        manifestGeometryMatches &&
        manifestBootAddressesMatch
  }

  final case class PlatformProfile(
      intent: ProfileIntent,
      core: CoreParams,
      ooo: OooParams,
      boot: BootParams,
      constructor: ConstructorCheckSummary,
      ownership: OwnershipReport)

  val LinuxMinCore: CoreParams = CoreParams(
    robEntries = 32,
    commitWidth = 2,
    scalarBackend = ScalarBackendParams(
      gprPhysRegs = 64,
      gprMapQDepth = 64,
      gprWritePorts = 2,
      scalarIssueBanks = 2,
      gprReadPorts = 3
    ),
    scalarLsu = ScalarLsuParams(
      stqEntries = 8,
      commitQueueEntries = 8,
      commitIssueWidth = 1,
      scbEntries = 4,
      scbResponseBufferDepth = 2,
      liqEntries = 8,
      loadMissQueueEntries = 2,
      loadRefillQueueEntries = 2,
      resolveQueueEntries = 4,
      mdbSsitEntries = 4,
      mdbCommandQueueEntries = 4,
      mdbOutputQueueEntries = 4,
      mdbWaitPlanQueueEntries = 2,
      mdbRecoveryQueueEntries = 2,
      loadReturnQueueEntries = 1,
      loadReturnPipeCount = 1,
      l1dSets = 32,
      l1dWays = 2,
      addrWidth = 32,
      pcWidth = 64,
      dataWidth = 64,
      lineBytes = 64,
      mapQDepth = 16,
      stidCount = 1
    ),
    lsidWidth = 32
  )

  val LinuxMinOoo: OooParams = OooParams(
    stidCount = 1,
    instructionDecodeWidth = 2,
    decodedUopWidth = 4,
    renameWidth = 2,
    dispatchWidth = 2,
    retireGroupWidth = 2,
    robCompletionBufferEntries = 4,
    storeCommitBufferEntries = 16,
    robGroupsPerStid = 16,
    robBankCount = 4,
    robSubbankCount = 1,
    robRecoveryScanGroupsPerCycle = 2,
    robNonFlushScanGroupsPerCycle = 2,
    brobEntriesPerStid = 32,
    pPhysRegs = 64,
    pTagBanks = 2,
    pTagStagingDepthPerBank = 4,
    pTagReturnWidth = 2,
    pMapQDepthPerStid = 64,
    tPhysRegs = 16,
    uPhysRegs = 16,
    tuMapQDepthPerStid = 16,
    tuRetireSourceDepthPerStid = 64,
    pcBufferEntries = 16,
    pcBankCount = 2,
    pcRecoveryScanGroupsPerCycle = 2,
    pcWritePorts = 2,
    pcReadPorts = 2,
    pcReadReplicaCount = 1,
    iqBankCount = 2,
    iqEntriesPerBank = 8,
    iqWritePortsPerBank = 1,
    iexPReadPorts = 3,
    iexTReadPorts = 1,
    iexUReadPorts = 1,
    iexPWritePorts = 2,
    iexTWritePorts = 1,
    iexUWritePorts = 1,
    iexWakeupPorts = 2,
    iexBypassPorts = 4,
    iexLoadCancelPorts = 1,
    iexTerminalWidth = 1
  )

  private val SmokeBoot = BootParams(
    pc = BigInt("00010000", 16),
    sp = BigInt("0003ff00", 16),
    a0 = 0,
    a1 = 0,
    initramfs = None
  )

  private val LinuxNommuBoot = BootParams(
    pc = BigInt("00010000", 16),
    sp = BigInt("0ffef000", 16),
    a0 = BigInt("00000000", 16),
    a1 = BigInt("0f000000", 16),
    initramfs = Some(BigInt("08000000", 16))
  )

  private val ArchitectureWidthsPreserved =
    LinuxMinCore.scalarLsu.addrWidth == 32 &&
      LinuxMinCore.scalarLsu.pcWidth == 64 &&
      LinuxMinCore.scalarLsu.dataWidth == 64 &&
      LinuxMinCore.lsidWidth == 32 &&
      LinuxMinOoo.pcWidth == 64 &&
      LinuxMinOoo.instructionWidth == 64 &&
      LinuxMinOoo.lsidWidth == 32

  private val ManifestGeometryMatches =
    LinuxMinCore.scalarLsu.dataWidth == ZyboZ720Generated.AxiDataWidth &&
      LinuxMinCore.scalarLsu.lineBytes == ZyboZ720Generated.LineBytes &&
      ZyboZ720Generated.MaxOutstanding == 1 &&
      Seq(
        ZyboZ720Generated.AxiControlBase + ZyboZ720Generated.AxiControlSize,
        ZyboZ720Generated.LinxMemoryBase + ZyboZ720Generated.LinxMemorySize,
        ZyboZ720Generated.UartData,
        ZyboZ720Generated.UartStatusLinuxExit,
        ZyboZ720Generated.TestFinisher,
        ZyboZ720Generated.VirtioBase,
        ZyboZ720Generated.KernelArtifactBase + ZyboZ720Generated.KernelArtifactSize,
        ZyboZ720Generated.InitramfsArtifactBase + ZyboZ720Generated.InitramfsArtifactSize,
        ZyboZ720Generated.DtbArtifactBase + ZyboZ720Generated.DtbArtifactSize
      ).forall(_ <= (BigInt(1) << LinuxMinCore.scalarLsu.addrWidth))

  private val ManifestBootAddressesMatch =
    SmokeBoot.pc == ZyboZ720Generated.SmokePc &&
      SmokeBoot.sp == ZyboZ720Generated.SmokeSp &&
      LinuxNommuBoot.pc == ZyboZ720Generated.LinuxPc &&
      LinuxNommuBoot.sp == ZyboZ720Generated.LinuxSp &&
      LinuxNommuBoot.a0 == ZyboZ720Generated.LinuxA0 &&
      LinuxNommuBoot.a1 == ZyboZ720Generated.LinuxA1 &&
      LinuxNommuBoot.initramfs.contains(ZyboZ720Generated.LinuxInitramfs)

  val ConstructorSummary: ConstructorCheckSummary = ConstructorCheckSummary(
    benchmarkBodyUops = 16,
    closingMarkerUops = 1,
    configuredBenchmarkRobEntries = LinuxMinCore.robEntries,
    architectureWidthsPreserved = ArchitectureWidthsPreserved,
    manifestGeometryMatches = ManifestGeometryMatches,
    manifestBootAddressesMatch = ManifestBootAddressesMatch
  )

  require(ConstructorSummary.benchmarkRobHasCapacity,
    "the benchmark ROB must hold 16 body uops plus the closing marker")
  require(ConstructorSummary.architectureWidthsPreserved,
    "the compact profile must preserve Linx address, PC, data, instruction, and LSID widths")
  require(ConstructorSummary.manifestGeometryMatches,
    "compact Scala geometry and address reach must agree with the generated platform manifest")
  require(ConstructorSummary.manifestBootAddressesMatch,
    "compact Scala boot addresses must agree with the generated platform manifest")

  private object ConsumerSources {
    val BenchmarkTop = AdvisoryConsumerAnchor(
      ownerClass = "linxcore.top.LinxCoreBenchmarkAutonomousTop",
      constructorArgument = "coreParams",
      sourcePath =
        "chisel/src/main/scala/linxcore/top/LinxCoreBenchmarkAutonomousTop.scala"
    )
    val ReducedBackendTop = AdvisoryConsumerAnchor(
      ownerClass = "linxcore.top.LinxCoreFrontendFetchRfAluTraceTop",
      constructorArgument = "coreParams",
      sourcePath =
        "chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala"
    )
  }

  private final case class OwnershipIntent(
      parameter: String,
      configuredValue: Int,
      consumers: Vector[AdvisoryConsumerAnchor])

  private def advisory(
      parameter: String,
      configuredValue: Int,
      consumers: AdvisoryConsumerAnchor*) =
    OwnershipIntent(parameter, configuredValue, consumers.toVector)
  private def unpromoted(parameter: String, configuredValue: Int) =
    OwnershipIntent(parameter, configuredValue, Vector.empty)

  private val CoreOwnership = Vector(
    advisory("core.commitWidth", LinuxMinCore.commitWidth,
      ConsumerSources.BenchmarkTop, ConsumerSources.ReducedBackendTop),
    advisory("core.lsidWidth", LinuxMinCore.lsidWidth,
      ConsumerSources.ReducedBackendTop),
    advisory("core.robEntries", LinuxMinCore.robEntries,
      ConsumerSources.ReducedBackendTop),
    advisory("core.scalarBackend.gprMapQDepth", LinuxMinCore.scalarBackend.gprMapQDepth,
      ConsumerSources.BenchmarkTop),
    advisory("core.scalarBackend.gprPhysRegs", LinuxMinCore.scalarBackend.gprPhysRegs,
      ConsumerSources.BenchmarkTop),
    advisory("core.scalarBackend.gprReadPorts", LinuxMinCore.scalarBackend.gprReadPorts,
      ConsumerSources.ReducedBackendTop),
    unpromoted("core.scalarBackend.gprWritePorts", LinuxMinCore.scalarBackend.gprWritePorts),
    advisory("core.scalarBackend.scalarIssueBanks", LinuxMinCore.scalarBackend.scalarIssueBanks,
      ConsumerSources.ReducedBackendTop),
    unpromoted("core.scalarLsu.addrWidth", LinuxMinCore.scalarLsu.addrWidth),
    advisory("core.scalarLsu.commitIssueWidth", LinuxMinCore.scalarLsu.commitIssueWidth,
      ConsumerSources.ReducedBackendTop),
    advisory("core.scalarLsu.commitQueueEntries", LinuxMinCore.scalarLsu.commitQueueEntries,
      ConsumerSources.ReducedBackendTop),
    unpromoted("core.scalarLsu.dataWidth", LinuxMinCore.scalarLsu.dataWidth),
    unpromoted("core.scalarLsu.l1dSets", LinuxMinCore.scalarLsu.l1dSets),
    unpromoted("core.scalarLsu.l1dWays", LinuxMinCore.scalarLsu.l1dWays),
    unpromoted("core.scalarLsu.lineBytes", LinuxMinCore.scalarLsu.lineBytes),
    unpromoted("core.scalarLsu.liqEntries", LinuxMinCore.scalarLsu.liqEntries),
    unpromoted("core.scalarLsu.loadMissQueueEntries", LinuxMinCore.scalarLsu.loadMissQueueEntries),
    unpromoted("core.scalarLsu.loadRefillQueueEntries", LinuxMinCore.scalarLsu.loadRefillQueueEntries),
    advisory("core.scalarLsu.loadReturnPipeCount", LinuxMinCore.scalarLsu.loadReturnPipeCount,
      ConsumerSources.ReducedBackendTop),
    advisory("core.scalarLsu.loadReturnQueueEntries", LinuxMinCore.scalarLsu.loadReturnQueueEntries,
      ConsumerSources.ReducedBackendTop),
    advisory("core.scalarLsu.mapQDepth", LinuxMinCore.scalarLsu.mapQDepth,
      ConsumerSources.BenchmarkTop),
    unpromoted("core.scalarLsu.mdbCommandQueueEntries", LinuxMinCore.scalarLsu.mdbCommandQueueEntries),
    unpromoted("core.scalarLsu.mdbOutputQueueEntries", LinuxMinCore.scalarLsu.mdbOutputQueueEntries),
    unpromoted("core.scalarLsu.mdbRecoveryQueueEntries", LinuxMinCore.scalarLsu.mdbRecoveryQueueEntries),
    unpromoted("core.scalarLsu.mdbSsitEntries", LinuxMinCore.scalarLsu.mdbSsitEntries),
    unpromoted("core.scalarLsu.mdbWaitPlanQueueEntries", LinuxMinCore.scalarLsu.mdbWaitPlanQueueEntries),
    unpromoted("core.scalarLsu.pcWidth", LinuxMinCore.scalarLsu.pcWidth),
    unpromoted("core.scalarLsu.resolveQueueEntries", LinuxMinCore.scalarLsu.resolveQueueEntries),
    advisory("core.scalarLsu.scbEntries", LinuxMinCore.scalarLsu.scbEntries,
      ConsumerSources.ReducedBackendTop),
    advisory("core.scalarLsu.scbResponseBufferDepth", LinuxMinCore.scalarLsu.scbResponseBufferDepth,
      ConsumerSources.ReducedBackendTop),
    advisory("core.scalarLsu.stidCount", LinuxMinCore.scalarLsu.stidCount,
      ConsumerSources.BenchmarkTop),
    advisory("core.scalarLsu.stqEntries", LinuxMinCore.scalarLsu.stqEntries,
      ConsumerSources.ReducedBackendTop)
  )

  private val OooOwnership = Vector(
    "brobEntriesPerStid" -> LinuxMinOoo.brobEntriesPerStid,
    "decodedUopWidth" -> LinuxMinOoo.decodedUopWidth,
    "dispatchWidth" -> LinuxMinOoo.dispatchWidth,
    "iexBypassPorts" -> LinuxMinOoo.iexBypassPorts,
    "iexLoadCancelPorts" -> LinuxMinOoo.iexLoadCancelPorts,
    "iexPReadPorts" -> LinuxMinOoo.iexPReadPorts,
    "iexPWritePorts" -> LinuxMinOoo.iexPWritePorts,
    "iexTReadPorts" -> LinuxMinOoo.iexTReadPorts,
    "iexTWritePorts" -> LinuxMinOoo.iexTWritePorts,
    "iexTerminalWidth" -> LinuxMinOoo.iexTerminalWidth,
    "iexUReadPorts" -> LinuxMinOoo.iexUReadPorts,
    "iexUWritePorts" -> LinuxMinOoo.iexUWritePorts,
    "iexWakeupPorts" -> LinuxMinOoo.iexWakeupPorts,
    "instructionDecodeWidth" -> LinuxMinOoo.instructionDecodeWidth,
    "iqBankCount" -> LinuxMinOoo.iqBankCount,
    "iqClassCount" -> LinuxMinOoo.iqClassCount,
    "iqEntriesPerBank" -> LinuxMinOoo.iqEntriesPerBank,
    "iqWritePortsPerBank" -> LinuxMinOoo.iqWritePortsPerBank,
    "pMapQDepthPerStid" -> LinuxMinOoo.pMapQDepthPerStid,
    "pPhysRegs" -> LinuxMinOoo.pPhysRegs,
    "pTagBanks" -> LinuxMinOoo.pTagBanks,
    "pTagReturnWidth" -> LinuxMinOoo.pTagReturnWidth,
    "pTagStagingDepthPerBank" -> LinuxMinOoo.pTagStagingDepthPerBank,
    "pcBankCount" -> LinuxMinOoo.pcBankCount,
    "pcBufferEntries" -> LinuxMinOoo.pcBufferEntries,
    "pcReadPorts" -> LinuxMinOoo.pcReadPorts,
    "pcReadReplicaCount" -> LinuxMinOoo.pcReadReplicaCount,
    "pcRecoveryScanGroupsPerCycle" -> LinuxMinOoo.pcRecoveryScanGroupsPerCycle,
    "pcWritePorts" -> LinuxMinOoo.pcWritePorts,
    "renameWidth" -> LinuxMinOoo.renameWidth,
    "retireGroupWidth" -> LinuxMinOoo.retireGroupWidth,
    "robBankCount" -> LinuxMinOoo.robBankCount,
    "robCompletionBufferEntries" -> LinuxMinOoo.robCompletionBufferEntries,
    "robGroupsPerStid" -> LinuxMinOoo.robGroupsPerStid,
    "maxCommitStoreTokens" -> LinuxMinOoo.maxCommitStoreTokens,
    "robNonFlushScanGroupsPerCycle" -> LinuxMinOoo.robNonFlushScanGroupsPerCycle,
    "robRecoveryScanGroupsPerCycle" -> LinuxMinOoo.robRecoveryScanGroupsPerCycle,
    "robSubbankCount" -> LinuxMinOoo.robSubbankCount,
    "stidCount" -> LinuxMinOoo.stidCount,
    "storeCommitBufferEntries" -> LinuxMinOoo.storeCommitBufferEntries,
    "tPhysRegs" -> LinuxMinOoo.tPhysRegs,
    "tuMapQDepthPerStid" -> LinuxMinOoo.tuMapQDepthPerStid,
    "tuRetireSourceDepthPerStid" -> LinuxMinOoo.tuRetireSourceDepthPerStid,
    "uPhysRegs" -> LinuxMinOoo.uPhysRegs
  ).map { case (parameter, value) => unpromoted(s"ooo.$parameter", value) }

  private def ownershipFor(intent: ProfileIntent): OwnershipReport = {
    val entries = (CoreOwnership ++ OooOwnership).sortBy(_.parameter).map { entry =>
      ParameterOwnership(
        parameter = entry.parameter,
        configuredValue = entry.configuredValue,
        consumers = entry.consumers,
        expectedIntent = intent,
        expectedCore = LinuxMinCore,
        expectedOoo = LinuxMinOoo,
        hierarchyEvidence = None
      )
    }
    OwnershipReport(intent, LinuxMinCore, LinuxMinOoo, entries)
  }

  val OwnershipSummary: OwnershipReport = ownershipFor(ProfileIntent.Smoke)

  val Smoke: PlatformProfile = PlatformProfile(
    intent = ProfileIntent.Smoke,
    core = LinuxMinCore,
    ooo = LinuxMinOoo,
    boot = SmokeBoot,
    constructor = ConstructorSummary,
    ownership = OwnershipSummary
  )

  val LinuxNommu: PlatformProfile = PlatformProfile(
    intent = ProfileIntent.LinuxNommu,
    core = LinuxMinCore,
    ooo = LinuxMinOoo,
    boot = LinuxNommuBoot,
    constructor = ConstructorSummary,
    ownership = ownershipFor(ProfileIntent.LinuxNommu)
  )
}
