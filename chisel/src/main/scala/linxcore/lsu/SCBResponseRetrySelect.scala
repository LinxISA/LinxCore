package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, UIntToOH}

class SCBResponseRetrySelectIO(
    val scbEntries: Int,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  val entries = Input(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  val normalLookupRequest = Input(new SCBEgressLookupRequest(scbEntries, addrWidth, lineBytes))
  val normalLookupMask = Input(UInt(scbEntries.W))
  val retryHeadValid = Input(Bool())
  val retryHeadEntryIndex = Input(UInt(math.max(1, log2Ceil(scbEntries)).W))

  val retryCandidateMask = Output(UInt(scbEntries.W))
  val retryLookupMask = Output(UInt(scbEntries.W))
  val normalSelectedMask = Output(UInt(scbEntries.W))
  val lookupMask = Output(UInt(scbEntries.W))
  val lookupRetry = Output(Bool())
  val lookupNormal = Output(Bool())
  val retryHeadBlocked = Output(Bool())
  val lookupFull = Output(Bool())
  val lookupNotFull = Output(Bool())
  val noCandidate = Output(Bool())
  val lookupRequest = Output(new SCBEgressLookupRequest(scbEntries, addrWidth, lineBytes))
}

class SCBResponseRetrySelect(
    val scbEntries: Int = 16,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Module {
  require(scbEntries > 0, "SCB response retry selector requires at least one entry")
  require(addrWidth >= 7, "SCB response retry selector needs at least 7 address bits for 64-byte lines")
  require(lineBytes == 64, "SCB response retry selector currently models 64-byte scalar cachelines")

  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))

  val io = IO(new SCBResponseRetrySelectIO(scbEntries, addrWidth, lineBytes))

  val retryCandidateVec = VecInit(io.entries.map(entry => entry.valid && (entry.state === SCBEntryState.Lookup)))
  val retryCandidateMask = retryCandidateVec.asUInt
  val retryHeadEntry = io.entries(io.retryHeadEntryIndex)
  val retryHeadReady = io.retryHeadValid && retryHeadEntry.valid && (retryHeadEntry.state === SCBEntryState.Lookup)
  val retryHeadBlocked = io.retryHeadValid && !retryHeadReady
  val retryOH = Mux(retryHeadReady, UIntToOH(io.retryHeadEntryIndex, scbEntries), 0.U(scbEntries.W))

  val retryRequest = Wire(new SCBEgressLookupRequest(scbEntries, addrWidth, lineBytes))
  retryRequest := 0.U.asTypeOf(retryRequest)
  retryRequest.valid := retryHeadReady
  retryRequest.entryIndex := io.retryHeadEntryIndex
  retryRequest.lineAddr := retryHeadEntry.lineAddr
  retryRequest.byteMask := retryHeadEntry.byteMask
  retryRequest.data := retryHeadEntry.data
  retryRequest.full := retryHeadEntry.full

  val selectedRequest = Wire(new SCBEgressLookupRequest(scbEntries, addrWidth, lineBytes))
  selectedRequest := io.normalLookupRequest
  when(io.retryHeadValid) {
    selectedRequest := retryRequest
  }

  val normalSelected = !io.retryHeadValid && io.normalLookupRequest.valid

  io.retryCandidateMask := retryCandidateMask
  io.retryLookupMask := retryOH
  io.normalSelectedMask := Mux(normalSelected, io.normalLookupMask, 0.U(scbEntries.W))
  io.lookupMask := Mux(io.retryHeadValid, retryOH, io.normalLookupMask)
  io.lookupRetry := retryHeadReady
  io.lookupNormal := normalSelected
  io.retryHeadBlocked := retryHeadBlocked
  io.lookupFull := selectedRequest.valid && selectedRequest.full
  io.lookupNotFull := selectedRequest.valid && !selectedRequest.full
  io.noCandidate := !io.retryHeadValid && !io.normalLookupRequest.valid
  io.lookupRequest := selectedRequest
}
