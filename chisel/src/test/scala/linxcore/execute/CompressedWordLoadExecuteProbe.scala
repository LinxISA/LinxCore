package linxcore.execute

import circt.stage.ChiselStage
import chisel3._
import linxcore.commit.CommitTraceParams
import linxcore.common.{DispatchTarget, InterfaceParams, OperandClass}
import linxcore.frontend.{FrontendOpcodeDecodeTable, FrontendRegAliasClassify}

class CompressedWordLoadExecuteProbeIO extends Bundle {
  val inValid = Input(Bool())
  val opcode = Input(UInt(12.W))
  val pc = Input(UInt(64.W))
  val insnRaw = Input(UInt(64.W))
  val insnLen = Input(UInt(4.W))
  val imm = Input(UInt(64.W))
  val src0Data = Input(UInt(64.W))
  val src1Data = Input(UInt(64.W))
  val loadLookupData = Input(UInt(64.W))
  val loadLookupWaitBlocked = Input(Bool())
  val loadLiqEnable = Input(Bool())
  val loadLiqAccepted = Input(Bool())
  val bidValid = Input(Bool())
  val bidWrap = Input(Bool())
  val bidValue = Input(UInt(6.W))
  val gidValid = Input(Bool())
  val gidWrap = Input(Bool())
  val gidValue = Input(UInt(6.W))
  val ridValid = Input(Bool())
  val ridWrap = Input(Bool())
  val ridValue = Input(UInt(6.W))
  val lsid = Input(UInt(32.W))
  val blockBid = Input(UInt(64.W))
  val dstArchTag = Input(UInt(6.W))
  val dstPhysTag = Input(UInt(6.W))
  val srcArchTag = Input(UInt(6.W))
  val srcPhysTag = Input(UInt(6.W))

  val inReady = Output(Bool())
  val accepted = Output(Bool())
  val busy = Output(Bool())
  val loadWaitHold = Output(Bool())
  val unsupported = Output(Bool())
  val unsupportedOpcode = Output(UInt(12.W))

  val loadLookupValid = Output(Bool())
  val loadLookupAddr = Output(UInt(64.W))
  val loadLookupSize = Output(UInt(4.W))
  val loadLookupReturnSignExtend = Output(Bool())
  val loadLookupPc = Output(UInt(64.W))
  val loadLookupBidValid = Output(Bool())
  val loadLookupBidWrap = Output(Bool())
  val loadLookupBidValue = Output(UInt(6.W))
  val loadLookupGidValid = Output(Bool())
  val loadLookupGidWrap = Output(Bool())
  val loadLookupGidValue = Output(UInt(6.W))
  val loadLookupRidValid = Output(Bool())
  val loadLookupRidWrap = Output(Bool())
  val loadLookupRidValue = Output(UInt(6.W))
  val loadLookupLsId = Output(UInt(32.W))
  val loadLookupDstValid = Output(Bool())
  val loadLookupDstKind = Output(UInt(2.W))
  val loadLookupDstArchTag = Output(UInt(6.W))
  val loadLookupDstRelTag = Output(UInt(6.W))
  val loadLookupDstPhysTag = Output(UInt(6.W))

  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(6.W))
  val completeLsId = Output(UInt(32.W))
  val completeDstPhysValid = Output(Bool())
  val completeDstPhysTag = Output(UInt(6.W))
  val completeDstData = Output(UInt(64.W))
  val completeRowValid = Output(Bool())
  val completeRowBid = Output(UInt(32.W))
  val completeRowGid = Output(UInt(32.W))
  val completeRowRid = Output(UInt(32.W))
  val completeRowRobValid = Output(Bool())
  val completeRowRobWrap = Output(Bool())
  val completeRowRobValue = Output(UInt(6.W))
  val completeRowPc = Output(UInt(64.W))
  val completeRowDstValid = Output(Bool())
  val completeRowDstReg = Output(UInt(6.W))
  val completeRowDstData = Output(UInt(64.W))
  val completeRowWbValid = Output(Bool())
  val completeRowWbReg = Output(UInt(6.W))
  val completeRowWbData = Output(UInt(64.W))
  val completeRowMemValid = Output(Bool())
  val completeRowMemIsStore = Output(Bool())
  val completeRowMemAddr = Output(UInt(64.W))
  val completeRowMemRdata = Output(UInt(64.W))
  val completeRowMemSize = Output(UInt(4.W))
}

class CompressedWordLoadExecuteProbe extends Module {
  private val p = InterfaceParams()
  private val trace = CommitTraceParams(
    commitWidth = p.commitWidth,
    robValueWidth = p.robIndexWidth,
    blockBidWidth = p.blockBidWidth,
    pcWidth = p.pcWidth,
    insnWidth = p.insnWidth,
    lenWidth = p.lenWidth)

  val io = IO(new CompressedWordLoadExecuteProbeIO)
  val execute = Module(new ReducedScalarAluExecute(p, trace))
  execute.io.completeReady := true.B

