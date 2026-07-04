package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.commit.{CommitOperandTrace, CommitTraceParams}
import linxcore.rob.ROBID

class LoadReplayReturnLretPayloadIO(
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
  private val sourceTraceParams =
    CommitTraceParams(regWidth = math.max(8, archRegWidth), dataWidth = dataWidth)

  val enable = Input(Bool())
  val launchValid = Input(Bool())
  val dataValid = Input(Bool())
  val selectedBid = Input(new ROBID(idEntries))
  val selectedGid = Input(new ROBID(idEntries))
  val selectedRid = Input(new ROBID(idEntries))
  val selectedLoadLsId = Input(new ROBID(idEntries))
  val selectedPc = Input(UInt(pcWidth.W))
  val selectedAddr = Input(UInt(addrWidth.W))
  val selectedSize = Input(UInt(sizeWidth.W))
  val selectedDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))
  val selectedSourceTraceValid = Input(Bool())
  val selectedSource0 = Input(new CommitOperandTrace(sourceTraceParams))
  val selectedSource1 = Input(new CommitOperandTrace(sourceTraceParams))
  val returnData = Input(UInt(dataWidth.W))
  val returnPipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val specWakeup = Input(Bool())
  val stackValid = Input(Bool())

  val candidateValid = Output(Bool())
  val payloadValid = Output(Bool())
  val payloadBid = Output(new ROBID(idEntries))
  val payloadGid = Output(new ROBID(idEntries))
  val payloadRid = Output(new ROBID(idEntries))
  val payloadLoadLsId = Output(new ROBID(idEntries))
  val payloadPc = Output(UInt(pcWidth.W))
  val payloadAddr = Output(UInt(addrWidth.W))
  val payloadSize = Output(UInt(sizeWidth.W))
  val payloadDst = Output(new LoadReplayDestination(archRegWidth, physRegWidth))
  val payloadSourceTraceValid = Output(Bool())
  val payloadSource0 = Output(new CommitOperandTrace(sourceTraceParams))
  val payloadSource1 = Output(new CommitOperandTrace(sourceTraceParams))
  val payloadData = Output(UInt(dataWidth.W))
  val payloadPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val payloadSpecWakeup = Output(Bool())
  val payloadStackValid = Output(Bool())
  val wakeupRequired = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedByData = Output(Bool())
}

class LoadReplayReturnLretPayload(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(addrWidth > 0, "addrWidth must be positive")
  require(pcWidth > 0, "pcWidth must be positive")
  require(dataWidth > 0, "dataWidth must be positive")
  require(sizeWidth > 0, "sizeWidth must be positive")
  require(returnPipeCount > 0, "returnPipeCount must be positive")

  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val io = IO(new LoadReplayReturnLretPayloadIO(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  val candidateValid = io.enable && io.launchValid
  val payloadValid = candidateValid && io.dataValid

  io.candidateValid := candidateValid
  io.payloadValid := payloadValid
  io.payloadBid := ROBID.disabled(idEntries)
  io.payloadGid := ROBID.disabled(idEntries)
  io.payloadRid := ROBID.disabled(idEntries)
  io.payloadLoadLsId := ROBID.disabled(idEntries)
  io.payloadPc := 0.U
  io.payloadAddr := 0.U
  io.payloadSize := 0.U
  io.payloadDst := LoadReplayDestination.none(archRegWidth, physRegWidth)
  io.payloadSourceTraceValid := false.B
  io.payloadSource0 := 0.U.asTypeOf(io.payloadSource0)
  io.payloadSource1 := 0.U.asTypeOf(io.payloadSource1)
  io.payloadData := 0.U
  io.payloadPipeIndex := 0.U(returnPipeIndexWidth.W)
  io.payloadSpecWakeup := false.B
  io.payloadStackValid := false.B
  io.wakeupRequired := false.B

  when(payloadValid) {
    io.payloadBid := io.selectedBid
    io.payloadGid := io.selectedGid
    io.payloadRid := io.selectedRid
    io.payloadLoadLsId := io.selectedLoadLsId
    io.payloadPc := io.selectedPc
    io.payloadAddr := io.selectedAddr
    io.payloadSize := io.selectedSize
    io.payloadDst := io.selectedDst
    io.payloadSourceTraceValid := io.selectedSourceTraceValid
    io.payloadSource0 := io.selectedSource0
    io.payloadSource1 := io.selectedSource1
    io.payloadData := io.returnData
    io.payloadPipeIndex := io.returnPipeIndex
    io.payloadSpecWakeup := io.specWakeup
    io.payloadStackValid := io.stackValid
    io.wakeupRequired := !io.specWakeup && !io.stackValid
  }

  io.blockedByDisabled := !io.enable && io.launchValid
  io.blockedByNoCandidate := io.enable && !io.launchValid
  io.blockedByData := candidateValid && !io.dataValid
}
