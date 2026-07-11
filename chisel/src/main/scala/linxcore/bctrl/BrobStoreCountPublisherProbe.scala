package linxcore.bctrl

import chisel3._
import circt.stage.ChiselStage

class BrobStoreCountPublisherProbeIO extends Bundle {
  val allocValid = Input(Bool())
  val allocBid = Input(UInt(4.W))
  val allocStid = Input(UInt(2.W))
  val storeValid = Input(Bool())
  val storeBid = Input(UInt(4.W))
  val storeStid = Input(UInt(2.W))
  val scalarValid = Input(Bool())
  val scalarBid = Input(UInt(4.W))
  val scalarStid = Input(UInt(2.W))
  val explicitValid = Input(Bool())
  val explicitReady = Output(Bool())
  val explicitBid = Input(UInt(4.W))
  val explicitStid = Input(UInt(2.W))
  val explicitValue = Input(UInt(8.W))
  val recoveryValid = Input(Bool())
  val recoveryStid = Input(UInt(2.W))
  val recoveryFirstKilledBid = Input(UInt(4.W))
  val orderHeadBid = Input(Vec(2, UInt(4.W)))
  val orderLiveCount = Input(Vec(2, UInt(4.W)))
  val queryBid = Input(UInt(4.W))
  val queryStid = Input(UInt(2.W))

  val allocAccepted = Output(Bool())
  val storeAccepted = Output(Bool())
  val scalarInputAccepted = Output(Bool())
  val scalarInputCanceled = Output(Bool())
  val explicitInputAccepted = Output(Bool())
  val explicitInputCanceled = Output(Bool())
  val explicitPendingCanceled = Output(Bool())
  val explicitBlockedByLiveWindow = Output(Bool())
  val publishValid = Output(Bool())
  val publishBid = Output(UInt(4.W))
  val publishUseValue = Output(Bool())
  val scalarPublishFire = Output(Bool())
  val explicitPublishFire = Output(Bool())
  val scalarRedundantWithExplicit = Output(Bool())
  val sameBlockCollision = Output(Bool())
  val differentBlockCollision = Output(Bool())
  val scalarPending = Output(Bool())
  val explicitPending = Output(Bool())
  val countConflict = Output(Bool())
  val queryHit = Output(Bool())
  val queryCountKnown = Output(Bool())
  val queryStoreCount = Output(UInt(8.W))
  val headCountKnown = Output(Vec(2, Bool()))
}

class BrobStoreCountPublisherProbe extends Module {
  val io = IO(new BrobStoreCountPublisherProbeIO)
  val publisher = Module(new BrobStoreCountPublisher(
    entries = 8,
    bidWidth = 4,
    stidWidth = 2,
    stidCount = 2,
    storeCountWidth = 8))
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
  ranges.io.retireValid := false.B
  ranges.io.retireBid := 0.U
  ranges.io.retireStid := 0.U
  ranges.io.recoveryValid := io.recoveryValid
  ranges.io.recoveryStid := io.recoveryStid
  ranges.io.recoveryFirstKilledBid := io.recoveryFirstKilledBid
  ranges.io.orderHeadBid := io.orderHeadBid
  ranges.io.orderLiveCount := io.orderLiveCount
  ranges.io.queryBid := io.queryBid
  ranges.io.queryStid := io.queryStid

  publisher.io.scalarValid := io.scalarValid
  publisher.io.scalarBid := io.scalarBid
  publisher.io.scalarStid := io.scalarStid
  publisher.io.explicitValid := io.explicitValid
  publisher.io.explicitBid := io.explicitBid
  publisher.io.explicitStid := io.explicitStid
  publisher.io.explicitValue := io.explicitValue
  publisher.io.orderHeadBid := io.orderHeadBid
  publisher.io.orderLiveCount := io.orderLiveCount
  publisher.io.recoveryValid := io.recoveryValid
  publisher.io.recoveryStid := io.recoveryStid
  publisher.io.recoveryFirstKilledBid := io.recoveryFirstKilledBid
  ranges.io.countCertainValid := publisher.io.publishValid
  ranges.io.countCertainBid := publisher.io.publishBid
  ranges.io.countCertainStid := publisher.io.publishStid
  ranges.io.countCertainUseValue := publisher.io.publishUseValue
  ranges.io.countCertainValue := publisher.io.publishValue
  publisher.io.sinkAccepted := ranges.io.countCertainAccepted
  publisher.io.sinkDuplicateMatch := ranges.io.countCertainDuplicateMatch
  publisher.io.sinkConflict := ranges.io.countCertainConflict

  io.allocAccepted := ranges.io.allocAccepted
  io.storeAccepted := ranges.io.storeObservedAccepted
  io.explicitReady := publisher.io.explicitReady
  io.scalarInputAccepted := publisher.io.scalarInputAccepted
  io.scalarInputCanceled := publisher.io.scalarInputCanceled
  io.explicitInputAccepted := publisher.io.explicitInputAccepted
  io.explicitInputCanceled := publisher.io.explicitInputCanceled
  io.explicitPendingCanceled := publisher.io.explicitPendingCanceled
  io.explicitBlockedByLiveWindow := publisher.io.explicitBlockedByLiveWindow
  io.publishValid := publisher.io.publishValid
  io.publishBid := publisher.io.publishBid
  io.publishUseValue := publisher.io.publishUseValue
  io.scalarPublishFire := publisher.io.scalarPublishFire
  io.explicitPublishFire := publisher.io.explicitPublishFire
  io.scalarRedundantWithExplicit := publisher.io.scalarRedundantWithExplicit
  io.sameBlockCollision := publisher.io.sameBlockCollision
  io.differentBlockCollision := publisher.io.differentBlockCollision
  io.scalarPending := publisher.io.scalarPending
  io.explicitPending := publisher.io.explicitPending
  io.countConflict := ranges.io.countCertainConflict
  io.queryHit := ranges.io.queryHit
  io.queryCountKnown := ranges.io.query.countKnown
  io.queryStoreCount := ranges.io.query.storeCount
  io.headCountKnown := ranges.io.headCountKnown
}

object EmitBrobStoreCountPublisherProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new BrobStoreCountPublisherProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
