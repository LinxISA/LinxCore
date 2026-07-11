package linxcore.bctrl

import chisel3._
import circt.stage.ChiselStage

class BrobStoreRangeStateProbeIO extends Bundle {
  val allocValid = Input(Bool())
  val allocBid = Input(UInt(4.W))
  val allocStid = Input(UInt(2.W))
  val storeValid = Input(Bool())
  val storeBid = Input(UInt(4.W))
  val storeStid = Input(UInt(2.W))
  val certainValid = Input(Bool())
  val certainBid = Input(UInt(4.W))
  val certainStid = Input(UInt(2.W))
  val certainUseValue = Input(Bool())
  val certainValue = Input(UInt(8.W))
  val retireValid = Input(Bool())
  val retireBid = Input(UInt(4.W))
  val retireStid = Input(UInt(2.W))
  val recoveryValid = Input(Bool())
  val recoveryStid = Input(UInt(2.W))
  val recoveryFirstKilledBid = Input(UInt(4.W))
  val orderHeadBid = Input(Vec(2, UInt(4.W)))
  val orderLiveCount = Input(Vec(2, UInt(4.W)))
  val queryBid = Input(UInt(4.W))
  val queryStid = Input(UInt(2.W))

  val allocReady = Output(Bool())
  val allocAccepted = Output(Bool())
  val storeAccepted = Output(Bool())
  val certainAccepted = Output(Bool())
  val retireAccepted = Output(Bool())
  val recoveryRewound = Output(Bool())
  val recoveryMissingStart = Output(Bool())
  val rangeCursorBid = Output(Vec(2, UInt(4.W)))
  val nextStoreId = Output(Vec(2, UInt(8.W)))
  val advanceCount = Output(Vec(2, UInt(4.W)))
  val blockedValid = Output(Vec(2, Bool()))
  val blockedBid = Output(Vec(2, UInt(4.W)))
  val queryHit = Output(Bool())
  val queryCountKnown = Output(Bool())
  val queryStoreCount = Output(UInt(8.W))
  val queryStartValid = Output(Bool())
  val queryStartStoreId = Output(UInt(8.W))
}

class BrobStoreRangeStateProbe extends Module {
  val io = IO(new BrobStoreRangeStateProbeIO)
  val ranges = Module(new BrobStoreRangeState(
    entries = 8,
    bidWidth = 4,
    stidWidth = 2,
    stidCount = 2,
    storeIdWidth = 8,
    storeCountWidth = 8))

  ranges.io.allocValid := io.allocValid
  ranges.io.allocBid := io.allocBid
  ranges.io.allocStid := io.allocStid
  ranges.io.storeObservedValid := io.storeValid
  ranges.io.storeObservedBid := io.storeBid
  ranges.io.storeObservedStid := io.storeStid
  ranges.io.countCertainValid := io.certainValid
  ranges.io.countCertainBid := io.certainBid
  ranges.io.countCertainStid := io.certainStid
  ranges.io.countCertainUseValue := io.certainUseValue
  ranges.io.countCertainValue := io.certainValue
  ranges.io.retireValid := io.retireValid
  ranges.io.retireBid := io.retireBid
  ranges.io.retireStid := io.retireStid
  ranges.io.recoveryValid := io.recoveryValid
  ranges.io.recoveryStid := io.recoveryStid
  ranges.io.recoveryFirstKilledBid := io.recoveryFirstKilledBid
  ranges.io.orderHeadBid := io.orderHeadBid
  ranges.io.orderLiveCount := io.orderLiveCount
  ranges.io.queryBid := io.queryBid
  ranges.io.queryStid := io.queryStid

  io.allocReady := ranges.io.allocReady
  io.allocAccepted := ranges.io.allocAccepted
  io.storeAccepted := ranges.io.storeObservedAccepted
  io.certainAccepted := ranges.io.countCertainAccepted
  io.retireAccepted := ranges.io.retireAccepted
  io.recoveryRewound := ranges.io.recoveryRewound
  io.recoveryMissingStart := ranges.io.recoveryMissingStart
  io.rangeCursorBid := ranges.io.rangeCursorBid
  io.nextStoreId := ranges.io.nextStoreId
  io.advanceCount := ranges.io.advanceCount
  io.blockedValid := ranges.io.blockedValid
  io.blockedBid := ranges.io.blockedBid
  io.queryHit := ranges.io.queryHit
  io.queryCountKnown := ranges.io.query.countKnown
  io.queryStoreCount := ranges.io.query.storeCount
  io.queryStartValid := ranges.io.query.startValid
  io.queryStartStoreId := ranges.io.query.startStoreId
}

object EmitBrobStoreRangeStateProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new BrobStoreRangeStateProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
