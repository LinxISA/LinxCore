package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, OHToUInt, PriorityEncoderOH}

class SCBEgressLookupRequest(
    val scbEntries: Int,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))

  val valid = Bool()
  val entryIndex = UInt(entryIndexWidth.W)
  val lineAddr = UInt(addrWidth.W)
  val byteMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
  val full = Bool()
}

class SCBEgressSelectIO(
    val scbEntries: Int,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  val evictEnable = Input(Bool())
  val entries = Input(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))

  val validStateMask = Output(UInt(scbEntries.W))
  val fullCandidateMask = Output(UInt(scbEntries.W))
  val notFullCandidateMask = Output(UInt(scbEntries.W))
  val lookupMask = Output(UInt(scbEntries.W))
  val lookupFull = Output(Bool())
  val lookupNotFull = Output(Bool())
  val noCandidate = Output(Bool())
  val lookupRequest = Output(new SCBEgressLookupRequest(scbEntries, addrWidth, lineBytes))
}

class SCBEgressSelect(
    val scbEntries: Int = 16,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Module {
  require(scbEntries > 0, "SCB egress selector requires at least one entry")
  require(addrWidth >= 7, "SCB egress selector needs at least 7 address bits for 64-byte lines")
  require(lineBytes == 64, "SCB egress selector currently models 64-byte scalar cachelines")

  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))

  val io = IO(new SCBEgressSelectIO(scbEntries, addrWidth, lineBytes))

  val validStateVec = VecInit(io.entries.map(entry => entry.valid && SCBEntryState.canIssueLookup(entry.state)))
  val fullCandidateVec = VecInit(io.entries.map(entry =>
    entry.valid && SCBEntryState.canIssueLookup(entry.state) && entry.full))
  val notFullCandidateVec = VecInit(io.entries.map(entry =>
    entry.valid && SCBEntryState.canIssueLookup(entry.state) && !entry.full))

  val validStateMask = validStateVec.asUInt
  val fullCandidateMask = fullCandidateVec.asUInt
  val notFullCandidateMask = notFullCandidateVec.asUInt
  val hasFullCandidate = fullCandidateMask.orR
  val hasNotFullCandidate = notFullCandidateMask.orR
  val selectedCandidateMask = Mux(hasFullCandidate, fullCandidateMask, notFullCandidateMask)
  val selectedValid = io.evictEnable && (hasFullCandidate || hasNotFullCandidate)
  val selectedOH = Mux(selectedValid, PriorityEncoderOH(selectedCandidateMask), 0.U(scbEntries.W))

  val selectedIndex = Wire(UInt(entryIndexWidth.W))
  selectedIndex := 0.U
  if (scbEntries > 1) {
    selectedIndex := OHToUInt(selectedOH)
  }

  val selectedEntry = io.entries(selectedIndex)
  val lookupRequest = Wire(new SCBEgressLookupRequest(scbEntries, addrWidth, lineBytes))
  lookupRequest := 0.U.asTypeOf(lookupRequest)
  lookupRequest.valid := selectedValid
  lookupRequest.entryIndex := selectedIndex
  lookupRequest.lineAddr := selectedEntry.lineAddr
  lookupRequest.byteMask := selectedEntry.byteMask
  lookupRequest.data := selectedEntry.data
  lookupRequest.full := selectedEntry.full

  io.validStateMask := validStateMask
  io.fullCandidateMask := fullCandidateMask
  io.notFullCandidateMask := notFullCandidateMask
  io.lookupMask := selectedOH
  io.lookupFull := selectedValid && hasFullCandidate
  io.lookupNotFull := selectedValid && !hasFullCandidate && hasNotFullCandidate
  io.noCandidate := io.evictEnable && !hasFullCandidate && !hasNotFullCandidate
  io.lookupRequest := lookupRequest
}
