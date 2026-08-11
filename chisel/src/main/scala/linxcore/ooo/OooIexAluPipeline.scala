package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, Decoupled, Fill, PopCount, Valid}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.params.CoreParams
import linxcore.top.interface.{RecoveryPhase, RecoveryPlan}

class OooIexAluWriteback(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val destination = new OooIexDestinationState(p)
  val data = UInt(p.pcWidth.W)
}

/** Retained ALU result before any RF, wakeup, ROB, or trace side effect. */
class OooIexAluTerminalTransaction(val p: OooParams = OooParams())
    extends Bundle {
  val execute = new OooIexExecuteTransaction(p)
  val writebacks = Vec(p.maxDestinationOperands,
    new OooIexAluWriteback(p))
}

class OooIexAluReject(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val opcode = UInt(p.opcodeWidth.W)
  val classExact = Bool()
  val recipeExact = Bool()
  val operandShapeExact = Bool()
  val supportedOpcode = Bool()
  val incomingKilled = Bool()
}

class OooIexAluPipelineIO(
    val p: OooParams = OooParams(),
    val coreParams: CoreParams)
    extends Bundle {
  val e1 = Flipped(Decoupled(new OooIexExecuteTransaction(p)))
  val w1Bypass = Valid(new OooIexAluTerminalTransaction(p))
  val w2 = Decoupled(new OooIexAluTerminalTransaction(p))

  val recoveryApply = Flipped(Valid(new RecoveryPlan(coreParams)))
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))

  val rejected = Valid(new OooIexAluReject(p))
  val killedW1 = Valid(new OooIexAluTerminalTransaction(p))
  val killedW2 = Valid(new OooIexAluTerminalTransaction(p))
  val w1Occupied = Output(Bool())
  val w2Occupied = Output(Bool())
  val empty = Output(Bool())
}

object OooIexAluPipeline {
  val ImmediateOpcodes: Seq[Int] = Seq(
    FrontendOpcodeDecodeTable.OP_ADDI,
    FrontendOpcodeDecodeTable.OP_ADDIW,
    FrontendOpcodeDecodeTable.OP_SUBI,
    FrontendOpcodeDecodeTable.OP_SUBIW,
    FrontendOpcodeDecodeTable.OP_ANDI,
    FrontendOpcodeDecodeTable.OP_ANDIW,
    FrontendOpcodeDecodeTable.OP_ORI,
    FrontendOpcodeDecodeTable.OP_ORIW,
    FrontendOpcodeDecodeTable.OP_XORI,
    FrontendOpcodeDecodeTable.OP_XORIW,
    FrontendOpcodeDecodeTable.OP_C_ADDI)

  val CompactRegisterOpcodes: Seq[Int] = Seq(
    FrontendOpcodeDecodeTable.OP_C_ADD,
    FrontendOpcodeDecodeTable.OP_C_AND,
    FrontendOpcodeDecodeTable.OP_C_OR,
    FrontendOpcodeDecodeTable.OP_C_SUB)

  val MoveImmediateOpcodes: Seq[Int] = Seq(
    FrontendOpcodeDecodeTable.OP_C_MOVI)

  val LoadImmediateOpcodes: Seq[Int] = Seq(
    FrontendOpcodeDecodeTable.OP_LUI,
    FrontendOpcodeDecodeTable.OP_HL_LUI,
    FrontendOpcodeDecodeTable.OP_HL_LIS,
    FrontendOpcodeDecodeTable.OP_HL_LIU)

  val MoveRegisterOpcodes: Seq[Int] = Seq(
    FrontendOpcodeDecodeTable.OP_C_MOVR)

  val CompactSignExtendOpcodes: Seq[Int] = Seq(
    FrontendOpcodeDecodeTable.OP_C_SEXT_B,
    FrontendOpcodeDecodeTable.OP_C_SEXT_H,
    FrontendOpcodeDecodeTable.OP_C_SEXT_W)

