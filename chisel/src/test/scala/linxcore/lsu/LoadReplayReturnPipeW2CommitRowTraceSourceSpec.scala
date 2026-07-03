package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2CommitRowTraceSourceReference {
  final case class Source(valid: Boolean = false, reg: Int = 0, data: BigInt = 0)
  final case class Result(
      active: Boolean,
      traceCandidateValid: Boolean,
      instructionMetadataReady: Boolean,
      sourceTraceReady: Boolean,
      traceReady: Boolean,
      instructionValid: Boolean,
      instructionRaw: BigInt,
      instructionLen: Int,
      sourceTraceValid: Boolean,
      source0: Source,
      source1: Source,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoSlot: Boolean,
      blockedByNoInstructionMetadata: Boolean,
      blockedByNoSourceTrace: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      slotOccupied: Boolean,
      instructionProviderValid: Boolean,
      instructionProviderRaw: BigInt,
      instructionProviderLen: Int,
      sourceTraceProviderValid: Boolean,
      source0Provider: Source = Source(),
      source1Provider: Source = Source()): Result = {
    val active = enable && !flush
    val traceCandidateValid = active && slotOccupied
    val providerMetadataReady = instructionProviderValid && instructionProviderLen != 0
    val instructionMetadataReady = traceCandidateValid && providerMetadataReady
    val sourceTraceReady = instructionMetadataReady && sourceTraceProviderValid
    val traceReady = sourceTraceReady
    val observedEvidence = slotOccupied || instructionProviderValid || sourceTraceProviderValid

    Result(
      active = active,
      traceCandidateValid = traceCandidateValid,
      instructionMetadataReady = instructionMetadataReady,
      sourceTraceReady = sourceTraceReady,
      traceReady = traceReady,
      instructionValid = instructionMetadataReady,
      instructionRaw = if (instructionMetadataReady) instructionProviderRaw else 0,
      instructionLen = if (instructionMetadataReady) instructionProviderLen else 0,
      sourceTraceValid = traceReady,
      source0 = if (traceReady) source0Provider else Source(),
      source1 = if (traceReady) source1Provider else Source(),
      blockedByDisabled = !enable && observedEvidence,
      blockedByFlush = enable && flush && observedEvidence,
      blockedByNoSlot = active && !slotOccupied,
      blockedByNoInstructionMetadata = traceCandidateValid && !providerMetadataReady,
      blockedByNoSourceTrace = instructionMetadataReady && !sourceTraceProviderValid)
  }
}

class LoadReplayReturnPipeW2CommitRowTraceSourceSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2CommitRowTraceSourceReference._

  test("passes instruction metadata and source trace when resident W2 slot evidence is present") {
    val result = LoadReplayReturnPipeW2CommitRowTraceSourceReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      instructionProviderValid = true,
      instructionProviderRaw = BigInt("123456789abc", 16),
      instructionProviderLen = 6,
      sourceTraceProviderValid = true,
      source0Provider = Source(valid = true, reg = 1, data = 42),
      source1Provider = Source(valid = true, reg = 2, data = 99))

    assert(result.active)
    assert(result.traceCandidateValid)
    assert(result.instructionMetadataReady)
    assert(result.sourceTraceReady)
    assert(result.traceReady)
    assert(result.instructionValid)
    assert(result.instructionRaw == BigInt("123456789abc", 16))
    assert(result.instructionLen == 6)
    assert(result.sourceTraceValid)
    assert(result.source0.valid)
    assert(result.source0.reg == 1)
    assert(result.source0.data == 42)
    assert(result.source1.valid)
    assert(!result.blockedByNoInstructionMetadata)
    assert(!result.blockedByNoSourceTrace)
  }

  test("blocks metadata until the instruction provider is valid and length is nonzero") {
    val missingProvider = LoadReplayReturnPipeW2CommitRowTraceSourceReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      instructionProviderValid = false,
      instructionProviderRaw = 0xabc,
      instructionProviderLen = 4,
      sourceTraceProviderValid = true)
    val zeroLength = LoadReplayReturnPipeW2CommitRowTraceSourceReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      instructionProviderValid = true,
      instructionProviderRaw = 0xabc,
      instructionProviderLen = 0,
      sourceTraceProviderValid = true)

    assert(!missingProvider.instructionValid)
    assert(!missingProvider.traceReady)
    assert(missingProvider.blockedByNoInstructionMetadata)
    assert(missingProvider.instructionRaw == 0)
    assert(!zeroLength.instructionValid)
    assert(!zeroLength.traceReady)
    assert(zeroLength.blockedByNoInstructionMetadata)
    assert(zeroLength.instructionLen == 0)
  }

  test("blocks source trace after instruction metadata is ready") {
    val result = LoadReplayReturnPipeW2CommitRowTraceSourceReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      instructionProviderValid = true,
      instructionProviderRaw = 0xabc,
      instructionProviderLen = 4,
      sourceTraceProviderValid = false,
      source0Provider = Source(valid = true, reg = 1, data = 42))

    assert(result.instructionMetadataReady)
    assert(result.instructionValid)
    assert(result.instructionRaw == 0xabc)
    assert(!result.sourceTraceValid)
    assert(!result.traceReady)
    assert(!result.source0.valid)
    assert(result.blockedByNoSourceTrace)
  }

  test("suppresses outputs when disabled flushed or no slot is present") {
    val disabled = LoadReplayReturnPipeW2CommitRowTraceSourceReference(
      enable = false,
      flush = false,
      slotOccupied = true,
      instructionProviderValid = true,
      instructionProviderRaw = 0xabc,
      instructionProviderLen = 4,
      sourceTraceProviderValid = true)
    val flushed = LoadReplayReturnPipeW2CommitRowTraceSourceReference(
      enable = true,
      flush = true,
      slotOccupied = true,
      instructionProviderValid = true,
      instructionProviderRaw = 0xabc,
      instructionProviderLen = 4,
      sourceTraceProviderValid = true)
    val empty = LoadReplayReturnPipeW2CommitRowTraceSourceReference(
      enable = true,
      flush = false,
      slotOccupied = false,
      instructionProviderValid = true,
      instructionProviderRaw = 0xabc,
      instructionProviderLen = 4,
      sourceTraceProviderValid = true)

    assert(disabled.blockedByDisabled)
    assert(!disabled.instructionValid)
    assert(flushed.blockedByFlush)
    assert(!flushed.sourceTraceValid)
    assert(empty.blockedByNoSlot)
    assert(!empty.traceCandidateValid)
    assert(!empty.traceReady)
  }

  test("Chisel LoadReplayReturnPipeW2CommitRowTraceSource elaborates trace-source diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2CommitRowTraceSource)

    assert(sv.contains("module LoadReplayReturnPipeW2CommitRowTraceSource"))
    assert(sv.contains("io_instructionMetadataReady"))
    assert(sv.contains("io_sourceTraceReady"))
    assert(sv.contains("io_traceReady"))
    assert(sv.contains("io_instructionRaw"))
    assert(sv.contains("io_source0_valid"))
    assert(sv.contains("io_blockedByNoInstructionMetadata"))
    assert(sv.contains("io_blockedByNoSourceTrace"))
  }
}
