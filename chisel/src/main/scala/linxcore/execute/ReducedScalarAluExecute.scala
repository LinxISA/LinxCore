package linxcore.execute

import chisel3._
import chisel3.util.{Cat, Fill, PopCount, PriorityEncoder, Reverse, is, log2Ceil, switch}

import linxcore.commit.{CommitOperandTrace, CommitTraceParams, CommitTraceRow}
import linxcore.common.{DestinationKind, InterfaceParams, OperandClass, RenamedDestination, RenamedUop, ScalarSpAccess, ScalarSpTransaction}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.rob.ROBID

class ReducedScalarAluExecuteIO(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  private val ptrWidth = log2Ceil(p.robEntries)

  val inValid = Input(Bool())
  val inReady = Output(Bool())
  val in = Input(new RenamedUop(p))
  val srcData = Input(Vec(3, UInt(p.immWidth.W)))
  val loadLookupData = Input(UInt(p.immWidth.W))
  val loadPairFirstLookupData = Input(UInt(p.immWidth.W))
  val loadLookupWaitBlocked = Input(Bool())
  val loadLiqEnable = Input(Bool())
  val loadLiqAccepted = Input(Bool())
  val stackPointerData = Input(UInt(p.immWidth.W))
  val flushValid = Input(Bool())
  val fretStkFallbackTargetValid = Input(Bool())
  val fretStkFallbackTarget = Input(UInt(p.pcWidth.W))
  val fretStkConditionValid = Input(Bool())
  val fretStkConditionTaken = Input(Bool())

  val completeReady = Input(Bool())
  val completeValid = Output(Bool())
  val completeFire = Output(Bool())
  val completeAccepted = Output(Bool())
  val completeRobValue = Output(UInt(ptrWidth.W))
  val completePeId = Output(UInt(p.peIdWidth.W))
  val completeStid = Output(UInt(p.threadIdWidth.W))
  val completeTid = Output(UInt(p.threadIdWidth.W))
  val completeRow = Output(new CommitTraceRow(traceParams))
  val completeLsId = Output(UInt(p.lsidWidth.W))
  val completeDstPhysValid = Output(Bool())
  val completeDstPhysTag = Output(UInt(p.physRegWidth.W))
  val completeDstData = Output(UInt(p.immWidth.W))
  val completePairFirstDstPhysValid = Output(Bool())
  val completePairFirstDstPhysTag = Output(UInt(p.physRegWidth.W))
  val completePairFirstDstData = Output(UInt(p.immWidth.W))
  val completeSrcPhysValid = Output(Vec(3, Bool()))
  val completeSrcPhysTag = Output(Vec(3, UInt(p.physRegWidth.W)))
  val branchConditionValid = Output(Bool())
  val branchConditionTaken = Output(Bool())
  val loadLookupValid = Output(Bool())
  val loadLookupAddr = Output(UInt(p.immWidth.W))
  val loadPairFirstLookupValid = Output(Bool())
  val loadPairFirstLookupAddr = Output(UInt(p.immWidth.W))
  val loadLookupSize = Output(UInt(p.memSizeWidth.W))
  val loadLookupReturnSignExtend = Output(Bool())
  val loadLiqEligible = Output(Bool())
  val loadLookupPc = Output(UInt(p.pcWidth.W))
  val loadLookupBid = Output(new ROBID(p.robEntries))
  val loadLookupGid = Output(new ROBID(p.robEntries))
  val loadLookupRid = Output(new ROBID(p.robEntries))
  val loadLookupLsId = Output(UInt(p.lsidWidth.W))
  val loadLookupDst = Output(new RenamedDestination(p))
  val loadLookupSourceTraceValid = Output(Bool())
  val loadLookupSource0 = Output(new CommitOperandTrace(traceParams))
  val loadLookupSource1 = Output(new CommitOperandTrace(traceParams))
  val fretStkSpRestoreValid = Output(Bool())
  val fretStkSpRestoreData = Output(UInt(p.immWidth.W))
  val scalarSpTerminalValid = Output(Bool())
  val scalarSpTerminal = Output(new ScalarSpTransaction(p))
  val scalarSpProducedValid = Output(Bool())
  val scalarSpProducedData = Output(UInt(p.immWidth.W))
  val redirectValid = Output(Bool())
  val redirectPc = Output(UInt(p.pcWidth.W))
  val redirectOrder = Output(UInt(p.uopUidWidth.W))

  val releaseValid = Output(Bool())
  val releaseBid = Output(new ROBID(p.robEntries))
  val releaseGid = Output(new ROBID(p.robEntries))
  val releaseRid = Output(new ROBID(p.robEntries))
  val releaseStid = Output(UInt(p.threadIdWidth.W))
  val earlyReleaseValid = Output(Bool())
  val earlyReleaseBid = Output(new ROBID(p.robEntries))
  val earlyReleaseGid = Output(new ROBID(p.robEntries))
  val earlyReleaseRid = Output(new ROBID(p.robEntries))
  val earlyReleaseStid = Output(UInt(p.threadIdWidth.W))
  val liqReleaseValid = Output(Bool())
  val liqReleaseBid = Output(new ROBID(p.robEntries))
  val liqReleaseRid = Output(new ROBID(p.robEntries))
  val liqReleaseStid = Output(UInt(p.threadIdWidth.W))

  val accepted = Output(Bool())
  val busy = Output(Bool())
  val loadWaitHold = Output(Bool())
  val unsupported = Output(Bool())
  val unsupportedOpcode = Output(UInt(p.opcodeWidth.W))
}

