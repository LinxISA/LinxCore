package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedBfuPromotedRuntimeBodyEndOracleReference {
  final case class Event(headerPc: BigInt = 0, hsizeBytes: BigInt = 0, bodyEndPc: BigInt = 0)
  final case class Replay(
      valid: Boolean = false,
      headerPc: BigInt = 0,
      hsizeBytes: BigInt = 0,
      bsizeBytes: BigInt = 0)
  final case class Inputs(
      flush: Boolean = false,
      promote: Boolean = false,
      promoted: Event = Event(),
      replay: Replay = Replay())
  final case class State(pending: Boolean = false, event: Event = Event())
  final case class Result(
      state: State,
      captureFire: Boolean,
      overwritePending: Boolean,
      replayComparable: Boolean,
      replayMatch: Boolean,
      replayHeaderMismatch: Boolean,
      replayHSizeMismatch: Boolean,
      replayBodyEndMismatch: Boolean,
      replayBodyEndPc: BigInt)

  private val PcMask = (BigInt(1) << 64) - 1

  def step(state: State, in: Inputs): Result = {
    val replayBodyEndPc = (in.replay.headerPc + 2 + in.replay.bsizeBytes) & PcMask
    val compareAllowed = !in.flush
    val pendingComparable = compareAllowed && state.pending && in.replay.valid
    val immediateComparable = compareAllowed && !state.pending && in.promote && in.replay.valid
    val replayComparable = pendingComparable || immediateComparable
    val compare = if (state.pending) state.event else in.promoted
    val headerMatch = compare.headerPc == in.replay.headerPc
    val hsizeMatch = compare.hsizeBytes == in.replay.hsizeBytes
    val bodyEndMatch = compare.bodyEndPc == replayBodyEndPc
    val replayMatch = replayComparable && headerMatch && hsizeMatch && bodyEndMatch
    val captureAfterPendingMatch = in.promote && pendingComparable && replayMatch
    val captureIntoEmpty = in.promote && !state.pending && !immediateComparable
    val captureFire = !in.flush && (captureAfterPendingMatch || captureIntoEmpty)
    val overwritePending = !in.flush && state.pending && in.promote && !pendingComparable
    val next =
      if (in.flush) State()
      else if (captureFire) State(pending = true, in.promoted)
      else if (pendingComparable) State()
      else state

    Result(
      state = next,
      captureFire = captureFire,
      overwritePending = overwritePending,
      replayComparable = replayComparable,
      replayMatch = replayMatch,
      replayHeaderMismatch = replayComparable && !headerMatch,
      replayHSizeMismatch = replayComparable && !hsizeMatch,
      replayBodyEndMismatch = replayComparable && !bodyEndMatch,
      replayBodyEndPc = replayBodyEndPc)
  }
}

class ReducedBfuPromotedRuntimeBodyEndOracleProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val flushValid = Input(Bool())
  val promoteValid = Input(Bool())
  val promoteHeaderPc = Input(UInt(p.pcWidth.W))
  val promoteHSizeBytes = Input(UInt(p.pcWidth.W))
  val promoteBodyEndPc = Input(UInt(p.pcWidth.W))
  val replayValid = Input(Bool())
  val replayHeaderPc = Input(UInt(p.pcWidth.W))
  val replayHSizeBytes = Input(UInt(p.pcWidth.W))
  val replayBSizeBytes = Input(UInt(p.pcWidth.W))

  val pending = Output(Bool())
  val captureFire = Output(Bool())
  val overwritePending = Output(Bool())
  val replayComparable = Output(Bool())
  val replayMatch = Output(Bool())
  val replayBodyEndMismatch = Output(Bool())
}

