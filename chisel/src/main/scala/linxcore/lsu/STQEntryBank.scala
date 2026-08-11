package linxcore.lsu

import chisel3._
import chisel3.util._

import linxcore.common.{DestinationKind, InterfaceParams, TULinkFlushSequenceSource}
import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

object STQStoreType extends ChiselEnum {
  val All, Addr, Data = Value
}

/** Generation-qualified semantic owner for one logical store beat.
  *
  * The physical STQ index is deliberately absent.  It identifies storage,
  * while this bundle identifies the ROB/BROB member allowed to mutate it.
  */
class STQExactOwner(
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val nativeBidWidth: Int = 8,
    val ridSlotWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val nativeBidValid = Bool()
  val nativeBid = UInt(nativeBidWidth.W)
  val brobGeneration = UInt(brobGenerationWidth.W)
  val ridSlot = UInt(ridSlotWidth.W)
  val ridGeneration = UInt(ridGenerationWidth.W)
  val memberIndex = UInt(memberIndexWidth.W)
  val residentGeneration = UInt(residentGenerationWidth.W)
}

/** Physical STQ capability.  A slot index is never sufficient without the
  * allocation generation returned by the canonical STQ owner.
  */
class STQPhysicalLease(
    val entries: Int,
    val generationWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val index = UInt(log2Ceil(entries).W)
  val generation = UInt(generationWidth.W)
}

class STQStoreRequest(
    val entries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val pcWidth: Int = 64,
    val lsidWidth: Int = 32,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8,
    val physicalStqEntries: Int = 0,
    val transactionIdWidth: Int = 64)
    extends Bundle {
  private val ridSlotWidth = log2Ceil(entries)
  private val leaseEntries = if (physicalStqEntries > 0) physicalStqEntries else entries
  val transactionId = UInt(transactionIdWidth.W)
  val storeType = STQStoreType()
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val bid = new ROBID(entries)
  val gid = new ROBID(entries)
  val rid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val lsIdFull = UInt(lsidWidth.W)
  val storeIdFullValid = Bool()
  val storeIdFull = UInt(lsidWidth.W)
  val logicalStoreValid = Bool()
  val logicalFirstLsid = UInt(lsidWidth.W)
  val logicalFirstStoreId = UInt(lsidWidth.W)
  val logicalRequestCount = UInt(2.W)
  val logicalBeat = UInt(1.W)
  val exactOwner = new STQExactOwner(
    peIdWidth, stidWidth, nativeBidWidth, ridSlotWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth)
  val lease = new STQPhysicalLease(leaseEntries, leaseGenerationWidth)
  val tSeq = new ROBID(mapQDepth)
  val uSeq = new ROBID(mapQDepth)
  val tuDstValid = Bool()
  val tuDstKind = DestinationKind()
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
  val size = UInt(sizeWidth.W)
  val stackValid = Bool()
  val scalarIex = Bool()
  val simtLane = UInt(simtLaneWidth.W)
}

class STQEntryBankRow(
    val entries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val pcWidth: Int = 64,
    val lsidWidth: Int = 32,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8,
    val transactionIdWidth: Int = 64)
    extends Bundle {
  private val ridSlotWidth = log2Ceil(entries)
  val transactionId = UInt(transactionIdWidth.W)
  val valid = Bool()
  val status = STQEntryStatus()
  val storeType = STQStoreType()
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val bid = new ROBID(entries)
  val gid = new ROBID(entries)
  val rid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val lsIdFull = UInt(lsidWidth.W)
  val storeIdFullValid = Bool()
  val storeIdFull = UInt(lsidWidth.W)
  val logicalStoreValid = Bool()
  val logicalFirstLsid = UInt(lsidWidth.W)
  val logicalFirstStoreId = UInt(lsidWidth.W)
  val logicalRequestCount = UInt(2.W)
  val logicalBeat = UInt(1.W)
  val exactOwner = new STQExactOwner(
    peIdWidth, stidWidth, nativeBidWidth, ridSlotWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth)
  val leaseGeneration = UInt(leaseGenerationWidth.W)
  val tSeq = new ROBID(mapQDepth)
  val uSeq = new ROBID(mapQDepth)
  val tuDstValid = Bool()
  val tuDstKind = DestinationKind()
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
  val size = UInt(sizeWidth.W)
  val stackValid = Bool()
  val scalarIex = Bool()
  val simtLane = UInt(simtLaneWidth.W)
  val addrReady = Bool()
  val dataReady = Bool()
}

