package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, Valid}
import linxcore.frontend.FrontendOpcodeDecodeTable

object OooIexBctrlUpdateKind extends ChiselEnum {
  val Condition, Target = Value
}

class OooIexBruWriteback(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val destination = new OooIexDestinationState(p)
  val data = UInt(p.pcWidth.W)
}

class OooIexBctrlUpdate(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val kind = OooIexBctrlUpdateKind()
  val condition = Bool()
  val targetValid = Bool()
  val target = UInt(p.pcWidth.W)
}

/** Retained BRU result before BCTRL, RF, ROB, or redirect publication. */
class OooIexBruTerminalTransaction(val p: OooParams = OooParams())
    extends Bundle {
  val execute = new OooIexExecuteTransaction(p)
  val writeback = new OooIexBruWriteback(p)
  val bctrl = new OooIexBctrlUpdate(p)
}

class OooIexBruReject(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val opcode = UInt(p.opcodeWidth.W)
  val classExact = Bool()
  val recipeExact = Bool()
  val operandShapeExact = Bool()
  val supportedOpcode = Bool()
  val incomingKilled = Bool()
}

class OooIexBruPipelineIO(val p: OooParams = OooParams()) extends Bundle {
  val e1 = Flipped(Decoupled(new OooIexExecuteTransaction(p)))
  val e2 = Decoupled(new OooIexBruTerminalTransaction(p))

  val recoveryApply = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))

  val rejected = Valid(new OooIexBruReject(p))
  val killedE2 = Valid(new OooIexBruTerminalTransaction(p))
  val occupied = Output(Bool())
}

object OooIexBruPipeline {
  val PcValueOpcodes: Seq[Int] = Seq(
    FrontendOpcodeDecodeTable.OP_ADDTPC,
    FrontendOpcodeDecodeTable.OP_HL_ADDTPC)

  val CompareImmediateOpcodes: Seq[Int] = Seq(
    FrontendOpcodeDecodeTable.OP_CMP_ANDI,
    FrontendOpcodeDecodeTable.OP_CMP_EQI,
    FrontendOpcodeDecodeTable.OP_CMP_GEI,
    FrontendOpcodeDecodeTable.OP_CMP_GEUI,
    FrontendOpcodeDecodeTable.OP_CMP_LTI,
    FrontendOpcodeDecodeTable.OP_CMP_LTUI,
    FrontendOpcodeDecodeTable.OP_CMP_NEI,
    FrontendOpcodeDecodeTable.OP_CMP_ORI)

  val CompactConditionOpcodes: Seq[Int] = Seq(
    FrontendOpcodeDecodeTable.OP_C_SETC_EQ,
    FrontendOpcodeDecodeTable.OP_C_SETC_NE)

  val TargetOpcodes: Seq[Int] = Seq(
    FrontendOpcodeDecodeTable.OP_SETC_TGT,
    FrontendOpcodeDecodeTable.OP_C_SETC_TGT)

  val SupportedOpcodes: Seq[Int] = PcValueOpcodes ++
    CompareImmediateOpcodes ++ CompactConditionOpcodes ++ TargetOpcodes
}

/** One-cycle BRU value/condition execution with a retained E2 transaction.
  *
  * The module owns no BCTRL state and emits no redirect directly. E2 must be
  * accepted by a later atomic sink that applies the BCTRL/RF/ROB effects
  * together. Conditional/unconditional redirect opcodes stay fail-closed
  * until their missing architectural condition and prediction inputs have
  * explicit typed owners.
  */
class OooIexBruPipeline(val p: OooParams = OooParams()) extends Module {
  import OooIexBruPipeline._

  val io = IO(new OooIexBruPipelineIO(p))

  private def opcode(value: Int): UInt = value.U(p.opcodeWidth.W)

  private def isOneOf(op: UInt, values: Seq[Int]): Bool =
    values.map(value => op === opcode(value)).reduce(_ || _)

