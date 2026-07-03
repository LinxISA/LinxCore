package linxcore.lsu

import chisel3._

import linxcore.commit.{CommitOperandTrace, CommitTraceParams}

class LoadReplayReturnPipeW2CommitRowTraceSourceIO(
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val slotOccupied = Input(Bool())
  val instructionProviderValid = Input(Bool())
  val instructionProviderRaw = Input(UInt(traceParams.insnWidth.W))
  val instructionProviderLen = Input(UInt(traceParams.lenWidth.W))
  val sourceTraceProviderValid = Input(Bool())
  val source0Provider = Input(new CommitOperandTrace(traceParams))
  val source1Provider = Input(new CommitOperandTrace(traceParams))

  val active = Output(Bool())
  val traceCandidateValid = Output(Bool())
  val instructionMetadataReady = Output(Bool())
  val sourceTraceReady = Output(Bool())
  val traceReady = Output(Bool())
  val instructionValid = Output(Bool())
  val instructionRaw = Output(UInt(traceParams.insnWidth.W))
  val instructionLen = Output(UInt(traceParams.lenWidth.W))
  val sourceTraceValid = Output(Bool())
  val source0 = Output(new CommitOperandTrace(traceParams))
  val source1 = Output(new CommitOperandTrace(traceParams))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByNoInstructionMetadata = Output(Bool())
  val blockedByNoSourceTrace = Output(Bool())
}

class LoadReplayReturnPipeW2CommitRowTraceSource(
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  val io = IO(new LoadReplayReturnPipeW2CommitRowTraceSourceIO(traceParams))

  val active = io.enable && !io.flush
  val traceCandidateValid = active && io.slotOccupied
  val providerMetadataReady = io.instructionProviderValid && (io.instructionProviderLen =/= 0.U)
  val instructionMetadataReady = traceCandidateValid && providerMetadataReady
  val sourceTraceReady = instructionMetadataReady && io.sourceTraceProviderValid
  val traceReady = sourceTraceReady
  val observedEvidence = io.slotOccupied || io.instructionProviderValid || io.sourceTraceProviderValid

  io.active := active
  io.traceCandidateValid := traceCandidateValid
  io.instructionMetadataReady := instructionMetadataReady
  io.sourceTraceReady := sourceTraceReady
  io.traceReady := traceReady
  io.instructionValid := instructionMetadataReady
  io.instructionRaw := Mux(instructionMetadataReady, io.instructionProviderRaw, 0.U)
  io.instructionLen := Mux(instructionMetadataReady, io.instructionProviderLen, 0.U)
  io.sourceTraceValid := traceReady
  io.source0 := 0.U.asTypeOf(io.source0)
  io.source1 := 0.U.asTypeOf(io.source1)
  when(traceReady) {
    io.source0 := io.source0Provider
    io.source1 := io.source1Provider
  }
  io.blockedByDisabled := !io.enable && observedEvidence
  io.blockedByFlush := io.enable && io.flush && observedEvidence
  io.blockedByNoSlot := active && !io.slotOccupied
  io.blockedByNoInstructionMetadata := traceCandidateValid && !providerMetadataReady
  io.blockedByNoSourceTrace := instructionMetadataReady && !io.sourceTraceProviderValid
}
