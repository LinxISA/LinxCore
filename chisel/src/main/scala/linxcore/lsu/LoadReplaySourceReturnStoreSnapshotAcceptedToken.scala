package linxcore.lsu

import chisel3._

import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

class LoadReplaySourceReturnStoreSnapshotAcceptedTokenIO(
    idEntries: Int,
    clusterIdWidth: Int,
    entryIdWidth: Int,
    peIdWidth: Int,
    stidWidth: Int,
    tidWidth: Int,
    lineBytes: Int)
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(idEntries, peIdWidth, stidWidth, tidWidth))
  val queryIssued = Input(Bool())
  val selectedValid = Input(Bool())
  val selectedRepick = Input(Bool())
  val selectedClusterId = Input(UInt(clusterIdWidth.W))
  val selectedEntryId = Input(UInt(entryIdWidth.W))
  val selectedBid = Input(new ROBID(idEntries))
  val selectedGid = Input(new ROBID(idEntries))
  val selectedLoadLsId = Input(new ROBID(idEntries))
  val selectedPeId = Input(UInt(peIdWidth.W))
  val selectedStid = Input(UInt(stidWidth.W))
  val selectedTid = Input(UInt(tidWidth.W))
  val selectedLineData = Input(UInt((lineBytes * 8).W))
  val selectedValidMask = Input(UInt(lineBytes.W))
  val selectedRequestByteMask = Input(UInt(lineBytes.W))
  val responseConsumed = Input(Bool())

  val active = Output(Bool())
  val tokenCanAccept = Output(Bool())
  val tokenValid = Output(Bool())
  val tokenRepick = Output(Bool())
  val tokenClusterId = Output(UInt(clusterIdWidth.W))
  val tokenEntryId = Output(UInt(entryIdWidth.W))
  val tokenBid = Output(new ROBID(idEntries))
  val tokenGid = Output(new ROBID(idEntries))
  val tokenLoadLsId = Output(new ROBID(idEntries))
  val tokenPeId = Output(UInt(peIdWidth.W))
  val tokenStid = Output(UInt(stidWidth.W))
  val tokenTid = Output(UInt(tidWidth.W))
  val tokenLineData = Output(UInt((lineBytes * 8).W))
  val tokenValidMask = Output(UInt(lineBytes.W))
  val tokenRequestByteMask = Output(UInt(lineBytes.W))
  val residentTokenValid = Output(Bool())
  val captureCandidate = Output(Bool())
  val captureAccepted = Output(Bool())
  val captureBypass = Output(Bool())
  val clearAccepted = Output(Bool())
  val precisePruned = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByPreciseFlush = Output(Bool())
  val blockedByNoSelected = Output(Bool())
  val blockedByStaleRow = Output(Bool())
  val blockedByOutstandingToken = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotAcceptedToken(
    idEntries: Int = 16,
    clusterIdWidth: Int = 2,
    entryIdWidth: Int = 4,
    peIdWidth: Int = 8,
    stidWidth: Int = 8,
    tidWidth: Int = 8,
    lineBytes: Int = 64)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth > 0, "entryIdWidth must be positive")
  require(peIdWidth > 0, "peIdWidth must be positive")
  require(stidWidth > 0, "stidWidth must be positive")
  require(tidWidth > 0, "tidWidth must be positive")
  require(lineBytes == 64, "accepted token currently carries 64-byte scalar line context")

  val io = IO(new LoadReplaySourceReturnStoreSnapshotAcceptedTokenIO(
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    lineBytes = lineBytes
  ))

  val tokenValidReg = RegInit(false.B)
  val tokenRepickReg = RegInit(false.B)
  val tokenClusterIdReg = RegInit(0.U(clusterIdWidth.W))
  val tokenEntryIdReg = RegInit(0.U(entryIdWidth.W))
  val tokenBidReg = RegInit(0.U.asTypeOf(new ROBID(idEntries)))
  val tokenGidReg = RegInit(0.U.asTypeOf(new ROBID(idEntries)))
  val tokenLoadLsIdReg = RegInit(0.U.asTypeOf(new ROBID(idEntries)))
  val tokenPeIdReg = RegInit(0.U(peIdWidth.W))
  val tokenStidReg = RegInit(0.U(stidWidth.W))
  val tokenTidReg = RegInit(0.U(tidWidth.W))
  val tokenLineDataReg = RegInit(0.U((lineBytes * 8).W))
  val tokenValidMaskReg = RegInit(0.U(lineBytes.W))
  val tokenRequestByteMaskReg = RegInit(0.U(lineBytes.W))

  private def toPruneEntry: STQFlushPruneEntry = {
    val entry = Wire(new STQFlushPruneEntry(idEntries, peIdWidth, stidWidth, tidWidth))
    entry.valid := tokenValidReg
    entry.status := STQEntryStatus.Wait
    entry.peId := tokenPeIdReg
    entry.stid := tokenStidReg
    entry.tid := tokenTidReg
    entry.bid := tokenBidReg
    entry.gid := tokenGidReg
    entry.lsId := tokenLoadLsIdReg
    entry.lsIdFullValid := false.B
    entry.lsIdFull := 0.U
    entry
  }

  val baseActive = io.enable && !io.flush
  val precisePruneActive = baseActive && io.preciseFlush.req.valid
  val precisePruned = precisePruneActive &&
    STQFlushPrune.matchesFlushProjected(io.preciseFlush, toPruneEntry)
  val active = baseActive && !precisePruneActive
  val tokenCanAccept = active && !tokenValidReg
  val captureCandidate = active && io.queryIssued
  val captureHasSelected = captureCandidate && io.selectedValid
  val captureReady = captureHasSelected && io.selectedRepick
  val captureAccepted = captureReady && !tokenValidReg
  val captureBypass = captureAccepted
  val tokenValid = tokenValidReg || captureBypass
  val clearAccepted = active && io.responseConsumed && tokenValid

  when(io.flush) {
    tokenValidReg := false.B
    tokenRepickReg := false.B
    tokenClusterIdReg := 0.U
    tokenEntryIdReg := 0.U
    tokenBidReg := 0.U.asTypeOf(new ROBID(idEntries))
    tokenGidReg := 0.U.asTypeOf(new ROBID(idEntries))
    tokenLoadLsIdReg := 0.U.asTypeOf(new ROBID(idEntries))
    tokenPeIdReg := 0.U
    tokenStidReg := 0.U
    tokenTidReg := 0.U
    tokenLineDataReg := 0.U
    tokenValidMaskReg := 0.U
    tokenRequestByteMaskReg := 0.U
  }.elsewhen(baseActive) {
    when(precisePruned) {
      tokenValidReg := false.B
      tokenRepickReg := false.B
      tokenClusterIdReg := 0.U
      tokenEntryIdReg := 0.U
      tokenBidReg := 0.U.asTypeOf(new ROBID(idEntries))
      tokenGidReg := 0.U.asTypeOf(new ROBID(idEntries))
      tokenLoadLsIdReg := 0.U.asTypeOf(new ROBID(idEntries))
      tokenPeIdReg := 0.U
      tokenStidReg := 0.U
      tokenTidReg := 0.U
      tokenLineDataReg := 0.U
      tokenValidMaskReg := 0.U
      tokenRequestByteMaskReg := 0.U
    }.elsewhen(active) {
      when(clearAccepted) {
        tokenValidReg := false.B
        tokenRepickReg := false.B
        tokenClusterIdReg := 0.U
        tokenEntryIdReg := 0.U
        tokenBidReg := 0.U.asTypeOf(new ROBID(idEntries))
        tokenGidReg := 0.U.asTypeOf(new ROBID(idEntries))
        tokenLoadLsIdReg := 0.U.asTypeOf(new ROBID(idEntries))
        tokenPeIdReg := 0.U
        tokenStidReg := 0.U
        tokenTidReg := 0.U
        tokenLineDataReg := 0.U
        tokenValidMaskReg := 0.U
        tokenRequestByteMaskReg := 0.U
      }.elsewhen(captureAccepted) {
        tokenValidReg := true.B
        tokenRepickReg := io.selectedRepick
        tokenClusterIdReg := io.selectedClusterId
        tokenEntryIdReg := io.selectedEntryId
        tokenBidReg := io.selectedBid
        tokenGidReg := io.selectedGid
        tokenLoadLsIdReg := io.selectedLoadLsId
        tokenPeIdReg := io.selectedPeId
        tokenStidReg := io.selectedStid
        tokenTidReg := io.selectedTid
        tokenLineDataReg := io.selectedLineData
        tokenValidMaskReg := io.selectedValidMask
        tokenRequestByteMaskReg := io.selectedRequestByteMask
      }
    }
  }

  io.active := active
  io.tokenCanAccept := tokenCanAccept
  io.tokenValid := tokenValid
  io.tokenRepick := Mux(tokenValidReg, tokenRepickReg, captureReady)
  io.tokenClusterId := Mux(tokenValidReg, tokenClusterIdReg, io.selectedClusterId)
  io.tokenEntryId := Mux(tokenValidReg, tokenEntryIdReg, io.selectedEntryId)
  io.tokenBid := Mux(tokenValidReg, tokenBidReg, io.selectedBid)
  io.tokenGid := Mux(tokenValidReg, tokenGidReg, io.selectedGid)
  io.tokenLoadLsId := Mux(tokenValidReg, tokenLoadLsIdReg, io.selectedLoadLsId)
  io.tokenPeId := Mux(tokenValidReg, tokenPeIdReg, io.selectedPeId)
  io.tokenStid := Mux(tokenValidReg, tokenStidReg, io.selectedStid)
  io.tokenTid := Mux(tokenValidReg, tokenTidReg, io.selectedTid)
  io.tokenLineData := Mux(tokenValidReg, tokenLineDataReg, io.selectedLineData)
  io.tokenValidMask := Mux(tokenValidReg, tokenValidMaskReg, io.selectedValidMask)
  io.tokenRequestByteMask := Mux(tokenValidReg, tokenRequestByteMaskReg, io.selectedRequestByteMask)
  io.residentTokenValid := tokenValidReg
  io.captureCandidate := captureCandidate
  io.captureAccepted := captureAccepted
  io.captureBypass := captureBypass
  io.clearAccepted := clearAccepted
  io.precisePruned := precisePruned
  io.blockedByDisabled := !io.enable && io.queryIssued
  io.blockedByFlush := io.enable && io.flush && io.queryIssued
  io.blockedByPreciseFlush := precisePruneActive && io.queryIssued
  io.blockedByNoSelected := captureCandidate && !io.selectedValid
  io.blockedByStaleRow := captureHasSelected && !io.selectedRepick
  io.blockedByOutstandingToken := captureReady && tokenValidReg
}
