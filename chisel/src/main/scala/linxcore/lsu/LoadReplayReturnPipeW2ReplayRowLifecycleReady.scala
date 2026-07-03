package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PopCount, PriorityEncoder}

import linxcore.rob.ROBID

class LoadReplayReturnPipeW2ReplayRowLifecycleReadyIO(
    val liqEntries: Int = 16,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)
  private val matchCountWidth = log2Ceil(liqEntries + 1)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val lifecycleClearEnable = Input(Bool())
  val slotOccupied = Input(Bool())
  val slotBid = Input(new ROBID(idEntries))
  val slotGid = Input(new ROBID(idEntries))
  val slotRid = Input(new ROBID(idEntries))
  val slotLoadLsId = Input(new ROBID(idEntries))
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
      physRegWidth
    )
  ))

  val active = Output(Bool())
  val candidateValid = Output(Bool())
  val slotIdentityValid = Output(Bool())
  val resolvedRowMatch = Output(Bool())
  val matchedMask = Output(UInt(liqEntries.W))
  val matchCount = Output(UInt(matchCountWidth.W))
  val rowClearIndex = Output(UInt(liqPtrWidth.W))
  val rowClearReady = Output(Bool())
  val lifecycleReady = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByInvalidSlotIdentity = Output(Bool())
  val blockedByNoResolvedRow = Output(Bool())
  val blockedByMultipleResolvedRows = Output(Bool())
  val blockedByLifecycleClearDisabled = Output(Bool())
  val invalidLifecycleClearWithoutRow = Output(Bool())
}

class LoadReplayReturnPipeW2ReplayRowLifecycleReady(
    val liqEntries: Int = 16,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(lineBytes == 64, "replay W2 lifecycle guard currently models 64-byte scalar cachelines")
  require(sizeWidth > 0, "sizeWidth must be positive")
  require(archRegWidth > 0, "archRegWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  private val liqPtrWidth = log2Ceil(liqEntries)
  private val matchCountWidth = log2Ceil(liqEntries + 1)

  val io = IO(new LoadReplayReturnPipeW2ReplayRowLifecycleReadyIO(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth
  ))

  val active = io.enable && !io.flush
  val candidateValid = active && io.slotOccupied
  val slotIdentityValid =
    io.slotBid.valid && io.slotGid.valid && io.slotRid.valid && io.slotLoadLsId.valid
  val candidateIdentityValid = candidateValid && slotIdentityValid

  val matchVec = Wire(Vec(liqEntries, Bool()))
  for (idx <- 0 until liqEntries) {
    val row = io.rows(idx)
    matchVec(idx) :=
      row.valid &&
        (row.status === LoadInflightStatus.Resolved) &&
        ROBID.equal(row.bid, io.slotBid) &&
        ROBID.equal(row.gid, io.slotGid) &&
        ROBID.equal(row.rid, io.slotRid) &&
        ROBID.equal(row.loadLsId, io.slotLoadLsId)
  }

  val matchedMask = matchVec.asUInt
  val matchCount = PopCount(matchedMask)
  val resolvedRowMatch = candidateIdentityValid && matchedMask.orR
  val multipleResolvedRows = candidateIdentityValid && (matchCount > 1.U)
  val rowClearReady = resolvedRowMatch && !multipleResolvedRows
  val lifecycleReady = rowClearReady && io.lifecycleClearEnable

  io.active := active
  io.candidateValid := candidateValid
  io.slotIdentityValid := candidateValid && slotIdentityValid
  io.resolvedRowMatch := resolvedRowMatch
  io.matchedMask := Mux(candidateIdentityValid, matchedMask, 0.U(liqEntries.W))
  io.matchCount := Mux(candidateIdentityValid, matchCount, 0.U(matchCountWidth.W))
  io.rowClearIndex := Mux(rowClearReady, PriorityEncoder(matchedMask), 0.U(liqPtrWidth.W))
  io.rowClearReady := rowClearReady
  io.lifecycleReady := lifecycleReady
  io.blockedByDisabled := !io.enable && io.slotOccupied
  io.blockedByFlush := io.enable && io.flush && io.slotOccupied
  io.blockedByNoSlot := active && !io.slotOccupied
  io.blockedByInvalidSlotIdentity := candidateValid && !slotIdentityValid
  io.blockedByNoResolvedRow := candidateIdentityValid && !matchedMask.orR
  io.blockedByMultipleResolvedRows := multipleResolvedRows
  io.blockedByLifecycleClearDisabled := rowClearReady && !io.lifecycleClearEnable
  io.invalidLifecycleClearWithoutRow := io.lifecycleClearEnable && !rowClearReady
}