/** Exact proof that the physical store-data owner finished one row. */
class STQDataCompletion(
    val entries: Int,
    val robEntries: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val nativeBidWidth: Int,
    val lsidWidth: Int,
    val ridGenerationWidth: Int,
    val brobGenerationWidth: Int,
    val memberIndexWidth: Int,
    val residentGenerationWidth: Int,
    val leaseGenerationWidth: Int)
    extends Bundle {
  private val ridSlotWidth = log2Ceil(robEntries)
  val lease = new STQPhysicalLease(entries, leaseGenerationWidth)
  val exactOwner = new STQExactOwner(
    peIdWidth, stidWidth, nativeBidWidth, ridSlotWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth)
  val lsIdFull = UInt(lsidWidth.W)
  val storeIdFull = UInt(lsidWidth.W)
}

class STQEntryBankIO(
    val entries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val robEntries: Int = 0,
    val lsidWidth: Int = 32,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8,
    val maxReserveBeats: Int = 2,
    val transactionIdWidth: Int = 64)
    extends Bundle {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  private val ptrWidth = log2Ceil(entries)
  private val countWidth = log2Ceil(entries + 1)
  private val sourceParams = InterfaceParams(robEntries = identityEntries)

  val flush = Input(new FlushBus(identityEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val exactRecoveryValid = Input(Bool())
  val exactRecoveryFreeMask = Input(UInt(entries.W))
  val exactRecoveryAcceptedMask = Output(UInt(entries.W))
  val exactRecoveryBlockedMask = Output(UInt(entries.W))
  val exactRecoveryFreeCount = Output(UInt(countWidth.W))
  val recoverySourceConflict = Output(Bool())

  val insertValid = Input(Bool())
  val insert = Input(new STQStoreRequest(
    identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
    sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth, entries,
    transactionIdWidth))
  val insertReady = Output(Bool())
  val insertAccepted = Output(Bool())
  val insertAllocated = Output(Bool())
  val insertMerged = Output(Bool())
  val insertConflict = Output(Bool())
  val insertIndex = Output(UInt(ptrWidth.W))
  val insertLease = Output(new STQPhysicalLease(entries, leaseGenerationWidth))

  /** Canonical path: reserve storage before STA/STD execute, then let either
    * half fill only the returned generation-qualified lease.
    */
  val reserveValid = Input(Bool())
  val reserve = Input(new STQStoreRequest(
    identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
    sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth, entries,
    transactionIdWidth))
  val reserveReady = Output(Bool())
  val reserveAccepted = Output(Bool())
  val reserveConflict = Output(Bool())
  val reserveLease = Output(new STQPhysicalLease(entries, leaseGenerationWidth))

  val reserveBatchValid = Input(Bool())
  val reserveBatchMask = Input(UInt(maxReserveBeats.W))
  val reserveBatch = Input(Vec(maxReserveBeats, new STQStoreRequest(
    identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
    sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth, entries,
    transactionIdWidth)))
  val reserveBatchReady = Output(Bool())
  val reserveBatchAccepted = Output(Bool())
  val reserveBatchConflict = Output(Bool())
  val reserveBatchLeases = Output(Vec(maxReserveBeats,
    new STQPhysicalLease(entries, leaseGenerationWidth)))

  val fillValid = Input(Bool())
  val fill = Input(new STQStoreRequest(
    identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
    sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth, entries,
    transactionIdWidth))
  val fillReady = Output(Bool())
  val fillAccepted = Output(Bool())
  val fillConflict = Output(Bool())

  /** Exact completions from the physical store-data owner.  Data storage is
    * deliberately outside this metadata/status bank; only a live matching
    * lease may publish dataReady here.
    */
  val dataCompletions = Input(Vec(2,
    Valid(new STQDataCompletion(
      entries, identityEntries, peIdWidth, stidWidth, nativeBidWidth,
      lsidWidth, ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
      residentGenerationWidth, leaseGenerationWidth))))
  val dataCompletionAccepted = Output(UInt(2.W))
  val dataCompletionRejected = Output(UInt(2.W))

  val markCommitValid = Input(Bool())
  val markCommitIndex = Input(UInt(ptrWidth.W))
  val markCommitAccepted = Output(Bool())
  val markCommitIgnored = Output(Bool())

  val commitFreeValid = Input(Bool())
  val commitFreeIndex = Input(UInt(ptrWidth.W))
  val commitFreeAccepted = Output(Bool())
  val commitFreeIgnored = Output(Bool())
  val commitFreeMaskValid = Input(Bool())
  val commitFreeMask = Input(UInt(entries.W))
  val commitFreeAcceptedMask = Output(UInt(entries.W))
  val commitFreeIgnoredMask = Output(UInt(entries.W))
  val commitFreeCount = Output(UInt(countWidth.W))

  val flushApplied = Output(Bool())
  val flushMatchMask = Output(UInt(entries.W))
  val flushFreeMask = Output(UInt(entries.W))
  val flushStatusBlockedMask = Output(UInt(entries.W))
  val flushFullLsIdRequiredMask = Output(UInt(entries.W))
  val flushFullLsIdMissingMask = Output(UInt(entries.W))
  val flushFullLsIdAmbiguousMask = Output(UInt(entries.W))
  val flushFreeCount = Output(UInt(countWidth.W))
  val lsuTULinkSource = Output(new TULinkFlushSequenceSource(sourceParams, mapQDepth, stidWidth))
  val lsuTULinkSourceMatched = Output(Bool())
  val lsuTULinkSourceMultipleMatch = Output(Bool())

  val rows = Output(Vec(entries, new STQEntryBankRow(
    identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
    sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth, transactionIdWidth)))
  val occupiedMask = Output(UInt(entries.W))
  val waitMask = Output(UInt(entries.W))
  val commitMask = Output(UInt(entries.W))
  val addrReadyMask = Output(UInt(entries.W))
  val dataReadyMask = Output(UInt(entries.W))

  val empty = Output(Bool())
  val full = Output(Bool())
  val stall = Output(Bool())
  val residentCount = Output(UInt(countWidth.W))
  val outstandingWaitCount = Output(UInt(countWidth.W))
}

object STQEntryBank {
  /** Compatibility tie-off for paths that still use CAM-based insert while
    * they migrate to reservation plus exact lease fills.
    */
  def disableCanonicalPorts(io: STQEntryBankIO): Unit = {
    io.exactRecoveryValid := false.B
    io.exactRecoveryFreeMask := 0.U
    io.reserveValid := false.B
    io.reserve := 0.U.asTypeOf(io.reserve)
    io.reserveBatchValid := false.B
    io.reserveBatchMask := 0.U
    io.reserveBatch := 0.U.asTypeOf(io.reserveBatch)
    io.fillValid := false.B
    io.fill := 0.U.asTypeOf(io.fill)
    io.dataCompletions := 0.U.asTypeOf(io.dataCompletions)
  }
}

class STQEntryBank(
    val entries: Int = 16,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val robEntries: Int = 0,
    val lsidWidth: Int = 32,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8,
    val maxReserveBeats: Int = 2,
    val transactionIdWidth: Int = 64)
    extends Module {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  require(entries > 1, "STQ entries must be greater than one")
  require((entries & (entries - 1)) == 0, "STQ entries must be a power of two")
  require(identityEntries > 1, "ROB entries must be greater than one")
  require((identityEntries & (identityEntries - 1)) == 0, "ROB entries must be a power of two")
  require(lsidWidth >= 2, "LSID width must support modular serial ordering")
  require(leaseGenerationWidth > 0, "STQ lease generation width must be positive")
  require(maxReserveBeats >= 2 && maxReserveBeats <= entries,
    "canonical scalar STQ atomic reservation width must fit its capacity")

  private val countWidth = log2Ceil(entries + 1)

  private val sourceParams = InterfaceParams(robEntries = identityEntries)

  val io = IO(new STQEntryBankIO(
    entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth,
    simtLaneWidth, mapQDepth, identityEntries, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth, maxReserveBeats,
    transactionIdWidth))

  private def rowBundle: STQEntryBankRow =
    new STQEntryBankRow(
      identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
      sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth, nativeBidWidth,
      ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
      residentGenerationWidth, leaseGenerationWidth, transactionIdWidth)

  private def zeroRow: STQEntryBankRow = {
    val row = Wire(rowBundle)
    row := 0.U.asTypeOf(row)
    row.status := STQEntryStatus.Idle
    row
  }

  private def zeroSource: TULinkFlushSequenceSource = {
    val source = Wire(new TULinkFlushSequenceSource(sourceParams, mapQDepth, stidWidth))
    source := 0.U.asTypeOf(source)
    source
  }

  private def requestToRow(
      req: STQStoreRequest,
      leaseGeneration: UInt,
      reserved: Bool = false.B): STQEntryBankRow = {
    val row = Wire(rowBundle)
    row := 0.U.asTypeOf(row)
    row.transactionId := req.transactionId
    row.valid := true.B
    row.status := STQEntryStatus.Wait
    row.storeType := req.storeType
    row.peId := req.peId
    row.stid := req.stid
    row.tid := req.tid
    row.bid := req.bid
    row.gid := req.gid
    row.rid := req.rid
    row.lsId := req.lsId
    row.lsIdFull := req.lsIdFull
    row.storeIdFullValid := req.storeIdFullValid
    row.storeIdFull := req.storeIdFull
    row.logicalStoreValid := req.logicalStoreValid
    row.logicalFirstLsid := req.logicalFirstLsid
    row.logicalFirstStoreId := req.logicalFirstStoreId
    row.logicalRequestCount := req.logicalRequestCount
    row.logicalBeat := req.logicalBeat
    row.exactOwner := req.exactOwner
    row.leaseGeneration := leaseGeneration
    row.tSeq := req.tSeq
    row.uSeq := req.uSeq
    row.tuDstValid := req.tuDstValid
    row.tuDstKind := req.tuDstKind
    row.pc := req.pc
    row.addr := req.addr
    row.data := req.data
    row.size := req.size
    row.stackValid := req.stackValid
    row.scalarIex := req.scalarIex
    row.simtLane := req.simtLane
    row.addrReady := !reserved &&
      ((req.storeType === STQStoreType.All) || (req.storeType === STQStoreType.Addr))
    row.dataReady := !reserved &&
      ((req.storeType === STQStoreType.All) || (req.storeType === STQStoreType.Data))
    row
  }

  private def mergeRow(row: STQEntryBankRow, req: STQStoreRequest): STQEntryBankRow = {
    val out = Wire(rowBundle)
    out := row
    out.storeType := STQStoreType.All
    out.stackValid := row.stackValid || req.stackValid
    when(req.storeType === STQStoreType.Addr) {
      out.addrReady := true.B
      out.addr := req.addr
      out.size := req.size
    }
    when(req.storeType === STQStoreType.Data) {
      out.dataReady := true.B
      out.data := req.data
    }
    out
  }

  private def fillRow(row: STQEntryBankRow, req: STQStoreRequest): STQEntryBankRow = {
    val out = Wire(rowBundle)
    out := row
    out.stackValid := row.stackValid || req.stackValid
    when((req.storeType === STQStoreType.All) ||
      (req.storeType === STQStoreType.Addr)) {
      out.addrReady := true.B
      out.addr := req.addr
      out.size := req.size
    }
    when((req.storeType === STQStoreType.All) ||
      (req.storeType === STQStoreType.Data)) {
      out.dataReady := true.B
      out.data := req.data
    }
    when(out.addrReady && out.dataReady) {
      out.storeType := STQStoreType.All
    }.elsewhen(out.addrReady) {
      out.storeType := STQStoreType.Addr
    }.otherwise {
      out.storeType := STQStoreType.Data
    }
    out
  }

  val rows = RegInit(VecInit(Seq.fill(entries)(zeroRow)))
  val leaseGenerations = RegInit(VecInit(Seq.fill(entries)(0.U(leaseGenerationWidth.W))))
  val residentCount = RegInit(0.U(countWidth.W))
  val outstandingWaitCount = RegInit(0.U(countWidth.W))

  val occupiedVec = Wire(Vec(entries, Bool()))
  val waitVec = Wire(Vec(entries, Bool()))
  val commitVec = Wire(Vec(entries, Bool()))
  val addrReadyVec = Wire(Vec(entries, Bool()))
  val dataReadyVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    occupiedVec(idx) := rows(idx).valid
    waitVec(idx) := rows(idx).valid && (rows(idx).status === STQEntryStatus.Wait)
    commitVec(idx) := rows(idx).valid && (rows(idx).status === STQEntryStatus.Commit)
    addrReadyVec(idx) := rows(idx).valid && rows(idx).addrReady
    dataReadyVec(idx) := rows(idx).valid && rows(idx).dataReady
    io.rows(idx) := rows(idx)
  }

  io.occupiedMask := occupiedVec.asUInt
  io.waitMask := waitVec.asUInt
  io.commitMask := commitVec.asUInt
  io.addrReadyMask := addrReadyVec.asUInt
  io.dataReadyMask := dataReadyVec.asUInt
  io.empty := residentCount === 0.U
  io.full := residentCount === entries.U
  io.stall := io.full && (outstandingWaitCount === residentCount)
  io.residentCount := residentCount
  io.outstandingWaitCount := outstandingWaitCount

  val flushPrune = Module(new STQFlushPrune(
    entries, peIdWidth, stidWidth, tidWidth, identityEntries, lsidWidth))
  flushPrune.io.flush := io.flush
  for (idx <- 0 until entries) {
    flushPrune.io.rows(idx).valid := rows(idx).valid
    flushPrune.io.rows(idx).status := rows(idx).status
    flushPrune.io.rows(idx).peId := rows(idx).peId
    flushPrune.io.rows(idx).stid := rows(idx).stid
    flushPrune.io.rows(idx).tid := rows(idx).tid
    flushPrune.io.rows(idx).bid := rows(idx).bid
    flushPrune.io.rows(idx).gid := rows(idx).gid
    flushPrune.io.rows(idx).lsId := rows(idx).lsId
    flushPrune.io.rows(idx).lsIdFullValid := true.B
    flushPrune.io.rows(idx).lsIdFull := rows(idx).lsIdFull
  }

  val exactRecoveryAcceptedVec = Wire(Vec(entries, Bool()))
  val exactRecoveryBlockedVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    val requested = io.exactRecoveryValid && io.exactRecoveryFreeMask(idx)
    val exactRow = rows(idx).valid && rows(idx).exactOwner.valid &&
      (rows(idx).status === STQEntryStatus.Wait)
    exactRecoveryAcceptedVec(idx) := requested && exactRow
    exactRecoveryBlockedVec(idx) := requested && !exactRow
  }
  val recoverySourceConflict =
    io.exactRecoveryValid && io.flush.req.valid
  val appliedRecoveryFreeMask = Mux(
    io.exactRecoveryValid,
    exactRecoveryAcceptedVec.asUInt,
    flushPrune.io.freeMask)
  val flushApplied = appliedRecoveryFreeMask.orR
  val recoveryActive = io.flush.req.valid || io.exactRecoveryValid
  io.exactRecoveryAcceptedMask := exactRecoveryAcceptedVec.asUInt
  io.exactRecoveryBlockedMask := exactRecoveryBlockedVec.asUInt
  io.exactRecoveryFreeCount := PopCount(exactRecoveryAcceptedVec)
  io.recoverySourceConflict := recoverySourceConflict
  io.flushApplied := flushApplied
  io.flushMatchMask := flushPrune.io.matchMask
  io.flushFreeMask := flushPrune.io.freeMask
  io.flushStatusBlockedMask := flushPrune.io.statusBlockedMask
  io.flushFullLsIdRequiredMask := flushPrune.io.fullLsIdRequiredMask
  io.flushFullLsIdMissingMask := flushPrune.io.fullLsIdMissingMask
  io.flushFullLsIdAmbiguousMask := flushPrune.io.fullLsIdAmbiguousMask
  io.flushFreeCount := flushPrune.io.freeCount

  val lsuSourceMatchVec = Wire(Vec(entries, Bool()))
  val sourceRequired = io.flush.req.valid && !io.flush.baseOnBid
  for (idx <- 0 until entries) {
    lsuSourceMatchVec(idx) :=
      sourceRequired &&
        rows(idx).valid &&
        ROBID.equal(rows(idx).bid, io.flush.req.bid) &&
        ROBID.equal(rows(idx).rid, io.flush.req.rid) &&
        (rows(idx).stid === io.flush.req.stid)
  }

  val lsuSourceSelected = Wire(rowBundle)
  lsuSourceSelected := zeroRow
  for (idx <- 0 until entries) {
    when(lsuSourceMatchVec(idx)) {
      lsuSourceSelected := rows(idx)
    }
  }

  val lsuSource = Wire(new TULinkFlushSequenceSource(sourceParams, mapQDepth, stidWidth))
  lsuSource := zeroSource
  lsuSource.valid := lsuSourceMatchVec.asUInt.orR
  lsuSource.bid := lsuSourceSelected.bid
  lsuSource.rid := lsuSourceSelected.rid
  lsuSource.stid := lsuSourceSelected.stid
  lsuSource.tSeq := lsuSourceSelected.tSeq
  lsuSource.uSeq := lsuSourceSelected.uSeq
  lsuSource.dstValid := lsuSourceSelected.tuDstValid
  lsuSource.dstKind := lsuSourceSelected.tuDstKind

  io.lsuTULinkSource := lsuSource
  io.lsuTULinkSourceMatched := lsuSource.valid
  io.lsuTULinkSourceMultipleMatch := PopCount(lsuSourceMatchVec) > 1.U

  val fillIndex = io.fill.lease.index
  val fillTarget = rows(fillIndex)
  val dataCompletionAcceptedVec = Wire(Vec(2, Bool()))
  val dataCompletionRejectedVec = Wire(Vec(2, Bool()))
  for (port <- 0 until 2) {
    val completion = io.dataCompletions(port)
    val row = rows(completion.bits.lease.index)
    val exact = completion.bits.lease.valid && row.valid &&
      row.status === STQEntryStatus.Wait && !row.dataReady &&
      completion.bits.lease.generation === row.leaseGeneration &&
      completion.bits.exactOwner.asUInt === row.exactOwner.asUInt &&
      completion.bits.lsIdFull === row.lsIdFull &&
      row.storeIdFullValid &&
      completion.bits.storeIdFull === row.storeIdFull
    val duplicateTarget = if (port == 0) false.B else {
      dataCompletionAcceptedVec(0) &&
        io.dataCompletions(0).bits.lease.index === completion.bits.lease.index
    }
    dataCompletionAcceptedVec(port) := completion.valid && exact &&
      !duplicateTarget && !recoveryActive
    dataCompletionRejectedVec(port) := completion.valid &&
      !dataCompletionAcceptedVec(port)
  }
  io.dataCompletionAccepted := dataCompletionAcceptedVec.asUInt
  io.dataCompletionRejected := dataCompletionRejectedVec.asUInt
  val dataCompletionTargetMask = VecInit((0 until 2).map { port =>
    Mux(dataCompletionAcceptedVec(port),
      UIntToOH(io.dataCompletions(port).bits.lease.index, entries), 0.U)
  }).reduce(_ | _)
  val fillOwnerMatches =
    io.fill.exactOwner.valid &&
      fillTarget.exactOwner.valid &&
      (io.fill.exactOwner.asUInt === fillTarget.exactOwner.asUInt)
  val fillIdentityMatches =
    io.fill.lease.valid &&
      fillTarget.valid &&
      (fillTarget.status === STQEntryStatus.Wait) &&
      (io.fill.lease.generation === fillTarget.leaseGeneration) &&
      fillOwnerMatches &&
      (io.fill.lsIdFull === fillTarget.lsIdFull) &&
      io.fill.storeIdFullValid &&
      fillTarget.storeIdFullValid &&
      (io.fill.storeIdFull === fillTarget.storeIdFull) &&
      io.fill.logicalStoreValid && fillTarget.logicalStoreValid &&
      (io.fill.logicalFirstLsid === fillTarget.logicalFirstLsid) &&
      (io.fill.logicalFirstStoreId === fillTarget.logicalFirstStoreId) &&
      (io.fill.logicalRequestCount === fillTarget.logicalRequestCount) &&
      (io.fill.logicalBeat === fillTarget.logicalBeat)
  val fillPartAvailable = MuxLookup(io.fill.storeType, false.B)(Seq(
    STQStoreType.All -> (!fillTarget.addrReady && !fillTarget.dataReady),
    STQStoreType.Addr -> !fillTarget.addrReady,
    STQStoreType.Data -> !fillTarget.dataReady
  ))
  io.fillReady := !recoveryActive && fillIdentityMatches && fillPartAvailable &&
    !dataCompletionTargetMask(fillIndex)
  io.fillAccepted := io.fillValid && io.fillReady
  io.fillConflict := io.fillValid && !io.fillReady

  val freeMask = ~occupiedVec.asUInt
  val freeAvailable = freeMask.orR
  val freeIndex = PriorityEncoder(freeMask)
  val firstFreeOH = PriorityEncoderOH(freeMask)
  val secondFreeMask = freeMask & ~firstFreeOH
  val secondFreeIndex = PriorityEncoder(secondFreeMask)

  private def exactRequestIdentityValid(req: STQStoreRequest): Bool =
    req.exactOwner.valid &&
      req.exactOwner.nativeBidValid &&
      (req.exactOwner.peId === req.peId) &&
      (req.exactOwner.stid === req.stid) &&
      req.storeIdFullValid && req.logicalStoreValid &&
      ((req.logicalRequestCount === 1.U) ||
        (req.logicalRequestCount === 2.U)) &&
      (req.logicalBeat < req.logicalRequestCount) &&
      (req.lsIdFull === req.logicalFirstLsid + req.logicalBeat) &&
      (req.storeIdFull === req.logicalFirstStoreId + req.logicalBeat)

  private def duplicatesResident(req: STQStoreRequest): Bool =
    VecInit(rows.map { row =>
      row.valid &&
        row.exactOwner.valid &&
        (row.exactOwner.asUInt === req.exactOwner.asUInt) &&
        (row.lsIdFull === req.lsIdFull) &&
        row.storeIdFullValid &&
        (row.storeIdFull === req.storeIdFull)
    }).asUInt.orR

  val batchMaskLegal = io.reserveBatchMask.orR &&
    (io.reserveBatchMask & (io.reserveBatchMask + 1.U)) === 0.U
  val batchIdentityValid = VecInit((0 until maxReserveBeats).map { beat =>
    !io.reserveBatchMask(beat) || exactRequestIdentityValid(io.reserveBatch(beat))
  }).asUInt.andR
  val batchLogicalExact = (1 until maxReserveBeats).map { beat =>
    val current = io.reserveBatch(beat)
    val previous = io.reserveBatch(beat - 1)
    val continuesLogical = current.exactOwner.asUInt ===
      previous.exactOwner.asUInt
    !io.reserveBatchMask(beat) || Mux(continuesLogical,
      current.logicalFirstLsid === previous.logicalFirstLsid &&
        current.logicalFirstStoreId === previous.logicalFirstStoreId &&
        current.logicalRequestCount === previous.logicalRequestCount &&
        current.logicalBeat === previous.logicalBeat + 1.U,
      current.logicalBeat === 0.U)
  }.reduce(_ && _)
  val batchSerialConsecutive = (1 until maxReserveBeats).map { beat =>
    !io.reserveBatchMask(beat) ||
      (io.reserveBatch(beat).lsIdFull ===
        io.reserveBatch(beat - 1).lsIdFull + 1.U) &&
      (io.reserveBatch(beat).storeIdFull ===
        io.reserveBatch(beat - 1).storeIdFull + 1.U)
  }.reduce(_ && _)
  val batchDuplicatesResident = VecInit((0 until maxReserveBeats).map { beat =>
    io.reserveBatchMask(beat) && duplicatesResident(io.reserveBatch(beat))
  }).asUInt.orR
  val batchInternalDuplicate = (for {
    left <- 0 until maxReserveBeats
    right <- left + 1 until maxReserveBeats
  } yield io.reserveBatchMask(left) && io.reserveBatchMask(right) &&
    io.reserveBatch(left).exactOwner.asUInt ===
      io.reserveBatch(right).exactOwner.asUInt &&
    io.reserveBatch(left).lsIdFull === io.reserveBatch(right).lsIdFull &&
    io.reserveBatch(left).storeIdFull ===
      io.reserveBatch(right).storeIdFull).reduce(_ || _)
  val batchShapeValid = batchMaskLegal && batchIdentityValid &&
    batchLogicalExact && batchSerialConsecutive &&
    !batchDuplicatesResident && !batchInternalDuplicate
  val batchFreeEnough = PopCount(freeMask) >= PopCount(io.reserveBatchMask)
  io.reserveBatchReady :=
    !recoveryActive && !io.fillAccepted && batchShapeValid && batchFreeEnough
  io.reserveBatchAccepted := io.reserveBatchValid && io.reserveBatchReady
  io.reserveBatchConflict := io.reserveBatchValid && !batchShapeValid
  val batchIndices = Wire(Vec(maxReserveBeats, UInt(log2Ceil(entries).W)))
  val batchRemainingFree = Wire(Vec(maxReserveBeats + 1, UInt(entries.W)))
  batchRemainingFree(0) := freeMask
  for (beat <- 0 until maxReserveBeats) {
    batchIndices(beat) := PriorityEncoder(batchRemainingFree(beat))
    batchRemainingFree(beat + 1) := batchRemainingFree(beat) &
      ~UIntToOH(batchIndices(beat), entries)
    val generation = leaseGenerations(batchIndices(beat)) + 1.U
    io.reserveBatchLeases(beat).valid :=
      io.reserveBatchAccepted && io.reserveBatchMask(beat)
    io.reserveBatchLeases(beat).index := batchIndices(beat)
    io.reserveBatchLeases(beat).generation := generation
  }

  val reserveIdentityValid = exactRequestIdentityValid(io.reserve)
  val reserveDuplicate = VecInit(rows.map { row =>
    row.valid &&
      row.exactOwner.valid &&
      (row.exactOwner.asUInt === io.reserve.exactOwner.asUInt) &&
      (row.lsIdFull === io.reserve.lsIdFull) &&
      row.storeIdFullValid &&
      (row.storeIdFull === io.reserve.storeIdFull)
  }).asUInt.orR
  io.reserveReady :=
    !recoveryActive && !io.fillAccepted && !io.reserveBatchValid &&
      reserveIdentityValid &&
      !reserveDuplicate && freeAvailable
  io.reserveAccepted := io.reserveValid && io.reserveReady
  io.reserveConflict := io.reserveValid &&
    (!reserveIdentityValid || reserveDuplicate)
  val reserveGeneration = leaseGenerations(freeIndex) + 1.U
  io.reserveLease.valid := io.reserveAccepted
  io.reserveLease.index := freeIndex
  io.reserveLease.generation := reserveGeneration

  val insertProbe = Module(new STQInsertProbe(
    entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth,
    simtLaneWidth, mapQDepth, identityEntries, lsidWidth))
  insertProbe.io.requestValid := io.insertValid
  insertProbe.io.request := io.insert
  insertProbe.io.rows := rows
  insertProbe.io.flushApplied := flushApplied

  val mergeIndex = insertProbe.io.mergeIndex
  val allocateIndex = insertProbe.io.allocateIndex

  io.insertConflict := insertProbe.io.conflict
  io.insertReady := insertProbe.io.ready && !recoveryActive &&
    !io.fillAccepted && !io.reserveBatchValid && !io.reserveValid
  io.insertAccepted := io.insertValid && io.insertReady
  io.insertMerged := io.insertAccepted && insertProbe.io.canMerge
  io.insertAllocated := io.insertAccepted && !insertProbe.io.canMerge
  io.insertIndex := Mux(io.insertMerged, mergeIndex, allocateIndex)
  val insertAllocateGeneration = leaseGenerations(allocateIndex) + 1.U
  io.insertLease.valid := io.insertAccepted
  io.insertLease.index := io.insertIndex
  io.insertLease.generation := Mux(
    io.insertMerged, rows(mergeIndex).leaseGeneration, insertAllocateGeneration)

  val markCommitRow = rows(io.markCommitIndex)
  val markCommitLocalReady =
    markCommitRow.valid &&
      (markCommitRow.status === STQEntryStatus.Wait) &&
      markCommitRow.addrReady &&
      markCommitRow.dataReady &&
      (markCommitRow.storeType === STQStoreType.All)
  io.markCommitAccepted := !recoveryActive && io.markCommitValid && markCommitLocalReady
  io.markCommitIgnored := io.markCommitValid && (!markCommitLocalReady || recoveryActive)

  val freeCommitRow = rows(io.commitFreeIndex)
  val commitFreeLocalReady = freeCommitRow.valid && (freeCommitRow.status === STQEntryStatus.Commit)
  // A committed row is outside the speculative recovery suffix.  Its exact
  // terminal owner may release it while an ordinary recovery is active;
  // otherwise a one-cycle memory response would be lost and leak the row.
  io.commitFreeAccepted := io.commitFreeValid && commitFreeLocalReady
  io.commitFreeIgnored := io.commitFreeValid && !commitFreeLocalReady

  val commitFreeReqVec = Wire(Vec(entries, Bool()))
  val commitFreeAcceptedVec = Wire(Vec(entries, Bool()))
  val commitFreeIgnoredVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    val singleHit = io.commitFreeValid && (io.commitFreeIndex === idx.U)
    val maskHit = io.commitFreeMaskValid && io.commitFreeMask(idx)
    val rowReady = rows(idx).valid && (rows(idx).status === STQEntryStatus.Commit)
    commitFreeReqVec(idx) := singleHit || maskHit
    commitFreeAcceptedVec(idx) := commitFreeReqVec(idx) && rowReady
    commitFreeIgnoredVec(idx) := commitFreeReqVec(idx) && !rowReady
  }
  io.commitFreeAcceptedMask := commitFreeAcceptedVec.asUInt
  io.commitFreeIgnoredMask := commitFreeIgnoredVec.asUInt
  io.commitFreeCount := PopCount(commitFreeAcceptedVec)

  for (idx <- 0 until entries) {
    when(appliedRecoveryFreeMask(idx)) {
      rows(idx) := zeroRow
    }
  }

  when(io.markCommitAccepted) {
    rows(io.markCommitIndex).status := STQEntryStatus.Commit
  }

  for (idx <- 0 until entries) {
    when(commitFreeAcceptedVec(idx)) {
      rows(idx) := zeroRow
    }
  }

  when(io.fillAccepted) {
    rows(fillIndex) := fillRow(fillTarget, io.fill)
  }

  for (port <- 0 until 2) {
    when(dataCompletionAcceptedVec(port)) {
      val index = io.dataCompletions(port).bits.lease.index
      rows(index).dataReady := true.B
      rows(index).storeType := Mux(rows(index).addrReady,
        STQStoreType.All, STQStoreType.Data)
    }
  }

  when(io.reserveBatchAccepted) {
    for (beat <- 0 until maxReserveBeats) {
      when(io.reserveBatchMask(beat)) {
        val generation = leaseGenerations(batchIndices(beat)) + 1.U
        rows(batchIndices(beat)) := requestToRow(
          io.reserveBatch(beat), generation, reserved = true.B)
        leaseGenerations(batchIndices(beat)) := generation
      }
    }
  }

  when(io.reserveAccepted) {
    rows(freeIndex) := requestToRow(io.reserve, reserveGeneration, reserved = true.B)
    leaseGenerations(freeIndex) := reserveGeneration
  }

  when(io.insertMerged) {
    rows(mergeIndex) := mergeRow(rows(mergeIndex), io.insert)
  }

  when(io.insertAllocated) {
    rows(allocateIndex) := requestToRow(io.insert, insertAllocateGeneration)
    leaseGenerations(allocateIndex) := insertAllocateGeneration
  }

  val allocDelta = io.insertAllocated.asUInt +& io.reserveAccepted.asUInt +&
    PopCount(Mux(io.reserveBatchAccepted, io.reserveBatchMask, 0.U))
  val markCommitDelta = io.markCommitAccepted.asUInt
  val commitFreeDelta = io.commitFreeCount
  val flushFreeDelta = PopCount(appliedRecoveryFreeMask)

  residentCount := residentCount + allocDelta - commitFreeDelta - flushFreeDelta
  outstandingWaitCount := outstandingWaitCount + allocDelta - markCommitDelta - flushFreeDelta
}
