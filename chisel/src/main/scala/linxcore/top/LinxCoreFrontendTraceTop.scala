package linxcore.top

import chisel3._
import chisel3.util.log2Ceil

import linxcore.backend.DecodeRenameROBPath
import linxcore.commit.{CommitTraceParams, CommitTracePort, CommitTraceRow}
import linxcore.common.{CoreParams, FrontendDecodePacket, InterfaceParams}
import linxcore.frontend.F4DecodeWindow
import linxcore.lsu.StoreDispatchExecResult
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rob.{ROBEntryStatus, ROBID}

class LinxCoreFrontendTraceTopIO(
    val p: InterfaceParams,
    val traceParams: CommitTraceParams,
    val decRenQueueDepth: Int = 4)
    extends Bundle {
  private val ptrWidth = log2Ceil(p.robEntries)
  private val sizeWidth = log2Ceil(p.robEntries + 1)
  private val decRenCountWidth = log2Ceil(decRenQueueDepth + 1)

  val in = Input(new FrontendDecodePacket(p))
  val frontendFlushValid = Input(Bool())
  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(ptrWidth.W))
  val deallocReady = Input(Bool())

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

class LinxCoreFrontendTraceTop(
    val coreParams: CoreParams = CoreParams(),
    val decRenQueueDepth: Int = 4,
    val storeDispatchQueueDepth: Int = 4,
    val mapQDepth: Int = 32)
    extends Module {
  private val p = LinxCoreFrontendTraceTop.interfaceParamsFor(coreParams)
  private val traceParams = LinxCoreFrontendTraceTop.traceParamsFor(p)
  val io = IO(new LinxCoreFrontendTraceTopIO(p, traceParams, decRenQueueDepth))

  val f4 = Module(new F4DecodeWindow(p))
  f4.io.in := io.in
  f4.io.flushValid := io.frontendFlushValid

  val path = Module(new DecodeRenameROBPath(
    p = p,
    traceParams = traceParams,
    decRenQueueDepth = decRenQueueDepth,
    storeDispatchQueueDepth = storeDispatchQueueDepth,
    mapQDepth = mapQDepth
  ))

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
  path.io.deallocHoldMask := 0.U
  path.io.checkpointValid := false.B
  path.io.checkpointBid := ROBID.disabled(p.robEntries)
  path.io.checkpointStid := 0.U
  path.io.commitValid := false.B
  path.io.commitBid := ROBID.disabled(p.robEntries)
  path.io.commitBlockBid := 0.U
  path.io.commitStid := 0.U
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
  path.io.robFullBidLookupRequest := 0.U.asTypeOf(path.io.robFullBidLookupRequest)

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
  io.idle := path.io.empty
}

object LinxCoreFrontendTraceTop {
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

object EmitLinxCoreFrontendTraceTop extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new LinxCoreFrontendTraceTop(CoreParams(robEntries = 8, commitWidth = 2), mapQDepth = 8),
    args = Array("--target-dir", "../generated/chisel-verilog/frontend-trace-top"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
