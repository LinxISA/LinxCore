package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid, log2Ceil}

import linxcore.common.{CoreParams, ScalarLsuParams}
import linxcore.iex._
import linxcore.lsu.{LoadAttemptRebind, LoadCanonicalRowIdentity,
  LoadInflightAlloc, ScalarLSULoadReturnEntry}
import linxcore.params.{CoreParams => MainlineCoreParams}
import linxcore.rob.ROBID
import linxcore.top.interface.{RecoveryPlanContract, RecoveryTargetIO}

object OooIexCanonicalLoadOwnership {
  def defaultCoreParams(p: OooParams): CoreParams = {
    require(p.nativeBidWidth <= 16,
      "default canonical LSU projection supports native BID up to 16 bits")
    val bidProjectionEntries = 1 << (p.nativeBidWidth - 1)
    val robEntries = math.max(p.robGroupsPerStid, bidProjectionEntries)
    val defaultLiqEntries = ScalarLsuParams().liqEntries
    val scalarLsu = ScalarLsuParams(
      stidCount = p.stidCount,
      liqEntries = math.min(defaultLiqEntries, robEntries),
      loadReturnPipeCount = 2,
      peIdWidth = math.max(8, p.peIdWidth),
      stidWidth = math.max(8, p.stidWidth),
      tidWidth = math.max(8, p.stidWidth),
      archRegWidth = math.max(6, p.archRegWidth),
      physRegWidth = math.max(7, p.pTagWidth))
    CoreParams(
      robEntries = robEntries,
      lsidWidth = p.lsidWidth,
      scalarLsu = scalarLsu)
  }
}

/** Canonical LSU-facing port of the OOO execution composition. */
class OooIexCanonicalLoadPortIO(
    val p: OooParams,
    val coreParams: CoreParams) extends Bundle {
  private val lsu = coreParams.scalarLsu

  private def allocType = new LoadInflightAlloc(
    lsu.liqEntries, coreParams.robEntries, lsu.addrWidth, lsu.pcWidth,
    lsu.loadSizeWidth, lsu.archRegWidth, lsu.physRegWidth, lsu.peIdWidth,
    lsu.stidWidth, lsu.tidWidth, lsu.loadReturnPipeCount,
    coreParams.lsidWidth)
  private def completionType = new ScalarLSULoadReturnEntry(
    coreParams.robEntries, lsu.addrWidth, lsu.pcWidth, lsu.dataWidth,
    lsu.loadSizeWidth, lsu.loadReturnPipeCount, lsu.archRegWidth,
    lsu.physRegWidth, lsu.peIdWidth, lsu.stidWidth, lsu.tidWidth,
    coreParams.lsidWidth)

  val liqAlloc = Decoupled(allocType)
  val liqAllocLoadId = Input(new ROBID(lsu.liqEntries))
  val rebind = Flipped(Decoupled(
    new OooIexLoadTerminalMetadataRebind(p, coreParams)))
  val liqRebind = Decoupled(new LoadAttemptRebind(lsu.liqEntries))
  val attemptLaunch = Flipped(Valid(new OooIexLoadAttemptLaunch))
  val attemptLaunchAccepted = Output(Bool())
  val completion = Flipped(Decoupled(completionType))
}

