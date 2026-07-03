package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.DestinationKind
import linxcore.rob.ROBID

class LoadReplayReturnPipeW2WritebackRequestIO(
    val idEntries: Int = 16,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val writebackRequest = Input(Bool())
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
  val slotData = Input(UInt(dataWidth.W))

  val candidateValid = Output(Bool())
  val targetValid = Output(Bool())
  val identityValid = Output(Bool())
  val destinationValid = Output(Bool())
  val gprDestination = Output(Bool())
  val writebackValid = Output(Bool())
  val targetIsAgu = Output(Bool())
  val targetIsLda = Output(Bool())
  val targetPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val writebackBid = Output(new ROBID(idEntries))
  val writebackGid = Output(new ROBID(idEntries))
  val writebackRid = Output(new ROBID(idEntries))
  val writebackLoadLsId = Output(new ROBID(idEntries))
  val writebackPc = Output(UInt(pcWidth.W))
  val writebackKind = Output(DestinationKind())
  val writebackArchTag = Output(UInt(archRegWidth.W))
  val writebackRelTag = Output(UInt(archRegWidth.W))
  val writebackPhysTag = Output(UInt(physRegWidth.W))
  val writebackOldPhysTag = Output(UInt(physRegWidth.W))
  val writebackData = Output(UInt(dataWidth.W))
  val blockedByNoRequest = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByInvalidTarget = Output(Bool())
  val blockedByInvalidBid = Output(Bool())
  val blockedByInvalidGid = Output(Bool())
  val blockedByInvalidRid = Output(Bool())
  val blockedByInvalidIdentity = Output(Bool())
  val blockedByNoDestination = Output(Bool())
  val blockedByNonGprDestination = Output(Bool())
}

class LoadReplayReturnPipeW2WritebackRequest(
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

  val io = IO(new LoadReplayReturnPipeW2WritebackRequestIO(
    idEntries,
    pcWidth,
    dataWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  val candidateValid = io.writebackRequest && io.slotOccupied
  val targetValid = io.slotTargetIsAgu ^ io.slotTargetIsLda
  val identityValid = io.slotBid.valid && io.slotGid.valid && io.slotRid.valid
  val hasDestination = io.slotDst.valid && (io.slotDst.kind =/= DestinationKind.None)
  val isGprDestination = hasDestination && (io.slotDst.kind === DestinationKind.Gpr)
  val writebackValid = candidateValid && targetValid && identityValid && isGprDestination

  io.candidateValid := candidateValid
  io.targetValid := candidateValid && targetValid
  io.identityValid := candidateValid && targetValid && identityValid
  io.destinationValid := candidateValid && targetValid && identityValid && hasDestination
  io.gprDestination := candidateValid && targetValid && identityValid && isGprDestination
  io.writebackValid := writebackValid
  io.targetIsAgu := writebackValid && io.slotTargetIsAgu
  io.targetIsLda := writebackValid && io.slotTargetIsLda
  io.targetPipeIndex := Mux(writebackValid, io.slotPipeIndex, 0.U(returnPipeIndexWidth.W))
  io.writebackBid := ROBID.disabled(idEntries)
  io.writebackGid := ROBID.disabled(idEntries)
  io.writebackRid := ROBID.disabled(idEntries)
  io.writebackLoadLsId := ROBID.disabled(idEntries)
  io.writebackPc := 0.U
  io.writebackKind := DestinationKind.None
  io.writebackArchTag := 0.U
  io.writebackRelTag := 0.U
  io.writebackPhysTag := 0.U
  io.writebackOldPhysTag := 0.U
  io.writebackData := 0.U

  when(writebackValid) {
    io.writebackBid := io.slotBid
    io.writebackGid := io.slotGid
    io.writebackRid := io.slotRid
    io.writebackLoadLsId := io.slotLoadLsId
    io.writebackPc := io.slotPc
    io.writebackKind := io.slotDst.kind
    io.writebackArchTag := io.slotDst.archTag
    io.writebackRelTag := io.slotDst.relTag
    io.writebackPhysTag := io.slotDst.physTag
    io.writebackOldPhysTag := io.slotDst.oldPhysTag
    io.writebackData := io.slotData
  }

  io.blockedByNoRequest := io.slotOccupied && !io.writebackRequest
  io.blockedByNoSlot := io.writebackRequest && !io.slotOccupied
  io.blockedByInvalidTarget := candidateValid && !targetValid
  io.blockedByInvalidBid := candidateValid && targetValid && !io.slotBid.valid
  io.blockedByInvalidGid := candidateValid && targetValid && !io.slotGid.valid
  io.blockedByInvalidRid := candidateValid && targetValid && !io.slotRid.valid
  io.blockedByInvalidIdentity := candidateValid && targetValid && !identityValid
  io.blockedByNoDestination := candidateValid && targetValid && identityValid && !hasDestination
  io.blockedByNonGprDestination := candidateValid && targetValid && identityValid &&
    hasDestination && !isGprDestination
}
