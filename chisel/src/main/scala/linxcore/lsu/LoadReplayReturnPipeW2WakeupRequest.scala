package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.DestinationKind
import linxcore.rob.ROBID

class LoadReplayReturnPipeW2WakeupRequestIO(
    val idEntries: Int = 16,
    val pcWidth: Int = 64,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val wakeupRequest = Input(Bool())
  val slotOccupied = Input(Bool())
  val slotTargetIsAgu = Input(Bool())
  val slotTargetIsLda = Input(Bool())
  val slotPipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val slotBid = Input(new ROBID(idEntries))
  val slotGid = Input(new ROBID(idEntries))
  val slotRid = Input(new ROBID(idEntries))
  val slotLoadLsId = Input(new ROBID(idEntries))
  val slotPc = Input(UInt(pcWidth.W))
  val slotDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))
  val slotWakeupRequired = Input(Bool())

  val candidateValid = Output(Bool())
  val targetValid = Output(Bool())
  val identityValid = Output(Bool())
  val wakeupRequired = Output(Bool())
  val destinationValid = Output(Bool())
  val wakeupValid = Output(Bool())
  val reducedGprWakeupValid = Output(Bool())
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
  val blockedByNoRequest = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByInvalidTarget = Output(Bool())
  val blockedByInvalidBid = Output(Bool())
  val blockedByInvalidGid = Output(Bool())
  val blockedByInvalidRid = Output(Bool())
  val blockedByInvalidIdentity = Output(Bool())
  val blockedByWakeupNotRequired = Output(Bool())
  val blockedByNoDestination = Output(Bool())
}

class LoadReplayReturnPipeW2WakeupRequest(
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

  val io = IO(new LoadReplayReturnPipeW2WakeupRequestIO(
    idEntries,
    pcWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  val candidateValid = io.wakeupRequest && io.slotOccupied
  val targetValid = io.slotTargetIsAgu ^ io.slotTargetIsLda
  val identityValid = io.slotBid.valid && io.slotGid.valid && io.slotRid.valid
  val hasDestination = io.slotDst.valid && (io.slotDst.kind =/= DestinationKind.None)
  val wakeupRequired = candidateValid && targetValid && identityValid && io.slotWakeupRequired
  val wakeupValid = wakeupRequired && hasDestination
  val isGprDestination = hasDestination && (io.slotDst.kind === DestinationKind.Gpr)

  io.candidateValid := candidateValid
  io.targetValid := candidateValid && targetValid
  io.identityValid := candidateValid && targetValid && identityValid
  io.wakeupRequired := wakeupRequired
  io.destinationValid := wakeupRequired && hasDestination
  io.wakeupValid := wakeupValid
  io.reducedGprWakeupValid := wakeupValid && isGprDestination
  io.nonGprWakeup := wakeupValid && !isGprDestination
  io.targetIsAgu := wakeupValid && io.slotTargetIsAgu
  io.targetIsLda := wakeupValid && io.slotTargetIsLda
  io.targetPipeIndex := Mux(wakeupValid, io.slotPipeIndex, 0.U(returnPipeIndexWidth.W))
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
    io.wakeupBid := io.slotBid
    io.wakeupGid := io.slotGid
    io.wakeupRid := io.slotRid
    io.wakeupLoadLsId := io.slotLoadLsId
    io.wakeupPc := io.slotPc
    io.wakeupKind := io.slotDst.kind
    io.wakeupArchTag := io.slotDst.archTag
    io.wakeupRelTag := io.slotDst.relTag
    io.wakeupPhysTag := io.slotDst.physTag
    io.wakeupOldPhysTag := io.slotDst.oldPhysTag
  }

  io.blockedByNoRequest := io.slotOccupied && !io.wakeupRequest
  io.blockedByNoSlot := io.wakeupRequest && !io.slotOccupied
  io.blockedByInvalidTarget := candidateValid && !targetValid
  io.blockedByInvalidBid := candidateValid && targetValid && !io.slotBid.valid
  io.blockedByInvalidGid := candidateValid && targetValid && !io.slotGid.valid
  io.blockedByInvalidRid := candidateValid && targetValid && !io.slotRid.valid
  io.blockedByInvalidIdentity := candidateValid && targetValid && !identityValid
  io.blockedByWakeupNotRequired := candidateValid && targetValid && identityValid && !io.slotWakeupRequired
  io.blockedByNoDestination := wakeupRequired && !hasDestination
}
