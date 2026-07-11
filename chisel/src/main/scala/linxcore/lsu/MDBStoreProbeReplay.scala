package linxcore.lsu

import chisel3._

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
  val replayEnable = Input(Bool())
  val replayConsume = Input(Bool())

  val out = Output(new MDBConflictStoreProbe(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth))
  val liveSelected = Output(Bool())
  val replaySelected = Output(Bool())
  val retainedValid = Output(Bool())
  val retainedReplayed = Output(Bool())
  val retainedNeedsRetry = Output(Bool())
  val replayAccepted = Output(Bool())
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

  val retained = RegInit(0.U.asTypeOf(new MDBConflictStoreProbe(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth)))
  val retainedValid = RegInit(false.B)
  val retainedReplayed = RegInit(false.B)
  val retainedNeedsRetry = RegInit(false.B)

  val replayEligible =
    retainedValid && !retainedReplayed && (retainedNeedsRetry || io.replayEnable)
  val selectLive = io.live.valid
  val selectReplay = !selectLive && replayEligible

  io.out := Mux(selectLive, io.live, Mux(selectReplay, retained, zeroProbe))
  io.out.valid := selectLive || selectReplay
  io.liveSelected := selectLive
  io.replaySelected := selectReplay
  io.retainedValid := retainedValid
  io.retainedReplayed := retainedReplayed
  io.retainedNeedsRetry := retainedNeedsRetry
  io.replayAccepted := selectReplay && io.replayConsume

  when(io.flush) {
    retained := 0.U.asTypeOf(retained)
    retainedValid := false.B
    retainedReplayed := false.B
    retainedNeedsRetry := false.B
  }.elsewhen(io.live.valid) {
    retained := io.live
    retainedValid := true.B
    retainedReplayed := io.replayConsume && io.replayEnable
    retainedNeedsRetry := !io.replayConsume
  }.elsewhen(io.replayAccepted) {
    retainedNeedsRetry := false.B
    retainedReplayed := !retainedNeedsRetry || io.replayEnable
  }
}
