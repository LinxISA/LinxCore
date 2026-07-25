package linxcore.execute

import circt.stage.ChiselStage
import chisel3._
import linxcore.commit.CommitTraceParams
import linxcore.common.{DispatchTarget, InterfaceParams, OperandClass}
import linxcore.frontend.{FrontendOpcodeDecodeTable, FrontendRegAliasClassify}

class ReducedScalarAluHlSdiPrProbeIO extends Bundle {
  val inValid = Input(Bool())
  val flushValid = Input(Bool())
  val opcode = Input(UInt(12.W))
  val pc = Input(UInt(64.W))
  val insnLen = Input(UInt(4.W))
  val insnRaw = Input(UInt(64.W))
  val imm = Input(UInt(64.W))
  val srcDData = Input(UInt(64.W))
  val srcRData = Input(UInt(64.W))
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
  val srcDArchTag = Input(UInt(6.W))
  val srcDPhysTag = Input(UInt(6.W))
  val srcRArchTag = Input(UInt(6.W))
  val srcRPhysTag = Input(UInt(6.W))
  val dstValid = Input(Bool())
  val dstArchTag = Input(UInt(6.W))
  val dstPhysTag = Input(UInt(6.W))

  val inReady = Output(Bool())
  val accepted = Output(Bool())
  val busy = Output(Bool())
  val unsupported = Output(Bool())
  val unsupportedOpcode = Output(UInt(12.W))
  val loadWaitHold = Output(Bool())
  val loadLookupValid = Output(Bool())
  val loadLookupAddr = Output(UInt(64.W))
  val loadLookupSize = Output(UInt(4.W))
  val loadLiqEligible = Output(Bool())
  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(6.W))
  val completeLsId = Output(UInt(32.W))
  val completeDstPhysValid = Output(Bool())
  val completeDstPhysTag = Output(UInt(6.W))
  val completeDstData = Output(UInt(64.W))
  val completeSrc0PhysValid = Output(Bool())
  val completeSrc0PhysTag = Output(UInt(6.W))
  val completeSrc1PhysValid = Output(Bool())
  val completeSrc1PhysTag = Output(UInt(6.W))
  val completeRowValid = Output(Bool())
  val completeRowBid = Output(UInt(32.W))
  val completeRowGid = Output(UInt(32.W))
  val completeRowRid = Output(UInt(32.W))
  val completeRowRobValid = Output(Bool())
  val completeRowRobWrap = Output(Bool())
  val completeRowRobValue = Output(UInt(6.W))
  val completeRowBlockBidValid = Output(Bool())
  val completeRowBlockBid = Output(UInt(64.W))
  val completeRowPc = Output(UInt(64.W))
  val completeRowInsn = Output(UInt(64.W))
  val completeRowLen = Output(UInt(4.W))
  val completeRowSrc0Valid = Output(Bool())
  val completeRowSrc0Reg = Output(UInt(8.W))
  val completeRowSrc0Data = Output(UInt(64.W))
  val completeRowSrc1Valid = Output(Bool())
  val completeRowSrc1Reg = Output(UInt(8.W))
  val completeRowSrc1Data = Output(UInt(64.W))
  val completeRowDstValid = Output(Bool())
  val completeRowDstReg = Output(UInt(8.W))
  val completeRowDstData = Output(UInt(64.W))
  val completeRowWbValid = Output(Bool())
  val completeRowWbReg = Output(UInt(8.W))
  val completeRowWbData = Output(UInt(64.W))
  val completeRowMemValid = Output(Bool())
  val completeRowMemIsStore = Output(Bool())
  val completeRowMemAddr = Output(UInt(64.W))
  val completeRowMemWdata = Output(UInt(64.W))
  val completeRowMemRdata = Output(UInt(64.W))
  val completeRowMemSize = Output(UInt(4.W))
  val branchConditionValid = Output(Bool())
  val redirectValid = Output(Bool())
  val releaseValid = Output(Bool())
  val releaseBidValid = Output(Bool())
  val releaseBidWrap = Output(Bool())
  val releaseBidValue = Output(UInt(6.W))
  val releaseGidValid = Output(Bool())
  val releaseGidWrap = Output(Bool())
  val releaseGidValue = Output(UInt(6.W))
  val releaseRidValid = Output(Bool())
  val releaseRidWrap = Output(Bool())
  val releaseRidValue = Output(UInt(6.W))
  val releaseStid = Output(UInt(8.W))
  val liqReleaseValid = Output(Bool())
}

