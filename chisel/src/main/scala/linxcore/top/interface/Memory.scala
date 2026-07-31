package linxcore.top.interface

import chisel3._
import linxcore.params.CoreParams

object MemoryCommand extends ChiselEnum {
  val Read, Write, AcquireRead, AcquireWrite, Invalidate = Value
}

object MemoryAccessKind extends ChiselEnum {
  val InstructionLine, InstructionTranslation, Data, Device = Value
}

class MemoryRequestTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryTransactionIdentity(p)
  val command = MemoryCommand()
  val accessKind = MemoryAccessKind()
  val address = UInt(p.physicalAddressWidth.W)
  val data = UInt(p.dataWidth.W)
  val byteMask = UInt((p.dataWidth / 8).W)
  val sizeBytes = UInt(4.W)
  val instructionSide = Bool()
}

class MemoryResponseTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryTransactionIdentity(p)
  val data = UInt(p.dataWidth.W)
  val denied = Bool()
  val corrupt = Bool()
  val errorCause = UInt(p.trapCauseWidth.W)
}
