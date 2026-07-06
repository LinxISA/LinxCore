package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2AtomicPrereqSnapshotReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)

  final case class Inputs(
      enable: Boolean = true,
      flush: Boolean = false,
      captureEnable: Boolean = true,
      slotOccupied: Boolean = true,
      slotBid: Id = Id(value = 1),
      slotGid: Id = Id(value = 2),
      slotRid: Id = Id(value = 3),
      slotLoadLsId: Id = Id(value = 4),
      sideEffectSinksReadyIn: Boolean = true,
      clearCommitReadyIn: Boolean = true,
      rowFillCandidateValidIn: Boolean = true,
      lifecycleRowClearReadyIn: Boolean = true)

  final case class State(
      snapshotValid: Boolean = false,
      snapshotBid: Id = Id(valid = false),
      snapshotGid: Id = Id(valid = false),
      snapshotRid: Id = Id(valid = false),
      snapshotLoadLsId: Id = Id(valid = false),
      sideEffectSinksReady: Boolean = false,
      clearCommitReady: Boolean = false,
      rowFillCandidateValid: Boolean = false,
      lifecycleRowClearReady: Boolean = false)

  final case class Result(
      active: Boolean,
      captureCandidate: Boolean,
      captureAccepted: Boolean,
      slotIdentityValid: Boolean,
      snapshotValid: Boolean,
      snapshotMatchesSlot: Boolean,
      prereqsUsable: Boolean,
      sideEffectSinksReady: Boolean,
      clearCommitReady: Boolean,
      rowFillCandidateValid: Boolean,
      lifecycleRowClearReady: Boolean,
      residentPrereqsReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByCaptureDisabled: Boolean,
      blockedByNoSlot: Boolean,
      blockedByInvalidSlotIdentity: Boolean,
      blockedByNoSnapshot: Boolean,
      blockedByIdentityMismatch: Boolean,
      blockedByNoSideEffectSink: Boolean,
      blockedByNoClearCommit: Boolean,
      blockedByNoRowFillCandidate: Boolean,
      blockedByNoLifecycleRow: Boolean,
      next: State)

  private def same(lhs: Id, rhs: Id): Boolean =
    lhs.wrap == rhs.wrap && lhs.value == rhs.value

  def step(state: State, in: Inputs): Result = {
    val active = in.enable && !in.flush
    val slotIdentityValid =
      in.slotBid.valid && in.slotGid.valid && in.slotRid.valid && in.slotLoadLsId.valid
    val captureCandidate = active && in.captureEnable && in.slotOccupied
    val captureAccepted = captureCandidate && slotIdentityValid
    val snapshotMatchesSlot =
      state.snapshotValid &&
        in.slotOccupied &&
        slotIdentityValid &&
        state.snapshotBid.valid &&
        state.snapshotGid.valid &&
        state.snapshotRid.valid &&
        state.snapshotLoadLsId.valid &&
        same(state.snapshotBid, in.slotBid) &&
        same(state.snapshotGid, in.slotGid) &&
        same(state.snapshotRid, in.slotRid) &&
        same(state.snapshotLoadLsId, in.slotLoadLsId)
    val prereqsUsable = active && snapshotMatchesSlot
    val next =
      if (!active) State()
      else if (captureAccepted) {
        State(
          snapshotValid = true,
          snapshotBid = in.slotBid,
          snapshotGid = in.slotGid,
          snapshotRid = in.slotRid,
          snapshotLoadLsId = in.slotLoadLsId,
          sideEffectSinksReady = in.sideEffectSinksReadyIn,
          clearCommitReady = in.clearCommitReadyIn,
          rowFillCandidateValid = in.rowFillCandidateValidIn,
          lifecycleRowClearReady = in.lifecycleRowClearReadyIn)
      } else {
        state
      }

    Result(
      active = active,
      captureCandidate = captureCandidate,
      captureAccepted = captureAccepted,
      slotIdentityValid = in.slotOccupied && slotIdentityValid,
      snapshotValid = state.snapshotValid,
      snapshotMatchesSlot = snapshotMatchesSlot,
      prereqsUsable = prereqsUsable,
      sideEffectSinksReady = prereqsUsable && state.sideEffectSinksReady,
      clearCommitReady = prereqsUsable && state.clearCommitReady,
      rowFillCandidateValid = prereqsUsable && state.rowFillCandidateValid,
      lifecycleRowClearReady = prereqsUsable && state.lifecycleRowClearReady,
      residentPrereqsReady =
        prereqsUsable &&
          state.sideEffectSinksReady &&
          state.clearCommitReady &&
          state.rowFillCandidateValid &&
          state.lifecycleRowClearReady,
      blockedByDisabled = !in.enable && (in.captureEnable || state.snapshotValid),
      blockedByFlush = in.enable && in.flush && (in.captureEnable || state.snapshotValid),
      blockedByCaptureDisabled = active && !in.captureEnable && in.slotOccupied,
      blockedByNoSlot = active && in.captureEnable && !in.slotOccupied,
      blockedByInvalidSlotIdentity = captureCandidate && !slotIdentityValid,
      blockedByNoSnapshot = active && in.slotOccupied && !state.snapshotValid,
      blockedByIdentityMismatch = active && in.slotOccupied && state.snapshotValid && !snapshotMatchesSlot,
      blockedByNoSideEffectSink = prereqsUsable && !state.sideEffectSinksReady,
      blockedByNoClearCommit = prereqsUsable && state.sideEffectSinksReady && !state.clearCommitReady,
      blockedByNoRowFillCandidate =
        prereqsUsable && state.sideEffectSinksReady && state.clearCommitReady && !state.rowFillCandidateValid,
      blockedByNoLifecycleRow =
        prereqsUsable && state.sideEffectSinksReady && state.clearCommitReady && state.rowFillCandidateValid &&
          !state.lifecycleRowClearReady,
      next = next)
  }
}

