package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedBfuPendingRuntimeBodyEndCandidateReference {
  final case class Pending(
      valid: Boolean = false,
      headerPc: BigInt = 0,
      hsizeBytes: BigInt = 0,
      bodyEndPc: BigInt = 0)
  final case class ActiveHeader(valid: Boolean = false, pc: BigInt = 0)
  final case class Replay(
      valid: Boolean = false,
      headerPc: BigInt = 0,
      hsizeBytes: BigInt = 0,
      bsizeBytes: BigInt = 0)
  final case class Result(
      candidateValid: Boolean,
      pendingWithoutActiveHeader: Boolean,
      activeHeaderMismatch: Boolean,
      replayComparable: Boolean,
      replayMatch: Boolean,
      replayHeaderMismatch: Boolean,
      replayHSizeMismatch: Boolean,
      replayBodyEndMismatch: Boolean,
      replayBodyEndPc: BigInt)

  private val PcMask = (BigInt(1) << 64) - 1

  def step(pending: Pending, active: ActiveHeader, replay: Replay = Replay()): Result = {
    val activeHeaderMatch = active.valid && pending.headerPc == active.pc
    val candidateValid = pending.valid && activeHeaderMatch
    val replayBodyEndPc = (replay.headerPc + 2 + replay.bsizeBytes) & PcMask
    val replayComparable = candidateValid && replay.valid
    val headerMatch = pending.headerPc == replay.headerPc
    val hsizeMatch = pending.hsizeBytes == replay.hsizeBytes
    val bodyEndMatch = pending.bodyEndPc == replayBodyEndPc
    Result(
      candidateValid = candidateValid,
      pendingWithoutActiveHeader = pending.valid && !active.valid,
      activeHeaderMismatch = pending.valid && active.valid && !activeHeaderMatch,
      replayComparable = replayComparable,
      replayMatch = replayComparable && headerMatch && hsizeMatch && bodyEndMatch,
      replayHeaderMismatch = replayComparable && !headerMatch,
      replayHSizeMismatch = replayComparable && !hsizeMatch,
      replayBodyEndMismatch = replayComparable && !bodyEndMatch,
      replayBodyEndPc = replayBodyEndPc)
  }
}

class ReducedBfuPendingRuntimeBodyEndCandidateProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val pendingValid = Input(Bool())
  val pendingHeaderPc = Input(UInt(p.pcWidth.W))
  val pendingHSizeBytes = Input(UInt(p.pcWidth.W))
  val pendingBodyEndPc = Input(UInt(p.pcWidth.W))
  val headerActive = Input(Bool())
  val activeHeaderPc = Input(UInt(p.pcWidth.W))
  val replayValid = Input(Bool())
  val replayHeaderPc = Input(UInt(p.pcWidth.W))
  val replayHSizeBytes = Input(UInt(p.pcWidth.W))
  val replayBSizeBytes = Input(UInt(p.pcWidth.W))

  val candidateValid = Output(Bool())
  val pendingWithoutActiveHeader = Output(Bool())
  val activeHeaderMismatch = Output(Bool())
  val replayComparable = Output(Bool())
  val replayMatch = Output(Bool())
  val replayBodyEndMismatch = Output(Bool())
}

class ReducedBfuPendingRuntimeBodyEndCandidateProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuPendingRuntimeBodyEndCandidateProbeIO(p))
  val candidate = Module(new ReducedBfuPendingRuntimeBodyEndCandidate(p))

  candidate.io.pendingValid := io.pendingValid
  candidate.io.pendingHeaderPc := io.pendingHeaderPc
  candidate.io.pendingHSizeBytes := io.pendingHSizeBytes
  candidate.io.pendingBodyEndPc := io.pendingBodyEndPc
  candidate.io.headerActive := io.headerActive
  candidate.io.activeHeaderPc := io.activeHeaderPc
  candidate.io.replayValid := io.replayValid
  candidate.io.replayHeaderPc := io.replayHeaderPc
  candidate.io.replayHSizeBytes := io.replayHSizeBytes
  candidate.io.replayBSizeBytes := io.replayBSizeBytes

  io.candidateValid := candidate.io.candidateValid
  io.pendingWithoutActiveHeader := candidate.io.pendingWithoutActiveHeader
  io.activeHeaderMismatch := candidate.io.activeHeaderMismatch
  io.replayComparable := candidate.io.replayComparable
  io.replayMatch := candidate.io.replayMatch
  io.replayBodyEndMismatch := candidate.io.replayBodyEndMismatch
}

class ReducedBfuPendingRuntimeBodyEndCandidateSpec extends AnyFunSuite {
  test("reference admits pending feedback when the active header matches") {
    import ReducedBfuPendingRuntimeBodyEndCandidateReference._
    val pending = Pending(valid = true, headerPc = BigInt("4000630c", 16), hsizeBytes = 0, bodyEndPc = BigInt("4000632e", 16))
    val replay = Replay(valid = true, headerPc = pending.headerPc, hsizeBytes = 0, bsizeBytes = 0x20)
    val result = step(pending, ActiveHeader(valid = true, pending.headerPc), replay)

    assert(result.candidateValid)
    assert(result.replayComparable)
    assert(result.replayMatch)
    assert(!result.replayBodyEndMismatch)
  }

  test("reference separates inactive and mismatched active headers") {
    import ReducedBfuPendingRuntimeBodyEndCandidateReference._
    val pending = Pending(valid = true, headerPc = 0x1000, hsizeBytes = 0, bodyEndPc = 0x1010)
    val inactive = step(pending, ActiveHeader(valid = false, pc = 0))
    val mismatch = step(pending, ActiveHeader(valid = true, pc = 0x2000))

    assert(!inactive.candidateValid)
    assert(inactive.pendingWithoutActiveHeader)
    assert(!inactive.activeHeaderMismatch)
    assert(!mismatch.candidateValid)
    assert(!mismatch.pendingWithoutActiveHeader)
    assert(mismatch.activeHeaderMismatch)
  }

  test("reference reports replay body-end mismatches without invalidating the candidate") {
    import ReducedBfuPendingRuntimeBodyEndCandidateReference._
    val pending = Pending(valid = true, headerPc = 0x1000, hsizeBytes = 0, bodyEndPc = 0x1010)
    val replay = Replay(valid = true, headerPc = 0x1000, hsizeBytes = 0, bsizeBytes = 0x20)
    val result = step(pending, ActiveHeader(valid = true, pc = 0x1000), replay)

    assert(result.candidateValid)
    assert(result.replayComparable)
    assert(!result.replayMatch)
    assert(result.replayBodyEndMismatch)
  }

  test("ReducedBfuPendingRuntimeBodyEndCandidate elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedBfuPendingRuntimeBodyEndCandidateProbe(InterfaceParams()))

    assert(sv.contains("module ReducedBfuPendingRuntimeBodyEndCandidateProbe"))
    assert(sv.contains("module ReducedBfuPendingRuntimeBodyEndCandidate"))
    assert(sv.contains("io_replayBodyEndMismatch"))
    assert(sv.contains("io_activeHeaderMismatch"))
  }
}
