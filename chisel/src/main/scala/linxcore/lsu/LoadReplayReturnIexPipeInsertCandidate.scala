package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.commit.{CommitOperandTrace, CommitTraceParams}
import linxcore.rob.ROBID

class LoadReplayReturnIexPipeInsertCandidateIO(
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
  val flush = Input(Bool())
  val setMemDataValid = Input(Bool())
  val pipeInsertReady = Input(Bool())
  val pipeInsertIndex = Input(UInt(returnPipeIndexWidth.W))
  val memBid = Input(new ROBID(idEntries))
  val memGid = Input(new ROBID(idEntries))
  val memRid = Input(new ROBID(idEntries))
  val memLoadLsId = Input(new ROBID(idEntries))
  val memPc = Input(UInt(pcWidth.W))
  val memAddr = Input(UInt(addrWidth.W))
  val memSize = Input(UInt(sizeWidth.W))
  val memDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))
  val memSourceTraceValid = Input(Bool())
  val memSource0 = Input(new CommitOperandTrace(sourceTraceParams))
  val memSource1 = Input(new CommitOperandTrace(sourceTraceParams))
  val memData = Input(UInt(dataWidth.W))
  val memLoadToUsePipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val memSpecWakeup = Input(Bool())
  val memStackValid = Input(Bool())

  val candidateValid = Output(Bool())
  val insertValid = Output(Bool())
  val insertIsLoadReturn = Output(Bool())
  val insertPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val insertLoadToUsePipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val insertBid = Output(new ROBID(idEntries))
  val insertGid = Output(new ROBID(idEntries))
  val insertRid = Output(new ROBID(idEntries))
  val insertLoadLsId = Output(new ROBID(idEntries))
  val insertPc = Output(UInt(pcWidth.W))
  val insertAddr = Output(UInt(addrWidth.W))
  val insertSize = Output(UInt(sizeWidth.W))
  val insertDst = Output(new LoadReplayDestination(archRegWidth, physRegWidth))
  val insertSourceTraceValid = Output(Bool())
  val insertSource0 = Output(new CommitOperandTrace(sourceTraceParams))
  val insertSource1 = Output(new CommitOperandTrace(sourceTraceParams))
  val insertData = Output(UInt(dataWidth.W))
  val insertWakeupRequired = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoSetMemData = Output(Bool())
  val blockedByNoPipe = Output(Bool())
  val blockedByInvalidRid = Output(Bool())
}

class LoadReplayReturnIexPipeInsertCandidate(
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

  val io = IO(new LoadReplayReturnIexPipeInsertCandidateIO(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  val candidateValid = io.enable && !io.flush && io.setMemDataValid
  val insertValid = candidateValid && io.pipeInsertReady && io.memRid.valid

  io.candidateValid := candidateValid
  io.insertValid := insertValid
  io.insertIsLoadReturn := insertValid
  io.insertPipeIndex := 0.U(returnPipeIndexWidth.W)
  io.insertLoadToUsePipeIndex := 0.U(returnPipeIndexWidth.W)
  io.insertBid := ROBID.disabled(idEntries)
  io.insertGid := ROBID.disabled(idEntries)
  io.insertRid := ROBID.disabled(idEntries)
  io.insertLoadLsId := ROBID.disabled(idEntries)
  io.insertPc := 0.U
  io.insertAddr := 0.U
  io.insertSize := 0.U
  io.insertDst := LoadReplayDestination.none(archRegWidth, physRegWidth)
  io.insertSourceTraceValid := false.B
  io.insertSource0 := 0.U.asTypeOf(io.insertSource0)
  io.insertSource1 := 0.U.asTypeOf(io.insertSource1)
  io.insertData := 0.U
  io.insertWakeupRequired := false.B

  when(insertValid) {
    io.insertPipeIndex := io.pipeInsertIndex
    io.insertLoadToUsePipeIndex := io.memLoadToUsePipeIndex
    io.insertBid := io.memBid
    io.insertGid := io.memGid
    io.insertRid := io.memRid
    io.insertLoadLsId := io.memLoadLsId
    io.insertPc := io.memPc
    io.insertAddr := io.memAddr
    io.insertSize := io.memSize
    io.insertDst := io.memDst
    io.insertSourceTraceValid := io.memSourceTraceValid
    io.insertSource0 := io.memSource0
    io.insertSource1 := io.memSource1
    io.insertData := io.memData
    io.insertWakeupRequired := !io.memSpecWakeup && !io.memStackValid
  }

  io.blockedByDisabled := !io.enable && io.setMemDataValid
  io.blockedByFlush := io.enable && io.flush && io.setMemDataValid
  io.blockedByNoSetMemData := io.enable && !io.flush && !io.setMemDataValid
  io.blockedByNoPipe := candidateValid && !io.pipeInsertReady
  io.blockedByInvalidRid := candidateValid && io.pipeInsertReady && !io.memRid.valid
}
