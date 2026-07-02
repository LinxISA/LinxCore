package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class ReducedLoadReplayLiqAllocPathIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)
  private val countWidth = log2Ceil(liqEntries + 1)

  val flush = Input(Bool())
  val candidateValid = Input(Bool())
  val candidate = Input(new ReducedLoadReplayCandidate(idEntries, addrWidth, pcWidth, sizeWidth))

  val clearResolvedValid = Input(Bool())
  val clearResolvedIndex = Input(UInt(liqPtrWidth.W))

  val candidateConsumeReady = Output(Bool())
  val candidateUsable = Output(Bool())
  val candidateBlockedByAlloc = Output(Bool())

  val allocValid = Output(Bool())
  val allocReady = Output(Bool())
  val allocAccepted = Output(Bool())
  val allocIndex = Output(UInt(liqPtrWidth.W))
  val allocLoadId = Output(new ROBID(liqEntries))

  val rows = Output(Vec(
    liqEntries,
    new LoadInflightRow(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth)
  ))
  val occupiedMask = Output(UInt(liqEntries.W))
  val waitMask = Output(UInt(liqEntries.W))
  val waitStoreMask = Output(UInt(liqEntries.W))
  val launchWaitMask = Output(UInt(liqEntries.W))
  val launchWaitStoreBlockedMask = Output(UInt(liqEntries.W))
  val launchTileBlockedMask = Output(UInt(liqEntries.W))
  val launchUnblockedWaitMask = Output(UInt(liqEntries.W))
  val launchRequestCompleteMask = Output(UInt(liqEntries.W))
  val launchDataHitMask = Output(UInt(liqEntries.W))
  val launchCandidateMask = Output(UInt(liqEntries.W))
  val launchMask = Output(UInt(liqEntries.W))
  val launchValid = Output(Bool())
  val launchIndex = Output(UInt(liqPtrWidth.W))
  val launchCandidateCount = Output(UInt(countWidth.W))
  val residentCount = Output(UInt(countWidth.W))
  val empty = Output(Bool())
  val full = Output(Bool())
  val clearResolvedAccepted = Output(Bool())
}

class ReducedLoadReplayLiqAllocPath(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(lineBytes == 64, "ReducedLoadReplayLiqAllocPath currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  private val liqPtrWidth = log2Ceil(liqEntries)

  val io = IO(new ReducedLoadReplayLiqAllocPathIO(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth
  ))

  val adapter = Module(new ReducedLoadReplayLiqAllocAdapter(liqEntries, idEntries, addrWidth, pcWidth, sizeWidth))
  val liq = Module(new LoadInflightQueue(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))
  val launchSelect = Module(new LoadInflightLaunchSelect(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth))

  adapter.io.flush := io.flush
  adapter.io.candidateValid := io.candidateValid
  adapter.io.candidate := io.candidate
  adapter.io.allocReady := liq.io.allocReady

  liq.io.flush := io.flush
  liq.io.allocValid := adapter.io.allocValid
  liq.io.alloc := adapter.io.alloc

  liq.io.launchValid := false.B
  liq.io.launchIndex := 0.U(liqPtrWidth.W)
  liq.io.e2Stores := 0.U.asTypeOf(liq.io.e2Stores)
  liq.io.e2BaseData := 0.U
  liq.io.e2BaseValidMask := 0.U
  liq.io.e2LoadDataReturned := false.B
  liq.io.e2ScbReturned := false.B
  liq.io.e2ReturnReady := false.B
  liq.io.replayWakeValid := false.B
  liq.io.replayWake := 0.U.asTypeOf(liq.io.replayWake)
  liq.io.refillValid := false.B
  liq.io.refill := 0.U.asTypeOf(liq.io.refill)
  liq.io.clearResolvedValid := io.clearResolvedValid
  liq.io.clearResolvedIndex := io.clearResolvedIndex

  launchSelect.io.enable := !io.flush
  launchSelect.io.rows := liq.io.rows

  io.candidateConsumeReady := adapter.io.consumeReady
  io.candidateUsable := adapter.io.candidateUsable
  io.candidateBlockedByAlloc := adapter.io.blockedByAlloc
  io.allocValid := adapter.io.allocValid
  io.allocReady := liq.io.allocReady
  io.allocAccepted := liq.io.allocAccepted
  io.allocIndex := liq.io.allocIndex
  io.allocLoadId := liq.io.allocLoadId
  io.rows := liq.io.rows
  io.occupiedMask := liq.io.occupiedMask
  io.waitMask := liq.io.waitMask
  io.waitStoreMask := liq.io.waitStoreMask
  io.launchWaitMask := launchSelect.io.waitMask
  io.launchWaitStoreBlockedMask := launchSelect.io.waitStoreBlockedMask
  io.launchTileBlockedMask := launchSelect.io.tileBlockedMask
  io.launchUnblockedWaitMask := launchSelect.io.unblockedWaitMask
  io.launchRequestCompleteMask := launchSelect.io.requestCompleteMask
  io.launchDataHitMask := launchSelect.io.dataHitMask
  io.launchCandidateMask := launchSelect.io.launchCandidateMask
  io.launchMask := launchSelect.io.launchMask
  io.launchValid := launchSelect.io.launchValid
  io.launchIndex := launchSelect.io.launchIndex
  io.launchCandidateCount := launchSelect.io.candidateCount
  io.residentCount := liq.io.residentCount
  io.empty := liq.io.empty
  io.full := liq.io.full
  io.clearResolvedAccepted := liq.io.clearResolvedAccepted
}
