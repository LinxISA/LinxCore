package linxcore.fpga.zybo

import chisel3._
import chisel3.util.log2Ceil

object LinxPlatformMemory {
  final val AddressWidth = ZyboZ720PlatformParams.LinuxMinCore.scalarLsu.addrWidth
  final val BeatDataWidth = ZyboZ720Generated.AxiDataWidth
  final val BeatBytes = BeatDataWidth / 8
  final val LineDataWidth = ZyboZ720Generated.LineBytes * 8
  final val RequestIdWidth = ZyboZ720PlatformParams.LinuxMinCore.lsidWidth
  final val SizeWidth = log2Ceil(log2Ceil(ZyboZ720Generated.LineBytes) + 1)

  require(BeatDataWidth % 8 == 0, "native memory beat width must contain whole bytes")
  require(ZyboZ720Generated.LineBytes % BeatBytes == 0,
    "native memory line must contain whole transport beats")
}

object LinxMemSource extends ChiselEnum {
  val Instruction, Load, Store, Device = Value
}

object LinxMemFault extends ChiselEnum {
  val NoFault, Decode, Access, Protocol, Bus = Value
}

class LinxMemRequest extends Bundle {
  val id = UInt(LinxPlatformMemory.RequestIdWidth.W)
  val source = LinxMemSource()
  val addr = UInt(LinxPlatformMemory.AddressWidth.W)
  val write = Bool()
  val size = UInt(LinxPlatformMemory.SizeWidth.W)
  val wdata = UInt(LinxPlatformMemory.BeatDataWidth.W)
  val wstrb = UInt(LinxPlatformMemory.BeatBytes.W)
  val line = Bool()
  val last = Bool()
}

class LinxMemResponse extends Bundle {
  val id = UInt(LinxPlatformMemory.RequestIdWidth.W)
  val rdata = UInt(LinxPlatformMemory.LineDataWidth.W)
  val fault = LinxMemFault()
  val last = Bool()
}

object LinxMmioKind extends ChiselEnum {
  val UartTx, UartRx, UartStatus, Virtio = Value
}

class LinxMmioRequest extends Bundle {
  val id = UInt(LinxPlatformMemory.RequestIdWidth.W)
  val source = LinxMemSource()
  val target = LinxMmioKind()
  val addr = UInt(LinxPlatformMemory.AddressWidth.W)
  val write = Bool()
  val size = UInt(LinxPlatformMemory.SizeWidth.W)
  val wdata = UInt(LinxPlatformMemory.BeatDataWidth.W)
  val wstrb = UInt(LinxPlatformMemory.BeatBytes.W)
  val last = Bool()
}

class LinxMmioResponse extends Bundle {
  val id = UInt(LinxPlatformMemory.RequestIdWidth.W)
  val rdata = UInt(LinxPlatformMemory.BeatDataWidth.W)
  val fault = LinxMemFault()
  val last = Bool()
}

class LinxMmioEvent extends Bundle {
  val id = UInt(LinxPlatformMemory.RequestIdWidth.W)
  val payload = UInt(32.W)
  val last = Bool()
}
