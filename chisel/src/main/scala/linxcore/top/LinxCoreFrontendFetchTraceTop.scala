package linxcore.top

import chisel3._
import chisel3.util.log2Ceil

import linxcore.backend.DecodeRenameROBPath
import linxcore.commit.{CommitTraceParams, CommitTracePort, CommitTraceRow}
import linxcore.common.{CoreParams, InterfaceParams}
import linxcore.frontend.{F4DecodeWindow, FrontendFetchPacketSource}
import linxcore.lsu.StoreDispatchExecResult
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rob.{ROBEntryStatus, ROBID}

class LinxCoreFrontendFetchTraceTopIO(
    val p: InterfaceParams,
    val traceParams: CommitTraceParams,
    val decRenQueueDepth: Int = 4)
    extends Bundle {
  private val ptrWidth = log2Ceil(p.robEntries)
  private val sizeWidth = log2Ceil(p.robEntries + 1)
  private val decRenCountWidth = log2Ceil(decRenQueueDepth + 1)

  val startValid = Input(Bool())
  val startPc = Input(UInt(p.pcWidth.W))
  val restartValid = Input(Bool())
  val restartPc = Input(UInt(p.pcWidth.W))
  val frontendFlushValid = Input(Bool())
  val peId = Input(UInt(p.peIdWidth.W))
  val threadId = Input(UInt(p.threadIdWidth.W))

  val fetchReqReady = Input(Bool())
  val fetchRespValid = Input(Bool())
  val fetchRespWindow = Input(UInt(p.windowWidth.W))

  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(ptrWidth.W))
  val deallocReady = Input(Bool())

  val fetchReqValid = Output(Bool())
  val fetchReqPc = Output(UInt(p.pcWidth.W))
  val fetchRespReady = Output(Bool())
  val sourceActive = Output(Bool())
  val sourceWaitingResponse = Output(Bool())
  val sourcePacketValid = Output(Bool())
  val sourceReqFire = Output(Bool())
  val sourceRespFire = Output(Bool())
  val sourceOutFire = Output(Bool())
  val sourceAdvanceZero = Output(Bool())
  val sourceAdvanceBytes = Output(UInt(4.W))
  val sourceCurrentPc = Output(UInt(p.pcWidth.W))
  val sourceIssuedPc = Output(UInt(p.pcWidth.W))
  val sourceNextPktUid = Output(UInt(p.uopUidWidth.W))

  val f4ValidMask = Output(UInt(p.decodeWidth.W))
  val f4SlotCount = Output(UInt(log2Ceil(p.decodeWidth + 1).W))
  val decodeReady = Output(Bool())
  val selectedValid = Output(Bool())
  val selectedRobValue = Output(UInt(ptrWidth.W))
  val selectedBlockBid = Output(UInt(p.blockBidWidth.W))
  val decRenPushFire = Output(Bool())
  val decRenPopFire = Output(Bool())
  val decRenCount = Output(UInt(decRenCountWidth.W))
  val renamedOutValid = Output(Bool())
  val renamedAccepted = Output(Bool())
  val robAllocFire = Output(Bool())
  val robRenameUpdateFire = Output(Bool())
  val completeAccepted = Output(Bool())
  val completeIgnored = Output(Bool())

  val commit = Output(new CommitTracePort(traceParams))
  val commitValidMask = Output(UInt(traceParams.commitWidth.W))
  val commitCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val commitMonitorValidMask = Output(UInt(traceParams.commitWidth.W))
  val commitMonitorValidCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val commitSkippedSlot = Output(Bool())
  val commitDuplicateIdentity = Output(Bool())
  val commitSlotMismatch = Output(Bool())
  val commitInvalidSideEffect = Output(Bool())
  val commitContractError = Output(Bool())

  val deallocValidMask = Output(UInt(traceParams.commitWidth.W))
  val deallocCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val empty = Output(Bool())
  val full = Output(Bool())
  val size = Output(UInt(sizeWidth.W))
  val outstandingCount = Output(UInt(sizeWidth.W))
  val commitHeadValid = Output(Bool())
  val commitHeadStatus = Output(ROBEntryStatus())
  val commitHeadRobValue = Output(UInt(ptrWidth.W))
  val occupiedMask = Output(UInt(p.robEntries.W))
  val completedMask = Output(UInt(p.robEntries.W))
  val retiredMask = Output(UInt(p.robEntries.W))
  val idle = Output(Bool())
}

