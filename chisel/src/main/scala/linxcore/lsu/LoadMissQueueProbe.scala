package linxcore.lsu

import chisel3._
import circt.stage.ChiselStage

import linxcore.rob.ROBID

class LoadMissQueueProbeIO extends Bundle {
  val hardFlush = Input(Bool())
  val preciseFlushValid = Input(Bool())
  val preciseFlushStid = Input(UInt(2.W))
  val preciseFlushBid = Input(UInt(3.W))

  val missValid = Input(Bool())
  val missIndex = Input(UInt(3.W))
  val missLineAddr = Input(UInt(64.W))
  val missStid = Input(UInt(2.W))
  val missBid = Input(UInt(3.W))
  val missLsIdFull = Input(UInt(40.W))
  val missReady = Output(Bool())
  val missAccepted = Output(Bool())
  val missAllocated = Output(Bool())
  val missCoalesced = Output(Bool())

  val requestReady = Input(Bool())
  val requestValid = Output(Bool())
  val requestAccepted = Output(Bool())
  val requestMissSlot = Output(UInt(2.W))
  val requestMissGeneration = Output(Bool())
  val requestLineAddr = Output(UInt(64.W))

  val responseValid = Input(Bool())
  val responseMissValid = Input(Bool())
  val responseMissSlot = Input(UInt(2.W))
  val responseMissGeneration = Input(Bool())
  val responseLineAddr = Input(UInt(64.W))
  val responseIsRead = Input(Bool())
  val responseData = Input(UInt(512.W))
  val refillReady = Input(Bool())
  val responseReady = Output(Bool())
  val responseMatched = Output(Bool())
  val responseStale = Output(Bool())
  val refillValid = Output(Bool())
  val refillLineAddr = Output(UInt(64.W))
  val refillData = Output(UInt(512.W))
  val responseBlockedByRefill = Output(Bool())

  val validMask = Output(UInt(4.W))
  val issuedMask = Output(UInt(4.W))
  val orphanMask = Output(UInt(4.W))
  val dependentCount = Output(UInt(6.W))
  val precisePruneCount = Output(UInt(6.W))
  val pending = Output(Bool())
  val protocolError = Output(Bool())
}

class LoadMissQueueProbe extends Module {
  val io = IO(new LoadMissQueueProbeIO)
  val queue = Module(new LoadMissQueue(
    missEntries = 4,
    liqEntries = 8,
    idEntries = 8,
    storeEntries = 8,
    peIdWidth = 2,
    stidWidth = 2,
    tidWidth = 2,
    lsidWidth = 40))

  val preciseFlush = Wire(chiselTypeOf(queue.io.preciseFlush))
  preciseFlush := 0.U.asTypeOf(preciseFlush)
  preciseFlush.req.valid := io.preciseFlushValid
  preciseFlush.req.stid := io.preciseFlushStid
  preciseFlush.req.bid.valid := io.preciseFlushValid
  preciseFlush.req.bid.wrap := false.B
  preciseFlush.req.bid.value := io.preciseFlushBid
  preciseFlush.baseOnBid := true.B
  queue.io.flush := io.hardFlush
  queue.io.preciseFlush := preciseFlush

  val row = Wire(chiselTypeOf(queue.io.missRow))
  row := 0.U.asTypeOf(row)
  row.valid := io.missValid
  row.status := LoadInflightStatus.Repick
  row.loadId.valid := io.missValid
  row.loadId.wrap := false.B
  row.loadId.value := io.missIndex
  row.peId := 0.U
  row.stid := io.missStid
  row.tid := io.missStid
  row.bid.valid := io.missValid
  row.bid.wrap := false.B
  row.bid.value := io.missBid
  row.gid := ROBID.zero(8)
  row.rid := row.bid
  row.loadLsId.valid := io.missValid
  row.loadLsId.wrap := false.B
  row.loadLsId.value := io.missBid
  row.loadLsIdFullValid := io.missValid
  row.loadLsIdFull := io.missLsIdFull
  row.addr := io.missLineAddr
  row.size := 8.U
  row.isTile := false.B
  queue.io.missValid := io.missValid
  queue.io.missIndex := io.missIndex
  queue.io.missRow := row

  queue.io.requestReady := io.requestReady
  queue.io.responseValid := io.responseValid
  queue.io.response := 0.U.asTypeOf(queue.io.response)
  queue.io.response.missId.valid := io.responseMissValid
  queue.io.response.missId.wrap := io.responseMissGeneration
  queue.io.response.missId.value := io.responseMissSlot
  queue.io.response.lineAddr := io.responseLineAddr
  queue.io.response.isRead := io.responseIsRead
  queue.io.response.data := io.responseData
  queue.io.response.l2Miss := false.B
  queue.io.refillReady := io.refillReady

  io.missReady := queue.io.missReady
  io.missAccepted := queue.io.missAccepted
  io.missAllocated := queue.io.missAllocated
  io.missCoalesced := queue.io.missCoalesced
  io.requestValid := queue.io.requestValid
  io.requestAccepted := queue.io.requestAccepted
  io.requestMissSlot := queue.io.request.missId.value
  io.requestMissGeneration := queue.io.request.missId.wrap
  io.requestLineAddr := queue.io.request.lineAddr
  io.responseReady := queue.io.responseReady
  io.responseMatched := queue.io.responseMatched
  io.responseStale := queue.io.responseStale
  io.refillValid := queue.io.refillValid
  io.refillLineAddr := queue.io.refill.lineAddr
  io.refillData := queue.io.refill.data
  io.responseBlockedByRefill := queue.io.responseBlockedByRefill
  io.validMask := queue.io.validMask
  io.issuedMask := queue.io.issuedMask
  io.orphanMask := queue.io.orphanMask
  io.dependentCount := queue.io.dependentCount
  io.precisePruneCount := queue.io.precisePruneCount
  io.pending := queue.io.pending
  io.protocolError := queue.io.protocolError
}

object EmitLoadMissQueueProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new LoadMissQueueProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