class ReducedScalarAluHlSdiPrProbe extends Module {
  private val p = InterfaceParams()
  private val trace = CommitTraceParams(
    commitWidth = p.commitWidth,
    robValueWidth = p.robIndexWidth,
    blockBidWidth = p.blockBidWidth,
    pcWidth = p.pcWidth,
    insnWidth = p.insnWidth,
    lenWidth = p.lenWidth)

  val io = IO(new ReducedScalarAluHlSdiPrProbeIO)
  val execute = Module(new ReducedScalarAluExecute(p, trace))
  execute.io.completeReady := true.B
  val classifiedDst = FrontendRegAliasClassify.destination(p, io.dstValid, io.dstArchTag)

  execute.io.inValid := io.inValid
  execute.io.in := 0.U.asTypeOf(execute.io.in)
  execute.io.in.valid := io.inValid
  execute.io.in.peId := 0.U
  execute.io.in.threadId := 0.U
  execute.io.in.pc := io.pc
  execute.io.in.opcode := io.opcode
  execute.io.in.dispatchTarget := DispatchTarget.Lsu
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
  execute.io.in.isLoad := false.B
  execute.io.in.isStore := true.B
  execute.io.in.insnLen := io.insnLen
  execute.io.in.insnRaw := io.insnRaw
  execute.io.in.blockBidValid := true.B
  execute.io.in.blockBid := io.blockBid
  execute.io.in.src(0).valid := true.B
  execute.io.in.src(0).operandClass := OperandClass.P
  execute.io.in.src(0).archTag := io.srcDArchTag
  execute.io.in.src(0).physTag := io.srcDPhysTag
  execute.io.in.src(0).ready := true.B
  execute.io.in.src(1).valid := true.B
  execute.io.in.src(1).operandClass := OperandClass.P
  execute.io.in.src(1).archTag := io.srcRArchTag
  execute.io.in.src(1).physTag := io.srcRPhysTag
  execute.io.in.src(1).ready := true.B
  execute.io.in.dst(0).valid := io.dstValid
  execute.io.in.dst(0).kind := classifiedDst.kind
  execute.io.in.dst(0).archTag := classifiedDst.archTag
  execute.io.in.dst(0).relTag := classifiedDst.relTag
  execute.io.in.dst(0).physTag := io.dstPhysTag

  execute.io.srcData(0) := io.srcDData
  execute.io.srcData(1) := io.srcRData
  execute.io.srcData(2) := 0.U
  execute.io.loadLookupData := 0.U
  execute.io.loadPairFirstLookupData := 0.U
  execute.io.loadLookupWaitBlocked := false.B
  execute.io.loadLiqEnable := false.B
  execute.io.loadLiqAccepted := false.B
  execute.io.stackPointerData := 0.U
  execute.io.flushValid := io.flushValid
  execute.io.fretStkFallbackTargetValid := false.B
  execute.io.fretStkFallbackTarget := 0.U
  execute.io.fretStkConditionValid := false.B
  execute.io.fretStkConditionTaken := false.B

