package linxcore.lsu

import chisel3._
import chisel3.util._

import linxcore.commit.{CommitOperandTrace, CommitTraceParams}
import linxcore.recovery.FlushBus
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
    val physRegWidth: Int = 6,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val returnPipeCount: Int = 1,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))
  private val sourceTraceParams =
    CommitTraceParams(regWidth = math.max(8, archRegWidth), dataWidth = addrWidth)

  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val loadLsId = new ROBID(idEntries)
  val loadLsIdFullValid = Bool()
  val loadLsIdFull = UInt(lsidWidth.W)
  val attempt = new LoadAttemptIdentity
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
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
  val youngestStoreLsIdFullValid = Bool()
  val youngestStoreLsIdFull = UInt(lsidWidth.W)
  val isTile = Bool()
  val specWakeup = Bool()
  val stackValid = Bool()
  val returnPipeIndex = UInt(returnPipeIndexWidth.W)
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
    val physRegWidth: Int = 6,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val returnPipeCount: Int = 1,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))
  private val sourceTraceParams =
    CommitTraceParams(regWidth = math.max(8, archRegWidth), dataWidth = addrWidth)

  val valid = Bool()
  val status = LoadInflightStatus()
  val loadId = new ROBID(liqEntries)
  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val loadLsId = new ROBID(idEntries)
  val loadLsIdFullValid = Bool()
  val loadLsIdFull = UInt(lsidWidth.W)
  val attempt = new LoadAttemptIdentity
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
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
  val youngestStoreLsIdFullValid = Bool()
  val youngestStoreLsIdFull = UInt(lsidWidth.W)
  val isTile = Bool()
  val specWakeup = Bool()
  val stackValid = Bool()
  val returnPipeIndex = UInt(returnPipeIndexWidth.W)
  val forwardPending = Bool()

  val lineData = UInt((lineBytes * 8).W)
  val validMask = UInt(lineBytes.W)
  val loadByteMask = UInt(lineBytes.W)
  val forwardMask = UInt(lineBytes.W)
  val waitMask = UInt(lineBytes.W)

  val crossLine = Bool()
  val secondSegmentActive = Bool()
  val firstSegmentDone = Bool()
  val firstLineData = UInt((lineBytes * 8).W)
  val firstValidMask = UInt(lineBytes.W)
  val firstLoadByteMask = UInt(lineBytes.W)
  val firstForwardMask = UInt(lineBytes.W)

  val waitStore = Bool()
  val waitStoreInfo = new LoadStoreForwardWait(idEntries, storeEntries, pcWidth, lsidWidth)
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
    val pcWidth: Int = 64,
    val lsidWidth: Int = 32)
    extends Bundle {
  val loadId = new ROBID(liqEntries)
  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val loadLsId = new ROBID(idEntries)
  val loadLsIdFullValid = Bool()
  val loadLsIdFull = UInt(lsidWidth.W)
  val attempt = new LoadAttemptIdentity
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val lineAddr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
  val byteMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
  val forwardedMask = UInt(lineBytes.W)
}

