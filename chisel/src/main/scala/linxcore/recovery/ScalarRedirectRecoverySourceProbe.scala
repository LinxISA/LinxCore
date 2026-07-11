package linxcore.recovery

import chisel3._

import linxcore.rob.ROBID

class ScalarRedirectRecoverySourceProbeIO extends Bundle {
  val eventValid = Input(Bool())
  val blockBidValid = Input(Bool())
  val blockBid = Input(UInt(16.W))
  val bid = Input(new ROBID(8))
  val rid = Input(new ROBID(8))
  val lsId = Input(new ROBID(8))
  val resolveLsIdValid = Input(Bool())
  val orderValid = Input(Bool())
  val order = Input(UInt(12.W))
  val sourceReady = Input(Bool())
  val intentConsumed = Input(Bool())
  val cancel = Input(Bool())

  val eventReady = Output(Bool())
  val eventAccepted = Output(Bool())
  val sourceValid = Output(Bool())
  val sourceAccepted = Output(Bool())
  val sourceBlockBid = Output(UInt(16.W))
  val sourceRid = Output(new ROBID(8))
  val pending = Output(Bool())
  val published = Output(Bool())
  val blockedByMissingIdentity = Output(Bool())
  val cleanupOrderValid = Output(Bool())
  val cleanupOrder = Output(UInt(12.W))
  val cleanupResolveLsIdValid = Output(Bool())
  val cleanupLsId = Output(new ROBID(8))
}

/** Generated-RTL proof surface for retained scalar redirect recovery. */
class ScalarRedirectRecoverySourceProbe extends Module {
  val io = IO(new ScalarRedirectRecoverySourceProbeIO)
  val source = Module(new ScalarRedirectRecoverySource(
    entries = 8,
    bidWidth = 16,
    peIdWidth = 1,
    stidWidth = 1,
    tidWidth = 1,
    orderWidth = 12
  ))

  source.io.event.valid := io.eventValid
  source.io.event.blockBidValid := io.blockBidValid
  source.io.event.blockBid := io.blockBid
  source.io.event.bid := io.bid
  source.io.event.rid := io.rid
  source.io.event.lsId := io.lsId
  source.io.event.resolveLsIdValid := io.resolveLsIdValid
  source.io.event.stid := 0.U
  source.io.event.peId := 0.U
  source.io.event.tid := 0.U
  source.io.event.orderValid := io.orderValid
  source.io.event.order := io.order
  source.io.sourceReady := io.sourceReady
  source.io.intentConsumed := io.intentConsumed
  source.io.cancel := io.cancel

  io.eventReady := source.io.eventReady
  io.eventAccepted := source.io.eventAccepted
  io.sourceValid := source.io.source.valid
  io.sourceAccepted := source.io.sourceAccepted
  io.sourceBlockBid := source.io.source.blockBid
  io.sourceRid := source.io.source.rid
  io.pending := source.io.pending
  io.published := source.io.published
  io.blockedByMissingIdentity := source.io.blockedByMissingIdentity
  io.cleanupOrderValid := source.io.cleanupOrderValid
  io.cleanupOrder := source.io.cleanupOrder
  io.cleanupResolveLsIdValid := source.io.cleanupResolveLsIdValid
  io.cleanupLsId := source.io.cleanupLsId
}

object EmitScalarRedirectRecoverySourceProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new ScalarRedirectRecoverySourceProbe,
    args = Array("--target-dir", "../generated/chisel-verilog/scalar-redirect-recovery-source-probe"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
