package linxcore.fpga.zybo

import chisel3._
import chisel3.util.{Cat, Decoupled, MuxLookup}

sealed trait LinxMmioTarget
object LinxMmioTarget {
  case object UartTx extends LinxMmioTarget
  case object UartRx extends LinxMmioTarget
  case object UartStatus extends LinxMmioTarget
  case object LinuxExit extends LinxMmioTarget
  case object TestFinisher extends LinxMmioTarget
  case object Virtio extends LinxMmioTarget
  case object Unmapped extends LinxMmioTarget
}

object LinxMmioMap {
  final val VirtioSlotBytes = BigInt("200", 16)
  final val VirtioSlotCount = 4
  final val VirtioSize = VirtioSlotBytes * VirtioSlotCount

  def isDdr(addr: BigInt): Boolean =
    addr >= ZyboZ720Generated.LinxMemoryBase &&
      addr < ZyboZ720Generated.LinxMemoryBase + ZyboZ720Generated.LinxMemorySize

  def classify(addr: BigInt, write: Boolean): LinxMmioTarget = {
    import LinxMmioTarget._

    if (addr == ZyboZ720Generated.UartData)
      if (write) UartTx else UartRx
    else if (addr == ZyboZ720Generated.UartStatusLinuxExit)
      if (write) LinuxExit else UartStatus
    else if (addr == ZyboZ720Generated.TestFinisher && write)
      TestFinisher
    else if (addr >= ZyboZ720Generated.VirtioBase &&
        addr < ZyboZ720Generated.VirtioBase + VirtioSize)
      Virtio
    else Unmapped
  }
}

class LinxMmioRouterIO extends Bundle {
  val request = Flipped(Decoupled(new LinxMemRequest))
  val response = Decoupled(new LinxMemResponse)

  val ddrRequest = Decoupled(new LinxMemRequest)
  val ddrResponse = Flipped(Decoupled(new LinxMemResponse))

  val mmioRequest = Decoupled(new LinxMmioRequest)
  val mmioResponse = Flipped(Decoupled(new LinxMmioResponse))

  val linuxExit = Decoupled(new LinxMmioEvent)
  val testFinisher = Decoupled(new LinxMmioEvent)
}

object LinxMmioRouterState extends ChiselEnum {
  val Idle, SendDdr, WaitDdr, SendMmio, WaitMmio, SendLinuxExit,
    SendTestFinisher, Respond = Value
}

class LinxMmioRouter extends Module {
  val io = IO(new LinxMmioRouterIO)

  import LinxMmioRouterState._

  private val addrWidth = LinxPlatformMemory.AddressWidth
  private val beatBytes = LinxPlatformMemory.BeatBytes
  private val ddrBase = ZyboZ720Generated.LinxMemoryBase.U(addrWidth.W)
  private val ddrEnd =
    (ZyboZ720Generated.LinxMemoryBase + ZyboZ720Generated.LinxMemorySize).U(addrWidth.W)
  private val uartData = ZyboZ720Generated.UartData.U(addrWidth.W)
  private val uartStatusExit = ZyboZ720Generated.UartStatusLinuxExit.U(addrWidth.W)
  private val testFinisher = ZyboZ720Generated.TestFinisher.U(addrWidth.W)
  private val virtioBase = ZyboZ720Generated.VirtioBase.U(addrWidth.W)
  private val virtioEnd =
    (ZyboZ720Generated.VirtioBase + LinxMmioMap.VirtioSize).U(addrWidth.W)

  private val state = RegInit(Idle)
  private val retainedRequest = Reg(new LinxMemRequest)
  private val retainedMmioTarget = Reg(LinxMmioKind())
  private val retainedResponse = Reg(new LinxMemResponse)

  private val requestAddr = io.request.bits.addr
  private val requestIsUartData = requestAddr === uartData
  private val requestIsUartStatusExit = requestAddr === uartStatusExit
  private val requestIsTestFinisher = requestAddr === testFinisher && io.request.bits.write
  private val requestIsVirtio = requestAddr >= virtioBase && requestAddr < virtioEnd
  private val requestIsMmio = requestIsUartData || requestIsUartStatusExit ||
    requestIsTestFinisher || requestIsVirtio
  private val requestIsDdr = requestAddr >= ddrBase && requestAddr < ddrEnd

