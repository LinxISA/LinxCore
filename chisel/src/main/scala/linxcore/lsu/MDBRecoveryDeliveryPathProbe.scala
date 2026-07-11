package linxcore.lsu

import chisel3._
import linxcore.rob.ROBID

class MDBRecoveryDeliveryPathProbeIO extends Bundle {
  val flush = Input(Bool())
  val candidateValid = Input(Bool())
  val conflictValid = Input(Bool())
  val recordReady = Input(Bool())
  val nukeFlush = Input(Bool())
  val stid = Input(UInt(2.W))
  val bid = Input(UInt(3.W))
  val rid = Input(UInt(3.W))
  val oldestValidMask = Input(UInt(2.W))
  val oldestBid0 = Input(UInt(3.W))
  val oldestRid0 = Input(UInt(3.W))
  val oldestBid1 = Input(UInt(3.W))
  val oldestRid1 = Input(UInt(3.W))
  val lookupMatch = Input(Bool())
  val lookupBlockBid = Input(UInt(16.W))
  val sourceReady = Input(Bool())
  val replayLiveValid = Input(Bool())
  val replayEnable = Input(Bool())
  val replayConsume = Input(Bool())
  val replayPc = Input(UInt(64.W))

  val candidateAccepted = Output(Bool())
  val recordValid = Output(Bool())
  val recoveryPending = Output(Bool())
  val recoveryCount = Output(UInt(2.W))
  val recoveryStidInRange = Output(Bool())
  val lookupValid = Output(Bool())
  val lookupStid = Output(UInt(2.W))
  val lookupBid = Output(UInt(3.W))
  val lookupRid = Output(UInt(3.W))
  val sourceValid = Output(Bool())
  val sourceStid = Output(UInt(2.W))
  val sourceBlockBid = Output(UInt(16.W))
  val sourceAccepted = Output(Bool())
  val replayLiveSelected = Output(Bool())
  val replaySelected = Output(Bool())
  val replayRetainedReplayed = Output(Bool())
  val replayRetainedNeedsRetry = Output(Bool())
}

class MDBRecoveryDeliveryPathProbe extends Module {
  private val entries = 8
  val io = IO(new MDBRecoveryDeliveryPathProbeIO)
  val delivery = Module(new MDBRecoveryDeliveryPath(
    entries = entries,
    recoveryQueueEntries = 2,
    stidCount = 2,
    bidWidth = 16,
    stidWidth = 2,
    tidWidth = 2
  ))

  private def id(value: UInt): ROBID = {
    val out = Wire(new ROBID(entries))
    out.valid := true.B
    out.wrap := false.B
    out.value := value
    out
  }

  val record = Wire(chiselTypeOf(delivery.io.record))
  record := 0.U.asTypeOf(record)
  record.load.valid := io.conflictValid
  record.load.peId := 0.U
  record.load.stid := io.stid
  record.load.tid := 0.U
  record.load.bid := id(io.bid)
  record.load.gid := id(0.U)
  record.load.rid := id(io.rid)
  record.load.lsId := id(io.rid)
  record.load.pc := 0x1000.U

  delivery.io.enable := true.B
  delivery.io.flush := io.flush
  delivery.io.candidateValid := io.candidateValid
  delivery.io.conflictValid := io.conflictValid
  delivery.io.nukeFlush := io.nukeFlush
  delivery.io.record := record
  delivery.io.recordReady := io.recordReady
  delivery.io.oldestValid := io.oldestValidMask.asBools
  delivery.io.oldestBid(0) := id(io.oldestBid0)
  delivery.io.oldestRid(0) := id(io.oldestRid0)
  delivery.io.oldestBid(1) := id(io.oldestBid1)
  delivery.io.oldestRid(1) := id(io.oldestRid1)

  val lookup = Wire(chiselTypeOf(delivery.io.fullBidLookup))
  lookup := 0.U.asTypeOf(lookup)
  lookup.request := delivery.io.fullBidLookupRequest
  lookup.matched := io.lookupMatch
  lookup.blockBidValid := io.lookupMatch
  lookup.blockBid := io.lookupBlockBid
  delivery.io.fullBidLookup := lookup
  delivery.io.sourceReady := io.sourceReady

  io.candidateAccepted := delivery.io.candidateAccepted
  io.recordValid := delivery.io.recordValid
  io.recoveryPending := delivery.io.recoveryPending
  io.recoveryCount := delivery.io.recoveryCount
  io.recoveryStidInRange := delivery.io.recoveryStidInRange
  io.lookupValid := delivery.io.fullBidLookupRequest.valid
  io.lookupStid := delivery.io.fullBidLookupRequest.stid
  io.lookupBid := delivery.io.fullBidLookupRequest.bid.value
  io.lookupRid := delivery.io.fullBidLookupRequest.rid.value
  io.sourceValid := delivery.io.source.valid
  io.sourceStid := delivery.io.source.stid
  io.sourceBlockBid := delivery.io.source.blockBid
  io.sourceAccepted := delivery.io.sourceAccepted

  val replay = Module(new MDBStoreProbeReplay(entries = entries))
  val replayLive = Wire(chiselTypeOf(replay.io.live))
  replayLive := 0.U.asTypeOf(replayLive)
  replayLive.valid := io.replayLiveValid
  replayLive.pc := io.replayPc
  replay.io.flush := io.flush
  replay.io.live := replayLive
  replay.io.replayEnable := io.replayEnable
  replay.io.replayConsume := io.replayConsume
  io.replayLiveSelected := replay.io.liveSelected
  io.replaySelected := replay.io.replaySelected
  io.replayRetainedReplayed := replay.io.retainedReplayed
  io.replayRetainedNeedsRetry := replay.io.retainedNeedsRetry
}

object EmitMDBRecoveryDeliveryPathProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new MDBRecoveryDeliveryPathProbe,
    args = Array("--target-dir", "../generated/chisel-verilog/mdb-recovery-delivery-path-probe"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