class LoadReplayReturnPipeW2AtomicPrereqSnapshotSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2AtomicPrereqSnapshotReference._

  test("captured prerequisites become usable for the same resident W2 identity") {
    val captured = step(State(), Inputs())
    val usable = step(captured.next, Inputs(captureEnable = false))

    assert(captured.captureAccepted)
    assert(!captured.snapshotValid)
    assert(usable.snapshotValid)
    assert(usable.snapshotMatchesSlot)
    assert(usable.prereqsUsable)
    assert(usable.sideEffectSinksReady)
    assert(usable.clearCommitReady)
    assert(usable.rowFillCandidateValid)
    assert(usable.lifecycleRowClearReady)
    assert(usable.residentPrereqsReady)
  }

  test("snapshot blocks when the resident W2 identity changes") {
    val captured = step(State(), Inputs()).next
    val changed = step(captured, Inputs(captureEnable = false, slotRid = Id(value = 7)))

    assert(changed.snapshotValid)
    assert(!changed.snapshotMatchesSlot)
    assert(!changed.prereqsUsable)
    assert(changed.blockedByIdentityMismatch)
    assert(!changed.residentPrereqsReady)
  }

  test("flush clears the captured prerequisite snapshot") {
    val captured = step(State(), Inputs()).next
    val flushed = step(captured, Inputs(flush = true))
    val afterFlush = step(flushed.next, Inputs(captureEnable = false))

    assert(flushed.blockedByFlush)
    assert(!flushed.next.snapshotValid)
    assert(!afterFlush.snapshotValid)
    assert(afterFlush.blockedByNoSnapshot)
  }

  test("invalid resident identity is not captured") {
    val invalid = step(State(), Inputs(slotLoadLsId = Id(valid = false)))
    val next = step(invalid.next, Inputs(captureEnable = false))

    assert(invalid.captureCandidate)
    assert(!invalid.captureAccepted)
    assert(invalid.blockedByInvalidSlotIdentity)
    assert(!next.snapshotValid)
  }

  test("ordered prerequisite blockers expose the first missing captured condition") {
    val noSink = step(State(), Inputs(sideEffectSinksReadyIn = false)).next
    val noSinkUse = step(noSink, Inputs(captureEnable = false))
    assert(noSinkUse.blockedByNoSideEffectSink)
    assert(!noSinkUse.blockedByNoClearCommit)

    val noClear = step(State(), Inputs(clearCommitReadyIn = false)).next
    val noClearUse = step(noClear, Inputs(captureEnable = false))
    assert(!noClearUse.blockedByNoSideEffectSink)
    assert(noClearUse.blockedByNoClearCommit)
    assert(!noClearUse.blockedByNoRowFillCandidate)

    val noRow = step(State(), Inputs(rowFillCandidateValidIn = false)).next
    val noRowUse = step(noRow, Inputs(captureEnable = false))
    assert(noRowUse.blockedByNoRowFillCandidate)
    assert(!noRowUse.blockedByNoLifecycleRow)

    val noLifecycle = step(State(), Inputs(lifecycleRowClearReadyIn = false)).next
    val noLifecycleUse = step(noLifecycle, Inputs(captureEnable = false))
    assert(noLifecycleUse.blockedByNoLifecycleRow)
  }

  test("Chisel LoadReplayReturnPipeW2AtomicPrereqSnapshot elaborates registered state") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2AtomicPrereqSnapshot)

    assert(sv.contains("module LoadReplayReturnPipeW2AtomicPrereqSnapshot"))
    assert(sv.contains("snapshotValidReg"))
    assert(sv.contains("sideEffectSinksReadyReg"))
    assert(sv.contains("io_residentPrereqsReady"))
  }
}
