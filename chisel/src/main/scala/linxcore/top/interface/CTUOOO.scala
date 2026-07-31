package linxcore.top.interface

import chisel3._
import linxcore.params.CoreParams

object FrontEndOpKind extends ChiselEnum {
  val Encoded64, TemplateUop = Value
}

object OperandKind extends ChiselEnum {
  val None, Gpr, T, U, Immediate = Value
}

object UopClass extends ChiselEnum {
  val Alu, Bru, Agu, Std, System, Cmd, Boundary = Value
}

class FrontEndOp(val p: CoreParams) extends Bundle {
  val kind = FrontEndOpKind()
  val parent = new FetchedInstruction(p)
  val templateOrdinal = UInt(8.W)
  val templateCount = UInt(8.W)
  val templateOpcode = UInt(p.opcodeWidth.W)
  val templateImmediate = UInt(p.dataWidth.W)
}

class D1Packet(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.widths.ctuOutputWidth).W)
  val entries = Vec(p.widths.ctuOutputWidth, new FrontEndOp(p))
}

class DecodedSource(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val kind = OperandKind()
  val atag = UInt(p.archRegWidth.W)
  val relativeIndex = UInt(p.archRegWidth.W)
}

class DecodedDestination(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val kind = OperandKind()
  val atag = UInt(p.archRegWidth.W)
  val relativeIndex = UInt(p.archRegWidth.W)
}

class DecodedUop(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val instruction = new FrontEndOp(p)
  val rob = new RobIdentity(p)
  val opcode = UInt(p.opcodeWidth.W)
  val uopClass = UopClass()
  val sources = Vec(p.maxSourceOperands, new DecodedSource(p))
  val destinations =
    Vec(p.maxDestinationOperands, new DecodedDestination(p))
  val immediateValid = Bool()
  val immediate = UInt(p.dataWidth.W)
  val earlyComplete = Bool()
  val blockStart = Bool()
  val blockStop = Bool()
  val blockBoundary = Bool()
}
