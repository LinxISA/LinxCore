package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.{DecodedDestination, DecodedOperand, InterfaceParams}

class FrontendHlSdiPrOperandDecodeProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val active = Input(Bool())
  val insnRaw = Input(UInt(p.insnWidth.W))
  val lenBytes = Input(UInt(p.lenWidth.W))

  val metaValid = Output(Bool())
  val opcode = Output(UInt(p.opcodeWidth.W))
  val metaLenBytes = Output(UInt(p.lenWidth.W))
  val majorCategory = Output(UInt(4.W))
  val dispatchTarget = Output(UInt(3.W))
  val rdKind = Output(UInt(2.W))
  val rs1Kind = Output(UInt(2.W))
  val rs2Kind = Output(UInt(2.W))
  val immKind = Output(UInt(6.W))
  val isLoad = Output(Bool())
  val isStore = Output(Bool())
  val src = Output(Vec(3, new DecodedOperand(p)))
  val dst = Output(new DecodedDestination(p))
  val imm = Output(UInt(p.immWidth.W))
  val immValid = Output(Bool())
}

class FrontendHlSdiPrOperandDecodeProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new FrontendHlSdiPrOperandDecodeProbeIO(p))

  val meta = FrontendOpcodeDecodeTable.decode(p, io.insnRaw, io.lenBytes)
  val operands = Module(new FrontendOperandDecode(p))
  operands.io.active := io.active && meta.valid
  operands.io.meta := meta
  operands.io.insn := io.insnRaw

  io.metaValid := meta.valid
  io.opcode := meta.opcode
  io.metaLenBytes := meta.lenBytes
  io.majorCategory := meta.majorCategory
  io.dispatchTarget := meta.dispatchTarget.asUInt
  io.rdKind := meta.rdKind
  io.rs1Kind := meta.rs1Kind
  io.rs2Kind := meta.rs2Kind
  io.immKind := meta.immKind
  io.isLoad := meta.isLoad
  io.isStore := meta.isStore
  io.src := operands.io.src
  io.dst := operands.io.dst(0)
  io.imm := operands.io.imm
  io.immValid := operands.io.immValid
}

object EmitFrontendHlSdiPrOperandDecodeProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new FrontendHlSdiPrOperandDecodeProbe(InterfaceParams()),
    args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
