package linxcore.top.interface

import chisel3._
import chisel3.util.MuxLookup
import linxcore.params.CoreParams

object MemoryCommand extends ChiselEnum {
  val Read, Write, AcquireRead, AcquireWrite, Invalidate = Value
}

object MemoryAccessKind extends ChiselEnum {
  val InstructionLine, InstructionTranslation, Data, Device = Value
}

/** Log2 byte-count encoding for every external memory transaction.
  *
  * The named values make a 64-byte line representable without widening or
  * overloading the semantic 1/2/4/8-byte access fields used inside IEX/LSU.
  */
object MemorySize extends ChiselEnum {
  val Bytes1, Bytes2, Bytes4, Bytes8, Bytes16, Bytes32, Bytes64 = Value

  def fromBytes(bytes: UInt): Type = MuxLookup(bytes, Bytes1)(Seq(
    1.U -> Bytes1,
    2.U -> Bytes2,
    4.U -> Bytes4,
    8.U -> Bytes8,
    16.U -> Bytes16,
    32.U -> Bytes32,
    64.U -> Bytes64))

  def bytes(size: Type): UInt = MuxLookup(size.asUInt, 1.U(7.W))(Seq(
    Bytes1.asUInt -> 1.U,
    Bytes2.asUInt -> 2.U,
    Bytes4.asUInt -> 4.U,
    Bytes8.asUInt -> 8.U,
    Bytes16.asUInt -> 16.U,
    Bytes32.asUInt -> 32.U,
    Bytes64.asUInt -> 64.U))
}

class MemoryRequestTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryTransactionIdentity(p)
  val command = MemoryCommand()
  val accessKind = MemoryAccessKind()
  val address = UInt(p.physicalAddressWidth.W)
  val data = UInt(p.dataWidth.W)
  val byteMask = UInt((p.dataWidth / 8).W)
  val size = MemorySize()
  val instructionSide = Bool()
}

class MemoryResponseTxn(val p: CoreParams) extends Bundle {
  val identity = new MemoryTransactionIdentity(p)
  val address = UInt(p.physicalAddressWidth.W)
  val data = UInt(p.dataWidth.W)
  val lineData = UInt((p.lsu.lineBytes * 8).W)
  val denied = Bool()
  val corrupt = Bool()
  val errorCause = UInt(p.trapCauseWidth.W)
  val attributesValid = Bool()
  val readable = Bool()
  val writable = Bool()
  val cacheable = Bool()
  val device = Bool()
}
