package linxcore.lsu

import chisel3._
import chisel3.util.Fill
import circt.stage.ChiselStage

class ScalarL1DScbProbeIO extends Bundle {
  val requestValid = Input(Bool())
  val requestAddr = Input(UInt(64.W))
  val requestData = Input(UInt(64.W))
  val requestSize = Input(UInt(4.W))
  val requestAccepted = Output(Bool())
  val evictEnable = Input(Bool())
  val l2Ready = Input(Bool())
  val l2Valid = Output(Bool())
  val l2Upgrade = Output(Bool())
  val l2TxnId = Output(UInt(3.W))
  val rawRespValid = Input(Bool())
  val rawRespTxnId = Input(UInt(3.W))
  val rawRespUpgrade = Input(Bool())
  val rawRespReady = Output(Bool())
  val respDecodedUpgrade = Output(Bool())
  val dcacheUpdateValid = Output(Bool())
  val lookupStall = Output(Bool())
  val scbEntryCount = Output(UInt(2.W))
  val refillValid = Input(Bool())
  val refillLineAddr = Input(UInt(64.W))
  val refillData = Input(UInt(64.W))
  val refillWritable = Input(Bool())
  val refillReady = Output(Bool())
  val refillDuplicate = Output(Bool())
  val evictionReady = Input(Bool())
  val evictionValid = Output(Bool())
  val evictionLineAddr = Output(UInt(64.W))
  val evictionData = Output(UInt(64.W))
  val evictionDirty = Output(Bool())
  val loadValid = Input(Bool())
  val loadLineAddr = Input(UInt(64.W))
  val loadHit = Output(Bool())
  val loadData = Output(UInt(64.W))
  val dirtyCount = Output(UInt(3.W))
  val protocolError = Output(Bool())
}

class ScalarL1DScbProbe extends Module {
  val io = IO(new ScalarL1DScbProbeIO)
  val scb = Module(new SCBRowBank(
    stqEntries = 4,
    scbEntries = 2,
    requestCount = 1,
    responseBufferDepth = 2
  ))
  val cache = Module(new ScalarL1D(sets = 2, ways = 2, scbEntries = 2))

  scb.io.reqs := 0.U.asTypeOf(scb.io.reqs)
  scb.io.reqs(0).valid := io.requestValid
  scb.io.reqs(0).addr := io.requestAddr
  scb.io.reqs(0).data := io.requestData
  scb.io.reqs(0).size := io.requestSize
  scb.io.reqs(0).last := true.B
  scb.io.evictEnable := io.evictEnable
  scb.io.dcacheReady := cache.io.arrayReady
  scb.io.dcacheWriteHit := cache.io.storeLookup.writeHit
  scb.io.dcacheTagHit := cache.io.storeLookup.tagHit
  scb.io.l2RequestReady := io.l2Ready
  scb.io.rawRespValid := io.rawRespValid
  scb.io.rawRespTxnId := io.rawRespTxnId
  scb.io.rawRespWrite := false.B
  scb.io.rawRespUpgrade := io.rawRespUpgrade

  cache.io.loadLookupValid := io.loadValid
  cache.io.loadLookupLineAddr := io.loadLineAddr
  cache.io.storeLookupValid := scb.io.lookupRequest.valid
  cache.io.storeLookupLineAddr := scb.io.lookupRequest.lineAddr
  cache.io.storeUpdate := scb.io.dcacheUpdate
  cache.io.grantWriteValid := scb.io.respDecodedUpgrade
  cache.io.grantWriteLineAddr := scb.io.entries(scb.io.respDecodedEntryIndex).lineAddr
  cache.io.refill.valid := io.refillValid
  cache.io.refill.lineAddr := io.refillLineAddr
  cache.io.refill.data := Fill(8, io.refillData)
  cache.io.refill.writable := io.refillWritable
  cache.io.evictionReady := io.evictionReady

  io.requestAccepted := scb.io.acceptedMask(0)
  io.l2Valid := scb.io.l2Request.valid
  io.l2Upgrade := scb.io.l2Request.upgrade
  io.l2TxnId := scb.io.l2Request.txnTid
  io.rawRespReady := scb.io.rawRespReady
  io.respDecodedUpgrade := scb.io.respDecodedUpgrade
  io.dcacheUpdateValid := scb.io.dcacheUpdate.valid
  io.lookupStall := scb.io.lookupStall
  io.scbEntryCount := scb.io.entryCount
  io.refillReady := cache.io.refillReady
  io.refillDuplicate := cache.io.refillDuplicate
  io.evictionValid := cache.io.eviction.valid
  io.evictionLineAddr := cache.io.eviction.lineAddr
  io.evictionData := cache.io.eviction.data(63, 0)
  io.evictionDirty := cache.io.eviction.dirty
  io.loadHit := cache.io.loadLookup.readHit
  io.loadData := cache.io.loadLookup.data(63, 0)
  io.dirtyCount := cache.io.dirtyCount
  io.protocolError := cache.io.protocolError || scb.io.stateError || scb.io.respDecodeError
}

object EmitScalarL1DScbProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new ScalarL1DScbProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info")
  )
}
