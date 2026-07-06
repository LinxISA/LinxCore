package linxcore.lsu

import chisel3._

import linxcore.rob.ROBID

class LoadReplayReturnPipeW2AtomicPrereqSnapshotIO(val idEntries: Int = 16) extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val captureEnable = Input(Bool())
  val slotOccupied = Input(Bool())
  val slotBid = Input(new ROBID(idEntries))
  val slotGid = Input(new ROBID(idEntries))
  val slotRid = Input(new ROBID(idEntries))
  val slotLoadLsId = Input(new ROBID(idEntries))
  val sideEffectSinksReadyIn = Input(Bool())
  val clearCommitReadyIn = Input(Bool())
  val rowFillCandidateValidIn = Input(Bool())
  val lifecycleRowClearReadyIn = Input(Bool())

  val active = Output(Bool())
  val captureCandidate = Output(Bool())
  val captureAccepted = Output(Bool())
  val slotIdentityValid = Output(Bool())
  val snapshotValid = Output(Bool())
  val snapshotMatchesSlot = Output(Bool())
  val prereqsUsable = Output(Bool())
  val sideEffectSinksReady = Output(Bool())
  val clearCommitReady = Output(Bool())
  val rowFillCandidateValid = Output(Bool())
  val lifecycleRowClearReady = Output(Bool())
  val residentPrereqsReady = Output(Bool())
  val snapshotBid = Output(new ROBID(idEntries))
  val snapshotGid = Output(new ROBID(idEntries))
  val snapshotRid = Output(new ROBID(idEntries))
  val snapshotLoadLsId = Output(new ROBID(idEntries))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByCaptureDisabled = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByInvalidSlotIdentity = Output(Bool())
  val blockedByNoSnapshot = Output(Bool())
  val blockedByIdentityMismatch = Output(Bool())
  val blockedByNoSideEffectSink = Output(Bool())
  val blockedByNoClearCommit = Output(Bool())
  val blockedByNoRowFillCandidate = Output(Bool())
  val blockedByNoLifecycleRow = Output(Bool())
}

class LoadReplayReturnPipeW2AtomicPrereqSnapshot(val idEntries: Int = 16) extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")

  val io = IO(new LoadReplayReturnPipeW2AtomicPrereqSnapshotIO(idEntries))

  val active = io.enable && !io.flush
  val slotIdentityValid =
    io.slotBid.valid && io.slotGid.valid && io.slotRid.valid && io.slotLoadLsId.valid
  val captureCandidate = active && io.captureEnable && io.slotOccupied
  val captureAccepted = captureCandidate && slotIdentityValid

  val snapshotValidReg = RegInit(false.B)
  val snapshotBidReg = RegInit(ROBID.disabled(idEntries))
  val snapshotGidReg = RegInit(ROBID.disabled(idEntries))
  val snapshotRidReg = RegInit(ROBID.disabled(idEntries))
  val snapshotLoadLsIdReg = RegInit(ROBID.disabled(idEntries))
  val sideEffectSinksReadyReg = RegInit(false.B)
  val clearCommitReadyReg = RegInit(false.B)
  val rowFillCandidateValidReg = RegInit(false.B)
  val lifecycleRowClearReadyReg = RegInit(false.B)

  when(!active) {
    snapshotValidReg := false.B
  }.elsewhen(captureAccepted) {
    snapshotValidReg := true.B
    snapshotBidReg := io.slotBid
    snapshotGidReg := io.slotGid
    snapshotRidReg := io.slotRid
    snapshotLoadLsIdReg := io.slotLoadLsId
    sideEffectSinksReadyReg := io.sideEffectSinksReadyIn
    clearCommitReadyReg := io.clearCommitReadyIn
    rowFillCandidateValidReg := io.rowFillCandidateValidIn
    lifecycleRowClearReadyReg := io.lifecycleRowClearReadyIn
  }

  val snapshotMatchesSlot =
    snapshotValidReg &&
      io.slotOccupied &&
      slotIdentityValid &&
      snapshotBidReg.valid &&
      snapshotGidReg.valid &&
      snapshotRidReg.valid &&
      snapshotLoadLsIdReg.valid &&
      ROBID.equal(snapshotBidReg, io.slotBid) &&
      ROBID.equal(snapshotGidReg, io.slotGid) &&
      ROBID.equal(snapshotRidReg, io.slotRid) &&
      ROBID.equal(snapshotLoadLsIdReg, io.slotLoadLsId)
  val prereqsUsable = active && snapshotMatchesSlot

  io.active := active
  io.captureCandidate := captureCandidate
  io.captureAccepted := captureAccepted
  io.slotIdentityValid := io.slotOccupied && slotIdentityValid
  io.snapshotValid := snapshotValidReg
  io.snapshotMatchesSlot := snapshotMatchesSlot
  io.prereqsUsable := prereqsUsable
  io.sideEffectSinksReady := prereqsUsable && sideEffectSinksReadyReg
  io.clearCommitReady := prereqsUsable && clearCommitReadyReg
  io.rowFillCandidateValid := prereqsUsable && rowFillCandidateValidReg
  io.lifecycleRowClearReady := prereqsUsable && lifecycleRowClearReadyReg
  io.residentPrereqsReady :=
    prereqsUsable &&
      sideEffectSinksReadyReg &&
      clearCommitReadyReg &&
      rowFillCandidateValidReg &&
      lifecycleRowClearReadyReg
  io.snapshotBid := snapshotBidReg
  io.snapshotGid := snapshotGidReg
  io.snapshotRid := snapshotRidReg
  io.snapshotLoadLsId := snapshotLoadLsIdReg
  io.blockedByDisabled := !io.enable && (io.captureEnable || snapshotValidReg)
  io.blockedByFlush := io.enable && io.flush && (io.captureEnable || snapshotValidReg)
  io.blockedByCaptureDisabled := active && !io.captureEnable && io.slotOccupied
  io.blockedByNoSlot := active && io.captureEnable && !io.slotOccupied
  io.blockedByInvalidSlotIdentity := captureCandidate && !slotIdentityValid
  io.blockedByNoSnapshot := active && io.slotOccupied && !snapshotValidReg
  io.blockedByIdentityMismatch := active && io.slotOccupied && snapshotValidReg && !snapshotMatchesSlot
  io.blockedByNoSideEffectSink := prereqsUsable && !sideEffectSinksReadyReg
  io.blockedByNoClearCommit := prereqsUsable && sideEffectSinksReadyReg && !clearCommitReadyReg
  io.blockedByNoRowFillCandidate :=
    prereqsUsable && sideEffectSinksReadyReg && clearCommitReadyReg && !rowFillCandidateValidReg
  io.blockedByNoLifecycleRow :=
    prereqsUsable && sideEffectSinksReadyReg && clearCommitReadyReg && rowFillCandidateValidReg &&
      !lifecycleRowClearReadyReg
}