  val RegisterShiftOpcodes: Seq[Int] = Seq(
    FrontendOpcodeDecodeTable.OP_SLL,
    FrontendOpcodeDecodeTable.OP_SLLW,
    FrontendOpcodeDecodeTable.OP_SRA,
    FrontendOpcodeDecodeTable.OP_SRAW,
    FrontendOpcodeDecodeTable.OP_SRL,
    FrontendOpcodeDecodeTable.OP_SRLW)

  val ImmediateShiftOpcodes: Seq[Int] = Seq(
    FrontendOpcodeDecodeTable.OP_SLLI,
    FrontendOpcodeDecodeTable.OP_SLLIW,
    FrontendOpcodeDecodeTable.OP_SRAI,
    FrontendOpcodeDecodeTable.OP_SRAIW,
    FrontendOpcodeDecodeTable.OP_SRLI,
    FrontendOpcodeDecodeTable.OP_SRLIW)

  val SupportedOpcodes: Seq[Int] = ImmediateOpcodes ++
    CompactRegisterOpcodes ++ MoveImmediateOpcodes ++ LoadImmediateOpcodes ++
    MoveRegisterOpcodes ++ CompactSignExtendOpcodes ++ RegisterShiftOpcodes ++
    ImmediateShiftOpcodes
}

/** One-cycle integer ALU with retained W1 bypass and retained W2 terminal row.
  *
  * E1 computes only normalized scalar uops explicitly named by this module.
  * W1 and W2 retain the complete execute owner under backpressure. No RF
  * write, wakeup, ROB completion, redirect, or trace side effect occurs here;
  * a later atomic terminal sink must accept W2 before publishing those effects.
  */