class ReducedScalarAluExecute(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(traceParams.robValueWidth >= p.robIndexWidth, "trace ROB value must hold execute completion index")
  require(traceParams.regWidth >= p.archRegWidth, "trace register field must hold architectural register tags")
  require(traceParams.dataWidth == p.immWidth, "reduced ALU trace data follows InterfaceParams immediate/data width")

  val io = IO(new ReducedScalarAluExecuteIO(p, traceParams))

  private def opcode(value: Int): UInt =
    value.U(p.opcodeWidth.W)

  private def isSupported(op: UInt): Bool =
      op === opcode(FrontendOpcodeDecodeTable.OP_ADD) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ADDW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ADDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ADDIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ADDTPC) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ADDTPC) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETRET) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETRET) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETRET) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_AND) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_OR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SUB) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_B) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_H) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_W) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ZEXT_B) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ZEXT_H) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ZEXT_W) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_LDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_LWI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SWI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_EQ) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_NE) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_TGT) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_CMP_EQI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_CMP_NEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_AND) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_ANDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_EQ) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_EQI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_GE) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_GEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_GEU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_GEUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_LT) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_LTI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_LTU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_LTUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_NE) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_NEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_OR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_ORI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CSEL) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_FRET_STK) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_FENTRY) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_AND) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ANDW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ANDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ANDIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_BCNT) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_BIC) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_BIS) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_BXS) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_BXU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CLZ) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CTZ) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LIS) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LIU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ADDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ADDIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ANDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ANDIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_ANDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_EQI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_GEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_GEUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_LTI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_LTUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_NEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_ORI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ORI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_ORIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SUBI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SUBIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_XORI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_XORIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LD_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LB_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBU_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LW_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWU_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SB_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SD_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SH_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SW_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SDI_PO) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SDI_PR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWI_PO) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SDIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SDIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBUIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHUIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHUIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWUIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWUIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LDIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LDIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LB) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LBI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LBU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LBUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LD_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LD) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LH) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LHU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LR_W) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LWUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LHI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LHUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LWI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LWI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_DIV) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_DIVU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_DIVW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_DIVUW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MUL) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MULU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MULUW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MULW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MADD) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MAX) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MAXU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MIN) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MINU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_FEQ) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_FCVT) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_UCVTF) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ORI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SB) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SH) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SBI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_AND) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_ANDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_EQ) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_EQI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_GE) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_GEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_GEU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_GEUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LT) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_NE) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_NEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_OR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_ORI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_TGT) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_ANDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_EQI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_GEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_GEUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_LTI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_LTUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_NEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_ORI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SD) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SHI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SWI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SLL) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SLLI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SLLW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SLLIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRL) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRLI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRLW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRLIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRA) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRAI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRAW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRAIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REM) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REMU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REMW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REMUW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SSRSET) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SSRGET) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_OR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ORW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ORIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ADD) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ADDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SLLI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SRLI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SUB) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SUBI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SUBIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SUBW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_XOR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_XORI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_XORIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_XORW)

  private def isDivideOrRemainder(op: UInt): Bool =
      op === opcode(FrontendOpcodeDecodeTable.OP_DIV) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_DIVU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_DIVW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_DIVUW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REM) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REMU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REMW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REMUW)

  private def isScalarFp(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_FEQ) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_FCVT) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_UCVTF)

  private def isSignedDivideOrRemainder(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_DIV) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_DIVW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REM) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REMW)

  private def isSetcEqImmediate(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_SETC_EQI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_EQI)

  private def isSetcNeImmediate(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_SETC_NEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_NEI)

  private def isSetcAndImmediate(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_SETC_ANDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_ANDI)

  private def isSetcOrImmediate(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_SETC_ORI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_ORI)

  private def isSetcLtImmediate(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_LTI)

  private def isSetcGeImmediate(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_SETC_GEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_GEI)

  private def isSetcLtuImmediate(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_LTUI)

  private def isSetcGeuImmediate(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_SETC_GEUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_GEUI)

  private def isHlSetcImmediate(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_ANDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_EQI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_GEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_GEUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_LTI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_LTUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_NEI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETC_ORI)

  private def isWordDivideOrRemainder(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_DIVW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_DIVUW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REMW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REMUW)

  private def isRemainder(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_REM) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REMU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REMW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_REMUW)

  private def ldiScaledOffset(imm: UInt): UInt =
    (imm << 3)(p.immWidth - 1, 0)

  private def cLdiAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + imm

  private def cLwiAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + ((imm << 2)(p.immWidth - 1, 0))

  private def cSdiAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + ((imm << 3)(p.immWidth - 1, 0))

  private def cSwiAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + ((imm << 2)(p.immWidth - 1, 0))

  private def ldiAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + ldiScaledOffset(imm)

  private def lwiScaledAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + ((imm << 2)(p.immWidth - 1, 0))

  private def lwiUnscaledAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + imm

  private def loadByteImmAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + imm

  private def loadByteRegAddr(srcData: Vec[UInt], insnRaw: UInt): UInt =
    srcData(0) + addSubSrcR(insnRaw, srcData(1))

  private def loadHalfImmAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + ((imm << 1)(p.immWidth - 1, 0))

  private def loadRegAddr(srcData: Vec[UInt], insnRaw: UInt): UInt =
    srcData(0) + addSubSrcR(insnRaw, srcData(1))

  private def pcrLoadAddr(pc: UInt, imm: UInt): UInt =
    pc + imm

  private def isHlStorePair(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SDIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SDIP_U)

  private def hlStorePairIsWord(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWIP_U)

  private def hlStorePairIsUnscaled(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SDIP_U)

  private def hlStorePairAddr(op: UInt, srcData: Vec[UInt], imm: UInt): UInt = {
    val scaled = Mux(
      hlStorePairIsWord(op),
      (imm << 2)(p.immWidth - 1, 0),
      (imm << 3)(p.immWidth - 1, 0))
    srcData(2) + Mux(hlStorePairIsUnscaled(op), imm, scaled)
  }

  private def isHlImmediateLoadPair(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBUIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHUIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHUIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWUIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWUIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LDIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LDIP_U)

  private def hlImmediateLoadPairIsByte(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBUIP)

  private def hlImmediateLoadPairIsHalf(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHUIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHUIP_U)

  private def hlImmediateLoadPairIsWord(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWUIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWUIP_U)

  private def hlImmediateLoadPairIsUnscaled(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHUIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWUIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LDIP_U)

  private def hlImmediateLoadPairSize(op: UInt): UInt =
    Mux(
      hlImmediateLoadPairIsByte(op),
      1.U(p.memSizeWidth.W),
      Mux(
        hlImmediateLoadPairIsHalf(op),
        2.U(p.memSizeWidth.W),
        Mux(hlImmediateLoadPairIsWord(op), 4.U(p.memSizeWidth.W), 8.U(p.memSizeWidth.W))))

  private def hlImmediateLoadPairSecondAddr(op: UInt, srcData: Vec[UInt], imm: UInt): UInt = {
    val size = hlImmediateLoadPairSize(op)
    val shift = Mux(
      hlImmediateLoadPairIsByte(op),
      0.U,
      Mux(hlImmediateLoadPairIsHalf(op), 1.U, Mux(hlImmediateLoadPairIsWord(op), 2.U, 3.U)))
    val scaledOffset = (imm << shift)(p.immWidth - 1, 0)
    srcData(0) + Mux(hlImmediateLoadPairIsUnscaled(op), imm, scaledOffset) + size
  }

  private def hlImmediateLoadPairFirstAddr(op: UInt, srcData: Vec[UInt], imm: UInt): UInt =
    hlImmediateLoadPairSecondAddr(op, srcData, imm) - hlImmediateLoadPairSize(op)

  private def isHlLongOffsetLoad(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LDI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHUI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWUI_U)

  private def hlLongOffsetLoadIsByte(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBUI)

  private def hlLongOffsetLoadIsHalf(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHUI_U)

  private def hlLongOffsetLoadIsWord(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWUI_U)

  private def hlLongOffsetLoadIsUnscaled(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_LDI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHUI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWUI_U)

  private def hlLongOffsetLoadSize(op: UInt): UInt =
    Mux(
      hlLongOffsetLoadIsByte(op),
      1.U(p.memSizeWidth.W),
      Mux(
        hlLongOffsetLoadIsHalf(op),
        2.U(p.memSizeWidth.W),
        Mux(hlLongOffsetLoadIsWord(op), 4.U(p.memSizeWidth.W), 8.U(p.memSizeWidth.W))))

  private def hlLongOffsetLoadAddr(op: UInt, srcData: Vec[UInt], imm: UInt): UInt = {
    val scaled = Mux(
      hlLongOffsetLoadIsByte(op),
      imm,
      Mux(
        hlLongOffsetLoadIsHalf(op),
        (imm << 1)(p.immWidth - 1, 0),
        Mux(
          hlLongOffsetLoadIsWord(op),
          (imm << 2)(p.immWidth - 1, 0),
          (imm << 3)(p.immWidth - 1, 0))))
    srcData(0) + Mux(hlLongOffsetLoadIsUnscaled(op), imm, scaled)
  }

  private def isHlLongOffsetStore(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_SBI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SDI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SHI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SHI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWI_U)

  private def hlLongOffsetStoreIsByte(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_SBI)

  private def hlLongOffsetStoreIsHalf(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_SHI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SHI_U)

  private def hlLongOffsetStoreIsWord(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWI_U)

  private def hlLongOffsetStoreIsUnscaled(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_SDI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SHI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWI_U)

  private def hlLongOffsetStoreSize(op: UInt): UInt =
    Mux(
      hlLongOffsetStoreIsByte(op),
      1.U(p.memSizeWidth.W),
      Mux(
        hlLongOffsetStoreIsHalf(op),
        2.U(p.memSizeWidth.W),
        Mux(hlLongOffsetStoreIsWord(op), 4.U(p.memSizeWidth.W), 8.U(p.memSizeWidth.W))))

  private def hlLongOffsetStoreAddr(op: UInt, srcData: Vec[UInt], imm: UInt): UInt = {
    val scaled = Mux(
      hlLongOffsetStoreIsByte(op),
      imm,
      Mux(
        hlLongOffsetStoreIsHalf(op),
        (imm << 1)(p.immWidth - 1, 0),
        Mux(
          hlLongOffsetStoreIsWord(op),
          (imm << 2)(p.immWidth - 1, 0),
          (imm << 3)(p.immWidth - 1, 0))))
    srcData(1) + Mux(hlLongOffsetStoreIsUnscaled(op), imm, scaled)
  }

  private def pcrStoreSize(op: UInt): UInt =
    Mux(
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SB_PCR),
      1.U,
      Mux(op === opcode(FrontendOpcodeDecodeTable.OP_HL_SH_PCR), 2.U, Mux(op === opcode(FrontendOpcodeDecodeTable.OP_HL_SW_PCR), 4.U, 8.U)))

  private def traceUsesSrc1(op: UInt): Bool =
    !(op === opcode(FrontendOpcodeDecodeTable.OP_ADDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ADDI))

  private def loadReturnSignExtend(op: UInt): Bool =
    op === opcode(FrontendOpcodeDecodeTable.OP_LB) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LBI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LB_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LB_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LH) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LHI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LHI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LH_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LR_W) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LWI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LWI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LW_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LW_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWI_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWIP) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWIP_U) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_LWI)

  private def sext32(value: UInt): UInt =
    Cat(Fill(p.immWidth - 32, value(31)), value(31, 0))

  private def sext16(value: UInt): UInt =
    Cat(Fill(p.immWidth - 16, value(15)), value(15, 0))

  private def sext8(value: UInt): UInt =
    Cat(Fill(p.immWidth - 8, value(7)), value(7, 0))

  private def zext32(value: UInt): UInt =
    value(31, 0).pad(p.immWidth)

  private def zext16(value: UInt): UInt =
    value(15, 0).pad(p.immWidth)

  private def zext8(value: UInt): UInt =
    value(7, 0).pad(p.immWidth)

  private def srcRType(insn: UInt): UInt =
    insn(26, 25)

  private def srcRShamt(insn: UInt): UInt =
    insn(31, 27)

  private def setcImmediateShamt(op: UInt, insn: UInt): UInt =
    Mux(isHlSetcImmediate(op), insn(27, 23), insn(11, 7))

  private def setcShiftedImmediate(op: UInt, insn: UInt, imm: UInt): UInt =
    (imm << setcImmediateShamt(op, insn))(p.immWidth - 1, 0)

  private def shiftedSrcR(value: UInt, shamt: UInt): UInt =
    (value << shamt)(p.immWidth - 1, 0)

  private def boolResult(value: Bool): UInt =
    Mux(value, 1.U(p.immWidth.W), 0.U(p.immWidth.W))

  private def rotateRight(value: UInt, amount: UInt): UInt = {
    val inverse = p.immWidth.U((log2Ceil(p.immWidth) + 1).W) - amount
    ((value >> amount) | (value << inverse))(p.immWidth - 1, 0)
  }

  private def rotateLeft(value: UInt, amount: UInt): UInt = {
    val inverse = p.immWidth.U((log2Ceil(p.immWidth) + 1).W) - amount
    ((value << amount) | (value >> inverse))(p.immWidth - 1, 0)
  }

  private def bitField(insn: UInt, value: UInt): (UInt, UInt, Bool) = {
    val lsb = insn(31, 26)
    val width = Cat(0.U(1.W), insn(25, 20)) + 1.U
    val legal = width <= p.immWidth.U
    val wideMask = (1.U((p.immWidth + 1).W) << width) - 1.U
    val mask = Mux(width === p.immWidth.U, Fill(p.immWidth, 1.U(1.W)), wideMask(p.immWidth - 1, 0))
    val field = Mux(legal, rotateRight(value, lsb) & mask, 0.U)
    (field, width, legal)
  }

  private def bitFieldMask(insn: UInt): UInt = {
    val lsb = insn(31, 26)
    val width = Cat(0.U(1.W), insn(25, 20)) + 1.U
    val wideMask = (1.U((p.immWidth + 1).W) << width) - 1.U
    val lowMask =
      Mux(width === p.immWidth.U, Fill(p.immWidth, 1.U(1.W)), wideMask(p.immWidth - 1, 0))
    rotateLeft(lowMask, lsb)
  }

  private def bitExtractSigned(insn: UInt, value: UInt): UInt = {
    val (field, width, legal) = bitField(insn, value)
    val sign = (field >> (width - 1.U))(0)
    val wideMask = (1.U((p.immWidth + 1).W) << width) - 1.U
    val mask = Mux(width === p.immWidth.U, Fill(p.immWidth, 1.U(1.W)), wideMask(p.immWidth - 1, 0))
    Mux(legal && sign, field | ~mask, field)
  }

  private def countLeadingZerosField(insn: UInt, value: UInt): UInt = {
    val (field, width, legal) = bitField(insn, value)
    val aligned = (field << (p.immWidth.U - width))(p.immWidth - 1, 0)
    Mux(!legal, 0.U, Mux(field === 0.U, width, PriorityEncoder(Reverse(aligned)))).pad(p.immWidth)
  }

  private def countTrailingZerosField(insn: UInt, value: UInt): UInt = {
    val (field, width, legal) = bitField(insn, value)
    Mux(!legal, 0.U, Mux(field === 0.U, width, PriorityEncoder(field))).pad(p.immWidth)
  }

  private def addSubSrcR(insn: UInt, value: UInt): UInt = {
    val converted = Wire(UInt(p.immWidth.W))
    converted := value
    switch(srcRType(insn)) {
      is(0.U) { converted := sext32(value) }
      is(1.U) { converted := zext32(value) }
      is(2.U) { converted := (0.U(p.immWidth.W) - value)(p.immWidth - 1, 0) }
    }
    shiftedSrcR(converted, srcRShamt(insn))
  }

  private def storeRegSrcR(insn: UInt, value: UInt): UInt = {
    val converted = Wire(UInt(p.immWidth.W))
    converted := value
    switch(srcRType(insn)) {
      is(0.U) { converted := sext32(value) }
      is(1.U) { converted := zext32(value) }
      is(2.U) { converted := (0.U(p.immWidth.W) - value)(p.immWidth - 1, 0) }
    }
    converted
  }

  private def logicSrcR(insn: UInt, value: UInt): UInt = {
    val converted = Wire(UInt(p.immWidth.W))
    converted := value
    switch(srcRType(insn)) {
      is(0.U) { converted := sext32(value) }
      is(1.U) { converted := zext32(value) }
      is(2.U) { converted := ~value }
    }
    shiftedSrcR(converted, srcRShamt(insn))
  }

  private def setcRegisterSrcR(insn: UInt, value: UInt): UInt = {
    val converted = Wire(UInt(p.immWidth.W))
    converted := value
    switch(srcRType(insn)) {
      is(1.U) { converted := sext32(value) }
      is(2.U) { converted := zext32(value) }
    }
    converted
  }

  private def macroRangeContains(insn: UInt, reg: UInt): Bool = {
    val begin = insn(19, 15)
    val end = insn(24, 20)
    Mux(end >= begin, reg >= begin && reg <= end, reg >= begin || reg <= end)
  }

  private def fretStkRestoresRa(insn: UInt): Bool =
    macroRangeContains(insn, 10.U)

  private def fretStkRaLoadAddr(stackPointerData: UInt, imm: UInt): UInt =
    stackPointerData + imm - 8.U

  private def sdIndexedAddr(srcData: Vec[UInt]): UInt =
    srcData(1) + ((srcData(2) << 3)(p.immWidth - 1, 0))

  private def storeRegAddr(srcData: Vec[UInt], insnRaw: UInt, scale: Int): UInt =
    srcData(1) + ((storeRegSrcR(insnRaw, srcData(2)) << scale)(p.immWidth - 1, 0))

  private def storeByteImmAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(1) + imm

  private def storeByteRegAddr(srcData: Vec[UInt], insnRaw: UInt): UInt =
    srcData(1) + storeRegSrcR(insnRaw, srcData(2))

  private def storeHalfImmAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(1) + ((imm << 1)(p.immWidth - 1, 0))

  private def storeWordImmAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(1) + ((imm << 2)(p.immWidth - 1, 0))

  private def hlSdiPrAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(1) + ((imm << 3)(p.immWidth - 1, 0))

  private def resultFor(
      op: UInt,
      pc: UInt,
      insnRaw: UInt,
      srcData: Vec[UInt],
      imm: UInt,
      loadData: UInt,
      stackPointerData: UInt): UInt = {
    val addSubR = addSubSrcR(insnRaw, srcData(1))
    val logicR = logicSrcR(insnRaw, srcData(1))
    val out = Wire(UInt(p.immWidth.W))
    out := 0.U
    when(op === opcode(FrontendOpcodeDecodeTable.OP_ADD)) {
      out := srcData(0) + addSubR
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ADDW)) {
      out := sext32(srcData(0) + addSubR)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SUB)) {
      out := srcData(0) - addSubR
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SUBW)) {
      out := sext32(srcData(0) - addSubR)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ADDI)) {
      out := srcData(0) + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ADDIW)) {
      out := sext32(srcData(0)(31, 0) + imm(11, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SUBI)) {
      out := srcData(0) - imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SUBIW)) {
      out := sext32(srcData(0)(31, 0) - imm(11, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_AND)) {
      out := srcData(0) & logicR
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ANDW)) {
      out := sext32(srcData(0) & logicR)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ANDI)) {
      out := srcData(0) & imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ANDIW)) {
      out := sext32(srcData(0) & imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ADDTPC)) {
      out := (pc & "hffff_ffff_ffff_f000".U(p.immWidth.W)) + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SETRET)) {
      out := pc + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_SETRET)) {
      out := pc + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVI)) {
      out := imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVR)) {
      out := srcData(0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_ADD)) {
      out := srcData(0) + srcData(1)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_ADDI)) {
      out := srcData(0) + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SLLI)) {
      out := srcData(0) << imm(4, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SRLI)) {
      out := srcData(0) >> imm(4, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_AND)) {
      out := srcData(0) & srcData(1)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_OR)) {
      out := srcData(0) | srcData(1)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SUB)) {
      out := srcData(0) - srcData(1)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_B)) {
      out := sext8(srcData(0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_H)) {
      out := sext16(srcData(0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_W)) {
      out := sext32(srcData(0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_ZEXT_B)) {
      out := zext8(srcData(0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_ZEXT_H)) {
      out := zext16(srcData(0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_ZEXT_W)) {
      out := zext32(srcData(0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SETRET)) {
      out := pc + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_CMP_EQI)) {
      out := boolResult(srcData(0) === imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_CMP_NEI)) {
      out := boolResult(srcData(0) =/= imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_AND)) {
      out := boolResult((srcData(0) & logicSrcR(insnRaw, srcData(1))) =/= 0.U)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_ANDI)) {
      out := boolResult((srcData(0) & imm) =/= 0.U)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_EQ)) {
      out := boolResult(srcData(0) === addSubSrcR(insnRaw, srcData(1)))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_EQI)) {
      out := boolResult(srcData(0) === imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_GE)) {
      out := boolResult(srcData(0).asSInt >= addSubSrcR(insnRaw, srcData(1)).asSInt)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_GEI)) {
      out := boolResult(srcData(0).asSInt >= imm.asSInt)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_GEU)) {
      out := boolResult(srcData(0) >= addSubSrcR(insnRaw, srcData(1)))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_GEUI)) {
      out := boolResult(srcData(0) >= imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_LT)) {
      out := boolResult(srcData(0).asSInt < addSubSrcR(insnRaw, srcData(1)).asSInt)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_LTI)) {
      out := boolResult(srcData(0).asSInt < imm.asSInt)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_LTU)) {
      out := boolResult(srcData(0) < addSubSrcR(insnRaw, srcData(1)))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_LTUI)) {
      out := boolResult(srcData(0) < imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_NE)) {
      out := boolResult(srcData(0) =/= addSubSrcR(insnRaw, srcData(1)))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_NEI)) {
      out := boolResult(srcData(0) =/= imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_OR)) {
      out := boolResult((srcData(0) | logicSrcR(insnRaw, srcData(1))) =/= 0.U)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_ORI)) {
      out := boolResult((srcData(0) | imm) =/= 0.U)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CSEL)) {
      out := Mux(srcData(2) =/= 0.U, srcData(0), srcData(1))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_BCNT)) {
      out := PopCount(bitField(insnRaw, srcData(0))._1).pad(p.immWidth)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_BIC)) {
      out := srcData(0) & ~bitFieldMask(insnRaw)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_BIS)) {
      out := srcData(0) | bitFieldMask(insnRaw)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_BXS)) {
      out := bitExtractSigned(insnRaw, srcData(0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_BXU)) {
      out := bitField(insnRaw, srcData(0))._1
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CLZ)) {
      out := countLeadingZerosField(insnRaw, srcData(0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CTZ)) {
      out := countTrailingZerosField(insnRaw, srcData(0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_LDI)) {
      out := loadData
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_LWI)) {
      out := sext32(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LB)) {
      out := sext8(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LBI)) {
      out := sext8(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LBU)) {
      out := loadData(7, 0).pad(p.immWidth)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LBUI)) {
      out := loadData(7, 0).pad(p.immWidth)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LD)) {
      out := loadData
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LH)) {
      out := sext16(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LHU)) {
      out := zext16(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LW)) {
      out := sext32(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LR_W)) {
      out := sext32(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LWUI)) {
      out := zext32(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LDI)) {
      out := loadData
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LHI)) {
      out := sext16(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LHUI)) {
      out := zext16(loadData)
    }.elsewhen(
      op === opcode(FrontendOpcodeDecodeTable.OP_LWI) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_LWI_U)) {
      out := sext32(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SDI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SWI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_EQ)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_NE)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_TGT)) {
      out := 0.U
    }.elsewhen(isSetcAndImmediate(op)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SETC_EQ)) {
      out := 0.U
    }.elsewhen(isSetcEqImmediate(op)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SETC_NE)) {
      out := 0.U
    }.elsewhen(isSetcNeImmediate(op)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LT)) {
      out := 0.U
    }.elsewhen(isSetcLtImmediate(op)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SETC_GE)) {
      out := 0.U
    }.elsewhen(isSetcGeImmediate(op)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTU)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SETC_GEU)) {
      out := 0.U
    }.elsewhen(isSetcGeuImmediate(op)) {
      out := 0.U
    }.elsewhen(isSetcLtuImmediate(op)) {
      out := 0.U
    }.elsewhen(isSetcOrImmediate(op)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SETC_TGT)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_FRET_STK)) {
      out := loadData
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_FENTRY)) {
      out := stackPointerData - imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LUI)) {
      out := imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_LUI)) {
      out := imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_LIS)) {
      out := imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_LIU)) {
      out := imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_ADDI)) {
      out := srcData(0) + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_ADDIW)) {
      out := sext32(srcData(0) + imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_ANDI)) {
      out := srcData(0) & imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_ANDIW)) {
      out := sext32(srcData(0) & imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_ANDI)) {
      out := boolResult((srcData(0) & imm) =/= 0.U)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_EQI)) {
      out := boolResult(srcData(0) === imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_GEI)) {
      out := boolResult(srcData(0).asSInt >= imm.asSInt)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_GEUI)) {
      out := boolResult(srcData(0) >= imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_LTI)) {
      out := boolResult(srcData(0).asSInt < imm.asSInt)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_LTUI)) {
      out := boolResult(srcData(0) < imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_NEI)) {
      out := boolResult(srcData(0) =/= imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_CMP_ORI)) {
      out := boolResult((srcData(0) | imm) =/= 0.U)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_ORI)) {
      out := srcData(0) | imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_ORIW)) {
      out := sext32(srcData(0) | imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_SUBI)) {
      out := srcData(0) - imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_SUBIW)) {
      out := sext32(srcData(0) - imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_XORI)) {
      out := srcData(0) ^ imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_XORIW)) {
      out := sext32(srcData(0) ^ imm)
    }.elsewhen(
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LD_PCR) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_LD_PCR)) {
      out := loadData
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_LB_PCR)) {
      out := sext8(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBU_PCR)) {
      out := zext8(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_LW_PCR)) {
      out := sext32(loadData)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWU_PCR)) {
      out := zext32(loadData)
    }.elsewhen(isHlImmediateLoadPair(op)) {
      out := Mux(
        hlImmediateLoadPairIsByte(op),
        Mux(op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBIP), sext8(loadData), zext8(loadData)),
        Mux(
          hlImmediateLoadPairIsHalf(op),
          Mux(
            op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHIP) ||
              op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHIP_U),
            sext16(loadData),
            zext16(loadData)),
          Mux(
            hlImmediateLoadPairIsWord(op),
            Mux(
              op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWIP) ||
                op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWIP_U),
              sext32(loadData),
              zext32(loadData)),
            loadData)))
    }.elsewhen(isHlLongOffsetLoad(op)) {
      out := Mux(
        hlLongOffsetLoadIsByte(op),
        Mux(op === opcode(FrontendOpcodeDecodeTable.OP_HL_LBI), sext8(loadData), zext8(loadData)),
        Mux(
          hlLongOffsetLoadIsHalf(op),
          Mux(
            op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHI) ||
              op === opcode(FrontendOpcodeDecodeTable.OP_HL_LHI_U),
            sext16(loadData),
            zext16(loadData)),
          Mux(
            hlLongOffsetLoadIsWord(op),
            Mux(
              op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWI) ||
                op === opcode(FrontendOpcodeDecodeTable.OP_HL_LWI_U),
              sext32(loadData),
              zext32(loadData)),
            loadData)))
    }.elsewhen(
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SB_PCR) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_HL_SD_PCR) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_HL_SH_PCR) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_HL_SW_PCR)) {
      out := 0.U
    }.elsewhen(
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SDI_PO) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_HL_SDI_PR)) {
      out := hlSdiPrAddr(srcData, imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_SWI_PO)) {
      // Store-post-index uses the old base as the memory address and writes
      // base + (signed encoded immediate << log2(word bytes)) to RegDst.
      out := srcData(1) + ((imm << 2)(p.immWidth - 1, 0))
    }.elsewhen(isHlStorePair(op)) {
      out := 0.U
    }.elsewhen(isHlLongOffsetStore(op)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SB)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SH)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SW)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SBI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SD)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SDI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SHI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SWI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_MUL)) {
      out := (srcData(0) * srcData(1))(p.immWidth - 1, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_MULU)) {
      out := (srcData(0) * srcData(1))(p.immWidth - 1, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_MULW)) {
      out := sext32(srcData(0) * srcData(1))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_MULUW)) {
      out := sext32(srcData(0) * srcData(1))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_MADD)) {
      out := srcData(2) + (srcData(0) * srcData(1))(p.immWidth - 1, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_MAX)) {
      out := Mux(srcData(0).asSInt >= srcData(1).asSInt, srcData(0), srcData(1))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_MAXU)) {
      out := Mux(srcData(0) >= srcData(1), srcData(0), srcData(1))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_MIN)) {
      out := Mux(srcData(0).asSInt <= srcData(1).asSInt, srcData(0), srcData(1))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_MINU)) {
      out := Mux(srcData(0) <= srcData(1), srcData(0), srcData(1))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SLL)) {
      out := (srcData(0) << srcData(1)(5, 0))(p.immWidth - 1, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SLLI)) {
      out := (srcData(0) << imm(5, 0))(p.immWidth - 1, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SLLW)) {
      out := sext32(srcData(0)(31, 0) << srcData(1)(4, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SLLIW)) {
      out := sext32(srcData(0)(31, 0) << imm(4, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRL)) {
      out := srcData(0) >> srcData(1)(5, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRLI)) {
      out := srcData(0) >> imm(5, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRLW)) {
      out := sext32(srcData(0)(31, 0) >> srcData(1)(4, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRLIW)) {
      out := sext32(srcData(0)(31, 0) >> imm(4, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRA)) {
      out := (srcData(0).asSInt >> srcData(1)(5, 0)).asUInt
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRAI)) {
      out := (srcData(0).asSInt >> imm(5, 0)).asUInt
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRAW)) {
      out := sext32((srcData(0)(31, 0).asSInt >> srcData(1)(4, 0)).asUInt)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRAIW)) {
      out := sext32((srcData(0)(31, 0).asSInt >> imm(4, 0)).asUInt)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SSRSET)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_OR)) {
      out := srcData(0) | logicR
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ORW)) {
      out := sext32(srcData(0) | logicR)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ORI)) {
      out := srcData(0) | imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ORIW)) {
      out := sext32(srcData(0) | imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_XOR)) {
      out := srcData(0) ^ logicR
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_XORI)) {
      out := srcData(0) ^ imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_XORIW)) {
      out := sext32(srcData(0) ^ imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_XORW)) {
      out := sext32(srcData(0) ^ logicR)
    }
    out
  }

  private def fitReg(tag: UInt): UInt =
    tag.pad(traceParams.regWidth)(traceParams.regWidth - 1, 0)

  private def robIdValue(id: ROBID): UInt =
    id.value.pad(32)(31, 0)

  private def completionRow(
      uop: RenamedUop,
      srcData: Vec[UInt],
      result: UInt,
      valid: Bool,
      setcTargetValid: Bool,
      setcTarget: UInt,
      fallbackTargetValid: Bool,
      fallbackTarget: UInt,
      fretStkLoadReturn: Bool,
      stackPointerData: UInt): CommitTraceRow = {
    val row = Wire(new CommitTraceRow(traceParams))
    row := 0.U.asTypeOf(row)
    row.valid := valid
    row.identity.bid := robIdValue(uop.bid)
    row.identity.gid := robIdValue(uop.gid)
    row.identity.rid := robIdValue(uop.rid)
    row.rob.valid := uop.rid.valid
    row.rob.wrap := uop.rid.wrap
    row.rob.value := uop.rid.value
    row.blockBidValid := uop.blockBidValid
    row.blockBid := uop.blockBid
    row.pc := uop.pc
    row.insn := uop.insnRaw
    row.len := uop.insnLen
    row.nextPc := uop.pc + uop.insnLen
    row.src0.valid := valid && uop.src(0).valid && (uop.src(0).operandClass === OperandClass.P)
    row.src0.reg := fitReg(uop.src(0).archTag)
    row.src0.data := srcData(0)
    row.src1.valid := valid && traceUsesSrc1(uop.opcode) && uop.src(1).valid && (uop.src(1).operandClass === OperandClass.P)
    row.src1.reg := fitReg(uop.src(1).archTag)
    row.src1.data := srcData(1)
    when(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SD)) {
      row.src0.valid := valid && uop.src(1).valid && (uop.src(1).operandClass === OperandClass.P)
      row.src0.reg := fitReg(uop.src(1).archTag)
      row.src0.data := srcData(1)
      row.src1.valid := valid && uop.src(2).valid && (uop.src(2).operandClass === OperandClass.P)
      row.src1.reg := fitReg(uop.src(2).archTag)
      row.src1.data := srcData(2)
    }
    row.dst.valid := valid && uop.dst(0).valid
    row.dst.reg := fitReg(uop.dst(0).archTag)
    row.dst.data := result
    row.wb.valid := valid && uop.dst(0).valid
    row.wb.reg := fitReg(uop.dst(0).archTag)
    row.wb.data := result
    when(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_FRET_STK)) {
      row.nextPc := Mux(
        fretStkLoadReturn,
        result,
        Mux(setcTargetValid, setcTarget, Mux(fallbackTargetValid, fallbackTarget, uop.pc + uop.insnLen)))
      row.src0.valid := false.B
      row.src0.reg := 0.U
      row.src0.data := 0.U
      row.src1.valid := false.B
      row.src1.reg := 0.U
      row.src1.data := 0.U
      when(fretStkLoadReturn) {
        row.dst.valid := valid
        row.dst.reg := 10.U
        row.dst.data := result
        row.wb.valid := valid
        row.wb.reg := 10.U
        row.wb.data := result
        row.mem.valid := valid
        row.mem.isStore := false.B
        row.mem.addr := fretStkRaLoadAddr(stackPointerData, uop.imm)
        row.mem.wdata := 0.U
        row.mem.rdata := result
        row.mem.size := 8.U
      }.otherwise {
        row.dst.valid := false.B
        row.dst.reg := 0.U
        row.dst.data := 0.U
        row.wb.valid := false.B
        row.wb.reg := 0.U
        row.wb.data := 0.U
      }
    }
    when(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_FENTRY)) {
      row.src0.valid := false.B
      row.src1.valid := false.B
      row.mem.valid := valid
      row.mem.isStore := true.B
      // The register ring starts at slot zero, which is always oldSP-8.
      // Later serialized D3 STORE rows walk downward by another eight bytes.
      row.mem.addr := result + uop.imm - 8.U
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := 8.U
    }.elsewhen(
      uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_LDI) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_LWI)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := Mux(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_LWI), cLwiAddr(srcData, uop.imm), cLdiAddr(srcData, uop.imm))
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := Mux(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_LWI), 4.U, 8.U)
    }.elsewhen(
      uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LB) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LBI) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LBU) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LBUI)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := Mux(
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LB) || uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LBU),
        loadByteRegAddr(srcData, uop.insnRaw),
        loadByteImmAddr(srcData, uop.imm))
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := 1.U
    }.elsewhen(
      uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LD) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LH) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LHU) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LW) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LR_W)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := loadRegAddr(srcData, uop.insnRaw)
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := Mux(
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LD),
        8.U,
        Mux(
          uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LH) || uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LHU),
          2.U,
          4.U))
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LDI)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := ldiAddr(srcData, uop.imm)
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := 8.U
    }.elsewhen(
      uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LHI) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LHUI)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := loadHalfImmAddr(srcData, uop.imm)
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := 2.U
    }.elsewhen(
      uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LWI) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LWI_U)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := Mux(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LWI), lwiScaledAddr(srcData, uop.imm), lwiUnscaledAddr(srcData, uop.imm))
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := 4.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LWUI)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := lwiScaledAddr(srcData, uop.imm)
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := 4.U
    }.elsewhen(
      uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LD_PCR) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LD_PCR)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := pcrLoadAddr(uop.pc, uop.imm)
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := 8.U
    }.elsewhen(
      uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LB_PCR) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LBU_PCR) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LW_PCR) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LWU_PCR)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := pcrLoadAddr(uop.pc, uop.imm)
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := Mux(
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LB_PCR) ||
          uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LBU_PCR),
        1.U,
        4.U)
    }.elsewhen(isHlImmediateLoadPair(uop.opcode)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := hlImmediateLoadPairSecondAddr(uop.opcode, srcData, uop.imm)
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := hlImmediateLoadPairSize(uop.opcode)
    }.elsewhen(isHlLongOffsetLoad(uop.opcode)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := hlLongOffsetLoadAddr(uop.opcode, srcData, uop.imm)
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := hlLongOffsetLoadSize(uop.opcode)
    }.elsewhen(
      uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_SB_PCR) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_SD_PCR) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_SH_PCR) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_SW_PCR)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := pcrLoadAddr(uop.pc, uop.imm)
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := pcrStoreSize(uop.opcode)
    }.elsewhen(isHlStorePair(uop.opcode)) {
      // A pair is one architectural instruction with two adjacent memory
      // writes.  This row carries the first lane; the autonomous integration
      // publishes the second lane from src1 on its paired observation port.
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := hlStorePairAddr(uop.opcode, srcData, uop.imm)
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := Mux(hlStorePairIsWord(uop.opcode), 4.U, 8.U)
    }.elsewhen(isHlLongOffsetStore(uop.opcode)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := hlLongOffsetStoreAddr(uop.opcode, srcData, uop.imm)
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := hlLongOffsetStoreSize(uop.opcode)
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_SDI_PO)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := srcData(1)
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := 8.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_SDI_PR)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := result
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := 8.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_SWI_PO)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := srcData(1)
      row.mem.wdata := srcData(0)(31, 0).pad(p.immWidth)
      row.mem.rdata := 0.U
      row.mem.size := 4.U
    }.elsewhen(
      uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SDI) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SWI)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := Mux(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SWI), cSwiAddr(srcData, uop.imm), cSdiAddr(srcData, uop.imm))
      row.mem.wdata := srcData(1)
      row.mem.rdata := 0.U
      row.mem.size := Mux(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SWI), 4.U, 8.U)
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SDI)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := srcData(1) + ((uop.imm << 3)(p.immWidth - 1, 0))
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := 8.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SBI)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := storeByteImmAddr(srcData, uop.imm)
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := 1.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SHI)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := storeHalfImmAddr(srcData, uop.imm)
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := 2.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SB)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := storeByteRegAddr(srcData, uop.insnRaw)
      row.mem.wdata := srcData(0)(7, 0).pad(p.immWidth)
      row.mem.rdata := 0.U
      row.mem.size := 1.U
    }.elsewhen(
      uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SH) ||
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SW)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := Mux(
        uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SH),
        storeRegAddr(srcData, uop.insnRaw, 1),
        storeRegAddr(srcData, uop.insnRaw, 2))
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := Mux(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SH), 2.U, 4.U)
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SWI)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := storeWordImmAddr(srcData, uop.imm)
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := 4.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SD)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := sdIndexedAddr(srcData)
      row.mem.wdata := srcData(0)
      row.mem.rdata := 0.U
      row.mem.size := 8.U
    }
    row
  }

  val eValid = RegInit(false.B)
  val eUop = Reg(new RenamedUop(p))
  val eSrcData = Reg(Vec(3, UInt(p.immWidth.W)))
  val eStackPointerData = Reg(UInt(p.immWidth.W))
  val eEarlyReleased = RegInit(false.B)
  // TIME is architecturally monotonic.  The autonomous core has no external
  // wall-clock port yet, so expose a deterministic hardware cycle timebase;
  // software uses only monotonic deltas in the frozen benchmark profile.
  val cycleCounter = RegInit(0.U(p.immWidth.W))
  cycleCounter := cycleCounter + 1.U

  val w1Valid = RegInit(false.B)
  val w1Uop = Reg(new RenamedUop(p))
  val w1SrcData = Reg(Vec(3, UInt(p.immWidth.W)))
  val w1Result = Reg(UInt(p.immWidth.W))
  val w1PairFirstResult = Reg(UInt(p.immWidth.W))
  val w1Supported = Reg(Bool())
  val w1StackPointerData = Reg(UInt(p.immWidth.W))
  val w1FretStkLoadReturn = Reg(Bool())
  // FRET.STK consumes the block SETC condition in E1. Keep that decision
  // with the uop through W1/W2: fetch may cross a later BSTART and clear the
  // shared block-condition latch before this older return completes.
  val w1FretStkConditionValid = RegInit(false.B)
  val w1FretStkConditionTaken = RegInit(false.B)
  val w1FretStkFallbackTargetValid = RegInit(false.B)
  val w1FretStkFallbackTarget = RegInit(0.U(p.pcWidth.W))
  val w1EarlyReleased = RegInit(false.B)

  val w2Valid = RegInit(false.B)
  val w2Uop = Reg(new RenamedUop(p))
  val w2SrcData = Reg(Vec(3, UInt(p.immWidth.W)))
  val w2Result = Reg(UInt(p.immWidth.W))
  val w2PairFirstResult = Reg(UInt(p.immWidth.W))
  val w2Supported = Reg(Bool())
  val w2StackPointerData = Reg(UInt(p.immWidth.W))
  val w2FretStkLoadReturn = Reg(Bool())
  val w2FretStkConditionValid = RegInit(false.B)
  val w2FretStkConditionTaken = RegInit(false.B)
  val w2FretStkFallbackTargetValid = RegInit(false.B)
  val w2FretStkFallbackTarget = RegInit(0.U(p.pcWidth.W))
  val w2EarlyReleased = RegInit(false.B)
  val setcTargetValid = RegInit(false.B)
  val setcTarget = RegInit(0.U(p.pcWidth.W))
  val earlyReleasePendingValid = RegInit(false.B)
  val earlyReleasePendingBid = RegInit(ROBID.disabled(p.robEntries))
  val earlyReleasePendingGid = RegInit(ROBID.disabled(p.robEntries))
  val earlyReleasePendingRid = RegInit(ROBID.disabled(p.robEntries))
  val earlyReleasePendingStid = RegInit(0.U(p.threadIdWidth.W))

  val accept = io.inValid && io.inReady
  val eLoadCLdi = eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_LDI)
  val eLoadCLwi = eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_LWI)
  val eLoadC = eLoadCLdi || eLoadCLwi
  val eLoadByteR =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LB) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LBU)
  val eLoadByteI =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LBI) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LBUI)
  val eLoadByte = eLoadByteR || eLoadByteI
  val eLoadReg =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LD) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LH) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LHU) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LW) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LR_W)
  val eLoadI = eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LDI)
  val eLoadLhi = eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LHI)
  val eLoadLhui = eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LHUI)
  val eLoadHalfI = eLoadLhi || eLoadLhui
  val eLoadLwi = eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LWI)
  val eLoadLwiU = eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LWI_U)
  val eLoadLwui = eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LWUI)
  val eLoadWordI = eLoadLwi || eLoadLwiU || eLoadLwui
  val eLoadPcr =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LD_PCR) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LB_PCR) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LBU_PCR) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LW_PCR) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LWU_PCR) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LD_PCR)
  val eLoadPairSecond = isHlImmediateLoadPair(eUop.opcode)
  val eLoadHlLongOffset = isHlLongOffsetLoad(eUop.opcode)
  val w2CompleteValid = !io.flushValid && w2Valid && w2Supported
  val w2CompleteFire = w2CompleteValid && io.completeReady
  val w2RetainForCompletion = w2CompleteValid && !io.completeReady
  val w2UnsupportedDrain = !io.flushValid && w2Valid && !w2Supported
  val w2IsFretStk = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_FRET_STK)
  val branchSrc0 = Mux(w2Uop.src(0).valid, w2SrcData(0), 0.U)
  val branchSrc1 = Mux(w2Uop.src(1).valid, w2SrcData(1), 0.U)
  val branchSetcRegisterSrc1 = setcRegisterSrcR(w2Uop.insnRaw, branchSrc1)
  val branchSetcImmediate = setcShiftedImmediate(w2Uop.opcode, w2Uop.insnRaw, w2Uop.imm)
  val branchTargetValid = branchSrc0 =/= 0.U
  val branchIsSetcEqCompressed = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_EQ)
  val branchIsSetcEqRegister = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_EQ)
  val branchIsSetcEqImmediate = isSetcEqImmediate(w2Uop.opcode)
  val branchIsSetcEq = branchIsSetcEqCompressed || branchIsSetcEqRegister || branchIsSetcEqImmediate
  val branchIsSetcNeCompressed = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_NE)
  val branchIsSetcNeRegister = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_NE)
  val branchIsSetcNeImmediate = isSetcNeImmediate(w2Uop.opcode)
  val branchIsSetcNe = branchIsSetcNeCompressed || branchIsSetcNeRegister || branchIsSetcNeImmediate
  val branchIsSetcAndi = isSetcAndImmediate(w2Uop.opcode)
  val branchIsSetcOri = isSetcOrImmediate(w2Uop.opcode)
  val branchIsSetcLt = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_LT)
  val branchIsSetcLti = isSetcLtImmediate(w2Uop.opcode)
  val branchIsSetcLtAny = branchIsSetcLt || branchIsSetcLti
  val branchIsSetcGeRegister = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_GE)
  val branchIsSetcGeImmediate = isSetcGeImmediate(w2Uop.opcode)
  val branchIsSetcGe = branchIsSetcGeRegister || branchIsSetcGeImmediate
  val branchIsSetcLtu = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTU)
  val branchIsSetcGeuRegister = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_GEU)
  val branchIsSetcGeuImmediate = isSetcGeuImmediate(w2Uop.opcode)
  val branchIsSetcGeu = branchIsSetcGeuRegister || branchIsSetcGeuImmediate
  val branchIsSetcLtui = isSetcLtuImmediate(w2Uop.opcode)
  val branchIsSetcTgt =
    w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_TGT) ||
      w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_TGT)
  val w2BranchConditionValid =
    w2CompleteFire && (
      branchIsSetcEq || branchIsSetcNe || branchIsSetcAndi || branchIsSetcOri ||
        branchIsSetcLtAny || branchIsSetcGe || branchIsSetcLtu || branchIsSetcGeu ||
        branchIsSetcLtui || branchIsSetcTgt)
  val branchEqNeSrc1 =
    Mux(branchIsSetcEqImmediate || branchIsSetcNeImmediate,
      branchSetcImmediate,
      Mux(branchIsSetcEqRegister || branchIsSetcNeRegister, branchSetcRegisterSrc1, branchSrc1))
  val w2BranchConditionTaken = Wire(Bool())
  w2BranchConditionTaken := false.B
  when(branchIsSetcTgt) {
    w2BranchConditionTaken := branchTargetValid
  }.elsewhen(branchIsSetcEq) {
    w2BranchConditionTaken := branchSrc0 === branchEqNeSrc1
  }.elsewhen(branchIsSetcNe) {
    w2BranchConditionTaken := branchSrc0 =/= branchEqNeSrc1
  }.elsewhen(branchIsSetcAndi) {
    w2BranchConditionTaken := (branchSrc0 & branchSetcImmediate) =/= 0.U
  }.elsewhen(branchIsSetcOri) {
    w2BranchConditionTaken := (branchSrc0 | branchSetcImmediate) =/= 0.U
  }.elsewhen(branchIsSetcLt) {
    w2BranchConditionTaken := branchSrc0.asSInt < branchSetcRegisterSrc1.asSInt
  }.elsewhen(branchIsSetcLti) {
    w2BranchConditionTaken := branchSrc0.asSInt < branchSetcImmediate.asSInt
  }.elsewhen(branchIsSetcGe) {
    w2BranchConditionTaken := branchSrc0.asSInt >= Mux(branchIsSetcGeImmediate, branchSetcImmediate, branchSetcRegisterSrc1).asSInt
  }.elsewhen(branchIsSetcLtu) {
    w2BranchConditionTaken := branchSrc0 < branchSetcRegisterSrc1
  }.elsewhen(branchIsSetcGeu) {
    w2BranchConditionTaken := branchSrc0 >= Mux(branchIsSetcGeuImmediate, branchSetcImmediate, branchSetcRegisterSrc1)
  }.elsewhen(branchIsSetcLtui) {
    w2BranchConditionTaken := branchSrc0 < branchSetcImmediate
  }
  // An explicit not-taken condition selects the synthetic RA-load form. An
  // absent condition instead keeps a live SETC/marker target authoritative;
  // if neither target owner exists, the only defined FRET.STK continuation is
  // the synthetic RA return.
  val eFretStkContextValid = eUop.fretStkContextValid
  val eFretStkLiveConditionValid = io.fretStkConditionValid || w2BranchConditionValid
  val eFretStkLiveConditionTaken = Mux(w2BranchConditionValid, w2BranchConditionTaken, io.fretStkConditionTaken)
  val eFretStkConditionValid = Mux(eFretStkContextValid, eUop.fretStkConditionValid, eFretStkLiveConditionValid)
  val eFretStkConditionTaken = Mux(eFretStkContextValid, eUop.fretStkConditionTaken, eFretStkLiveConditionTaken)
  val eFretStkFallbackTargetValid =
    Mux(eFretStkContextValid, eUop.fretStkFallbackTargetValid, io.fretStkFallbackTargetValid)
  val eFretStkFallbackTarget =
    Mux(eFretStkContextValid, eUop.fretStkFallbackTarget, io.fretStkFallbackTarget)
  val eFretStkHasRedirectTarget = setcTargetValid || eFretStkFallbackTargetValid
  val eFretStkConditionAllowsLoad =
    (eFretStkConditionValid && !eFretStkConditionTaken) ||
      (!eFretStkConditionValid && !eFretStkHasRedirectTarget)
  val eFretStkLoadReturn =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_FRET_STK) &&
      eFretStkConditionAllowsLoad &&
      fretStkRestoresRa(eUop.insnRaw)
  val eLoadLookupValid =
    !io.flushValid && eValid &&
      (eLoadC || eLoadByte || eLoadReg || eLoadI || eLoadHalfI || eLoadWordI ||
        eLoadPcr || eLoadPairSecond || eLoadHlLongOffset || eFretStkLoadReturn)
  val eLoadLookupAddr = Mux(
    eFretStkLoadReturn,
    fretStkRaLoadAddr(eStackPointerData, eUop.imm),
    Mux(
      eLoadPcr,
      pcrLoadAddr(eUop.pc, eUop.imm),
      Mux(
        eLoadPairSecond,
        hlImmediateLoadPairSecondAddr(eUop.opcode, eSrcData, eUop.imm),
        Mux(
          eLoadHlLongOffset,
          hlLongOffsetLoadAddr(eUop.opcode, eSrcData, eUop.imm),
          Mux(
            eLoadReg,
            loadRegAddr(eSrcData, eUop.insnRaw),
            Mux(
              eLoadByte,
              Mux(eLoadByteR, loadByteRegAddr(eSrcData, eUop.insnRaw), loadByteImmAddr(eSrcData, eUop.imm)),
              Mux(
                eLoadWordI,
                Mux(eLoadLwi || eLoadLwui, lwiScaledAddr(eSrcData, eUop.imm), lwiUnscaledAddr(eSrcData, eUop.imm)),
                Mux(
                  eLoadHalfI,
                  loadHalfImmAddr(eSrcData, eUop.imm),
                  Mux(
                    eLoadI,
                    ldiAddr(eSrcData, eUop.imm),
                    Mux(eLoadCLwi, cLwiAddr(eSrcData, eUop.imm), cLdiAddr(eSrcData, eUop.imm)))))))))))
  val eLoadPcrByte =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LB_PCR) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LBU_PCR)
  val eLoadPcrWord =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LW_PCR) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LWU_PCR)
  val eLoadRegHalf =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LH) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LHU)
  val eLoadRegWord =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LW) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LR_W)
  val eLoadLookupSize =
    Mux(
      eLoadByte || eLoadPcrByte,
      1.U(p.memSizeWidth.W),
      Mux(
        eLoadHalfI || eLoadRegHalf,
        2.U(p.memSizeWidth.W),
        Mux(
          eLoadPairSecond,
          hlImmediateLoadPairSize(eUop.opcode),
          Mux(
            eLoadHlLongOffset,
            hlLongOffsetLoadSize(eUop.opcode),
            Mux(
              eLoadCLwi || eLoadWordI || eLoadRegWord || eLoadPcrWord,
              4.U(p.memSizeWidth.W),
              8.U(p.memSizeWidth.W))))))
  val w2LoadByteRLookup =
    w2CompleteValid &&
      (w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LB) ||
        w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LBU))
  // E1 consumes the returned lookup data combinationally when it advances to
  // W1, so an older W2 byte-replay must never steal the single external read
  // port from a younger E1 load.  This matters for adjacent loads: otherwise
  // E1 can latch the older address's byte while still reporting its own
  // address in the completion row.
  val lookupReplayValid = !io.flushValid && w2LoadByteRLookup && !eLoadLookupValid
  val lookupValid = eLoadLookupValid || lookupReplayValid
  val lookupUop = Wire(new RenamedUop(p))
  lookupUop := Mux(lookupReplayValid, w2Uop, eUop)
  val lookupSrcData = Wire(Vec(3, UInt(p.immWidth.W)))
  for (idx <- 0 until 3) {
    lookupSrcData(idx) := Mux(lookupReplayValid, w2SrcData(idx), eSrcData(idx))
  }
  val lookupAddr = Mux(lookupReplayValid, loadByteRegAddr(w2SrcData, w2Uop.insnRaw), eLoadLookupAddr)
  val lookupSize = Mux(lookupReplayValid, 1.U(p.memSizeWidth.W), eLoadLookupSize)
  val lookupFretStkLoadReturn = eFretStkLoadReturn && !lookupReplayValid
  io.loadLookupValid := lookupValid
  io.loadLookupAddr := Mux(lookupValid, lookupAddr, 0.U)
  io.loadPairFirstLookupValid := eLoadLookupValid && eLoadPairSecond
  io.loadPairFirstLookupAddr :=
    Mux(io.loadPairFirstLookupValid,
      hlImmediateLoadPairFirstAddr(eUop.opcode, eSrcData, eUop.imm),
      0.U)
  io.loadLookupSize := Mux(io.loadLookupValid, lookupSize, 0.U)
  io.loadLookupReturnSignExtend := io.loadLookupValid && loadReturnSignExtend(lookupUop.opcode)
  // FRET.STK's optional RA read has architectural effects beyond a normal
  // load: it synthesizes the RA destination, restores SP, and redirects.
  // Keep that return on the direct execute path until LIQ owns all of those
  // effects as one transaction.
  val eLoadLiqEligible =
    eLoadLookupValid &&
      !eFretStkLoadReturn &&
      !eLoadPairSecond &&
      (eUop.opcode =/= opcode(FrontendOpcodeDecodeTable.OP_LR_W))
  io.loadLiqEligible := eLoadLiqEligible || lookupReplayValid
  io.loadLookupPc := Mux(io.loadLookupValid, lookupUop.pc, 0.U)
  io.loadLookupBid := Mux(io.loadLookupValid, lookupUop.bid, ROBID.disabled(p.robEntries))
  io.loadLookupGid := Mux(io.loadLookupValid, lookupUop.gid, ROBID.disabled(p.robEntries))
  io.loadLookupRid := Mux(io.loadLookupValid, lookupUop.rid, ROBID.disabled(p.robEntries))
  io.loadLookupLsId := Mux(io.loadLookupValid, lookupUop.lsid, 0.U)
  val noLoadLookupDst = Wire(new RenamedDestination(p))
  noLoadLookupDst := 0.U.asTypeOf(noLoadLookupDst)
  noLoadLookupDst.kind := DestinationKind.None
  io.loadLookupDst := Mux(io.loadLookupValid, lookupUop.dst(0), noLoadLookupDst)
  val loadLookupSourceTraceValid = io.loadLookupValid && !lookupFretStkLoadReturn
  io.loadLookupSourceTraceValid := loadLookupSourceTraceValid
  io.loadLookupSource0 := 0.U.asTypeOf(io.loadLookupSource0)
  io.loadLookupSource1 := 0.U.asTypeOf(io.loadLookupSource1)
  when(loadLookupSourceTraceValid) {
    io.loadLookupSource0.valid := lookupUop.src(0).valid && (lookupUop.src(0).operandClass === OperandClass.P)
    io.loadLookupSource0.reg := fitReg(lookupUop.src(0).archTag)
    io.loadLookupSource0.data := lookupSrcData(0)
    io.loadLookupSource1.valid := lookupUop.src(1).valid && (lookupUop.src(1).operandClass === OperandClass.P)
    io.loadLookupSource1.reg := fitReg(lookupUop.src(1).archTag)
    io.loadLookupSource1.data := lookupSrcData(1)
  }
  // A live-LIQ load is retired from E1 only after the allocator handshake.
  // In that mode LIQ, not the direct execute lookup, owns store-forward waits.
  val eLoadLiqRetire = eLoadLiqEligible && io.loadLiqEnable && io.loadLiqAccepted
  val eLoadWaitHold =
    eLoadLookupValid && Mux(eLoadLiqEligible && io.loadLiqEnable, !io.loadLiqAccepted, io.loadLookupWaitBlocked)
  val eIsDivideOrRemainder = eValid && isDivideOrRemainder(eUop.opcode)
  val divider = Module(new ReducedScalarDivider)
  divider.io.reqValid := eIsDivideOrRemainder
  divider.io.lhs := eSrcData(0)
  divider.io.rhs := eSrcData(1)
  divider.io.signed := isSignedDivideOrRemainder(eUop.opcode)
  divider.io.word := isWordDivideOrRemainder(eUop.opcode)
  divider.io.remainder := isRemainder(eUop.opcode)
  divider.io.flush := io.flushValid
  val eDivideComplete = eIsDivideOrRemainder && divider.io.respValid
  divider.io.respReady := eDivideComplete
  val eDivideHold = eIsDivideOrRemainder && !divider.io.respValid
  val scalarFp = Module(new ReducedScalarFpExecute)
  val eIsScalarFp = eValid && isScalarFp(eUop.opcode)
  scalarFp.io.inValid := eIsScalarFp
  scalarFp.io.opcode := eUop.opcode
  scalarFp.io.insnRaw := eUop.insnRaw(31, 0)
  scalarFp.io.srcL := eSrcData(0)
  scalarFp.io.srcR := eSrcData(1)
  scalarFp.io.flush := io.flushValid
  scalarFp.io.outReady := true.B
  val eResult = Mux(
    eIsDivideOrRemainder,
    divider.io.result,
    Mux(
      eIsScalarFp,
      scalarFp.io.outData,
      Mux(
        eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SSRGET),
        cycleCounter,
        resultFor(eUop.opcode, eUop.pc, eUop.insnRaw, eSrcData, eUop.imm, io.loadLookupData, eStackPointerData))))
  val ePairFirstResult =
    resultFor(
      eUop.opcode,
      eUop.pc,
      eUop.insnRaw,
      eSrcData,
      eUop.imm,
      io.loadPairFirstLookupData,
      eStackPointerData)
  val eSupported = eValid && isSupported(eUop.opcode) && (!eIsScalarFp || !scalarFp.io.unsupported)
  val eCanAdvanceToW1 = !eLoadWaitHold && !eDivideHold
  val pipeSafeAdvanceReady =
    eValid && eCanAdvanceToW1 && ScalarPipeSafety.fixedScalarAlu(eUop) && ScalarPipeSafety.fixedScalarAlu(io.in)
  val earlyReleaseCapture = accept && ScalarPipeSafety.fixedScalarAlu(io.in)

  when(io.flushValid) {
    eValid := false.B
    w1Valid := false.B
    w2Valid := false.B
    eEarlyReleased := false.B
    w1EarlyReleased := false.B
    w2EarlyReleased := false.B
    setcTargetValid := false.B
    setcTarget := 0.U
  }.elsewhen(w2RetainForCompletion) {
    w2Valid := w2Valid
    w2EarlyReleased := w2EarlyReleased
  }.otherwise {
    w2Valid := w1Valid
    w2Uop := w1Uop
    w2SrcData := w1SrcData
    w2Result := w1Result
    w2PairFirstResult := w1PairFirstResult
    w2Supported := w1Supported
    w2StackPointerData := w1StackPointerData
    w2FretStkLoadReturn := w1FretStkLoadReturn
    w2FretStkConditionValid := w1FretStkConditionValid
    w2FretStkConditionTaken := w1FretStkConditionTaken
    w2FretStkFallbackTargetValid := w1FretStkFallbackTargetValid
    w2FretStkFallbackTarget := w1FretStkFallbackTarget
    w2EarlyReleased := w1EarlyReleased

    when(!eCanAdvanceToW1) {
      w1Valid := false.B
      w1EarlyReleased := false.B
    }.otherwise {
      w1Valid := eValid && !eLoadLiqRetire
      w1Uop := eUop
      w1SrcData := eSrcData
      w1Result := eResult
      w1PairFirstResult := ePairFirstResult
      w1Supported := eSupported
      w1StackPointerData := eStackPointerData
      w1FretStkLoadReturn := eFretStkLoadReturn
      w1FretStkConditionValid := eFretStkConditionValid
      w1FretStkConditionTaken := eFretStkConditionTaken
      w1FretStkFallbackTargetValid := eFretStkFallbackTargetValid
      w1FretStkFallbackTarget := eFretStkFallbackTarget
      w1EarlyReleased := eEarlyReleased && !eLoadLiqRetire

      eValid := accept
      when(accept) {
        eUop := io.in
        eSrcData := io.srcData
        eStackPointerData := io.stackPointerData
        eEarlyReleased := earlyReleaseCapture
      }.otherwise {
        eEarlyReleased := false.B
      }
    }
  }

  when(io.flushValid) {
    earlyReleasePendingValid := false.B
    earlyReleasePendingBid := ROBID.disabled(p.robEntries)
    earlyReleasePendingGid := ROBID.disabled(p.robEntries)
    earlyReleasePendingRid := ROBID.disabled(p.robEntries)
    earlyReleasePendingStid := 0.U
  }.elsewhen(w2RetainForCompletion) {
    earlyReleasePendingValid := false.B
  }.otherwise {
    earlyReleasePendingValid := earlyReleaseCapture
    when(earlyReleaseCapture) {
      earlyReleasePendingBid := io.in.bid
      earlyReleasePendingGid := io.in.gid
      earlyReleasePendingRid := io.in.rid
      earlyReleasePendingStid := io.in.threadId
    }
  }

  io.inReady :=
    !io.flushValid && !w2RetainForCompletion && (!eValid || eLoadLiqRetire || eDivideComplete || pipeSafeAdvanceReady)
  io.accepted := accept
  io.busy := !io.flushValid && (eValid || w1Valid || w2Valid)
  io.loadWaitHold := !io.flushValid && eLoadWaitHold
  io.completeValid := w2CompleteValid
  io.completeFire := w2CompleteFire
  io.completeAccepted := w2CompleteFire
  io.completeRobValue := w2Uop.rid.value
  io.completePeId := Mux(io.completeValid, w2Uop.peId, 0.U)
  io.completeStid := Mux(io.completeValid, w2Uop.threadId, 0.U)
  io.completeTid := Mux(io.completeValid, w2Uop.threadId, 0.U)
  io.completeLsId := Mux(io.completeValid, w2Uop.lsid, 0.U)
  val w2FretStkConditionAllowsTarget = !w2FretStkConditionValid || w2FretStkConditionTaken
  val w2FretStkTargetTaken =
    (setcTargetValid || w2FretStkFallbackTargetValid) && w2FretStkConditionAllowsTarget
  val w2FretStkTarget = Mux(setcTargetValid, setcTarget, w2FretStkFallbackTarget)
  io.completeRow := completionRow(
    w2Uop,
    w2SrcData,
    w2Result,
    io.completeValid,
    setcTargetValid && w2FretStkConditionAllowsTarget,
    setcTarget,
    w2FretStkFallbackTargetValid && w2FretStkConditionAllowsTarget,
    w2FretStkFallbackTarget,
    w2FretStkLoadReturn,
    w2StackPointerData)
  io.completeDstPhysValid :=
    io.completeFire &&
      Mux(w2FretStkLoadReturn, true.B, w2Uop.dst(0).valid && (w2Uop.dst(0).kind === DestinationKind.Gpr))
  io.completeDstPhysTag := Mux(w2FretStkLoadReturn, 10.U(p.physRegWidth.W), w2Uop.dst(0).physTag)
  io.completeDstData := w2Result
  io.completePairFirstDstPhysValid :=
    io.completeFire &&
      w2Uop.pairFirstDst.valid &&
      (w2Uop.pairFirstDst.kind === DestinationKind.Gpr)
  io.completePairFirstDstPhysTag := w2Uop.pairFirstDst.physTag
  io.completePairFirstDstData := w2PairFirstResult
  for (idx <- 0 until 3) {
    io.completeSrcPhysValid(idx) :=
      io.completeFire && w2Uop.src(idx).valid && (w2Uop.src(idx).operandClass === OperandClass.P) &&
        (idx.U =/= 1.U || traceUsesSrc1(w2Uop.opcode))
    io.completeSrcPhysTag(idx) := w2Uop.src(idx).physTag
  }
  io.fretStkSpRestoreValid := io.completeFire && w2FretStkLoadReturn
  io.fretStkSpRestoreData := w2StackPointerData + w2Uop.imm
  val w2ScalarSpAccess = ScalarSpAccess.classify(w2Uop)
  io.scalarSpTerminalValid := io.completeFire && w2ScalarSpAccess.valid
  io.scalarSpTerminal := 0.U.asTypeOf(new ScalarSpTransaction(p))
  io.scalarSpTerminal.access := w2ScalarSpAccess
  io.scalarSpTerminal.stid := w2Uop.threadId
  io.scalarSpTerminal.bid := w2Uop.bid
  io.scalarSpTerminal.rid := w2Uop.rid
  io.scalarSpTerminal.epoch := 0.U
  io.scalarSpProducedValid := io.scalarSpTerminalValid && w2ScalarSpAccess.write
  io.scalarSpProducedData := Mux(
    w2IsFretStk,
    Mux(w2FretStkLoadReturn, w2StackPointerData + w2Uop.imm, w2StackPointerData),
    Mux(
      w2Uop.pairFirstDst.valid && (w2Uop.pairFirstDst.archTag === 1.U),
      w2PairFirstResult,
      w2Result))
  io.branchConditionValid := w2BranchConditionValid
  io.branchConditionTaken := w2BranchConditionTaken
  io.redirectValid := io.completeFire && w2IsFretStk && (w2FretStkLoadReturn || w2FretStkTargetTaken)
  io.redirectPc := Mux(w2FretStkLoadReturn, w2Result(p.pcWidth - 1, 0), w2FretStkTarget)
  io.redirectOrder := w2Uop.uid.uid
  when(io.completeFire && branchIsSetcTgt) {
    setcTargetValid := branchTargetValid
    setcTarget := branchSrc0(p.pcWidth - 1, 0)
  }.elsewhen(io.completeFire && w2IsFretStk) {
    setcTargetValid := false.B
    setcTarget := 0.U
  }
  io.releaseValid := (io.completeFire && !w2EarlyReleased) || w2UnsupportedDrain
  io.releaseBid := w2Uop.bid
  io.releaseGid := w2Uop.gid
  io.releaseRid := w2Uop.rid
  io.releaseStid := w2Uop.threadId
  io.earlyReleaseValid := !io.flushValid && earlyReleasePendingValid
  io.earlyReleaseBid := Mux(io.earlyReleaseValid, earlyReleasePendingBid, ROBID.disabled(p.robEntries))
  io.earlyReleaseGid := Mux(io.earlyReleaseValid, earlyReleasePendingGid, ROBID.disabled(p.robEntries))
  io.earlyReleaseRid := Mux(io.earlyReleaseValid, earlyReleasePendingRid, ROBID.disabled(p.robEntries))
  io.earlyReleaseStid := Mux(io.earlyReleaseValid, earlyReleasePendingStid, 0.U)
  // A first-pass live load hands its completion ownership to LIQ in E1, so
  // it must release its issue-queue residency there rather than waiting for a
  // W2 row that will intentionally never exist.  Keep this a separate port:
  // a W2 completion and an E1 LIQ handoff may occur in the same cycle.
  io.liqReleaseValid := !io.flushValid && eLoadLiqRetire
  io.liqReleaseBid := Mux(io.liqReleaseValid, eUop.bid, ROBID.disabled(p.robEntries))
  io.liqReleaseRid := Mux(io.liqReleaseValid, eUop.rid, ROBID.disabled(p.robEntries))
  io.liqReleaseStid := Mux(io.liqReleaseValid, eUop.threadId, 0.U)
  io.unsupported := w2UnsupportedDrain
  io.unsupportedOpcode := w2Uop.opcode
}

