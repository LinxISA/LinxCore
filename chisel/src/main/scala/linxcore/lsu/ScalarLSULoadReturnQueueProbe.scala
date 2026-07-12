package linxcore.lsu

import chisel3._
import circt.stage.ChiselStage

import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

class ScalarLSULoadReturnQueueProbeIO extends Bundle {
  val hardFlush = Input(Bool())
  val preciseFlushValid = Input(Bool())
  val preciseFlushStid = Input(UInt(1.W))
  val preciseFlushBid = Input(UInt(3.W))
  val preciseFlushWrap = Input(Bool())
  val enqueueValid = Input(Bool())
  val enqueueStid = Input(UInt(1.W))
  val enqueuePipe = Input(UInt(1.W))
  val enqueueBid = Input(UInt(3.W))
  val enqueueWrap = Input(Bool())
  val enqueueLsId = Input(UInt(3.W))
  val enqueueData = Input(UInt(64.W))
  val enqueueReady = Output(Bool())
  val preEnqueueReady = Output(Bool())
  val enqueueAccepted = Output(Bool())
  val drainReady = Input(Bool())
  val drainValid = Output(Bool())
  val drainFire = Output(Bool())
  val drainStid = Output(UInt(1.W))
  val drainPipe = Output(UInt(1.W))
  val drainBid = Output(UInt(3.W))
  val drainWrap = Output(Bool())
  val drainData = Output(UInt(64.W))
  val lane0Count = Output(UInt(2.W))
  val lane1Count = Output(UInt(2.W))
  val lane2Count = Output(UInt(2.W))
  val lane3Count = Output(UInt(2.W))
  val totalCount = Output(UInt(4.W))
  val precisePruneCount = Output(UInt(4.W))
  val full = Output(Bool())
  val blockedByFull = Output(Bool())
}

class ScalarLSULoadReturnQueueProbe extends Module {
  val io = IO(new ScalarLSULoadReturnQueueProbeIO)
  val bank = Module(new ScalarLSULoadReturnQueueBank(
    idEntries = 8,
    stidCount = 2,
    returnPipeCount = 2,
    queueDepth = 2,
    stidWidth = 1,
    tidWidth = 1
  ))

  val preciseFlush = Wire(new FlushBus(8, peIdWidth = 8, stidWidth = 1, tidWidth = 1))
  preciseFlush := 0.U.asTypeOf(preciseFlush)
  preciseFlush.req.valid := io.preciseFlushValid
  preciseFlush.req.stid := io.preciseFlushStid
  preciseFlush.req.bid.valid := io.preciseFlushValid
  preciseFlush.req.bid.wrap := io.preciseFlushWrap
  preciseFlush.req.bid.value := io.preciseFlushBid
  preciseFlush.baseOnBid := true.B

  val entry = Wire(chiselTypeOf(bank.io.enqueue))
  entry := 0.U.asTypeOf(entry)
  entry.valid := io.enqueueValid
  entry.bid.valid := io.enqueueValid
  entry.bid.wrap := io.enqueueWrap
  entry.bid.value := io.enqueueBid
  entry.gid := ROBID.disabled(8)
  entry.rid.valid := io.enqueueValid
  entry.rid.wrap := io.enqueueWrap
  entry.rid.value := io.enqueueBid
  entry.loadLsId.valid := io.enqueueValid
  entry.loadLsId.wrap := io.enqueueWrap
  entry.loadLsId.value := io.enqueueLsId
  entry.data := io.enqueueData

  bank.io.enable := true.B
  bank.io.flush := io.hardFlush
  bank.io.preciseFlush := preciseFlush
  bank.io.enqueueValid := io.enqueueValid
  bank.io.enqueuePeId := 0.U
  bank.io.enqueueStid := io.enqueueStid
  bank.io.enqueueTid := io.enqueueStid
  bank.io.enqueuePipeIndex := io.enqueuePipe
  bank.io.enqueue := entry
  bank.io.drainReady := io.drainReady

  io.enqueueReady := bank.io.enqueueReady
  io.preEnqueueReady := bank.io.preEnqueueReady
  io.enqueueAccepted := bank.io.enqueueAccepted
  io.drainValid := bank.io.drainValid
  io.drainFire := bank.io.drainFire
  io.drainStid := bank.io.drainStid
  io.drainPipe := bank.io.drainPipeIndex
  io.drainBid := bank.io.drain.bid.value
  io.drainWrap := bank.io.drain.bid.wrap
  io.drainData := bank.io.drain.data
  io.lane0Count := bank.io.laneCountState(0)
  io.lane1Count := bank.io.laneCountState(1)
  io.lane2Count := bank.io.laneCountState(2)
  io.lane3Count := bank.io.laneCountState(3)
  io.totalCount := bank.io.totalCount
  io.precisePruneCount := bank.io.precisePruneCount
  io.full := bank.io.full
  io.blockedByFull := bank.io.blockedByFull
}

object EmitScalarLSULoadReturnQueueProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new ScalarLSULoadReturnQueueProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info")
  )
}
