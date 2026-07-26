package linxcore.top

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class IfuLineMemoryBridgeSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()

  private def clear(dut: IfuLineMemoryBridge): Unit = {
    dut.io.ifuRequest.valid.poke(false.B)
    dut.io.ifuRequest.bits.poke(0.U.asTypeOf(dut.io.ifuRequest.bits))
    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
    dut.io.ifuRefill.ready.poke(false.B)
  }

  private def enqueue(
      dut: IfuLineMemoryBridge,
      transactionId: BigInt,
      packetUid: BigInt,
      fetchSeq: BigInt,
      lineVa: BigInt,
      linePa: BigInt): Unit = {
    dut.io.ifuRequest.bits.poke(0.U.asTypeOf(dut.io.ifuRequest.bits))
    dut.io.ifuRequest.valid.poke(true.B)
    dut.io.ifuRequest.bits.request.pc.poke((lineVa + 6).U)
    dut.io.ifuRequest.bits.request.lineVa.poke(lineVa.U)
    dut.io.ifuRequest.bits.request.transactionId.poke(transactionId.U)
    dut.io.ifuRequest.bits.request.identity.peId.poke(3.U)
    dut.io.ifuRequest.bits.request.identity.threadId.poke(1.U)
    dut.io.ifuRequest.bits.request.identity.fetchPacketUid.poke(packetUid.U)
    dut.io.ifuRequest.bits.request.identity.fetchSeq.poke(fetchSeq.U)
    dut.io.ifuRequest.bits.request.identity.checkpointId.poke(9.U)
    dut.io.ifuRequest.bits.request.identity.epoch.poke(4.U)
    dut.io.ifuRequest.bits.linePa.poke(linePa.U)
    dut.io.ifuRequest.ready.expect(true.B)
    dut.clock.step()
    dut.io.ifuRequest.valid.poke(false.B)
  }

  private def issue(dut: IfuLineMemoryBridge, expectedPa: BigInt): BigInt = {
    dut.io.memoryRequest.valid.expect(true.B)
    dut.io.memoryRequest.bits.linePa.expect(expectedPa.U)
    val tag = dut.io.memoryRequest.bits.tag.peek().litValue
    dut.io.memoryRequest.ready.poke(true.B)
    dut.clock.step()
    dut.io.memoryRequest.ready.poke(false.B)
    tag
  }

  test("reconstructs complete IFU identities from out-of-order tagged responses") {
    simulate(new IfuLineMemoryBridge(p, entries = 4)) { dut =>
      clear(dut)
      enqueue(dut, 0x11, 0x21, 0x31, 0x1000, 0x8000)
      enqueue(dut, 0x12, 0x22, 0x32, 0x1040, 0x9000)
      enqueue(dut, 0x13, 0x23, 0x33, 0x1080, 0xa000)
      dut.io.outstandingCount.expect(3.U)

      val tags = Seq(0x8000, 0x9000, 0xa000).map(pa => issue(dut, pa))
      assert(tags.distinct.size == 3)

      dut.io.ifuRefill.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.tag.poke(tags(2).U)
      dut.io.memoryResponse.bits.linePa.poke(0xa000.U)
      dut.io.memoryResponse.bits.lineData.poke(0xfeed.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.ifuRefill.valid.expect(true.B)
      dut.io.ifuRefill.bits.transactionId.expect(0x13.U)
      dut.io.ifuRefill.bits.fetchPacketUid.expect(0x23.U)
      dut.io.ifuRefill.bits.fetchSeq.expect(0x33.U)
      dut.io.ifuRefill.bits.lineVa.expect(0x1080.U)
      dut.io.ifuRefill.bits.linePa.expect(0xa000.U)
      dut.io.ifuRefill.bits.lineData.expect(0xfeed.U)
      dut.clock.step()

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.tag.poke(tags.head.U)
      dut.io.memoryResponse.bits.linePa.poke(0x8000.U)
      dut.io.memoryResponse.bits.lineData.poke(0xbeef.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.ifuRefill.valid.expect(true.B)
      dut.io.ifuRefill.bits.transactionId.expect(0x11.U)
      dut.io.ifuRefill.bits.fetchPacketUid.expect(0x21.U)
      dut.io.ifuRefill.bits.fetchSeq.expect(0x31.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.outstandingCount.expect(1.U)
    }
  }

  test("holds queued requests and accepted responses under independent backpressure") {
    simulate(new IfuLineMemoryBridge(p, entries = 4)) { dut =>
      clear(dut)
      enqueue(dut, 0x41, 0x51, 0x61, 0x2000, 0xb000)

      dut.io.memoryRequest.valid.expect(true.B)
      val firstTag = dut.io.memoryRequest.bits.tag.peek().litValue
      dut.io.memoryRequest.bits.linePa.expect(0xb000.U)
      enqueue(dut, 0x42, 0x52, 0x62, 0x2040, 0xc000)
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.tag.expect(firstTag.U)
      dut.io.memoryRequest.bits.linePa.expect(0xb000.U)
      dut.clock.step(2)
      dut.io.memoryRequest.bits.tag.expect(firstTag.U)

      issue(dut, 0xb000)
      issue(dut, 0xc000)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.tag.poke(firstTag.U)
      dut.io.memoryResponse.bits.linePa.poke(0xb000.U)
      dut.io.memoryResponse.bits.lineData.poke(0x1234.U)
      dut.io.ifuRefill.ready.poke(false.B)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.ifuRefill.valid.expect(true.B)
      dut.io.ifuRefill.bits.transactionId.expect(0x41.U)
      dut.clock.step(2)
      dut.io.ifuRefill.bits.transactionId.expect(0x41.U)
      dut.io.ifuRefill.bits.lineData.expect(0x1234.U)
      dut.io.outstandingCount.expect(2.U)

      dut.io.ifuRefill.ready.poke(true.B)
      dut.clock.step()
      dut.io.outstandingCount.expect(1.U)
    }
  }

  test("drains stale tags without mutating a live request") {
    simulate(new IfuLineMemoryBridge(p, entries = 2)) { dut =>
      clear(dut)
      enqueue(dut, 0x71, 0x81, 0x91, 0x3000, 0xd000)
      val liveTag = issue(dut, 0xd000)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.tag.poke((liveTag + 7).U)
      dut.io.memoryResponse.bits.linePa.poke(0xd000.U)
      dut.io.memoryResponse.bits.lineData.poke(0xaaaa.U)
      dut.io.staleResponse.expect(true.B)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.io.ifuRefill.valid.expect(false.B)
      dut.clock.step()
      dut.io.outstandingCount.expect(1.U)

      dut.io.memoryResponse.bits.tag.poke(liveTag.U)
      dut.io.memoryResponse.bits.linePa.poke(0xd040.U)
      dut.io.ifuRefill.ready.poke(true.B)
      dut.io.staleResponse.expect(true.B)
      dut.io.ifuRefill.valid.expect(false.B)
      dut.clock.step()
      dut.io.outstandingCount.expect(1.U)

      dut.io.memoryResponse.bits.linePa.poke(0xd000.U)
      dut.io.staleResponse.expect(false.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.ifuRefill.valid.expect(true.B)
      dut.io.ifuRefill.bits.transactionId.expect(0x71.U)
      dut.clock.step()
      dut.io.outstandingCount.expect(0.U)
    }
  }

  test("separates same-PA aliases and recovers credit only after IFU consumption") {
    simulate(new IfuLineMemoryBridge(p, entries = 2)) { dut =>
      clear(dut)
      enqueue(dut, 0xa1, 0xb1, 0xc1, 0x4000, 0xe000)
      enqueue(dut, 0xa2, 0xb2, 0xc2, 0x5000, 0xe000)
      dut.io.ifuRequest.valid.poke(true.B)
      dut.io.ifuRequest.ready.expect(false.B)
      dut.io.ifuRequest.valid.poke(false.B)

      val firstTag = issue(dut, 0xe000)
      val secondTag = issue(dut, 0xe000)
      assert(firstTag != secondTag)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.tag.poke(secondTag.U)
      dut.io.memoryResponse.bits.linePa.poke(0xe000.U)
      dut.io.memoryResponse.bits.lineData.poke(0x2222.U)
      dut.clock.step()
      dut.io.memoryResponse.bits.tag.poke(firstTag.U)
      dut.io.memoryResponse.bits.lineData.poke(0x1111.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.responsePendingMask.expect("b11".U)
      dut.io.outstandingCount.expect(2.U)

      dut.io.ifuRefill.valid.expect(true.B)
      dut.io.ifuRefill.bits.transactionId.expect(0xa1.U)
      dut.io.ifuRefill.bits.lineVa.expect(0x4000.U)
      dut.io.ifuRefill.bits.linePa.expect(0xe000.U)
      dut.io.ifuRefill.bits.lineData.expect(0x1111.U)
      dut.io.ifuRefill.ready.poke(true.B)
      dut.clock.step()
      dut.io.ifuRequest.ready.expect(true.B)
      dut.io.ifuRefill.bits.transactionId.expect(0xa2.U)
      dut.io.ifuRefill.bits.lineVa.expect(0x5000.U)
      dut.io.ifuRefill.bits.lineData.expect(0x2222.U)
      dut.clock.step()
      dut.io.outstandingCount.expect(0.U)
    }
  }

  test("retains a zero-latency response in the memory-request issue cycle") {
    simulate(new IfuLineMemoryBridge(p, entries = 2)) { dut =>
      clear(dut)
      enqueue(dut, 0xd1, 0xe1, 0xf1, 0x6000, 0xf000)
      dut.io.memoryRequest.valid.expect(true.B)
      val tag = dut.io.memoryRequest.bits.tag.peek().litValue

      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.tag.poke(tag.U)
      dut.io.memoryResponse.bits.linePa.poke(0xf000.U)
      dut.io.memoryResponse.bits.lineData.poke(0xabcd.U)
      dut.io.staleResponse.expect(false.B)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.clock.step()

      dut.io.memoryRequest.ready.poke(false.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.ifuRefill.valid.expect(true.B)
      dut.io.ifuRefill.bits.transactionId.expect(0xd1.U)
      dut.io.ifuRefill.bits.fetchPacketUid.expect(0xe1.U)
      dut.io.ifuRefill.bits.fetchSeq.expect(0xf1.U)
      dut.io.ifuRefill.bits.lineData.expect(0xabcd.U)
      dut.io.ifuRefill.ready.poke(true.B)
      dut.clock.step()
      dut.io.outstandingCount.expect(0.U)
    }
  }
}
