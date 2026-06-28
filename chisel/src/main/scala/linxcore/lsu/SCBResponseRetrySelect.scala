package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, OHToUInt, PriorityEncoderOH}

class SCBResponseRetrySelectIO(
    val scbEntries: Int,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  val entries = Input(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  val normalLookupRequest = Input(new SCBEgressLookupRequest(scbEntries, addrWidth, lineBytes))
  val normalLookupMask = Input(UInt(scbEntries.W))

  val retryCandidateMask = Output(UInt(scbEntries.W))
  val retryLookupMask = Output(UInt(scbEntries.W))
  val normalSelectedMask = Output(UInt(scbEntries.W))
  val lookupMask = Output(UInt(scbEntries.W))
  val lookupRetry = Output(Bool())
  val lookupNormal = Output(Bool())
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
  val selectedRetry = retryCandidateMask.orR
  val retryOH = Mux(selectedRetry, PriorityEncoderOH(retryCandidateMask), 0.U(scbEntries.W))

  val retryIndex = Wire(UInt(entryIndexWidth.W))
  retryIndex := 0.U
  if (scbEntries > 1) {
    retryIndex := OHToUInt(retryOH)
  }

  val retryEntry = io.entries(retryIndex)
  val retryRequest = Wire(new SCBEgressLookupRequest(scbEntries, addrWidth, lineBytes))
  retryRequest := 0.U.asTypeOf(retryRequest)
  retryRequest.valid := selectedRetry
  retryRequest.entryIndex := retryIndex
  retryRequest.lineAddr := retryEntry.lineAddr
  retryRequest.byteMask := retryEntry.byteMask
  retryRequest.data := retryEntry.data
  retryRequest.full := retryEntry.full

  val selectedRequest = Wire(new SCBEgressLookupRequest(scbEntries, addrWidth, lineBytes))
  selectedRequest := io.normalLookupRequest
  when(selectedRetry) {
    selectedRequest := retryRequest
  }

  val normalSelected = !selectedRetry && io.normalLookupRequest.valid

  io.retryCandidateMask := retryCandidateMask
  io.retryLookupMask := retryOH
  io.normalSelectedMask := Mux(normalSelected, io.normalLookupMask, 0.U(scbEntries.W))
  io.lookupMask := Mux(selectedRetry, retryOH, io.normalLookupMask)
  io.lookupRetry := selectedRetry
  io.lookupNormal := normalSelected
  io.lookupFull := selectedRequest.valid && selectedRequest.full
  io.lookupNotFull := selectedRequest.valid && !selectedRequest.full
  io.noCandidate := !selectedRetry && !io.normalLookupRequest.valid
  io.lookupRequest := selectedRequest
}
