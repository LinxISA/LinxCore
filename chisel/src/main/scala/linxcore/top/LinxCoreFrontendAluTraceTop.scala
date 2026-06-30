package linxcore.top

import chisel3._
import chisel3.util.log2Ceil

import linxcore.backend.DecodeRenameROBPath
import linxcore.commit.{CommitTraceParams, CommitTracePort}
import linxcore.common.{CoreParams, FrontendDecodePacket, InterfaceParams}
import linxcore.execute.ReducedScalarAluExecute
import linxcore.frontend.F4DecodeWindow
import linxcore.lsu.StoreDispatchExecResult
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rob.{ROBEntryStatus, ROBID}

class LinxCoreFrontendAluTraceTopIO(
    val p: InterfaceParams,
    val traceParams: CommitTraceParams,
    val decRenQueueDepth: Int = 4)
    extends Bundle {
  private val ptrWidth = log2Ceil(p.robEntries)
  private val sizeWidth = log2Ceil(p.robEntries + 1)
  private val decRenCountWidth = log2Ceil(decRenQueueDepth + 1)

  val in = Input(new FrontendDecodePacket(p))
  val operandData = Input(Vec(3, UInt(p.immWidth.W)))
  val frontendFlushValid = Input(Bool())
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
  val executeAccepted = Output(Bool())
  val executeBusy = Output(Bool())
  val executeCompleteValid = Output(Bool())
  val executeCompleteRobValue = Output(UInt(ptrWidth.W))
  val executeUnsupported = Output(Bool())
  val executeUnsupportedOpcode = Output(UInt(p.opcodeWidth.W))
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

class LinxCoreFrontendAluTraceTop(
    val coreParams: CoreParams = CoreParams(),
    val decRenQueueDepth: Int = 4,
    val storeDispatchQueueDepth: Int = 4,
    val mapQDepth: Int = 32)
    extends Module {
  private val p = LinxCoreFrontendAluTraceTop.interfaceParamsFor(coreParams)
  private val traceParams = LinxCoreFrontendAluTraceTop.traceParamsFor(p)
  val io = IO(new LinxCoreFrontendAluTraceTopIO(p, traceParams, decRenQueueDepth))

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

  val execute = Module(new ReducedScalarAluExecute(p, traceParams))

  path.io.d1 := f4.io.d1
  path.io.slots := f4.io.slots
  path.io.validMask := f4.io.validMask
  path.io.flushValid := io.frontendFlushValid
  path.io.renamedOutReady := execute.io.inReady
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
  path.io.cleanup := 0.U.asTypeOf(new RecoveryCleanupIntent(p.robEntries, peIdWidth = p.peIdWidth, stidWidth = p.threadIdWidth, tidWidth = p.threadIdWidth))
  path.io.completeValid := execute.io.completeValid
  path.io.completeRobValue := execute.io.completeRobValue
  path.io.completeRowValid := execute.io.completeValid
  path.io.completeRow := execute.io.completeRow
  path.io.blockBranchTakenValid := false.B
  path.io.blockBranchTaken := false.B
  path.io.scalarRedirectValid := false.B
  path.io.deallocReady := io.deallocReady

  execute.io.inValid := path.io.renamedOutValid
  execute.io.in := path.io.renamedOut
  execute.io.srcData := io.operandData
  execute.io.loadLookupData := 0.U

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
  io.executeAccepted := execute.io.accepted
  io.executeBusy := execute.io.busy
  io.executeCompleteValid := execute.io.completeValid
  io.executeCompleteRobValue := execute.io.completeRobValue
  io.executeUnsupported := execute.io.unsupported
  io.executeUnsupportedOpcode := execute.io.unsupportedOpcode
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
  io.idle := path.io.empty && !execute.io.busy
}

object LinxCoreFrontendAluTraceTop {
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

object EmitLinxCoreFrontendAluTraceTop extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new LinxCoreFrontendAluTraceTop(CoreParams(robEntries = 8, commitWidth = 2), mapQDepth = 8),
    args = Array("--target-dir", "../generated/chisel-verilog/frontend-alu-trace-top"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
