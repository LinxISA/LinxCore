package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.common.{CoreParams, ScalarLsuParams}
import org.scalatest.funsuite.AnyFunSuite

object ScalarLSUMDBPathReference {
  sealed trait Recovery
  case object Inner extends Recovery
  case object Nuke extends Recovery

  def recovery(loadBid: Int, storeBid: Int): Recovery =
    if (loadBid == storeBid) Inner else Nuke

  def drainTargetOrder(mask: BigInt, entries: Int): Seq[Int] = {
    require(mask >= 0 && mask < (BigInt(1) << entries))
    (0 until entries).filter(index => ((mask >> index) & 1) == 1)
  }

  final case class LookupState(valid: Boolean, mutationPending: Boolean)

  def lookupStep(state: LookupState, mutationAccepted: Boolean): LookupState =
    if (!state.valid) state
    else if (!state.mutationPending || mutationAccepted) LookupState(valid = false, mutationPending = false)
    else state

  final case class RecoveryState(pending: Boolean, payload: Option[Recovery])

  def recoveryStep(
      state: RecoveryState,
      conflict: Option[Recovery],
      outerReady: Boolean): RecoveryState = {
    val consumed = state.pending && outerReady
    conflict match {
      case Some(payload) if !state.pending || consumed => RecoveryState(pending = true, Some(payload))
      case _ if consumed => RecoveryState(pending = false, None)
      case _ => state
    }
  }
}

class ScalarLSUMDBPathSpec extends AnyFunSuite {
  import ScalarLSUMDBPathReference._

  private val lsu = ScalarLsuParams(
    stqEntries = 8,
    commitQueueEntries = 4,
    commitIssueWidth = 1,
    scbEntries = 4,
    liqEntries = 4,
    resolveQueueEntries = 8,
    mdbSsitEntries = 8,
    mdbCommandQueueEntries = 4,
    mdbOutputQueueEntries = 4,
    mdbWaitPlanQueueEntries = 4,
    mdbFailedWaitTimeoutCycles = 4,
    mapQDepth = 8
  )
  private val core = CoreParams(robEntries = 32, commitWidth = 2, scalarLsu = lsu)

  test("classifies Linx inner and nuke recovery from block identity") {
    assert(recovery(loadBid = 5, storeBid = 5) == Inner)
    assert(recovery(loadBid = 5, storeBid = 3) == Nuke)
  }

  test("retains a multi-row store wait plan and drains every target deterministically") {
    assert(drainTargetOrder(mask = BigInt("1011", 2), entries = 4) == Seq(0, 1, 3))
    assert(drainTargetOrder(mask = 0, entries = 4).isEmpty)
  }

  test("holds an MDB lookup result until its LIQ mutation is accepted") {
    val blocked = lookupStep(LookupState(valid = true, mutationPending = true), mutationAccepted = false)
    val accepted = lookupStep(blocked, mutationAccepted = true)

    assert(blocked.valid)
    assert(blocked.mutationPending)
    assert(!accepted.valid)
    assert(!accepted.mutationPending)
  }

  test("retains typed recovery until the outer owner accepts it") {
    val accepted = recoveryStep(RecoveryState(false, None), Some(Nuke), outerReady = false)
    val held = recoveryStep(accepted, None, outerReady = false)
    val consumed = recoveryStep(held, None, outerReady = true)

    assert(accepted == RecoveryState(pending = true, Some(Nuke)))
    assert(held == accepted)
    assert(consumed == RecoveryState(pending = false, None))
  }

  test("ScalarLSUMDBPath elaborates conflict, SSIT, failed-wait delete, wait-plan, and typed flush ownership") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarLSUMDBPath(core))

    assert(sv.contains("module ScalarLSUMDBPath"))
    assert(sv.contains("module MDBConflictDetect"))
    assert(sv.contains("module MDBQueueFanout"))
    assert(sv.contains("module MDBSSIT"))
    assert(sv.contains("module LoadReplayMdbLookupWaitPlan"))
    assert(sv.contains("module LoadWaitStoreTimeout"))
    assert(sv.contains("io_mutationAccepted"))
    assert(sv.contains("io_conflictFlush_req_typ"))
    assert(sv.contains("io_recoveryReady"))
    assert(sv.contains("io_recoveryFlush_req_typ"))
    assert(sv.contains("io_recoveryPending"))
    assert(sv.contains("io_waitPlanTargetMask"))
    assert(sv.contains("io_transientEmpty"))
    assert(sv.contains("io_protocolError"))
  }

  test("generated-RTL probe elaborates the bounded canonical MDB runtime surface") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarLSUMDBPathProbe)

    assert(sv.contains("module ScalarLSUMDBPathProbe"))
    assert(sv.contains("io_innerFlush"))
    assert(sv.contains("io_nukeFlush"))
    assert(sv.contains("io_recoveryValid"))
    assert(sv.contains("io_recoveryAccepted"))
    assert(sv.contains("io_lookupWaitMutation"))
    assert(sv.contains("io_failedWaitReleaseAccepted"))
    assert(sv.contains("io_deleteProcessed"))
    assert(sv.contains("io_integratedWaitStoreMask"))
    assert(sv.contains("io_integratedFailedWaitReleaseAccepted"))
    assert(sv.contains("io_integratedProtocolError"))
    assert(sv.contains("io_oneCycleTimeoutValid"))
  }
}
