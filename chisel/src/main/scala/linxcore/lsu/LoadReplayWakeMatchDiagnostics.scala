package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplayWakeMatchDiagnosticsIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val lsidWidth: Int = 32)
    extends Bundle {
  val wakeValid = Input(Bool())
  val wake = Input(new LoadReplayWakeupRequest(idEntries, addrWidth, pcWidth, lineBytes, lsidWidth))
  val rows = Input(Vec(
    liqEntries,
    new LoadInflightRow(
      liqEntries,
      idEntries,
      storeEntries,
      addrWidth,
      pcWidth,
      lineBytes,
      sizeWidth,
      archRegWidth,
      physRegWidth,
      lsidWidth = lsidWidth
    )
  ))

  val waitStoreCandidateMask = Output(UInt(liqEntries.W))
  val bidMatchMask = Output(UInt(liqEntries.W))
  val lsIdMatchMask = Output(UInt(liqEntries.W))
  val pcMatchMask = Output(UInt(liqEntries.W))
  val fullMatchMask = Output(UInt(liqEntries.W))
  val storeUnit = Output(Bool())
  val storeUnitFullMatchMask = Output(UInt(liqEntries.W))
}

class LoadReplayWakeMatchDiagnostics(
    val liqEntries: Int = 16,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val lsidWidth: Int = 32)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(log2Ceil(liqEntries) <= log2Ceil(idEntries), "diagnostic ROBID width assumes LIQ IDs fit ID entries")

  val io = IO(new LoadReplayWakeMatchDiagnosticsIO(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    lsidWidth
  ))

  val waitStoreCandidateVec = Wire(Vec(liqEntries, Bool()))
  val bidMatchVec = Wire(Vec(liqEntries, Bool()))
  val lsIdMatchVec = Wire(Vec(liqEntries, Bool()))
  val pcMatchVec = Wire(Vec(liqEntries, Bool()))
  val fullMatchVec = Wire(Vec(liqEntries, Bool()))
  val storeUnitFullMatchVec = Wire(Vec(liqEntries, Bool()))
  val storeUnit = io.wakeValid && (io.wake.source === LoadReplayWakeSource.StoreUnit)

  for (idx <- 0 until liqEntries) {
    val row = io.rows(idx)
    val candidate = io.wakeValid && row.valid && row.waitStore && row.waitStoreInfo.valid
    val bidMatch = candidate && ROBID.equal(row.waitStoreInfo.storeId, io.wake.storeId)
    val lsIdMatch =
      bidMatch &&
        (!row.waitStoreInfo.storeLsId.valid || ROBID.equal(row.waitStoreInfo.storeLsId, io.wake.storeLsId))
    val fullLsIdMatch =
      lsIdMatch &&
        row.waitStoreInfo.storeLsIdFullValid &&
        io.wake.storeLsIdFullValid &&
        row.waitStoreInfo.storeLsIdFull === io.wake.storeLsIdFull
    val pcMatch = candidate && (row.waitStoreInfo.pc === io.wake.pc)
    val fullMatch = fullLsIdMatch && pcMatch

    waitStoreCandidateVec(idx) := candidate
    bidMatchVec(idx) := bidMatch
    lsIdMatchVec(idx) := lsIdMatch
    pcMatchVec(idx) := pcMatch
    fullMatchVec(idx) := fullMatch
    storeUnitFullMatchVec(idx) := storeUnit && fullMatch
  }

  io.waitStoreCandidateMask := waitStoreCandidateVec.asUInt
  io.bidMatchMask := bidMatchVec.asUInt
  io.lsIdMatchMask := lsIdMatchVec.asUInt
  io.pcMatchMask := pcMatchVec.asUInt
  io.fullMatchMask := fullMatchVec.asUInt
  io.storeUnit := storeUnit
  io.storeUnitFullMatchMask := storeUnitFullMatchVec.asUInt
}
