package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.DestinationKind
import linxcore.rob.ROBID

class LoadReplayReturnPipeW2WakeupFirePayloadIO(
    val idEntries: Int = 16,
    val pcWidth: Int = 64,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val enable = Input(Bool())
  val flush = Input(Bool())
  val wakeupFire = Input(Bool())
  val wakeupPayloadValid = Input(Bool())
  val wakeupTargetValid = Input(Bool())
  val wakeupIdentityValid = Input(Bool())
  val wakeupRequired = Input(Bool())
  val wakeupDestinationValid = Input(Bool())
  val wakeupReducedGpr = Input(Bool())
  val wakeupNonGpr = Input(Bool())
  val wakeupTargetIsAgu = Input(Bool())
  val wakeupTargetIsLda = Input(Bool())
  val wakeupTargetPipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val wakeupBid = Input(new ROBID(idEntries))
  val wakeupGid = Input(new ROBID(idEntries))
  val wakeupRid = Input(new ROBID(idEntries))
  val wakeupLoadLsId = Input(new ROBID(idEntries))
  val wakeupPc = Input(UInt(pcWidth.W))
  val wakeupKind = Input(DestinationKind())
  val wakeupArchTag = Input(UInt(archRegWidth.W))
  val wakeupRelTag = Input(UInt(archRegWidth.W))
  val wakeupPhysTag = Input(UInt(physRegWidth.W))
  val wakeupOldPhysTag = Input(UInt(physRegWidth.W))

  val candidateValid = Output(Bool())
  val payloadValid = Output(Bool())
  val targetValid = Output(Bool())
  val identityValid = Output(Bool())
  val required = Output(Bool())
  val destinationValid = Output(Bool())
  val reducedGprWakeup = Output(Bool())
  val nonGprWakeup = Output(Bool())
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
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoFire = Output(Bool())
  val blockedByNoPayload = Output(Bool())
  val blockedByInvalidTarget = Output(Bool())
  val blockedByInvalidIdentity = Output(Bool())
  val blockedByWakeupNotRequired = Output(Bool())
  val blockedByNoDestination = Output(Bool())
  val invalidFireWithoutPayload = Output(Bool())
  val invalidPayloadWithoutFire = Output(Bool())
}

class LoadReplayReturnPipeW2WakeupFirePayload(
    val idEntries: Int = 16,
    val pcWidth: Int = 64,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(pcWidth > 0, "pcWidth must be positive")
  require(returnPipeCount > 0, "returnPipeCount must be positive")
  require(archRegWidth > 0, "archRegWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val io = IO(new LoadReplayReturnPipeW2WakeupFirePayloadIO(
    idEntries,
    pcWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  val active = io.enable && !io.flush
  val candidateValid = active && io.wakeupFire
  val payloadValid = active && io.wakeupPayloadValid
  val fireValid = candidateValid && io.wakeupPayloadValid && io.wakeupTargetValid &&
    io.wakeupIdentityValid && io.wakeupRequired && io.wakeupDestinationValid

  io.candidateValid := candidateValid
  io.payloadValid := payloadValid
  io.targetValid := candidateValid && io.wakeupTargetValid
  io.identityValid := candidateValid && io.wakeupTargetValid && io.wakeupIdentityValid
  io.required := candidateValid && io.wakeupTargetValid && io.wakeupIdentityValid && io.wakeupRequired
  io.destinationValid := candidateValid && io.wakeupTargetValid &&
    io.wakeupIdentityValid && io.wakeupRequired && io.wakeupDestinationValid
  io.reducedGprWakeup := fireValid && io.wakeupReducedGpr
  io.nonGprWakeup := fireValid && io.wakeupNonGpr
  io.fireValid := fireValid
  io.targetIsAgu := fireValid && io.wakeupTargetIsAgu
  io.targetIsLda := fireValid && io.wakeupTargetIsLda
  io.targetPipeIndex := Mux(fireValid, io.wakeupTargetPipeIndex, 0.U(returnPipeIndexWidth.W))
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

  when(fireValid) {
    io.fireBid := io.wakeupBid
    io.fireGid := io.wakeupGid
    io.fireRid := io.wakeupRid
    io.fireLoadLsId := io.wakeupLoadLsId
    io.firePc := io.wakeupPc
    io.fireKind := io.wakeupKind
    io.fireArchTag := io.wakeupArchTag
    io.fireRelTag := io.wakeupRelTag
    io.firePhysTag := io.wakeupPhysTag
    io.fireOldPhysTag := io.wakeupOldPhysTag
  }

  io.blockedByDisabled := !io.enable && io.wakeupFire
  io.blockedByFlush := io.enable && io.flush && io.wakeupFire
  io.blockedByNoFire := active && io.wakeupPayloadValid && !io.wakeupFire
  io.blockedByNoPayload := candidateValid && !io.wakeupPayloadValid
  io.blockedByInvalidTarget := candidateValid && !io.wakeupTargetValid
  io.blockedByInvalidIdentity := candidateValid && io.wakeupTargetValid && !io.wakeupIdentityValid
  io.blockedByWakeupNotRequired := candidateValid && io.wakeupTargetValid &&
    io.wakeupIdentityValid && !io.wakeupRequired
  io.blockedByNoDestination := candidateValid && io.wakeupTargetValid &&
    io.wakeupIdentityValid && io.wakeupRequired && !io.wakeupDestinationValid
  io.invalidFireWithoutPayload := io.blockedByNoPayload
  io.invalidPayloadWithoutFire := active && !io.wakeupFire && io.wakeupPayloadValid
}
