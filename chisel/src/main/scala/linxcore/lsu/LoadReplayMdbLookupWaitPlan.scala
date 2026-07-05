package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PopCount, PriorityEncoder}

import linxcore.rob.ROBID

class LoadReplayMdbLookupWaitPlanIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val sizeWidth: Int,
    val archRegWidth: Int,
    val physRegWidth: Int,
    val confWidth: Int,
    val weightWidth: Int)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val luOutValid = Input(Bool())
  val luOut = Input(new MDBQueueBus(
    idEntries,
    addrWidth,
    pcWidth,
    stidWidth = 8,
    sizeWidth = sizeWidth,
    confWidth = confWidth,
    weightWidth = weightWidth
  ))
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
  val storeIndexValid = Input(Bool())
  val storeIndex = Input(UInt(log2Ceil(storeEntries).W))
  val storeLsIdValid = Input(Bool())
  val storeLsId = Input(new ROBID(idEntries))

  val active = Output(Bool())
  val lookupIntent = Output(Bool())
  val lookupHit = Output(Bool())
  val candidateMask = Output(UInt(liqEntries.W))
  val candidateCount = Output(UInt(log2Ceil(liqEntries + 1).W))
  val targetValid = Output(Bool())
  val targetIndex = Output(UInt(liqPtrWidth.W))
  val multiTarget = Output(Bool())
  val waitIntentValid = Output(Bool())

  val requestValid = Output(Bool())
  val requestTargetMask = Output(UInt(liqEntries.W))
  val requestTargetIndex = Output(UInt(liqPtrWidth.W))
  val setWaitStatus = Output(Bool())
  val keepRepickStatus = Output(Bool())
  val clearReturnState = Output(Bool())
  val lineWrite = Output(Bool())
  val waitStoreWrite = Output(Bool())
  val nextWaitStore = Output(Bool())
  val nextWaitStoreInfo = Output(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth))
  val nextLineData = Output(UInt((lineBytes * 8).W))
  val nextValidMask = Output(UInt(lineBytes.W))
  val nextDataComplete = Output(Bool())
  val nextScbReturned = Output(Bool())
  val nextStqReturned = Output(Bool())
  val nextStoreSourceReturned = Output(Bool())

  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoLookup = Output(Bool())
  val blockedByLookupMiss = Output(Bool())
  val blockedByMissingLoadInfo = Output(Bool())
  val blockedByMissingStoreInfo = Output(Bool())
  val blockedByTile = Output(Bool())
  val blockedByNoTarget = Output(Bool())
  val blockedByMultiTarget = Output(Bool())
  val blockedByMissingStoreIndex = Output(Bool())
  val blockedByMissingStoreLsId = Output(Bool())
}

class LoadReplayMdbLookupWaitPlan(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val confWidth: Int = 2,
    val weightWidth: Int = 2)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(lineBytes == 64, "MDB lookup wait plan currently models 64-byte scalar cachelines")

  val io = IO(new LoadReplayMdbLookupWaitPlanIO(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    confWidth,
    weightWidth
  ))

  private def zeroWait: LoadStoreForwardWait =
    0.U.asTypeOf(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth))

  val active = io.enable && !io.flush
  val lookupIntent = io.luOutValid && io.luOut.valid
  val loadInfoValid = io.luOut.ldInfo.valid && io.luOut.ldInfo.bid.valid && io.luOut.ldInfo.lsId.valid
  val storeInfoValid = io.luOut.stInfo.valid && io.luOut.stInfo.bid.valid
  val scalarLookup = !io.luOut.ldInfo.isTile && !io.luOut.stInfo.isTile
  val lookupHit = active && lookupIntent && io.luOut.hit && loadInfoValid && storeInfoValid && scalarLookup

  val candidateVec = Wire(Vec(liqEntries, Bool()))
  for (idx <- 0 until liqEntries) {
    val row = io.rows(idx)
    candidateVec(idx) :=
      lookupHit &&
        row.valid &&
        row.status === LoadInflightStatus.Repick &&
        !row.isTile &&
        row.bid.valid &&
        row.loadLsId.valid &&
        ROBID.equal(row.bid, io.luOut.ldInfo.bid) &&
        ROBID.equal(row.loadLsId, io.luOut.ldInfo.lsId)
  }

  val candidateMask = candidateVec.asUInt
  val candidateCount = PopCount(candidateVec)
  val targetIndex = PriorityEncoder(candidateMask)
  val targetValid = lookupHit && candidateCount === 1.U
  val multiTarget = lookupHit && candidateCount > 1.U
  val waitIntentValid = targetValid
  val resolvedStoreIdentity = io.storeIndexValid && io.storeLsIdValid && io.storeLsId.valid
  val requestValid = waitIntentValid && resolvedStoreIdentity

  val waitInfo = Wire(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth))
  waitInfo := zeroWait
  waitInfo.valid := requestValid
  waitInfo.storeIndex := io.storeIndex
  waitInfo.storeId := io.luOut.stInfo.bid
  waitInfo.storeLsId := io.storeLsId
  waitInfo.pc := io.luOut.stInfo.pc

  io.active := active
  io.lookupIntent := lookupIntent
  io.lookupHit := lookupHit
  io.candidateMask := candidateMask
  io.candidateCount := candidateCount
  io.targetValid := targetValid
  io.targetIndex := targetIndex
  io.multiTarget := multiTarget
  io.waitIntentValid := waitIntentValid

  io.requestValid := requestValid
  io.requestTargetMask := Mux(requestValid, candidateMask, 0.U)
  io.requestTargetIndex := targetIndex
  io.setWaitStatus := requestValid
  io.keepRepickStatus := false.B
  io.clearReturnState := requestValid
  io.lineWrite := requestValid
  io.waitStoreWrite := requestValid
  io.nextWaitStore := requestValid
  io.nextWaitStoreInfo := waitInfo
  io.nextLineData := 0.U
  io.nextValidMask := 0.U
  io.nextDataComplete := false.B
  io.nextScbReturned := false.B
  io.nextStqReturned := false.B
  io.nextStoreSourceReturned := false.B

  io.blockedByDisabled := !io.enable && lookupIntent
  io.blockedByFlush := io.enable && io.flush && lookupIntent
  io.blockedByNoLookup := active && !lookupIntent
  io.blockedByLookupMiss := active && lookupIntent && !io.luOut.hit
  io.blockedByMissingLoadInfo := active && lookupIntent && io.luOut.hit && !loadInfoValid
  io.blockedByMissingStoreInfo := active && lookupIntent && io.luOut.hit && loadInfoValid && !storeInfoValid
  io.blockedByTile := active && lookupIntent && io.luOut.hit && loadInfoValid && storeInfoValid && !scalarLookup
  io.blockedByNoTarget := lookupHit && candidateCount === 0.U
  io.blockedByMultiTarget := multiTarget
  io.blockedByMissingStoreIndex := waitIntentValid && !io.storeIndexValid
  io.blockedByMissingStoreLsId := waitIntentValid && (!io.storeLsIdValid || !io.storeLsId.valid)
}
