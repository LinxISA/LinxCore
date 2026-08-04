package linxcore.top.interface

import chisel3._
import linxcore.params.CoreParams

/** PC buffer index and byte offset carried with one uop instead of a full PC. */
class PcBufferIndexOffset(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val pcBufferIndex = UInt(InterfaceWidth.index(p.ooo.pcBufferEntries).W)
  val pcOffset = UInt(p.ooo.pcOffsetWidth.W)
  val allocationEpoch = UInt(p.ooo.pcAllocationEpochWidth.W)
}

/** Readyless I1 read address for the OOO-owned PC buffer. */
class PcBufferReadAddress(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val stid = UInt(p.ooo.stidWidth.W)
  val pcBufferIndex = UInt(InterfaceWidth.index(p.ooo.pcBufferEntries).W)
  val allocationEpoch = UInt(p.ooo.pcAllocationEpochWidth.W)
}

/** Side-effect-free D3 PC-buffer allocation result. */
class PcBufferD3Prepared(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W)
  val groupCount = UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W)
  val lanes = Vec(p.ooo.d3PrefixWidth, new PcBufferIndexOffset(p))
}
