package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, Valid, log2Ceil}

import linxcore.common.{CoreParams, DestinationKind}
import linxcore.lsu.{LoadAttemptIdentity, LoadCanonicalRowIdentity,
  LoadReplayDestination, LoadTerminalFault, ScalarLSULoadReturnEntry}

/** Metadata installed atomically beside one canonical LIQ allocation.
  *
  * Address, miss, replay, data, and request residency remain solely in the
  * scalar LSU.  This sidecar retains only the typed OOO execution context that
  * the architectural terminal sink must revalidate after the LIQ row has
  * transferred into LRET/W1/W2.
  */
class OooIexLoadTerminalMetadataAlloc(
    val p: OooParams,
    val coreParams: CoreParams) extends Bundle {
  val loadId = new LoadCanonicalRowIdentity
  val attempt = new LoadAttemptIdentity
  val load = new OooIexLoadGeneration(p)
  val request = new OooIexAguLoadRequest(p)
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
  val destinationExact = Bool()
  val outcomeExact = Bool()
}

class OooIexLoadTerminalMetadataEntry(
    val p: OooParams,
    val coreParams: CoreParams) extends Bundle {
  val valid = Bool()
  val loadId = new LoadCanonicalRowIdentity
  val attempt = new LoadAttemptIdentity
  val load = new OooIexLoadGeneration(p)
  val request = new OooIexAguLoadRequest(p)
}

class OooIexLoadTerminalMetadataIO(
    val p: OooParams,
    val coreParams: CoreParams) extends Bundle {
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

  val flush = Input(Bool())
  val alloc = Flipped(Decoupled(
    new OooIexLoadTerminalMetadataAlloc(p, coreParams)))
  val rebind = Flipped(Decoupled(
    new OooIexLoadTerminalMetadataRebind(p, coreParams)))
  val completion = Flipped(Decoupled(completionType))
  val result = Decoupled(new OooIexLoadResult(p))

  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryRejected = Output(Bool())
  val recoveryKilledMask = Output(UInt(lsu.liqEntries.W))
  val recoveryFire = Input(Bool())

  val allocRejected = Valid(new OooIexLoadTerminalMetadataReject(p, coreParams))
  val rebindRejected = Valid(new OooIexLoadTerminalMetadataReject(p, coreParams))
  val completionRejected = Valid(new OooIexLoadTerminalMetadataReject(p, coreParams))
  val occupied = Output(UInt(log2Ceil(lsu.liqEntries + 1).W))
  val empty = Output(Bool())
}

