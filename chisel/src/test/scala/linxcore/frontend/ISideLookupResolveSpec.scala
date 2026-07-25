package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.Valid
import linxcore.common.{BoundaryKind, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

class ISideLookupResolveProbeIO(
    val p: InterfaceParams,
    val lineBytes: Int,
    val pageBytes: Int)
    extends Bundle {
  val request = Flipped(chisel3.util.Decoupled(new ISideFetchRequest(p, lineBytes)))
  val itlbRefill = Flipped(Valid(new ISideItlbRefill(p, pageBytes)))
  val l1iRefill = Flipped(Valid(new ISideL1IRefill(p, lineBytes)))
  val result = chisel3.util.Decoupled(new ISideF2Result(p, lineBytes))
  val innerFlush = chisel3.util.Decoupled(new IfuInnerFlush(p))
  val externalFlush = Input(new IfuInnerFlush(p))
  val parallelLaunch = Output(Bool())
}

class ISideLookupResolveProbe(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 16,
    val pageBytes: Int = 256)
    extends Module {
  val io = IO(new ISideLookupResolveProbeIO(p, lineBytes, pageBytes))

  val f1 = Module(new ISideF1Lookup(p, lineBytes))
  val itlb = Module(new ISideITLB(p, entries = 4, lineBytes = lineBytes, pageBytes = pageBytes))
  val l1i = Module(new ISideL1I(p, sets = 4, lineBytes = lineBytes))
  val f2 = Module(new ISideF2Resolve(p, lineBytes, pageBytes))

  f1.io.in <> io.request
  itlb.io.lookup <> f1.io.itlbRequest
  l1i.io.lookup <> f1.io.l1iRequest
  f2.io.translation <> itlb.io.response
  f2.io.cacheCandidate <> l1i.io.response
  io.result <> f2.io.result
  io.innerFlush <> f2.io.innerFlush

  itlb.io.refill <> io.itlbRefill
  l1i.io.refill <> io.l1iRefill
  itlb.io.invalidate := false.B
  l1i.io.invalidate := false.B
  itlb.io.innerFlush := io.externalFlush
  l1i.io.innerFlush := io.externalFlush
  f2.io.externalFlush := io.externalFlush

  io.parallelLaunch := f1.io.parallelLaunch
}

class ISideLookupResolveSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()
  private val lineBytes = 16
  private val pageBytes = 256

  private def clear(dut: ISideLookupResolveProbe): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.itlbRefill.valid.poke(false.B)
    dut.io.itlbRefill.bits.poke(0.U.asTypeOf(dut.io.itlbRefill.bits))
    dut.io.l1iRefill.valid.poke(false.B)
    dut.io.l1iRefill.bits.poke(0.U.asTypeOf(dut.io.l1iRefill.bits))
    dut.io.result.ready.poke(true.B)
    dut.io.innerFlush.ready.poke(true.B)
    dut.io.externalFlush.poke(0.U.asTypeOf(dut.io.externalFlush))
  }

  private def refill(
      dut: ISideLookupResolveProbe,
      vpn: Int,
      ppn: Int,
      executable: Boolean,
      linePa: BigInt,
      lineData: BigInt): Unit = {
    dut.io.itlbRefill.valid.poke(true.B)
    dut.io.itlbRefill.bits.vpn.poke(vpn.U)
    dut.io.itlbRefill.bits.ppn.poke(ppn.U)
    dut.io.itlbRefill.bits.executable.poke(executable.B)
    dut.io.l1iRefill.valid.poke(true.B)
    dut.io.l1iRefill.bits.linePa.poke(linePa.U)
    dut.io.l1iRefill.bits.lineData.poke(lineData.U)
    dut.clock.step()
    dut.io.itlbRefill.valid.poke(false.B)
    dut.io.l1iRefill.valid.poke(false.B)
  }

  private def issue(
      dut: ISideLookupResolveProbe,
      pc: BigInt,
      transactionId: Int,
      epoch: Int = 0): Unit = {
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.pc.poke(pc.U)
    dut.io.request.bits.lineVa.poke((pc & ~BigInt(lineBytes - 1)).U)
    dut.io.request.bits.transactionId.poke(transactionId.U)
    dut.io.request.bits.identity.threadId.poke(0.U)
    dut.io.request.bits.identity.fetchSeq.poke(transactionId.U)
    dut.io.request.bits.identity.epoch.poke(epoch.U)
    dut.io.request.bits.prediction.kind.poke(BoundaryKind.Fall)
    dut.io.parallelLaunch.expect(true.B)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
  }

  private def waitForResult(dut: ISideLookupResolveProbe, limit: Int = 8): Unit = {
    var cycles = 0
    while (!dut.io.result.valid.peek().litToBoolean && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.result.valid.peek().litToBoolean)
  }

  test("I-F1 launches ITLB and L1I together and I-F2 resolves a physical-tag hit") {
    simulate(new ISideLookupResolveProbe(p, lineBytes, pageBytes)) { dut =>
      clear(dut)
      val data = BigInt("ffeeddccbbaa99887766554433221100", 16)
      refill(dut, vpn = 1, ppn = 2, executable = true, linePa = 0x220, lineData = data)
      issue(dut, pc = 0x123, transactionId = 7)
      waitForResult(dut)

      dut.io.result.bits.status.expect(ISideF2Status.Hit)
      dut.io.result.bits.linePa.expect(0x220.U)
      dut.io.result.bits.lineData.expect(data.U)
      dut.io.innerFlush.valid.expect(false.B)
    }
  }

  test("I-F2 distinguishes L1I miss, ITLB miss, and execute fault") {
    simulate(new ISideLookupResolveProbe(p, lineBytes, pageBytes)) { dut =>
      clear(dut)
      refill(dut, vpn = 1, ppn = 2, executable = true, linePa = 0x230, lineData = 0x55)
      issue(dut, pc = 0x123, transactionId = 1)
      waitForResult(dut)
      dut.io.result.bits.status.expect(ISideF2Status.L1IMiss)
      dut.clock.step()

      issue(dut, pc = 0x500, transactionId = 2)
      waitForResult(dut)
      dut.io.result.bits.status.expect(ISideF2Status.ItlbMiss)
      dut.io.innerFlush.valid.expect(true.B)
      dut.io.innerFlush.bits.restartPc.expect(0x500.U)
      dut.io.innerFlush.bits.newEpoch.expect(1.U)
      dut.clock.step()

      refill(dut, vpn = 6, ppn = 7, executable = false, linePa = 0x700, lineData = 0x66)
      issue(dut, pc = 0x600, transactionId = 3)
      waitForResult(dut)
      dut.io.result.bits.status.expect(ISideF2Status.AccessFault)
      dut.io.innerFlush.valid.expect(false.B)
    }
  }

  test("inner flush cancels transient lookup state without invalidating ITLB or L1I contents") {
    simulate(new ISideLookupResolveProbe(p, lineBytes, pageBytes)) { dut =>
      clear(dut)
      val data = BigInt("00112233445566778899aabbccddeeff", 16)
      refill(dut, vpn = 1, ppn = 2, executable = true, linePa = 0x220, lineData = data)
      issue(dut, pc = 0x120, transactionId = 1)

      dut.io.externalFlush.valid.poke(true.B)
      dut.io.externalFlush.threadId.poke(0.U)
      dut.io.externalFlush.newEpoch.poke(1.U)
      dut.clock.step()
      dut.io.externalFlush.valid.poke(false.B)
      dut.io.result.valid.expect(false.B)

      issue(dut, pc = 0x120, transactionId = 2, epoch = 1)
      waitForResult(dut)
      dut.io.result.bits.status.expect(ISideF2Status.Hit)
      dut.io.result.bits.lineData.expect(data.U)
    }
  }
}
