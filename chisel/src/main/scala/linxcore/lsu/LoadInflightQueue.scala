package linxcore.lsu

import chisel3._
import chisel3.util._

import linxcore.commit.{CommitOperandTrace, CommitTraceParams}
import linxcore.rob.ROBID

object LoadInflightStatus extends ChiselEnum {
  val Idle, Wait, Repick, L1DcMiss, L2Wait, Resolved = Value
}

class LoadInflightAlloc(
    val liqEntries: Int,
    val idEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val sourceTraceParams =
    CommitTraceParams(regWidth = math.max(8, archRegWidth), dataWidth = addrWidth)

  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val loadLsId = new ROBID(idEntries)
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
  val returnSignExtend = Bool()
  val dst = new LoadReplayDestination(archRegWidth, physRegWidth)
  val sourceTraceValid = Bool()
  val source0 = new CommitOperandTrace(sourceTraceParams)
  val source1 = new CommitOperandTrace(sourceTraceParams)
  val youngestStoreId = new ROBID(idEntries)
  val youngestStoreLsId = new ROBID(idEntries)
  val isTile = Bool()
  val specWakeup = Bool()
  val stackValid = Bool()
}

class LoadInflightRow(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val sourceTraceParams =
    CommitTraceParams(regWidth = math.max(8, archRegWidth), dataWidth = addrWidth)

  val valid = Bool()
  val status = LoadInflightStatus()
  val loadId = new ROBID(liqEntries)
  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val loadLsId = new ROBID(idEntries)
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
  val returnSignExtend = Bool()
  val dst = new LoadReplayDestination(archRegWidth, physRegWidth)
  val sourceTraceValid = Bool()
  val source0 = new CommitOperandTrace(sourceTraceParams)
  val source1 = new CommitOperandTrace(sourceTraceParams)
  val youngestStoreId = new ROBID(idEntries)
  val youngestStoreLsId = new ROBID(idEntries)
  val isTile = Bool()
  val specWakeup = Bool()
  val stackValid = Bool()

  val lineData = UInt((lineBytes * 8).W)
  val validMask = UInt(lineBytes.W)
  val loadByteMask = UInt(lineBytes.W)
  val forwardMask = UInt(lineBytes.W)
  val waitMask = UInt(lineBytes.W)

  val waitStore = Bool()
  val waitStoreInfo = new LoadStoreForwardWait(idEntries, storeEntries, pcWidth)
  val storeBypass = Bool()
  val dataComplete = Bool()
  val sourcesReturned = Bool()
  val scbReturned = Bool()
  val stqReturned = Bool()
  val l1Hit = Bool()
  val l1Miss = Bool()
  val missKind = LoadForwardMissKind()
}

class LoadHitRecord(
    val liqEntries: Int,
    val idEntries: Int,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val pcWidth: Int = 64)
    extends Bundle {
  val loadId = new ROBID(liqEntries)
  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val loadLsId = new ROBID(idEntries)
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val lineAddr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
  val byteMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
  val forwardedMask = UInt(lineBytes.W)
}

class LoadInflightQueueIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)
  private val countWidth = log2Ceil(liqEntries + 1)

  val flush = Input(Bool())

  val allocValid = Input(Bool())
  val alloc = Input(new LoadInflightAlloc(
    liqEntries,
    idEntries,
    addrWidth,
    pcWidth,
    sizeWidth,
    archRegWidth,
    physRegWidth
  ))
  val allocReady = Output(Bool())
  val allocAccepted = Output(Bool())
  val allocIndex = Output(UInt(liqPtrWidth.W))
  val allocLoadId = Output(new ROBID(liqEntries))

  val launchValid = Input(Bool())
  val launchIndex = Input(UInt(liqPtrWidth.W))
  val launchReady = Output(Bool())
  val launchAccepted = Output(Bool())

  val pickValid = Input(Bool())
  val pickIndex = Input(UInt(liqPtrWidth.W))
  val pickReady = Output(Bool())
  val pickAccepted = Output(Bool())

  val scbReturnValid = Input(Bool())
  val scbReturnIndex = Input(UInt(liqPtrWidth.W))
  val scbReturnReady = Output(Bool())
  val scbReturnAccepted = Output(Bool())

  val e2Stores = Input(Vec(storeEntries, new LoadStoreForwardStore(idEntries, storeEntries, addrWidth, pcWidth, lineBytes)))
  val e2BaseData = Input(UInt((lineBytes * 8).W))
  val e2BaseValidMask = Input(UInt(lineBytes.W))
  val e2LoadDataReturned = Input(Bool())
  val e2ScbReturned = Input(Bool())
  val e2StqReturned = Input(Bool())
  val e2ReturnReady = Input(Bool())

  val replayWakeValid = Input(Bool())
  val replayWake = Input(new LoadReplayWakeupRequest(idEntries, addrWidth, pcWidth, lineBytes))
  val replayWakeWaitStoreClearMask = Output(UInt(liqEntries.W))
  val replayWakeMergeMask = Output(UInt(liqEntries.W))
  val replayWakeCompletedMask = Output(UInt(liqEntries.W))

  val refillValid = Input(Bool())
  val refill = Input(new LoadRefillWakeupRequest(addrWidth, lineBytes))
  val refillAccepted = Output(Bool())
  val refillWakeMask = Output(UInt(liqEntries.W))

  val clearResolvedValid = Input(Bool())
  val clearResolvedIndex = Input(UInt(liqPtrWidth.W))
  val clearResolvedAccepted = Output(Bool())

  val rowMutationValid = Input(Bool())
  val rowMutationTargetIndex = Input(UInt(liqPtrWidth.W))
  val rowMutationSetWaitStatus = Input(Bool())
  val rowMutationKeepRepickStatus = Input(Bool())
  val rowMutationClearReturnState = Input(Bool())
  val rowMutationLineWrite = Input(Bool())
  val rowMutationWaitStoreWrite = Input(Bool())
  val rowMutationNextWaitStore = Input(Bool())
  val rowMutationNextWaitStoreInfo = Input(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth))
  val rowMutationNextLineData = Input(UInt((lineBytes * 8).W))
  val rowMutationNextValidMask = Input(UInt(lineBytes.W))
  val rowMutationNextDataComplete = Input(Bool())
  val rowMutationNextScbReturned = Input(Bool())
  val rowMutationNextStqReturned = Input(Bool())
  val rowMutationNextStoreSourceReturned = Input(Bool())
  val rowMutationBridgeValid = Output(Bool())
  val rowMutationTargetEvidenceValid = Output(Bool())
  val rowMutationWriteConflict = Output(Bool())
  val rowMutationWriteEnable = Output(Bool())
  val rowMutationApplyValid = Output(Bool())
  val rowMutationBlockedByBridge = Output(Bool())
  val rowMutationBlockedByControl = Output(Bool())
  val rowMutationBlockedByApply = Output(Bool())
  val rowMutationControlBlockedByInvalidRow = Output(Bool())
  val rowMutationControlBlockedByNotRepick = Output(Bool())
  val rowMutationControlBlockedByScbNotReturned = Output(Bool())
  val rowMutationControlBlockedByE4UpdateConflict = Output(Bool())
  val rowMutationControlBlockedByClearResolvedConflict = Output(Bool())
  val rowMutationControlBlockedByReplayWakeConflict = Output(Bool())
  val rowMutationControlBlockedByRefillConflict = Output(Bool())
  val rowMutationControlBlockedByLaunchConflict = Output(Bool())
  val rowMutationControlBlockedByAllocationConflict = Output(Bool())

  val e4UpdateValid = Output(Bool())
  val e4UpdateIndex = Output(UInt(liqPtrWidth.W))
  val e4MissKind = Output(LoadForwardMissKind())
  val e4WakeupValid = Output(Bool())

  val lhqRecordValid = Output(Bool())
  val lhqRecord = Output(new LoadHitRecord(liqEntries, idEntries, addrWidth, lineBytes, sizeWidth))

  val rows = Output(Vec(
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
  val occupiedMask = Output(UInt(liqEntries.W))
  val waitMask = Output(UInt(liqEntries.W))
  val repickMask = Output(UInt(liqEntries.W))
  val missMask = Output(UInt(liqEntries.W))
  val resolvedMask = Output(UInt(liqEntries.W))
  val waitStoreMask = Output(UInt(liqEntries.W))
  val residentCount = Output(UInt(countWidth.W))
  val empty = Output(Bool())
  val full = Output(Bool())
  val missPending = Output(Bool())
}

class LoadInflightQueue(
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
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(lineBytes == 64, "LoadInflightQueue currently models 64-byte scalar cachelines")
  require(addrWidth >= 7, "LoadInflightQueue needs 64-byte line addresses")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  private val liqPtrWidth = log2Ceil(liqEntries)
  private val countWidth = log2Ceil(liqEntries + 1)
  private val lineOffsetWidth = log2Ceil(lineBytes)

  val io = IO(new LoadInflightQueueIO(
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

  private def zeroWait: LoadStoreForwardWait =
    0.U.asTypeOf(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth))

  private def zeroRow: LoadInflightRow = {
    val row = Wire(new LoadInflightRow(
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
    row := 0.U.asTypeOf(row)
    row.status := LoadInflightStatus.Idle
    row.missKind := LoadForwardMissKind.NoMiss
    row.waitStoreInfo := zeroWait
    row.loadId := ROBID.disabled(liqEntries)
    row.dst := LoadReplayDestination.none(archRegWidth, physRegWidth)
    row.bid := ROBID.disabled(idEntries)
    row.gid := ROBID.disabled(idEntries)
    row.rid := ROBID.disabled(idEntries)
    row.loadLsId := ROBID.disabled(idEntries)
    row.youngestStoreId := ROBID.disabled(idEntries)
    row.youngestStoreLsId := ROBID.disabled(idEntries)
    row
  }

  private def zeroHitRecord: LoadHitRecord =
    0.U.asTypeOf(new LoadHitRecord(liqEntries, idEntries, addrWidth, lineBytes, sizeWidth))

  private def currentLoadId: ROBID = {
    val id = Wire(new ROBID(liqEntries))
    id.valid := true.B
    id.wrap := allocWrap
    id.value := allocPtr
    id
  }

  private def lineAddr(addr: UInt): UInt =
    Cat(addr(addrWidth - 1, lineOffsetWidth), 0.U(lineOffsetWidth.W))

  val rows = RegInit(VecInit(Seq.fill(liqEntries)(zeroRow)))
  val allocPtr = RegInit(0.U(liqPtrWidth.W))
  val allocWrap = RegInit(false.B)
  val residentCount = RegInit(0.U(countWidth.W))

  val pipeline = Module(new LoadForwardPipeline(idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))
  pipeline.io.flush := io.flush
  pipeline.io.e2Stores := io.e2Stores
  pipeline.io.e2LoadDataReturned := io.e2LoadDataReturned
  pipeline.io.e2ScbReturned := io.e2ScbReturned
  pipeline.io.e2StqReturned := io.e2StqReturned
  pipeline.io.e2ReturnReady := io.e2ReturnReady

  val launchRow = rows(io.launchIndex)
  val launchReady =
    !io.flush && launchRow.valid && (launchRow.status === LoadInflightStatus.Wait) && !launchRow.waitStore
  val launchAccepted = io.launchValid && launchReady
  val launchUsesRowData = launchRow.validMask.orR
  val pickRow = rows(io.pickIndex)
  val pickReady =
    !io.flush && pickRow.valid && (pickRow.status === LoadInflightStatus.Wait) && !pickRow.waitStore
  val pickAccepted = io.pickValid && pickReady
  val scbReturnRow = rows(io.scbReturnIndex)
  val scbReturnReady =
    !io.flush && scbReturnRow.valid && (scbReturnRow.status === LoadInflightStatus.Repick) && !scbReturnRow.scbReturned
  val scbReturnAccepted = io.scbReturnValid && scbReturnReady

  pipeline.io.e2BaseData := Mux(launchUsesRowData, launchRow.lineData, io.e2BaseData)
  pipeline.io.e2BaseValidMask := Mux(launchUsesRowData, launchRow.validMask, io.e2BaseValidMask)

  val query = Wire(new LoadStoreForwardQuery(idEntries, addrWidth, lineBytes, sizeWidth))
  query := 0.U.asTypeOf(query)
  query.valid := launchAccepted
  query.lineAddr := lineAddr(launchRow.addr)
  query.byteOffset := launchRow.addr(lineOffsetWidth - 1, 0)
  query.size := launchRow.size
  query.youngestStoreId := launchRow.youngestStoreId
  query.youngestStoreLsId := launchRow.youngestStoreLsId
  query.isTile := launchRow.isTile

  pipeline.io.e2Valid := launchAccepted
  pipeline.io.e2Query := query

  val replayWakeup = Module(new LoadReplayWakeup(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))
  replayWakeup.io.wakeValid := io.replayWakeValid && !io.flush
  replayWakeup.io.wake := io.replayWake
  replayWakeup.io.rows := rows

  val refillWakeup = Module(new LoadRefillWakeup(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))
  refillWakeup.io.refillValid := io.refillValid && !io.flush
  refillWakeup.io.refill := io.refill
  refillWakeup.io.rows := rows

  val e3IndexValid = RegInit(false.B)
  val e3Index = RegInit(0.U(liqPtrWidth.W))
  val e4IndexValid = RegInit(false.B)
  val e4Index = RegInit(0.U(liqPtrWidth.W))

  val allocLoadId = currentLoadId
  val allocReady = !io.flush && !rows(allocPtr).valid
  val allocAccepted = io.allocValid && allocReady
  val clearResolvedReady =
    !io.flush && rows(io.clearResolvedIndex).valid && (rows(io.clearResolvedIndex).status === LoadInflightStatus.Resolved)
  val clearResolvedAccepted = io.clearResolvedValid && clearResolvedReady

  val e4UpdateValid =
    e4IndexValid && pipeline.io.e4Valid && rows(e4Index).valid && (rows(e4Index).status === LoadInflightStatus.Repick)
  val e4Resolved = e4UpdateValid && pipeline.io.e4WakeupValid && (pipeline.io.e4MissKind === LoadForwardMissKind.NoMiss)
  val e4StoreWait = e4UpdateValid && (pipeline.io.e4MissKind === LoadForwardMissKind.StoreDataNotReady)
  val e4DataMiss = e4UpdateValid && (pipeline.io.e4MissKind === LoadForwardMissKind.DataNotComplete)
  val e4ReplayWait =
    e4UpdateValid &&
      ((pipeline.io.e4MissKind === LoadForwardMissKind.AwaitingSources) ||
        (pipeline.io.e4MissKind === LoadForwardMissKind.ReturnPortBlocked))

  val lhqRecord = Wire(new LoadHitRecord(liqEntries, idEntries, addrWidth, lineBytes, sizeWidth))
  lhqRecord := zeroHitRecord
  lhqRecord.loadId := rows(e4Index).loadId
  lhqRecord.bid := rows(e4Index).bid
  lhqRecord.gid := rows(e4Index).gid
  lhqRecord.rid := rows(e4Index).rid
  lhqRecord.loadLsId := rows(e4Index).loadLsId
  lhqRecord.pc := rows(e4Index).pc
  lhqRecord.addr := rows(e4Index).addr
  lhqRecord.lineAddr := lineAddr(rows(e4Index).addr)
  lhqRecord.size := rows(e4Index).size
  lhqRecord.byteMask := pipeline.io.e4LoadByteMask
  lhqRecord.data := pipeline.io.e4LineData
  lhqRecord.forwardedMask := pipeline.io.e4ForwardMask

  val rowMutationPath = Module(new LoadInflightRowMutationPath(
    liqEntries = liqEntries,
    idEntries = idEntries,
    sourceStoreEntries = storeEntries,
    storeEntries = storeEntries,
    addrWidth = addrWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    sizeWidth = sizeWidth,
    archRegWidth = archRegWidth,
    physRegWidth = physRegWidth
  ))
  val rowMutationReplayConflictMask = replayWakeup.io.waitStoreClearMask | replayWakeup.io.mergeMask
  val rowMutationTargetMask = UIntToOH(io.rowMutationTargetIndex, liqEntries)
  val rowMutationReplayConflictVec = VecInit((0 until liqEntries).map(idx => rowMutationReplayConflictMask(idx)))
  val rowMutationRefillConflictVec = VecInit((0 until liqEntries).map(idx => refillWakeup.io.wakeMask(idx)))
  rowMutationPath.io.enable := true.B
  rowMutationPath.io.flush := io.flush
  rowMutationPath.io.requestValid := io.rowMutationValid
  rowMutationPath.io.requestTargetMask := rowMutationTargetMask
  rowMutationPath.io.requestTargetIndex := io.rowMutationTargetIndex
  rowMutationPath.io.row := rows(io.rowMutationTargetIndex)
  rowMutationPath.io.setWaitStatus := io.rowMutationSetWaitStatus
  rowMutationPath.io.keepRepickStatus := io.rowMutationKeepRepickStatus
  rowMutationPath.io.clearReturnState := io.rowMutationClearReturnState
  rowMutationPath.io.lineWrite := io.rowMutationLineWrite
  rowMutationPath.io.waitStoreWrite := io.rowMutationWaitStoreWrite
  rowMutationPath.io.nextWaitStore := io.rowMutationNextWaitStore
  rowMutationPath.io.nextWaitStoreInfo := io.rowMutationNextWaitStoreInfo
  rowMutationPath.io.nextLineData := io.rowMutationNextLineData
  rowMutationPath.io.nextValidMask := io.rowMutationNextValidMask
  rowMutationPath.io.nextDataComplete := io.rowMutationNextDataComplete
  rowMutationPath.io.nextScbReturned := io.rowMutationNextScbReturned
  rowMutationPath.io.nextStqReturned := io.rowMutationNextStqReturned
  rowMutationPath.io.nextStoreSourceReturned := io.rowMutationNextStoreSourceReturned
  rowMutationPath.io.e4UpdateConflict := e4UpdateValid && (e4Index === io.rowMutationTargetIndex)
  rowMutationPath.io.clearResolvedConflict := clearResolvedAccepted && (io.clearResolvedIndex === io.rowMutationTargetIndex)
  rowMutationPath.io.replayWakeConflict := io.replayWakeValid && rowMutationReplayConflictVec(io.rowMutationTargetIndex)
  rowMutationPath.io.refillConflict := io.refillValid && rowMutationRefillConflictVec(io.rowMutationTargetIndex)
  rowMutationPath.io.launchConflict :=
    (launchAccepted && (io.launchIndex === io.rowMutationTargetIndex)) ||
      (pickAccepted && (io.pickIndex === io.rowMutationTargetIndex)) ||
      (scbReturnAccepted && (io.scbReturnIndex === io.rowMutationTargetIndex))
  rowMutationPath.io.allocationConflict := allocAccepted && (allocPtr === io.rowMutationTargetIndex)

  when(io.flush) {
    for (idx <- 0 until liqEntries) {
      rows(idx) := zeroRow
    }
    residentCount := 0.U
    allocPtr := 0.U
    allocWrap := false.B
    e3IndexValid := false.B
    e4IndexValid := false.B
  }.otherwise {
    e4IndexValid := e3IndexValid
    e4Index := e3Index
    e3IndexValid := launchAccepted
    e3Index := io.launchIndex

    when(e4UpdateValid) {
      rows(e4Index).lineData := pipeline.io.e4LineData
      rows(e4Index).validMask := pipeline.io.e4ValidMask
      rows(e4Index).loadByteMask := pipeline.io.e4LoadByteMask
      rows(e4Index).forwardMask := pipeline.io.e4ForwardMask
      rows(e4Index).waitMask := pipeline.io.e4WaitMask
      rows(e4Index).dataComplete := pipeline.io.e4DataComplete
      rows(e4Index).sourcesReturned := pipeline.io.e4SourcesReturned
      rows(e4Index).scbReturned := pipeline.io.e4ScbReturned
      rows(e4Index).stqReturned := pipeline.io.e4StqReturned
      rows(e4Index).missKind := pipeline.io.e4MissKind
      rows(e4Index).storeBypass := pipeline.io.e4ForwardMask.orR

      when(e4Resolved) {
        rows(e4Index).status := LoadInflightStatus.Resolved
        rows(e4Index).waitStore := false.B
        rows(e4Index).waitStoreInfo := zeroWait
        rows(e4Index).l1Hit := false.B
        rows(e4Index).l1Miss := false.B
      }.elsewhen(e4StoreWait) {
        rows(e4Index).status := LoadInflightStatus.Wait
        rows(e4Index).waitStore := true.B
        rows(e4Index).waitStoreInfo := pipeline.io.e4WaitStore
        rows(e4Index).validMask := 0.U
        rows(e4Index).loadByteMask := 0.U
        rows(e4Index).forwardMask := 0.U
        rows(e4Index).waitMask := 0.U
        rows(e4Index).dataComplete := false.B
        rows(e4Index).sourcesReturned := false.B
        rows(e4Index).scbReturned := false.B
        rows(e4Index).stqReturned := false.B
        rows(e4Index).l1Hit := false.B
      }.elsewhen(e4DataMiss) {
        rows(e4Index).status := LoadInflightStatus.L1DcMiss
        rows(e4Index).waitStore := false.B
        rows(e4Index).waitStoreInfo := zeroWait
        rows(e4Index).validMask := 0.U
        rows(e4Index).loadByteMask := 0.U
        rows(e4Index).forwardMask := 0.U
        rows(e4Index).waitMask := 0.U
        rows(e4Index).dataComplete := false.B
        rows(e4Index).sourcesReturned := false.B
        rows(e4Index).scbReturned := false.B
        rows(e4Index).stqReturned := false.B
        rows(e4Index).l1Hit := false.B
        rows(e4Index).l1Miss := true.B
      }.elsewhen(e4ReplayWait) {
        rows(e4Index).status := LoadInflightStatus.Wait
        rows(e4Index).waitStore := false.B
        rows(e4Index).waitStoreInfo := zeroWait
        rows(e4Index).validMask := 0.U
        rows(e4Index).loadByteMask := 0.U
        rows(e4Index).forwardMask := 0.U
        rows(e4Index).waitMask := 0.U
        rows(e4Index).dataComplete := false.B
        rows(e4Index).sourcesReturned := false.B
        rows(e4Index).scbReturned := false.B
        rows(e4Index).stqReturned := false.B
        rows(e4Index).l1Hit := false.B
      }
    }

    when(clearResolvedAccepted) {
      rows(io.clearResolvedIndex) := zeroRow
    }

    when(io.replayWakeValid) {
      for (idx <- 0 until liqEntries) {
        when(replayWakeup.io.waitStoreClearMask(idx)) {
          rows(idx).waitStore := false.B
          rows(idx).waitStoreInfo := zeroWait
        }

        when(replayWakeup.io.mergeMask(idx)) {
          rows(idx).lineData := replayWakeup.io.mergedLineData(idx)
          rows(idx).validMask := replayWakeup.io.mergedValidMasks(idx)
          rows(idx).loadByteMask := replayWakeup.io.requestByteMasks(idx)
          when(replayWakeup.io.completedMask(idx)) {
            rows(idx).status := LoadInflightStatus.Wait
            rows(idx).storeBypass := true.B
            rows(idx).dataComplete := true.B
            rows(idx).sourcesReturned := true.B
            when(io.replayWake.source === LoadReplayWakeSource.StoreUnit) {
              rows(idx).stqReturned := true.B
            }
            when(io.replayWake.source === LoadReplayWakeSource.StoreCoalescingBuffer) {
              rows(idx).scbReturned := true.B
            }
            rows(idx).missKind := LoadForwardMissKind.NoMiss
          }
        }
      }
    }

    when(io.refillValid) {
      for (idx <- 0 until liqEntries) {
        val sameRowResolvedAtE4 = e4UpdateValid && e4Resolved && (e4Index === idx.U)
        when(refillWakeup.io.wakeMask(idx) && !sameRowResolvedAtE4) {
          rows(idx).status := LoadInflightStatus.Wait
          rows(idx).lineData := io.refill.data
          rows(idx).validMask := refillWakeup.io.lineValidMask
          rows(idx).loadByteMask := refillWakeup.io.requestByteMasks(idx)
          rows(idx).forwardMask := 0.U
          rows(idx).waitMask := 0.U
          rows(idx).l1Hit := true.B
          rows(idx).dataComplete := false.B
          rows(idx).sourcesReturned := false.B
          rows(idx).scbReturned := false.B
          rows(idx).stqReturned := false.B
          rows(idx).missKind := LoadForwardMissKind.NoMiss
        }
      }
    }

    when(launchAccepted) {
      rows(io.launchIndex).status := LoadInflightStatus.Repick
      rows(io.launchIndex).waitStore := false.B
      rows(io.launchIndex).missKind := LoadForwardMissKind.NoMiss
    }

    when(pickAccepted && !(launchAccepted && (io.launchIndex === io.pickIndex))) {
      rows(io.pickIndex).status := LoadInflightStatus.Repick
      rows(io.pickIndex).waitStore := false.B
      rows(io.pickIndex).missKind := LoadForwardMissKind.NoMiss
    }

    when(scbReturnAccepted) {
      rows(io.scbReturnIndex).scbReturned := true.B
    }

    when(allocAccepted) {
      rows(allocPtr) := zeroRow
      rows(allocPtr).valid := true.B
      rows(allocPtr).status := LoadInflightStatus.Wait
      rows(allocPtr).loadId := allocLoadId
      rows(allocPtr).bid := io.alloc.bid
      rows(allocPtr).gid := io.alloc.gid
      rows(allocPtr).rid := io.alloc.rid
      rows(allocPtr).loadLsId := io.alloc.loadLsId
      rows(allocPtr).pc := io.alloc.pc
      rows(allocPtr).addr := io.alloc.addr
      rows(allocPtr).size := io.alloc.size
      rows(allocPtr).returnSignExtend := io.alloc.returnSignExtend
      rows(allocPtr).dst := io.alloc.dst
      rows(allocPtr).sourceTraceValid := io.alloc.sourceTraceValid
      rows(allocPtr).source0 := io.alloc.source0
      rows(allocPtr).source1 := io.alloc.source1
      rows(allocPtr).youngestStoreId := io.alloc.youngestStoreId
      rows(allocPtr).youngestStoreLsId := io.alloc.youngestStoreLsId
      rows(allocPtr).isTile := io.alloc.isTile
      rows(allocPtr).specWakeup := io.alloc.specWakeup
      rows(allocPtr).stackValid := io.alloc.stackValid

      when(allocPtr === (liqEntries - 1).U) {
        allocPtr := 0.U
        allocWrap := !allocWrap
      }.otherwise {
        allocPtr := allocPtr + 1.U
      }
    }

    when(rowMutationPath.io.writeEnable) {
      rows(io.rowMutationTargetIndex) := rowMutationPath.io.nextRow
    }

    residentCount := residentCount + allocAccepted.asUInt - clearResolvedAccepted.asUInt
  }

  val occupiedVec = Wire(Vec(liqEntries, Bool()))
  val waitVec = Wire(Vec(liqEntries, Bool()))
  val repickVec = Wire(Vec(liqEntries, Bool()))
  val missVec = Wire(Vec(liqEntries, Bool()))
  val resolvedVec = Wire(Vec(liqEntries, Bool()))
  val waitStoreVec = Wire(Vec(liqEntries, Bool()))
  for (idx <- 0 until liqEntries) {
    val status = rows(idx).status
    occupiedVec(idx) := rows(idx).valid
    waitVec(idx) := rows(idx).valid && (status === LoadInflightStatus.Wait)
    repickVec(idx) := rows(idx).valid && (status === LoadInflightStatus.Repick)
    missVec(idx) := rows(idx).valid && ((status === LoadInflightStatus.L1DcMiss) || (status === LoadInflightStatus.L2Wait))
    resolvedVec(idx) := rows(idx).valid && (status === LoadInflightStatus.Resolved)
    waitStoreVec(idx) := rows(idx).valid && rows(idx).waitStore
    io.rows(idx) := rows(idx)
  }

  io.allocReady := allocReady
  io.allocAccepted := allocAccepted
  io.allocIndex := allocPtr
  io.allocLoadId := allocLoadId
  io.launchReady := launchReady
  io.launchAccepted := launchAccepted
  io.pickReady := pickReady
  io.pickAccepted := pickAccepted
  io.scbReturnReady := scbReturnReady
  io.scbReturnAccepted := scbReturnAccepted
  io.clearResolvedAccepted := clearResolvedAccepted
  io.rowMutationBridgeValid := rowMutationPath.io.bridgeValid
  io.rowMutationTargetEvidenceValid := rowMutationPath.io.targetEvidenceValid
  io.rowMutationWriteConflict := rowMutationPath.io.writeConflict
  io.rowMutationWriteEnable := rowMutationPath.io.writeEnable
  io.rowMutationApplyValid := rowMutationPath.io.applyValid
  io.rowMutationBlockedByBridge := rowMutationPath.io.blockedByBridge
  io.rowMutationBlockedByControl := rowMutationPath.io.blockedByControl
  io.rowMutationBlockedByApply := rowMutationPath.io.blockedByApply
  io.rowMutationControlBlockedByInvalidRow := rowMutationPath.io.controlBlockedByInvalidRow
  io.rowMutationControlBlockedByNotRepick := rowMutationPath.io.controlBlockedByNotRepick
  io.rowMutationControlBlockedByScbNotReturned := rowMutationPath.io.controlBlockedByScbNotReturned
  io.rowMutationControlBlockedByE4UpdateConflict := rowMutationPath.io.controlBlockedByE4UpdateConflict
  io.rowMutationControlBlockedByClearResolvedConflict := rowMutationPath.io.controlBlockedByClearResolvedConflict
  io.rowMutationControlBlockedByReplayWakeConflict := rowMutationPath.io.controlBlockedByReplayWakeConflict
  io.rowMutationControlBlockedByRefillConflict := rowMutationPath.io.controlBlockedByRefillConflict
  io.rowMutationControlBlockedByLaunchConflict := rowMutationPath.io.controlBlockedByLaunchConflict
  io.rowMutationControlBlockedByAllocationConflict := rowMutationPath.io.controlBlockedByAllocationConflict
  io.replayWakeWaitStoreClearMask := replayWakeup.io.waitStoreClearMask
  io.replayWakeMergeMask := replayWakeup.io.mergeMask
  io.replayWakeCompletedMask := replayWakeup.io.completedMask
  io.refillAccepted := refillWakeup.io.refillAccepted
  io.refillWakeMask := refillWakeup.io.wakeMask
  io.e4UpdateValid := e4UpdateValid
  io.e4UpdateIndex := e4Index
  io.e4MissKind := pipeline.io.e4MissKind
  io.e4WakeupValid := pipeline.io.e4WakeupValid
  io.lhqRecordValid := e4Resolved
  io.lhqRecord := lhqRecord
  io.occupiedMask := occupiedVec.asUInt
  io.waitMask := waitVec.asUInt
  io.repickMask := repickVec.asUInt
  io.missMask := missVec.asUInt
  io.resolvedMask := resolvedVec.asUInt
  io.waitStoreMask := waitStoreVec.asUInt
  io.residentCount := residentCount
  io.empty := residentCount === 0.U
  io.full := residentCount === liqEntries.U
  io.missPending := missVec.asUInt.orR || e4DataMiss
}
