package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedBfuResolvedBodyEndPendingReference {
  final case class Event(headerPc: BigInt = 0, hsizeBytes: BigInt = 0, bodyEndPc: BigInt = 0)
  final case class Candidate(valid: Boolean = false, headerPc: BigInt = 0, hsizeBytes: BigInt = 0, bsizeBytes: BigInt = 0)
  final case class Inputs(
      flush: Boolean = false,
      capture: Boolean = false,
      captured: Event = Event(),
      candidate: Candidate = Candidate(),
      consume: Boolean = false)
  final case class State(pending: Boolean = false, event: Event = Event())
  final case class Result(
      state: State,
      runtimeValid: Boolean,
      comparable: Boolean,
      matchCandidate: Boolean,
      mismatchCandidate: Boolean,
      consumeFire: Boolean,
      dropMismatch: Boolean)

  private val PcMask = (BigInt(1) << 64) - 1

  def step(state: State, in: Inputs): Result = {
    val candidateBodyEnd = (in.candidate.headerPc + 2 + in.candidate.bsizeBytes) & PcMask
    val comparable = state.pending && in.candidate.valid
    val matchCandidate =
      comparable &&
        state.event.headerPc == in.candidate.headerPc &&
        state.event.hsizeBytes == in.candidate.hsizeBytes &&
        state.event.bodyEndPc == candidateBodyEnd
    val mismatchCandidate = comparable && !matchCandidate
    val runtimeValid = state.pending && matchCandidate
    val consumeFire = state.pending && in.consume
    val next =
      if (in.flush) State()
      else if (in.capture) State(pending = true, in.captured)
      else if (consumeFire || mismatchCandidate) State()
      else state

    Result(
      state = next,
      runtimeValid = runtimeValid,
      comparable = comparable,
      matchCandidate = matchCandidate,
      mismatchCandidate = mismatchCandidate,
      consumeFire = consumeFire,
      dropMismatch = mismatchCandidate)
  }
}

class ReducedBfuResolvedBodyEndPendingProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val flushValid = Input(Bool())
  val captureValid = Input(Bool())
  val captureHeaderPc = Input(UInt(p.pcWidth.W))
  val captureHSizeBytes = Input(UInt(p.pcWidth.W))
  val captureBodyEndPc = Input(UInt(p.pcWidth.W))
  val candidateValid = Input(Bool())
  val candidateHeaderPc = Input(UInt(p.pcWidth.W))
  val candidateHSizeBytes = Input(UInt(p.pcWidth.W))
  val candidateBSizeBytes = Input(UInt(p.pcWidth.W))
  val consumeValid = Input(Bool())

  val runtimeValid = Output(Bool())
  val pending = Output(Bool())
  val consumeFire = Output(Bool())
  val dropMismatch = Output(Bool())
  val candidateComparable = Output(Bool())
  val candidateMatch = Output(Bool())
  val candidateMismatch = Output(Bool())
}

class ReducedBfuResolvedBodyEndPendingProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuResolvedBodyEndPendingProbeIO(p))
  val pending = Module(new ReducedBfuResolvedBodyEndPending(p))

  pending.io.flushValid := io.flushValid
  pending.io.captureValid := io.captureValid
  pending.io.captureHeaderPc := io.captureHeaderPc
  pending.io.captureHSizeBytes := io.captureHSizeBytes
  pending.io.captureBodyEndPc := io.captureBodyEndPc
  pending.io.candidateValid := io.candidateValid
  pending.io.candidateHeaderPc := io.candidateHeaderPc
  pending.io.candidateHSizeBytes := io.candidateHSizeBytes
  pending.io.candidateBSizeBytes := io.candidateBSizeBytes
  pending.io.consumeValid := io.consumeValid

  io.runtimeValid := pending.io.runtimeValid
  io.pending := pending.io.pending
  io.consumeFire := pending.io.consumeFire
  io.dropMismatch := pending.io.dropMismatch
  io.candidateComparable := pending.io.candidateComparable
  io.candidateMatch := pending.io.candidateMatch
  io.candidateMismatch := pending.io.candidateMismatch
}

class ReducedBfuResolvedBodyEndPendingSpec extends AnyFunSuite {
  test("reference holds a runtime event until a matching candidate consumes it") {
    import ReducedBfuResolvedBodyEndPendingReference._
    val captured = Event(headerPc = BigInt("4000630c", 16), hsizeBytes = 0, bodyEndPc = BigInt("4000632e", 16))
    val candidate = Candidate(valid = true, headerPc = captured.headerPc, hsizeBytes = 0, bsizeBytes = 0x20)
    val afterCapture = step(State(), Inputs(capture = true, captured = captured))
    val selected = step(afterCapture.state, Inputs(candidate = candidate, consume = true))

    assert(afterCapture.state.pending)
    assert(!afterCapture.runtimeValid)
    assert(selected.runtimeValid)
    assert(selected.matchCandidate)
    assert(selected.consumeFire)
    assert(!selected.state.pending)
  }

  test("reference drops stale runtime feedback on candidate mismatch") {
    import ReducedBfuResolvedBodyEndPendingReference._
    val captured = Event(headerPc = 0x1000, hsizeBytes = 0, bodyEndPc = 0x1010)
    val candidate = Candidate(valid = true, headerPc = 0x1000, hsizeBytes = 0, bsizeBytes = 0x20)
    val afterCapture = step(State(), Inputs(capture = true, captured = captured))
    val dropped = step(afterCapture.state, Inputs(candidate = candidate))

    assert(!dropped.runtimeValid)
    assert(dropped.comparable)
    assert(dropped.mismatchCandidate)
    assert(dropped.dropMismatch)
    assert(!dropped.state.pending)
  }

  test("ReducedBfuResolvedBodyEndPending elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedBfuResolvedBodyEndPendingProbe(InterfaceParams()))

    assert(sv.contains("module ReducedBfuResolvedBodyEndPendingProbe"))
    assert(sv.contains("module ReducedBfuResolvedBodyEndPending"))
    assert(sv.contains("io_candidateMismatch"))
    assert(sv.contains("io_dropMismatch"))
  }
}