  execute.io.inValid := io.inValid
  execute.io.in := 0.U.asTypeOf(execute.io.in)
  execute.io.in.valid := io.inValid
  execute.io.in.peId := 0.U
  execute.io.in.threadId := 0.U
  execute.io.in.pc := io.pc
  execute.io.in.opcode := io.opcode
  execute.io.in.dispatchTarget := DispatchTarget.Alu
  execute.io.in.imm := io.imm
  execute.io.in.immValid := true.B
  execute.io.in.rid.valid := io.ridValid
  execute.io.in.rid.wrap := io.ridWrap
  execute.io.in.rid.value := io.ridValue
  execute.io.in.bid.valid := io.bidValid
  execute.io.in.bid.wrap := io.bidWrap
  execute.io.in.bid.value := io.bidValue
  execute.io.in.gid.valid := io.gidValid
  execute.io.in.gid.wrap := io.gidWrap
  execute.io.in.gid.value := io.gidValue
  execute.io.in.lsid := io.lsid
  execute.io.in.isLoad := true.B
  execute.io.in.isStore := false.B
  execute.io.in.insnLen := io.insnLen
  execute.io.in.insnRaw := io.insnRaw
  execute.io.in.blockBidValid := true.B
  execute.io.in.blockBid := io.blockBid
  execute.io.in.src(0).valid := true.B
  execute.io.in.src(0).operandClass := OperandClass.P
  execute.io.in.src(0).archTag := io.srcArchTag
  execute.io.in.src(0).physTag := io.srcPhysTag
  execute.io.in.src(0).ready := true.B
  val classifiedDst = FrontendRegAliasClassify.destination(p, true.B, io.dstArchTag)
  execute.io.in.dst(0).valid := true.B
  execute.io.in.dst(0).kind := classifiedDst.kind
  execute.io.in.dst(0).archTag := classifiedDst.archTag
  execute.io.in.dst(0).relTag := classifiedDst.relTag
  execute.io.in.dst(0).physTag := io.dstPhysTag

  execute.io.srcData(0) := io.src0Data
  execute.io.srcData(1) := io.src1Data
  execute.io.srcData(2) := 0.U
  execute.io.loadLookupData := io.loadLookupData
  execute.io.loadPairFirstLookupData := 0.U
  execute.io.loadLookupWaitBlocked := io.loadLookupWaitBlocked
  execute.io.loadLiqEnable := io.loadLiqEnable
  execute.io.loadLiqAccepted := io.loadLiqAccepted
  execute.io.stackPointerData := 0.U
  execute.io.flushValid := false.B
  execute.io.fretStkFallbackTargetValid := false.B
  execute.io.fretStkFallbackTarget := 0.U
  execute.io.fretStkConditionValid := false.B
  execute.io.fretStkConditionTaken := false.B

  io.inReady := execute.io.inReady
  io.accepted := execute.io.accepted
  io.busy := execute.io.busy
  io.loadWaitHold := execute.io.loadWaitHold
  io.unsupported := execute.io.unsupported
  io.unsupportedOpcode := execute.io.unsupportedOpcode
  io.loadLookupValid := execute.io.loadLookupValid
  io.loadLookupAddr := execute.io.loadLookupAddr
  io.loadLookupSize := execute.io.loadLookupSize
  io.loadLookupReturnSignExtend := execute.io.loadLookupReturnSignExtend
  io.loadLookupPc := execute.io.loadLookupPc
  io.loadLookupBidValid := execute.io.loadLookupBid.valid
  io.loadLookupBidWrap := execute.io.loadLookupBid.wrap
  io.loadLookupBidValue := execute.io.loadLookupBid.value
  io.loadLookupGidValid := execute.io.loadLookupGid.valid
  io.loadLookupGidWrap := execute.io.loadLookupGid.wrap
  io.loadLookupGidValue := execute.io.loadLookupGid.value
  io.loadLookupRidValid := execute.io.loadLookupRid.valid
  io.loadLookupRidWrap := execute.io.loadLookupRid.wrap
  io.loadLookupRidValue := execute.io.loadLookupRid.value
  io.loadLookupLsId := execute.io.loadLookupLsId
  io.loadLookupDstValid := execute.io.loadLookupDst.valid
  io.loadLookupDstKind := execute.io.loadLookupDst.kind.asUInt
  io.loadLookupDstArchTag := execute.io.loadLookupDst.archTag
  io.loadLookupDstRelTag := execute.io.loadLookupDst.relTag
  io.loadLookupDstPhysTag := execute.io.loadLookupDst.physTag
  io.completeValid := execute.io.completeValid
  io.completeRobValue := execute.io.completeRobValue
  io.completeLsId := execute.io.completeLsId
  io.completeDstPhysValid := execute.io.completeDstPhysValid
  io.completeDstPhysTag := execute.io.completeDstPhysTag
  io.completeDstData := execute.io.completeDstData
  io.completeRowValid := execute.io.completeRow.valid
  io.completeRowBid := execute.io.completeRow.identity.bid
  io.completeRowGid := execute.io.completeRow.identity.gid
  io.completeRowRid := execute.io.completeRow.identity.rid
  io.completeRowRobValid := execute.io.completeRow.rob.valid
  io.completeRowRobWrap := execute.io.completeRow.rob.wrap
  io.completeRowRobValue := execute.io.completeRow.rob.value
  io.completeRowPc := execute.io.completeRow.pc
  io.completeRowDstValid := execute.io.completeRow.dst.valid
  io.completeRowDstReg := execute.io.completeRow.dst.reg
  io.completeRowDstData := execute.io.completeRow.dst.data
  io.completeRowWbValid := execute.io.completeRow.wb.valid
  io.completeRowWbReg := execute.io.completeRow.wb.reg
  io.completeRowWbData := execute.io.completeRow.wb.data
  io.completeRowMemValid := execute.io.completeRow.mem.valid
  io.completeRowMemIsStore := execute.io.completeRow.mem.isStore
  io.completeRowMemAddr := execute.io.completeRow.mem.addr
  io.completeRowMemRdata := execute.io.completeRow.mem.rdata
  io.completeRowMemSize := execute.io.completeRow.mem.size
}

object EmitCompressedWordLoadExecuteProbe extends App {
  val targetDir = args.sliding(2, 1).collectFirst {
    case Array("--target-dir", dir) => dir
  }.getOrElse("generated/chisel-verilog/backend-compressed-word-load-execute-probe")

  ChiselStage.emitSystemVerilogFile(
    new CompressedWordLoadExecuteProbe,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    args = Array("--target-dir", targetDir))
}
