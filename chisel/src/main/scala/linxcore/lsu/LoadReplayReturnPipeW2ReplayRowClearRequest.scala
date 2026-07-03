package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class LoadReplayReturnPipeW2ReplayRowClearRequestIO(val liqEntries: Int = 16) extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())

  val existingClearValid = Input(Bool())
  val existingClearIndex = Input(UInt(liqPtrWidth.W))

  val lifecycleClearRequestEnable = Input(Bool())
  val lifecycleClearCommitEnable = Input(Bool())
  val lifecycleRowClearReady = Input(Bool())
  val lifecycleRowClearIndex = Input(UInt(liqPtrWidth.W))

  val clearResolvedAccepted = Input(Bool())

  val active = Output(Bool())
  val existingClearCandidate = Output(Bool())
  val lifecycleClearCandidate = Output(Bool())
  val lifecycleClearEnable = Output(Bool())
  val lifecycleClearSelected = Output(Bool())
  val clearResolvedValid = Output(Bool())
  val clearResolvedIndex = Output(UInt(liqPtrWidth.W))
  val existingClearAccepted = Output(Bool())
  val lifecycleClearAccepted = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByExistingClear = Output(Bool())
  val blockedByLifecycleRequestDisabled = Output(Bool())
  val blockedByNoLifecycleRow = Output(Bool())
  val blockedByLifecycleCommitDisabled = Output(Bool())
  val invalidLifecycleCommitWithoutSelection = Output(Bool())
}

class LoadReplayReturnPipeW2ReplayRowClearRequest(val liqEntries: Int = 16) extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")

  private val liqPtrWidth = log2Ceil(liqEntries)

  val io = IO(new LoadReplayReturnPipeW2ReplayRowClearRequestIO(liqEntries))

  val active = io.enable && !io.flush
  val anyClearIntent = io.existingClearValid || io.lifecycleClearRequestEnable || io.lifecycleRowClearReady
  val existingClearCandidate = active && io.existingClearValid
  val lifecycleRequested = active && io.lifecycleClearRequestEnable
  val lifecycleClearCandidate = lifecycleRequested && io.lifecycleRowClearReady
  val lifecycleClearSelected = lifecycleClearCandidate && !existingClearCandidate
  val lifecycleClearEnable = lifecycleClearSelected
  val committedLifecycleClear = lifecycleClearSelected && io.lifecycleClearCommitEnable
  val clearResolvedValid = existingClearCandidate || committedLifecycleClear

  io.active := active
  io.existingClearCandidate := existingClearCandidate
  io.lifecycleClearCandidate := lifecycleClearCandidate
  io.lifecycleClearEnable := lifecycleClearEnable
  io.lifecycleClearSelected := lifecycleClearSelected
  io.clearResolvedValid := clearResolvedValid
  io.clearResolvedIndex := Mux(
    existingClearCandidate,
    io.existingClearIndex,
    Mux(committedLifecycleClear, io.lifecycleRowClearIndex, 0.U(liqPtrWidth.W))
  )
  io.existingClearAccepted := io.clearResolvedAccepted && existingClearCandidate
  io.lifecycleClearAccepted := io.clearResolvedAccepted && committedLifecycleClear
  io.blockedByDisabled := !io.enable && anyClearIntent
  io.blockedByFlush := io.enable && io.flush && anyClearIntent
  io.blockedByExistingClear := lifecycleRequested && existingClearCandidate
  io.blockedByLifecycleRequestDisabled :=
    active && io.lifecycleRowClearReady && !io.lifecycleClearRequestEnable
  io.blockedByNoLifecycleRow := lifecycleRequested && !io.lifecycleRowClearReady
  io.blockedByLifecycleCommitDisabled := lifecycleClearSelected && !io.lifecycleClearCommitEnable
  io.invalidLifecycleCommitWithoutSelection :=
    io.lifecycleClearCommitEnable && !lifecycleClearSelected
}
