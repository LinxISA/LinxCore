package linxcore.bctrl

import chisel3._
import circt.stage.ChiselStage

class BrobOrderStateProbeIO extends Bundle {
  val allocValid = Input(Bool())
  val allocBid = Input(UInt(4.W))
  val allocStid = Input(UInt(2.W))
  val scalarDoneValid = Input(Bool())
  val scalarDoneBid = Input(UInt(4.W))
  val scalarDoneStid = Input(UInt(2.W))
  val scalarTrapValid = Input(Bool())
  val retireReady = Input(Bool())
  val recoveryValid = Input(Bool())
  val recoveryStid = Input(UInt(2.W))
  val recoveryPivotBid = Input(UInt(3.W))
  val recoveryTransportPointerValid = Input(Bool())
  val recoveryTransportPointer = Input(UInt(4.W))
  val recoveryInclusive = Input(Bool())

  val allocApplied = Output(Bool())
  val allocIdentityMatch = Output(Bool())
  val allocCursor = Output(Vec(2, UInt(4.W)))
  val commitCursor = Output(Vec(2, UInt(4.W)))
  val liveCount = Output(Vec(2, UInt(4.W)))
  val oldestValid = Output(Vec(2, Bool()))
  val oldestComplete = Output(Vec(2, Bool()))
  val headMismatch = Output(Vec(2, Bool()))
  val retireValid = Output(Bool())
  val retireFire = Output(Bool())
  val retireBid = Output(UInt(4.W))
  val retireStid = Output(UInt(2.W))
  val retireMetadataAccepted = Output(Bool())
  val recoveryWindowValid = Output(Bool())
  val recoveryCanonicalMatch = Output(Bool())
  val recoveryResolvedPivotBid = Output(UInt(4.W))
  val recoveryLegacyPointerMismatch = Output(Bool())
  val recoveryApplied = Output(Bool())
  val recoveryFirstKilledBid = Output(UInt(4.W))
  val recoveryRetainedCount = Output(UInt(4.W))
  val nonFlushValid = Output(Vec(2, Bool()))
  val nonFlushHeadBid = Output(Vec(2, UInt(4.W)))
  val nonFlushFrontierBid = Output(Vec(2, UInt(4.W)))
  val nonFlushPrefixCount = Output(Vec(2, UInt(4.W)))
  val nonFlushBlockedValid = Output(Vec(2, Bool()))
  val nonFlushBlockedBid = Output(Vec(2, UInt(4.W)))
}

class BrobOrderStateProbe extends Module {
  val io = IO(new BrobOrderStateProbeIO)
  val order = Module(new BrobOrderState(entries = 8, bidWidth = 4, stidWidth = 2, stidCount = 2))
  val meta = Module(new BrobMetaTracker(entries = 8, bidWidth = 4, stidWidth = 2, stidCount = 2))

  order.io.allocValid := io.allocValid && meta.io.allocReady
  order.io.allocBid := io.allocBid
  order.io.allocStid := io.allocStid
  order.io.recoveryValid := io.recoveryValid
  order.io.recoveryStid := io.recoveryStid
  order.io.recoveryPivotBid := io.recoveryPivotBid
  order.io.recoveryTransportPointerValid := io.recoveryTransportPointerValid
  order.io.recoveryTransportPointer := io.recoveryTransportPointer
  order.io.recoveryInclusive := io.recoveryInclusive
  order.io.headResident := meta.io.oldestValid
  order.io.headComplete := meta.io.oldestComplete
  order.io.retireReady := io.retireReady

  meta.io.allocValid := order.io.allocApplied
  meta.io.allocBid := io.allocBid
  meta.io.allocStid := io.allocStid
  meta.io.allocTid := 0.U
  meta.io.allocPeId := 0.U
  meta.io.allocBlockType := 0.U
  meta.io.allocNeedsEngine := false.B
  meta.io.scalarDoneValid := io.scalarDoneValid
  meta.io.scalarDoneBid := io.scalarDoneBid
  meta.io.scalarDoneStid := io.scalarDoneStid
  meta.io.scalarTrapValid := io.scalarTrapValid
  meta.io.scalarTrapCause := 0.U
  meta.io.engineDoneValid := false.B
  meta.io.engineDoneBid := 0.U
  meta.io.engineDoneStid := 0.U
  meta.io.engineTrapValid := false.B
  meta.io.engineTrapCause := 0.U
  meta.io.retireValid := order.io.retireFire
  meta.io.retireBid := order.io.retireBid
  meta.io.retireStid := order.io.retireStid
  meta.io.flushValid := order.io.recoveryApplied
  meta.io.flushBid := order.io.recoveryResolvedPivotBid
  meta.io.flushStid := io.recoveryStid
  meta.io.flushInclusive := io.recoveryInclusive
  meta.io.queryBid := 0.U
  meta.io.queryStid := 0.U
  meta.io.orderHeadValid := order.io.headValid
  meta.io.orderHeadBid := order.io.commitCursor
  meta.io.orderLiveCount := order.io.liveCount

  io.allocApplied := order.io.allocApplied
  io.allocIdentityMatch := order.io.allocIdentityMatch
  io.allocCursor := order.io.allocCursor
  io.commitCursor := order.io.commitCursor
  io.liveCount := order.io.liveCount
  io.oldestValid := meta.io.oldestValid
  io.oldestComplete := meta.io.oldestComplete
  io.headMismatch := order.io.headMismatch
  io.retireValid := order.io.retireValid
  io.retireFire := order.io.retireFire
  io.retireBid := order.io.retireBid
  io.retireStid := order.io.retireStid
  io.retireMetadataAccepted := meta.io.retireAccepted
  io.recoveryWindowValid := order.io.recoveryWindowValid
  io.recoveryCanonicalMatch := order.io.recoveryCanonicalMatch
  io.recoveryResolvedPivotBid := order.io.recoveryResolvedPivotBid
  io.recoveryLegacyPointerMismatch := order.io.recoveryLegacyPointerMismatch
  io.recoveryApplied := order.io.recoveryApplied
  io.recoveryFirstKilledBid := order.io.recoveryFirstKilledBid
  io.recoveryRetainedCount := order.io.recoveryRetainedCount
  io.nonFlushValid := meta.io.nonFlushValid
  io.nonFlushHeadBid := meta.io.nonFlushHeadBid
  io.nonFlushFrontierBid := meta.io.nonFlushFrontierBid
  io.nonFlushPrefixCount := meta.io.nonFlushPrefixCount
  io.nonFlushBlockedValid := meta.io.nonFlushBlockedValid
  io.nonFlushBlockedBid := meta.io.nonFlushBlockedBid
}

object EmitBrobOrderStateProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new BrobOrderStateProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info")
  )
}