object ReducedScalarAluExecute {
  private val Mask64 = (BigInt(1) << 64) - 1
  private val Mask32 = (BigInt(1) << 32) - 1
  private val SignBit64 = BigInt(1) << 63
  private val SignBit32 = BigInt(1) << 31
  private val NoModifierInsn = BigInt(3) << 25

  def referenceLoadLookupOwner(e1LoadValid: Boolean, w2ByteReplayValid: Boolean): String =
    if (e1LoadValid) "e1" else if (w2ByteReplayValid) "w2-replay" else "none"

  def referenceHlImmediateLoadPairSecondAddress(opcode: Int, base: BigInt, imm: BigInt): BigInt = {
    val size =
      if (Set(FrontendOpcodeDecodeTable.OP_HL_LBIP, FrontendOpcodeDecodeTable.OP_HL_LBUIP).contains(opcode)) 1
      else if (Set(
        FrontendOpcodeDecodeTable.OP_HL_LHIP,
        FrontendOpcodeDecodeTable.OP_HL_LHIP_U,
        FrontendOpcodeDecodeTable.OP_HL_LHUIP,
        FrontendOpcodeDecodeTable.OP_HL_LHUIP_U).contains(opcode)) 2
      else if (Set(
        FrontendOpcodeDecodeTable.OP_HL_LWIP,
        FrontendOpcodeDecodeTable.OP_HL_LWIP_U,
        FrontendOpcodeDecodeTable.OP_HL_LWUIP,
        FrontendOpcodeDecodeTable.OP_HL_LWUIP_U).contains(opcode)) 4
      else 8
    val unscaled = Set(
      FrontendOpcodeDecodeTable.OP_HL_LHIP_U,
      FrontendOpcodeDecodeTable.OP_HL_LHUIP_U,
      FrontendOpcodeDecodeTable.OP_HL_LWIP_U,
      FrontendOpcodeDecodeTable.OP_HL_LWUIP_U,
      FrontendOpcodeDecodeTable.OP_HL_LDIP_U).contains(opcode)
    (base + (if (unscaled) signed64(imm) else signed64(imm) * size) + size) & Mask64
  }

