package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, Queue}

class MDBStoreProbeReplayIO(
    val entries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 7)
    extends Bundle {
  val flush = Input(Bool())
  val live = Input(new MDBConflictStoreProbe(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth))
  val liveCommit = Input(Bool())
  val retainForReplay = Input(Bool())
  val replayEnable = Input(Bool())
  val replayConsume = Input(Bool())

  val liveReady = Output(Bool())
  val out = Output(new MDBConflictStoreProbe(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth))
  val liveSelected = Output(Bool())
  val replaySelected = Output(Bool())
  val retainedValid = Output(Bool())
  val retainedReplayed = Output(Bool())
  val retainedNeedsRetry = Output(Bool())
  val replayAccepted = Output(Bool())
  val retainedCount = Output(UInt(log2Ceil(entries + 1).W))
}

class MDBStoreProbeReplay(
    val entries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 7)
    extends Module {
  require(entries > 1, "ROB entries must be greater than one")
  require((entries & (entries - 1)) == 0, "ROB entries must be a power of two")
  require(addrWidth > 0, "address width must be nonzero")
  require(sizeWidth > 0, "size width must be nonzero")

  val io = IO(new MDBStoreProbeReplayIO(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth))

  private def zeroProbe: MDBConflictStoreProbe = {
    val probe = Wire(new MDBConflictStoreProbe(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth))
    probe := 0.U.asTypeOf(probe)
    probe
  }

  val retained = withReset(reset.asBool || io.flush) {
    Module(new Queue(
      new MDBConflictStoreProbe(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth),
      entries
    ))
  }
  val retainLive = io.live.valid && io.liveCommit && io.retainForReplay && !io.replayEnable
  retained.io.enq.valid := retainLive
  retained.io.enq.bits := io.live

  val replayEligible = retained.io.deq.valid && io.replayEnable
  val selectLive = io.live.valid
  val selectReplay = !selectLive && replayEligible

  io.out := Mux(selectLive, io.live, Mux(selectReplay, retained.io.deq.bits, zeroProbe))
  io.out.valid := selectLive || selectReplay
  io.liveReady := !io.retainForReplay || io.replayEnable || retained.io.enq.ready
  io.liveSelected := selectLive
  io.replaySelected := selectReplay
  io.retainedValid := retained.io.deq.valid
  io.retainedReplayed := retained.io.deq.fire
  io.retainedNeedsRetry := retained.io.deq.valid && !io.replayEnable
  io.replayAccepted := selectReplay && io.replayConsume
  io.retainedCount := retained.io.count
  retained.io.deq.ready := io.replayAccepted
}
