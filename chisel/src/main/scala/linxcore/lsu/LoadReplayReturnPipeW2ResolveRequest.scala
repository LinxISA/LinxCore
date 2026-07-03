package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplayReturnPipeW2ResolveRequestIO(
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

  val resolveRequest = Input(Bool())
  val slotOccupied = Input(Bool())
  val slotTargetIsAgu = Input(Bool())
  val slotTargetIsLda = Input(Bool())
  val slotPipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val slotBid = Input(new ROBID(idEntries))
  val slotGid = Input(new ROBID(idEntries))
  val slotRid = Input(new ROBID(idEntries))
  val slotLoadLsId = Input(new ROBID(idEntries))
  val slotPc = Input(UInt(pcWidth.W))
  val slotAddr = Input(UInt(addrWidth.W))
  val slotSize = Input(UInt(sizeWidth.W))
  val slotDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))
  val slotData = Input(UInt(dataWidth.W))

  val candidateValid = Output(Bool())
  val targetValid = Output(Bool())
  val resolveValid = Output(Bool())
  val isComplete = Output(Bool())
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
  val blockedByNoRequest = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByInvalidTarget = Output(Bool())
  val blockedByInvalidBid = Output(Bool())
  val blockedByInvalidGid = Output(Bool())
  val blockedByInvalidRid = Output(Bool())
  val blockedByInvalidIdentity = Output(Bool())
}

class LoadReplayReturnPipeW2ResolveRequest(
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

  val io = IO(new LoadReplayReturnPipeW2ResolveRequestIO(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  val candidateValid = io.resolveRequest && io.slotOccupied
  val targetValid = io.slotTargetIsAgu ^ io.slotTargetIsLda
  val identityValid = io.slotBid.valid && io.slotGid.valid && io.slotRid.valid
  val resolveValid = candidateValid && targetValid && identityValid

  io.candidateValid := candidateValid
  io.targetValid := candidateValid && targetValid
  io.resolveValid := resolveValid
  io.isComplete := resolveValid
  io.targetIsAgu := resolveValid && io.slotTargetIsAgu
  io.targetIsLda := resolveValid && io.slotTargetIsLda
  io.targetPipeIndex := Mux(resolveValid, io.slotPipeIndex, 0.U(returnPipeIndexWidth.W))
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
    io.resolveBid := io.slotBid
    io.resolveGid := io.slotGid
    io.resolveRid := io.slotRid
    io.resolveLoadLsId := io.slotLoadLsId
    io.resolvePc := io.slotPc
    io.resolveAddr := io.slotAddr
    io.resolveSize := io.slotSize
    io.resolveDst := io.slotDst
    io.resolveData := io.slotData
  }

  io.blockedByNoRequest := io.slotOccupied && !io.resolveRequest
  io.blockedByNoSlot := io.resolveRequest && !io.slotOccupied
  io.blockedByInvalidTarget := candidateValid && !targetValid
  io.blockedByInvalidBid := candidateValid && targetValid && !io.slotBid.valid
  io.blockedByInvalidGid := candidateValid && targetValid && !io.slotGid.valid
  io.blockedByInvalidRid := candidateValid && targetValid && !io.slotRid.valid
  io.blockedByInvalidIdentity := candidateValid && targetValid && !identityValid
}
