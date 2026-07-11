package linxcore.recovery

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object RecoveryCleanupControlReference {
  import FullBidRecoveryBridgeReference._

  final case class Intent(
      globalFlush: Boolean,
      globalReplay: Boolean,
      peScopedReplay: Boolean,
      bctrlFlush: Boolean,
      bctrlReplay: Boolean,
      bctrlSimtRecovered: Boolean,
      renameFlush: Boolean,
      renameReplay: Boolean,
      backendFlush: Boolean,
      reportQueueFlush: Boolean,
      frontendRestart: Boolean,
      robPrune: Boolean,
      blockFlush: Boolean,
      blockFlushInclusive: Boolean,
      peFanoutAll: Boolean,
      peFanoutSingle: Boolean,
      vectorReplay: Boolean,
      vectorFlush: Boolean,
      mtcReplay: Boolean,
      mtcFlush: Boolean,
      lsuFlush: Boolean,
      stqFlush: Boolean,
      tileFlush: Boolean)

  sealed trait RefExecEngine
  case object Scalar extends RefExecEngine
  case object Simt extends RefExecEngine
  case object Mem extends RefExecEngine
  case object IexNumOrHigher extends RefExecEngine

  def isFlushType(typ: RefFlushType): Boolean =
    typ == MissPredFlush || typ == NukeFlush || typ == InnerFlush || typ == FastFlush

  def isBasedOnPE(typ: RefFlushType, execEngine: RefExecEngine): Boolean =
    typ == PeReplay || execEngine != Scalar

  def isBasedOnThread(execEngine: RefExecEngine): Boolean =
    execEngine != Scalar

  def simtReplay(execEngine: RefExecEngine): Boolean =
    execEngine == Simt || execEngine == IexNumOrHigher

  def mtcReplay(execEngine: RefExecEngine): Boolean =
    execEngine == Mem

  def classify(typ: RefFlushType, execEngine: RefExecEngine): Intent = {
    val flushType = isFlushType(typ)
    val peScoped = isBasedOnPE(typ, execEngine) || isBasedOnThread(execEngine)
    val globalFlush = flushType && !peScoped
    val globalReplay = !flushType && !peScoped
    val peScopedReplay = peScoped
    val simt = simtReplay(execEngine)
    val mtc = mtcReplay(execEngine)
    val singlePe = simt || mtc || isBasedOnPE(typ, execEngine)
    val simtRecovered = peScopedReplay && (simt || mtc) && typ != SimtInnerFlush

    Intent(
      globalFlush = globalFlush,
      globalReplay = globalReplay,
      peScopedReplay = peScopedReplay,
      bctrlFlush = globalFlush,
      bctrlReplay = globalReplay || peScopedReplay,
      bctrlSimtRecovered = simtRecovered,
      renameFlush = globalFlush,
      renameReplay = globalReplay || peScopedReplay,
      backendFlush = true,
      reportQueueFlush = true,
      frontendRestart = globalFlush,
      robPrune = true,
      blockFlush = globalFlush,
      blockFlushInclusive = globalFlush && typ == MissPredFlush,
      peFanoutAll = !singlePe,
      peFanoutSingle = singlePe,
      vectorReplay = simt,
      vectorFlush = !simt,
      mtcReplay = mtc,
      mtcFlush = !simt && !mtc,
      lsuFlush = true,
      stqFlush = true,
      tileFlush = true
    )
  }
}

class RecoveryCleanupControlSpec extends AnyFunSuite {
  import FullBidRecoveryBridgeReference._
  import RecoveryCleanupControlReference._

  test("global miss-predict flush drives BCTRL, rename, frontend, backend, ROB, and all-PE cleanup") {
    val intent = classify(MissPredFlush, Scalar)

    assert(intent.globalFlush)
    assert(!intent.globalReplay)
    assert(!intent.peScopedReplay)
    assert(intent.bctrlFlush)
    assert(!intent.bctrlReplay)
    assert(intent.renameFlush)
    assert(!intent.renameReplay)
    assert(intent.frontendRestart)
    assert(intent.backendFlush)
    assert(intent.reportQueueFlush)
    assert(intent.robPrune)
    assert(intent.blockFlush)
    assert(intent.blockFlushInclusive)
    assert(intent.peFanoutAll)
    assert(!intent.peFanoutSingle)
    assert(intent.lsuFlush)
    assert(intent.stqFlush)
  }

  test("scalar fast replay uses BCTRL and rename replay without frontend restart") {
    val intent = classify(FastReplay, Scalar)

    assert(!intent.globalFlush)
    assert(intent.globalReplay)
    assert(!intent.bctrlFlush)
    assert(intent.bctrlReplay)
    assert(!intent.renameFlush)
    assert(intent.renameReplay)
    assert(!intent.frontendRestart)
    assert(!intent.blockFlush)
    assert(!intent.blockFlushInclusive)
    assert(intent.backendFlush)
    assert(intent.robPrune)
    assert(intent.peFanoutAll)
  }

  test("PE replay is PE-scoped and fans out to one PE") {
    val intent = classify(PeReplay, Scalar)

    assert(intent.peScopedReplay)
    assert(!intent.globalFlush)
    assert(!intent.globalReplay)
    assert(intent.bctrlReplay)
    assert(intent.renameReplay)
    assert(intent.peFanoutSingle)
    assert(!intent.peFanoutAll)
    assert(!intent.frontendRestart)
  }

  test("SIMT inner flush replays vector backend without BROB SIMT recovery sweep") {
    val intent = classify(SimtInnerFlush, Simt)

    assert(intent.peScopedReplay)
    assert(intent.vectorReplay)
    assert(!intent.vectorFlush)
    assert(intent.peFanoutSingle)
    assert(!intent.bctrlSimtRecovered)
    assert(intent.bctrlReplay)
    assert(intent.renameReplay)
  }

  test("MEM replay targets MTC replay hooks and the single PE fanout path") {
    val intent = classify(PeReplay, Mem)

    assert(intent.peScopedReplay)
    assert(intent.mtcReplay)
    assert(!intent.mtcFlush)
    assert(intent.bctrlSimtRecovered)
    assert(intent.peFanoutSingle)
  }

  test("Chisel RecoveryCleanupControl elaborates registered cleanup intent outputs") {
    val sv = ChiselStage.emitSystemVerilog(new RecoveryCleanupControl(entries = 8, bidWidth = 16))

    assert(sv.contains("module RecoveryCleanupControl"))
    assert(sv.contains("FullBidRecoveryBridge"))
    assert(sv.contains("io_reqReady"))
    assert(sv.contains("io_ringReqReady"))
    assert(sv.contains("io_ringAccepted"))
    assert(sv.contains("io_intent_bctrlFlushValid"))
    assert(sv.contains("io_intent_blockFlushInclusive"))
    assert(sv.contains("io_intent_robPruneValid"))
    assert(sv.contains("io_intent_frontendRestartValid"))
    assert(sv.contains("pendingValid"))
  }
}
