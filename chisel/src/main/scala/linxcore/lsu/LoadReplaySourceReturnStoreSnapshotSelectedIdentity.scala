package linxcore.lsu

import chisel3._
import chisel3.util.{UIntToOH, log2Ceil}

class LoadReplaySourceReturnStoreSnapshotSelectedIdentityIO(
    val liqEntries: Int,
    clusterIdWidth: Int,
    entryIdWidth: Int)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val launchValid = Input(Bool())
  val launchIndex = Input(UInt(liqPtrWidth.W))
  val repickMask = Input(UInt(liqEntries.W))

  val active = Output(Bool())
  val selectedValid = Output(Bool())
  val selectedRepick = Output(Bool())
  val selectedClusterId = Output(UInt(clusterIdWidth.W))
  val selectedEntryId = Output(UInt(entryIdWidth.W))
  val selectedIndexOneHot = Output(UInt(liqEntries.W))
  val repickMaskHit = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoLaunch = Output(Bool())
  val blockedByNotRepick = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotSelectedIdentity(
    val liqEntries: Int = 4,
    clusterIdWidth: Int = 2,
    entryIdWidth: Int = 4)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth >= log2Ceil(liqEntries), "entryIdWidth must cover the reduced LIQ slot index")

  val io = IO(new LoadReplaySourceReturnStoreSnapshotSelectedIdentityIO(
    liqEntries = liqEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth
  ))

  val active = io.enable && !io.flush
  val rawIndexOneHot = UIntToOH(io.launchIndex, liqEntries)
  val selectedIndexOneHot = Mux(active && io.launchValid, rawIndexOneHot, 0.U(liqEntries.W))
  val repickMaskHit = (io.repickMask & rawIndexOneHot).orR
  val selectedValid = active && io.launchValid
  val selectedRepick = selectedValid && repickMaskHit

  io.active := active
  io.selectedValid := selectedValid
  io.selectedRepick := selectedRepick
  io.selectedClusterId := 0.U
  io.selectedEntryId := io.launchIndex.pad(entryIdWidth)
  io.selectedIndexOneHot := selectedIndexOneHot
  io.repickMaskHit := selectedValid && repickMaskHit
  io.blockedByDisabled := !io.enable && io.launchValid
  io.blockedByFlush := io.enable && io.flush && io.launchValid
  io.blockedByNoLaunch := active && !io.launchValid
  io.blockedByNotRepick := selectedValid && !repickMaskHit
}
