package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplayReturnPipeW2ResolveArbiterInputIO(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveEnable = Input(Bool())
  val firePayloadValid = Input(Bool())
  val fireTargetIsAgu = Input(Bool())
  val fireTargetIsLda = Input(Bool())
  val fireTargetPipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val fireBid = Input(new ROBID(idEntries))
  val fireGid = Input(new ROBID(idEntries))
  val fireRid = Input(new ROBID(idEntries))
  val fireLoadLsId = Input(new ROBID(idEntries))
  val firePc = Input(UInt(pcWidth.W))
  val fireAddr = Input(UInt(addrWidth.W))
  val fireSize = Input(UInt(sizeWidth.W))
  val fireDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))
  val fireData = Input(UInt(dataWidth.W))

  val active = Output(Bool())
  val candidateValid = Output(Bool())
  val resolveValid = Output(Bool())
  val targetIsAgu = Output(Bool())
  val targetIsLda = Output(Bool())
  val targetPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val resolveBid = Output(new ROBID(idEntries))
  val resolveGid = Output(new ROBID(idEntries))
  val resolveRid = Output(new ROBID(idEntries))
  val resolveLoadLsId = Output(new ROBID(idEntries))
  val resolvePc = Output(UInt(pcWidth.W))
  val resolveAddr = Output(UInt(addrWidth.W))
  val resolveSize = Output(UInt(sizeWidth.W))
  val resolveDst = Output(new LoadReplayDestination(archRegWidth, physRegWidth))
  val resolveData = Output(UInt(dataWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoPayload = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2ResolveArbiterInput(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(addrWidth > 0, "addrWidth must be positive")
  require(pcWidth > 0, "pcWidth must be positive")
  require(dataWidth > 0, "dataWidth must be positive")
  require(sizeWidth > 0, "sizeWidth must be positive")
  require(returnPipeCount > 0, "returnPipeCount must be positive")
  require(archRegWidth > 0, "archRegWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val io = IO(new LoadReplayReturnPipeW2ResolveArbiterInputIO(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  val active = io.enable && !io.flush
  val candidateValid = active && io.firePayloadValid
  val resolveValid = candidateValid && io.liveEnable

  io.active := active
  io.candidateValid := candidateValid
  io.resolveValid := resolveValid
  io.targetIsAgu := resolveValid && io.fireTargetIsAgu
  io.targetIsLda := resolveValid && io.fireTargetIsLda
  io.targetPipeIndex := Mux(resolveValid, io.fireTargetPipeIndex, 0.U(returnPipeIndexWidth.W))
  io.resolveBid := ROBID.disabled(idEntries)
  io.resolveGid := ROBID.disabled(idEntries)
  io.resolveRid := ROBID.disabled(idEntries)
  io.resolveLoadLsId := ROBID.disabled(idEntries)
  io.resolvePc := 0.U
  io.resolveAddr := 0.U
  io.resolveSize := 0.U
  io.resolveDst := LoadReplayDestination.none(archRegWidth, physRegWidth)
  io.resolveData := 0.U

  when(resolveValid) {
    io.resolveBid := io.fireBid
    io.resolveGid := io.fireGid
    io.resolveRid := io.fireRid
    io.resolveLoadLsId := io.fireLoadLsId
    io.resolvePc := io.firePc
    io.resolveAddr := io.fireAddr
    io.resolveSize := io.fireSize
    io.resolveDst := io.fireDst
    io.resolveData := io.fireData
  }

  io.blockedByDisabled := !io.enable && io.firePayloadValid
  io.blockedByFlush := io.enable && io.flush && io.firePayloadValid
  io.blockedByNoPayload := active && !io.firePayloadValid
  io.blockedByLiveDisabled := candidateValid && !io.liveEnable
}
