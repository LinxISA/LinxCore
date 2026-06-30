package linxcore.execute

import chisel3._
import chisel3.util.{Cat, Fill, log2Ceil}

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
  val branchConditionValid = Output(Bool())
  val branchConditionTaken = Output(Bool())
  val loadLookupValid = Output(Bool())
  val loadLookupAddr = Output(UInt(p.immWidth.W))
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
      op === opcode(FrontendOpcodeDecodeTable.OP_C_LDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_EQ) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_NE) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_TGT) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_FRET_STK) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_FENTRY) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ANDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ANDIW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LUI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_LD_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LD_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_LDI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MUL) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_MULW) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_ORI) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SBI) ||
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
      op === opcode(FrontendOpcodeDecodeTable.OP_OR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_C_ADD) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SUB) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_SUBI)

  private def ldiScaledOffset(imm: UInt): UInt =
    (imm << 3)(p.immWidth - 1, 0)

  private def cLdiAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + imm

  private def cSdiAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + ((imm << 3)(p.immWidth - 1, 0))

  private def ldiAddr(srcData: Vec[UInt], imm: UInt): UInt =
    srcData(0) + ldiScaledOffset(imm)

  private def pcrLoadAddr(pc: UInt, imm: UInt): UInt =
    pc + imm

  private def sext32(value: UInt): UInt =
    Cat(Fill(p.immWidth - 32, value(31)), value(31, 0))

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
      srcData: Vec[UInt],
      imm: UInt,
      loadData: UInt,
      stackPointerData: UInt): UInt = {
    val out = Wire(UInt(p.immWidth.W))
    out := 0.U
    when(op === opcode(FrontendOpcodeDecodeTable.OP_ADD)) {
      out := srcData(0) + srcData(1)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ADDW)) {
      out := sext32(srcData(0) + srcData(1))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SUB)) {
      out := srcData(0) - srcData(1)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ADDI)) {
      out := srcData(0) + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SUBI)) {
      out := srcData(0) - imm
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
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SETRET)) {
      out := pc + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_LDI)) {
      out := loadData
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_LDI)) {
      out := loadData
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SDI)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_EQ)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_NE)) {
      out := 0.U
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_TGT)) {
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
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_OR)) {
      out := srcData(0) | srcData(1)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ORI)) {
      out := srcData(0) | imm
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
    }.elsewhen(uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SDI)) {
      row.mem.valid := valid
      row.mem.isStore := true.B
      row.mem.addr := cSdiAddr(srcData, uop.imm)
      row.mem.wdata := srcData(1)
      row.mem.rdata := 0.U
      row.mem.size := 8.U
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
  val eLoadI = eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LDI)
  val eLoadPcr =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_HL_LD_PCR) ||
      eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_LD_PCR)
  val eFretStkConditionNotTaken = io.fretStkConditionValid && !io.fretStkConditionTaken
  val eFretStkLoadReturn =
    eUop.opcode === opcode(FrontendOpcodeDecodeTable.OP_FRET_STK) &&
      eFretStkConditionNotTaken &&
      fretStkRestoresRa(eUop.insnRaw)
  io.loadLookupValid := !io.flushValid && eValid && (eLoadC || eLoadI || eLoadPcr || eFretStkLoadReturn)
  io.loadLookupAddr := Mux(
    eFretStkLoadReturn,
    fretStkRaLoadAddr(io.stackPointerData, eUop.imm),
    Mux(
      eLoadPcr,
      pcrLoadAddr(eUop.pc, eUop.imm),
      Mux(eLoadI, ldiAddr(eSrcData, eUop.imm), cLdiAddr(eSrcData, eUop.imm))))
  val eResult = resultFor(eUop.opcode, eUop.pc, eSrcData, eUop.imm, io.loadLookupData, io.stackPointerData)
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
  val branchSrc0 = Mux(w2Uop.src(0).valid, w2SrcData(0), 0.U)
  val branchSrc1 = Mux(w2Uop.src(1).valid, w2SrcData(1), 0.U)
  val branchIsSetcEq = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_EQ)
  val branchIsSetcNe = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_NE)
  val branchIsSetcLtu = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTU)
  val branchIsSetcLtui = w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_LTUI)
  val branchIsSetcTgt =
    w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_TGT) ||
      w2Uop.opcode === opcode(FrontendOpcodeDecodeTable.OP_SETC_TGT)
  io.branchConditionValid := io.completeValid && (branchIsSetcEq || branchIsSetcNe || branchIsSetcLtu || branchIsSetcLtui || branchIsSetcTgt)
  io.branchConditionTaken := Mux(
    branchIsSetcTgt,
    true.B,
    Mux(
      branchIsSetcEq,
      branchSrc0 === branchSrc1,
      Mux(
        branchIsSetcNe,
        branchSrc0 =/= branchSrc1,
        Mux(branchIsSetcLtui, branchSrc0 < w2Uop.imm, branchSrc0 < branchSrc1))))
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

  private def signed64(value: BigInt): BigInt = {
    val masked = value & Mask64
    if ((masked & SignBit64) != 0) masked - (BigInt(1) << 64) else masked
  }

  private def signed32(value: BigInt): BigInt = {
    val mask = (BigInt(1) << 32) - 1
    val masked = value & mask
    if ((masked & SignBit32) != 0) masked - (BigInt(1) << 32) else masked
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
    opcode match {
      case FrontendOpcodeDecodeTable.OP_ADD => Some((src0 + src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ADDW =>
        val low32 = (src0 + src1) & ((BigInt(1) << 32) - 1)
        Some(signed32(low32) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SUB => Some((src0 - src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ADDI => Some((src0 + imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_SUBI => Some((src0 - imm) & Mask64)
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
      case FrontendOpcodeDecodeTable.OP_C_SETRET => None
      case FrontendOpcodeDecodeTable.OP_C_LDI => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_C_SDI => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SETC_EQ => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SETC_NE => Some(0)
      case FrontendOpcodeDecodeTable.OP_C_SETC_TGT => Some(0)
      case FrontendOpcodeDecodeTable.OP_FRET_STK => Some(loadData & Mask64)
      case FrontendOpcodeDecodeTable.OP_SETC_LTU => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_LTUI => Some(0)
      case FrontendOpcodeDecodeTable.OP_SETC_TGT => Some(0)
      case FrontendOpcodeDecodeTable.OP_FENTRY => Some((src1 - imm) & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LUI => Some(imm & Mask64)
      case FrontendOpcodeDecodeTable.OP_HL_LD_PCR => Some(loadData & Mask64)
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
      case FrontendOpcodeDecodeTable.OP_OR => Some((src0 | src1) & Mask64)
      case FrontendOpcodeDecodeTable.OP_ORI => Some((src0 | imm) & Mask64)
      case _ => None
    }

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
}