class LinxCoreFrontendFetchTraceTop(
    val coreParams: CoreParams = CoreParams(),
    val decRenQueueDepth: Int = 4,
    val storeDispatchQueueDepth: Int = 4,
    val mapQDepth: Int = 32)
    extends Module {
  private val p = LinxCoreFrontendFetchTraceTop.interfaceParamsFor(coreParams)
  private val traceParams = LinxCoreFrontendFetchTraceTop.traceParamsFor(p)
  val io = IO(new LinxCoreFrontendFetchTraceTopIO(p, traceParams, decRenQueueDepth))

  val source = Module(new FrontendFetchPacketSource(p))
  val f4 = Module(new F4DecodeWindow(p))
  val path = Module(new DecodeRenameROBPath(
    p = p,
    traceParams = traceParams,
    decRenQueueDepth = decRenQueueDepth,
    storeDispatchQueueDepth = storeDispatchQueueDepth,
    mapQDepth = mapQDepth
  ))

  source.io.startValid := io.startValid
  source.io.startPc := io.startPc
  source.io.restartValid := io.restartValid
  source.io.restartPc := io.restartPc
  source.io.flushValid := io.frontendFlushValid
  source.io.peId := io.peId
  source.io.threadId := io.threadId
  source.io.reqReady := io.fetchReqReady
  source.io.respValid := io.fetchRespValid
  source.io.respWindow := io.fetchRespWindow
  source.io.outReady := path.io.decodeReady
  source.io.advanceBytes := f4.io.totalLenBytes

  f4.io.in := source.io.out
  f4.io.flushValid := io.frontendFlushValid

  path.io.d1 := f4.io.d1
  path.io.slots := f4.io.slots
  path.io.validMask := f4.io.validMask
  path.io.flushValid := io.frontendFlushValid
  path.io.renamedOutReady := true.B
  path.io.storeStaExec := 0.U.asTypeOf(new StoreDispatchExecResult(64, 64, p.peIdWidth, p.threadIdWidth, p.threadIdWidth))
  path.io.storeStdExec := 0.U.asTypeOf(new StoreDispatchExecResult(64, 64, p.peIdWidth, p.threadIdWidth, p.threadIdWidth))
  path.io.storeMarkCommitValid := false.B
  path.io.storeMarkCommitIndex := 0.U
  path.io.storeCommitFreeValid := false.B
  path.io.storeCommitFreeIndex := 0.U
  path.io.storeCommitFreeMaskValid := false.B
  path.io.storeCommitFreeMask := 0.U
  path.io.checkpointValid := false.B
  path.io.checkpointBid := ROBID.disabled(p.robEntries)
  path.io.commitValid := false.B
  path.io.commitBid := ROBID.disabled(p.robEntries)
  path.io.commitBlockBid := 0.U
  path.io.cleanup := 0.U.asTypeOf(new RecoveryCleanupIntent(p.robEntries, peIdWidth = p.peIdWidth, stidWidth = p.threadIdWidth, tidWidth = p.threadIdWidth))
  path.io.scalarCleanupOrderValid := false.B
  path.io.scalarCleanupOrder := 0.U
  path.io.completeValid := io.completeValid
  path.io.completeRobValue := io.completeRobValue
  path.io.completeRowValid := false.B
  path.io.completeRow := 0.U.asTypeOf(new CommitTraceRow(traceParams))
  path.io.blockBranchTakenValid := false.B
  path.io.blockBranchTaken := false.B
  path.io.scalarRedirectValid := false.B
  path.io.scalarRedirectStid := 0.U
  path.io.deallocReady := io.deallocReady
  path.io.robStatusLookupValid := false.B
  path.io.robStatusLookupRid := ROBID.disabled(p.robEntries)
  path.io.robCommitTraceLookupValid := false.B
  path.io.robCommitTraceLookupRid := ROBID.disabled(p.robEntries)
  path.io.robCommitTraceLookupSourceTraceEnable := false.B

  io.fetchReqValid := source.io.reqValid
  io.fetchReqPc := source.io.reqPc
  io.fetchRespReady := source.io.respReady
  io.sourceActive := source.io.active
  io.sourceWaitingResponse := source.io.waitingResponse
  io.sourcePacketValid := source.io.packetValid
  io.sourceReqFire := source.io.reqFire
  io.sourceRespFire := source.io.respFire
  io.sourceOutFire := source.io.outFire
  io.sourceAdvanceZero := source.io.advanceZero
  io.sourceAdvanceBytes := f4.io.totalLenBytes
  io.sourceCurrentPc := source.io.currentPc
  io.sourceIssuedPc := source.io.issuedPc
  io.sourceNextPktUid := source.io.nextPktUid

  io.f4ValidMask := f4.io.validMask
  io.f4SlotCount := f4.io.slotCount
  io.decodeReady := path.io.decodeReady
  io.selectedValid := path.io.selectedValid
  io.selectedRobValue := path.io.selectedRobValue
  io.selectedBlockBid := path.io.selectedBlockBid
  io.decRenPushFire := path.io.decRenPushFire
  io.decRenPopFire := path.io.decRenPopFire
  io.decRenCount := path.io.decRenCount
  io.renamedOutValid := path.io.renamedOutValid
  io.renamedAccepted := path.io.accepted
  io.robAllocFire := path.io.robAllocFire
  io.robRenameUpdateFire := path.io.robRenameUpdateFire
  io.completeAccepted := path.io.completeAccepted
  io.completeIgnored := path.io.completeIgnored

  io.commit := path.io.commit
  io.commitValidMask := path.io.commitValidMask
  io.commitCount := path.io.commitCount
  io.commitMonitorValidMask := path.io.commitMonitorValidMask
  io.commitMonitorValidCount := path.io.commitMonitorValidCount
  io.commitSkippedSlot := path.io.commitSkippedSlot
  io.commitDuplicateIdentity := path.io.commitDuplicateIdentity
  io.commitSlotMismatch := path.io.commitSlotMismatch
  io.commitInvalidSideEffect := path.io.commitInvalidSideEffect
  io.commitContractError := path.io.commitContractError

  io.deallocValidMask := path.io.deallocValidMask
  io.deallocCount := path.io.deallocCount
  io.empty := path.io.empty
  io.full := path.io.full
  io.size := path.io.size
  io.outstandingCount := path.io.outstandingCount
  io.commitHeadValid := path.io.commitHeadValid
  io.commitHeadStatus := path.io.commitHeadStatus
  io.commitHeadRobValue := path.io.commitHeadRobValue
  io.occupiedMask := path.io.occupiedMask
  io.completedMask := path.io.completedMask
  io.retiredMask := path.io.retiredMask
  io.idle := path.io.empty && !source.io.waitingResponse && !source.io.packetValid
}

object LinxCoreFrontendFetchTraceTop {
  def interfaceParamsFor(coreParams: CoreParams): InterfaceParams =
    InterfaceParams(
      robEntries = coreParams.robEntries,
      commitWidth = coreParams.commitWidth
    )

  def traceParamsFor(p: InterfaceParams): CommitTraceParams =
    CommitTraceParams(
      commitWidth = p.commitWidth,
      robValueWidth = p.robIndexWidth
    )
}

object EmitLinxCoreFrontendFetchTraceTop extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new LinxCoreFrontendFetchTraceTop(CoreParams(robEntries = 8, commitWidth = 2), mapQDepth = 8),
    args = Array("--target-dir", "../generated/chisel-verilog/frontend-fetch-trace-top"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
