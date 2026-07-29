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
    val physicalStqEntries: Int = 0)
    extends Bundle {
  private val ridSlotWidth = log2Ceil(entries)
  private val leaseEntries = if (physicalStqEntries > 0) physicalStqEntries else entries
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
    val leaseGenerationWidth: Int = 8)
    extends Bundle {
  private val ridSlotWidth = log2Ceil(entries)
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
    val leaseGenerationWidth: Int = 8)
    extends Bundle {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  private val ptrWidth = log2Ceil(entries)
  private val countWidth = log2Ceil(entries + 1)
  private val sourceParams = InterfaceParams(robEntries = identityEntries)

  val flush = Input(new FlushBus(identityEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))

  val insertValid = Input(Bool())
  val insert = Input(new STQStoreRequest(
    identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
    sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth, entries))
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
    residentGenerationWidth, leaseGenerationWidth, entries))
  val reserveReady = Output(Bool())
  val reserveAccepted = Output(Bool())
  val reserveConflict = Output(Bool())
  val reserveLease = Output(new STQPhysicalLease(entries, leaseGenerationWidth))

  val fillValid = Input(Bool())
  val fill = Input(new STQStoreRequest(
    identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
    sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth, entries))
  val fillReady = Output(Bool())
  val fillAccepted = Output(Bool())
  val fillConflict = Output(Bool())

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
    residentGenerationWidth, leaseGenerationWidth)))
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
    io.reserveValid := false.B
    io.reserve := 0.U.asTypeOf(io.reserve)
    io.fillValid := false.B
    io.fill := 0.U.asTypeOf(io.fill)
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
    val leaseGenerationWidth: Int = 8)
    extends Module {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  require(entries > 1, "STQ entries must be greater than one")
  require((entries & (entries - 1)) == 0, "STQ entries must be a power of two")
  require(identityEntries > 1, "ROB entries must be greater than one")
  require((identityEntries & (identityEntries - 1)) == 0, "ROB entries must be a power of two")
  require(lsidWidth >= 2, "LSID width must support modular serial ordering")
  require(leaseGenerationWidth > 0, "STQ lease generation width must be positive")

  private val countWidth = log2Ceil(entries + 1)

  private val sourceParams = InterfaceParams(robEntries = identityEntries)

  val io = IO(new STQEntryBankIO(
    entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth,
    simtLaneWidth, mapQDepth, identityEntries, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth))

  private def rowBundle: STQEntryBankRow =
    new STQEntryBankRow(
      identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
      sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth, nativeBidWidth,
      ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
      residentGenerationWidth, leaseGenerationWidth)

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

  val flushApplied = flushPrune.io.freeMask.orR
  val recoveryActive = io.flush.req.valid
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
      (io.fill.storeIdFull === fillTarget.storeIdFull)
  val fillPartAvailable = MuxLookup(io.fill.storeType, false.B)(Seq(
    STQStoreType.All -> (!fillTarget.addrReady && !fillTarget.dataReady),
    STQStoreType.Addr -> !fillTarget.addrReady,
    STQStoreType.Data -> !fillTarget.dataReady
  ))
  io.fillReady := !recoveryActive && fillIdentityMatches && fillPartAvailable
  io.fillAccepted := io.fillValid && io.fillReady
  io.fillConflict := io.fillValid && !io.fillReady

  val freeAvailable = !occupiedVec.asUInt.andR
  val freeIndex = PriorityEncoder(~occupiedVec.asUInt)
  val reserveOwnerConsistent =
    io.reserve.exactOwner.valid &&
      io.reserve.exactOwner.nativeBidValid &&
      (io.reserve.exactOwner.peId === io.reserve.peId) &&
      (io.reserve.exactOwner.stid === io.reserve.stid)
  val reserveIdentityValid =
    reserveOwnerConsistent && io.reserve.storeIdFullValid
  val reserveDuplicate = VecInit(rows.map { row =>
    row.valid &&
      row.exactOwner.valid &&
      (row.exactOwner.asUInt === io.reserve.exactOwner.asUInt) &&
      (row.lsIdFull === io.reserve.lsIdFull) &&
      row.storeIdFullValid &&
      (row.storeIdFull === io.reserve.storeIdFull)
  }).asUInt.orR
  io.reserveReady :=
    !recoveryActive && !io.fillAccepted && reserveIdentityValid &&
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

  val canonicalMutation = io.fillAccepted || io.reserveAccepted
  io.insertConflict := insertProbe.io.conflict
  io.insertReady := insertProbe.io.ready && !recoveryActive && !canonicalMutation
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
  io.commitFreeAccepted := !recoveryActive && io.commitFreeValid && commitFreeLocalReady
  io.commitFreeIgnored := io.commitFreeValid && (!commitFreeLocalReady || recoveryActive)

  val commitFreeReqVec = Wire(Vec(entries, Bool()))
  val commitFreeAcceptedVec = Wire(Vec(entries, Bool()))
  val commitFreeIgnoredVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    val singleHit = io.commitFreeValid && (io.commitFreeIndex === idx.U)
    val maskHit = io.commitFreeMaskValid && io.commitFreeMask(idx)
    val rowReady = rows(idx).valid && (rows(idx).status === STQEntryStatus.Commit)
    commitFreeReqVec(idx) := singleHit || maskHit
    commitFreeAcceptedVec(idx) := !recoveryActive && commitFreeReqVec(idx) && rowReady
    commitFreeIgnoredVec(idx) := commitFreeReqVec(idx) && (!rowReady || recoveryActive)
  }
  io.commitFreeAcceptedMask := commitFreeAcceptedVec.asUInt
  io.commitFreeIgnoredMask := commitFreeIgnoredVec.asUInt
  io.commitFreeCount := PopCount(commitFreeAcceptedVec)

  for (idx <- 0 until entries) {
    when(flushPrune.io.freeMask(idx)) {
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

  val allocDelta = io.insertAllocated.asUInt +& io.reserveAccepted.asUInt
  val markCommitDelta = io.markCommitAccepted.asUInt
  val commitFreeDelta = io.commitFreeCount
  val flushFreeDelta = Mux(flushApplied, flushPrune.io.freeCount, 0.U)

  residentCount := residentCount + allocDelta - commitFreeDelta - flushFreeDelta
  outstandingWaitCount := outstandingWaitCount + allocDelta - markCommitDelta - flushFreeDelta
}
