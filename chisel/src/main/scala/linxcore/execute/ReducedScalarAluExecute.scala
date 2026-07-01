package linxcore.execute

import chisel3._
import chisel3.util.{Cat, Fill, is, log2Ceil, switch}

import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.common.{DestinationKind, InterfaceParams, OperandClass, RenamedUop}
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
  val stackPointerData = Input(UInt(p.immWidth.W))
  val flushValid = Input(Bool())
  val fretStkFallbackTargetValid = Input(Bool())
  val fretStkFallbackTarget = Input(UInt(p.pcWidth.W))
  val fretStkConditionValid = Input(Bool())
  val fretStkConditionTaken = Input(Bool())

  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(ptrWidth.W))
  val completeRow = Output(new CommitTraceRow(traceParams))
  val completeDstPhysValid = Output(Bool())
  val completeDstPhysTag = Output(UInt(p.physRegWidth.W))
  val completeDstData = Output(UInt(p.immWidth.W))
  val completeSrcPhysValid = Output(Vec(3, Bool()))
  val completeSrcPhysTag = Output(Vec(3, UInt(p.physRegWidth.W)))
  val branchConditionValid = Output(Bool())
  val branchConditionTaken = Output(Bool())
  val loadLookupValid = Output(Bool())
  val loadLookupAddr = Output(UInt(p.immWidth.W))
  val fretStkSpRestoreValid = Output(Bool())
  val fretStkSpRestoreData = Output(UInt(p.immWidth.W))
  val redirectValid = Output(Bool())
  val redirectPc = Output(UInt(p.pcWidth.W))

  val releaseValid = Output(Bool())
  val releaseBid = Output(new ROBID(p.robEntries))
  val releaseRid = Output(new ROBID(p.robEntries))
  val releaseStid = Output(UInt(p.threadIdWidth.W))

  val accepted = Output(Bool())
  val busy = Output(Bool())
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
      op === opcode(FrontendOpcodeDecodeTable.OP_ADDTPC) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETRET) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_AND) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SUB) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_B) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_H) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_W) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ZEXT_B) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ZEXT_H) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ZEXT_W) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_LDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SWI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_EQ) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_NE) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_TGT) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CMP_EQI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_CSEL) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_FRET_STK) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_FENTRY) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_AND) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ANDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ANDIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LD_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SB_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SD_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SH_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SW_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LBUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LD_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MUL) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MULW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ORI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SBI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LT) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTU) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SETC_TGT) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SD) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SWI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SLL) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SLLI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRL) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SRA) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SSRSET) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_OR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ADD) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SUB) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SUBI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_XORI)

  private def ldiScaledOffset(imm: UInt): UInt =
    (imm << 3)(p.immWidth - 1, 0)

  private def cLdiAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + imm

  private def cSdiAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + ((imm << 3)(p.immWidth - 1, 0))

  private def cSwiAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + ((imm << 2)(p.immWidth - 1, 0))

  private def ldiAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + ldiScaledOffset(imm)

  private def loadByteImmAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + imm

  private def pcrLoadAddr(pc: UInt, imm: UInt): UInt =
    pc + imm

  private def pcrStoreSize(op: UInt): UInt =
    Mux(
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SB_PCR),
      1.U,
      Mux(op === opcode(FrontendOpcodeDecodeTable.OP_HL_SH_PCR), 2.U, Mux(op === opcode(FrontendOpcodeDecodeTable.OP_HL_SW_PCR), 4.U, 8.U)))

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

  private def shiftedSrcR(value: UInt, shamt: UInt): UInt =
    (value << shamt)(p.immWidth - 1, 0)

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

  private def fentrySaveCount(insn: UInt): UInt = {
    val begin = insn(19, 15)
    val end = insn(24, 20)
    Mux(end >= begin, end - begin + 1.U, end + 23.U - begin)
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
    srcData(0) + ((srcData(1) << 3)(p.immWidth - 1, 0))

  private def storeByteImmAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(1) + imm

  private def storeWordImmAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(1) + ((imm << 2)(p.immWidth - 1, 0))

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
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ADDI)) {
      out := srcData(0) + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SUBI)) {
      out := srcData(0) - imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_AND)) {
      out := srcData(0) & logicR
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ANDI)) {
      out := srcData(0) & imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ANDIW)) {
      out := sext32(srcData(0) & imm)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ADDTPC)) {
      out := (pc & "hffff_ffff_ffff_f000".U(p.immWidth.W)) + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVI)) {
      out := imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVR)) {
      out := srcData(0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_ADD)) {
      out := srcData(0) + srcData(1)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_AND)) {
      out := srcData(0) & srcData(1)
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
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_EQI)) {
      out := Mux(srcData(0) === imm, 1.U, 0.U)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CSEL)) {
      out := Mux(srcData(2) =/= 0.U, srcData(0), srcData(1))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_LDI)) {
      out := loadData
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LBUI)) {
      out := loadData(7, 0).pad(p.immWidth)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LDI)) {
      out := loadData
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
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LT)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTU)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTUI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SETC_TGT)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_FRET_STK)) {
      out := loadData
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_FENTRY)) {
      out := stackPointerData - imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_HL_LUI)) {
      out := imm
    }.elsewhen(
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LD_PCR) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_LD_PCR)) {
      out := loadData
    }.elsewhen(
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SB_PCR) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_HL_SD_PCR) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_HL_SH_PCR) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_HL_SW_PCR)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SBI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SD)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SDI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SWI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_MUL)) {
      out := (srcData(0) * srcData(1))(p.immWidth - 1, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_MULW)) {
      out := sext32(srcData(0) * srcData(1))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SLL)) {
      out := (srcData(0) << srcData(1)(5, 0))(p.immWidth - 1, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SLLI)) {
      out := (srcData(0) << imm(5, 0))(p.immWidth - 1, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRL)) {
      out := srcData(0) >> srcData(1)(5, 0)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRA)) {
      out := (srcData(0).asSInt >> srcData(1)(5, 0)).asUInt
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SSRSET)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_OR)) {
      out := srcData(0) | logicR
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ORI)) {
      out := srcData(0) | imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_XORI)) {
      out := srcData(0) ^ imm
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
    row.src1.valid := valid && uop.src(1).valid && (uop.src(1).operandClass === OperandClass.P)
    row.src1.reg := fitReg(uop.src(1).archTag)
    row.src1.data := srcData(1)
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
      row.mem.addr := result + uop.imm - ((fentrySaveCount(uop.insnRaw).pad(p.immWidth) << 3)(p.immWidth - 1, 0))
      row.mem.wdata := Mux(fentrySaveCount(uop.insnRaw) === 1.U, srcData(0), 0.U)
      row.mem.rdata := 0.U
      row.mem.size := 8.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_LDI)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := cLdiAddr(srcData, uop.imm)
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := 8.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LBUI)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := loadByteImmAddr(srcData, uop.imm)
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := 1.U
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LDI)) {
      row.mem.valid := valid
      row.mem.isStore := false.B
      row.mem.addr := ldiAddr(srcData, uop.imm)
      row.mem.wdata := 0.U
      row.mem.rdata := result
      row.mem.size := 8.U
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
      row.mem.wdata := srcData(2)
      row.mem.rdata := 0.U
      row.mem.size := 8.U
    }
    row
  }

  val eValid = RegInit(false.B)
  val eUop = Reg(new RenamedUop(p))
  val eSrcData = Reg(Vec(3, UInt(p.immWidth.W)))

  val w1Valid = RegInit(false.B)
  val w1Uop = Reg(new RenamedUop(p))
  val w1SrcData = Reg(Vec(3, UInt(p.immWidth.W)))
  val w1Result = Reg(UInt(p.immWidth.W))
  val w1Supported = Reg(Bool())
  val w1StackPointerData = Reg(UInt(p.immWidth.W))
  val w1FretStkLoadReturn = Reg(Bool())

  val w2Valid = RegInit(false.B)
  val w2Uop = Reg(new RenamedUop(p))
  val w2SrcData = Reg(Vec(3, UInt(p.immWidth.W)))
  val w2Result = Reg(UInt(p.immWidth.W))
  val w2Supported = Reg(Bool())
  val w2StackPointerData = Reg(UInt(p.immWidth.W))
  val w2FretStkLoadReturn = Reg(Bool())
  val setcTargetValid = RegInit(false.B)
  val setcTarget = RegInit(0.U(p.pcWidth.W))

  val accept = io.inValid && io.inReady
  val eLoadC = eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_LDI)
  val eLoadByteI = eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LBUI)
  val eLoadI = eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LDI)
  val eLoadPcr =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LD_PCR) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LD_PCR)
  val eFretStkConditionAllowsLoad = !io.fretStkConditionValid || !io.fretStkConditionTaken
  val eFretStkTargetWouldWin =
    (setcTargetValid || io.fretStkFallbackTargetValid) &&
      (!io.fretStkConditionValid || io.fretStkConditionTaken)
  val eFretStkLoadReturn =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_FRET_STK) &&
      eFretStkConditionAllowsLoad &&
      !eFretStkTargetWouldWin &&
      fretStkRestoresRa(eUop.insnRaw)
  io.loadLookupValid := !io.flushValid && eValid && (eLoadC || eLoadByteI || eLoadI || eLoadPcr || eFretStkLoadReturn)
  io.loadLookupAddr := Mux(
    eFretStkLoadReturn,
    fretStkRaLoadAddr(io.stackPointerData, eUop.imm),
    Mux(
      eLoadPcr,
      pcrLoadAddr(eUop.pc, eUop.imm),
      Mux(
        eLoadByteI,
        loadByteImmAddr(eSrcData, eUop.imm),
        Mux(eLoadI, ldiAddr(eSrcData, eUop.imm), cLdiAddr(eSrcData, eUop.imm)))))
  val eResult = resultFor(eUop.opcode, eUop.pc, eUop.insnRaw, eSrcData, eUop.imm, io.loadLookupData, io.stackPointerData)
  val eSupported = eValid && isSupported(eUop.opcode)

  when(io.flushValid) {
    eValid := false.B
    w1Valid := false.B
    w2Valid := false.B
    setcTargetValid := false.B
    setcTarget := 0.U
  }.otherwise {
    w2Valid := w1Valid
    w2Uop := w1Uop
    w2SrcData := w1SrcData
    w2Result := w1Result
    w2Supported := w1Supported
    w2StackPointerData := w1StackPointerData
    w2FretStkLoadReturn := w1FretStkLoadReturn

    w1Valid := eValid
    w1Uop := eUop
    w1SrcData := eSrcData
    w1Result := eResult
    w1Supported := eSupported
    w1StackPointerData := io.stackPointerData
    w1FretStkLoadReturn := eFretStkLoadReturn

    eValid := accept
    when(accept) {
      eUop := io.in
      eSrcData := io.srcData
    }
  }

  io.inReady := !io.flushValid && !eValid
  io.accepted := accept
  io.busy := !io.flushValid && (eValid || w1Valid || w2Valid)
  io.completeValid := !io.flushValid && w2Valid && w2Supported
  io.completeRobValue := w2Uop.rid.value
  val w2IsFretStk = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_FRET_STK)
  val w2FretStkFallbackTargetValid = io.fretStkFallbackTargetValid
  val w2FretStkConditionAllowsTarget = !io.fretStkConditionValid || io.fretStkConditionTaken
  val w2FretStkTargetTaken =
    (setcTargetValid || w2FretStkFallbackTargetValid) && w2FretStkConditionAllowsTarget
  val w2FretStkTarget = Mux(setcTargetValid, setcTarget, io.fretStkFallbackTarget)
  io.completeRow := completionRow(
    w2Uop,
    w2SrcData,
    w2Result,
    io.completeValid,
    setcTargetValid && w2FretStkConditionAllowsTarget,
    setcTarget,
    w2FretStkFallbackTargetValid && w2FretStkConditionAllowsTarget,
    io.fretStkFallbackTarget,
    w2FretStkLoadReturn,
    w2StackPointerData)
  io.completeDstPhysValid :=
    io.completeValid &&
      Mux(w2FretStkLoadReturn, true.B, w2Uop.dst(0).valid && (w2Uop.dst(0).kind === DestinationKind.Gpr))
  io.completeDstPhysTag := Mux(w2FretStkLoadReturn, 10.U(p.physRegWidth.W), w2Uop.dst(0).physTag)
  io.completeDstData := w2Result
  for (idx <- 0 until 3) {
    io.completeSrcPhysValid(idx) :=
      io.completeValid && w2Uop.src(idx).valid && (w2Uop.src(idx).operandClass === OperandClass.P)
    io.completeSrcPhysTag(idx) := w2Uop.src(idx).physTag
  }
  io.fretStkSpRestoreValid := io.completeValid && w2FretStkLoadReturn
  io.fretStkSpRestoreData := w2StackPointerData + w2Uop.imm
  val branchSrc0 = Mux(w2Uop.src(0).valid, w2SrcData(0), 0.U)
  val branchSrc1 = Mux(w2Uop.src(1).valid, w2SrcData(1), 0.U)
  val branchSrc1AddSub = addSubSrcR(w2Uop.insnRaw, branchSrc1)
  val branchIsSetcEq = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_EQ)
  val branchIsSetcNe = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_NE)
  val branchIsSetcLt = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_LT)
  val branchIsSetcLtu = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTU)
  val branchIsSetcLtui = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTUI)
  val branchIsSetcTgt =
    w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_TGT) ||
      w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_TGT)
  io.branchConditionValid :=
    io.completeValid && (branchIsSetcEq || branchIsSetcNe || branchIsSetcLt || branchIsSetcLtu || branchIsSetcLtui || branchIsSetcTgt)
  io.branchConditionTaken := Mux(
    branchIsSetcTgt,
    true.B,
      Mux(
        branchIsSetcEq,
        branchSrc0 === branchSrc1,
        Mux(
          branchIsSetcNe,
          branchSrc0 =/= branchSrc1,
          Mux(
            branchIsSetcLt,
            branchSrc0.asSInt < branchSrc1AddSub.asSInt,
            Mux(branchIsSetcLtui, branchSrc0 < w2Uop.imm, branchSrc0 < branchSrc1AddSub)))))
  io.redirectValid := io.completeValid && w2IsFretStk && (w2FretStkLoadReturn || w2FretStkTargetTaken)
  io.redirectPc := Mux(w2FretStkLoadReturn, w2Result(p.pcWidth - 1, 0), w2FretStkTarget)
  when(io.completeValid && branchIsSetcTgt) {
    setcTargetValid := true.B
    setcTarget := branchSrc0(p.pcWidth - 1, 0)
  }.elsewhen(io.completeValid && w2IsFretStk) {
    setcTargetValid := false.B
    setcTarget := 0.U
  }
  io.releaseValid := !io.flushValid && w2Valid
  io.releaseBid := w2Uop.bid
  io.releaseRid := w2Uop.rid
  io.releaseStid := w2Uop.threadId
  io.unsupported := !io.flushValid && w2Valid && !w2Supported
  io.unsupportedOpcode := w2Uop.opcode
}