class OooIexCanonicalLoadOwnershipIO(
    val p: OooParams,
    val coreParams: CoreParams,
    val laneCount: Int,
    val recoveryParams: MainlineCoreParams) extends Bundle {
  private val lsu = coreParams.scalarLsu

  private def allocType = new LoadInflightAlloc(
    lsu.liqEntries,
    coreParams.robEntries,
    lsu.addrWidth,
    lsu.pcWidth,
    lsu.loadSizeWidth,
    lsu.archRegWidth,
    lsu.physRegWidth,
    lsu.peIdWidth,
    lsu.stidWidth,
    lsu.tidWidth,
    lsu.loadReturnPipeCount,
    coreParams.lsidWidth)

  private def completionType = new ScalarLSULoadReturnEntry(
    coreParams.robEntries,
    lsu.addrWidth,
    lsu.pcWidth,
    lsu.dataWidth,
    lsu.loadSizeWidth,
    lsu.loadReturnPipeCount,
    lsu.archRegWidth,
    lsu.physRegWidth,
    lsu.peIdWidth,
    lsu.stidWidth,
    lsu.tidWidth,
    coreParams.lsidWidth)

  val agu = Flipped(Vec(laneCount,
    Decoupled(new OooIexAguLoadRequest(p))))

  // The canonical ScalarLSULoadPath remains the sole lifecycle owner.  Its
  // current allocation lease is returned combinationally beside allocReady.
  val liqAlloc = Decoupled(allocType)
  val liqAllocLoadId = Input(new ROBID(lsu.liqEntries))

  // Replay policy requests one exact generation transition.  Both the
  // canonical LIQ and the OOO metadata sidecar must accept the same fire.
  val rebind = Flipped(Decoupled(
    new OooIexLoadTerminalMetadataRebind(p, coreParams)))
  val liqRebind = Decoupled(new LoadAttemptRebind(lsu.liqEntries))
  val attemptLaunch = Flipped(Valid(
    new OooIexLoadAttemptLaunch))

  val completion = Flipped(Decoupled(completionType))
  val result = Decoupled(new OooIexLoadResult(p))
  val resultLane = Output(UInt(math.max(1, log2Ceil(laneCount)).W))
  val speculativeWakeup = Output(Vec(laneCount,
    Valid(new OooIexWakeup(p))))
  val loadCancel = Output(Vec(laneCount,
    Valid(new OooIexLoadCancel(p))))
  val loadBypass = Output(Vec(laneCount,
    Valid(new OooIexBypassCandidate(p))))

  val recovery = Flipped(new RecoveryTargetIO(recoveryParams))

  val allocAccepted = Output(Bool())
  val rebindAccepted = Output(Bool())
  val aguRejected = Output(Vec(laneCount,
    Valid(new LoadIexIssueReject(p))))
  val metadataAllocRejected = Output(Valid(
    new OooIexLoadTerminalMetadataReject(p, coreParams)))
  val metadataRebindRejected = Output(Valid(
    new OooIexLoadTerminalMetadataReject(p, coreParams)))
  val attemptLaunchAccepted = Output(Bool())
  val attemptLaunchRejected = Output(Valid(
    new OooIexLoadTerminalMetadataReject(p, coreParams)))
  val completionRejected = Output(Valid(
    new OooIexLoadTerminalMetadataReject(p, coreParams)))
  val metadataOccupied = Output(UInt(log2Ceil(lsu.liqEntries + 1).W))
  val metadataEmpty = Output(Bool())
}

/** Non-resident ownership join between parameterized OOO AGUs and canonical LIQ.
  *
  * Address, launch, miss, refill, replay, and result residency belong only to
  * `ScalarLSULoadPath`. This module atomically joins each canonical LIQ row
  * lease to the minimal OOO terminal metadata needed after the LIQ row has
  * transferred into LRET/W1/W2.  It cannot accept one half of allocation or
  * rebind, and terminal backpressure releases neither half early.
  */