/** Exact metadata-only owner joining canonical LSU completion to OOO terminal.
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
        stidCount = 4, loadReturnPipeCount = 3))) extends Module {
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

  val io = IO(new OooIexLoadTerminalMetadataIO(p, coreParams))

  private def zeroEntry: OooIexLoadTerminalMetadataEntry = {
    val entry = Wire(new OooIexLoadTerminalMetadataEntry(p, coreParams))
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
      load.producer.asUInt === row.member.asUInt &&
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

  private def sidecarExact(
      entry: OooIexLoadTerminalMetadataEntry,
      index: Int): Bool =
    !entry.valid || (
      LoadCanonicalRowIdentity.wellFormed(entry.loadId, entriesCount) &&
      entry.loadId.valid && entry.loadId.slot === index.U &&
      LoadAttemptIdentity.equal(entry.attempt, toAttempt(entry.load)) &&
      producerExact(entry.load, entry.request))

  val entries = RegInit(VecInit(Seq.fill(entriesCount)(zeroEntry)))
  val fence = io.flush || io.recoveryPrepare.valid

  val allocIdWellFormed =
    LoadCanonicalRowIdentity.wellFormed(io.alloc.bits.loadId, entriesCount)
  val allocIdValid = allocIdWellFormed && io.alloc.bits.loadId.valid
  val allocIndex = io.alloc.bits.loadId.slot(log2Ceil(entriesCount) - 1, 0)
  val allocResident = allocIdValid && entries(allocIndex).valid
  val allocProducerExact = producerExact(io.alloc.bits.load, io.alloc.bits.request)
  val allocAttemptExact = LoadAttemptIdentity.equal(
    io.alloc.bits.attempt, toAttempt(io.alloc.bits.load))
  val allocExact = allocIdValid && allocProducerExact && allocAttemptExact
  io.alloc.ready := !fence && allocExact && !allocResident

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
  val completionDestinationExact = completionResident &&
    projectedDestinationExact(io.completion.bits.payload.dst,
      completionEntry.request)
  val completionOutcomeExact = io.completion.bits.payload.valid &&
    (!io.completion.bits.payload.faultValid ||
      io.completion.bits.payload.data === 0.U)
  val completionExact = completionIdValid && completionProducerExact &&
    completionAttemptExact && completionDestinationExact &&
    completionOutcomeExact

  io.result.valid := io.completion.valid && completionExact && !fence
  io.result.bits := 0.U.asTypeOf(io.result.bits)
  io.result.bits.agu := completionEntry.request
  io.result.bits.load := completionEntry.load
  io.result.bits.data := io.completion.bits.payload.data
  io.result.bits.faultValid := io.completion.bits.payload.faultValid
  io.result.bits.faultCause := io.completion.bits.payload.faultCause
  io.completion.ready := io.result.ready && completionExact && !fence

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
    io.rebind.bits.nextLoad.generation === rebindEntry.load.generation + 1.U &&
    LoadAttemptIdentity.equal(io.rebind.bits.nextAttempt,
      toAttempt(io.rebind.bits.nextLoad))
  val completionTargetsRebind = io.completion.valid && completionIdValid &&
    LoadCanonicalRowIdentity.equal(completionId, rebindId)
  val rebindExact = rebindCurrentExact && rebindNextExact
  io.rebind.ready := !fence && rebindExact && !completionTargetsRebind

  val entryExact = VecInit(entries.zipWithIndex.map {
    case (entry, index) => sidecarExact(entry, index)
  })
  val recoveryPlanExact = io.recoveryPrepare.bits.valid &&
    io.recoveryPrepare.bits.oldHead.stid < p.stidCount.U && entryExact.asUInt.andR
  val recoveryKill = VecInit(entries.map(entry =>
    entry.valid && recoveryPlanExact &&
      OooRecoveryMembership.memberKilled(
        p, io.recoveryPrepare.bits, entry.load.producer)))
  io.recoveryPrepareReady := io.recoveryPrepare.valid && recoveryPlanExact
  io.recoveryRejected := io.recoveryPrepare.valid && !recoveryPlanExact
  io.recoveryKilledMask := Mux(io.recoveryPrepareReady,
    recoveryKill.asUInt, 0.U)

  private def rejectDefaults(
      out: Valid[OooIexLoadTerminalMetadataReject]): Unit = {
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
    out.bits.loadId := LoadCanonicalRowIdentity.none
  }
  rejectDefaults(io.allocRejected)
  rejectDefaults(io.rebindRejected)
  rejectDefaults(io.completionRejected)

  io.allocRejected.valid := io.alloc.valid && !fence && !io.alloc.ready
  io.allocRejected.bits.loadId := io.alloc.bits.loadId
  io.allocRejected.bits.member := io.alloc.bits.load.producer
  io.allocRejected.bits.rowIdExact := allocIdValid
  io.allocRejected.bits.resident := allocResident
  io.allocRejected.bits.producerExact := allocProducerExact
  io.allocRejected.bits.attemptExact := allocAttemptExact
  io.allocRejected.bits.destinationExact := true.B
  io.allocRejected.bits.outcomeExact := true.B

  io.rebindRejected.valid := io.rebind.valid && !fence && !io.rebind.ready
  io.rebindRejected.bits.loadId := rebindId
  io.rebindRejected.bits.member := io.rebind.bits.currentLoad.producer
  io.rebindRejected.bits.rowIdExact := rebindIdValid
  io.rebindRejected.bits.resident := rebindResident
  io.rebindRejected.bits.producerExact := rebindCurrentExact
  io.rebindRejected.bits.attemptExact := rebindNextExact
  io.rebindRejected.bits.destinationExact := true.B
  io.rebindRejected.bits.outcomeExact := !completionTargetsRebind

  io.completionRejected.valid := io.completion.valid && !fence && !completionExact
  io.completionRejected.bits.loadId := completionId
  io.completionRejected.bits.member := completionEntry.load.producer
  io.completionRejected.bits.rowIdExact := completionIdValid
  io.completionRejected.bits.resident := completionResident
  io.completionRejected.bits.producerExact := completionProducerExact
  io.completionRejected.bits.attemptExact := completionAttemptExact
  io.completionRejected.bits.destinationExact := completionDestinationExact
  io.completionRejected.bits.outcomeExact := completionOutcomeExact

  when(io.flush) {
    for (index <- 0 until entriesCount) {
      entries(index) := zeroEntry
    }
  }.elsewhen(io.recoveryFire) {
    assert(io.recoveryPrepare.valid && io.recoveryPrepareReady,
      "load terminal metadata recovery must share one accepted common fire")
    for (index <- 0 until entriesCount) {
      when(recoveryKill(index)) {
        entries(index) := zeroEntry
      }
    }
  }.otherwise {
    when(io.result.fire) {
      entries(completionIndex) := zeroEntry
    }
    when(io.rebind.fire) {
      entries(rebindIndex).attempt := io.rebind.bits.nextAttempt
      entries(rebindIndex).load := io.rebind.bits.nextLoad
    }
    when(io.alloc.fire) {
      entries(allocIndex).valid := true.B
      entries(allocIndex).loadId := io.alloc.bits.loadId
      entries(allocIndex).attempt := io.alloc.bits.attempt
      entries(allocIndex).load := io.alloc.bits.load
      entries(allocIndex).request := io.alloc.bits.request
    }
  }

  io.occupied := PopCount(VecInit(entries.map(_.valid)))
  io.empty := !VecInit(entries.map(_.valid)).asUInt.orR

  when(io.result.fire) {
    assert(io.completion.fire,
      "canonical LSU completion and OOO terminal result must share owner release")
  }
}
