package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplayReturnPipeW2ResolveFirePayloadIO(
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
  val resolveFire = Input(Bool())
  val resolvePayloadValid = Input(Bool())
  val resolveComplete = Input(Bool())
  val resolveTargetIsAgu = Input(Bool())
  val resolveTargetIsLda = Input(Bool())
  val resolveTargetPipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val resolveBid = Input(new ROBID(idEntries))
  val resolveGid = Input(new ROBID(idEntries))
  val resolveRid = Input(new ROBID(idEntries))
  val resolveLoadLsId = Input(new ROBID(idEntries))
  val resolvePc = Input(UInt(pcWidth.W))
  val resolveAddr = Input(UInt(addrWidth.W))
  val resolveSize = Input(UInt(sizeWidth.W))
  val resolveDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))
  val resolveData = Input(UInt(dataWidth.W))

  val candidateValid = Output(Bool())
  val payloadValid = Output(Bool())
  val targetValid = Output(Bool())
  val identityValid = Output(Bool())
  val fireValid = Output(Bool())
  val isComplete = Output(Bool())
  val targetIsAgu = Output(Bool())
  val targetIsLda = Output(Bool())
  val targetPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val fireBid = Output(new ROBID(idEntries))
  val fireGid = Output(new ROBID(idEntries))
  val fireRid = Output(new ROBID(idEntries))
  val fireLoadLsId = Output(new ROBID(idEntries))
  val firePc = Output(UInt(pcWidth.W))
  val fireAddr = Output(UInt(addrWidth.W))
  val fireSize = Output(UInt(sizeWidth.W))
  val fireDst = Output(new LoadReplayDestination(archRegWidth, physRegWidth))
  val fireData = Output(UInt(dataWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoFire = Output(Bool())
  val blockedByNoPayload = Output(Bool())
  val blockedByIncomplete = Output(Bool())
  val blockedByInvalidTarget = Output(Bool())
  val blockedByInvalidBid = Output(Bool())
  val blockedByInvalidGid = Output(Bool())
  val blockedByInvalidRid = Output(Bool())
  val blockedByInvalidIdentity = Output(Bool())
  val invalidFireWithoutPayload = Output(Bool())
  val invalidPayloadWithoutFire = Output(Bool())
}

class LoadReplayReturnPipeW2ResolveFirePayload(
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

  val io = IO(new LoadReplayReturnPipeW2ResolveFirePayloadIO(
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
  val candidateValid = active && io.resolveFire
  val payloadValid = active && io.resolvePayloadValid
  val targetValid = io.resolveTargetIsAgu ^ io.resolveTargetIsLda
  val identityValid = io.resolveBid.valid && io.resolveGid.valid && io.resolveRid.valid
  val fireValid =
    candidateValid && io.resolvePayloadValid && io.resolveComplete && targetValid && identityValid

  io.candidateValid := candidateValid
  io.payloadValid := payloadValid
  io.targetValid := candidateValid && io.resolvePayloadValid && targetValid
  io.identityValid := candidateValid && io.resolvePayloadValid && targetValid && identityValid
  io.fireValid := fireValid
  io.isComplete := fireValid
  io.targetIsAgu := fireValid && io.resolveTargetIsAgu
  io.targetIsLda := fireValid && io.resolveTargetIsLda
  io.targetPipeIndex := Mux(fireValid, io.resolveTargetPipeIndex, 0.U(returnPipeIndexWidth.W))
  io.fireBid := ROBID.disabled(idEntries)
  io.fireGid := ROBID.disabled(idEntries)
  io.fireRid := ROBID.disabled(idEntries)
  io.fireLoadLsId := ROBID.disabled(idEntries)
  io.firePc := 0.U
  io.fireAddr := 0.U
  io.fireSize := 0.U
  io.fireDst := LoadReplayDestination.none(archRegWidth, physRegWidth)
  io.fireData := 0.U

  when(fireValid) {
    io.fireBid := io.resolveBid
    io.fireGid := io.resolveGid
    io.fireRid := io.resolveRid
    io.fireLoadLsId := io.resolveLoadLsId
    io.firePc := io.resolvePc
    io.fireAddr := io.resolveAddr
    io.fireSize := io.resolveSize
    io.fireDst := io.resolveDst
    io.fireData := io.resolveData
  }

  io.blockedByDisabled := !io.enable && io.resolveFire
  io.blockedByFlush := io.enable && io.flush && io.resolveFire
  io.blockedByNoFire := active && io.resolvePayloadValid && !io.resolveFire
  io.blockedByNoPayload := candidateValid && !io.resolvePayloadValid
  io.blockedByIncomplete := candidateValid && io.resolvePayloadValid && !io.resolveComplete
  io.blockedByInvalidTarget := candidateValid && io.resolvePayloadValid && io.resolveComplete && !targetValid
  io.blockedByInvalidBid :=
    candidateValid && io.resolvePayloadValid && io.resolveComplete && targetValid && !io.resolveBid.valid
  io.blockedByInvalidGid :=
    candidateValid && io.resolvePayloadValid && io.resolveComplete && targetValid && !io.resolveGid.valid
  io.blockedByInvalidRid :=
    candidateValid && io.resolvePayloadValid && io.resolveComplete && targetValid && !io.resolveRid.valid
  io.blockedByInvalidIdentity :=
    candidateValid && io.resolvePayloadValid && io.resolveComplete && targetValid && !identityValid
  io.invalidFireWithoutPayload := io.blockedByNoPayload
  io.invalidPayloadWithoutFire := active && !io.resolveFire && io.resolvePayloadValid
}
