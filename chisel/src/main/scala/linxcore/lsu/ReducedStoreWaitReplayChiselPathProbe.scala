package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class ReducedStoreWaitReplayChiselPathProbeIO(
    val entries: Int,
    val liqEntries: Int,
    val addrWidth: Int,
    val dataWidth: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val sizeWidth: Int)
    extends Bundle {
  val flush = Input(Bool())

  val storeInsertValid = Input(Bool())
  val storeInsert = Input(new STQStoreRequest(entries, addrWidth, dataWidth, sizeWidth = 4, pcWidth = pcWidth))
  val storeInsertAccepted = Output(Bool())
  val stqOccupiedMask = Output(UInt(entries.W))
  val stqAddrReadyMask = Output(UInt(entries.W))
  val stqDataReadyMask = Output(UInt(entries.W))

  val loadValid = Input(Bool())
  val loadAddr = Input(UInt(addrWidth.W))
  val loadSize = Input(UInt(sizeWidth.W))
  val loadBid = Input(new ROBID(entries))
  val loadLsId = Input(new ROBID(entries))
  val baseLoadData = Input(UInt(dataWidth.W))
  val captureEnable = Input(Bool())

  val forwardWaitBlocked = Output(Bool())
  val forwardReady = Output(Bool())
  val forwardWaitStoreValid = Output(Bool())
  val forwardWaitStoreIndex = Output(UInt(log2Ceil(entries).W))

  val waitSlotCaptureAccepted = Output(Bool())
  val waitSlotActive = Output(Bool())
  val waitStoreClear = Output(Bool())
  val wakeValid = Output(Bool())
  val wakeIdentityMatch = Output(Bool())
  val wakeSelectedRowReady = Output(Bool())

  val relaunchQueuePending = Output(Bool())
  val relaunchQueueOutValid = Output(Bool())
  val relaunchQueueOutFire = Output(Bool())

  val liqCandidateConsumeReady = Output(Bool())
  val liqAllocAccepted = Output(Bool())
  val liqRefillValid = Input(Bool())
  val liqRefillLineAddr = Input(UInt(addrWidth.W))
  val liqRefillData = Input(UInt((lineBytes * 8).W))
  val liqRefillAccepted = Output(Bool())
  val liqRefillWakeMask = Output(UInt(liqEntries.W))
  val liqLaunchEnable = Input(Bool())
  val liqLaunchValid = Output(Bool())
  val liqLaunchDriveValid = Output(Bool())
  val liqLaunchReady = Output(Bool())
  val liqLaunchAccepted = Output(Bool())
  val liqLaunchIndex = Output(UInt(log2Ceil(liqEntries).W))
  val liqLaunchSelectedLoadLsId = Output(new ROBID(entries))
  val liqResidentCount = Output(UInt(log2Ceil(liqEntries + 1).W))
  val liqOccupiedMask = Output(UInt(liqEntries.W))
  val liqWaitMask = Output(UInt(liqEntries.W))
  val liqRepickMask = Output(UInt(liqEntries.W))
  val liqFirstYoungestStoreLsId = Output(new ROBID(entries))
}

