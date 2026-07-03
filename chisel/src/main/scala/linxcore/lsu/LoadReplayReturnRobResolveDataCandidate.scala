package linxcore.lsu

import chisel3._

import linxcore.rob.ROBID

class LoadReplayReturnRobResolveDataCandidateIO(
    val idEntries: Int = 16,
    val dataWidth: Int = 64,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val setMemDataValid = Input(Bool())
  val reducedSingleLane = Input(Bool())
  val memRid = Input(new ROBID(idEntries))
  val memDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))
  val memData = Input(UInt(dataWidth.W))

  val candidateValid = Output(Bool())
  val resolveValid = Output(Bool())
  val readyForPipeInsert = Output(Bool())
  val resolveRid = Output(new ROBID(idEntries))
  val resolveDst = Output(new LoadReplayDestination(archRegWidth, physRegWidth))
  val resolveData = Output(UInt(dataWidth.W))
  val markAllDestinationsDataValid = Output(Bool())
  val markDestinationDataValid = Output(Bool())
  val retLaneIncrement = Output(Bool())
  val vectorLaneDataWrite = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoSetMemData = Output(Bool())
  val blockedByUnsupportedMultiLane = Output(Bool())
  val blockedByInvalidRid = Output(Bool())
  val blockedByNoDestination = Output(Bool())
}

class LoadReplayReturnRobResolveDataCandidate(
    val idEntries: Int = 16,
    val dataWidth: Int = 64,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(dataWidth > 0, "dataWidth must be positive")
  require(archRegWidth > 0, "archRegWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  val io = IO(new LoadReplayReturnRobResolveDataCandidateIO(
    idEntries,
    dataWidth,
    archRegWidth,
    physRegWidth
  ))

  val candidateValid = io.enable && !io.flush && io.setMemDataValid
  val resolveValid = candidateValid && io.reducedSingleLane && io.memRid.valid
  val destinationPresent = io.memDst.valid

  io.candidateValid := candidateValid
  io.resolveValid := resolveValid
  io.readyForPipeInsert := resolveValid
  io.resolveRid := ROBID.disabled(idEntries)
  io.resolveDst := LoadReplayDestination.none(archRegWidth, physRegWidth)
  io.resolveData := 0.U
  io.markAllDestinationsDataValid := resolveValid
  io.markDestinationDataValid := resolveValid && destinationPresent
  io.retLaneIncrement := resolveValid
  io.vectorLaneDataWrite := false.B

  when(resolveValid) {
    io.resolveRid := io.memRid
    io.resolveDst := io.memDst
    io.resolveData := io.memData
  }

  io.blockedByDisabled := !io.enable && io.setMemDataValid
  io.blockedByFlush := io.enable && io.flush && io.setMemDataValid
  io.blockedByNoSetMemData := io.enable && !io.flush && !io.setMemDataValid
  io.blockedByUnsupportedMultiLane := candidateValid && !io.reducedSingleLane
  io.blockedByInvalidRid := candidateValid && io.reducedSingleLane && !io.memRid.valid
  io.blockedByNoDestination := resolveValid && !destinationPresent
}
