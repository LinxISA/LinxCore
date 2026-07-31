package linxcore.top.interface

import chisel3._
import linxcore.params.CoreParams

class CommitEntry(val p: CoreParams) extends Bundle {
  val instruction = new InstructionIdentity(p)
  val rob = new RobIdentity(p)
  val pc = UInt(p.pcWidth.W)
  val instructionBits = UInt(p.instructionWidth.W)
  val instructionLengthBytes = UInt(4.W)
  val opcode = UInt(p.opcodeWidth.W)
  val destination = new RenamedDestination(p)
  val resultValid = Bool()
  val result = UInt(p.dataWidth.W)
  val memoryValid = Bool()
  val memory = new MemoryIdentity(p)
  val storeData = UInt(p.dataWidth.W)
  val storeMask = UInt((p.dataWidth / 8).W)
  val trap = new TrapEvent(p)
}

class CommitTxn(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.widths.retireWidth).W)
  val entries = Vec(p.widths.retireWidth, new CommitEntry(p))
}
