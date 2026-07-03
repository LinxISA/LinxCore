package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplayReturnPipeW2ClearCommitGuardIO(val idEntries: Int = 16) extends Bundle {
  private val ptrWidth = log2Ceil(idEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val slotOccupied = Input(Bool())
  val slotRid = Input(new ROBID(idEntries))
  val clearIntent = Input(Bool())
  val liveClear = Input(Bool())
  val resolveFireValid = Input(Bool())
  val resolveFireRid = Input(new ROBID(idEntries))
  val robCompleteValid = Input(Bool())
  val robCompleteRobValue = Input(UInt(ptrWidth.W))

  val active = Output(Bool())
  val candidateValid = Output(Bool())
  val slotRidValid = Output(Bool())
  val resolveFireRidValid = Output(Bool())
  val resolveMatchesSlot = Output(Bool())
  val robMatchesSlot = Output(Bool())
  val robMatchesResolve = Output(Bool())
  val commitClearReady = Output(Bool())
  val liveClearReady = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByNoClearIntent = Output(Bool())
  val blockedByLiveClearDisabled = Output(Bool())
  val blockedByInvalidSlotRid = Output(Bool())
  val blockedByNoResolveFire = Output(Bool())
  val blockedByInvalidResolveRid = Output(Bool())
  val blockedByNoRobComplete = Output(Bool())
  val blockedByResolveSlotMismatch = Output(Bool())
  val blockedByRobSlotMismatch = Output(Bool())
  val blockedByRobResolveMismatch = Output(Bool())
  val invalidResolveWithoutClear = Output(Bool())
  val invalidRobCompleteWithoutClear = Output(Bool())
}

class LoadReplayReturnPipeW2ClearCommitGuard(val idEntries: Int = 16) extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")

  val io = IO(new LoadReplayReturnPipeW2ClearCommitGuardIO(idEntries))

  val active = io.enable && !io.flush
  val candidateValid = active && io.slotOccupied
  val clearCandidate = candidateValid && io.clearIntent
  val slotRidValid = io.slotRid.valid
  val resolveFireRidValid = io.resolveFireValid && io.resolveFireRid.valid
  val resolveMatchesSlot =
    resolveFireRidValid && slotRidValid && ROBID.equal(io.resolveFireRid, io.slotRid)
  val robMatchesSlot =
    io.robCompleteValid && slotRidValid && (io.robCompleteRobValue === io.slotRid.value)
  val robMatchesResolve =
    io.robCompleteValid && resolveFireRidValid &&
      (io.robCompleteRobValue === io.resolveFireRid.value)
  val commitClearReady =
    clearCandidate && slotRidValid && resolveMatchesSlot && robMatchesSlot && robMatchesResolve

  io.active := active
  io.candidateValid := candidateValid
  io.slotRidValid := slotRidValid
  io.resolveFireRidValid := resolveFireRidValid
  io.resolveMatchesSlot := resolveMatchesSlot
  io.robMatchesSlot := robMatchesSlot
  io.robMatchesResolve := robMatchesResolve
  io.commitClearReady := commitClearReady
  io.liveClearReady := commitClearReady && io.liveClear
  io.blockedByDisabled := !io.enable && io.slotOccupied
  io.blockedByFlush := io.enable && io.flush && io.slotOccupied
  io.blockedByNoSlot := active && !io.slotOccupied
  io.blockedByNoClearIntent := candidateValid && !io.clearIntent
  io.blockedByLiveClearDisabled := commitClearReady && !io.liveClear
  io.blockedByInvalidSlotRid := clearCandidate && !slotRidValid
  io.blockedByNoResolveFire := clearCandidate && !io.resolveFireValid
  io.blockedByInvalidResolveRid := clearCandidate && io.resolveFireValid && !io.resolveFireRid.valid
  io.blockedByNoRobComplete := clearCandidate && !io.robCompleteValid
  io.blockedByResolveSlotMismatch :=
    clearCandidate && resolveFireRidValid && slotRidValid && !resolveMatchesSlot
  io.blockedByRobSlotMismatch :=
    clearCandidate && io.robCompleteValid && slotRidValid && !robMatchesSlot
  io.blockedByRobResolveMismatch :=
    clearCandidate && io.robCompleteValid && resolveFireRidValid && !robMatchesResolve
  io.invalidResolveWithoutClear := candidateValid && !io.clearIntent && io.resolveFireValid
  io.invalidRobCompleteWithoutClear := candidateValid && !io.clearIntent && io.robCompleteValid
}
