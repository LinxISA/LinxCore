package linxcore.lsu

import chisel3._

import linxcore.commit.{CommitOperandTrace, CommitTraceParams}
import linxcore.rob.ROBID

/** E1 admission adapter for ordinary scalar loads entering the reduced LIQ. */
class ReducedLiveLoadLiqCaptureIO(
    val idEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val sourceTraceParams =
    CommitTraceParams(regWidth = math.max(8, archRegWidth), dataWidth = addrWidth)

  val captureEnable = Input(Bool())
  val flush = Input(Bool())
  val loadValid = Input(Bool())
  val loadPc = Input(UInt(pcWidth.W))
  val loadAddr = Input(UInt(addrWidth.W))
  val loadSize = Input(UInt(sizeWidth.W))
  val loadReturnSignExtend = Input(Bool())
  val loadDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))
  val loadSourceTraceValid = Input(Bool())
  val loadSource0 = Input(new CommitOperandTrace(sourceTraceParams))
  val loadSource1 = Input(new CommitOperandTrace(sourceTraceParams))
  val loadBid = Input(new ROBID(idEntries))
  val loadGid = Input(new ROBID(idEntries))
  val loadRid = Input(new ROBID(idEntries))
  val loadLsId = Input(new ROBID(idEntries))
  val loadLsIdFullValid = Input(Bool())
  val loadLsIdFull = Input(UInt(lsidWidth.W))
  val youngestStoreId = Input(new ROBID(idEntries))
  val youngestStoreLsId = Input(new ROBID(idEntries))
  val youngestStoreLsIdFullValid = Input(Bool())
  val youngestStoreLsIdFull = Input(UInt(lsidWidth.W))
  val allocReady = Input(Bool())

  val candidateValid = Output(Bool())
  val candidate = Output(new ReducedLoadReplayCandidate(
    idEntries,
    addrWidth,
    pcWidth,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    lsidWidth))
  val captureAccepted = Output(Bool())
  val blockedByAlloc = Output(Bool())
}

class ReducedLiveLoadLiqCapture(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val lsidWidth: Int = 32)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  val io = IO(new ReducedLiveLoadLiqCaptureIO(
    idEntries,
    addrWidth,
    pcWidth,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    lsidWidth))

  private def disabledId: ROBID = ROBID.disabled(idEntries)

  val candidate = Wire(new ReducedLoadReplayCandidate(
    idEntries,
    addrWidth,
    pcWidth,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    lsidWidth))
  candidate := 0.U.asTypeOf(candidate)
  candidate.dst := LoadReplayDestination.none(archRegWidth, physRegWidth)
  candidate.bid := disabledId
  candidate.gid := disabledId
  candidate.rid := disabledId
  candidate.loadLsId := disabledId
  candidate.youngestStoreId := disabledId
  candidate.youngestStoreLsId := disabledId

  val candidateValid = io.captureEnable && !io.flush && io.loadValid
  when(candidateValid) {
    candidate.valid := true.B
    candidate.pc := io.loadPc
    candidate.addr := io.loadAddr
    candidate.size := io.loadSize
    candidate.returnSignExtend := io.loadReturnSignExtend
    candidate.dst := io.loadDst
    candidate.sourceTraceValid := io.loadSourceTraceValid
    candidate.source0 := io.loadSource0
    candidate.source1 := io.loadSource1
    candidate.bid := io.loadBid
    candidate.gid := io.loadGid
    candidate.rid := io.loadRid
    candidate.loadLsId := io.loadLsId
    candidate.loadLsIdFullValid := io.loadLsIdFullValid
    candidate.loadLsIdFull := io.loadLsIdFull
    candidate.youngestStoreId := io.youngestStoreId
    candidate.youngestStoreLsId := io.youngestStoreLsId
    candidate.youngestStoreLsIdFullValid := io.youngestStoreLsIdFullValid
    candidate.youngestStoreLsIdFull := io.youngestStoreLsIdFull
  }

  io.candidateValid := candidateValid
  io.candidate := candidate
  io.captureAccepted := candidateValid && io.allocReady
  io.blockedByAlloc := candidateValid && !io.allocReady
}
