package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle(
    val idEntries: Int,
    val clusterIdWidth: Int,
    val entryIdWidth: Int,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val storeEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val physicalStoreEntries = if (storeEntries > 0) storeEntries else idEntries
  val valid = Bool()
  val clusterId = UInt(clusterIdWidth.W)
  val entryId = UInt(entryIdWidth.W)
  val requestBid = new ROBID(idEntries)
  val requestGid = new ROBID(idEntries)
  val requestRid = new ROBID(idEntries)
  val requestLoadLsId = new ROBID(idEntries)
  val requestLoadLsIdFullValid = Bool()
  val requestLoadLsIdFull = UInt(lsidWidth.W)
  val requestPeId = UInt(peIdWidth.W)
  val requestStid = UInt(stidWidth.W)
  val requestTid = UInt(tidWidth.W)
  val waitStore = Bool()
  val dataValid = Bool()
  val rawDataValid = Bool()
  val dataSuppressedByWait = Bool()
  val waitStoreIndex = UInt(log2Ceil(physicalStoreEntries).W)
  val waitStoreBid = new ROBID(idEntries)
  val waitStoreRid = new ROBID(idEntries)
  val waitStoreLsId = new ROBID(idEntries)
  val waitStoreLsIdFullValid = Bool()
  val waitStoreLsIdFull = UInt(lsidWidth.W)
  val waitStorePc = UInt(pcWidth.W)
  val dataMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
}
