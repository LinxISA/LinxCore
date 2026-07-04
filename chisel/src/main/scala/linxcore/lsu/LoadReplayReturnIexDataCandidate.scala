package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.commit.{CommitOperandTrace, CommitTraceParams}
import linxcore.rob.ROBID

class LoadReplayReturnIexDataCandidateIO(
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
  val sinkValid = Input(Bool())
  val drainReady = Input(Bool())
  val entry = Input(new LoadReplayReturnLretEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))
  val robRowValid = Input(Bool())
  val robRowNeedFlush = Input(Bool())

  val candidateValid = Output(Bool())
  val wouldDrain = Output(Bool())
  val setMemDataValid = Output(Bool())
  val memBid = Output(new ROBID(idEntries))
  val memGid = Output(new ROBID(idEntries))
  val memRid = Output(new ROBID(idEntries))
  val memLoadLsId = Output(new ROBID(idEntries))
  val memPc = Output(UInt(pcWidth.W))
  val memAddr = Output(UInt(addrWidth.W))
  val memSize = Output(UInt(sizeWidth.W))
  val memDst = Output(new LoadReplayDestination(archRegWidth, physRegWidth))
  val memSourceTraceValid = Output(Bool())
  val memSource0 = Output(new CommitOperandTrace(sourceTraceParams))
  val memSource1 = Output(new CommitOperandTrace(sourceTraceParams))
  val memData = Output(UInt(dataWidth.W))
  val memPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val memSpecWakeup = Output(Bool())
  val memStackValid = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoEntry = Output(Bool())
  val blockedByInvalidEntry = Output(Bool())
  val blockedByDrain = Output(Bool())
  val blockedByRobMissing = Output(Bool())
  val blockedByNeedFlush = Output(Bool())
}

class LoadReplayReturnIexDataCandidate(
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

  val io = IO(new LoadReplayReturnIexDataCandidateIO(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  val candidateValid = io.enable && !io.flush && io.sinkValid && io.entry.valid
  val wouldDrain = candidateValid && io.drainReady
  val setMemDataValid = wouldDrain && io.robRowValid && !io.robRowNeedFlush

  io.candidateValid := candidateValid
  io.wouldDrain := wouldDrain
  io.setMemDataValid := setMemDataValid
  io.memBid := ROBID.disabled(idEntries)
  io.memGid := ROBID.disabled(idEntries)
  io.memRid := ROBID.disabled(idEntries)
  io.memLoadLsId := ROBID.disabled(idEntries)
  io.memPc := 0.U
  io.memAddr := 0.U
  io.memSize := 0.U
  io.memDst := LoadReplayDestination.none(archRegWidth, physRegWidth)
  io.memSourceTraceValid := false.B
  io.memSource0 := 0.U.asTypeOf(io.memSource0)
  io.memSource1 := 0.U.asTypeOf(io.memSource1)
  io.memData := 0.U
  io.memPipeIndex := 0.U(returnPipeIndexWidth.W)
  io.memSpecWakeup := false.B
  io.memStackValid := false.B

  when(setMemDataValid) {
    io.memBid := io.entry.bid
    io.memGid := io.entry.gid
    io.memRid := io.entry.rid
    io.memLoadLsId := io.entry.loadLsId
    io.memPc := io.entry.pc
    io.memAddr := io.entry.addr
    io.memSize := io.entry.size
    io.memDst := io.entry.dst
    io.memSourceTraceValid := io.entry.sourceTraceValid
    io.memSource0 := io.entry.source0
    io.memSource1 := io.entry.source1
    io.memData := io.entry.data
    io.memPipeIndex := io.entry.pipeIndex
    io.memSpecWakeup := io.entry.specWakeup
    io.memStackValid := io.entry.stackValid
  }

  io.blockedByDisabled := !io.enable && io.sinkValid
  io.blockedByFlush := io.enable && io.flush && io.sinkValid
  io.blockedByNoEntry := io.enable && !io.flush && !io.sinkValid
  io.blockedByInvalidEntry := io.enable && !io.flush && io.sinkValid && !io.entry.valid
  io.blockedByDrain := candidateValid && !io.drainReady
  io.blockedByRobMissing := wouldDrain && !io.robRowValid
  io.blockedByNeedFlush := wouldDrain && io.robRowValid && io.robRowNeedFlush
}