class ReducedBfuPromotedRuntimeBodyEndOracleProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuPromotedRuntimeBodyEndOracleProbeIO(p))
  val oracle = Module(new ReducedBfuPromotedRuntimeBodyEndOracle(p))

  oracle.io.flushValid := io.flushValid
  oracle.io.promoteValid := io.promoteValid
  oracle.io.promoteHeaderPc := io.promoteHeaderPc
  oracle.io.promoteHSizeBytes := io.promoteHSizeBytes
  oracle.io.promoteBodyEndPc := io.promoteBodyEndPc
  oracle.io.replayValid := io.replayValid
  oracle.io.replayHeaderPc := io.replayHeaderPc
  oracle.io.replayHSizeBytes := io.replayHSizeBytes
  oracle.io.replayBSizeBytes := io.replayBSizeBytes

  io.pending := oracle.io.pending
  io.captureFire := oracle.io.captureFire
  io.overwritePending := oracle.io.overwritePending
  io.replayComparable := oracle.io.replayComparable
  io.replayMatch := oracle.io.replayMatch
  io.replayBodyEndMismatch := oracle.io.replayBodyEndMismatch
}

class ReducedBfuPromotedRuntimeBodyEndOracleSpec extends AnyFunSuite {
  test("reference stores a promoted runtime event until replay proves it") {
    import ReducedBfuPromotedRuntimeBodyEndOracleReference._
    val promoted = Event(headerPc = BigInt("4000630c", 16), hsizeBytes = 0, bodyEndPc = BigInt("4000632e", 16))
    val replay = Replay(valid = true, headerPc = promoted.headerPc, hsizeBytes = 0, bsizeBytes = 0x20)
    val captured = step(State(), Inputs(promote = true, promoted = promoted))
    val checked = step(captured.state, Inputs(replay = replay))

    assert(captured.captureFire)
    assert(captured.state.pending)
    assert(checked.replayComparable)
    assert(checked.replayMatch)
    assert(!checked.state.pending)
  }

  test("reference compares same-cycle replay without retaining a matched event") {
    import ReducedBfuPromotedRuntimeBodyEndOracleReference._
    val promoted = Event(headerPc = 0x1000, hsizeBytes = 0, bodyEndPc = 0x1022)
    val replay = Replay(valid = true, headerPc = 0x1000, hsizeBytes = 0, bsizeBytes = 0x20)
    val result = step(State(), Inputs(promote = true, promoted = promoted, replay = replay))

    assert(result.replayComparable)
    assert(result.replayMatch)
    assert(!result.captureFire)
    assert(!result.state.pending)
  }

  test("reference reports replay mismatch and clears the stale pending event") {
    import ReducedBfuPromotedRuntimeBodyEndOracleReference._
    val promoted = Event(headerPc = 0x1000, hsizeBytes = 0, bodyEndPc = 0x1010)
    val replay = Replay(valid = true, headerPc = 0x1000, hsizeBytes = 0, bsizeBytes = 0x20)
    val captured = step(State(), Inputs(promote = true, promoted = promoted))
    val mismatched = step(captured.state, Inputs(replay = replay))

    assert(mismatched.replayComparable)
    assert(!mismatched.replayMatch)
    assert(mismatched.replayBodyEndMismatch)
    assert(!mismatched.state.pending)
  }

  test("reference reports overwrite when a second promotion arrives before replay") {
    import ReducedBfuPromotedRuntimeBodyEndOracleReference._
    val first = Event(headerPc = 0x1000, hsizeBytes = 0, bodyEndPc = 0x1022)
    val second = Event(headerPc = 0x2000, hsizeBytes = 0, bodyEndPc = 0x2022)
    val captured = step(State(), Inputs(promote = true, promoted = first))
    val overwrite = step(captured.state, Inputs(promote = true, promoted = second))

    assert(overwrite.overwritePending)
    assert(!overwrite.captureFire)
    assert(overwrite.state.pending)
    assert(overwrite.state.event == first)
  }

  test("ReducedBfuPromotedRuntimeBodyEndOracle elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedBfuPromotedRuntimeBodyEndOracleProbe(InterfaceParams()))

    assert(sv.contains("module ReducedBfuPromotedRuntimeBodyEndOracleProbe"))
    assert(sv.contains("module ReducedBfuPromotedRuntimeBodyEndOracle"))
    assert(sv.contains("io_overwritePending"))
    assert(sv.contains("io_replayBodyEndMismatch"))
  }
}
