package linxcore.iex

import chisel3._
import chisel3.util.{Decoupled, PopCount, Valid, log2Ceil}

import linxcore.common.{CoreParams, DestinationKind, OperandClass}
import linxcore.lsu.{LoadAttemptIdentity, LoadCanonicalRowIdentity,
  LoadReplayDestination, LoadTerminalFault, ScalarLSULoadReturnEntry}
import linxcore.ooo._
import linxcore.params.{CoreParams => MainlineCoreParams}
import linxcore.top.interface.{RecoveryPhase, RecoveryPlan,
  RecoveryPlanContract}

/** Metadata installed atomically beside one canonical LIQ allocation.
  *
  * Address, miss, replay, data, and request residency remain solely in the
  * scalar LSU.  This sidecar retains only the typed OOO execution context that
  * the architectural terminal sink must revalidate after the LIQ row has
  * transferred into LRET/W1/W2.
  */
class OooIexLoadTerminalMetadataAlloc(
    val p: OooParams,
    val coreParams: CoreParams,
    val laneCount: Int = 2) extends Bundle {
  val loadId = new LoadCanonicalRowIdentity
  val attempt = new LoadAttemptIdentity
  val load = new OooIexLoadGeneration(p)
  val request = new OooIexAguLoadRequest(p)
  val lane = UInt(math.max(1, log2Ceil(laneCount)).W)
}

/** Exact canonical launch event after one LIQ attempt enters execution. */
class OooIexLoadAttemptLaunch extends Bundle {
  val loadId = new LoadCanonicalRowIdentity
  val attempt = new LoadAttemptIdentity
}

/** Exact replay-generation update for one still-live terminal sidecar. */
class OooIexLoadTerminalMetadataRebind(
    val p: OooParams,
    val coreParams: CoreParams) extends Bundle {
  val loadId = new LoadCanonicalRowIdentity
  val currentAttempt = new LoadAttemptIdentity
  val nextAttempt = new LoadAttemptIdentity
  val currentLoad = new OooIexLoadGeneration(p)
  val nextLoad = new OooIexLoadGeneration(p)
}

class OooIexLoadTerminalMetadataReject(
    val p: OooParams,
    val coreParams: CoreParams) extends Bundle {
  val loadId = new LoadCanonicalRowIdentity
  val member = new RobMemberKey(p)
  val rowIdExact = Bool()
  val resident = Bool()
  val producerExact = Bool()
  val attemptExact = Bool()
  val transactionExact = Bool()
  val pipeExact = Bool()
  val destinationExact = Bool()
  val outcomeExact = Bool()
}

class OooIexLoadTerminalMetadataEntry(
    val p: OooParams,
    val coreParams: CoreParams,
    val laneCount: Int = 2) extends Bundle {
  val valid = Bool()
  val loadId = new LoadCanonicalRowIdentity
  val attempt = new LoadAttemptIdentity
  val canceledAttemptValid = Bool()
  val canceledAttempt = new LoadAttemptIdentity
  val load = new OooIexLoadGeneration(p)
  val request = new OooIexAguLoadRequest(p)
  val lane = UInt(math.max(1, log2Ceil(laneCount)).W)
  val faultCancelSent = Bool()
}