  private def killedByRecovery(execute: OooIexExecuteTransaction): Bool =
    io.recoveryApply.valid && io.recoveryApply.bits.valid &&
      OooRecoveryMembership.memberKilled(p, io.recoveryApply.bits,
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
      execute: OooIexExecuteTransaction): OooIexBruTerminalTransaction = {
    val terminal = Wire(new OooIexBruTerminalTransaction(p))
    terminal := 0.U.asTypeOf(terminal)
    terminal.execute := execute

    val i2 = execute.i2
    val op = i2.row.opcode
    val src0 = i2.sourceData(0)
    val src1 = i2.sourceData(1)
    val imm = i2.row.immediate
    val writebackOpcode = isOneOf(op,
      PcValueOpcodes ++ CompareImmediateOpcodes)
    val result = WireDefault(0.U(p.pcWidth.W))

    when(isOneOf(op, PcValueOpcodes)) {
      result := (i2.pc & "hffff_ffff_ffff_f000".U(p.pcWidth.W)) + imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_ANDI)) {
      result := (src0 & imm).orR
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_EQI)) {
      result := src0 === imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_GEI)) {
      result := src0.asSInt >= imm.asSInt
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_GEUI)) {
      result := src0 >= imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_LTI)) {
      result := src0.asSInt < imm.asSInt
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_LTUI)) {
      result := src0 < imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_NEI)) {
      result := src0 =/= imm
    }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_CMP_ORI)) {
      result := (src0 | imm).orR
    }

    terminal.writeback.valid := writebackOpcode
    terminal.writeback.destination := i2.row.destinations(0)
    terminal.writeback.data := result

    val compactCondition = isOneOf(op, CompactConditionOpcodes)
    val targetUpdate = isOneOf(op, TargetOpcodes)
    terminal.bctrl.valid := compactCondition || targetUpdate
    terminal.bctrl.kind := Mux(targetUpdate,
      OooIexBctrlUpdateKind.Target, OooIexBctrlUpdateKind.Condition)
    terminal.bctrl.condition := Mux(
      op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_EQ),
      src0 === src1,
      Mux(op === opcode(FrontendOpcodeDecodeTable.OP_C_SETC_NE),
        src0 =/= src1, src0 =/= 0.U))
    terminal.bctrl.targetValid := targetUpdate && src0 =/= 0.U
    terminal.bctrl.target := src0
    terminal
  }

  val incoming = io.e1.bits
  val incomingOpcode = incoming.i2.row.opcode
  val supportedOpcode = isOneOf(incomingOpcode, SupportedOpcodes)
  val classExact = incoming.ownerClass === OooUopClass.Bru &&
    incoming.i2.row.reservation.uopClass === OooUopClass.Bru
  val recipeExact = incoming.i2.row.recipe.valid &&
    incoming.i2.row.recipe.opcode === incomingOpcode &&
    incoming.i2.row.recipe.disposition === OooOpcodeDisposition.Dispatch.U &&
    incoming.i2.row.recipe.dispatchClass === OooDispatchClass.Bru.U &&
    incoming.i2.row.recipe.sideEffectOwner === OooSideEffectOwner.Bctrl.U

  val expectedSourceCount = WireDefault(0.U(p.sourceCountWidth.W))
  when(isOneOf(incomingOpcode, CompareImmediateOpcodes) ||
      isOneOf(incomingOpcode, TargetOpcodes)) {
    expectedSourceCount := 1.U
  }.elsewhen(isOneOf(incomingOpcode, CompactConditionOpcodes)) {
    expectedSourceCount := 2.U
  }
  val expectedDestinationCount = Mux(
    isOneOf(incomingOpcode, PcValueOpcodes ++ CompareImmediateOpcodes),
    1.U, 0.U)
  val logicalSourceMask = VecInit(
    incoming.i2.row.sources.map(_.valid)).asUInt
  val destinationMask = VecInit(
    incoming.i2.row.destinations.map(_.valid)).asUInt
  val needsImmediate = isOneOf(incomingOpcode,
    PcValueOpcodes ++ CompareImmediateOpcodes)
  val needsPc = isOneOf(incomingOpcode, PcValueOpcodes)
  val operandShapeExact = incoming.i2.row.valid &&
    incoming.i2.row.member.group.valid && incoming.i2.row.member.bid.valid &&
    incoming.i2.row.reservation.valid &&
    incoming.i2.sourceMask === logicalSourceMask &&
    PopCount(incoming.i2.sourceMask) === expectedSourceCount &&
    incoming.i2.row.recipe.pSourceCount === expectedSourceCount &&
    PopCount(destinationMask) === expectedDestinationCount &&
    (!needsImmediate || incoming.i2.row.immediateValid) &&
    (!needsPc || incoming.i2.pcValid)
  val incomingKilled = killedByRecovery(incoming) || canceledByLoad(incoming)
  val incomingExact = classExact && recipeExact && operandShapeExact &&
    supportedOpcode

  val e2Valid = RegInit(false.B)
  val e2 = Reg(new OooIexBruTerminalTransaction(p))
  val e2Killed = e2Valid &&
    (killedByRecovery(e2.execute) || canceledByLoad(e2.execute))
  val canAccept = !e2Valid || e2Killed || io.e2.ready

  io.e1.ready := canAccept && incomingExact && !incomingKilled
  io.e2.valid := e2Valid && !e2Killed
  io.e2.bits := e2
  io.rejected.valid := io.e1.valid && (!incomingExact || incomingKilled)
  io.rejected.bits.member := incoming.i2.row.member
  io.rejected.bits.opcode := incomingOpcode
  io.rejected.bits.classExact := classExact
  io.rejected.bits.recipeExact := recipeExact
  io.rejected.bits.operandShapeExact := operandShapeExact
  io.rejected.bits.supportedOpcode := supportedOpcode
  io.rejected.bits.incomingKilled := incomingKilled
  io.killedE2.valid := e2Killed
  io.killedE2.bits := e2
  io.occupied := e2Valid && !e2Killed

  when(e2Killed || io.e2.fire) {
    e2Valid := false.B
  }
  when(io.e1.fire) {
    e2Valid := true.B
    e2 := makeResult(incoming)
  }
}
