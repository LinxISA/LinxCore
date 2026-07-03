package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.DestinationKind
import linxcore.rob.ROBID

class LoadReplayReturnPipeW2WakeupArbiterInputIO(
    val idEntries: Int = 16,
    val pcWidth: Int = 64,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveEnable = Input(Bool())
  val firePayloadValid = Input(Bool())
  val fireReducedGprWakeup = Input(Bool())
  val fireNonGprWakeup = Input(Bool())
  val fireTargetIsAgu = Input(Bool())
  val fireTargetIsLda = Input(Bool())
  val fireTargetPipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val fireBid = Input(new ROBID(idEntries))
  val fireGid = Input(new ROBID(idEntries))
  val fireRid = Input(new ROBID(idEntries))
  val fireLoadLsId = Input(new ROBID(idEntries))
  val firePc = Input(UInt(pcWidth.W))
  val fireKind = Input(DestinationKind())
  val fireArchTag = Input(UInt(archRegWidth.W))
  val fireRelTag = Input(UInt(archRegWidth.W))
  val firePhysTag = Input(UInt(physRegWidth.W))
  val fireOldPhysTag = Input(UInt(physRegWidth.W))

  val active = Output(Bool())
  val candidateValid = Output(Bool())
  val wakeupValid = Output(Bool())
  val reducedGprWakeup = Output(Bool())
  val nonGprWakeup = Output(Bool())
  val targetIsAgu = Output(Bool())
  val targetIsLda = Output(Bool())
  val targetPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val wakeupBid = Output(new ROBID(idEntries))
  val wakeupGid = Output(new ROBID(idEntries))
  val wakeupRid = Output(new ROBID(idEntries))
  val wakeupLoadLsId = Output(new ROBID(idEntries))
  val wakeupPc = Output(UInt(pcWidth.W))
  val wakeupKind = Output(DestinationKind())
  val wakeupArchTag = Output(UInt(archRegWidth.W))
  val wakeupRelTag = Output(UInt(archRegWidth.W))
  val wakeupPhysTag = Output(UInt(physRegWidth.W))
  val wakeupOldPhysTag = Output(UInt(physRegWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoPayload = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2WakeupArbiterInput(
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

  val io = IO(new LoadReplayReturnPipeW2WakeupArbiterInputIO(
    idEntries,
    pcWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  val active = io.enable && !io.flush
  val candidateValid = active && io.firePayloadValid
  val wakeupValid = candidateValid && io.liveEnable

  io.active := active
  io.candidateValid := candidateValid
  io.wakeupValid := wakeupValid
  io.reducedGprWakeup := wakeupValid && io.fireReducedGprWakeup
  io.nonGprWakeup := wakeupValid && io.fireNonGprWakeup
  io.targetIsAgu := wakeupValid && io.fireTargetIsAgu
  io.targetIsLda := wakeupValid && io.fireTargetIsLda
  io.targetPipeIndex := Mux(wakeupValid, io.fireTargetPipeIndex, 0.U(returnPipeIndexWidth.W))
  io.wakeupBid := ROBID.disabled(idEntries)
  io.wakeupGid := ROBID.disabled(idEntries)
  io.wakeupRid := ROBID.disabled(idEntries)
  io.wakeupLoadLsId := ROBID.disabled(idEntries)
  io.wakeupPc := 0.U
  io.wakeupKind := DestinationKind.None
  io.wakeupArchTag := 0.U
  io.wakeupRelTag := 0.U
  io.wakeupPhysTag := 0.U
  io.wakeupOldPhysTag := 0.U

  when(wakeupValid) {
    io.wakeupBid := io.fireBid
    io.wakeupGid := io.fireGid
    io.wakeupRid := io.fireRid
    io.wakeupLoadLsId := io.fireLoadLsId
    io.wakeupPc := io.firePc
    io.wakeupKind := io.fireKind
    io.wakeupArchTag := io.fireArchTag
    io.wakeupRelTag := io.fireRelTag
    io.wakeupPhysTag := io.firePhysTag
    io.wakeupOldPhysTag := io.fireOldPhysTag
  }

  io.blockedByDisabled := !io.enable && io.firePayloadValid
  io.blockedByFlush := io.enable && io.flush && io.firePayloadValid
  io.blockedByNoPayload := active && !io.firePayloadValid
  io.blockedByLiveDisabled := candidateValid && !io.liveEnable
}
