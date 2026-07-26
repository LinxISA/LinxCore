package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ISideFetchMissTableSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()
  private val lineBytes = 16

  private def clear(dut: ISideFetchMissTable): Unit = {
    dut.io.allocate.valid.poke(false.B)
    dut.io.allocate.bits.poke(0.U.asTypeOf(dut.io.allocate.bits))
    dut.io.refill.valid.poke(false.B)
    dut.io.refill.bits.poke(0.U.asTypeOf(dut.io.refill.bits))
    dut.io.retry.ready.poke(false.B)
    dut.io.innerFlush.poke(0.U.asTypeOf(dut.io.innerFlush))
  }

  private def allocate(
      dut: ISideFetchMissTable,
      transactionId: Int,
      epoch: Int,
      linePa: BigInt): Unit = {
    dut.io.allocate.bits.poke(0.U.asTypeOf(dut.io.allocate.bits))
    dut.io.allocate.valid.poke(true.B)
    dut.io.allocate.bits.status.poke(ISideF2Status.L1IMiss)
    dut.io.allocate.bits.linePa.poke(linePa.U)
    dut.io.allocate.bits.request.pc.poke(0x1000.U)
    dut.io.allocate.bits.request.lineVa.poke(0x1000.U)
    dut.io.allocate.bits.request.transactionId.poke(transactionId.U)
    dut.io.allocate.bits.request.identity.threadId.poke(0.U)
    dut.io.allocate.bits.request.identity.fetchSeq.poke(transactionId.U)
    dut.io.allocate.bits.request.identity.epoch.poke(epoch.U)
    dut.io.allocate.ready.expect(true.B)
    dut.clock.step()
    dut.io.allocate.valid.poke(false.B)
  }

  private def refill(
      dut: ISideFetchMissTable,
      transactionId: Int,
      epoch: Int,
      linePa: BigInt,
      lineData: BigInt): Unit = {
    dut.io.refill.valid.poke(true.B)
    dut.io.refill.bits.peId.poke(0.U)
    dut.io.refill.bits.transactionId.poke(transactionId.U)
    dut.io.refill.bits.threadId.poke(0.U)
    dut.io.refill.bits.fetchPacketUid.poke(0.U)
    dut.io.refill.bits.fetchSeq.poke(transactionId.U)
    dut.io.refill.bits.checkpointId.poke(0.U)
    dut.io.refill.bits.epoch.poke(epoch.U)
    dut.io.refill.bits.lineVa.poke(0x1000.U)
    dut.io.refill.bits.linePa.poke(linePa.U)
    dut.io.refill.bits.lineData.poke(lineData.U)
  }

  test("retains a live miss through refill, updates L1I, and retries exact identity") {
    simulate(new ISideFetchMissTable(p, entries = 4, lineBytes = lineBytes)) { dut =>
      clear(dut)
      allocate(dut, transactionId = 7, epoch = 0, linePa = 0x220)
      refill(dut, transactionId = 7, epoch = 0, linePa = 0x220, lineData = 0x55)
      dut.io.refill.ready.expect(true.B)
      dut.io.l1iRefill.valid.expect(true.B)
      dut.io.l1iRefill.bits.linePa.expect(0x220.U)
      dut.clock.step()
      dut.io.refill.valid.poke(false.B)

      dut.io.retry.valid.expect(true.B)
      dut.io.retry.bits.transactionId.expect(7.U)
      dut.io.retry.bits.identity.epoch.expect(0.U)
      dut.io.retry.ready.poke(true.B)
      dut.clock.step()
      dut.io.validMask.expect(0.U)
    }
  }

  test("inner flush orphans an issued miss so refill updates physical L1I without stale retry") {
    simulate(new ISideFetchMissTable(p, entries = 4, lineBytes = lineBytes)) { dut =>
      clear(dut)
      allocate(dut, transactionId = 9, epoch = 0, linePa = 0x330)

      dut.io.innerFlush.valid.poke(true.B)
      dut.io.innerFlush.threadId.poke(0.U)
      dut.io.innerFlush.newEpoch.poke(1.U)
      dut.clock.step()
      dut.io.innerFlush.valid.poke(false.B)
      dut.io.orphanMask.expect(1.U)

      refill(dut, transactionId = 8, epoch = 0, linePa = 0x330, lineData = 0xaa)
      dut.io.refill.ready.expect(false.B)
      dut.io.staleRefill.expect(true.B)
      dut.io.refill.bits.transactionId.poke(9.U)
      dut.io.refill.bits.fetchSeq.poke(9.U)
      dut.io.refill.bits.epoch.poke(1.U)
      dut.io.refill.ready.expect(false.B)
      dut.io.staleRefill.expect(true.B)
      dut.io.refill.bits.epoch.poke(0.U)
      dut.io.refill.ready.expect(true.B)
      dut.io.l1iRefill.valid.expect(true.B)
      dut.clock.step()
      dut.io.refill.valid.poke(false.B)

      dut.io.retry.valid.expect(false.B)
      dut.io.validMask.expect(0.U)
    }
  }

  test("inner flush backpressures a coincident refill until orphan state is recorded") {
    simulate(new ISideFetchMissTable(p, entries = 4, lineBytes = lineBytes)) { dut =>
      clear(dut)
      allocate(dut, transactionId = 11, epoch = 0, linePa = 0x440)
      refill(dut, transactionId = 11, epoch = 0, linePa = 0x440, lineData = 0xbb)
      dut.io.innerFlush.valid.poke(true.B)
      dut.io.innerFlush.threadId.poke(0.U)
      dut.io.innerFlush.scope.poke(IfuPruneScope.KillAllThreadState)
      dut.io.refill.ready.expect(false.B)
      dut.io.l1iRefill.valid.expect(false.B)
      dut.clock.step()

      dut.io.innerFlush.valid.poke(false.B)
      dut.io.orphanMask.expect(1.U)
      dut.io.refill.ready.expect(true.B)
      dut.io.l1iRefill.valid.expect(true.B)
      dut.clock.step()
      dut.io.refill.valid.poke(false.B)
      dut.io.validMask.expect(0.U)
      dut.io.retry.valid.expect(false.B)
    }
  }

  test("refill matching rejects every independent request identity mismatch") {
    simulate(new ISideFetchMissTable(p, entries = 4, lineBytes = lineBytes)) { dut =>
      clear(dut)
      allocate(dut, transactionId = 13, epoch = 2, linePa = 0x550)
      refill(dut, transactionId = 13, epoch = 2, linePa = 0x550, lineData = 0xcc)
      dut.io.refill.ready.expect(true.B)

      val mismatches = Seq[(UInt, BigInt)](
        dut.io.refill.bits.peId -> 1,
        dut.io.refill.bits.fetchPacketUid -> 1,
        dut.io.refill.bits.fetchSeq -> 14,
        dut.io.refill.bits.checkpointId -> 1,
        dut.io.refill.bits.lineVa -> 0x2000)
      mismatches.foreach { case (field, wrong) =>
        val original = field.peek().litValue
        field.poke(wrong.U)
        dut.io.refill.ready.expect(false.B)
        dut.io.staleRefill.expect(true.B)
        field.poke(original.U)
        dut.io.refill.ready.expect(true.B)
      }

      dut.clock.step()
      dut.io.refill.valid.poke(false.B)
      dut.io.retry.valid.expect(true.B)
    }
  }
}