/** One fully classified forwarding result presented to the canonical LIQ. */
class LoadInflightForwardResult(
    val idEntries: Int,
    val storeEntries: Int,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32)
    extends Bundle {
  val identity = new STQLoadForwardResultIdentity
  val lineData = UInt((lineBytes * 8).W)
  val validMask = UInt(lineBytes.W)
  val loadByteMask = UInt(lineBytes.W)
  val forwardMask = UInt(lineBytes.W)
  val waitMask = UInt(lineBytes.W)
  val dataComplete = Bool()
  val sourcesReturned = Bool()
  val scbReturned = Bool()
  val stqReturned = Bool()
  val wakeupValid = Bool()
  val waitStore = new LoadStoreForwardWait(
    idEntries, storeEntries, pcWidth, lsidWidth)
  val missKind = LoadForwardMissKind()
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
    val physRegWidth: Int = 6,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val returnPipeCount: Int = 1,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)
  private val countWidth = log2Ceil(liqEntries + 1)

  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(idEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val flushPruneMask = Output(UInt(liqEntries.W))
  val flushPruneCount = Output(UInt(countWidth.W))

  val allocValid = Input(Bool())
  val alloc = Input(new LoadInflightAlloc(
    liqEntries,
    idEntries,
    addrWidth,
    pcWidth,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    returnPipeCount,
    lsidWidth
  ))
  val allocReady = Output(Bool())
  val allocAccepted = Output(Bool())
  val allocIndex = Output(UInt(liqPtrWidth.W))
  val allocLoadId = Output(new ROBID(liqEntries))
  val allocAttemptMalformed = Output(Bool())

  val attemptRebindValid = Input(Bool())
  val attemptRebind = Input(new LoadAttemptRebind(liqEntries))
  val attemptRebindReady = Output(Bool())
  val attemptRebindAccepted = Output(Bool())
  val attemptRebindBlockedByFlush = Output(Bool())
  val attemptRebindBlockedByInvalidLoadId = Output(Bool())
  val attemptRebindBlockedByNonresidentRow = Output(Bool())
  val attemptRebindBlockedByLifecycle = Output(Bool())
  val attemptRebindBlockedByStaleAttempt = Output(Bool())
  val attemptRebindBlockedByNextAttempt = Output(Bool())

  val structuralRetryValid = Input(Bool())
  val structuralRetry = Input(new LoadStructuralBlockRetry(
    idEntries, storeEntries, pcWidth, lsidWidth))
  val structuralRetryReady = Output(Bool())
  val structuralRetryAccepted = Output(Bool())
  val structuralRetryBlockedByLoadId = Output(Bool())
  val structuralRetryBlockedByAttempt = Output(Bool())
  val structuralRetryBlockedByNextAttempt = Output(Bool())
  val structuralRetryBlockedByPipe = Output(Bool())
  val structuralRetryBlockedByLifecycle = Output(Bool())
  val structuralRetryBlockedByWaitStore = Output(Bool())
  val structuralRetryBlockedByMutation = Output(Bool())

  val launchValid = Input(Bool())
  val launchIndex = Input(UInt(liqPtrWidth.W))
  // Raw launch intent is kept separate from the credit-qualified launch
  // request.  Replay rebind arbitration must conservatively exclude a
  // same-row launch without depending on downstream return-path readiness.
  val launchIntentValid = Input(Bool())
  val launchIntentIndex = Input(UInt(liqPtrWidth.W))
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

  val markResolvedValid = Input(Bool())
  val markResolvedIndex = Input(UInt(liqPtrWidth.W))
  val markResolvedReady = Output(Bool())
  val markResolvedAccepted = Output(Bool())

  val e2Stores = Input(Vec(storeEntries, new LoadStoreForwardStore(
    idEntries, storeEntries, addrWidth, pcWidth, lineBytes, lsidWidth)))
  val e2BaseData = Input(UInt((lineBytes * 8).W))
  val e2BaseValidMask = Input(UInt(lineBytes.W))
  val e2LoadDataReturned = Input(Bool())
  val e2ScbReturned = Input(Bool())
  val e2StqReturned = Input(Bool())
  val e2ReturnReady = Input(Bool())

  val forwardResultValid = Input(Bool())
  val forwardResult = Input(new LoadInflightForwardResult(
    idEntries, storeEntries, pcWidth, lineBytes, lsidWidth))
  val forwardResultAccepted = Output(Bool())
  val forwardResultRejected = Output(Bool())
  val forwardResultRejectedByLoadId = Output(Bool())
  val forwardResultRejectedByAttempt = Output(Bool())
  val forwardResultRejectedByPipe = Output(Bool())
  val forwardResultRejectedByLifecycle = Output(Bool())
  val forwardResultRejectedPermanent = Output(Bool())
  val forwardResultRetryRequired = Output(Bool())
  val forwardResultRetryByRecovery = Output(Bool())
  val forwardResultRetryByMutationConflict = Output(Bool())

  val replayWakeValid = Input(Bool())
  val replayWake = Input(new LoadReplayWakeupRequest(idEntries, addrWidth, pcWidth, lineBytes, lsidWidth))
  val replayWakeWaitStoreClearMask = Output(UInt(liqEntries.W))
  val replayWakeMergeMask = Output(UInt(liqEntries.W))
  val replayWakeCompletedMask = Output(UInt(liqEntries.W))
  val replayWakeOrderAuthorityMissingMask = Output(UInt(liqEntries.W))
  val replayWakeOrderAmbiguousMask = Output(UInt(liqEntries.W))

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
  val rowMutationNextWaitStoreInfo = Input(new LoadStoreForwardWait(
    idEntries, storeEntries, pcWidth, lsidWidth))
  val rowMutationNextLineData = Input(UInt((lineBytes * 8).W))
  val rowMutationNextValidMask = Input(UInt(lineBytes.W))
  val rowMutationNextDataComplete = Input(Bool())
  val rowMutationNextScbReturned = Input(Bool())
  val rowMutationNextStqReturned = Input(Bool())
  val rowMutationNextStoreSourceReturned = Input(Bool())
  val rowMutationAllowWaitTarget = Input(Bool())
  val rowMutationRequireScbReturned = Input(Bool())
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
  val lhqRecord = Output(new LoadHitRecord(
    liqEntries, idEntries, addrWidth, lineBytes, sizeWidth, pcWidth, lsidWidth))

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
      physRegWidth,
      peIdWidth,
      stidWidth,
      tidWidth,
      returnPipeCount,
      lsidWidth
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
    val physRegWidth: Int = 6,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val returnPipeCount: Int = 1,
    val lsidWidth: Int = 32,
    val useExternalForwardResult: Boolean = false)
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
  require(returnPipeCount > 0, "returnPipeCount must be positive")

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
    physRegWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    returnPipeCount,
    lsidWidth
  ))

  private def zeroWait: LoadStoreForwardWait =
    0.U.asTypeOf(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth, lsidWidth))

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
      physRegWidth,
      peIdWidth,
      stidWidth,
      tidWidth,
      returnPipeCount,
      lsidWidth
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
    row.loadLsIdFullValid := false.B
    row.loadLsIdFull := 0.U
    row.attempt := LoadAttemptIdentity.none
    row.youngestStoreId := ROBID.disabled(idEntries)
    row.youngestStoreLsId := ROBID.disabled(idEntries)
    row.youngestStoreLsIdFullValid := false.B
    row.youngestStoreLsIdFull := 0.U
    row
  }

  private def zeroHitRecord: LoadHitRecord =
    0.U.asTypeOf(new LoadHitRecord(
      liqEntries, idEntries, addrWidth, lineBytes, sizeWidth, pcWidth, lsidWidth))

  private def currentLoadId: ROBID = {
    val id = Wire(new ROBID(liqEntries))
    id.valid := true.B
    id.wrap := allocWrap
    id.value := allocPtr
    id
  }

  private def lineAddr(addr: UInt): UInt =
    Cat(addr(addrWidth - 1, lineOffsetWidth), 0.U(lineOffsetWidth.W))

  private def activeSecondSegment(row: LoadInflightRow): Bool =
    row.crossLine && row.secondSegmentActive

  private def firstSegmentSize(row: LoadInflightRow): UInt = {
    val offset = Wire(UInt(sizeWidth.W))
    offset := row.addr(lineOffsetWidth - 1, 0)
    lineBytes.U(sizeWidth.W) - offset
  }

  private def activeAddr(row: LoadInflightRow): UInt =
    Mux(activeSecondSegment(row), lineAddr(row.addr) + lineBytes.U, row.addr)

  private def activeSize(row: LoadInflightRow): UInt =
    Mux(
      activeSecondSegment(row),
      row.size - firstSegmentSize(row),
      Mux(row.crossLine, firstSegmentSize(row), row.size))

  private def requestByteMask(row: LoadInflightRow): UInt = {
    val mask = Wire(Vec(lineBytes, Bool()))
    val offset = Wire(UInt(sizeWidth.W))
    offset := Mux(activeSecondSegment(row), 0.U, row.addr(lineOffsetWidth - 1, 0))
    val size = activeSize(row)
    val end = offset +& size
    for (byte <- 0 until lineBytes) {
      val byteIndex = byte.U(end.getWidth.W)
      mask(byte) := row.valid && size =/= 0.U && byteIndex >= offset && byteIndex < end
    }
    mask.asUInt
  }

  val rows = RegInit(VecInit(Seq.fill(liqEntries)(zeroRow)))
  val allocPtr = RegInit(0.U(liqPtrWidth.W))
  val allocWrap = RegInit(false.B)
  val residentCount = RegInit(0.U(countWidth.W))
  val flushCycle = io.flush || io.preciseFlush.req.valid

  val launchRow = rows(io.launchIndex)
  val launchReady =
    !flushCycle && launchRow.valid && (launchRow.status === LoadInflightStatus.Wait) && !launchRow.waitStore
  val launchAccepted = io.launchValid && launchReady
  val launchUsesRowData = launchRow.validMask.orR
  val pickRow = rows(io.pickIndex)
  val pickReady =
    !flushCycle && pickRow.valid && (pickRow.status === LoadInflightStatus.Wait) && !pickRow.waitStore
  val pickAccepted = io.pickValid && pickReady
  val scbReturnRow = rows(io.scbReturnIndex)
  val scbReturnReady =
    !flushCycle && scbReturnRow.valid && (scbReturnRow.status === LoadInflightStatus.Repick) && !scbReturnRow.scbReturned
  val scbReturnAccepted = io.scbReturnValid && scbReturnReady
  val markResolvedRow = rows(io.markResolvedIndex)
  val markResolvedRequestByteMask = requestByteMask(markResolvedRow)
  val markResolvedRequestComplete =
    markResolvedRequestByteMask.orR && ((markResolvedRow.validMask & markResolvedRequestByteMask) === markResolvedRequestByteMask)
  val markResolvedReady =
    !flushCycle &&
      markResolvedRow.valid &&
      (markResolvedRow.status === LoadInflightStatus.Repick) &&
      markResolvedRow.dataComplete &&
      markResolvedRow.sourcesReturned &&
      markResolvedRow.scbReturned &&
      markResolvedRow.stqReturned &&
      markResolvedRequestComplete
  val markResolvedAccepted = io.markResolvedValid && markResolvedReady

  val query = Wire(new LoadStoreForwardQuery(idEntries, addrWidth, lineBytes, sizeWidth, lsidWidth))
  query := 0.U.asTypeOf(query)
  query.valid := launchAccepted
  query.lineAddr := lineAddr(activeAddr(launchRow))
  query.byteOffset := activeAddr(launchRow)(lineOffsetWidth - 1, 0)
  query.size := activeSize(launchRow)
  query.youngestStoreId := launchRow.youngestStoreId
  query.youngestStoreLsId := launchRow.youngestStoreLsId
  query.youngestStoreLsIdFullValid := launchRow.youngestStoreLsIdFullValid
  query.youngestStoreLsIdFull := launchRow.youngestStoreLsIdFull
  query.isTile := launchRow.isTile

  val selectedForwardResult = Wire(new LoadInflightForwardResult(
    idEntries, storeEntries, pcWidth, lineBytes, lsidWidth))
  selectedForwardResult := 0.U.asTypeOf(selectedForwardResult)
  val selectedForwardResultValid = WireDefault(false.B)
  val forwardPipelineResidentMask = WireDefault(0.U(liqEntries.W))

  if (useExternalForwardResult) {
    selectedForwardResult := io.forwardResult
    selectedForwardResultValid := io.forwardResultValid
  } else {
    val pipeline = Module(new LoadForwardPipeline(
      idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth,
      lsidWidth))
    pipeline.io.flush := flushCycle
    pipeline.io.e2Stores := io.e2Stores
    pipeline.io.e2LoadDataReturned := io.e2LoadDataReturned
    pipeline.io.e2ScbReturned := io.e2ScbReturned
    pipeline.io.e2StqReturned := io.e2StqReturned
    pipeline.io.e2ReturnReady := io.e2ReturnReady
    pipeline.io.e2BaseData := Mux(
      launchUsesRowData, launchRow.lineData, io.e2BaseData)
    pipeline.io.e2BaseValidMask := Mux(
      launchUsesRowData, launchRow.validMask, io.e2BaseValidMask)
    pipeline.io.e2Valid := launchAccepted
    pipeline.io.e2Query := query

    val e3Identity = RegInit(0.U.asTypeOf(
      new STQLoadForwardResultIdentity))
    val e4Identity = RegInit(0.U.asTypeOf(
      new STQLoadForwardResultIdentity))
    when(flushCycle) {
      e3Identity := 0.U.asTypeOf(e3Identity)
      e4Identity := 0.U.asTypeOf(e4Identity)
    }.otherwise {
      e4Identity := e3Identity
      when(launchAccepted) {
        e3Identity.loadId :=
          LoadCanonicalRowIdentity.fromRobId(launchRow.loadId)
        e3Identity.attempt := launchRow.attempt
        e3Identity.returnPipeIndex := launchRow.returnPipeIndex
      }
    }

    selectedForwardResultValid := pipeline.io.e4Valid
    selectedForwardResult.identity := e4Identity
    selectedForwardResult.lineData := pipeline.io.e4LineData
    selectedForwardResult.validMask := pipeline.io.e4ValidMask
    selectedForwardResult.loadByteMask := pipeline.io.e4LoadByteMask
    selectedForwardResult.forwardMask := pipeline.io.e4ForwardMask
    selectedForwardResult.waitMask := pipeline.io.e4WaitMask
    selectedForwardResult.dataComplete := pipeline.io.e4DataComplete
    selectedForwardResult.sourcesReturned := pipeline.io.e4SourcesReturned
    selectedForwardResult.scbReturned := pipeline.io.e4ScbReturned
    selectedForwardResult.stqReturned := pipeline.io.e4StqReturned
    selectedForwardResult.wakeupValid := pipeline.io.e4WakeupValid
    selectedForwardResult.waitStore := pipeline.io.e4WaitStore
    selectedForwardResult.missKind := pipeline.io.e4MissKind

    val e3IdentityWellFormed = e3Identity.loadId.valid &&
      LoadCanonicalRowIdentity.wellFormed(e3Identity.loadId, liqEntries)
    val e4IdentityWellFormed = e4Identity.loadId.valid &&
      LoadCanonicalRowIdentity.wellFormed(e4Identity.loadId, liqEntries)
    val e3ResidentMask = Mux(
      pipeline.io.e3Valid && e3IdentityWellFormed,
      UIntToOH(e3Identity.loadId.slot(liqPtrWidth - 1, 0), liqEntries),
      0.U(liqEntries.W))
    val e4ResidentMask = Mux(
      pipeline.io.e4Valid && e4IdentityWellFormed,
      UIntToOH(e4Identity.loadId.slot(liqPtrWidth - 1, 0), liqEntries),
      0.U(liqEntries.W))
    forwardPipelineResidentMask := e3ResidentMask | e4ResidentMask
  }

  val replayWakeup = Module(new LoadReplayWakeup(
    liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth, lsidWidth))
  replayWakeup.io.wakeValid := io.replayWakeValid && !flushCycle
  replayWakeup.io.wake := io.replayWake
  replayWakeup.io.rows := rows

  val refillWakeup = Module(new LoadRefillWakeup(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))
  refillWakeup.io.refillValid := io.refillValid && !flushCycle
  refillWakeup.io.refill := io.refill
  refillWakeup.io.rows := rows

  val allocLoadId = currentLoadId
  val allocAttemptWellFormed = LoadAttemptIdentity.wellFormed(io.alloc.attempt)
  val allocReady =
    !flushCycle && !rows(allocPtr).valid && (!io.allocValid || allocAttemptWellFormed)
  val allocAccepted = io.allocValid && allocReady
  val attemptRebindRow = rows(io.attemptRebind.loadId.value)
  val attemptRebindLoadIdValid =
    io.attemptRebind.loadId.valid &&
      attemptRebindRow.loadId.valid &&
      ROBID.equal(io.attemptRebind.loadId, attemptRebindRow.loadId)
  val attemptRebindCurrentExact =
    io.attemptRebind.current.valid &&
      attemptRebindRow.attempt.valid &&
      LoadAttemptIdentity.equal(io.attemptRebind.current, attemptRebindRow.attempt)
  val attemptRebindNextExact =
    io.attemptRebind.next.valid &&
      (io.attemptRebind.next.producer.asUInt === io.attemptRebind.current.producer.asUInt) &&
      (io.attemptRebind.next.generation === (io.attemptRebind.current.generation +% 1.U))
  val attemptRebindLifecycleReady =
    (attemptRebindRow.status === LoadInflightStatus.Wait) ||
      (attemptRebindRow.status === LoadInflightStatus.L1DcMiss) ||
      (attemptRebindRow.status === LoadInflightStatus.L2Wait)
  val attemptRebindLaunchConflict =
    (io.launchIntentValid &&
      (io.launchIntentIndex === io.attemptRebind.loadId.value)) ||
      (pickAccepted && (io.pickIndex === io.attemptRebind.loadId.value))
  val attemptRebindReady =
    !flushCycle &&
      attemptRebindRow.valid &&
      attemptRebindLoadIdValid &&
      attemptRebindLifecycleReady &&
      !attemptRebindLaunchConflict &&
      attemptRebindCurrentExact &&
      attemptRebindNextExact
  val attemptRebindAccepted = io.attemptRebindValid && attemptRebindReady
  val clearResolvedRow = rows(io.clearResolvedIndex)
  val clearResolvedCompleteRepick =
    clearResolvedRow.valid &&
      (clearResolvedRow.status === LoadInflightStatus.Repick) &&
      clearResolvedRow.dataComplete &&
      clearResolvedRow.sourcesReturned &&
      clearResolvedRow.scbReturned &&
      clearResolvedRow.stqReturned &&
      !clearResolvedRow.waitStore
  val clearResolvedReady =
    !flushCycle &&
      clearResolvedRow.valid &&
      ((clearResolvedRow.status === LoadInflightStatus.Resolved) || clearResolvedCompleteRepick)
  val clearResolvedAccepted = io.clearResolvedValid && clearResolvedReady

  val forwardResultLoadIdWellFormed =
    selectedForwardResult.identity.loadId.valid &&
      LoadCanonicalRowIdentity.wellFormed(
        selectedForwardResult.identity.loadId, liqEntries)
  val forwardResultIndex =
    selectedForwardResult.identity.loadId.slot(liqPtrWidth - 1, 0)
  val forwardResultRow = rows(forwardResultIndex)
  val forwardResultLoadIdExact = forwardResultLoadIdWellFormed &&
    forwardResultRow.valid && LoadCanonicalRowIdentity.equal(
      selectedForwardResult.identity.loadId,
      LoadCanonicalRowIdentity.fromRobId(forwardResultRow.loadId))
  val forwardResultAttemptExact =
    selectedForwardResult.identity.attempt.valid &&
      LoadAttemptIdentity.wellFormed(
        selectedForwardResult.identity.attempt) &&
      forwardResultRow.attempt.valid && LoadAttemptIdentity.equal(
        selectedForwardResult.identity.attempt, forwardResultRow.attempt)
  val forwardResultPipeExact =
    selectedForwardResult.identity.returnPipeIndex ===
      forwardResultRow.returnPipeIndex
  val forwardResultLifecycleReady =
    (forwardResultRow.status === LoadInflightStatus.Repick) &&
      forwardResultRow.forwardPending
  val forwardResultExactCandidate = selectedForwardResultValid &&
    forwardResultLoadIdExact && forwardResultAttemptExact &&
    forwardResultPipeExact && forwardResultLifecycleReady
  val externalForwardResultMutationConflict =
    useExternalForwardResult.B &&
      ((clearResolvedAccepted &&
        (io.clearResolvedIndex === forwardResultIndex)) ||
        (io.rowMutationValid &&
          (io.rowMutationTargetIndex === forwardResultIndex)) ||
        (io.replayWakeValid &&
          replayWakeup.io.waitStoreClearMask(forwardResultIndex)) ||
        (io.replayWakeValid &&
          replayWakeup.io.mergeMask(forwardResultIndex)) ||
        (io.refillValid && refillWakeup.io.wakeMask(forwardResultIndex)))
  val e4UpdateValid =
    forwardResultExactCandidate && !flushCycle &&
      !externalForwardResultMutationConflict
  val e4SegmentResolved =
    e4UpdateValid && selectedForwardResult.wakeupValid &&
      (selectedForwardResult.missKind === LoadForwardMissKind.NoMiss)
  val e4FirstSegmentResolved =
    e4SegmentResolved && forwardResultRow.crossLine &&
      !forwardResultRow.secondSegmentActive
  val e4Resolved =
    e4SegmentResolved && (!forwardResultRow.crossLine ||
      forwardResultRow.secondSegmentActive)
  val e4StoreWait = e4UpdateValid &&
    (selectedForwardResult.missKind ===
      LoadForwardMissKind.StoreDataNotReady)
  val e4DataMiss = e4UpdateValid &&
    (selectedForwardResult.missKind === LoadForwardMissKind.DataNotComplete)
  val e4ReplayWait =
    e4UpdateValid &&
      ((selectedForwardResult.missKind ===
        LoadForwardMissKind.AwaitingSources) ||
        (selectedForwardResult.missKind ===
          LoadForwardMissKind.ReturnPortBlocked))

  val structuralRetryLoadIdWellFormed =
    io.structuralRetry.loadId.valid &&
      LoadCanonicalRowIdentity.wellFormed(
        io.structuralRetry.loadId, liqEntries)
  val structuralRetryIndex =
    io.structuralRetry.loadId.slot(liqPtrWidth - 1, 0)
  val structuralRetryRow = rows(structuralRetryIndex)
  val structuralRetryLoadIdExact = structuralRetryLoadIdWellFormed &&
    structuralRetryRow.valid && LoadCanonicalRowIdentity.equal(
      io.structuralRetry.loadId,
      LoadCanonicalRowIdentity.fromRobId(structuralRetryRow.loadId))
  val structuralRetryAttemptExact =
    io.structuralRetry.current.valid &&
      LoadAttemptIdentity.wellFormed(io.structuralRetry.current) &&
      structuralRetryRow.attempt.valid &&
      LoadAttemptIdentity.equal(
        io.structuralRetry.current, structuralRetryRow.attempt)
  val structuralRetryNextExact =
    io.structuralRetry.next.valid &&
      LoadAttemptIdentity.wellFormed(io.structuralRetry.next) &&
      (io.structuralRetry.next.producer.asUInt ===
        io.structuralRetry.current.producer.asUInt) &&
      (io.structuralRetry.next.generation ===
        (io.structuralRetry.current.generation +% 1.U))
  val structuralRetryPipeExact =
    io.structuralRetry.returnPipeIndex ===
      structuralRetryRow.returnPipeIndex
  val structuralRetryLifecycleExact =
    structuralRetryRow.valid &&
      (structuralRetryRow.status === LoadInflightStatus.Repick) &&
      structuralRetryRow.forwardPending
  val structuralRetryWaitStoreExact = Mux(
    io.structuralRetry.waitStore,
    io.structuralRetry.waitStoreInfo.valid &&
      io.structuralRetry.waitStoreInfo.storeId.valid &&
      io.structuralRetry.waitStoreInfo.storeLsId.valid &&
      io.structuralRetry.waitStoreInfo.storeLsIdFullValid,
    !io.structuralRetry.waitStoreInfo.valid)
  val structuralRetryMutationConflict =
    (io.launchIntentValid &&
      (io.launchIntentIndex === structuralRetryIndex)) ||
      (io.pickValid && (io.pickIndex === structuralRetryIndex)) ||
      (io.scbReturnValid &&
        (io.scbReturnIndex === structuralRetryIndex)) ||
      (io.markResolvedValid &&
        (io.markResolvedIndex === structuralRetryIndex)) ||
      (io.clearResolvedValid &&
        (io.clearResolvedIndex === structuralRetryIndex)) ||
      (io.rowMutationValid &&
        (io.rowMutationTargetIndex === structuralRetryIndex)) ||
      (io.replayWakeValid &&
        (replayWakeup.io.waitStoreClearMask(structuralRetryIndex) ||
          replayWakeup.io.mergeMask(structuralRetryIndex))) ||
      (io.refillValid && refillWakeup.io.wakeMask(structuralRetryIndex)) ||
      (selectedForwardResultValid &&
        (forwardResultIndex === structuralRetryIndex))
  val structuralRetryReady = !flushCycle &&
    structuralRetryLoadIdExact && structuralRetryAttemptExact &&
    structuralRetryNextExact && structuralRetryPipeExact &&
    structuralRetryLifecycleExact && structuralRetryWaitStoreExact &&
    !structuralRetryMutationConflict
  val structuralRetryAccepted =
    io.structuralRetryValid && structuralRetryReady
  assert(!(io.structuralRetryValid && io.attemptRebindValid),
    "ordinary and structural load rebind requests must be arbitrated upstream")

  val externalForwardResultActive =
    useExternalForwardResult.B && io.forwardResultValid
  val forwardResultRetryByRecovery = externalForwardResultActive &&
    forwardResultExactCandidate && flushCycle
  val forwardResultRetryByMutationConflict = externalForwardResultActive &&
    forwardResultExactCandidate && !flushCycle &&
    externalForwardResultMutationConflict
  val forwardResultRetryRequired =
    forwardResultRetryByRecovery || forwardResultRetryByMutationConflict
  io.forwardResultAccepted := externalForwardResultActive && e4UpdateValid
  io.forwardResultRejected := externalForwardResultActive && !e4UpdateValid
  io.forwardResultRejectedByLoadId := externalForwardResultActive &&
    (!forwardResultLoadIdWellFormed || !forwardResultLoadIdExact)
  io.forwardResultRejectedByAttempt := externalForwardResultActive &&
    forwardResultLoadIdExact && !forwardResultAttemptExact
  io.forwardResultRejectedByPipe := externalForwardResultActive &&
    forwardResultLoadIdExact && forwardResultAttemptExact &&
    !forwardResultPipeExact
  io.forwardResultRejectedByLifecycle := externalForwardResultActive &&
    forwardResultLoadIdExact && forwardResultAttemptExact &&
    forwardResultPipeExact && (!forwardResultLifecycleReady || flushCycle ||
      externalForwardResultMutationConflict)
  io.forwardResultRejectedPermanent := io.forwardResultRejected &&
    !forwardResultRetryRequired
  io.forwardResultRetryRequired := forwardResultRetryRequired
  io.forwardResultRetryByRecovery := forwardResultRetryByRecovery
  io.forwardResultRetryByMutationConflict :=
    forwardResultRetryByMutationConflict

  val lhqRecord = Wire(new LoadHitRecord(
    liqEntries, idEntries, addrWidth, lineBytes, sizeWidth, pcWidth, lsidWidth))
  lhqRecord := zeroHitRecord
  lhqRecord.loadId := forwardResultRow.loadId
  lhqRecord.bid := forwardResultRow.bid
  lhqRecord.gid := forwardResultRow.gid
  lhqRecord.rid := forwardResultRow.rid
  lhqRecord.loadLsId := forwardResultRow.loadLsId
  lhqRecord.loadLsIdFullValid := forwardResultRow.loadLsIdFullValid
  lhqRecord.loadLsIdFull := forwardResultRow.loadLsIdFull
  lhqRecord.attempt := forwardResultRow.attempt
  lhqRecord.pc := forwardResultRow.pc
  lhqRecord.addr := forwardResultRow.addr
  lhqRecord.lineAddr := lineAddr(activeAddr(forwardResultRow))
  lhqRecord.size := forwardResultRow.size
  lhqRecord.byteMask := selectedForwardResult.loadByteMask
  lhqRecord.data := selectedForwardResult.lineData
  lhqRecord.forwardedMask := selectedForwardResult.forwardMask

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
    physRegWidth = physRegWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    returnPipeCount = returnPipeCount,
    lsidWidth = lsidWidth
  ))
  val rowMutationReplayConflictMask = replayWakeup.io.waitStoreClearMask | replayWakeup.io.mergeMask
  val rowMutationTargetMask = UIntToOH(io.rowMutationTargetIndex, liqEntries)
  val rowMutationReplayConflictVec = VecInit((0 until liqEntries).map(idx => rowMutationReplayConflictMask(idx)))
  val rowMutationRefillConflictVec = VecInit((0 until liqEntries).map(idx => refillWakeup.io.wakeMask(idx)))
  rowMutationPath.io.enable := true.B
  rowMutationPath.io.flush := flushCycle
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
  rowMutationPath.io.allowWaitTarget := io.rowMutationAllowWaitTarget
  rowMutationPath.io.requireScbReturned := io.rowMutationRequireScbReturned
  rowMutationPath.io.e4UpdateConflict :=
    e4UpdateValid && (forwardResultIndex === io.rowMutationTargetIndex)
  rowMutationPath.io.clearResolvedConflict := clearResolvedAccepted && (io.clearResolvedIndex === io.rowMutationTargetIndex)
  rowMutationPath.io.replayWakeConflict := io.replayWakeValid && rowMutationReplayConflictVec(io.rowMutationTargetIndex)
  rowMutationPath.io.refillConflict := io.refillValid && rowMutationRefillConflictVec(io.rowMutationTargetIndex)
  rowMutationPath.io.launchConflict :=
    (launchAccepted && (io.launchIndex === io.rowMutationTargetIndex)) ||
      (pickAccepted && (io.pickIndex === io.rowMutationTargetIndex)) ||
      (scbReturnAccepted && (io.scbReturnIndex === io.rowMutationTargetIndex)) ||
      (markResolvedAccepted && (io.markResolvedIndex === io.rowMutationTargetIndex))
  rowMutationPath.io.allocationConflict := allocAccepted && (allocPtr === io.rowMutationTargetIndex)

  val flushPruneVec = VecInit(rows.map(row => LoadQueueFlushMatch(
    io.preciseFlush,
    row.valid,
    row.peId,
    row.stid,
    row.tid,
    row.bid,
    row.gid,
    row.loadLsId,
    row.loadLsIdFullValid,
    row.loadLsIdFull)))
  val flushPruneMask = flushPruneVec.asUInt
  val flushPruneCount = PopCount(flushPruneVec)

  when(io.flush) {
    for (idx <- 0 until liqEntries) {
      rows(idx) := zeroRow
    }
    residentCount := 0.U
    allocPtr := 0.U
    allocWrap := false.B
  }.otherwise {
    when(e4UpdateValid) {
      rows(forwardResultIndex).forwardPending := false.B
      rows(forwardResultIndex).lineData := selectedForwardResult.lineData
      rows(forwardResultIndex).validMask := selectedForwardResult.validMask
      rows(forwardResultIndex).loadByteMask :=
        selectedForwardResult.loadByteMask
      rows(forwardResultIndex).forwardMask :=
        selectedForwardResult.forwardMask
      rows(forwardResultIndex).waitMask := selectedForwardResult.waitMask
      rows(forwardResultIndex).dataComplete :=
        selectedForwardResult.dataComplete
      rows(forwardResultIndex).sourcesReturned :=
        selectedForwardResult.sourcesReturned
      rows(forwardResultIndex).scbReturned :=
        selectedForwardResult.scbReturned
      rows(forwardResultIndex).stqReturned :=
        selectedForwardResult.stqReturned
      rows(forwardResultIndex).missKind := selectedForwardResult.missKind
      rows(forwardResultIndex).storeBypass :=
        selectedForwardResult.forwardMask.orR

      when(e4FirstSegmentResolved) {
        rows(forwardResultIndex).status := LoadInflightStatus.Wait
        rows(forwardResultIndex).secondSegmentActive := true.B
        rows(forwardResultIndex).firstSegmentDone := true.B
        rows(forwardResultIndex).firstLineData := selectedForwardResult.lineData
        rows(forwardResultIndex).firstValidMask :=
          selectedForwardResult.validMask
        rows(forwardResultIndex).firstLoadByteMask :=
          selectedForwardResult.loadByteMask
        rows(forwardResultIndex).firstForwardMask :=
          selectedForwardResult.forwardMask
        rows(forwardResultIndex).lineData := 0.U
        rows(forwardResultIndex).validMask := 0.U
        rows(forwardResultIndex).loadByteMask := 0.U
        rows(forwardResultIndex).forwardMask := 0.U
        rows(forwardResultIndex).waitMask := 0.U
        rows(forwardResultIndex).dataComplete := false.B
        rows(forwardResultIndex).sourcesReturned := false.B
        rows(forwardResultIndex).scbReturned := false.B
        rows(forwardResultIndex).stqReturned := false.B
        rows(forwardResultIndex).waitStore := false.B
        rows(forwardResultIndex).waitStoreInfo := zeroWait
        rows(forwardResultIndex).l1Hit := false.B
        rows(forwardResultIndex).l1Miss := false.B
        rows(forwardResultIndex).missKind := LoadForwardMissKind.NoMiss
      }.elsewhen(e4Resolved) {
        // Preserve the E4 hit as Repick until the return owner publishes its
        // LRET and markResolved accepts the terminal row state.
        rows(forwardResultIndex).status := LoadInflightStatus.Repick
        rows(forwardResultIndex).waitStore := false.B
        rows(forwardResultIndex).waitStoreInfo := zeroWait
        rows(forwardResultIndex).l1Hit := false.B
        rows(forwardResultIndex).l1Miss := false.B
      }.elsewhen(e4StoreWait) {
        rows(forwardResultIndex).status := LoadInflightStatus.Wait
        rows(forwardResultIndex).waitStore := true.B
        rows(forwardResultIndex).waitStoreInfo :=
          selectedForwardResult.waitStore
        rows(forwardResultIndex).validMask := 0.U
        rows(forwardResultIndex).loadByteMask := 0.U
        rows(forwardResultIndex).forwardMask := 0.U
        rows(forwardResultIndex).waitMask := 0.U
        rows(forwardResultIndex).dataComplete := false.B
        rows(forwardResultIndex).sourcesReturned := false.B
        rows(forwardResultIndex).scbReturned := false.B
        rows(forwardResultIndex).stqReturned := false.B
        rows(forwardResultIndex).l1Hit := false.B
      }.elsewhen(e4DataMiss) {
        rows(forwardResultIndex).status := LoadInflightStatus.L1DcMiss
        rows(forwardResultIndex).waitStore := false.B
        rows(forwardResultIndex).waitStoreInfo := zeroWait
        rows(forwardResultIndex).validMask := 0.U
        rows(forwardResultIndex).loadByteMask := 0.U
        rows(forwardResultIndex).forwardMask := 0.U
        rows(forwardResultIndex).waitMask := 0.U
        rows(forwardResultIndex).dataComplete := false.B
        rows(forwardResultIndex).sourcesReturned := false.B
        rows(forwardResultIndex).scbReturned := false.B
        rows(forwardResultIndex).stqReturned := false.B
        rows(forwardResultIndex).l1Hit := false.B
        rows(forwardResultIndex).l1Miss := true.B
      }.elsewhen(e4ReplayWait) {
        rows(forwardResultIndex).status := LoadInflightStatus.Wait
        rows(forwardResultIndex).waitStore := false.B
        rows(forwardResultIndex).waitStoreInfo := zeroWait
        rows(forwardResultIndex).validMask := 0.U
        rows(forwardResultIndex).loadByteMask := 0.U
        rows(forwardResultIndex).forwardMask := 0.U
        rows(forwardResultIndex).waitMask := 0.U
        rows(forwardResultIndex).dataComplete := false.B
        rows(forwardResultIndex).sourcesReturned := false.B
        rows(forwardResultIndex).scbReturned := false.B
        rows(forwardResultIndex).stqReturned := false.B
        rows(forwardResultIndex).l1Hit := false.B
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
        val sameRowResolvedAtE4 =
          e4SegmentResolved && (forwardResultIndex === idx.U)
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
      rows(io.launchIndex).forwardPending := true.B
    }

    when(pickAccepted && !(launchAccepted && (io.launchIndex === io.pickIndex))) {
      rows(io.pickIndex).status := LoadInflightStatus.Repick
      rows(io.pickIndex).waitStore := false.B
      rows(io.pickIndex).missKind := LoadForwardMissKind.NoMiss
    }

    when(scbReturnAccepted) {
      rows(io.scbReturnIndex).scbReturned := true.B
    }

    when(markResolvedAccepted) {
      rows(io.markResolvedIndex).status := LoadInflightStatus.Resolved
      rows(io.markResolvedIndex).waitStore := false.B
      rows(io.markResolvedIndex).missKind := LoadForwardMissKind.NoMiss
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
      rows(allocPtr).loadLsIdFullValid := io.alloc.loadLsIdFullValid
      rows(allocPtr).loadLsIdFull := io.alloc.loadLsIdFull
      rows(allocPtr).attempt := LoadAttemptIdentity.canonical(io.alloc.attempt)
      rows(allocPtr).peId := io.alloc.peId
      rows(allocPtr).stid := io.alloc.stid
      rows(allocPtr).tid := io.alloc.tid
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
      rows(allocPtr).youngestStoreLsIdFullValid := io.alloc.youngestStoreLsIdFullValid
      rows(allocPtr).youngestStoreLsIdFull := io.alloc.youngestStoreLsIdFull
      rows(allocPtr).isTile := io.alloc.isTile
      rows(allocPtr).specWakeup := io.alloc.specWakeup
      rows(allocPtr).stackValid := io.alloc.stackValid
      rows(allocPtr).returnPipeIndex := io.alloc.returnPipeIndex

      val allocOffset = Wire(UInt(sizeWidth.W))
      allocOffset := io.alloc.addr(lineOffsetWidth - 1, 0)
      val allocEnd = allocOffset +& io.alloc.size
      rows(allocPtr).crossLine :=
        !io.alloc.isTile && io.alloc.size =/= 0.U && allocEnd > lineBytes.U(allocEnd.getWidth.W)

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

    when(attemptRebindAccepted) {
      rows(io.attemptRebind.loadId.value).attempt := io.attemptRebind.next
    }

    when(structuralRetryAccepted) {
      rows(structuralRetryIndex).status := LoadInflightStatus.Wait
      rows(structuralRetryIndex).attempt := io.structuralRetry.next
      rows(structuralRetryIndex).forwardPending := false.B
      rows(structuralRetryIndex).lineData := 0.U
      rows(structuralRetryIndex).validMask := 0.U
      rows(structuralRetryIndex).loadByteMask := 0.U
      rows(structuralRetryIndex).forwardMask := 0.U
      rows(structuralRetryIndex).waitMask := 0.U
      rows(structuralRetryIndex).waitStore := io.structuralRetry.waitStore
      rows(structuralRetryIndex).waitStoreInfo :=
        Mux(io.structuralRetry.waitStore,
          io.structuralRetry.waitStoreInfo, zeroWait)
      rows(structuralRetryIndex).storeBypass := false.B
      rows(structuralRetryIndex).dataComplete := false.B
      rows(structuralRetryIndex).sourcesReturned := false.B
      rows(structuralRetryIndex).scbReturned := false.B
      rows(structuralRetryIndex).stqReturned := false.B
      rows(structuralRetryIndex).l1Hit := false.B
      rows(structuralRetryIndex).l1Miss := false.B
      rows(structuralRetryIndex).missKind := LoadForwardMissKind.NoMiss
    }

    residentCount := residentCount + allocAccepted.asUInt - clearResolvedAccepted.asUInt

    when(io.preciseFlush.req.valid) {
      for (idx <- 0 until liqEntries) {
        val pipelineResident = forwardPipelineResidentMask(idx)
        when(flushPruneVec(idx)) {
          rows(idx) := zeroRow
        }.elsewhen(pipelineResident && rows(idx).valid && (rows(idx).status === LoadInflightStatus.Repick)) {
          rows(idx).status := LoadInflightStatus.Wait
          rows(idx).forwardPending := false.B
          rows(idx).dataComplete := false.B
          rows(idx).sourcesReturned := false.B
          rows(idx).scbReturned := false.B
          rows(idx).stqReturned := false.B
          when(rows(idx).crossLine && !rows(idx).secondSegmentActive) {
            rows(idx).secondSegmentActive := false.B
            rows(idx).firstSegmentDone := false.B
            rows(idx).firstLineData := 0.U
            rows(idx).firstValidMask := 0.U
            rows(idx).firstLoadByteMask := 0.U
            rows(idx).firstForwardMask := 0.U
          }
        }
      }
      residentCount := residentCount - flushPruneCount
      when(flushPruneMask.orR) {
        val firstPruned = PriorityEncoder(flushPruneMask)
        allocPtr := firstPruned
        allocWrap := !rows(firstPruned).loadId.wrap
      }
    }
  }

  val occupiedVec = Wire(Vec(liqEntries, Bool()))
  val waitVec = Wire(Vec(liqEntries, Bool()))
  val repickVec = Wire(Vec(liqEntries, Bool()))
  val missVec = Wire(Vec(liqEntries, Bool()))
  val resolvedVec = Wire(Vec(liqEntries, Bool()))
  val waitStoreVec = Wire(Vec(liqEntries, Bool()))
  for (idx <- 0 until liqEntries) {
    val status = rows(idx).status
    assert(!rows(idx).valid || !rows(idx).secondSegmentActive ||
      (rows(idx).crossLine && rows(idx).firstSegmentDone),
      "active second load segment requires retained first-segment completion")
    assert(!rows(idx).valid || !rows(idx).firstSegmentDone || rows(idx).crossLine,
      "first-segment completion is legal only for a cross-line scalar load")
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
  io.allocAttemptMalformed := io.allocValid && !allocAttemptWellFormed
  io.attemptRebindReady := attemptRebindReady
  io.attemptRebindAccepted := attemptRebindAccepted
  io.attemptRebindBlockedByFlush := io.attemptRebindValid && flushCycle
  io.attemptRebindBlockedByInvalidLoadId :=
    io.attemptRebindValid && !flushCycle && !io.attemptRebind.loadId.valid
  io.attemptRebindBlockedByNonresidentRow :=
    io.attemptRebindValid && !flushCycle && io.attemptRebind.loadId.valid &&
      (!attemptRebindRow.valid || !attemptRebindLoadIdValid)
  io.attemptRebindBlockedByLifecycle :=
    io.attemptRebindValid && !flushCycle && attemptRebindRow.valid &&
      attemptRebindLoadIdValid && (!attemptRebindLifecycleReady || attemptRebindLaunchConflict)
  io.attemptRebindBlockedByStaleAttempt :=
    io.attemptRebindValid && !flushCycle && attemptRebindRow.valid &&
      attemptRebindLoadIdValid && attemptRebindLifecycleReady &&
      !attemptRebindLaunchConflict && !attemptRebindCurrentExact
  io.attemptRebindBlockedByNextAttempt :=
    io.attemptRebindValid && !flushCycle && attemptRebindRow.valid &&
      attemptRebindLoadIdValid && attemptRebindLifecycleReady &&
      !attemptRebindLaunchConflict && attemptRebindCurrentExact && !attemptRebindNextExact
  io.structuralRetryReady := structuralRetryReady
  io.structuralRetryAccepted := structuralRetryAccepted
  io.structuralRetryBlockedByLoadId := io.structuralRetryValid &&
    !flushCycle && !structuralRetryLoadIdExact
  io.structuralRetryBlockedByAttempt := io.structuralRetryValid &&
    !flushCycle && structuralRetryLoadIdExact &&
    !structuralRetryAttemptExact
  io.structuralRetryBlockedByNextAttempt := io.structuralRetryValid &&
    !flushCycle && structuralRetryLoadIdExact &&
    structuralRetryAttemptExact && !structuralRetryNextExact
  io.structuralRetryBlockedByPipe := io.structuralRetryValid &&
    !flushCycle && structuralRetryLoadIdExact &&
    structuralRetryAttemptExact && structuralRetryNextExact &&
    !structuralRetryPipeExact
  io.structuralRetryBlockedByLifecycle := io.structuralRetryValid &&
    !flushCycle && structuralRetryLoadIdExact &&
    structuralRetryAttemptExact && structuralRetryNextExact &&
    structuralRetryPipeExact && !structuralRetryLifecycleExact
  io.structuralRetryBlockedByWaitStore := io.structuralRetryValid &&
    !flushCycle && structuralRetryLoadIdExact &&
    structuralRetryAttemptExact && structuralRetryNextExact &&
    structuralRetryPipeExact && structuralRetryLifecycleExact &&
    !structuralRetryWaitStoreExact
  io.structuralRetryBlockedByMutation := io.structuralRetryValid &&
    !flushCycle && structuralRetryLoadIdExact &&
    structuralRetryAttemptExact && structuralRetryNextExact &&
    structuralRetryPipeExact && structuralRetryLifecycleExact &&
    structuralRetryWaitStoreExact && structuralRetryMutationConflict
  io.launchReady := launchReady
  io.launchAccepted := launchAccepted
  io.pickReady := pickReady
  io.pickAccepted := pickAccepted
  io.scbReturnReady := scbReturnReady
  io.scbReturnAccepted := scbReturnAccepted
  io.markResolvedReady := markResolvedReady
  io.markResolvedAccepted := markResolvedAccepted
  io.clearResolvedAccepted := clearResolvedAccepted
  io.flushPruneMask := flushPruneMask
  io.flushPruneCount := flushPruneCount
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
  io.replayWakeOrderAuthorityMissingMask :=
    replayWakeup.io.orderAuthorityMissingMask
  io.replayWakeOrderAmbiguousMask := replayWakeup.io.orderAmbiguousMask
  io.refillAccepted := refillWakeup.io.refillAccepted
  io.refillWakeMask := refillWakeup.io.wakeMask
  io.e4UpdateValid := e4UpdateValid
  io.e4UpdateIndex := forwardResultIndex
  io.e4MissKind := selectedForwardResult.missKind
  io.e4WakeupValid := e4Resolved
  io.lhqRecordValid := e4Resolved
  assert(!e4Resolved || !forwardResultRow.crossLine ||
    forwardResultRow.firstSegmentDone,
    "cross-line scalar publication requires a retained first segment")
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
