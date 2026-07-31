package linxcore.top.interface

import chisel3._
import linxcore.params.CoreParams

object TraceSource extends ChiselEnum {
  val Top, Ifu, Ctu, Ooo, Iex, Lsu = Value
}

object TraceKind extends ChiselEnum {
  val Pipeline, Commit, Recovery, Trap, Memory, Performance = Value
}

class TraceEvent(val p: CoreParams) extends Bundle {
  val source = TraceSource()
  val kind = TraceKind()
  val cycle = UInt(64.W)
  val instructionValid = Bool()
  val instruction = new InstructionIdentity(p)
  val robValid = Bool()
  val rob = new RobIdentity(p)
  val pc = UInt(p.pcWidth.W)
  val opcode = UInt(p.opcodeWidth.W)
  val payload = UInt(p.dataWidth.W)
}

class TracePacket(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.dtu.traceWidth).W)
  val entries = Vec(p.dtu.traceWidth, new TraceEvent(p))
}

object DebugCommand extends ChiselEnum {
  val Halt, Resume, Step, ReadRegister, WriteRegister = Value
}

class DebugRequest(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val command = DebugCommand()
  val stid = UInt(InterfaceWidth.index(p.ooo.stidCount).W)
  val address = UInt(p.dataWidth.W)
  val data = UInt(p.dataWidth.W)
}

class DebugResponse(val p: CoreParams) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val accepted = Bool()
  val data = UInt(p.dataWidth.W)
  val error = Bool()
}