  private val byteMask = MuxLookup(io.request.bits.size, 0.U(beatBytes.W))(Seq(
    0.U -> 1.U(beatBytes.W),
    1.U -> 3.U(beatBytes.W),
    2.U -> 15.U(beatBytes.W),
    3.U -> 255.U(beatBytes.W)
  ))
  private val aligned = MuxLookup(io.request.bits.size, false.B)(Seq(
    0.U -> true.B,
    1.U -> !requestAddr(0),
    2.U -> !requestAddr(1, 0).orR,
    3.U -> !requestAddr(2, 0).orR
  ))
  private val expectedStrobe = (byteMask << requestAddr(2, 0))(beatBytes - 1, 0)
  private val strobeValid = Mux(
    io.request.bits.write,
    io.request.bits.wstrb === expectedStrobe,
    io.request.bits.wstrb === 0.U)
  private val targetSizeValid = Mux(
    requestIsUartData,
    io.request.bits.size === 0.U,
    Mux(
      requestIsUartStatusExit || requestIsTestFinisher,
      io.request.bits.size === 2.U,
      io.request.bits.size <= 3.U))
  private val mmioAccessValid =
    !io.request.bits.line && targetSizeValid && aligned && strobeValid

  io.request.ready := state === Idle
  io.response.valid := state === Respond
  io.response.bits := retainedResponse

  io.ddrRequest.valid := state === SendDdr
  io.ddrRequest.bits := retainedRequest
  io.ddrResponse.ready := state === WaitDdr

  io.mmioRequest.valid := state === SendMmio
  io.mmioRequest.bits := 0.U.asTypeOf(io.mmioRequest.bits)
  io.mmioRequest.bits.id := retainedRequest.id
  io.mmioRequest.bits.source := retainedRequest.source
  io.mmioRequest.bits.target := retainedMmioTarget
  io.mmioRequest.bits.addr := retainedRequest.addr
  io.mmioRequest.bits.write := retainedRequest.write
  io.mmioRequest.bits.size := retainedRequest.size
  io.mmioRequest.bits.wdata := retainedRequest.wdata
  io.mmioRequest.bits.wstrb := retainedRequest.wstrb
  io.mmioRequest.bits.last := retainedRequest.last
  io.mmioResponse.ready := state === WaitMmio

  private val retainedByteShift = Cat(retainedRequest.addr(2, 0), 0.U(3.W))
  private val retainedScalarPayload =
    (retainedRequest.wdata >> retainedByteShift)(31, 0)

  io.linuxExit.valid := state === SendLinuxExit
  io.linuxExit.bits.id := retainedRequest.id
  io.linuxExit.bits.payload := retainedScalarPayload
  io.linuxExit.bits.last := retainedRequest.last

  io.testFinisher.valid := state === SendTestFinisher
  io.testFinisher.bits.id := retainedRequest.id
  io.testFinisher.bits.payload := retainedScalarPayload
  io.testFinisher.bits.last := retainedRequest.last

  when(io.request.fire) {
    retainedRequest := io.request.bits

    when(requestIsMmio) {
      when(!mmioAccessValid) {
        retainedResponse.id := io.request.bits.id
        retainedResponse.rdata := 0.U
        retainedResponse.fault := LinxMemFault.Access
        retainedResponse.last := io.request.bits.last
        state := Respond
      }.elsewhen(requestIsUartData) {
        retainedMmioTarget := Mux(
          io.request.bits.write, LinxMmioKind.UartTx, LinxMmioKind.UartRx)
        state := SendMmio
      }.elsewhen(requestIsUartStatusExit && !io.request.bits.write) {
        retainedMmioTarget := LinxMmioKind.UartStatus
        state := SendMmio
      }.elsewhen(requestIsUartStatusExit) {
        state := SendLinuxExit
      }.elsewhen(requestIsTestFinisher) {
        state := SendTestFinisher
      }.otherwise {
        retainedMmioTarget := LinxMmioKind.Virtio
        state := SendMmio
      }
    }.elsewhen(requestIsDdr) {
      state := SendDdr
    }.otherwise {
      retainedResponse.id := io.request.bits.id
      retainedResponse.rdata := 0.U
      retainedResponse.fault := LinxMemFault.Decode
      retainedResponse.last := io.request.bits.last
      state := Respond
    }
  }

  when(io.ddrRequest.fire) {
    state := WaitDdr
  }

  when(io.ddrResponse.fire) {
    retainedResponse := io.ddrResponse.bits
    state := Respond
  }

  when(io.mmioRequest.fire) {
    state := WaitMmio
  }

  when(io.mmioResponse.fire) {
    retainedResponse.id := io.mmioResponse.bits.id
    retainedResponse.rdata := io.mmioResponse.bits.rdata
    retainedResponse.fault := io.mmioResponse.bits.fault
    retainedResponse.last := io.mmioResponse.bits.last
    state := Respond
  }

  when(io.linuxExit.fire || io.testFinisher.fire) {
    retainedResponse.id := retainedRequest.id
    retainedResponse.rdata := 0.U
    retainedResponse.fault := LinxMemFault.NoFault
    retainedResponse.last := retainedRequest.last
    state := Respond
  }

  when(io.response.fire) {
    state := Idle
  }
}
