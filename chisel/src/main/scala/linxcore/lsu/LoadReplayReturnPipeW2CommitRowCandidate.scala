package linxcore.lsu

import chisel3._

import linxcore.commit.{CommitOperandTrace, CommitTraceParams, CommitTraceRow}
import linxcore.common.DestinationKind
import linxcore.rob.ROBID

class LoadReplayReturnPipeW2CommitRowCandidateIO(
    val idEntries: Int = 16,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val rowFillEnable = Input(Bool())
  val slotOccupied = Input(Bool())
  val slotTargetIsAgu = Input(Bool())
  val slotTargetIsLda = Input(Bool())
  val slotBid = Input(new ROBID(idEntries))
  val slotGid = Input(new ROBID(idEntries))
  val slotRid = Input(new ROBID(idEntries))
  val slotPc = Input(UInt(traceParams.pcWidth.W))
  val slotAddr = Input(UInt(traceParams.dataWidth.W))
  val slotSize = Input(UInt(sizeWidth.W))
  val slotDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))
  val slotData = Input(UInt(traceParams.dataWidth.W))
  val instructionValid = Input(Bool())
  val instructionRaw = Input(UInt(traceParams.insnWidth.W))
  val instructionLen = Input(UInt(traceParams.lenWidth.W))
  val sourceTraceValid = Input(Bool())
  val source0 = Input(new CommitOperandTrace(traceParams))
  val source1 = Input(new CommitOperandTrace(traceParams))

  val active = Output(Bool())
  val candidateValid = Output(Bool())
  val targetValid = Output(Bool())
  val identityValid = Output(Bool())
  val metadataReady = Output(Bool())
  val sourceTraceReady = Output(Bool())
  val sizeSupported = Output(Bool())
  val destinationGpr = Output(Bool())
  val rowFillCandidateValid = Output(Bool())
  val completeRowValid = Output(Bool())
  val completeRow = Output(new CommitTraceRow(traceParams))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByInvalidTarget = Output(Bool())
  val blockedByInvalidIdentity = Output(Bool())
  val blockedByNoInstructionMetadata = Output(Bool())
  val blockedByNoSourceTrace = Output(Bool())
  val blockedByInvalidSize = Output(Bool())
  val blockedByNoDestination = Output(Bool())
  val blockedByNonGprDestination = Output(Bool())
  val blockedByRowFillDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2CommitRowCandidate(
    val idEntries: Int = 16,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(sizeWidth > 0, "sizeWidth must be positive")
  require(archRegWidth > 0, "archRegWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")
  require(traceParams.dataWidth == 64, "replay W2 commit rows currently model 64-bit scalar load data")

  private val identityWidth = 32

  val io = IO(new LoadReplayReturnPipeW2CommitRowCandidateIO(
    idEntries,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    traceParams
  ))

  private def fitReg(tag: UInt): UInt =
    tag.pad(traceParams.regWidth)(traceParams.regWidth - 1, 0)

  private def idValue(id: ROBID): UInt =
    id.value.pad(identityWidth)(identityWidth - 1, 0)

  val active = io.enable && !io.flush
  val candidateValid = active && io.slotOccupied
  val targetValid = io.slotTargetIsAgu ^ io.slotTargetIsLda
  val identityValid = io.slotBid.valid && io.slotGid.valid && io.slotRid.valid
  val metadataReady = io.instructionValid && (io.instructionLen =/= 0.U)
  val sourceTraceReady = io.sourceTraceValid
  val sizeSupported =
    (io.slotSize === 1.U) ||
      (io.slotSize === 2.U) ||
      (io.slotSize === 4.U) ||
      (io.slotSize === 8.U)
  val hasDestination = io.slotDst.valid && (io.slotDst.kind =/= DestinationKind.None)
  val destinationGpr = hasDestination && (io.slotDst.kind === DestinationKind.Gpr)
  val rowFillCandidateValid =
    candidateValid &&
      targetValid &&
      identityValid &&
      metadataReady &&
      sourceTraceReady &&
      sizeSupported &&
      destinationGpr
  val completeRowValid = rowFillCandidateValid && io.rowFillEnable

  val row = Wire(new CommitTraceRow(traceParams))
  row := 0.U.asTypeOf(row)
  row.valid := completeRowValid
  row.identity.bid := idValue(io.slotBid)
  row.identity.gid := idValue(io.slotGid)
  row.identity.rid := idValue(io.slotRid)
  row.rob.valid := io.slotRid.valid
  row.rob.wrap := io.slotRid.wrap
  row.rob.value := io.slotRid.value
  row.pc := io.slotPc
  row.insn := io.instructionRaw
  row.len := io.instructionLen
  row.nextPc := io.slotPc + io.instructionLen.pad(traceParams.pcWidth)
  row.src0.valid := completeRowValid && io.source0.valid
  row.src0.reg := io.source0.reg
  row.src0.data := io.source0.data
  row.src1.valid := completeRowValid && io.source1.valid
  row.src1.reg := io.source1.reg
  row.src1.data := io.source1.data
  row.dst.valid := completeRowValid
  row.dst.reg := fitReg(io.slotDst.archTag)
  row.dst.data := io.slotData
  row.wb.valid := completeRowValid
  row.wb.reg := fitReg(io.slotDst.archTag)
  row.wb.data := io.slotData
  row.mem.valid := completeRowValid
  row.mem.isStore := false.B
  row.mem.addr := io.slotAddr
  row.mem.wdata := 0.U
  row.mem.rdata := io.slotData
  row.mem.size := io.slotSize.pad(4)(3, 0)

  io.active := active
  io.candidateValid := candidateValid
  io.targetValid := candidateValid && targetValid
  io.identityValid := candidateValid && targetValid && identityValid
  io.metadataReady := candidateValid && targetValid && identityValid && metadataReady
  io.sourceTraceReady := candidateValid && targetValid && identityValid && metadataReady && sourceTraceReady
  io.sizeSupported :=
    candidateValid && targetValid && identityValid && metadataReady && sourceTraceReady && sizeSupported
  io.destinationGpr :=
    candidateValid && targetValid && identityValid && metadataReady && sourceTraceReady && sizeSupported && destinationGpr
  io.rowFillCandidateValid := rowFillCandidateValid
  io.completeRowValid := completeRowValid
  io.completeRow := row
  io.blockedByDisabled := !io.enable && io.slotOccupied
  io.blockedByFlush := io.enable && io.flush && io.slotOccupied
  io.blockedByNoSlot := active && !io.slotOccupied
  io.blockedByInvalidTarget := candidateValid && !targetValid
  io.blockedByInvalidIdentity := candidateValid && targetValid && !identityValid
  io.blockedByNoInstructionMetadata := candidateValid && targetValid && identityValid && !metadataReady
  io.blockedByNoSourceTrace :=
    candidateValid && targetValid && identityValid && metadataReady && !sourceTraceReady
  io.blockedByInvalidSize :=
    candidateValid && targetValid && identityValid && metadataReady && sourceTraceReady && !sizeSupported
  io.blockedByNoDestination :=
    candidateValid && targetValid && identityValid && metadataReady && sourceTraceReady && sizeSupported && !hasDestination
  io.blockedByNonGprDestination :=
    candidateValid && targetValid && identityValid && metadataReady && sourceTraceReady && sizeSupported &&
      io.slotDst.valid && !destinationGpr
  io.blockedByRowFillDisabled := rowFillCandidateValid && !io.rowFillEnable
}