  io.inReady := execute.io.inReady
  io.accepted := execute.io.accepted
  io.busy := execute.io.busy
  io.unsupported := execute.io.unsupported
  io.unsupportedOpcode := execute.io.unsupportedOpcode
  io.loadWaitHold := execute.io.loadWaitHold
  io.loadLookupValid := execute.io.loadLookupValid
  io.loadLookupAddr := execute.io.loadLookupAddr
  io.loadLookupSize := execute.io.loadLookupSize
  io.loadLiqEligible := execute.io.loadLiqEligible
  io.completeValid := execute.io.completeValid
  io.completeRobValue := execute.io.completeRobValue
  io.completeLsId := execute.io.completeLsId
  io.completeDstPhysValid := execute.io.completeDstPhysValid
  io.completeDstPhysTag := execute.io.completeDstPhysTag
  io.completeDstData := execute.io.completeDstData
  io.completeSrc0PhysValid := execute.io.completeSrcPhysValid(0)
  io.completeSrc0PhysTag := execute.io.completeSrcPhysTag(0)
  io.completeSrc1PhysValid := execute.io.completeSrcPhysValid(1)
  io.completeSrc1PhysTag := execute.io.completeSrcPhysTag(1)
  io.completeRowValid := execute.io.completeRow.valid
  io.completeRowBid := execute.io.completeRow.identity.bid
  io.completeRowGid := execute.io.completeRow.identity.gid
  io.completeRowRid := execute.io.completeRow.identity.rid
  io.completeRowRobValid := execute.io.completeRow.rob.valid
  io.completeRowRobWrap := execute.io.completeRow.rob.wrap
  io.completeRowRobValue := execute.io.completeRow.rob.value
  io.completeRowBlockBidValid := execute.io.completeRow.blockBidValid
  io.completeRowBlockBid := execute.io.completeRow.blockBid
  io.completeRowPc := execute.io.completeRow.pc
  io.completeRowInsn := execute.io.completeRow.insn
  io.completeRowLen := execute.io.completeRow.len
  io.completeRowSrc0Valid := execute.io.completeRow.src0.valid
  io.completeRowSrc0Reg := execute.io.completeRow.src0.reg
  io.completeRowSrc0Data := execute.io.completeRow.src0.data
  io.completeRowSrc1Valid := execute.io.completeRow.src1.valid
  io.completeRowSrc1Reg := execute.io.completeRow.src1.reg
  io.completeRowSrc1Data := execute.io.completeRow.src1.data
  io.completeRowDstValid := execute.io.completeRow.dst.valid
  io.completeRowDstReg := execute.io.completeRow.dst.reg
  io.completeRowDstData := execute.io.completeRow.dst.data
  io.completeRowWbValid := execute.io.completeRow.wb.valid
  io.completeRowWbReg := execute.io.completeRow.wb.reg
  io.completeRowWbData := execute.io.completeRow.wb.data
  io.completeRowMemValid := execute.io.completeRow.mem.valid
  io.completeRowMemIsStore := execute.io.completeRow.mem.isStore
  io.completeRowMemAddr := execute.io.completeRow.mem.addr
  io.completeRowMemWdata := execute.io.completeRow.mem.wdata
  io.completeRowMemRdata := execute.io.completeRow.mem.rdata
  io.completeRowMemSize := execute.io.completeRow.mem.size
  io.branchConditionValid := execute.io.branchConditionValid
  io.redirectValid := execute.io.redirectValid
  io.releaseValid := execute.io.releaseValid
  io.releaseBidValid := execute.io.releaseBid.valid
  io.releaseBidWrap := execute.io.releaseBid.wrap
  io.releaseBidValue := execute.io.releaseBid.value
  io.releaseGidValid := execute.io.releaseGid.valid
  io.releaseGidWrap := execute.io.releaseGid.wrap
  io.releaseGidValue := execute.io.releaseGid.value
  io.releaseRidValid := execute.io.releaseRid.valid
  io.releaseRidWrap := execute.io.releaseRid.wrap
  io.releaseRidValue := execute.io.releaseRid.value
  io.releaseStid := execute.io.releaseStid
  io.liqReleaseValid := execute.io.liqReleaseValid
}

object EmitReducedScalarAluHlSdiPrProbe extends App {
  val targetDir = args.sliding(2, 1).collectFirst {
    case Array("--target-dir", dir) => dir
  }.getOrElse("generated/chisel-verilog/backend-reduced-scalar-alu-hl-sdi-pr-probe")

  ChiselStage.emitSystemVerilogFile(
    new ReducedScalarAluHlSdiPrProbe,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    args = Array("--target-dir", targetDir))
}
