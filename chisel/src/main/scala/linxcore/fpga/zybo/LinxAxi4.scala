package linxcore.fpga.zybo

import chisel3._
import chisel3.util.Decoupled

object LinxAxi4 {
  final val AddressWidth = LinxPlatformMemory.AddressWidth
  final val DataWidth = ZyboZ720Generated.AxiDataWidth
  final val DataBytes = DataWidth / 8
  final val IdWidth = 1
  final val SupportedId = 0

  final val BurstFixed = 0
  final val BurstIncr = 1
  final val BurstWrap = 2

  final val RespOkay = 0
  final val RespExOkay = 1
  final val RespSlvErr = 2
  final val RespDecErr = 3

  require(AddressWidth == 32, "Zybo HP0 AXI address width must be 32 bits")
  require(DataWidth == 64, "Zybo HP0 AXI data width must be 64 bits")
  require(ZyboZ720Generated.MaxOutstanding == 1,
    "the first Zybo HP0 profile supports exactly one outstanding transaction")
  require(IdWidth == 1, "the first Zybo HP0 profile exposes one AXI ID bit")
  require(SupportedId < (1 << IdWidth), "configured AXI ID must fit its width")
}

final case class LinxAxi4Burst(
    address: BigInt,
    len: Int,
    size: Int,
    burst: Int,
    addresses: Seq[BigInt])

object LinxAxi4Reference {
  private val SupportedByteCounts = Set(1, 2, 4, 8, 64)
  private val RamBase = ZyboZ720Generated.LinxMemoryBase
  private val RamEnd = RamBase + ZyboZ720Generated.LinxMemorySize

  def readBurst(address: BigInt, bytes: Int): LinxAxi4Burst = {
    require(SupportedByteCounts.contains(bytes),
      s"unsupported AXI read size: $bytes bytes")
    require(address >= RamBase && address < RamEnd,
      f"AXI read address 0x$address%x is outside Linx RAM")
    require((address & (bytes - 1)) == 0,
      f"AXI read address 0x$address%x is not $bytes-byte aligned")
    val finalAddress = address + bytes - 1
    require(finalAddress < RamEnd,
      f"AXI read ending at 0x$finalAddress%x is outside Linx RAM")
    require((address >> 12) == (finalAddress >> 12),
      f"AXI read 0x$address%x..0x$finalAddress%x crosses 4 KiB")

    val line = bytes == ZyboZ720Generated.LineBytes
    val beats = if (line) bytes / LinxAxi4.DataBytes else 1
    val size = if (line) 3 else Integer.numberOfTrailingZeros(bytes)
    val addresses =
      if (line) (0 until beats).map(beat => address + beat * LinxAxi4.DataBytes)
      else Seq(address)
    LinxAxi4Burst(address, beats - 1, size, LinxAxi4.BurstIncr, addresses)
  }
}

class LinxAxi4Address extends Bundle {
  val id = UInt(LinxAxi4.IdWidth.W)
  val addr = UInt(LinxAxi4.AddressWidth.W)
  val len = UInt(8.W)
  val size = UInt(3.W)
  val burst = UInt(2.W)
}

class LinxAxi4ReadData extends Bundle {
  val id = UInt(LinxAxi4.IdWidth.W)
  val data = UInt(LinxAxi4.DataWidth.W)
  val resp = UInt(2.W)
  val last = Bool()
}

class LinxAxi4WriteData extends Bundle {
  val data = UInt(LinxAxi4.DataWidth.W)
  val strb = UInt(LinxAxi4.DataBytes.W)
  val last = Bool()
}

class LinxAxi4WriteResponse extends Bundle {
  val id = UInt(LinxAxi4.IdWidth.W)
  val resp = UInt(2.W)
}

class LinxAxi4MasterPort extends Bundle {
  val ar = Decoupled(new LinxAxi4Address)
  val r = Flipped(Decoupled(new LinxAxi4ReadData))
  val aw = Decoupled(new LinxAxi4Address)
  val w = Decoupled(new LinxAxi4WriteData)
  val b = Flipped(Decoupled(new LinxAxi4WriteResponse))
}