class OooIexAluPipeline(
    val p: OooParams = OooParams(),
    val coreParams: CoreParams)
    extends Module {
  def this(p: OooParams) =
    this(p, OooRecoveryMembership.coreParams(p))
  import OooIexAluPipeline._
  OooRecoveryMembership.requireCompatible(p, coreParams)

  val io = IO(new OooIexAluPipelineIO(p, coreParams))

  private def opcode(value: Int): UInt = value.U(p.opcodeWidth.W)

  private def isOneOf(op: UInt, values: Seq[Int]): Bool =
    values.map(value => op === opcode(value)).reduce(_ || _)

  private def killedByRecovery(execute: OooIexExecuteTransaction): Bool =
    io.recoveryApply.valid &&
      io.recoveryApply.bits.phase === RecoveryPhase.Apply &&
      OooRecoveryMembership.memberKilled(p, coreParams,
        io.recoveryApply.bits,
        execute.i2.row.member)

  private def canceledByLoad(execute: OooIexExecuteTransaction): Bool =
    io.loadCancel.map { cancel =>
      val sourceMatch = execute.i2.row.sources.map { source =>
        source.valid && source.specReady && source.load.valid &&
          cancel.bits.load.valid &&
          source.load.asUInt === cancel.bits.load.asUInt
      }.reduce(_ || _)
      cancel.valid && cancel.bits.stid === execute.i2.row.stid &&
        cancel.bits.epoch === execute.i2.row.epoch && sourceMatch
    }.reduce(_ || _)

  private def makeResult(
      execute: OooIexExecuteTransaction): OooIexAluTerminalTransaction = {
    val terminal = Wire(new OooIexAluTerminalTransaction(p))
    terminal := 0.U.asTypeOf(terminal)
    terminal.execute := execute

    val i2 = execute.i2
    val op = i2.row.opcode
    val src0 = i2.sourceData(0)
    val src1 = i2.sourceData(1)
    val imm = i2.row.immediate
    val result = WireDefault(0.U(p.pcWidth.W))

    when(op === opcode(FrontendOpcodeDecodeTable.OP_ADDI) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_C_ADDI)) {
      result := src0 + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ADDIW)) {
      result := Cat(Fill(p.pcWidth - 32, (src0 + imm)(31)),
        (src0 + imm)(31, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SUBI)) {
      result := src0 - imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SUBIW)) {
      result := Cat(Fill(p.pcWidth - 32, (src0 - imm)(31)),
        (src0 - imm)(31, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ANDI)) {
      result := src0 & imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ANDIW)) {
      result := Cat(Fill(p.pcWidth - 32, (src0 & imm)(31)),
        (src0 & imm)(31, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ORI)) {
      result := src0 | imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_ORIW)) {
      result := Cat(Fill(p.pcWidth - 32, (src0 | imm)(31)),
        (src0 | imm)(31, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_XORI)) {
      result := src0 ^ imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_XORIW)) {
      result := Cat(Fill(p.pcWidth - 32, (src0 ^ imm)(31)),
        (src0 ^ imm)(31, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_ADD)) {
      result := src0 + src1
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_AND)) {
      result := src0 & src1
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_OR)) {
      result := src0 | src1
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SUB)) {
      result := src0 - src1
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVI)) {
      result := imm
    }.elsewhen(isOneOf(op, LoadImmediateOpcodes)) {
      result := imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_MOVR)) {
      result := src0
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_B)) {
      result := Cat(Fill(p.pcWidth - 8, src0(7)), src0(7, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_H)) {
      result := Cat(Fill(p.pcWidth - 16, src0(15)), src0(15, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SEXT_W)) {
      result := Cat(Fill(p.pcWidth - 32, src0(31)), src0(31, 0))
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SLL) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_SLLI)) {
      val shiftAmount = Mux(
        op === opcode(FrontendOpcodeDecodeTable.OP_SLL), src1(5, 0), imm(5, 0))
      result := src0 << shiftAmount
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRL) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_SRLI)) {
      val shiftAmount = Mux(
        op === opcode(FrontendOpcodeDecodeTable.OP_SRL), src1(5, 0), imm(5, 0))
      result := src0 >> shiftAmount
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRA) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_SRAI)) {
      val shiftAmount = Mux(
        op === opcode(FrontendOpcodeDecodeTable.OP_SRA), src1(5, 0), imm(5, 0))
      result := (src0.asSInt >> shiftAmount).asUInt
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SLLW) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_SLLIW)) {
      val shiftAmount = Mux(
        op === opcode(FrontendOpcodeDecodeTable.OP_SLLW), src1(4, 0), imm(4, 0))
      val shifted = (src0(31, 0) << shiftAmount)(31, 0)
      result := Cat(Fill(p.pcWidth - 32, shifted(31)), shifted)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRLW) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_SRLIW)) {
      val shiftAmount = Mux(
        op === opcode(FrontendOpcodeDecodeTable.OP_SRLW), src1(4, 0), imm(4, 0))
      val shifted = src0(31, 0) >> shiftAmount
      result := Cat(Fill(p.pcWidth - 32, shifted(31)), shifted)
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SRAW) ||
        op === opcode(FrontendOpcodeDecodeTable.OP_SRAIW)) {
      val shiftAmount = Mux(
        op === opcode(FrontendOpcodeDecodeTable.OP_SRAW), src1(4, 0), imm(4, 0))
      val shifted = (src0(31, 0).asSInt >> shiftAmount).asUInt
      result := Cat(Fill(p.pcWidth - 32, shifted(31)), shifted)
    }

    terminal.writebacks(0).valid := true.B
    terminal.writebacks(0).destination := i2.row.destinations(0)
    terminal.writebacks(0).data := result
    terminal
  }

  val incoming = io.e1.bits
  val incomingOpcode = incoming.i2.row.opcode
  val supportedOpcode = isOneOf(incomingOpcode, SupportedOpcodes)
  val classExact = incoming.ownerClass === OooUopClass.Alu &&
    incoming.i2.row.reservation.uopClass === OooUopClass.Alu
  val recipeExact = incoming.i2.row.recipe.valid &&
    incoming.i2.row.recipe.opcode === incomingOpcode &&
    incoming.i2.row.recipe.disposition === OooOpcodeDisposition.Dispatch.U &&
    incoming.i2.row.recipe.dispatchClass === OooDispatchClass.Alu.U &&
    incoming.i2.row.recipe.sideEffectOwner === OooSideEffectOwner.Iex.U

  val expectedSourceCount = WireDefault(0.U(p.sourceCountWidth.W))
  when(isOneOf(incomingOpcode, ImmediateOpcodes) ||
      isOneOf(incomingOpcode, ImmediateShiftOpcodes) ||
      isOneOf(incomingOpcode,
        MoveRegisterOpcodes ++ CompactSignExtendOpcodes)) {
    expectedSourceCount := 1.U
  }.elsewhen(isOneOf(incomingOpcode,
      CompactRegisterOpcodes ++ RegisterShiftOpcodes)) {
    expectedSourceCount := 2.U
  }
  val needsImmediate = isOneOf(incomingOpcode,
    ImmediateOpcodes ++ ImmediateShiftOpcodes ++ MoveImmediateOpcodes ++
      LoadImmediateOpcodes)
  val logicalSourceMask = VecInit(
    incoming.i2.row.sources.map(_.valid)).asUInt
  val destinationMask = VecInit(
    incoming.i2.row.destinations.map(_.valid)).asUInt
  val destination = incoming.i2.row.destinations(0)
  val operandShapeExact = incoming.i2.row.valid &&
    incoming.i2.row.member.group.valid && incoming.i2.row.member.bid.valid &&
    incoming.i2.row.reservation.valid &&
    incoming.i2.sourceMask === logicalSourceMask &&
    PopCount(incoming.i2.sourceMask) === expectedSourceCount &&
    incoming.i2.row.recipe.pSourceCount === expectedSourceCount &&
    PopCount(destinationMask) === 1.U && destination.valid &&
    destination.kind =/= linxcore.common.DestinationKind.None &&
    (!needsImmediate || incoming.i2.row.immediateValid)
  val incomingKilled = killedByRecovery(incoming) || canceledByLoad(incoming)
  val incomingExact = classExact && recipeExact && operandShapeExact &&
    supportedOpcode

  val w1Valid = RegInit(false.B)
  val w1 = Reg(new OooIexAluTerminalTransaction(p))
  val w2Valid = RegInit(false.B)
  val w2 = Reg(new OooIexAluTerminalTransaction(p))

  val w1Killed = w1Valid &&
    (killedByRecovery(w1.execute) || canceledByLoad(w1.execute))
  val w2Killed = w2Valid &&
    (killedByRecovery(w2.execute) || canceledByLoad(w2.execute))
  val w2CanAccept = !w2Valid || w2Killed || io.w2.ready
  val w1Advances = w1Valid && !w1Killed && w2CanAccept
  val w1CanAccept = !w1Valid || w1Killed || w1Advances

  io.e1.ready := w1CanAccept && incomingExact && !incomingKilled
  io.w1Bypass.valid := w1Valid && !w1Killed
  io.w1Bypass.bits := w1
  io.w2.valid := w2Valid && !w2Killed
  io.w2.bits := w2

  io.rejected.valid := io.e1.valid && (!incomingExact || incomingKilled)
  io.rejected.bits.member := incoming.i2.row.member
  io.rejected.bits.opcode := incomingOpcode
  io.rejected.bits.classExact := classExact
  io.rejected.bits.recipeExact := recipeExact
  io.rejected.bits.operandShapeExact := operandShapeExact
  io.rejected.bits.supportedOpcode := supportedOpcode
  io.rejected.bits.incomingKilled := incomingKilled

  io.killedW1.valid := w1Killed
  io.killedW1.bits := w1
  io.killedW2.valid := w2Killed
  io.killedW2.bits := w2
  io.w1Occupied := w1Valid && !w1Killed
  io.w2Occupied := w2Valid && !w2Killed
  io.empty := !io.w1Occupied && !io.w2Occupied

  when(w2Killed || (io.w2.fire && !w1Advances)) {
    w2Valid := false.B
  }
  when(w1Advances) {
    w2Valid := true.B
    w2 := w1
  }

  when(w1Killed || w1Advances) {
    w1Valid := false.B
  }
  when(io.e1.fire) {
    w1Valid := true.B
    w1 := makeResult(incoming)
  }
}