class OooIexLoadTerminalMetadataIO(
    val p: OooParams,
    val coreParams: CoreParams,
    val laneCount: Int = 2,
    val recoveryParams: MainlineCoreParams) extends Bundle {
  private val lsu = coreParams.scalarLsu
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

  val alloc = Flipped(Decoupled(
    new OooIexLoadTerminalMetadataAlloc(p, coreParams, laneCount)))
  val rebind = Flipped(Decoupled(
    new OooIexLoadTerminalMetadataRebind(p, coreParams)))
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

  val recoveryPrepare = Flipped(Valid(new RecoveryPlan(recoveryParams)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Valid(new RecoveryPlan(recoveryParams))
  val recoveryRejected = Output(Bool())
  val recoveryKilledMask = Output(UInt(lsu.liqEntries.W))
  val recoveryApply = Flipped(Valid(new RecoveryPlan(recoveryParams)))
  val recoveryApplyAccepted = Output(Bool())
  val recoveryApplyRejected = Output(Bool())
  val recoveryAbort = Flipped(Valid(new RecoveryPlan(recoveryParams)))
  val recoveryAbortAccepted = Output(Bool())
  val recoveryAbortRejected = Output(Bool())

  val allocRejected = Valid(new OooIexLoadTerminalMetadataReject(p, coreParams))
  val rebindRejected = Valid(new OooIexLoadTerminalMetadataReject(p, coreParams))
  val attemptLaunchAccepted = Output(Bool())
  val attemptLaunchRejected = Valid(
    new OooIexLoadTerminalMetadataReject(p, coreParams))
  val completionRejected = Valid(new OooIexLoadTerminalMetadataReject(p, coreParams))
  val cancelCollision = Output(Bool())
  val occupied = Output(UInt(log2Ceil(lsu.liqEntries + 1).W))
  val empty = Output(Bool())
}

/** Exact metadata-only owner joining canonical LSU attempts to OOO policy and
  * terminal publication.
  *
  * A sidecar row remains occupied after LIQ resolution and is released only
  * by the same `result.fire` that releases the LSU W2 transaction.  Therefore
  * the corresponding LIQ slot cannot be rebound to another producer while a
  * delayed terminal transaction still names its old physical row lease.
  */
class OooIexLoadTerminalMetadata(
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
  private val entriesCount = lsu.liqEntries

  require(lsu.stidCount == p.stidCount,
    "OOO and canonical scalar LSU must share the STID population")
  require(coreParams.robEntries >= p.robGroupsPerStid,
    "canonical LSU ROB projection must cover every OOO RID slot")
  require(p.pcWidth <= lsu.pcWidth && p.peIdWidth <= lsu.peIdWidth &&
    p.stidWidth <= lsu.stidWidth && p.archRegWidth <= lsu.archRegWidth &&
    p.pTagWidth <= lsu.physRegWidth,
    "OOO terminal metadata must fit the canonical scalar LSU payload")
  require(p.trapCauseWidth <= LoadTerminalFault.CauseWidth,
    "OOO trap cause must fit the canonical load terminal fault payload")
  require(laneCount > 0 && lsu.loadReturnPipeCount >= laneCount,
    "terminal metadata requires every configured load lane")
  LoadCanonicalRowIdentity.requireBridgeFits(entriesCount)
  LoadAttemptIdentity.requireBridgeFits(
    p.peIdWidth,
    p.stidWidth,
    p.nativeBidWidth,
    p.ridSlotWidth,
    p.brobGenerationWidth,
    p.ridGenerationWidth,
    p.robMemberIndexWidth,
    p.residentGenerationWidth,
    p.loadGenerationWidth)
  OooRecoveryMembership.requireCompatible(p, recoveryParams)

  val io = IO(new OooIexLoadTerminalMetadataIO(
    p, coreParams, laneCount, recoveryParams))

  private def zeroEntry: OooIexLoadTerminalMetadataEntry = {
    val entry = Wire(new OooIexLoadTerminalMetadataEntry(
      p, coreParams, laneCount))
    entry := 0.U.asTypeOf(entry)
    entry.loadId := LoadCanonicalRowIdentity.none
    entry.attempt := LoadAttemptIdentity.none
    entry
  }

  private def toAttempt(load: OooIexLoadGeneration): LoadAttemptIdentity = {
    val attempt = Wire(new LoadAttemptIdentity)
    attempt := 0.U.asTypeOf(attempt)
    attempt.valid := load.valid
    attempt.producer.valid := load.producer.group.valid
    attempt.producer.peId := load.producer.group.peId
    attempt.producer.stid := load.producer.group.stid
    attempt.producer.nativeBidValid := load.producer.bid.valid
    attempt.producer.nativeBid := load.producer.bid.value
    attempt.producer.brobGeneration := load.producer.brobGeneration
    attempt.producer.ridSlot := load.producer.group.ridSlot
    attempt.producer.ridGeneration := load.producer.group.ridGeneration
    attempt.producer.memberIndex := load.producer.memberIndex
    attempt.producer.residentGeneration := load.producer.residentGeneration
    attempt.generation := load.generation
    attempt
  }

  private def producerExact(
      load: OooIexLoadGeneration,
      request: OooIexAguLoadRequest): Bool = {
    val row = request.execute.i2.row
    load.valid && row.valid && row.member.group.valid && row.member.bid.valid &&
      row.memoryTransactionValid &&
      load.producer.asUInt === row.member.asUInt &&
      load.transaction.asUInt === row.memoryTransaction.asUInt &&
      row.member.group.peId === row.peId &&
      row.member.group.stid === row.stid && row.stid < p.stidCount.U
  }

  private def projectedDestinationExact(
      destination: LoadReplayDestination,
      request: OooIexAguLoadRequest): Bool = {
    val previous = request.execute.i2.row.payload.previousPDestinations(0)
    destination.valid && destination.kind === DestinationKind.Gpr &&
      request.destination.valid && request.destination.kind === DestinationKind.Gpr &&
      destination.archTag === request.destination.atag &&
      destination.relTag === request.destination.relativeIndex &&
      destination.physTag === request.destination.ptag &&
      destination.oldPhysTag === previous.ptag
  }

  private def operandClass(destination: OooIexDestinationState): OperandClass.Type =
    Mux(destination.kind === DestinationKind.Gpr, OperandClass.P,
      Mux(destination.kind === DestinationKind.T, OperandClass.T,
        Mux(destination.kind === DestinationKind.U, OperandClass.U,
          OperandClass.Invalid)))

  private def sidecarExact(
      entry: OooIexLoadTerminalMetadataEntry,
      index: Int): Bool =
    !entry.valid || (
      LoadCanonicalRowIdentity.wellFormed(entry.loadId, entriesCount) &&
      entry.loadId.valid && entry.loadId.slot === index.U &&
      entry.lane < laneCount.U &&
      LoadAttemptIdentity.equal(entry.attempt, toAttempt(entry.load)) &&
      producerExact(entry.load, entry.request))

  val entries = RegInit(VecInit(Seq.fill(entriesCount)(zeroEntry)))
  val preparedValid = RegInit(false.B)
  val preparedPlan = Reg(new RecoveryPlan(recoveryParams))
  val preparedKilledMask = Reg(UInt(entriesCount.W))

  val entryExact = VecInit(entries.zipWithIndex.map {
    case (entry, index) => sidecarExact(entry, index)
  })
  val recoveryPlanExact =
    io.recoveryPrepare.bits.phase === RecoveryPhase.Prepare &&
    RecoveryPlanContract.legalSuffixWindow(io.recoveryPrepare.bits) &&
    io.recoveryPrepare.bits.trigger.stid < p.stidCount.U &&
    entryExact.asUInt.andR
  io.recoveryPrepareReady := !preparedValid && recoveryPlanExact
  val recoveryPrepareFire =
    io.recoveryPrepare.valid && io.recoveryPrepareReady
  val recoveryKill = VecInit(entries.map(entry =>
    entry.valid && recoveryPlanExact &&
      OooRecoveryMembership.memberKilled(
        p, recoveryParams, io.recoveryPrepare.bits, entry.load.producer)))

  private def recoveryFences(stid: UInt): Bool =
    (recoveryPrepareFire &&
      io.recoveryPrepare.bits.trigger.stid === stid) ||
    (preparedValid && preparedPlan.trigger.stid === stid)

  val allocIdWellFormed =
    LoadCanonicalRowIdentity.wellFormed(io.alloc.bits.loadId, entriesCount)
  val allocIdValid = allocIdWellFormed && io.alloc.bits.loadId.valid
  val allocIndex = io.alloc.bits.loadId.slot(log2Ceil(entriesCount) - 1, 0)
  val allocResident = allocIdValid && entries(allocIndex).valid
  val allocProducerExact = producerExact(io.alloc.bits.load, io.alloc.bits.request)
  val allocAttemptExact = LoadAttemptIdentity.equal(
    io.alloc.bits.attempt, toAttempt(io.alloc.bits.load))
  val allocInitialAttemptExact =
    io.alloc.bits.request.execute.i2.row.initialLoadAttemptValid &&
    io.alloc.bits.load.generation ===
      io.alloc.bits.request.execute.i2.row.initialLoadAttemptGeneration
  val allocLaneExact = io.alloc.bits.lane < laneCount.U
  val allocExact = allocIdValid && allocProducerExact && allocAttemptExact &&
    allocInitialAttemptExact && allocLaneExact
  val allocFence = recoveryFences(io.alloc.bits.load.producer.group.stid)
  io.alloc.ready := !allocFence && allocExact && !allocResident

  val completionId = io.completion.bits.payload.loadId
  val completionIdWellFormed =
    LoadCanonicalRowIdentity.wellFormed(completionId, entriesCount)
  val completionIdValid = completionIdWellFormed && completionId.valid
  val completionIndex = completionId.slot(log2Ceil(entriesCount) - 1, 0)
  val completionEntry = entries(completionIndex)
  val completionResident = completionIdValid && completionEntry.valid &&
    LoadCanonicalRowIdentity.equal(completionEntry.loadId, completionId)
  val completionProducerExact = completionResident &&
    producerExact(completionEntry.load, completionEntry.request) &&
    io.completion.bits.peId === completionEntry.request.execute.i2.row.peId &&
    io.completion.bits.stid === completionEntry.request.execute.i2.row.stid &&
    io.completion.bits.tid === completionEntry.request.execute.i2.row.stid
  val completionAttemptExact = completionResident &&
    LoadAttemptIdentity.equal(io.completion.bits.payload.attempt,
      completionEntry.attempt)
  val completionTransactionExact = completionResident &&
    io.completion.bits.payload.transactionValid &&
    io.completion.bits.payload.transactionValue ===
      completionEntry.load.transaction.value &&
    io.completion.bits.payload.transactionGeneration ===
      completionEntry.load.transaction.generation
  val completionPipeExact = completionResident &&
    io.completion.bits.payload.pipeIndex === completionEntry.lane
  val completionDestinationExact = completionResident &&
    projectedDestinationExact(io.completion.bits.payload.dst,
      completionEntry.request)
  val completionOutcomeExact = io.completion.bits.payload.valid &&
    (!io.completion.bits.payload.faultValid ||
      io.completion.bits.payload.data === 0.U)
  val completionExact = completionIdValid && completionProducerExact &&
    completionAttemptExact && completionTransactionExact &&
    completionPipeExact && completionDestinationExact && completionOutcomeExact
  val completionCanceledAttemptExact = completionResident &&
    completionEntry.canceledAttemptValid &&
    LoadAttemptIdentity.equal(io.completion.bits.payload.attempt,
      completionEntry.canceledAttempt)
  val completionStaleAttempt = completionIdValid && completionProducerExact &&
    completionCanceledAttemptExact && completionTransactionExact &&
    completionPipeExact && completionDestinationExact && completionOutcomeExact

  val completionFence = recoveryFences(io.completion.bits.stid)
  io.result.valid := io.completion.valid && completionExact && !completionFence
  io.result.bits := 0.U.asTypeOf(io.result.bits)
  io.result.bits.agu := completionEntry.request
  io.result.bits.load := completionEntry.load
  io.result.bits.data := io.completion.bits.payload.data
  io.result.bits.faultValid := io.completion.bits.payload.faultValid
  io.result.bits.faultCause := io.completion.bits.payload.faultCause
  io.completion.ready := !completionFence &&
    ((completionExact && io.result.ready) || completionStaleAttempt)
  io.resultLane := completionEntry.lane

  val launchId = io.attemptLaunch.bits.loadId
  val launchIdWellFormed =
    LoadCanonicalRowIdentity.wellFormed(launchId, entriesCount)
  val launchIdValid = launchIdWellFormed && launchId.valid
  val launchIndex = launchId.slot(log2Ceil(entriesCount) - 1, 0)
  val launchEntry = entries(launchIndex)
  val launchResident = launchIdValid && launchEntry.valid &&
    LoadCanonicalRowIdentity.equal(launchEntry.loadId, launchId)
  val launchAttemptExact = launchResident &&
    LoadAttemptIdentity.equal(io.attemptLaunch.bits.attempt,
      launchEntry.attempt)
  val launchProducerExact = launchResident &&
    producerExact(launchEntry.load, launchEntry.request)
  val launchExact = launchIdValid && launchResident && launchAttemptExact &&
    launchProducerExact
  val launchFence = recoveryFences(
    io.attemptLaunch.bits.attempt.producer.stid)
  io.attemptLaunchAccepted :=
    io.attemptLaunch.valid && launchExact && !launchFence

  io.speculativeWakeup.foreach(_ := 0.U.asTypeOf(
    io.speculativeWakeup.head))
  for (lane <- 0 until laneCount) {
    when(io.attemptLaunchAccepted && launchEntry.lane === lane.U) {
      val destination = launchEntry.request.destination
      io.speculativeWakeup(lane).valid := true.B
      io.speculativeWakeup(lane).bits.kind :=
        OooIexWakeupKind.SpeculativeLoad
      io.speculativeWakeup(lane).bits.stid :=
        launchEntry.request.execute.i2.row.stid
      io.speculativeWakeup(lane).bits.epoch :=
        launchEntry.request.execute.i2.row.epoch
      io.speculativeWakeup(lane).bits.operandClass :=
        operandClass(destination)
      io.speculativeWakeup(lane).bits.ptag := destination.ptag
      io.speculativeWakeup(lane).bits.ptagGeneration :=
        destination.ptagGeneration
      io.speculativeWakeup(lane).bits.localTag := destination.localTag
      io.speculativeWakeup(lane).bits.localSequence :=
        destination.localSequence
      io.speculativeWakeup(lane).bits.load := launchEntry.load
    }
  }

  val rebindId = io.rebind.bits.loadId
  val rebindIdWellFormed =
    LoadCanonicalRowIdentity.wellFormed(rebindId, entriesCount)
  val rebindIdValid = rebindIdWellFormed && rebindId.valid
  val rebindIndex = rebindId.slot(log2Ceil(entriesCount) - 1, 0)
  val rebindEntry = entries(rebindIndex)
  val rebindResident = rebindIdValid && rebindEntry.valid &&
    LoadCanonicalRowIdentity.equal(rebindEntry.loadId, rebindId)
  val rebindCurrentExact = rebindResident &&
    rebindEntry.load.asUInt === io.rebind.bits.currentLoad.asUInt &&
    LoadAttemptIdentity.equal(rebindEntry.attempt,
      io.rebind.bits.currentAttempt) &&
    LoadAttemptIdentity.equal(io.rebind.bits.currentAttempt,
      toAttempt(io.rebind.bits.currentLoad))
  val rebindNextExact = rebindResident &&
    io.rebind.bits.nextLoad.valid &&
    io.rebind.bits.nextLoad.producer.asUInt === rebindEntry.load.producer.asUInt &&
    io.rebind.bits.nextLoad.transaction.asUInt ===
      rebindEntry.load.transaction.asUInt &&
    io.rebind.bits.nextLoad.generation === rebindEntry.load.generation + 1.U &&
    LoadAttemptIdentity.equal(io.rebind.bits.nextAttempt,
      toAttempt(io.rebind.bits.nextLoad))
  val completionTargetsRebind = io.completion.valid && completionIdValid &&
    LoadCanonicalRowIdentity.equal(completionId, rebindId)
  val rebindExact = rebindCurrentExact && rebindNextExact
  val rebindFence = recoveryFences(
    io.rebind.bits.currentLoad.producer.group.stid)
  val faultCancelPublish = io.completion.valid && completionExact &&
    io.completion.bits.payload.faultValid &&
    !completionEntry.faultCancelSent && !completionFence
  val cancelCollision = io.rebind.valid && rebindExact &&
    faultCancelPublish &&
    rebindEntry.lane === completionEntry.lane
  io.cancelCollision := cancelCollision
  io.rebind.ready := !rebindFence && rebindExact && !completionTargetsRebind &&
    !cancelCollision

  io.loadCancel.foreach(_ := 0.U.asTypeOf(io.loadCancel.head))
  for (lane <- 0 until laneCount) {
    when(io.rebind.fire && rebindEntry.lane === lane.U) {
      io.loadCancel(lane).valid := true.B
      io.loadCancel(lane).bits.stid := rebindEntry.request.execute.i2.row.stid
      io.loadCancel(lane).bits.epoch := rebindEntry.request.execute.i2.row.epoch
      io.loadCancel(lane).bits.load := io.rebind.bits.currentLoad
    }
    when(faultCancelPublish &&
        completionEntry.lane === lane.U) {
      io.loadCancel(lane).valid := true.B
      io.loadCancel(lane).bits.stid :=
        completionEntry.request.execute.i2.row.stid
      io.loadCancel(lane).bits.epoch :=
        completionEntry.request.execute.i2.row.epoch
      io.loadCancel(lane).bits.load := completionEntry.load
    }
  }

  io.loadBypass.foreach(_ := 0.U.asTypeOf(io.loadBypass.head))
  for (lane <- 0 until laneCount) {
    when(io.result.valid && !io.result.bits.faultValid &&
        completionEntry.lane === lane.U) {
      val destination = completionEntry.request.destination
      io.loadBypass(lane).valid := true.B
      io.loadBypass(lane).bits.stid :=
        completionEntry.request.execute.i2.row.stid
      io.loadBypass(lane).bits.epoch :=
        completionEntry.request.execute.i2.row.epoch
      io.loadBypass(lane).bits.producer := completionEntry.load.producer
      io.loadBypass(lane).bits.operandClass := operandClass(destination)
      io.loadBypass(lane).bits.ptag := destination.ptag
      io.loadBypass(lane).bits.ptagGeneration :=
        destination.ptagGeneration
      io.loadBypass(lane).bits.localTag := destination.localTag
      io.loadBypass(lane).bits.localSequence := destination.localSequence
      io.loadBypass(lane).bits.load := completionEntry.load
      io.loadBypass(lane).bits.stage := OooIexBypassStage.W1
      io.loadBypass(lane).bits.data := io.result.bits.data
    }
  }

  io.recoveryPrepared.valid := preparedValid
  io.recoveryPrepared.bits := preparedPlan
  io.recoveryRejected :=
    io.recoveryPrepare.valid && !io.recoveryPrepareReady
  io.recoveryKilledMask := Mux(preparedValid,
    preparedKilledMask, Mux(recoveryPrepareFire,
      recoveryKill.asUInt, 0.U))

  val recoveryTerminalConflict =
    io.recoveryApply.valid && io.recoveryAbort.valid
  val recoveryApplyExact = preparedValid && !recoveryTerminalConflict &&
    io.recoveryApply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recoveryApply.bits, preparedPlan)
  io.recoveryApplyAccepted := io.recoveryApply.valid && recoveryApplyExact
  io.recoveryApplyRejected := io.recoveryApply.valid && !recoveryApplyExact

  val recoveryAbortExact = preparedValid && !recoveryTerminalConflict &&
    io.recoveryAbort.bits.phase === RecoveryPhase.Abort &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recoveryAbort.bits, preparedPlan)
  io.recoveryAbortAccepted := io.recoveryAbort.valid && recoveryAbortExact
  io.recoveryAbortRejected := io.recoveryAbort.valid && !recoveryAbortExact

  private def rejectDefaults(
      out: Valid[OooIexLoadTerminalMetadataReject]): Unit = {
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
    out.bits.loadId := LoadCanonicalRowIdentity.none
  }
  rejectDefaults(io.allocRejected)
  rejectDefaults(io.rebindRejected)
  rejectDefaults(io.attemptLaunchRejected)
  rejectDefaults(io.completionRejected)

  io.allocRejected.valid := io.alloc.valid && !allocFence && !io.alloc.ready
  io.allocRejected.bits.loadId := io.alloc.bits.loadId
  io.allocRejected.bits.member := io.alloc.bits.load.producer
  io.allocRejected.bits.rowIdExact := allocIdValid
  io.allocRejected.bits.resident := allocResident
  io.allocRejected.bits.producerExact := allocProducerExact
  io.allocRejected.bits.attemptExact :=
    allocAttemptExact && allocInitialAttemptExact
  io.allocRejected.bits.destinationExact := true.B
  io.allocRejected.bits.outcomeExact := allocLaneExact

  io.rebindRejected.valid :=
    io.rebind.valid && !rebindFence && !io.rebind.ready
  io.rebindRejected.bits.loadId := rebindId
  io.rebindRejected.bits.member := io.rebind.bits.currentLoad.producer
  io.rebindRejected.bits.rowIdExact := rebindIdValid
  io.rebindRejected.bits.resident := rebindResident
  io.rebindRejected.bits.producerExact := rebindCurrentExact
  io.rebindRejected.bits.attemptExact := rebindNextExact
  io.rebindRejected.bits.destinationExact := true.B
  io.rebindRejected.bits.outcomeExact :=
    !completionTargetsRebind && !cancelCollision

  io.attemptLaunchRejected.valid := io.attemptLaunch.valid &&
    !launchFence && !launchExact
  io.attemptLaunchRejected.bits.loadId := launchId
  io.attemptLaunchRejected.bits.member := launchEntry.load.producer
  io.attemptLaunchRejected.bits.rowIdExact := launchIdValid
  io.attemptLaunchRejected.bits.resident := launchResident
  io.attemptLaunchRejected.bits.producerExact := launchProducerExact
  io.attemptLaunchRejected.bits.attemptExact := launchAttemptExact
  io.attemptLaunchRejected.bits.destinationExact := true.B
  io.attemptLaunchRejected.bits.outcomeExact := true.B

  io.completionRejected.valid :=
    io.completion.valid && !completionFence && !completionExact
  io.completionRejected.bits.loadId := completionId
  io.completionRejected.bits.member := completionEntry.load.producer
  io.completionRejected.bits.rowIdExact := completionIdValid
  io.completionRejected.bits.resident := completionResident
  io.completionRejected.bits.producerExact := completionProducerExact
  io.completionRejected.bits.attemptExact := completionAttemptExact
  io.completionRejected.bits.transactionExact := completionTransactionExact
  io.completionRejected.bits.pipeExact := completionPipeExact
  io.completionRejected.bits.destinationExact := completionDestinationExact
  io.completionRejected.bits.outcomeExact := completionOutcomeExact

  when(recoveryPrepareFire) {
    preparedValid := true.B
    preparedPlan := io.recoveryPrepare.bits
    preparedKilledMask := recoveryKill.asUInt
  }
  when(io.recoveryApplyAccepted || io.recoveryAbortAccepted) {
    preparedValid := false.B
  }
  when(io.result.fire) {
    entries(completionIndex) := zeroEntry
  }
  when(io.rebind.fire) {
    entries(rebindIndex).canceledAttemptValid := true.B
    entries(rebindIndex).canceledAttempt := io.rebind.bits.currentAttempt
    entries(rebindIndex).attempt := io.rebind.bits.nextAttempt
    entries(rebindIndex).load := io.rebind.bits.nextLoad
    entries(rebindIndex).faultCancelSent := false.B
  }
  when(io.completion.fire && completionStaleAttempt) {
    entries(completionIndex).canceledAttemptValid := false.B
  }
  when(faultCancelPublish && !io.result.fire) {
    entries(completionIndex).faultCancelSent := true.B
  }
  when(io.alloc.fire) {
    entries(allocIndex).valid := true.B
    entries(allocIndex).loadId := io.alloc.bits.loadId
    entries(allocIndex).attempt := io.alloc.bits.attempt
    entries(allocIndex).canceledAttemptValid := false.B
    entries(allocIndex).canceledAttempt := LoadAttemptIdentity.none
    entries(allocIndex).load := io.alloc.bits.load
    entries(allocIndex).request := io.alloc.bits.request
    entries(allocIndex).lane := io.alloc.bits.lane
    entries(allocIndex).faultCancelSent := false.B
  }
  when(io.recoveryApplyAccepted) {
    for (index <- 0 until entriesCount) {
      when(preparedKilledMask(index)) {
        entries(index) := zeroEntry
      }
    }
  }

  io.occupied := PopCount(VecInit(entries.map(_.valid)))
  io.empty := !VecInit(entries.map(_.valid)).asUInt.orR

  when(io.result.fire) {
    assert(io.completion.fire,
      "canonical LSU completion and OOO terminal result must share owner release")
  }
}
