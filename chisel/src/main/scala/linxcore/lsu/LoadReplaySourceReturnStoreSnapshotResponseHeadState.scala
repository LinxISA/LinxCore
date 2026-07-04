package linxcore.lsu

import chisel3._
import chisel3.util.{UIntToOH, log2Ceil}

class LoadReplaySourceReturnStoreSnapshotResponseHeadStateIO(
    val liqEntries: Int,
    clusterIdWidth: Int,
    entryIdWidth: Int)
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val reducedEnable = Input(Bool())
  val headValid = Input(Bool())
  val responseClusterId = Input(UInt(clusterIdWidth.W))
  val responseEntryId = Input(UInt(entryIdWidth.W))
  val repickMask = Input(UInt(liqEntries.W))
  val externalHeadStale = Input(Bool())

  val active = Output(Bool())
  val headStale = Output(Bool())
  val externalHeadStaleUsed = Output(Bool())
  val reducedHeadTargetsRow = Output(Bool())
  val reducedHeadRepick = Output(Bool())
  val reducedHeadStale = Output(Bool())
  val reducedHeadOneHot = Output(UInt(liqEntries.W))
  val blockedByNoHead = Output(Bool())
  val blockedByReducedDisabled = Output(Bool())
  val blockedByUnsupportedCluster = Output(Bool())
  val blockedByEntryOutOfRange = Output(Bool())
  val blockedByStillRepick = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotResponseHeadState(
    val liqEntries: Int = 4,
    clusterIdWidth: Int = 2,
    entryIdWidth: Int = 4)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth >= log2Ceil(liqEntries), "entryIdWidth must cover the reduced LIQ slot index")

  private val liqPtrWidth = log2Ceil(liqEntries)

  val io = IO(new LoadReplaySourceReturnStoreSnapshotResponseHeadStateIO(
    liqEntries = liqEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth
  ))

  val active = io.enable && !io.flush
  val reducedCandidate = active && io.reducedEnable && io.headValid
  val clusterIsReduced = io.responseClusterId === 0.U
  val entryInRange = io.responseEntryId < liqEntries.U
  val responseEntryIndex = io.responseEntryId(liqPtrWidth - 1, 0)
  val rawOneHot = UIntToOH(responseEntryIndex, liqEntries)
  val reducedHeadOneHot = Mux(reducedCandidate && clusterIsReduced && entryInRange, rawOneHot, 0.U(liqEntries.W))
  val repickMaskHit = (io.repickMask & rawOneHot).orR
  val reducedHeadTargetsRow = reducedCandidate && clusterIsReduced && entryInRange
  val reducedHeadRepick = reducedHeadTargetsRow && repickMaskHit
  val reducedHeadStale = reducedHeadTargetsRow && !repickMaskHit
  val externalHeadStaleUsed = active && io.headValid && io.externalHeadStale

  io.active := active
  io.headStale := externalHeadStaleUsed || reducedHeadStale
  io.externalHeadStaleUsed := externalHeadStaleUsed
  io.reducedHeadTargetsRow := reducedHeadTargetsRow
  io.reducedHeadRepick := reducedHeadRepick
  io.reducedHeadStale := reducedHeadStale
  io.reducedHeadOneHot := reducedHeadOneHot
  io.blockedByNoHead := active && !io.headValid
  io.blockedByReducedDisabled := active && io.headValid && !io.reducedEnable && !io.externalHeadStale
  io.blockedByUnsupportedCluster := reducedCandidate && !clusterIsReduced
  io.blockedByEntryOutOfRange := reducedCandidate && clusterIsReduced && !entryInRange
  io.blockedByStillRepick := reducedHeadRepick
  io.blockedByDisabled := !io.enable && io.headValid
  io.blockedByFlush := io.enable && io.flush && io.headValid
}
