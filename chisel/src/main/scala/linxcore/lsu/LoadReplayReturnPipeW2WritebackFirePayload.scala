package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.DestinationKind
import linxcore.rob.ROBID

class LoadReplayReturnPipeW2WritebackFirePayloadIO(
    val idEntries: Int = 16,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val enable = Input(Bool())
  val flush = Input(Bool())
  val writebackFire = Input(Bool())
  val writebackPayloadValid = Input(Bool())
  val writebackTargetValid = Input(Bool())
  val writebackIdentityValid = Input(Bool())
  val writebackDestinationValid = Input(Bool())
  val writebackGprDestination = Input(Bool())
  val writebackTargetIsAgu = Input(Bool())
  val writebackTargetIsLda = Input(Bool())
  val writebackTargetPipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val writebackBid = Input(new ROBID(idEntries))
  val writebackGid = Input(new ROBID(idEntries))
  val writebackRid = Input(new ROBID(idEntries))
  val writebackLoadLsId = Input(new ROBID(idEntries))
  val writebackPc = Input(UInt(pcWidth.W))
  val writebackKind = Input(DestinationKind())
  val writebackArchTag = Input(UInt(archRegWidth.W))
  val writebackRelTag = Input(UInt(archRegWidth.W))
  val writebackPhysTag = Input(UInt(physRegWidth.W))
  val writebackOldPhysTag = Input(UInt(physRegWidth.W))
  val writebackData = Input(UInt(dataWidth.W))

  val candidateValid = Output(Bool())
  val payloadValid = Output(Bool())
  val targetValid = Output(Bool())
  val identityValid = Output(Bool())
  val destinationValid = Output(Bool())
  val gprDestination = Output(Bool())
  val fireValid = Output(Bool())
  val targetIsAgu = Output(Bool())
  val targetIsLda = Output(Bool())
  val targetPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val fireBid = Output(new ROBID(idEntries))
  val fireGid = Output(new ROBID(idEntries))
  val fireRid = Output(new ROBID(idEntries))
  val fireLoadLsId = Output(new ROBID(idEntries))
  val firePc = Output(UInt(pcWidth.W))
  val fireKind = Output(DestinationKind())
  val fireArchTag = Output(UInt(archRegWidth.W))
  val fireRelTag = Output(UInt(archRegWidth.W))
  val firePhysTag = Output(UInt(physRegWidth.W))
  val fireOldPhysTag = Output(UInt(physRegWidth.W))
  val fireData = Output(UInt(dataWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoFire = Output(Bool())
  val blockedByNoPayload = Output(Bool())
  val blockedByInvalidTarget = Output(Bool())
  val blockedByInvalidIdentity = Output(Bool())
  val blockedByNoDestination = Output(Bool())
  val blockedByNonGprDestination = Output(Bool())
  val invalidFireWithoutPayload = Output(Bool())
  val invalidPayloadWithoutFire = Output(Bool())
}

class LoadReplayReturnPipeW2WritebackFirePayload(
    val idEntries: Int = 16,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(pcWidth > 0, "pcWidth must be positive")
  require(dataWidth > 0, "dataWidth must be positive")
  require(returnPipeCount > 0, "returnPipeCount must be positive")
  require(archRegWidth > 0, "archRegWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val io = IO(new LoadReplayReturnPipeW2WritebackFirePayloadIO(
    idEntries,
    pcWidth,
    dataWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  val active = io.enable && !io.flush
  val candidateValid = active && io.writebackFire
  val payloadValid = active && io.writebackPayloadValid
  val destinationShapeValid = io.writebackDestinationValid && io.writebackGprDestination
  val fireValid = candidateValid && io.writebackPayloadValid && io.writebackTargetValid &&
    io.writebackIdentityValid && destinationShapeValid

  io.candidateValid := candidateValid
  io.payloadValid := payloadValid
  io.targetValid := candidateValid && io.writebackTargetValid
  io.identityValid := candidateValid && io.writebackTargetValid && io.writebackIdentityValid
  io.destinationValid := candidateValid && io.writebackTargetValid &&
    io.writebackIdentityValid && io.writebackDestinationValid
  io.gprDestination := candidateValid && io.writebackTargetValid &&
    io.writebackIdentityValid && destinationShapeValid
  io.fireValid := fireValid
  io.targetIsAgu := fireValid && io.writebackTargetIsAgu
  io.targetIsLda := fireValid && io.writebackTargetIsLda
  io.targetPipeIndex := Mux(fireValid, io.writebackTargetPipeIndex, 0.U(returnPipeIndexWidth.W))
  io.fireBid := ROBID.disabled(idEntries)
  io.fireGid := ROBID.disabled(idEntries)
  io.fireRid := ROBID.disabled(idEntries)
  io.fireLoadLsId := ROBID.disabled(idEntries)
  io.firePc := 0.U
  io.fireKind := DestinationKind.None
  io.fireArchTag := 0.U
  io.fireRelTag := 0.U
  io.firePhysTag := 0.U
  io.fireOldPhysTag := 0.U
  io.fireData := 0.U

  when(fireValid) {
    io.fireBid := io.writebackBid
    io.fireGid := io.writebackGid
    io.fireRid := io.writebackRid
    io.fireLoadLsId := io.writebackLoadLsId
    io.firePc := io.writebackPc
    io.fireKind := io.writebackKind
    io.fireArchTag := io.writebackArchTag
    io.fireRelTag := io.writebackRelTag
    io.firePhysTag := io.writebackPhysTag
    io.fireOldPhysTag := io.writebackOldPhysTag
    io.fireData := io.writebackData
  }

  io.blockedByDisabled := !io.enable && io.writebackFire
  io.blockedByFlush := io.enable && io.flush && io.writebackFire
  io.blockedByNoFire := active && io.writebackPayloadValid && !io.writebackFire
  io.blockedByNoPayload := candidateValid && !io.writebackPayloadValid
  io.blockedByInvalidTarget := candidateValid && !io.writebackTargetValid
  io.blockedByInvalidIdentity := candidateValid && io.writebackTargetValid && !io.writebackIdentityValid
  io.blockedByNoDestination := candidateValid && io.writebackTargetValid &&
    io.writebackIdentityValid && !io.writebackDestinationValid
  io.blockedByNonGprDestination := candidateValid && io.writebackTargetValid &&
    io.writebackIdentityValid && io.writebackDestinationValid && !io.writebackGprDestination
  io.invalidFireWithoutPayload := io.blockedByNoPayload
  io.invalidPayloadWithoutFire := active && !io.writebackFire && io.writebackPayloadValid
}
