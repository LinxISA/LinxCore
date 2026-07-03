package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnWritebackCandidateReference {
  final case class Dst(valid: Boolean, kind: Int, physTag: Int)

  final case class Result(
      candidateValid: Boolean,
      writeValid: Boolean,
      writeTag: Int,
      writeData: BigInt,
      ignoredNoDestination: Boolean,
      ignoredNonGprDestination: Boolean,
      blockedByDisabled: Boolean)

  private val NoneKind = 0
  private val GprKind = 1

  def apply(
      enable: Boolean,
      payloadValid: Boolean,
      dst: Dst,
      payloadData: BigInt): Result = {
    val candidateValid = enable && payloadValid
    val hasDestination = dst.valid && dst.kind != NoneKind
    val isGprDestination = hasDestination && dst.kind == GprKind
    val writeValid = candidateValid && isGprDestination

    Result(
      candidateValid = candidateValid,
      writeValid = writeValid,
      writeTag = if (writeValid) dst.physTag else 0,
      writeData = if (writeValid) payloadData else BigInt(0),
      ignoredNoDestination = candidateValid && !hasDestination,
      ignoredNonGprDestination = candidateValid && hasDestination && !isGprDestination,
      blockedByDisabled = !enable && payloadValid)
  }
}

class LoadReplayReturnWritebackCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnWritebackCandidateReference._

  test("emits a GPR writeback candidate from a valid payload destination") {
    val result = LoadReplayReturnWritebackCandidateReference(
      enable = true,
      payloadValid = true,
      dst = Dst(valid = true, kind = 1, physTag = 42),
      payloadData = BigInt("8877665544332211", 16))

    assert(result.candidateValid)
    assert(result.writeValid)
    assert(result.writeTag == 42)
    assert(result.writeData == BigInt("8877665544332211", 16))
    assert(!result.ignoredNoDestination)
    assert(!result.ignoredNonGprDestination)
  }

  test("reports missing and non-GPR destinations without producing a write") {
    val missing = LoadReplayReturnWritebackCandidateReference(
      enable = true,
      payloadValid = true,
      dst = Dst(valid = false, kind = 0, physTag = 42),
      payloadData = 0x1234)
    val local = LoadReplayReturnWritebackCandidateReference(
      enable = true,
      payloadValid = true,
      dst = Dst(valid = true, kind = 2, physTag = 7),
      payloadData = 0x5678)

    assert(missing.candidateValid)
    assert(!missing.writeValid)
    assert(missing.ignoredNoDestination)
    assert(!missing.ignoredNonGprDestination)
    assert(local.candidateValid)
    assert(!local.writeValid)
    assert(!local.ignoredNoDestination)
    assert(local.ignoredNonGprDestination)
  }

  test("reports disabled payloads and suppresses stale write fields") {
    val disabled = LoadReplayReturnWritebackCandidateReference(
      enable = false,
      payloadValid = true,
      dst = Dst(valid = true, kind = 1, physTag = 42),
      payloadData = 0x1234)
    val empty = LoadReplayReturnWritebackCandidateReference(
      enable = true,
      payloadValid = false,
      dst = Dst(valid = true, kind = 1, physTag = 42),
      payloadData = 0x1234)

    assert(!disabled.candidateValid)
    assert(!disabled.writeValid)
    assert(disabled.blockedByDisabled)
    assert(disabled.writeTag == 0)
    assert(disabled.writeData == 0)
    assert(!empty.candidateValid)
    assert(!empty.writeValid)
    assert(!empty.blockedByDisabled)
  }

  test("Chisel LoadReplayReturnWritebackCandidate elaborates writeback diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnWritebackCandidate)

    assert(sv.contains("module LoadReplayReturnWritebackCandidate"))
    assert(sv.contains("io_payloadDst_valid"))
    assert(sv.contains("io_writeValid"))
    assert(sv.contains("io_writeTag"))
    assert(sv.contains("io_ignoredNonGprDestination"))
  }
}