object ReducedScalarAluExecute {
  private val Mask64 = (BigInt(1) << 64) - 1
  private val SignBit64 = BigInt(1) << 63
  private val SignBit32 = BigInt(1) << 31
  private val NoModifierInsn = BigInt(3) << 25

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
      case FrontendOpcodeDecodeTable.OP_ADDI => Some((src0 + imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SUBI => Some((src0 - imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_AND => Some((src0 & logicR) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ANDI => Some((src0 & imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ANDIW =>
        val low32 = (src0 & imm) & ((BigInt(1) << 32) - 1)
        Some(signed32(low32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ADDTPC => None
      case FrontendOpcodeDecodeTable.OP_C_MOVI => Some(imm & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_MOVR => Some(src0 & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_ADD => Some((src0 + src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_AND => Some((src0 & src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SUB => Some((src0 - src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SEXT_B => Some(signedN(src0, 8) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SEXT_H => Some(signedN(src0, 16) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SEXT_W => Some(signed32(src0) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_ZEXT_B => Some(src0 & 0xff)
      case FrontendOpcodeDecodeTable.OP_C_ZEXT_H => Some(src0 & 0xffff)
      case FrontendOpcodeDecodeTable.OP_C_ZEXT_W => Some(src0 & ((BigInt(1) << 32) - 1))
      case FrontendOpcodeDecodeTable.OP_C_SETRET => None
      case FrontendOpcodeDecodeTable.OP_CMP_EQI => Some(if ((src0 & Mask64) == (imm & Mask64)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_C_LDI => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_LBUI => Some(loadData & 0xFF)
      case FrontendOpcodeDecodeTable.OP_C_SDI => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SWI => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SETC_EQ => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SETC_NE => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SETC_TGT => Some(0)
      case FrontendOpcodeDecodeTable.OP_FRET_STK => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_SETC_LT => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_LTU => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_LTUI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_TGT => Some(0)
      case FrontendOpcodeDecodeTable.OP_FENTRY => Some((src1 - imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LUI => Some(imm & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LD_PCR => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_SB_PCR => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SD_PCR => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SH_PCR => Some(0)
      case FrontendOpcodeDecodeTable.OP_HL_SW_PCR => Some(0)
      case FrontendOpcodeDecodeTable.OP_LD_PCR => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_LDI => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_MUL => Some((src0 * src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_MULW =>
        val low32 = (src0 * src1) & ((BigInt(1) << 32) - 1)
        Some(signed32(low32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SBI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SD => Some(0)
      case FrontendOpcodeDecodeTable.OP_SDI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SWI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SLL => Some((src0 << ((src1 & 0x3f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SLLI => Some((src0 << ((imm & 0x3f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SRL => Some((src0 & Mask64) >> ((src1 & 0x3f).toInt))
      case FrontendOpcodeDecodeTable.OP_SRA => Some((signed64(src0) >> ((src1 & 0x3f).toInt)) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SSRSET => Some(0)
      case FrontendOpcodeDecodeTable.OP_OR => Some((src0 | logicR) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ORI => Some((src0 | imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_XORI => Some((src0 ^ imm) & Mask64)
      case _ => None
    }
  }

  private def srcRType(insnRaw: BigInt): Int =
    ((insnRaw >> 25) & 0x3).toInt

  private def srcRShamt(insnRaw: BigInt): Int =
    ((insnRaw >> 27) & 0x1f).toInt

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

  def referenceCsel(srcL: BigInt, srcR: BigInt, srcP: BigInt): BigInt =
    if ((srcP & Mask64) != 0) srcL & Mask64 else srcR & Mask64

  def referenceResult(opcode: Int, pc: BigInt, src0: BigInt, src1: BigInt, imm: BigInt): Option[BigInt] =
    opcode match {
      case FrontendOpcodeDecodeTable.OP_ADDTPC => Some(((pc & ~BigInt(0xfff)) + imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SETRET => Some((pc + imm) & Mask64)
      case _ => referenceResult(opcode, src0, src1, imm)
    }

  def referenceBranchCondition(
      opcode: Int,
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
      case FrontendOpcodeDecodeTable.OP_C_SETC_TGT => Some(true)
      case FrontendOpcodeDecodeTable.OP_SETC_LT =>
        val lhs = if (src0Valid) signed64(src0) else BigInt(0)
        val rhs = if (src1Valid) signed64(src1) else BigInt(0)
        Some(lhs < rhs)
      case FrontendOpcodeDecodeTable.OP_SETC_LTU =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        val rhs = if (src1Valid) src1 & Mask64 else BigInt(0)
        Some(lhs < rhs)
      case FrontendOpcodeDecodeTable.OP_SETC_LTUI =>
        val lhs = if (src0Valid) src0 & Mask64 else BigInt(0)
        Some(lhs < (src1 & Mask64))
      case FrontendOpcodeDecodeTable.OP_SETC_TGT => Some(true)
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
    val targetWouldWin = targetPending && (!conditionValid || conditionTaken)
    restoresRa && (!conditionValid || !conditionTaken) && !targetWouldWin
  }
}
