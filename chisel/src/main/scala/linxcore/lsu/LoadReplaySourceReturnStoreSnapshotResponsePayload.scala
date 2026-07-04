package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle(
    val idEntries: Int,
    val clusterIdWidth: Int,
    val entryIdWidth: Int,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  val valid = Bool()
  val clusterId = UInt(clusterIdWidth.W)
  val entryId = UInt(entryIdWidth.W)
  val requestBid = new ROBID(idEntries)
  val requestGid = new ROBID(idEntries)
  val requestRid = new ROBID(idEntries)
  val requestLoadLsId = new ROBID(idEntries)
  val waitStore = Bool()
  val dataValid = Bool()
  val rawDataValid = Bool()
  val dataSuppressedByWait = Bool()
  val waitStoreIndex = UInt(log2Ceil(idEntries).W)
  val waitStoreBid = new ROBID(idEntries)
  val waitStoreRid = new ROBID(idEntries)
  val waitStoreLsId = new ROBID(idEntries)
  val waitStorePc = UInt(pcWidth.W)
  val dataMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
}
