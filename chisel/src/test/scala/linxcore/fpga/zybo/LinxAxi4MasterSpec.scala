package linxcore.fpga.zybo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

class LinxAxi4MasterSpec extends AnyFunSuite with ChiselSim {
  private val Okay = 0
  private val ExOkay = 1
  private val SlvErr = 2
  private val DecErr = 3

  private def init(dut: LinxAxi4Master): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.response.ready.poke(false.B)

    dut.io.axi.ar.ready.poke(false.B)
    dut.io.axi.r.valid.poke(false.B)
    dut.io.axi.r.bits.poke(0.U.asTypeOf(dut.io.axi.r.bits))
    dut.io.axi.aw.ready.poke(false.B)
    dut.io.axi.w.ready.poke(false.B)
    dut.io.axi.b.valid.poke(false.B)
    dut.io.axi.b.bits.poke(0.U.asTypeOf(dut.io.axi.b.bits))
  }

  private def pokeRequest(
      dut: LinxAxi4Master,
      id: BigInt,
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

  private def acceptRequest(dut: LinxAxi4Master): Unit = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
    dut.io.request.ready.expect(false.B)
  }

  private def expectAr(
      dut: LinxAxi4Master,
      addr: BigInt,
      len: Int,
      size: Int): Unit = {
    dut.io.axi.ar.valid.expect(true.B)
    dut.io.axi.ar.bits.id.expect(0.U)
    dut.io.axi.ar.bits.addr.expect(addr.U)
    dut.io.axi.ar.bits.len.expect(len.U)
    dut.io.axi.ar.bits.size.expect(size.U)
    dut.io.axi.ar.bits.burst.expect(1.U)
  }

  private def handshakeAr(
      dut: LinxAxi4Master,
      addr: BigInt,
      len: Int,
      size: Int,
      random: Random,
      minimumStalls: Int = 0): Int = {
    var handshakes = 0
    for (_ <- 0 until minimumStalls) {
      dut.io.axi.ar.ready.poke(false.B)
      expectAr(dut, addr, len, size)
      dut.clock.step()
    }
    var cycles = 0
    while (handshakes == 0 && cycles < 16) {
      expectAr(dut, addr, len, size)
      val ready = random.nextBoolean()
      dut.io.axi.ar.ready.poke(ready.B)
      if (ready) handshakes += 1
      dut.clock.step()
      cycles += 1
    }
    if (handshakes == 0) {
      expectAr(dut, addr, len, size)
      dut.io.axi.ar.ready.poke(true.B)
      handshakes += 1
      dut.clock.step()
    }
    for (_ <- 0 until 2) {
      if (dut.io.axi.ar.valid.peek().litToBoolean &&
          dut.io.axi.ar.ready.peek().litToBoolean) handshakes += 1
      dut.clock.step()
    }
    dut.io.axi.ar.ready.poke(false.B)
    assert(handshakes == 1)
    handshakes
  }

  private def sendReadBeat(
      dut: LinxAxi4Master,
      data: BigInt,
      resp: Int,
      last: Boolean,
      id: Int,
      random: Random,
      maximumRandomStalls: Int = 4): Int = {
    val stalls = random.nextInt(maximumRandomStalls + 1)
    for (_ <- 0 until stalls) {
      dut.io.axi.r.valid.poke(false.B)
      dut.io.axi.r.ready.expect(true.B)
      dut.clock.step()
    }
    dut.io.axi.r.bits.id.poke(id.U)
    dut.io.axi.r.bits.data.poke(data.U)
    dut.io.axi.r.bits.resp.poke(resp.U)
    dut.io.axi.r.bits.last.poke(last.B)
    dut.io.axi.r.valid.poke(true.B)
    dut.io.axi.r.ready.expect(true.B)
    val handshakes =
      if (dut.io.axi.r.valid.peek().litToBoolean &&
          dut.io.axi.r.ready.peek().litToBoolean) 1 else 0
    dut.clock.step()
    dut.io.axi.r.valid.poke(false.B)
    dut.io.axi.r.bits.id.poke((id ^ 1).U)
    dut.io.axi.r.bits.data.poke(
      (data ^ BigInt("ffffffffffffffff", 16)).U)
    dut.io.axi.r.bits.resp.poke(((resp + 1) & 3).U)
    dut.io.axi.r.bits.last.poke((!last).B)
    assert(handshakes == 1)
    handshakes
  }

  private def expectAw(dut: LinxAxi4Master, addr: BigInt, size: Int): Unit = {
    dut.io.axi.aw.valid.expect(true.B)
    dut.io.axi.aw.bits.id.expect(0.U)
    dut.io.axi.aw.bits.addr.expect(addr.U)
    dut.io.axi.aw.bits.len.expect(0.U)
    dut.io.axi.aw.bits.size.expect(size.U)
    dut.io.axi.aw.bits.burst.expect(1.U)
  }

  private def handshakeAw(
      dut: LinxAxi4Master,
      addr: BigInt,
      size: Int,
      random: Random,
      minimumStalls: Int = 0): Int = {
    var handshakes = 0
    for (_ <- 0 until minimumStalls) {
      dut.io.axi.aw.ready.poke(false.B)
      expectAw(dut, addr, size)
      dut.clock.step()
    }
    while (handshakes == 0) {
      expectAw(dut, addr, size)
      val ready = random.nextBoolean()
      dut.io.axi.aw.ready.poke(ready.B)
      if (ready) handshakes += 1
      dut.clock.step()
    }
    for (_ <- 0 until 2) {
      if (dut.io.axi.aw.valid.peek().litToBoolean &&
          dut.io.axi.aw.ready.peek().litToBoolean) handshakes += 1
      dut.clock.step()
    }
    dut.io.axi.aw.ready.poke(false.B)
    assert(handshakes == 1)
    handshakes
  }

  private def expectW(
      dut: LinxAxi4Master,
      data: BigInt,
      strb: BigInt): Unit = {
    dut.io.axi.w.valid.expect(true.B)
    dut.io.axi.w.bits.data.expect(data.U)
    dut.io.axi.w.bits.strb.expect(strb.U)
    dut.io.axi.w.bits.last.expect(true.B)
  }

  private def handshakeW(
      dut: LinxAxi4Master,
      data: BigInt,
      strb: BigInt,
      random: Random,
      minimumStalls: Int = 0): Int = {
    var handshakes = 0
    for (_ <- 0 until minimumStalls) {
      dut.io.axi.w.ready.poke(false.B)
      expectW(dut, data, strb)
      dut.clock.step()
    }
    while (handshakes == 0) {
      expectW(dut, data, strb)
      val ready = random.nextBoolean()
      dut.io.axi.w.ready.poke(ready.B)
      if (ready) handshakes += 1
      dut.clock.step()
    }
    for (_ <- 0 until 2) {
      if (dut.io.axi.w.valid.peek().litToBoolean &&
          dut.io.axi.w.ready.peek().litToBoolean) handshakes += 1
      dut.clock.step()
    }
    dut.io.axi.w.ready.poke(false.B)
    assert(handshakes == 1)
    handshakes
  }

  private def sendWriteResponse(
      dut: LinxAxi4Master,
      resp: Int,
      id: Int,
      random: Random): Int = {
    val stalls = random.nextInt(5)
    for (_ <- 0 until stalls) {
      dut.io.axi.b.valid.poke(false.B)
      dut.io.axi.b.ready.expect(true.B)
      dut.clock.step()
    }
    dut.io.axi.b.bits.id.poke(id.U)
    dut.io.axi.b.bits.resp.poke(resp.U)
    dut.io.axi.b.valid.poke(true.B)
    dut.io.axi.b.ready.expect(true.B)
    var handshakes = 0
    if (dut.io.axi.b.valid.peek().litToBoolean &&
        dut.io.axi.b.ready.peek().litToBoolean) handshakes += 1
    dut.clock.step()
    dut.io.axi.b.bits.id.poke((id ^ 1).U)
    dut.io.axi.b.bits.resp.poke(((resp + 1) & 3).U)
    for (_ <- 0 until 3) {
      dut.io.axi.b.valid.expect(true.B)
      dut.io.axi.b.ready.expect(false.B)
      dut.io.response.ready.expect(false.B)
      if (dut.io.axi.b.valid.peek().litToBoolean &&
          dut.io.axi.b.ready.peek().litToBoolean) handshakes += 1
      dut.clock.step()
    }
    dut.io.axi.b.valid.poke(false.B)
    assert(handshakes == 1)
    handshakes
  }

  private def consumeResponse(
      dut: LinxAxi4Master,
      id: BigInt,
      data: BigInt,
      fault: LinxMemFault.Type,
      last: Boolean,
      random: Random,
      minimumStalls: Int = 0): Int = {
    var handshakes = 0
    def check(): Unit = {
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.id.expect(id.U)
      dut.io.response.bits.rdata.expect(data.U)
      dut.io.response.bits.fault.expect(fault)
      dut.io.response.bits.last.expect(last.B)
      dut.io.request.ready.expect(false.B)
    }

    for (_ <- 0 until minimumStalls) {
      dut.io.response.ready.poke(false.B)
      check()
      dut.clock.step()
    }
    var cycles = 0
    while (handshakes == 0 && cycles < 16) {
      check()
      val ready = random.nextBoolean()
      dut.io.response.ready.poke(ready.B)
      if (ready) handshakes += 1
      dut.clock.step()
      cycles += 1
    }
    if (handshakes == 0) {
      check()
      dut.io.response.ready.poke(true.B)
      handshakes += 1
      dut.clock.step()
    }
    for (_ <- 0 until 2) {
      if (dut.io.response.valid.peek().litToBoolean &&
          dut.io.response.ready.peek().litToBoolean) handshakes += 1
      dut.clock.step()
    }
    dut.io.response.ready.poke(false.B)
    dut.io.response.valid.expect(false.B)
    dut.io.request.ready.expect(true.B)
    assert(handshakes == 1)
    handshakes
  }

  private def expectNoAxiIssue(dut: LinxAxi4Master): Unit = {
    dut.io.axi.ar.valid.expect(false.B)
    dut.io.axi.aw.valid.expect(false.B)
    dut.io.axi.w.valid.expect(false.B)
    dut.io.axi.r.ready.expect(false.B)
    dut.io.axi.b.ready.expect(false.B)
  }

  test("64-byte line maps to eight 64-bit beats") {
    val burst = LinxAxi4Reference.readBurst(BigInt("00102000", 16), 64)
    assert(burst.len == 7)
    assert(burst.size == 3)
    assert(burst.addresses ==
      (0 until 8).map(i => BigInt("00102000", 16) + i * 8))
  }

  test("burst crossing 4 KiB is rejected") {
    assertThrows[IllegalArgumentException] {
      LinxAxi4Reference.readBurst(BigInt("00100fe0", 16), 64)
    }
  }

  test("reference preserves scalar geometry and accepts exact RAM endpoints") {
    val one = LinxAxi4Reference.readBurst(BigInt("00000000", 16), 1)
    val two = LinxAxi4Reference.readBurst(BigInt("00000002", 16), 2)
    val four = LinxAxi4Reference.readBurst(BigInt("0ffffffc", 16), 4)
    val eight = LinxAxi4Reference.readBurst(BigInt("0ffffff8", 16), 8)
    val line = LinxAxi4Reference.readBurst(BigInt("0fffffc0", 16), 64)

    assert((one.len, one.size, one.burst, one.addresses) ==
      (0, 0, 1, Seq(BigInt("00000000", 16))))
    assert((two.len, two.size, two.burst, two.addresses) ==
      (0, 1, 1, Seq(BigInt("00000002", 16))))
    assert((four.len, four.size, four.burst, four.addresses) ==
      (0, 2, 1, Seq(BigInt("0ffffffc", 16))))
    assert((eight.len, eight.size, eight.burst, eight.addresses) ==
      (0, 3, 1, Seq(BigInt("0ffffff8", 16))))
    assert(line.addresses.head == BigInt("0fffffc0", 16))
    assert(line.addresses.last == BigInt("0ffffff8", 16))
    assertThrows[IllegalArgumentException] {
      LinxAxi4Reference.readBurst(BigInt("0ffffff9", 16), 8)
    }
    assertThrows[IllegalArgumentException] {
      LinxAxi4Reference.readBurst(BigInt("10000000", 16), 1)
    }
  }

  test("line read survives independent randomized stalls and assembles low-to-high beats") {
    simulate(new LinxAxi4Master) { dut =>
      init(dut)
      val random = new Random(0x5a17c0deL)
      val id = BigInt("80000021", 16)
      val addr = BigInt("00102000", 16)
      val beats = Seq(
        BigInt("0102030405060708", 16), BigInt("1112131415161718", 16),
        BigInt("2122232425262728", 16), BigInt("3132333435363738", 16),
        BigInt("4142434445464748", 16), BigInt("5152535455565758", 16),
        BigInt("6162636465666768", 16), BigInt("8182838485868788", 16))
      val expected = BigInt(
        "8182838485868788616263646566676851525354555657584142434445464748" +
          "3132333435363738212223242526272811121314151617180102030405060708",
        16)

      pokeRequest(
        dut, id, addr, write = false, size = 6, line = true, last = false,
        source = LinxMemSource.Instruction)
      acceptRequest(dut)
      pokeRequest(
        dut, 7, BigInt("00000008", 16), write = true, size = 3,
        wdata = BigInt("deadbeefdeadbeef", 16), wstrb = 0xff,
        source = LinxMemSource.Store)

      val arCount = handshakeAr(dut, addr, len = 7, size = 3, random, minimumStalls = 7)
      var rCount = 0
      for ((beat, index) <- beats.zipWithIndex)
        rCount += sendReadBeat(
          dut, beat, Okay, last = index == 7, id = 0, random = random)
      val responseCount = consumeResponse(
        dut, id, expected, LinxMemFault.NoFault, last = false, random,
        minimumStalls = 7)

      assert((arCount, rCount, responseCount) == (1, 8, 1))
      assert(expected.testBit(511))
    }
  }

  test("write address and data channels make independent progress in either order") {
    simulate(new LinxAxi4Master) { dut =>
      init(dut)
      val random = new Random(0x4a571defL)

      def runCase(id: Int, order: String): Unit = {
        val addr = BigInt("00030000", 16) + id * 8
        val data = BigInt("cafe000000000000", 16) | id
        pokeRequest(
          dut, id, addr, write = true, size = 3, wdata = data, wstrb = 0xff,
          last = false, source = LinxMemSource.Store)
        acceptRequest(dut)
        pokeRequest(
          dut, BigInt("80000000", 16) | id, BigInt("0ffffff8", 16),
          write = false, size = 0, wdata = BigInt("0123456789abcdef", 16),
          wstrb = 0, line = true, last = true,
          source = LinxMemSource.Instruction)

        var awHandshakes = 0
        var wHandshakes = 0
        def sample(): Unit = {
          if (dut.io.axi.aw.valid.peek().litToBoolean &&
              dut.io.axi.aw.ready.peek().litToBoolean) awHandshakes += 1
          if (dut.io.axi.w.valid.peek().litToBoolean &&
              dut.io.axi.w.ready.peek().litToBoolean) wHandshakes += 1
        }

        for (_ <- 0 until 4) {
          dut.io.axi.aw.ready.poke(false.B)
          dut.io.axi.w.ready.poke(false.B)
          expectAw(dut, addr, 3)
          expectW(dut, data, 0xff)
          sample()
          dut.clock.step()
        }

        order match {
          case "w-first" =>
            dut.io.axi.aw.ready.poke(false.B)
            dut.io.axi.w.ready.poke(true.B)
            expectAw(dut, addr, 3)
            expectW(dut, data, 0xff)
            sample()
            dut.clock.step()
            for (_ <- 0 until 3) {
              dut.io.axi.aw.ready.poke(false.B)
              dut.io.axi.w.ready.poke(true.B)
              expectAw(dut, addr, 3)
              dut.io.axi.w.valid.expect(false.B)
              sample()
              dut.clock.step()
            }
            dut.io.axi.aw.ready.poke(true.B)
            expectAw(dut, addr, 3)
            dut.io.axi.w.valid.expect(false.B)
            sample()
            dut.clock.step()
          case "aw-first" =>
            dut.io.axi.aw.ready.poke(true.B)
            dut.io.axi.w.ready.poke(false.B)
            expectAw(dut, addr, 3)
            expectW(dut, data, 0xff)
            sample()
            dut.clock.step()
            for (_ <- 0 until 3) {
              dut.io.axi.aw.ready.poke(true.B)
              dut.io.axi.w.ready.poke(false.B)
              dut.io.axi.aw.valid.expect(false.B)
              expectW(dut, data, 0xff)
              sample()
              dut.clock.step()
            }
            dut.io.axi.w.ready.poke(true.B)
            dut.io.axi.aw.valid.expect(false.B)
            expectW(dut, data, 0xff)
            sample()
            dut.clock.step()
          case "simultaneous" =>
            dut.io.axi.aw.ready.poke(true.B)
            dut.io.axi.w.ready.poke(true.B)
            expectAw(dut, addr, 3)
            expectW(dut, data, 0xff)
            sample()
            dut.clock.step()
          case _ => fail(s"unknown write order: $order")
        }

        dut.io.axi.aw.ready.poke(true.B)
        dut.io.axi.w.ready.poke(true.B)
        dut.io.axi.aw.valid.expect(false.B)
        dut.io.axi.w.valid.expect(false.B)
        dut.io.axi.b.ready.expect(true.B)
        for (_ <- 0 until 2) {
          sample()
          dut.clock.step()
        }
        assert((awHandshakes, wHandshakes) == (1, 1))
        dut.io.axi.aw.ready.poke(false.B)
        dut.io.axi.w.ready.poke(false.B)

        assert(sendWriteResponse(dut, Okay, id = 0, random) == 1)
        consumeResponse(
          dut, id, 0, LinxMemFault.NoFault, last = false, random,
          minimumStalls = 2)
      }

      runCase(61, "w-first")
      runCase(62, "aw-first")
      runCase(63, "simultaneous")
    }
  }

  test("scalar reads and writes cover exact RAM endpoints and every legal lane") {
    simulate(new LinxAxi4Master) { dut =>
      init(dut)
      val random = new Random(0x13579bdfL)

      val readCases = Seq(
        (BigInt("80000040", 16), BigInt("00000000", 16), 0,
          BigInt("000000000000005a", 16)),
        (BigInt("80000041", 16), BigInt("0fffffff", 16), 0,
          BigInt("a500000000000000", 16)),
        (BigInt("80000042", 16), BigInt("0ffffffe", 16), 1,
          BigInt("b6c7000000000000", 16)),
        (BigInt("80000043", 16), BigInt("0ffffffc", 16), 2,
          BigInt("d8e9fa0b00000000", 16)),
        (BigInt("80000044", 16), BigInt("0ffffff8", 16), 3,
          BigInt("1122334455667788", 16)))

      for ((id, addr, size, data) <- readCases) {
        pokeRequest(
          dut, id, addr, write = false, size = size,
          last = id != BigInt("80000042", 16))
        acceptRequest(dut)
        val arCount = handshakeAr(
          dut, addr, len = 0, size = size, random, minimumStalls = 2)
        val rCount = sendReadBeat(
          dut, data, Okay, last = true, id = 0, random)
        val responseCount = consumeResponse(
          dut, id, data, LinxMemFault.NoFault,
          last = id != BigInt("80000042", 16), random, minimumStalls = 2)
        assert((arCount, rCount, responseCount) == (1, 1, 1))
      }

      val writeCases = Seq(
        (BigInt("f0000050", 16), BigInt("00000000", 16), 0,
          BigInt("000000000000005a", 16), BigInt("01", 16)),
        (BigInt("f0000051", 16), BigInt("0fffffff", 16), 0,
          BigInt("a500000000000000", 16), BigInt("80", 16)),
        (BigInt("f0000052", 16), BigInt("0ffffffe", 16), 1,
          BigInt("b6c7000000000000", 16), BigInt("c0", 16)),
        (BigInt("f0000053", 16), BigInt("0ffffffc", 16), 2,
          BigInt("d8e9fa0b00000000", 16), BigInt("f0", 16)),
        (BigInt("f0000054", 16), BigInt("0ffffff8", 16), 3,
          BigInt("1122334455667788", 16), BigInt("ff", 16)))

      for ((id, addr, size, data, strobe) <- writeCases) {
        pokeRequest(
          dut, id, addr, write = true, size = size,
          wdata = data, wstrb = strobe, last = id != BigInt("f0000052", 16),
          source = LinxMemSource.Store)
        acceptRequest(dut)
        pokeRequest(
          dut, BigInt("00000009", 16), BigInt("00001000", 16),
          write = false, size = 6, wdata = BigInt("deadbeefdeadbeef", 16),
          wstrb = 0, line = true, last = true,
          source = LinxMemSource.Instruction)
        val awCount = handshakeAw(
          dut, addr, size = size, random, minimumStalls = 2)
        val wCount = handshakeW(
          dut, data, strobe, random, minimumStalls = 2)
        val bCount = sendWriteResponse(dut, Okay, id = 0, random)
        val responseCount = consumeResponse(
          dut, id, 0, LinxMemFault.NoFault,
          last = id != BigInt("f0000052", 16), random, minimumStalls = 2)
        assert((awCount, wCount, bCount, responseCount) == (1, 1, 1, 1))
      }
    }
  }

  test("read response errors drain once and retain Bus or Protocol priority") {
    simulate(new LinxAxi4Master) { dut =>
      init(dut)
      val random = new Random(0x2468ace0L)

      def scalarRead(id: Int, resp: Int, axiId: Int, fault: LinxMemFault.Type): Unit = {
        val addr = BigInt("00002000", 16) + id * 8
        pokeRequest(dut, id, addr, write = false, size = 3)
        acceptRequest(dut)
        handshakeAr(dut, addr, 0, 3, random)
        sendReadBeat(dut, BigInt(id), resp, last = true, id = axiId, random)
        consumeResponse(dut, id, BigInt(id), fault, last = true, random)
      }

      def lineRead(
          id: Int,
          responses: Seq[(Int, Int)],
          fault: LinxMemFault.Type): Unit = {
        val addr = BigInt("00020000", 16) + id * 64
        pokeRequest(dut, id, addr, write = false, size = 6, line = true)
        acceptRequest(dut)
        handshakeAr(dut, addr, 7, 3, random)
        for (index <- 0 until 8) {
          val (resp, axiId) = responses(index)
          sendReadBeat(
            dut, 0, resp, last = index == 7, id = axiId, random)
        }
        consumeResponse(dut, id, 0, fault, last = true, random)
      }

      scalarRead(1, SlvErr, 0, LinxMemFault.Bus)
      scalarRead(2, DecErr, 0, LinxMemFault.Bus)
      scalarRead(3, ExOkay, 0, LinxMemFault.Bus)
      scalarRead(4, Okay, 1, LinxMemFault.Protocol)
      scalarRead(5, DecErr, 1, LinxMemFault.Protocol)
      lineRead(
        90,
        Seq(
          (Okay, 0), (Okay, 0), (SlvErr, 0), (Okay, 0),
          (Okay, 0), (DecErr, 0), (Okay, 0), (Okay, 0)),
        LinxMemFault.Bus)
      lineRead(
        91,
        Seq(
          (Okay, 0), (DecErr, 0), (Okay, 1), (Okay, 0),
          (Okay, 0), (Okay, 0), (Okay, 0), (Okay, 0)),
        LinxMemFault.Protocol)
    }
  }

  test("early or missing RLAST terminates once and rejects a late overflow beat") {
    simulate(new LinxAxi4Master) { dut =>
      init(dut)
      val random = new Random(0x10203040L)

      pokeRequest(
        dut, 11, BigInt("00004000", 16), write = false, size = 6,
        line = true)
      acceptRequest(dut)
      handshakeAr(dut, BigInt("00004000", 16), 7, 3, random)
      sendReadBeat(dut, 0x10, Okay, last = false, id = 0, random)
      sendReadBeat(dut, 0x20, Okay, last = false, id = 0, random)
      sendReadBeat(dut, 0x30, Okay, last = true, id = 0, random)
      dut.io.axi.r.valid.poke(true.B)
      dut.io.axi.r.bits.last.poke(true.B)
      dut.io.axi.r.ready.expect(false.B)
      val earlyData = BigInt(
        "0000000000000000000000000000000000000000000000000000000000000000" +
          "0000000000000000000000000000003000000000000000200000000000000010",
        16)
      consumeResponse(
        dut, 11, earlyData, LinxMemFault.Protocol, last = true, random)
      dut.io.axi.r.valid.poke(false.B)

      pokeRequest(
        dut, 12, BigInt("00008000", 16), write = false, size = 6,
        line = true, last = false)
      acceptRequest(dut)
      handshakeAr(dut, BigInt("00008000", 16), 7, 3, random)
      for (index <- 0 until 8)
        sendReadBeat(dut, index + 1, Okay, last = false, id = 0, random)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.fault.expect(LinxMemFault.Protocol)
      dut.io.axi.r.ready.expect(false.B)
      dut.clock.step(2)

      dut.io.axi.r.valid.poke(true.B)
      dut.io.axi.r.bits.id.poke(1.U)
      dut.io.axi.r.bits.data.poke(BigInt("ffffffffffffffff", 16).U)
      dut.io.axi.r.bits.resp.poke(DecErr.U)
      dut.io.axi.r.bits.last.poke(true.B)
      dut.io.axi.r.ready.expect(false.B)
      val missingLastData = BigInt(
        "0000000000000008000000000000000700000000000000060000000000000005" +
          "0000000000000004000000000000000300000000000000020000000000000001",
        16)
      consumeResponse(
        dut, 12, missingLastData, LinxMemFault.Protocol, last = false, random,
        minimumStalls = 3)
      dut.io.axi.r.valid.poke(false.B)
    }
  }

  test("write responses classify EXOKAY bus errors and BID mismatches") {
    simulate(new LinxAxi4Master) { dut =>
      init(dut)
      val random = new Random(0x55aa77ccL)

      def scalarWrite(id: Int, resp: Int, axiId: Int, fault: LinxMemFault.Type): Unit = {
        val addr = BigInt("00010000", 16) + id * 8
        val data = BigInt("0123456789abcdef", 16) ^ id
        pokeRequest(
          dut, id, addr, write = true, size = 3, wdata = data, wstrb = 0xff,
          source = LinxMemSource.Store)
        acceptRequest(dut)
        handshakeAw(dut, addr, 3, random)
        handshakeW(dut, data, 0xff, random)
        sendWriteResponse(dut, resp, axiId, random)
        consumeResponse(dut, id, 0, fault, last = true, random)
      }

      scalarWrite(21, SlvErr, 0, LinxMemFault.Bus)
      scalarWrite(22, DecErr, 0, LinxMemFault.Bus)
      scalarWrite(23, ExOkay, 0, LinxMemFault.Bus)
      scalarWrite(24, Okay, 1, LinxMemFault.Protocol)
      scalarWrite(25, SlvErr, 1, LinxMemFault.Protocol)
    }
  }

  test("malformed native requests fail locally without issuing AXI") {
    simulate(new LinxAxi4Master) { dut =>
      init(dut)
      val random = new Random(0x0badcafeL)

      def reject(
          id: Int,
          addr: BigInt,
          write: Boolean,
          size: Int,
          wdata: BigInt,
          wstrb: BigInt,
          line: Boolean,
          fault: LinxMemFault.Type): Unit = {
        pokeRequest(dut, id, addr, write, size, wdata, wstrb, line)
        acceptRequest(dut)
        expectNoAxiIssue(dut)
        consumeResponse(dut, id, 0, fault, last = true, random, minimumStalls = 3)
      }

      reject(31, BigInt("00001000", 16), write = false, 4, 0, 0,
        line = false, LinxMemFault.Access)
      reject(32, BigInt("00001002", 16), write = false, 2, 0, 0,
        line = false, LinxMemFault.Access)
      reject(33, BigInt("10000000", 16), write = false, 0, 0, 0,
        line = false, LinxMemFault.Access)
      reject(34, BigInt("0ffffffc", 16), write = false, 3, 0, 0,
        line = false, LinxMemFault.Access)
      reject(35, BigInt("00100fe0", 16), write = false, 6, 0, 0,
        line = true, LinxMemFault.Access)
      reject(36, BigInt("00002000", 16), write = false, 6, 0, 0,
        line = false, LinxMemFault.Access)
      reject(37, BigInt("00002000", 16), write = false, 3, 0, 0,
        line = true, LinxMemFault.Protocol)
      reject(38, BigInt("00002000", 16), write = false, 3, 0, 1,
        line = false, LinxMemFault.Protocol)
      reject(39, BigInt("00002004", 16), write = true, 2,
        BigInt("aabbccdd00000000", 16), 0x0f, line = false,
        LinxMemFault.Protocol)
      reject(40, BigInt("00002000", 16), write = true, 6,
        BigInt("1122334455667788", 16), 0xff, line = true,
        LinxMemFault.Protocol)

      pokeRequest(
        dut, 41, BigInt("0fffffc0", 16), write = false, size = 6,
        line = true)
      acceptRequest(dut)
      handshakeAr(dut, BigInt("0fffffc0", 16), 7, 3, random)
      for (index <- 0 until 8)
        sendReadBeat(dut, 0, Okay, last = index == 7, id = 0, random)
      consumeResponse(dut, 41, 0, LinxMemFault.NoFault, last = true, random)
    }
  }
}
