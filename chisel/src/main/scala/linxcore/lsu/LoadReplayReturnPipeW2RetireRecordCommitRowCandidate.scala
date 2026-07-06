package linxcore.lsu

import chisel3._

import linxcore.commit.{CommitTraceParams, CommitTraceRow}

class LoadReplayReturnPipeW2RetireRecordCommitRowCandidateIO(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val rowFillEnable = Input(Bool())
  val recordValid = Input(Bool())
  val record = Input(new LoadReplayReturnLretEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))
  val instructionValid = Input(Bool())
  val instructionRaw = Input(UInt(traceParams.insnWidth.W))
  val instructionLen = Input(UInt(traceParams.lenWidth.W))

  val active = Output(Bool())
  val candidateValid = Output(Bool())
  val metadataReady = Output(Bool())
  val sourceTraceReady = Output(Bool())
  val sizeSupported = Output(Bool())
  val destinationGpr = Output(Bool())
  val rowFillCandidateValid = Output(Bool())
  val completeRowValid = Output(Bool())
  val completeRow = Output(new CommitTraceRow(traceParams))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoRecord = Output(Bool())
  val blockedByInvalidIdentity = Output(Bool())
  val blockedByNoInstructionMetadata = Output(Bool())
  val blockedByNoSourceTrace = Output(Bool())
  val blockedByInvalidSize = Output(Bool())
  val blockedByNoDestination = Output(Bool())
  val blockedByNonGprDestination = Output(Bool())
  val blockedByRowFillDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordCommitRowCandidate(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(addrWidth == traceParams.dataWidth, "retire-record commit rows require address width to match trace data width")
  require(pcWidth == traceParams.pcWidth, "retire-record commit rows require PC width to match trace PC width")
  require(dataWidth == traceParams.dataWidth, "retire-record commit rows require data width to match trace data width")

  val io = IO(new LoadReplayReturnPipeW2RetireRecordCommitRowCandidateIO(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth,
    traceParams
  ))

  private val physicalCandidate = Module(new LoadReplayReturnPipeW2CommitRowCandidate(
    idEntries = idEntries,
    sizeWidth = sizeWidth,
    archRegWidth = archRegWidth,
    physRegWidth = physRegWidth,
    traceParams = traceParams
  ))

  physicalCandidate.io.enable := io.enable
  physicalCandidate.io.flush := io.flush
  physicalCandidate.io.rowFillEnable := io.rowFillEnable
  physicalCandidate.io.slotOccupied := io.recordValid && io.record.valid
  physicalCandidate.io.slotTargetIsAgu := false.B
  physicalCandidate.io.slotTargetIsLda := true.B
  physicalCandidate.io.slotBid := io.record.bid
  physicalCandidate.io.slotGid := io.record.gid
  physicalCandidate.io.slotRid := io.record.rid
  physicalCandidate.io.slotPc := io.record.pc
  physicalCandidate.io.slotAddr := io.record.addr
  physicalCandidate.io.slotSize := io.record.size
  physicalCandidate.io.slotDst := io.record.dst
  physicalCandidate.io.slotData := io.record.data
  physicalCandidate.io.instructionValid := io.instructionValid
  physicalCandidate.io.instructionRaw := io.instructionRaw
  physicalCandidate.io.instructionLen := io.instructionLen
  physicalCandidate.io.sourceTraceValid := io.record.sourceTraceValid
  physicalCandidate.io.source0 := io.record.source0
  physicalCandidate.io.source1 := io.record.source1

  io.active := physicalCandidate.io.active
  io.candidateValid := physicalCandidate.io.candidateValid
  io.metadataReady := physicalCandidate.io.metadataReady
  io.sourceTraceReady := physicalCandidate.io.sourceTraceReady
  io.sizeSupported := physicalCandidate.io.sizeSupported
  io.destinationGpr := physicalCandidate.io.destinationGpr
  io.rowFillCandidateValid := physicalCandidate.io.rowFillCandidateValid
  io.completeRowValid := physicalCandidate.io.completeRowValid
  io.completeRow := physicalCandidate.io.completeRow
  io.blockedByDisabled := physicalCandidate.io.blockedByDisabled
  io.blockedByFlush := physicalCandidate.io.blockedByFlush
  io.blockedByNoRecord := physicalCandidate.io.blockedByNoSlot
  io.blockedByInvalidIdentity := physicalCandidate.io.blockedByInvalidIdentity
  io.blockedByNoInstructionMetadata := physicalCandidate.io.blockedByNoInstructionMetadata
  io.blockedByNoSourceTrace := physicalCandidate.io.blockedByNoSourceTrace
  io.blockedByInvalidSize := physicalCandidate.io.blockedByInvalidSize
  io.blockedByNoDestination := physicalCandidate.io.blockedByNoDestination
  io.blockedByNonGprDestination := physicalCandidate.io.blockedByNonGprDestination
  io.blockedByRowFillDisabled := physicalCandidate.io.blockedByRowFillDisabled
}
