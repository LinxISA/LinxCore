package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, log2Ceil, UIntToOH}

class SCBDCacheUpdate(
    val scbEntries: Int,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))

  val valid = Bool()
  val entryIndex = UInt(entryIndexWidth.W)
  val lineAddr = UInt(addrWidth.W)
  val byteMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
  val broadcastUpgrade = Bool()
}

class SCBL2OwnershipRequest(
    val scbEntries: Int,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))

  val valid = Bool()
  val entryIndex = UInt(entryIndexWidth.W)
  val lineAddr = UInt(addrWidth.W)
  val size = UInt(7.W)
  val txnTid = UInt((entryIndexWidth + 2).W)
  val write = Bool()
  val upgrade = Bool()
}

class SCBLookupControlIO(
    val scbEntries: Int,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  val lookupRequest = Input(new SCBEgressLookupRequest(scbEntries, addrWidth, lineBytes))
  val dcacheReady = Input(Bool())
  val dcacheWriteHit = Input(Bool())
  val dcacheTagHit = Input(Bool())
  val l2RequestReady = Input(Bool())

  val lookupReady = Output(Bool())
  val lookupFire = Output(Bool())
  val lookupStall = Output(Bool())
  val acceptedMask = Output(UInt(scbEntries.W))
  val missMask = Output(UInt(scbEntries.W))
  val freeMask = Output(UInt(scbEntries.W))
  val dcacheUpdate = Output(new SCBDCacheUpdate(scbEntries, addrWidth, lineBytes))
  val l2Request = Output(new SCBL2OwnershipRequest(scbEntries, addrWidth, lineBytes))
}

class SCBLookupControl(
    val scbEntries: Int = 16,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Module {
  require(scbEntries > 0, "SCB lookup control requires at least one entry")
  require(addrWidth >= 7, "SCB lookup control needs at least 7 address bits for 64-byte lines")
  require(lineBytes == 64, "SCB lookup control currently models 64-byte scalar cachelines")

  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))

  val io = IO(new SCBLookupControlIO(scbEntries, addrWidth, lineBytes))

  val needsL2 = !io.dcacheWriteHit
  val lookupReady = io.dcacheReady && (!needsL2 || io.l2RequestReady)
  val lookupFire = io.lookupRequest.valid && lookupReady
  val selectedMask = Mux(lookupFire, UIntToOH(io.lookupRequest.entryIndex, scbEntries), 0.U(scbEntries.W))
  val hitPath = lookupFire && io.dcacheWriteHit
  val missPath = lookupFire && !io.dcacheWriteHit
  val hasStoreBytes = io.lookupRequest.byteMask.orR

  val dcacheUpdate = Wire(new SCBDCacheUpdate(scbEntries, addrWidth, lineBytes))
  dcacheUpdate := 0.U.asTypeOf(dcacheUpdate)
  dcacheUpdate.valid := hitPath && hasStoreBytes
  dcacheUpdate.entryIndex := io.lookupRequest.entryIndex
  dcacheUpdate.lineAddr := io.lookupRequest.lineAddr
  dcacheUpdate.byteMask := io.lookupRequest.byteMask
  dcacheUpdate.data := io.lookupRequest.data
  dcacheUpdate.broadcastUpgrade := dcacheUpdate.valid

  val l2Request = Wire(new SCBL2OwnershipRequest(scbEntries, addrWidth, lineBytes))
  l2Request := 0.U.asTypeOf(l2Request)
  l2Request.valid := missPath
  l2Request.entryIndex := io.lookupRequest.entryIndex
  l2Request.lineAddr := io.lookupRequest.lineAddr
  l2Request.size := lineBytes.U
  l2Request.txnTid := Cat(io.lookupRequest.entryIndex, 2.U(2.W))
  l2Request.write := missPath && !io.dcacheTagHit
  l2Request.upgrade := missPath && io.dcacheTagHit

  io.lookupReady := lookupReady
  io.lookupFire := lookupFire
  io.lookupStall := io.lookupRequest.valid && !lookupReady
  io.acceptedMask := selectedMask
  io.missMask := Mux(missPath, selectedMask, 0.U(scbEntries.W))
  io.freeMask := Mux(hitPath, selectedMask, 0.U(scbEntries.W))
  io.dcacheUpdate := dcacheUpdate
  io.l2Request := l2Request
}
