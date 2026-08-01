package linxcore.fpga.zybo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class LinxMmioRouterSpec extends AnyFunSuite with ChiselSim {
  private def init(dut: LinxMmioRouter): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.response.ready.poke(false.B)

    dut.io.ddrRequest.ready.poke(false.B)
    dut.io.ddrResponse.valid.poke(false.B)
    dut.io.ddrResponse.bits.poke(0.U.asTypeOf(dut.io.ddrResponse.bits))

    dut.io.mmioRequest.ready.poke(false.B)
    dut.io.mmioResponse.valid.poke(false.B)
    dut.io.mmioResponse.bits.poke(0.U.asTypeOf(dut.io.mmioResponse.bits))

    dut.io.linuxExit.ready.poke(false.B)
    dut.io.testFinisher.ready.poke(false.B)
  }

  private def pokeRequest(
      dut: LinxMmioRouter,
      id: Long,
      addr: BigInt,
      write: Boolean,
      size: Int,
      wdata: BigInt = 0,
      wstrb: BigInt = 0,
      line: Boolean = false,
      last: Boolean = true,
      source: LinxMemSource.Type = LinxMemSource.Load): Unit = {
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.request.bits.id.poke(id.U)
    dut.io.request.bits.source.poke(source)
    dut.io.request.bits.addr.poke(addr.U)
    dut.io.request.bits.write.poke(write.B)
    dut.io.request.bits.size.poke(size.U)
    dut.io.request.bits.wdata.poke(wdata.U)
    dut.io.request.bits.wstrb.poke(wstrb.U)
    dut.io.request.bits.line.poke(line.B)
    dut.io.request.bits.last.poke(last.B)
  }

  private def acceptRequest(dut: LinxMmioRouter): Unit = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
  }

  private def expectResponse(
      dut: LinxMmioRouter,
      id: Long,
      rdata: BigInt,
      fault: LinxMemFault.Type,
      last: Boolean = true): Unit = {
    dut.io.response.valid.expect(true.B)
    dut.io.response.bits.id.expect(id.U)
    dut.io.response.bits.rdata.expect(rdata.U)
    dut.io.response.bits.fault.expect(fault)
    dut.io.response.bits.last.expect(last.B)
  }

  private def consumeMatchingResponseExactlyOnce(
      dut: LinxMmioRouter,
      id: Long,
      rdata: BigInt,
      fault: LinxMemFault.Type,
      last: Boolean = true): Unit = {
    var handshakes = 0
    for (_ <- 0 until 3) {
      expectResponse(dut, id, rdata, fault, last)
      dut.io.response.ready.expect(false.B)
      dut.clock.step()
    }
    dut.io.response.ready.poke(true.B)
    for (_ <- 0 until 3) {
      if (dut.io.response.valid.peek().litToBoolean &&
          dut.io.response.ready.peek().litToBoolean) {
        expectResponse(dut, id, rdata, fault, last)
        handshakes += 1
      }
      dut.clock.step()
    }
    dut.io.response.ready.poke(false.B)
    for (_ <- 0 until 3) {
      dut.io.response.valid.expect(false.B)
      dut.clock.step()
    }
    assert(handshakes == 1)
    dut.io.request.ready.expect(true.B)
  }

  test("write at 0x10000004 is Linux exit while read is UART status") {
    assert(LinxMmioMap.classify(BigInt("10000004", 16), write = true) ==
      LinxMmioTarget.LinuxExit)
    assert(LinxMmioMap.classify(BigInt("10000004", 16), write = false) ==
      LinxMmioTarget.UartStatus)
  }

  test("test finisher and DDR never alias") {
    assert(LinxMmioMap.classify(BigInt("10009000", 16), write = true) ==
      LinxMmioTarget.TestFinisher)
    assert(!LinxMmioMap.isDdr(BigInt("10009000", 16)))
  }

  test("map classifies UART directions and exact DDR and virtio boundaries") {
    assert(LinxMmioMap.classify(ZyboZ720Generated.UartData, write = true) ==
      LinxMmioTarget.UartTx)
    assert(LinxMmioMap.classify(ZyboZ720Generated.UartData, write = false) ==
      LinxMmioTarget.UartRx)
    assert(LinxMmioMap.isDdr(ZyboZ720Generated.LinxMemoryBase))
    assert(LinxMmioMap.isDdr(
      ZyboZ720Generated.LinxMemoryBase + ZyboZ720Generated.LinxMemorySize - 1))
    assert(!LinxMmioMap.isDdr(
      ZyboZ720Generated.LinxMemoryBase + ZyboZ720Generated.LinxMemorySize))
    assert(LinxMmioMap.classify(ZyboZ720Generated.VirtioBase, write = false) ==
      LinxMmioTarget.Virtio)
    assert(LinxMmioMap.classify(
      ZyboZ720Generated.VirtioBase + LinxMmioMap.VirtioSize - 1,
      write = true) == LinxMmioTarget.Virtio)
    assert(LinxMmioMap.classify(
      ZyboZ720Generated.VirtioBase + LinxMmioMap.VirtioSize,
      write = false) == LinxMmioTarget.Unmapped)
  }

  test("fault and source encodings are stable for later native consumers") {
    assert(LinxMemFault.NoFault.litValue == 0)
    assert(LinxMemFault.Decode.litValue == 1)
    assert(LinxMemFault.Access.litValue == 2)
    assert(LinxMemFault.Protocol.litValue == 3)
    assert(LinxMemFault.Bus.litValue == 4)
    assert(LinxMemSource.Instruction.litValue == 0)
    assert(LinxMemSource.Load.litValue == 1)
    assert(LinxMemSource.Store.litValue == 2)
    assert(LinxMemSource.Device.litValue == 3)
    assert(LinxMmioKind.UartTx.litValue == 0)
    assert(LinxMmioKind.UartRx.litValue == 1)
    assert(LinxMmioKind.UartStatus.litValue == 2)
    assert(LinxMmioKind.Virtio.litValue == 3)
  }

  test("DDR request is retained at exactly one destination under backpressure") {
    simulate(new LinxMmioRouter) { dut =>
      init(dut)
      val lineResponse =
        (BigInt(1) << (LinxPlatformMemory.LineDataWidth - 1)) |
          BigInt("123456789abcdef0", 16)
      pokeRequest(
        dut,
        id = 0x80000021L,
        addr = BigInt("00102000", 16),
        write = false,
        size = 6,
        line = true,
        last = false,
        source = LinxMemSource.Instruction)
      acceptRequest(dut)

      dut.io.request.ready.expect(false.B)
      dut.io.ddrRequest.valid.expect(true.B)
      dut.io.mmioRequest.valid.expect(false.B)
      dut.io.linuxExit.valid.expect(false.B)
      dut.io.testFinisher.valid.expect(false.B)
      dut.io.ddrRequest.bits.id.expect(0x80000021L.U)
      dut.io.ddrRequest.bits.source.expect(LinxMemSource.Instruction)
      dut.io.ddrRequest.bits.addr.expect(BigInt("00102000", 16).U)
      dut.io.ddrRequest.bits.write.expect(false.B)
      dut.io.ddrRequest.bits.size.expect(6.U)
      dut.io.ddrRequest.bits.wdata.expect(0.U)
      dut.io.ddrRequest.bits.wstrb.expect(0.U)
      dut.io.ddrRequest.bits.line.expect(true.B)
      dut.io.ddrRequest.bits.last.expect(false.B)

      pokeRequest(
        dut,
        id = 0x7f,
        addr = ZyboZ720Generated.UartData,
        write = true,
        size = 0,
        wdata = 0xaa,
        wstrb = 1,
        line = false,
        last = true,
        source = LinxMemSource.Device)
      for (_ <- 0 until 4) {
        dut.io.request.ready.expect(false.B)
        dut.io.ddrRequest.valid.expect(true.B)
        dut.io.ddrRequest.bits.id.expect(0x80000021L.U)
        dut.io.ddrRequest.bits.source.expect(LinxMemSource.Instruction)
        dut.io.ddrRequest.bits.addr.expect(BigInt("00102000", 16).U)
        dut.io.ddrRequest.bits.write.expect(false.B)
        dut.io.ddrRequest.bits.size.expect(6.U)
        dut.io.ddrRequest.bits.wdata.expect(0.U)
        dut.io.ddrRequest.bits.wstrb.expect(0.U)
        dut.io.ddrRequest.bits.line.expect(true.B)
        dut.io.ddrRequest.bits.last.expect(false.B)
        dut.io.mmioRequest.valid.expect(false.B)
        dut.io.linuxExit.valid.expect(false.B)
        dut.io.testFinisher.valid.expect(false.B)
        dut.clock.step()
      }

      var ddrRequestHandshakes = 0
      dut.io.ddrRequest.ready.poke(true.B)
      for (_ <- 0 until 3) {
        if (dut.io.ddrRequest.valid.peek().litToBoolean &&
            dut.io.ddrRequest.ready.peek().litToBoolean)
          ddrRequestHandshakes += 1
        dut.clock.step()
      }
      dut.io.ddrRequest.ready.poke(false.B)
      dut.io.ddrRequest.valid.expect(false.B)
      assert(ddrRequestHandshakes == 1)

      dut.io.ddrResponse.valid.poke(true.B)
      dut.io.ddrResponse.bits.id.poke(0x80000021L.U)
      dut.io.ddrResponse.bits.rdata.poke(lineResponse.U)
      dut.io.ddrResponse.bits.fault.poke(LinxMemFault.NoFault)
      dut.io.ddrResponse.bits.last.poke(false.B)
      dut.io.ddrResponse.ready.expect(true.B)
      dut.clock.step()
      dut.io.ddrResponse.valid.poke(false.B)
      dut.io.ddrResponse.bits.id.poke(0x77.U)
      dut.io.ddrResponse.bits.rdata.poke(0x55.U)
      dut.io.ddrResponse.bits.fault.poke(LinxMemFault.Protocol)
      dut.io.ddrResponse.bits.last.poke(true.B)
      consumeMatchingResponseExactlyOnce(
        dut,
        id = 0x80000021L,
        rdata = lineResponse,
        fault = LinxMemFault.NoFault,
        last = false)

      def checkDdrBoundary(id: Long, addr: BigInt): Unit = {
        pokeRequest(dut, id = id, addr = addr, write = false, size = 0)
        acceptRequest(dut)
        dut.io.ddrRequest.valid.expect(true.B)
        dut.io.ddrRequest.bits.addr.expect(addr.U)
        dut.io.mmioRequest.valid.expect(false.B)
        dut.io.ddrRequest.ready.poke(true.B)
        dut.clock.step()
        dut.io.ddrRequest.ready.poke(false.B)
        dut.io.ddrResponse.valid.poke(true.B)
        dut.io.ddrResponse.bits.id.poke(id.U)
        dut.io.ddrResponse.bits.rdata.poke(0.U)
        dut.io.ddrResponse.bits.fault.poke(LinxMemFault.NoFault)
        dut.io.ddrResponse.bits.last.poke(true.B)
        dut.clock.step()
        dut.io.ddrResponse.valid.poke(false.B)
        consumeMatchingResponseExactlyOnce(dut, id, 0, LinxMemFault.NoFault)
      }

      checkDdrBoundary(0x41, ZyboZ720Generated.LinxMemoryBase)
      checkDdrBoundary(
        0x42,
        ZyboZ720Generated.LinxMemoryBase + ZyboZ720Generated.LinxMemorySize - 1)
    }
  }

  test("MMIO requests retain payload and distinguish UART TX RX and status") {
    simulate(new LinxMmioRouter) { dut =>
      init(dut)
      pokeRequest(
        dut,
        id = 9,
        addr = ZyboZ720Generated.UartData,
        write = true,
        size = 0,
        wdata = 0xa5,
        wstrb = 1,
        last = false,
        source = LinxMemSource.Store)
      acceptRequest(dut)

      pokeRequest(
        dut,
        id = 0x90000009L,
        addr = BigInt("00102000", 16),
        write = false,
        size = 6,
        wdata = BigInt("1122334455667788", 16),
        wstrb = 0,
        line = true,
        last = true,
        source = LinxMemSource.Device)
      for (_ <- 0 until 3) {
        dut.io.request.ready.expect(false.B)
        dut.io.mmioRequest.valid.expect(true.B)
        dut.io.mmioRequest.bits.id.expect(9.U)
        dut.io.mmioRequest.bits.source.expect(LinxMemSource.Store)
        dut.io.mmioRequest.bits.target.expect(LinxMmioKind.UartTx)
        dut.io.mmioRequest.bits.addr.expect(ZyboZ720Generated.UartData.U)
        dut.io.mmioRequest.bits.write.expect(true.B)
        dut.io.mmioRequest.bits.size.expect(0.U)
        dut.io.mmioRequest.bits.wdata.expect(0xa5.U)
        dut.io.mmioRequest.bits.wstrb.expect(1.U)
        dut.io.mmioRequest.bits.last.expect(false.B)
        dut.io.ddrRequest.valid.expect(false.B)
        dut.io.linuxExit.valid.expect(false.B)
        dut.io.testFinisher.valid.expect(false.B)
        dut.clock.step()
      }

      var mmioRequestHandshakes = 0
      dut.io.mmioRequest.ready.poke(true.B)
      for (_ <- 0 until 3) {
        if (dut.io.mmioRequest.valid.peek().litToBoolean &&
            dut.io.mmioRequest.ready.peek().litToBoolean)
          mmioRequestHandshakes += 1
        dut.clock.step()
      }
      dut.io.mmioRequest.ready.poke(false.B)
      dut.io.mmioRequest.valid.expect(false.B)
      assert(mmioRequestHandshakes == 1)

      dut.io.mmioResponse.valid.poke(true.B)
      dut.io.mmioResponse.bits.id.poke(9.U)
      dut.io.mmioResponse.bits.rdata.poke(0x55.U)
      dut.io.mmioResponse.bits.fault.poke(LinxMemFault.Bus)
      dut.io.mmioResponse.bits.last.poke(false.B)
      dut.io.mmioResponse.ready.expect(true.B)
      dut.clock.step()
      dut.io.mmioResponse.valid.poke(false.B)
      dut.io.mmioResponse.bits.id.poke(0x66.U)
      dut.io.mmioResponse.bits.rdata.poke(0xaa.U)
      dut.io.mmioResponse.bits.fault.poke(LinxMemFault.Access)
      dut.io.mmioResponse.bits.last.poke(true.B)
      consumeMatchingResponseExactlyOnce(
        dut, 9, 0x55, LinxMemFault.Bus, last = false)

      def checkRead(
          id: Long,
          addr: BigInt,
          size: Int,
          target: LinxMmioKind.Type): Unit = {
        pokeRequest(dut, id = id, addr = addr, write = false, size = size)
        acceptRequest(dut)
        dut.io.request.ready.expect(false.B)
        dut.io.mmioRequest.valid.expect(true.B)
        dut.io.mmioRequest.bits.target.expect(target)
        dut.io.mmioRequest.bits.wstrb.expect(0.U)
        dut.io.ddrRequest.valid.expect(false.B)
        dut.io.linuxExit.valid.expect(false.B)
        dut.io.testFinisher.valid.expect(false.B)
        dut.io.mmioRequest.ready.poke(true.B)
        dut.clock.step()
        dut.io.mmioRequest.ready.poke(false.B)
        dut.io.mmioResponse.valid.poke(true.B)
        dut.io.mmioResponse.bits.id.poke(id.U)
        dut.io.mmioResponse.bits.rdata.poke((0x40L + id).U)
        dut.io.mmioResponse.bits.fault.poke(LinxMemFault.NoFault)
        dut.io.mmioResponse.bits.last.poke(true.B)
        dut.clock.step()
        dut.io.mmioResponse.valid.poke(false.B)
        consumeMatchingResponseExactlyOnce(
          dut, id, 0x40L + id, LinxMemFault.NoFault)
      }

      checkRead(4, ZyboZ720Generated.UartData, 0, LinxMmioKind.UartRx)
      checkRead(5, ZyboZ720Generated.UartStatusLinuxExit, 2, LinxMmioKind.UartStatus)
      checkRead(6, ZyboZ720Generated.VirtioBase, 3, LinxMmioKind.Virtio)
      checkRead(7, BigInt("300017ff", 16), 0, LinxMmioKind.Virtio)

      pokeRequest(
        dut,
        id = 8,
        addr = BigInt("30001800", 16),
        write = false,
        size = 0)
      acceptRequest(dut)
      pokeRequest(
        dut,
        id = 0x80000008L,
        addr = ZyboZ720Generated.VirtioBase,
        write = true,
        size = 3,
        wdata = BigInt("8877665544332211", 16),
        wstrb = 0xff,
        line = true,
        last = false,
        source = LinxMemSource.Device)
      dut.io.request.ready.expect(false.B)
      dut.io.ddrRequest.valid.expect(false.B)
      dut.io.mmioRequest.valid.expect(false.B)
      dut.io.linuxExit.valid.expect(false.B)
      dut.io.testFinisher.valid.expect(false.B)
      consumeMatchingResponseExactlyOnce(dut, 8, 0, LinxMemFault.Decode)
    }
  }

  test("local decode and access faults are retained and never reach a destination") {
    simulate(new LinxMmioRouter) { dut =>
      init(dut)
      pokeRequest(
        dut,
        id = 3,
        addr = BigInt("20000000", 16),
        write = false,
        size = 3,
        last = false)
      acceptRequest(dut)

      dut.io.ddrRequest.valid.expect(false.B)
      dut.io.mmioRequest.valid.expect(false.B)
      dut.io.linuxExit.valid.expect(false.B)
      dut.io.testFinisher.valid.expect(false.B)
      expectResponse(dut, 3, 0, LinxMemFault.Decode, last = false)
      dut.clock.step(3)
      expectResponse(dut, 3, 0, LinxMemFault.Decode, last = false)
      consumeMatchingResponseExactlyOnce(
        dut, 3, 0, LinxMemFault.Decode, last = false)

      val accessCases = Seq(
        (ZyboZ720Generated.UartData, false, 6, BigInt(0), true),
        (ZyboZ720Generated.UartData, false, 2, BigInt(0), false),
        (ZyboZ720Generated.VirtioBase + 2, false, 2, BigInt(0), false),
        (ZyboZ720Generated.UartStatusLinuxExit, false, 2, BigInt(0xf), false),
        (ZyboZ720Generated.UartStatusLinuxExit, true, 2, BigInt(0x3), false),
        (ZyboZ720Generated.TestFinisher, true, 0, BigInt(0x1), false))

      for (((addr, write, size, wstrb, line), index) <- accessCases.zipWithIndex) {
        pokeRequest(
          dut,
          id = 0x12 + index,
          addr = addr,
          write = write,
          size = size,
          wdata = 0x89abcdefL,
          wstrb = wstrb,
          line = line)
        acceptRequest(dut)
        dut.io.ddrRequest.valid.expect(false.B)
        dut.io.mmioRequest.valid.expect(false.B)
        dut.io.linuxExit.valid.expect(false.B)
        dut.io.testFinisher.valid.expect(false.B)
        consumeMatchingResponseExactlyOnce(
          dut, 0x12 + index, 0, LinxMemFault.Access)
      }

      pokeRequest(
        dut,
        id = 0x13,
        addr = ZyboZ720Generated.TestFinisher,
        write = false,
        size = 2)
      acceptRequest(dut)
      expectResponse(dut, 0x13, 0, LinxMemFault.Decode)
      dut.io.ddrRequest.valid.expect(false.B)
      dut.io.mmioRequest.valid.expect(false.B)
      dut.io.testFinisher.valid.expect(false.B)
      consumeMatchingResponseExactlyOnce(dut, 0x13, 0, LinxMemFault.Decode)
    }
  }

  test("exit and finisher events retain independent payloads and fire once") {
    simulate(new LinxMmioRouter) { dut =>
      init(dut)
      pokeRequest(
        dut,
        id = 0x31,
        addr = ZyboZ720Generated.UartStatusLinuxExit,
        write = true,
        size = 2,
        wdata = BigInt("deadbeef", 16) << 32,
        wstrb = 0xf0,
        last = false)
      acceptRequest(dut)

      pokeRequest(
        dut,
        id = 0x80000031L,
        addr = BigInt("00102000", 16),
        write = false,
        size = 6,
        wdata = BigInt("0123456789abcdef", 16),
        wstrb = 0,
        line = true,
        last = true,
        source = LinxMemSource.Device)
      for (_ <- 0 until 5) {
        dut.io.request.ready.expect(false.B)
        dut.io.linuxExit.valid.expect(true.B)
        dut.io.linuxExit.bits.id.expect(0x31.U)
        dut.io.linuxExit.bits.payload.expect(BigInt("deadbeef", 16).U)
        dut.io.linuxExit.bits.last.expect(false.B)
        dut.io.ddrRequest.valid.expect(false.B)
        dut.io.mmioRequest.valid.expect(false.B)
        dut.io.testFinisher.valid.expect(false.B)
        dut.io.response.valid.expect(false.B)
        dut.clock.step()
      }

      var linuxExitHandshakes = 0
      dut.io.linuxExit.ready.poke(true.B)
      for (_ <- 0 until 3) {
        if (dut.io.linuxExit.valid.peek().litToBoolean &&
            dut.io.linuxExit.ready.peek().litToBoolean)
          linuxExitHandshakes += 1
        dut.clock.step()
      }
      dut.io.linuxExit.ready.poke(false.B)
      dut.io.linuxExit.valid.expect(false.B)
      assert(linuxExitHandshakes == 1)
      dut.io.linuxExit.valid.expect(false.B)
      dut.io.testFinisher.valid.expect(false.B)
      consumeMatchingResponseExactlyOnce(
        dut, 0x31, 0, LinxMemFault.NoFault, last = false)

      pokeRequest(
        dut,
        id = 0x32,
        addr = ZyboZ720Generated.TestFinisher,
        write = true,
        size = 2,
        wdata = BigInt("1234abcd", 16),
        wstrb = 0xf)
      acceptRequest(dut)

      pokeRequest(
        dut,
        id = 0x80000032L,
        addr = ZyboZ720Generated.UartData,
        write = false,
        size = 0,
        wdata = BigInt("fedcba9876543210", 16),
        wstrb = 0,
        line = true,
        last = false,
        source = LinxMemSource.Device)
      for (_ <- 0 until 4) {
        dut.io.request.ready.expect(false.B)
        dut.io.testFinisher.valid.expect(true.B)
        dut.io.testFinisher.bits.id.expect(0x32.U)
        dut.io.testFinisher.bits.payload.expect(BigInt("1234abcd", 16).U)
        dut.io.testFinisher.bits.last.expect(true.B)
        dut.io.ddrRequest.valid.expect(false.B)
        dut.io.mmioRequest.valid.expect(false.B)
        dut.io.linuxExit.valid.expect(false.B)
        dut.io.response.valid.expect(false.B)
        dut.clock.step()
      }
      var testFinisherHandshakes = 0
      dut.io.testFinisher.ready.poke(true.B)
      for (_ <- 0 until 3) {
        if (dut.io.testFinisher.valid.peek().litToBoolean &&
            dut.io.testFinisher.ready.peek().litToBoolean)
          testFinisherHandshakes += 1
        dut.clock.step()
      }
      dut.io.testFinisher.ready.poke(false.B)
      dut.io.testFinisher.valid.expect(false.B)
      assert(testFinisherHandshakes == 1)
      consumeMatchingResponseExactlyOnce(
        dut, 0x32, 0, LinxMemFault.NoFault)
    }
  }
}