class ReducedStoreWaitReplayChiselPathProbe(
    val entries: Int = 8,
    val liqEntries: Int = 4,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0)
  require(liqEntries > 1 && (liqEntries & (liqEntries - 1)) == 0)

  val io = IO(new ReducedStoreWaitReplayChiselPathProbeIO(
    entries,
    liqEntries,
    addrWidth,
    dataWidth,
    pcWidth,
    lineBytes,
    sizeWidth
  ))

  val stq = Module(new STQEntryBank(entries, addrWidth, dataWidth, sizeWidth = 4))
  stq.io.flush := 0.U.asTypeOf(stq.io.flush)
  stq.io.flush.req.valid := io.flush
  stq.io.insertValid := io.storeInsertValid && !io.flush
  stq.io.insert := io.storeInsert
  stq.io.markCommitValid := false.B
  stq.io.markCommitIndex := 0.U
  stq.io.commitFreeValid := false.B
  stq.io.commitFreeIndex := 0.U
  stq.io.commitFreeMaskValid := false.B
  stq.io.commitFreeMask := 0.U

  val forward = Module(new ReducedStoreResidentForward(entries, addrWidth, dataWidth, sizeWidth = 4, pcWidth = pcWidth, lineBytes = lineBytes))
  forward.io.enable := !io.flush
  forward.io.loadValid := io.loadValid
  forward.io.loadAddr := io.loadAddr
  forward.io.loadSize := io.loadSize
  forward.io.loadBid := io.loadBid
  forward.io.loadLsId := io.loadLsId
  forward.io.baseLoadData := io.baseLoadData
  forward.io.rows := stq.io.rows

  val waitSlot = Module(new ReducedLoadWaitReplaySlot(entries, entries, addrWidth, pcWidth, lineBytes, sizeWidth))
  waitSlot.io.flush := io.flush
  waitSlot.io.captureValid := io.captureEnable && forward.io.waitBlocked
  waitSlot.io.capturePc := 0.U
  waitSlot.io.captureAddr := io.loadAddr
  waitSlot.io.captureSize := io.loadSize
  waitSlot.io.captureReturnSignExtend := false.B
  waitSlot.io.captureDst := LoadReplayDestination.none()
  waitSlot.io.captureSourceTraceValid := false.B
  waitSlot.io.captureSource0 := 0.U.asTypeOf(waitSlot.io.captureSource0)
  waitSlot.io.captureSource1 := 0.U.asTypeOf(waitSlot.io.captureSource1)
  waitSlot.io.captureBid := io.loadBid
  waitSlot.io.captureGid := ROBID.zero(entries)
  waitSlot.io.captureRid := ROBID.zero(entries)
  waitSlot.io.captureLsId := io.loadLsId
  waitSlot.io.captureYoungestStoreId := forward.io.waitStore.storeId
  waitSlot.io.captureYoungestStoreLsId := forward.io.waitStore.storeLsId
  waitSlot.io.captureWaitStore := forward.io.waitStore

  val wake = Module(new ResidentStoreReplayWakeup(entries, addrWidth, dataWidth, sizeWidth = 4, pcWidth = pcWidth, lineBytes = lineBytes))
  wake.io.enable := !io.flush
  wake.io.waitStore := waitSlot.io.storedWaitStore
  wake.io.rows := stq.io.rows
  waitSlot.io.replayWakeValid := wake.io.wakeValid
  waitSlot.io.replayWake := wake.io.wake

  val relaunchQueue = Module(new ReducedLoadReplayRelaunchQueue(entries, depth = 2, addrWidth, pcWidth, sizeWidth))
  relaunchQueue.io.flush := io.flush
  relaunchQueue.io.enqueueValid := waitSlot.io.relaunch.valid
  relaunchQueue.io.enqueue := waitSlot.io.relaunch

  val liq = Module(new ReducedLoadReplayLiqAllocPath(liqEntries, entries, entries, addrWidth, pcWidth, lineBytes, sizeWidth))
  liq.io.flush := io.flush
  liq.io.candidateValid := relaunchQueue.io.outValid
  liq.io.candidate := relaunchQueue.io.out
  liq.io.launchEnable := io.liqLaunchEnable
  liq.io.e2Stores := 0.U.asTypeOf(liq.io.e2Stores)
  liq.io.e2BaseData := 0.U
  liq.io.e2BaseValidMask := 0.U
  liq.io.e2LoadDataReturned := false.B
  liq.io.e2ScbReturned := false.B
  liq.io.e2StqReturned := false.B
  liq.io.e2ReturnReady := false.B
  liq.io.refillValid := io.liqRefillValid
  liq.io.refill.isRead := true.B
  liq.io.refill.lineAddr := io.liqRefillLineAddr
  liq.io.refill.data := io.liqRefillData
  liq.io.refill.l2Miss := false.B
  liq.io.clearResolvedValid := false.B
  liq.io.clearResolvedIndex := 0.U
  liq.io.rowMutationRequestValid := false.B
  liq.io.rowMutationRequestTargetMask := 0.U
  liq.io.rowMutationRequestTargetIndex := 0.U
  liq.io.rowMutationSetWaitStatus := false.B
  liq.io.rowMutationKeepRepickStatus := false.B
  liq.io.rowMutationClearReturnState := false.B
  liq.io.rowMutationLineWrite := false.B
  liq.io.rowMutationWaitStoreWrite := false.B
  liq.io.rowMutationNextWaitStore := false.B
  liq.io.rowMutationNextWaitStoreInfo := 0.U.asTypeOf(liq.io.rowMutationNextWaitStoreInfo)
  liq.io.rowMutationNextLineData := 0.U
  liq.io.rowMutationNextValidMask := 0.U
  liq.io.rowMutationNextDataComplete := false.B
  liq.io.rowMutationNextScbReturned := false.B
  liq.io.rowMutationNextStqReturned := false.B
  liq.io.rowMutationNextStoreSourceReturned := false.B

  relaunchQueue.io.outReady := liq.io.candidateConsumeReady

  io.storeInsertAccepted := stq.io.insertAccepted
  io.stqOccupiedMask := stq.io.occupiedMask
  io.stqAddrReadyMask := stq.io.addrReadyMask
  io.stqDataReadyMask := stq.io.dataReadyMask
  io.forwardWaitBlocked := forward.io.waitBlocked
  io.forwardReady := forward.io.readyForward
  io.forwardWaitStoreValid := forward.io.waitStore.valid
  io.forwardWaitStoreIndex := forward.io.waitStore.storeIndex
  io.waitSlotCaptureAccepted := waitSlot.io.captureAccepted
  io.waitSlotActive := waitSlot.io.active
  io.waitStoreClear := waitSlot.io.waitStoreClear
  io.wakeValid := wake.io.wakeValid
  io.wakeIdentityMatch := wake.io.identityMatch
  io.wakeSelectedRowReady := wake.io.selectedRowReady
  io.relaunchQueuePending := relaunchQueue.io.pending
  io.relaunchQueueOutValid := relaunchQueue.io.outValid
  io.relaunchQueueOutFire := relaunchQueue.io.outFire
  io.liqCandidateConsumeReady := liq.io.candidateConsumeReady
  io.liqAllocAccepted := liq.io.allocAccepted
  io.liqRefillAccepted := liq.io.refillAccepted
  io.liqRefillWakeMask := liq.io.refillWakeMask
  io.liqLaunchValid := liq.io.launchValid
  io.liqLaunchDriveValid := liq.io.launchDriveValid
  io.liqLaunchReady := liq.io.launchReady
  io.liqLaunchAccepted := liq.io.launchAccepted
  io.liqLaunchIndex := liq.io.launchIndex
  io.liqLaunchSelectedLoadLsId := liq.io.launchSelectedLoadLsId
  io.liqResidentCount := liq.io.residentCount
  io.liqOccupiedMask := liq.io.occupiedMask
  io.liqWaitMask := liq.io.waitMask
  io.liqRepickMask := liq.io.repickMask
  io.liqFirstYoungestStoreLsId := liq.io.rows(0).youngestStoreLsId
}

object EmitReducedStoreWaitReplayChiselPathProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new ReducedStoreWaitReplayChiselPathProbe(entries = 8, liqEntries = 4),
    args = Array("--target-dir", "../generated/chisel-verilog/reduced-store-wait-replay-chisel-path"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
