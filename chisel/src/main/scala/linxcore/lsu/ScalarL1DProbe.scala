package linxcore.lsu

import chisel3._
import chisel3.util.Fill
import circt.stage.ChiselStage

class ScalarL1DProbeIO extends Bundle {
  val hardFlush = Input(Bool())
  val preciseFlush = Input(Bool())
  val loadValid = Input(Bool())
  val loadLineAddr = Input(UInt(64.W))
  val loadHit = Output(Bool())
  val loadData = Output(UInt(64.W))
  val refillValid = Input(Bool())
  val refillLineAddr = Input(UInt(64.W))
  val refillData = Input(UInt(64.W))
  val refillWritable = Input(Bool())
  val refillReady = Output(Bool())
  val refillAccepted = Output(Bool())
  val refillDuplicate = Output(Bool())
  val refillReturnData = Output(UInt(64.W))
  val evictionReady = Input(Bool())
  val evictionValid = Output(Bool())
  val evictionLineAddr = Output(UInt(64.W))
  val evictionData = Output(UInt(64.W))
  val evictionDirty = Output(Bool())
  val storeLookupValid = Input(Bool())
  val storeLineAddr = Input(UInt(64.W))
  val storeTagHit = Output(Bool())
  val storeWriteHit = Output(Bool())
  val grantWriteValid = Input(Bool())
  val storeUpdateValid = Input(Bool())
  val storeByteMask = Input(UInt(64.W))
  val storeData = Input(UInt(512.W))
  val arrayReady = Output(Bool())
  val residentCount = Output(UInt(3.W))
  val dirtyCount = Output(UInt(3.W))
  val protocolError = Output(Bool())
}

class ScalarL1DProbe extends Module {
  val io = IO(new ScalarL1DProbeIO)
  val cache = Module(new ScalarL1D(sets = 2, ways = 2, scbEntries = 4))

  cache.io.loadLookupValid := io.loadValid
  cache.io.loadLookupLineAddr := io.loadLineAddr
  cache.io.storeLookupValid := io.storeLookupValid
  cache.io.storeLookupLineAddr := io.storeLineAddr
  cache.io.grantWriteValid := io.grantWriteValid
  cache.io.grantWriteLineAddr := io.storeLineAddr
  cache.io.storeUpdate := 0.U.asTypeOf(cache.io.storeUpdate)
  cache.io.storeUpdate.valid := io.storeUpdateValid
  cache.io.storeUpdate.lineAddr := io.storeLineAddr
  cache.io.storeUpdate.byteMask := io.storeByteMask
  cache.io.storeUpdate.data := io.storeData
  cache.io.refill.valid := io.refillValid
  cache.io.refill.lineAddr := io.refillLineAddr
  cache.io.refill.data := Fill(8, io.refillData)
  cache.io.refill.writable := io.refillWritable
  cache.io.evictionReady := io.evictionReady

  // Cache state is physical and intentionally has no Linx recovery input.
  dontTouch(io.hardFlush)
  dontTouch(io.preciseFlush)

  io.loadHit := cache.io.loadLookup.readHit
  io.loadData := cache.io.loadLookup.data(63, 0)
  io.refillReady := cache.io.refillReady
  io.refillAccepted := cache.io.refillAccepted
  io.refillDuplicate := cache.io.refillDuplicate
  io.refillReturnData := cache.io.refillData(63, 0)
  io.evictionValid := cache.io.eviction.valid
  io.evictionLineAddr := cache.io.eviction.lineAddr
  io.evictionData := cache.io.eviction.data(63, 0)
  io.evictionDirty := cache.io.eviction.dirty
  io.storeTagHit := cache.io.storeLookup.tagHit
  io.storeWriteHit := cache.io.storeLookup.writeHit
  io.arrayReady := cache.io.arrayReady
  io.residentCount := cache.io.residentCount
  io.dirtyCount := cache.io.dirtyCount
  io.protocolError := cache.io.protocolError
}

object EmitScalarL1DProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new ScalarL1DProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info")
  )
}