  private def signed64(value: BigInt): BigInt = {
    val masked = value & Mask64
    if ((masked & SignBit64) != 0) masked - (BigInt(1) << 64) else masked
  }

  private def signedN(value: BigInt, width: Int): BigInt = {
    val mask = (BigInt(1) << width) - 1
    val signBit = BigInt(1) << (width - 1)
    val masked = value & mask
    if ((masked & signBit) != 0) masked - (BigInt(1) << width) else masked
  }

  private def signed32(value: BigInt): BigInt = {
    signedN(value, 32)
  }

  private def divisionResult(
      src0: BigInt,
      src1: BigInt,
      signedOperation: Boolean,
      wordOperation: Boolean,
      remainderOperation: Boolean): BigInt = {
    val width = if (wordOperation) 32 else 64
    val mask = (BigInt(1) << width) - 1
    val lhsBits = src0 & mask
    val rhsBits = src1 & mask
    val lhs = if (signedOperation) signedN(lhsBits, width) else lhsBits
    val rhs = if (signedOperation) signedN(rhsBits, width) else rhsBits
    val domainResult =
      if (rhsBits == 0) {
        if (remainderOperation) lhsBits else BigInt(0)
      } else if (remainderOperation) {
        lhs - ((lhs / rhs) * rhs)
      } else {
        lhs / rhs
      }
    val resultBits = domainResult & mask
    if (wordOperation) signed32(resultBits) & Mask64 else resultBits & Mask64
  }

