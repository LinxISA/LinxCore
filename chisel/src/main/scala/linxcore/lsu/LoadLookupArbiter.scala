package linxcore.lsu

import chisel3._

class LoadLookupArbiterIO(
    val addrWidth: Int = 64,
    val pcWidth: Int = 64)
    extends Bundle {
  val executeValid = Input(Bool())
  val executeAddr = Input(UInt(addrWidth.W))
  val executePc = Input(UInt(pcWidth.W))

  val replayValid = Input(Bool())
  val replayAddr = Input(UInt(addrWidth.W))
  val replayPc = Input(UInt(pcWidth.W))

  val lookupValid = Output(Bool())
  val lookupAddr = Output(UInt(addrWidth.W))
  val lookupPc = Output(UInt(pcWidth.W))

  val executeGranted = Output(Bool())
  val replayGranted = Output(Bool())
  val replayBlockedByExecute = Output(Bool())
  val idle = Output(Bool())
}

class LoadLookupArbiter(
    val addrWidth: Int = 64,
    val pcWidth: Int = 64)
    extends Module {
  require(addrWidth > 0, "load lookup arbiter requires a positive address width")
  require(pcWidth > 0, "load lookup arbiter requires a positive PC width")

  val io = IO(new LoadLookupArbiterIO(addrWidth, pcWidth))

  val executeGrant = io.executeValid
  val replayGrant = !io.executeValid && io.replayValid

  io.lookupValid := executeGrant || replayGrant
  io.lookupAddr := Mux(executeGrant, io.executeAddr, Mux(replayGrant, io.replayAddr, 0.U))
  io.lookupPc := Mux(executeGrant, io.executePc, Mux(replayGrant, io.replayPc, 0.U))

  io.executeGranted := executeGrant
  io.replayGranted := replayGrant
  io.replayBlockedByExecute := io.replayValid && io.executeValid
  io.idle := !io.executeValid && !io.replayValid
}
