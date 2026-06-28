package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, UIntToOH}

class SCBStateUpdateIO(
    val scbEntries: Int,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))

  val entries = Input(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  val acceptedMask = Input(UInt(scbEntries.W))
  val missMask = Input(UInt(scbEntries.W))
  val freeMask = Input(UInt(scbEntries.W))
  val memRespValid = Input(Bool())
  val memRespEntryIndex = Input(UInt(entryIndexWidth.W))

  val nextEntries = Output(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  val memRespMask = Output(UInt(scbEntries.W))
  val acceptedToLookupMask = Output(UInt(scbEntries.W))
  val missStateMask = Output(UInt(scbEntries.W))
  val respToLookupMask = Output(UInt(scbEntries.W))
  val clearedMask = Output(UInt(scbEntries.W))
  val acceptedIllegalMask = Output(UInt(scbEntries.W))
  val missIllegalMask = Output(UInt(scbEntries.W))
  val freeIllegalMask = Output(UInt(scbEntries.W))
  val memRespIllegalMask = Output(UInt(scbEntries.W))
  val illegalMask = Output(UInt(scbEntries.W))
  val stateError = Output(Bool())
}

class SCBStateUpdate(
    val scbEntries: Int = 16,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Module {
  require(scbEntries > 0, "SCB state update requires at least one entry")
  require(addrWidth >= 7, "SCB state update needs at least 7 address bits for 64-byte lines")
  require(lineBytes == 64, "SCB state update currently models 64-byte scalar cachelines")

  val io = IO(new SCBStateUpdateIO(scbEntries, addrWidth, lineBytes))

  private def zeroEntry: SCBLineEntry = {
    val entry = Wire(new SCBLineEntry(addrWidth, lineBytes))
    entry := 0.U.asTypeOf(entry)
    entry
  }

  val memRespMask = Mux(io.memRespValid, UIntToOH(io.memRespEntryIndex, scbEntries), 0.U(scbEntries.W))

  val acceptedToLookupVec = Wire(Vec(scbEntries, Bool()))
  val missStateVec = Wire(Vec(scbEntries, Bool()))
  val respToLookupVec = Wire(Vec(scbEntries, Bool()))
  val clearedVec = Wire(Vec(scbEntries, Bool()))
  val acceptedIllegalVec = Wire(Vec(scbEntries, Bool()))
  val missIllegalVec = Wire(Vec(scbEntries, Bool()))
  val freeIllegalVec = Wire(Vec(scbEntries, Bool()))
  val memRespIllegalVec = Wire(Vec(scbEntries, Bool()))

  for (idx <- 0 until scbEntries) {
    val entry = io.entries(idx)
    val accepted = io.acceptedMask(idx)
    val miss = io.missMask(idx)
    val free = io.freeMask(idx)
    val resp = memRespMask(idx)

    val canStartLookup = entry.valid && (entry.state === SCBEntryState.Valid)
    val canFinishLookup = entry.valid &&
      ((entry.state === SCBEntryState.Valid) || (entry.state === SCBEntryState.Lookup))
    val canAcceptResp = entry.valid && (entry.state === SCBEntryState.Miss)

    acceptedIllegalVec(idx) := accepted && !canStartLookup
    missIllegalVec(idx) := miss && !canFinishLookup
    freeIllegalVec(idx) := free && !canFinishLookup
    memRespIllegalVec(idx) := resp && !canAcceptResp

    acceptedToLookupVec(idx) := accepted && !acceptedIllegalVec(idx) && !free && !miss && !resp
    missStateVec(idx) := miss && !missIllegalVec(idx) && !free
    respToLookupVec(idx) := resp && !memRespIllegalVec(idx) && !free && !miss
    clearedVec(idx) := free && !freeIllegalVec(idx)

    val nextEntry = Wire(new SCBLineEntry(addrWidth, lineBytes))
    nextEntry := entry

    when(clearedVec(idx)) {
      nextEntry := zeroEntry
    }.elsewhen(missStateVec(idx)) {
      nextEntry.state := SCBEntryState.Miss
    }.elsewhen(respToLookupVec(idx)) {
      nextEntry.state := SCBEntryState.Lookup
    }.elsewhen(acceptedToLookupVec(idx)) {
      nextEntry.state := SCBEntryState.Lookup
    }

    io.nextEntries(idx) := nextEntry
  }

  io.memRespMask := memRespMask
  io.acceptedToLookupMask := acceptedToLookupVec.asUInt
  io.missStateMask := missStateVec.asUInt
  io.respToLookupMask := respToLookupVec.asUInt
  io.clearedMask := clearedVec.asUInt
  io.acceptedIllegalMask := acceptedIllegalVec.asUInt
  io.missIllegalMask := missIllegalVec.asUInt
  io.freeIllegalMask := freeIllegalVec.asUInt
  io.memRespIllegalMask := memRespIllegalVec.asUInt
  io.illegalMask := acceptedIllegalVec.asUInt | missIllegalVec.asUInt | freeIllegalVec.asUInt | memRespIllegalVec.asUInt
  io.stateError := io.illegalMask.orR
}