  def referenceResult(
      opcode: Int,
      src0: BigInt,
      src1: BigInt,
      imm: BigInt): Option[BigInt] =
    referenceResultWithLoad(opcode, src0, src1, imm, loadData = 0)

  def referenceResultWithLoad(
      opcode: Int,
      src0: BigInt,
      src1: BigInt,
      imm: BigInt,
      loadData: BigInt): Option[BigInt] =
    referenceResultWithInsn(opcode, NoModifierInsn, src0, src1, imm, loadData)

  def referenceResultWithInsn(
      opcode: Int,
      insnRaw: BigInt,
      src0: BigInt,
      src1: BigInt,
      imm: BigInt,
      loadData: BigInt = 0): Option[BigInt] = {
    val addSubR = addSubSrcR(insnRaw, src1)
    val logicR = logicSrcR(insnRaw, src1)
    opcode match {
      case FrontendOpcodeDecodeTable.OP_ADD => Some((src0 + addSubR) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ADDW =>
        val low32 = (src0 + addSubR) & ((BigInt(1) << 32) - 1)
        Some(signed32(low32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SUB => Some((src0 - addSubR) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SUBW => Some(signed32((src0 - addSubR) & Mask32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ADDI => Some((src0 + imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ADDIW =>
        val low32 = ((src0 & ((BigInt(1) << 32) - 1)) + (imm & 0xfff)) & ((BigInt(1) << 32) - 1)
        Some(signed32(low32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SUBI => Some((src0 - imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SUBIW =>
        Some(signed32(((src0 & Mask32) - (imm & 0xfff)) & Mask32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_AND => Some((src0 & logicR) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ANDW =>
        val low32 = (src0 & logicR) & ((BigInt(1) << 32) - 1)
        Some(signed32(low32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ANDI => Some((src0 & imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ANDIW =>
        val low32 = (src0 & imm) & ((BigInt(1) << 32) - 1)
        Some(signed32(low32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ADDTPC => None
      case FrontendOpcodeDecodeTable.OP_SETRET => None
      case FrontendOpcodeDecodeTable.OP_HL_SETRET => None
      case FrontendOpcodeDecodeTable.OP_C_MOVI => Some(imm & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_MOVR => Some(src0 & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_ADD => Some((src0 + src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_ADDI => Some((src0 + imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SLLI => Some((src0 << (imm.toInt & 0x1f)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SRLI => Some((src0 & Mask64) >> (imm.toInt & 0x1f))
      case FrontendOpcodeDecodeTable.OP_C_AND => Some((src0 & src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_OR => Some((src0 | src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SUB => Some((src0 - src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SEXT_B => Some(signedN(src0, 8) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SEXT_H => Some(signedN(src0, 16) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SEXT_W => Some(signed32(src0) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_ZEXT_B => Some(src0 & 0xff)
      case FrontendOpcodeDecodeTable.OP_C_ZEXT_H => Some(src0 & 0xffff)
      case FrontendOpcodeDecodeTable.OP_C_ZEXT_W => Some(src0 & ((BigInt(1) << 32) - 1))
      case FrontendOpcodeDecodeTable.OP_C_SETRET => None
      case FrontendOpcodeDecodeTable.OP_C_CMP_EQI => Some(if ((src0 & Mask64) == (imm & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_C_CMP_NEI => Some(if ((src0 & Mask64) != (imm & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_AND => Some(if (((src0 & logicR) & Mask64) != 0) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_ANDI => Some(if (((src0 & imm) & Mask64) != 0) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_EQ => Some(if ((src0 & Mask64) == (addSubR & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_EQI => Some(if ((src0 & Mask64) == (imm & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_GE => Some(if (signed64(src0) >= signed64(addSubR)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_GEI => Some(if (signed64(src0) >= signed64(imm)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_GEU => Some(if ((src0 & Mask64) >= (addSubR & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_GEUI => Some(if ((src0 & Mask64) >= (imm & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_LT => Some(if (signed64(src0) < signed64(addSubR)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_LTI => Some(if (signed64(src0) < signed64(imm)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_LTU => Some(if ((src0 & Mask64) < (addSubR & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_LTUI => Some(if ((src0 & Mask64) < (imm & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_NE => Some(if ((src0 & Mask64) != (addSubR & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_NEI => Some(if ((src0 & Mask64) != (imm & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_OR => Some(if (((src0 | logicR) & Mask64) != 0) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_CMP_ORI => Some(if (((src0 | imm) & Mask64) != 0) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_C_LDI => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_LWI => Some(signed32(loadData) & Mask64)
      case FrontendOpcodeDecodeTable.OP_LB => Some(signedN(loadData, 8) & Mask64)
      case FrontendOpcodeDecodeTable.OP_LBI => Some(signedN(loadData, 8) & Mask64)
      case FrontendOpcodeDecodeTable.OP_LBU => Some(loadData & 0xFF)
      case FrontendOpcodeDecodeTable.OP_LBUI => Some(loadData & 0xFF)
      case FrontendOpcodeDecodeTable.OP_LD => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_LH => Some(signedN(loadData, 16) & Mask64)
      case FrontendOpcodeDecodeTable.OP_LHU => Some(loadData & 0xffff)
      case FrontendOpcodeDecodeTable.OP_LW => Some(signed32(loadData) & Mask64)
      case FrontendOpcodeDecodeTable.OP_LR_W => Some(signed32(loadData) & Mask64)
      case FrontendOpcodeDecodeTable.OP_LWUI => Some(loadData & Mask32)
      case FrontendOpcodeDecodeTable.OP_HL_LBIP => Some(signedN(loadData, 8) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LBUIP => Some(loadData & 0xff)
      case FrontendOpcodeDecodeTable.OP_HL_LHIP | FrontendOpcodeDecodeTable.OP_HL_LHIP_U =>
        Some(signedN(loadData, 16) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LHUIP | FrontendOpcodeDecodeTable.OP_HL_LHUIP_U =>
        Some(loadData & 0xffff)
      case FrontendOpcodeDecodeTable.OP_HL_LWIP | FrontendOpcodeDecodeTable.OP_HL_LWIP_U =>
        Some(signed32(loadData) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LWUIP | FrontendOpcodeDecodeTable.OP_HL_LWUIP_U =>
        Some(loadData & Mask32)
      case FrontendOpcodeDecodeTable.OP_HL_LDIP | FrontendOpcodeDecodeTable.OP_HL_LDIP_U =>
        Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LBI => Some(signedN(loadData, 8) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LBUI => Some(loadData & 0xff)
      case FrontendOpcodeDecodeTable.OP_HL_LHI | FrontendOpcodeDecodeTable.OP_HL_LHI_U =>
        Some(signedN(loadData, 16) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LHUI | FrontendOpcodeDecodeTable.OP_HL_LHUI_U =>
        Some(loadData & 0xffff)
      case FrontendOpcodeDecodeTable.OP_HL_LWI | FrontendOpcodeDecodeTable.OP_HL_LWI_U =>
        Some(signed32(loadData) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LWUI | FrontendOpcodeDecodeTable.OP_HL_LWUI_U =>
        Some(loadData & Mask32)
      case FrontendOpcodeDecodeTable.OP_HL_LDI | FrontendOpcodeDecodeTable.OP_HL_LDI_U =>
        Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SDI => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SWI => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SETC_EQ => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SETC_NE => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SETC_TGT => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_ANDI | FrontendOpcodeDecodeTable.OP_HL_SETC_ANDI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_EQ => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_EQI | FrontendOpcodeDecodeTable.OP_HL_SETC_EQI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_NE => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_NEI | FrontendOpcodeDecodeTable.OP_HL_SETC_NEI => Some(0)
      case FrontendOpcodeDecodeTable.OP_FRET_STK => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_SETC_LT => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_LTI | FrontendOpcodeDecodeTable.OP_HL_SETC_LTI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_GE => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_GEI | FrontendOpcodeDecodeTable.OP_HL_SETC_GEI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_LTU => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_GEU => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_GEUI | FrontendOpcodeDecodeTable.OP_HL_SETC_GEUI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_LTUI | FrontendOpcodeDecodeTable.OP_HL_SETC_LTUI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_ORI | FrontendOpcodeDecodeTable.OP_HL_SETC_ORI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_TGT => Some(0)
      case FrontendOpcodeDecodeTable.OP_FENTRY => Some((src1 - imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_LUI => Some(imm & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LUI => Some(imm & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LIS => Some(imm & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LIU => Some(imm & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_ADDI => Some((src0 + imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_ADDIW => Some(signed32((src0 + imm) & Mask32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_ANDI => Some((src0 & imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_ANDIW => Some(signed32((src0 & imm) & Mask32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_CMP_ANDI => Some(if (((src0 & imm) & Mask64) != 0) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_HL_CMP_EQI => Some(if ((src0 & Mask64) == (imm & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_HL_CMP_GEI => Some(if (signed64(src0) >= signed64(imm)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_HL_CMP_GEUI => Some(if ((src0 & Mask64) >= (imm & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_HL_CMP_LTI => Some(if (signed64(src0) < signed64(imm)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_HL_CMP_LTUI => Some(if ((src0 & Mask64) < (imm & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_HL_CMP_NEI => Some(if ((src0 & Mask64) != (imm & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_HL_CMP_ORI => Some(if (((src0 | imm) & Mask64) != 0) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_HL_ORI => Some((src0 | imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_ORIW => Some(signed32((src0 | imm) & Mask32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_SUBI => Some((src0 - imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_SUBIW => Some(signed32((src0 - imm) & Mask32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_XORI => Some((src0 ^ imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_XORIW => Some(signed32((src0 ^ imm) & Mask32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LD_PCR => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LB_PCR => Some(signedN(loadData, 8) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LBU_PCR => Some(loadData & 0xff)
      case FrontendOpcodeDecodeTable.OP_HL_LW_PCR => Some(signed32(loadData) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LWU_PCR => Some(loadData & Mask32)
      case FrontendOpcodeDecodeTable.OP_HL_SB_PCR => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SD_PCR => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SH_PCR => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SW_PCR => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SDI_PO => Some((src1 + ((imm << 3) & Mask64)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_SDI_PR => Some((src1 + ((imm << 3) & Mask64)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_SWI_PO => Some((src1 + ((imm << 2) & Mask64)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_SWIP => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SWIP_U => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SDIP => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SDIP_U => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SBI => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SDI => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SDI_U => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SHI => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SHI_U => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SWI => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SWI_U => Some(0)
      case FrontendOpcodeDecodeTable.OP_LD_PCR => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_LDI => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_LHI => Some(signedN(loadData, 16) & Mask64)
      case FrontendOpcodeDecodeTable.OP_LHUI => Some(loadData & 0xffff)
      case FrontendOpcodeDecodeTable.OP_LWI => Some(signed32(loadData) & Mask64)
      case FrontendOpcodeDecodeTable.OP_LWI_U => Some(signed32(loadData) & Mask64)
      case FrontendOpcodeDecodeTable.OP_DIV => Some(divisionResult(src0, src1, signedOperation = true, wordOperation = false, remainderOperation = false))
      case FrontendOpcodeDecodeTable.OP_DIVU => Some(divisionResult(src0, src1, signedOperation = false, wordOperation = false, remainderOperation = false))
      case FrontendOpcodeDecodeTable.OP_DIVW => Some(divisionResult(src0, src1, signedOperation = true, wordOperation = true, remainderOperation = false))
      case FrontendOpcodeDecodeTable.OP_DIVUW => Some(divisionResult(src0, src1, signedOperation = false, wordOperation = true, remainderOperation = false))
      case FrontendOpcodeDecodeTable.OP_MUL => Some((src0 * src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_MULU => Some((src0 * src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_MULW =>
        val low32 = (src0 * src1) & ((BigInt(1) << 32) - 1)
        Some(signed32(low32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_MULUW =>
        val low32 = (src0 * src1) & Mask32
        Some(signed32(low32) & Mask64)
      // The public two-source reference helper cannot express MADD's SrcD;
      // use referenceMadd for that three-source instruction.
      case FrontendOpcodeDecodeTable.OP_BCNT => Some(BigInt(bitField(insnRaw, src0).bitCount) & Mask64)
      case FrontendOpcodeDecodeTable.OP_BIC => Some((src0 & ~bitFieldMask(insnRaw)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_BIS => Some((src0 | bitFieldMask(insnRaw)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_BXS =>
        val (field, width) = bitFieldWithWidth(insnRaw, src0)
        Some(signedN(field, width) & Mask64)
      case FrontendOpcodeDecodeTable.OP_BXU => Some(bitField(insnRaw, src0) & Mask64)
      case FrontendOpcodeDecodeTable.OP_CLZ =>
        val (field, width) = bitFieldWithWidth(insnRaw, src0)
        Some(BigInt(if (field == 0) width else width - field.bitLength) & Mask64)
      case FrontendOpcodeDecodeTable.OP_CTZ =>
        val (field, width) = bitFieldWithWidth(insnRaw, src0)
        Some(BigInt(if (field == 0) width else field.lowestSetBit) & Mask64)
      case FrontendOpcodeDecodeTable.OP_MAX =>
        Some(if (signed64(src0) >= signed64(src1)) src0 & Mask64 else src1 & Mask64)
      case FrontendOpcodeDecodeTable.OP_MAXU =>
        Some(if ((src0 & Mask64) >= (src1 & Mask64)) src0 & Mask64 else src1 & Mask64)
      case FrontendOpcodeDecodeTable.OP_MIN =>
        Some(if (signed64(src0) <= signed64(src1)) src0 & Mask64 else src1 & Mask64)
      case FrontendOpcodeDecodeTable.OP_MINU =>
        Some(if ((src0 & Mask64) <= (src1 & Mask64)) src0 & Mask64 else src1 & Mask64)
      case FrontendOpcodeDecodeTable.OP_SB => Some(0)
      case FrontendOpcodeDecodeTable.OP_SH => Some(0)
      case FrontendOpcodeDecodeTable.OP_SW => Some(0)
      case FrontendOpcodeDecodeTable.OP_SBI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SD => Some(0)
      case FrontendOpcodeDecodeTable.OP_SDI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SHI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SWI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SLL => Some((src0 << ((src1 & 0x3f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SLLI => Some((src0 << ((imm & 0x3f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SLLW =>
        Some(signed32(((src0 & Mask32) << ((src1 & 0x1f).toInt)) & Mask32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SLLIW =>
        Some(signed32(((src0 & Mask32) << ((imm & 0x1f).toInt)) & Mask32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SRL => Some((src0 & Mask64) >> ((src1 & 0x3f).toInt))
      case FrontendOpcodeDecodeTable.OP_SRLI => Some((src0 & Mask64) >> ((imm & 0x3f).toInt))
      case FrontendOpcodeDecodeTable.OP_SRLW =>
        Some(signed32((src0 & Mask32) >> ((src1 & 0x1f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SRLIW =>
        Some(signed32((src0 & Mask32) >> ((imm & 0x1f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SRA => Some((signed64(src0) >> ((src1 & 0x3f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SRAI => Some((signed64(src0) >> ((imm & 0x3f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SRAW =>
        Some((signed32(src0) >> ((src1 & 0x1f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SRAIW =>
        Some((signed32(src0) >> ((imm & 0x1f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_REM => Some(divisionResult(src0, src1, signedOperation = true, wordOperation = false, remainderOperation = true))
      case FrontendOpcodeDecodeTable.OP_REMU => Some(divisionResult(src0, src1, signedOperation = false, wordOperation = false, remainderOperation = true))
      case FrontendOpcodeDecodeTable.OP_REMW => Some(divisionResult(src0, src1, signedOperation = true, wordOperation = true, remainderOperation = true))
      case FrontendOpcodeDecodeTable.OP_REMUW => Some(divisionResult(src0, src1, signedOperation = false, wordOperation = true, remainderOperation = true))
      case FrontendOpcodeDecodeTable.OP_SSRSET => Some(0)
      case FrontendOpcodeDecodeTable.OP_SSRGET => None
      case FrontendOpcodeDecodeTable.OP_OR => Some((src0 | logicR) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ORW => Some(signed32((src0 | logicR) & Mask32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ORI => Some((src0 | imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ORIW => Some(signed32((src0 | imm) & Mask32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_XOR => Some((src0 ^ logicR) & Mask64)
      case FrontendOpcodeDecodeTable.OP_XORI => Some((src0 ^ imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_XORIW => Some(signed32((src0 ^ imm) & Mask32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_XORW => Some(signed32((src0 ^ logicR) & Mask32) & Mask64)
      case _ => None
    }
  }

  private def srcRType(insnRaw: BigInt): Int =
    ((insnRaw >> 25) & 0x3).toInt

  private def srcRShamt(insnRaw: BigInt): Int =
    ((insnRaw >> 27) & 0x1f).toInt

  private def isHlSetcImmediate(opcode: Int): Boolean =
    Set(
      FrontendOpcodeDecodeTable.OP_HL_SETC_ANDI,
      FrontendOpcodeDecodeTable.OP_HL_SETC_EQI,
      FrontendOpcodeDecodeTable.OP_HL_SETC_GEI,
      FrontendOpcodeDecodeTable.OP_HL_SETC_GEUI,
      FrontendOpcodeDecodeTable.OP_HL_SETC_LTI,
      FrontendOpcodeDecodeTable.OP_HL_SETC_LTUI,
      FrontendOpcodeDecodeTable.OP_HL_SETC_NEI,
      FrontendOpcodeDecodeTable.OP_HL_SETC_ORI).contains(opcode)

  private def setcImmediateShamt(opcode: Int, insnRaw: BigInt): Int =
    if (isHlSetcImmediate(opcode)) ((insnRaw >> 23) & 0x1f).toInt
    else ((insnRaw >> 7) & 0x1f).toInt

  private def setcShiftedImmediate(opcode: Int, insnRaw: BigInt, imm: BigInt): BigInt =
    (imm << setcImmediateShamt(opcode, insnRaw)) & Mask64

  private def addSubSrcR(insnRaw: BigInt, value: BigInt): BigInt = {
    val converted = srcRType(insnRaw) match {
      case 0 => signed32(value) & Mask64
      case 1 => value & ((BigInt(1) << 32) - 1)
      case 2 => (-value) & Mask64
      case _ => value & Mask64
    }
    (converted << srcRShamt(insnRaw)) & Mask64
  }

  private def logicSrcR(insnRaw: BigInt, value: BigInt): BigInt = {
    val converted = srcRType(insnRaw) match {
      case 0 => signed32(value) & Mask64
      case 1 => value & ((BigInt(1) << 32) - 1)
      case 2 => (~value) & Mask64
      case _ => value & Mask64
    }
    (converted << srcRShamt(insnRaw)) & Mask64
  }

  private def bitFieldWithWidth(insnRaw: BigInt, value: BigInt): (BigInt, Int) = {
    val lsb = ((insnRaw >> 26) & 0x3f).toInt
    val width = (((insnRaw >> 20) & 0x3f) + 1).toInt
    val rotated = rotateRight64(value, lsb)
    (rotated & ((BigInt(1) << width) - 1), width)
  }

  private def bitField(insnRaw: BigInt, value: BigInt): BigInt =
    bitFieldWithWidth(insnRaw, value)._1

  private def bitFieldMask(insnRaw: BigInt): BigInt = {
    val lsb = ((insnRaw >> 26) & 0x3f).toInt
    val width = (((insnRaw >> 20) & 0x3f) + 1).toInt
    rotateLeft64((BigInt(1) << width) - 1, lsb)
  }

  private def rotateRight64(value: BigInt, amount: Int): BigInt = {
    val shift = amount & 0x3f
    if (shift == 0) value & Mask64
    else (((value & Mask64) >> shift) | ((value & Mask64) << (64 - shift))) & Mask64
  }

  private def rotateLeft64(value: BigInt, amount: Int): BigInt = {
    val shift = amount & 0x3f
    if (shift == 0) value & Mask64
    else (((value & Mask64) << shift) | ((value & Mask64) >> (64 - shift))) & Mask64
  }

  def referenceMadd(srcD: BigInt, srcL: BigInt, srcR: BigInt): BigInt =
    (srcD + (srcL * srcR)) & Mask64

  private def setcRegisterSrcR(insnRaw: BigInt, value: BigInt): BigInt =
    srcRType(insnRaw) match {
      case 1 => signed32(value) & Mask64
      case 2 => value & ((BigInt(1) << 32) - 1)
      case _ => value & Mask64
    }

  def referenceCsel(srcL: BigInt, srcR: BigInt, srcP: BigInt): BigInt =
    if ((srcP & Mask64) != 0) srcL & Mask64 else srcR & Mask64

  def referenceSdIndexedAddress(srcL: BigInt, srcR: BigInt): BigInt =
    (srcL + ((srcR & Mask64) << 3)) & Mask64

  def referenceSdIndexedData(srcD: BigInt): BigInt =
    srcD & Mask64

  def referenceShiAddress(srcR: BigInt, imm: BigInt): BigInt =
    (srcR + ((imm << 1) & Mask64)) & Mask64

  def referenceShiData(srcL: BigInt): BigInt =
    srcL & Mask64

  def referenceHlSwiPoAddress(srcR: BigInt): BigInt =
    srcR & Mask64

  def referenceHlSwiPoData(srcD: BigInt): BigInt =
    srcD & Mask32

  def referenceHlSdiPoAddress(srcR: BigInt): BigInt =
    srcR & Mask64

  def referenceHlSdiPoData(srcD: BigInt): BigInt =
    srcD & Mask64

  def referenceFentryFirstSaveAddress(oldSp: BigInt): BigInt =
    (oldSp - 8) & Mask64

  def referenceLwiAddress(srcL: BigInt, imm: BigInt): BigInt =
    (srcL + ((imm << 2) & Mask64)) & Mask64

  def referenceLwiUAddress(srcL: BigInt, imm: BigInt): BigInt =
    (srcL + imm) & Mask64

  def referenceLhiAddress(srcL: BigInt, imm: BigInt): BigInt =
    (srcL + ((imm << 1) & Mask64)) & Mask64

  def referenceHlLongOffsetLoadAddress(opcode: Int, srcL: BigInt, imm: BigInt): BigInt = {
    val size =
      if (Set(FrontendOpcodeDecodeTable.OP_HL_LBI, FrontendOpcodeDecodeTable.OP_HL_LBUI).contains(opcode)) 1
      else if (Set(
        FrontendOpcodeDecodeTable.OP_HL_LHI,
        FrontendOpcodeDecodeTable.OP_HL_LHI_U,
        FrontendOpcodeDecodeTable.OP_HL_LHUI,
        FrontendOpcodeDecodeTable.OP_HL_LHUI_U).contains(opcode)) 2
      else if (Set(
        FrontendOpcodeDecodeTable.OP_HL_LWI,
        FrontendOpcodeDecodeTable.OP_HL_LWI_U,
        FrontendOpcodeDecodeTable.OP_HL_LWUI,
        FrontendOpcodeDecodeTable.OP_HL_LWUI_U).contains(opcode)) 4
      else 8
    val unscaled = Set(
      FrontendOpcodeDecodeTable.OP_HL_LDI_U,
      FrontendOpcodeDecodeTable.OP_HL_LHI_U,
      FrontendOpcodeDecodeTable.OP_HL_LHUI_U,
      FrontendOpcodeDecodeTable.OP_HL_LWI_U,
      FrontendOpcodeDecodeTable.OP_HL_LWUI_U).contains(opcode)
    (srcL + (if (unscaled) signed64(imm) else signed64(imm) * size)) & Mask64
  }

  def referenceHlLongOffsetStoreAddress(opcode: Int, srcR: BigInt, imm: BigInt): BigInt = {
    val size =
      if (opcode == FrontendOpcodeDecodeTable.OP_HL_SBI) 1
      else if (Set(FrontendOpcodeDecodeTable.OP_HL_SHI, FrontendOpcodeDecodeTable.OP_HL_SHI_U).contains(opcode)) 2
      else if (Set(FrontendOpcodeDecodeTable.OP_HL_SWI, FrontendOpcodeDecodeTable.OP_HL_SWI_U).contains(opcode)) 4
      else 8
    val unscaled = Set(
      FrontendOpcodeDecodeTable.OP_HL_SDI_U,
      FrontendOpcodeDecodeTable.OP_HL_SHI_U,
      FrontendOpcodeDecodeTable.OP_HL_SWI_U).contains(opcode)
    (srcR + (if (unscaled) signed64(imm) else signed64(imm) * size)) & Mask64
  }

  def referenceResult(opcode: Int, pc: BigInt, src0: BigInt, src1: BigInt, imm: BigInt): Option[BigInt] =
    opcode match {
      case FrontendOpcodeDecodeTable.OP_ADDTPC => Some(((pc & ~BigInt(0xfff)) + imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SETRET => Some((pc + imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_SETRET => Some((pc + imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SETRET => Some((pc + imm) & Mask64)
      case _ => referenceResult(opcode, src0, src1, imm)
    }

  def referenceBranchCondition(
      opcode: Int,
      src0: BigInt,
      src1: BigInt,
      src0Valid: Boolean = true,
      src1Valid: Boolean = true): Option[Boolean] =
    referenceBranchConditionWithInsn(opcode, NoModifierInsn, src0, src1, src0Valid, src1Valid)

  def referenceBranchConditionWithInsn(
      opcode: Int,
      insnRaw: BigInt,
      src0: BigInt,
      src1: BigInt,
      src0Valid: Boolean = true,
      src1Valid: Boolean = true): Option[Boolean] =
    opcode match {
      case FrontendOpcodeDecodeTable.OP_C_SETC_EQ =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        val rhs = if (src1Valid) src1 & Mask64 else BigInt(0)
        Some(lhs == rhs)
      case FrontendOpcodeDecodeTable.OP_C_SETC_NE =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        val rhs = if (src1Valid) src1 & Mask64 else BigInt(0)
        Some(lhs != rhs)
      case FrontendOpcodeDecodeTable.OP_C_SETC_TGT =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        Some(lhs != 0)
      case FrontendOpcodeDecodeTable.OP_SETC_EQ =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        val rhs = if (src1Valid) setcRegisterSrcR(insnRaw, src1) else BigInt(0)
        Some(lhs == rhs)
      case FrontendOpcodeDecodeTable.OP_SETC_NE =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        val rhs = if (src1Valid) setcRegisterSrcR(insnRaw, src1) else BigInt(0)
        Some(lhs != rhs)
      case FrontendOpcodeDecodeTable.OP_SETC_EQI | FrontendOpcodeDecodeTable.OP_HL_SETC_EQI =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        Some(lhs == setcShiftedImmediate(opcode, insnRaw, src1))
      case FrontendOpcodeDecodeTable.OP_SETC_NEI | FrontendOpcodeDecodeTable.OP_HL_SETC_NEI =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        Some(lhs != setcShiftedImmediate(opcode, insnRaw, src1))
      case FrontendOpcodeDecodeTable.OP_SETC_ANDI | FrontendOpcodeDecodeTable.OP_HL_SETC_ANDI =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        Some((lhs & setcShiftedImmediate(opcode, insnRaw, src1)) != 0)
      case FrontendOpcodeDecodeTable.OP_SETC_ORI | FrontendOpcodeDecodeTable.OP_HL_SETC_ORI =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        Some((lhs | setcShiftedImmediate(opcode, insnRaw, src1)) != 0)
      case FrontendOpcodeDecodeTable.OP_SETC_LT =>
        val lhs = if (src0Valid) signed64(src0) else BigInt(0)
        val rhs = if (src1Valid) signed64(setcRegisterSrcR(insnRaw, src1)) else BigInt(0)
        Some(lhs < rhs)
      case FrontendOpcodeDecodeTable.OP_SETC_LTI | FrontendOpcodeDecodeTable.OP_HL_SETC_LTI =>
        val lhs = if (src0Valid) signed64(src0) else BigInt(0)
        Some(lhs < signed64(setcShiftedImmediate(opcode, insnRaw, src1)))
      case FrontendOpcodeDecodeTable.OP_SETC_GE =>
        val lhs = if (src0Valid) signed64(src0) else BigInt(0)
        val rhs = if (src1Valid) signed64(setcRegisterSrcR(insnRaw, src1)) else BigInt(0)
        Some(lhs >= rhs)
      case FrontendOpcodeDecodeTable.OP_SETC_GEI | FrontendOpcodeDecodeTable.OP_HL_SETC_GEI =>
        val lhs = if (src0Valid) signed64(src0) else BigInt(0)
        Some(lhs >= signed64(setcShiftedImmediate(opcode, insnRaw, src1)))
      case FrontendOpcodeDecodeTable.OP_SETC_LTU =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        val rhs = if (src1Valid) setcRegisterSrcR(insnRaw, src1) else BigInt(0)
        Some(lhs < rhs)
      case FrontendOpcodeDecodeTable.OP_SETC_GEU =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        val rhs = if (src1Valid) setcRegisterSrcR(insnRaw, src1) else BigInt(0)
        Some(lhs >= rhs)
      case FrontendOpcodeDecodeTable.OP_SETC_LTUI | FrontendOpcodeDecodeTable.OP_HL_SETC_LTUI =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        Some(lhs < setcShiftedImmediate(opcode, insnRaw, src1))
      case FrontendOpcodeDecodeTable.OP_SETC_GEUI | FrontendOpcodeDecodeTable.OP_HL_SETC_GEUI =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        Some(lhs >= setcShiftedImmediate(opcode, insnRaw, src1))
      case FrontendOpcodeDecodeTable.OP_SETC_TGT =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        Some(lhs != 0)
      case _ => None
    }

  def referenceFretStkNextPc(
      pc: BigInt,
      lenBytes: Int,
      setcTarget: Option[BigInt],
      fallbackTarget: Option[BigInt]): BigInt =
    (setcTarget.orElse(fallbackTarget).getOrElse(pc + lenBytes)) & Mask64

  def referenceFretStkLoadsReturn(
      restoresRa: Boolean,
      conditionValid: Boolean,
      conditionTaken: Boolean,
      targetPending: Boolean = false): Boolean = {
    restoresRa && ((conditionValid && !conditionTaken) || (!conditionValid && !targetPending))
  }

  def referenceFretStkSnapshotLoadsReturn(
      restoresRa: Boolean,
      contextValid: Boolean,
      snapshotConditionValid: Boolean,
      snapshotConditionTaken: Boolean,
      snapshotTargetPending: Boolean,
      liveConditionValid: Boolean,
      liveConditionTaken: Boolean,
      liveTargetPending: Boolean): Boolean = {
    val conditionValid = if (contextValid) snapshotConditionValid else liveConditionValid
    val conditionTaken = if (contextValid) snapshotConditionTaken else liveConditionTaken
    val targetPending = if (contextValid) snapshotTargetPending else liveTargetPending
    referenceFretStkLoadsReturn(restoresRa, conditionValid, conditionTaken, targetPending)
  }

  def referenceFretStkDecodeContextValid(
      isFretStk: Boolean,
      activeBlockConditional: Boolean,
      conditionValid: Boolean): Boolean =
    isFretStk && (!activeBlockConditional || conditionValid)

  def referenceFretStkProducedSp(
      stackPointerData: BigInt,
      frameSize: BigInt,
      loadsReturn: Boolean): BigInt =
    (if (loadsReturn) stackPointerData + frameSize else stackPointerData) & Mask64
}
