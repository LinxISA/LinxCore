package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnStoreSnapshotAcceptedTokenIO(
    clusterIdWidth: Int,
    entryIdWidth: Int,
    lineBytes: Int)
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val queryIssued = Input(Bool())
  val selectedValid = Input(Bool())
  val selectedRepick = Input(Bool())
  val selectedClusterId = Input(UInt(clusterIdWidth.W))
  val selectedEntryId = Input(UInt(entryIdWidth.W))
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
  val tokenLineData = Output(UInt((lineBytes * 8).W))
  val tokenValidMask = Output(UInt(lineBytes.W))
  val tokenRequestByteMask = Output(UInt(lineBytes.W))
  val residentTokenValid = Output(Bool())
  val captureCandidate = Output(Bool())
  val captureAccepted = Output(Bool())
  val captureBypass = Output(Bool())
  val clearAccepted = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoSelected = Output(Bool())
  val blockedByStaleRow = Output(Bool())
  val blockedByOutstandingToken = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotAcceptedToken(
    clusterIdWidth: Int = 2,
    entryIdWidth: Int = 4,
    lineBytes: Int = 64)
    extends Module {
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth > 0, "entryIdWidth must be positive")
  require(lineBytes == 64, "accepted token currently carries 64-byte scalar line context")

  val io = IO(new LoadReplaySourceReturnStoreSnapshotAcceptedTokenIO(
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    lineBytes = lineBytes
  ))

  val tokenValidReg = RegInit(false.B)
  val tokenRepickReg = RegInit(false.B)
  val tokenClusterIdReg = RegInit(0.U(clusterIdWidth.W))
  val tokenEntryIdReg = RegInit(0.U(entryIdWidth.W))
  val tokenLineDataReg = RegInit(0.U((lineBytes * 8).W))
  val tokenValidMaskReg = RegInit(0.U(lineBytes.W))
  val tokenRequestByteMaskReg = RegInit(0.U(lineBytes.W))

  val active = io.enable && !io.flush
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
    tokenLineDataReg := 0.U
    tokenValidMaskReg := 0.U
    tokenRequestByteMaskReg := 0.U
  }.elsewhen(active) {
    when(clearAccepted) {
      tokenValidReg := false.B
      tokenRepickReg := false.B
      tokenClusterIdReg := 0.U
      tokenEntryIdReg := 0.U
      tokenLineDataReg := 0.U
      tokenValidMaskReg := 0.U
      tokenRequestByteMaskReg := 0.U
    }.elsewhen(captureAccepted) {
      tokenValidReg := true.B
      tokenRepickReg := io.selectedRepick
      tokenClusterIdReg := io.selectedClusterId
      tokenEntryIdReg := io.selectedEntryId
      tokenLineDataReg := io.selectedLineData
      tokenValidMaskReg := io.selectedValidMask
      tokenRequestByteMaskReg := io.selectedRequestByteMask
    }
  }

  io.active := active
  io.tokenCanAccept := tokenCanAccept
  io.tokenValid := tokenValid
  io.tokenRepick := Mux(tokenValidReg, tokenRepickReg, captureReady)
  io.tokenClusterId := Mux(tokenValidReg, tokenClusterIdReg, io.selectedClusterId)
  io.tokenEntryId := Mux(tokenValidReg, tokenEntryIdReg, io.selectedEntryId)
  io.tokenLineData := Mux(tokenValidReg, tokenLineDataReg, io.selectedLineData)
  io.tokenValidMask := Mux(tokenValidReg, tokenValidMaskReg, io.selectedValidMask)
  io.tokenRequestByteMask := Mux(tokenValidReg, tokenRequestByteMaskReg, io.selectedRequestByteMask)
  io.residentTokenValid := tokenValidReg
  io.captureCandidate := captureCandidate
  io.captureAccepted := captureAccepted
  io.captureBypass := captureBypass
  io.clearAccepted := clearAccepted
  io.blockedByDisabled := !io.enable && io.queryIssued
  io.blockedByFlush := io.enable && io.flush && io.queryIssued
  io.blockedByNoSelected := captureCandidate && !io.selectedValid
  io.blockedByStaleRow := captureHasSelected && !io.selectedRepick
  io.blockedByOutstandingToken := captureReady && tokenValidReg
}