class OooIexCanonicalLoadOwnership(
    val p: OooParams = OooParams(),
    val coreParams: CoreParams = CoreParams(
      scalarLsu = linxcore.common.ScalarLsuParams(
        stidCount = 4, loadReturnPipeCount = 2)),
    val laneCount: Int = 2,
    val recoveryParams: MainlineCoreParams) extends Module {
  def this(p: OooParams, coreParams: CoreParams) =
    this(p, coreParams, coreParams.scalarLsu.loadReturnPipeCount,
      OooRecoveryMembership.coreParams(p))

  def this(p: OooParams, coreParams: CoreParams, laneCount: Int) =
    this(p, coreParams, laneCount, OooRecoveryMembership.coreParams(p))
  private val lsu = coreParams.scalarLsu

  require(laneCount > 0,
    "the Linx scalar profile needs at least one AGU load lane")
  require(lsu.loadReturnPipeCount >= laneCount,
    "canonical LSU return-pipe identities must cover every AGU load lane")
  LoadCanonicalRowIdentity.requireBridgeFits(lsu.liqEntries)

  val io = IO(new OooIexCanonicalLoadOwnershipIO(
    p, coreParams, laneCount, recoveryParams))

  val issuePipeline = Module(new LoadIexIssuePipeline(
    p, coreParams, laneCount, recoveryParams))
  val metadata = Module(new OooIexLoadTerminalMetadata(
    p, coreParams, laneCount = laneCount, recoveryParams = recoveryParams))

  issuePipeline.io.agu <> io.agu

  val issuePrepareReady = issuePipeline.io.recovery.prepare.ready
  metadata.io.recoveryPrepare.bits := io.recovery.prepare.bits
  val metadataPrepareReady = metadata.io.recoveryPrepareReady
  val commonPrepareReady = issuePrepareReady && metadataPrepareReady
  val commonPrepareFire = io.recovery.prepare.valid && commonPrepareReady
  io.recovery.prepare.ready := commonPrepareReady
  issuePipeline.io.recovery.prepare.valid := commonPrepareFire
  issuePipeline.io.recovery.prepare.bits := io.recovery.prepare.bits
  metadata.io.recoveryPrepare.valid := commonPrepareFire

  val bothPrepared = issuePipeline.io.recovery.prepared.valid &&
    metadata.io.recoveryPrepared.valid
  io.recovery.prepared.valid := bothPrepared
  io.recovery.prepared.bits := issuePipeline.io.recovery.prepared.bits
  issuePipeline.io.recovery.prepared.ready := io.recovery.prepared.ready &&
    metadata.io.recoveryPrepared.valid

  issuePipeline.io.recovery.apply := io.recovery.apply
  issuePipeline.io.recovery.abort := io.recovery.abort
  metadata.io.recoveryApply := io.recovery.apply
  metadata.io.recoveryAbort := io.recovery.abort

  io.liqAlloc.bits := issuePipeline.io.alloc.bits
  io.liqAlloc.valid := issuePipeline.io.alloc.valid && metadata.io.alloc.ready
  issuePipeline.io.alloc.ready := io.liqAlloc.ready && metadata.io.alloc.ready

  metadata.io.alloc.valid := issuePipeline.io.alloc.valid && io.liqAlloc.ready
  metadata.io.alloc.bits := 0.U.asTypeOf(metadata.io.alloc.bits)
  metadata.io.alloc.bits.loadId :=
    LoadCanonicalRowIdentity.fromRobId(io.liqAllocLoadId)
  metadata.io.alloc.bits.attempt := issuePipeline.io.alloc.bits.attempt
  metadata.io.alloc.bits.load := issuePipeline.io.accepted.bits.load
  metadata.io.alloc.bits.request := issuePipeline.io.accepted.bits.request
  metadata.io.alloc.bits.lane := issuePipeline.io.accepted.bits.lane

  io.allocAccepted := io.liqAlloc.fire && metadata.io.alloc.fire &&
    issuePipeline.io.accepted.valid

  private def toRobId(target: ROBID, source: LoadCanonicalRowIdentity): Unit = {
    target.valid := source.valid
    target.value := source.slot(log2Ceil(target.entries) - 1, 0)
    target.wrap := source.generation(0)
  }

  io.liqRebind.bits := 0.U.asTypeOf(io.liqRebind.bits)
  toRobId(io.liqRebind.bits.loadId, io.rebind.bits.loadId)
  io.liqRebind.bits.current := io.rebind.bits.currentAttempt
  io.liqRebind.bits.next := io.rebind.bits.nextAttempt
  io.liqRebind.valid := io.rebind.valid && metadata.io.rebind.ready

  metadata.io.rebind.bits := io.rebind.bits
  metadata.io.rebind.valid := io.rebind.valid && io.liqRebind.ready
  io.rebind.ready := io.liqRebind.ready && metadata.io.rebind.ready
  io.rebindAccepted := io.rebind.fire && io.liqRebind.fire &&
    metadata.io.rebind.fire

  metadata.io.attemptLaunch := io.attemptLaunch
  io.attemptLaunchAccepted := metadata.io.attemptLaunchAccepted
  io.attemptLaunchRejected := metadata.io.attemptLaunchRejected
  io.speculativeWakeup := metadata.io.speculativeWakeup
  io.loadCancel := metadata.io.loadCancel
  io.loadBypass := metadata.io.loadBypass

  metadata.io.completion <> io.completion
  io.result <> metadata.io.result
  io.resultLane := metadata.io.resultLane

  io.aguRejected := issuePipeline.io.rejected
  io.metadataAllocRejected := metadata.io.allocRejected
  io.metadataRebindRejected := metadata.io.rebindRejected
  io.completionRejected := metadata.io.completionRejected
  io.metadataOccupied := metadata.io.occupied
  io.metadataEmpty := metadata.io.empty

  when(io.liqAlloc.fire || metadata.io.alloc.fire ||
      issuePipeline.io.accepted.valid) {
    assert(io.liqAlloc.fire && metadata.io.alloc.fire &&
      issuePipeline.io.accepted.valid,
      "canonical LIQ and OOO terminal metadata allocation must be atomic")
  }
  when(io.rebind.fire || io.liqRebind.fire || metadata.io.rebind.fire) {
    assert(io.rebind.fire && io.liqRebind.fire && metadata.io.rebind.fire,
      "canonical LIQ and OOO metadata rebind must be atomic")
  }
  when(io.recovery.prepared.fire) {
    assert(RecoveryPlanContract.sameTransactionIgnoringPhase(
      issuePipeline.io.recovery.prepared.bits,
      metadata.io.recoveryPrepared.bits),
      "canonical load owners must echo one exact common prepared plan")
  }
  when(io.recovery.apply.valid) {
    assert(metadata.io.recoveryApplyAccepted,
      "canonical load apply must match the commonly prepared plan")
  }
  when(io.recovery.abort.valid) {
    assert(metadata.io.recoveryAbortAccepted,
      "canonical load abort must match the commonly prepared plan")
  }
}
