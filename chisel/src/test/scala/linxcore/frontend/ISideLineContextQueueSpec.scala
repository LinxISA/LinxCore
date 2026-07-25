package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ISideLineContextQueueSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()
  private val lineBytes = 16

  private def clear(dut: ISideLineContextQueue): Unit = {
    dut.io.allocate.valid.poke(false.B)
    dut.io.allocate.bits.poke(0.U.asTypeOf(dut.io.allocate.bits))
    dut.io.complete.valid.poke(false.B)
    dut.io.complete.bits.poke(0.U.asTypeOf(dut.io.complete.bits))
    dut.io.carry.valid.poke(false.B)
    dut.io.carry.bits.poke(0.U.asTypeOf(dut.io.carry.bits))
    dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
    dut.io.out.ready.poke(false.B)
  }

  private def pokeRequest(
      request: ISideFetchRequest,
      transactionId: Int,
      pc: BigInt,
      epoch: Int = 0): Unit = {
    request.poke(0.U.asTypeOf(request))
    request.pc.poke(pc.U)
    request.lineVa.poke((pc & ~BigInt(lineBytes - 1)).U)
    request.transactionId.poke(transactionId.U)
    request.identity.peId.poke(1.U)
    request.identity.threadId.poke(0.U)
    request.identity.fetchPacketUid.poke(transactionId.U)
    request.identity.fetchSeq.poke(transactionId.U)
    request.identity.checkpointId.poke(transactionId.U)
    request.identity.epoch.poke(epoch.U)
  }

  private def allocate(
      dut: ISideLineContextQueue,
      transactionId: Int,
      pc: BigInt,
      epoch: Int = 0): Unit = {
    pokeRequest(dut.io.allocate.bits, transactionId, pc, epoch)
    dut.io.allocate.valid.poke(true.B)
    dut.io.allocate.ready.expect(true.B)
    dut.clock.step()
    dut.io.allocate.valid.poke(false.B)
  }

  private def complete(
      dut: ISideLineContextQueue,
      transactionId: Int,
      pc: BigInt,
      lineData: BigInt,
      epoch: Int = 0): Unit = {
    dut.io.complete.bits.poke(0.U.asTypeOf(dut.io.complete.bits))
    pokeRequest(dut.io.complete.bits.request, transactionId, pc, epoch)
    dut.io.complete.bits.status.poke(ISideF2Status.Hit)
    dut.io.complete.bits.lineData.poke(lineData.U)
    dut.io.complete.valid.poke(true.B)
    dut.io.complete.ready.expect(true.B)
    dut.clock.step()
    dut.io.complete.valid.poke(false.B)
  }

  private def carry(
      dut: ISideLineContextQueue,
      transactionId: Int,
      lineVa: BigInt,
      successorPc: BigInt,
      epoch: Int = 0): Unit = {
    dut.io.carry.bits.poke(0.U.asTypeOf(dut.io.carry.bits))
    dut.io.carry.valid.poke(true.B)
    dut.io.carry.bits.successorTransactionId.poke(transactionId.U)
    dut.io.carry.bits.successorIdentity.peId.poke(1.U)
    dut.io.carry.bits.successorIdentity.threadId.poke(0.U)
    dut.io.carry.bits.successorIdentity.fetchPacketUid.poke(transactionId.U)
    dut.io.carry.bits.successorIdentity.fetchSeq.poke(transactionId.U)
    dut.io.carry.bits.successorIdentity.checkpointId.poke(transactionId.U)
    dut.io.carry.bits.successorIdentity.epoch.poke(epoch.U)
    dut.io.carry.bits.successorLineVa.poke(lineVa.U)
    dut.io.carry.bits.successorPc.poke(successorPc.U)
    dut.clock.step()
    dut.io.carry.valid.poke(false.B)
  }

  test("returns out-of-order I-F2 completions to I-F3 in F0 allocation order") {
    simulate(new ISideLineContextQueue(p, lineBytes, entries = 4)) { dut =>
      clear(dut)
      allocate(dut, 0, 0x1000)
      allocate(dut, 1, 0x1010)
      allocate(dut, 2, 0x1020)
      dut.io.count.expect(3.U)

      complete(dut, 2, 0x1020, 0x22)
      dut.io.out.valid.expect(false.B)
      dut.io.headValid.expect(true.B)
      dut.io.headCompleted.expect(false.B)
      dut.io.headRequest.transactionId.expect(0.U)
      complete(dut, 0, 0x1000, 0x00)
      complete(dut, 1, 0x1010, 0x11)

      dut.io.out.ready.poke(true.B)
      for ((transactionId, lineData) <- Seq((0, 0x00), (1, 0x11), (2, 0x22))) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.request.transactionId.expect(transactionId.U)
        dut.io.out.bits.lineData.expect(lineData.U)
        dut.clock.step()
      }
      dut.io.count.expect(0.U)
      dut.io.out.valid.expect(false.B)
    }
  }

  test("applies an exact prefix carry to an already completed successor") {
    simulate(new ISideLineContextQueue(p, lineBytes, entries = 4)) { dut =>
      clear(dut)
      allocate(dut, 4, 0x2010)
      complete(dut, 4, 0x2010, 0x44)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.request.pc.expect(0x2010.U)

      carry(dut, transactionId = 4, lineVa = 0x2010, successorPc = 0x2012)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.request.pc.expect(0x2012.U)
      dut.io.out.bits.lineData.expect(0x44.U)
    }
  }

  test("retains a prefix carry until its exact successor allocates") {
    simulate(new ISideLineContextQueue(p, lineBytes, entries = 4)) { dut =>
      clear(dut)
      carry(dut, transactionId = 5, lineVa = 0x3010, successorPc = 0x3014)
      dut.io.carryPending.expect(true.B)

      allocate(dut, 5, 0x3010)
      dut.io.carryPending.expect(false.B)
      complete(dut, 5, 0x3010, 0x55)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.request.pc.expect(0x3014.U)
    }
  }

  test("precise flush preserves its producer and removes younger contexts") {
    simulate(new ISideLineContextQueue(p, lineBytes, entries = 4)) { dut =>
      clear(dut)
      allocate(dut, 6, 0x4000)
      allocate(dut, 7, 0x4010)
      complete(dut, 6, 0x4000, 0x66)
      complete(dut, 7, 0x4010, 0x77)

      dut.io.flush.valid.poke(true.B)
      dut.io.flush.threadId.poke(0.U)
      dut.io.flush.transactionId.poke(6.U)
      dut.io.flush.fetchSeq.poke(6.U)
      dut.io.flush.oldEpoch.poke(0.U)
      dut.io.flush.newEpoch.poke(1.U)
      dut.io.flush.scope.poke(IfuPruneScope.PreserveTriggerKillYounger)
      dut.clock.step()
      dut.io.flush.valid.poke(false.B)

      dut.io.count.expect(1.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.request.transactionId.expect(6.U)
    }
  }

  test("drains a stale completion without creating a context") {
    simulate(new ISideLineContextQueue(p, lineBytes, entries = 4)) { dut =>
      clear(dut)
      dut.io.complete.bits.poke(0.U.asTypeOf(dut.io.complete.bits))
      pokeRequest(dut.io.complete.bits.request, 9, 0x5000)
      dut.io.complete.bits.status.poke(ISideF2Status.Hit)
      dut.io.complete.valid.poke(true.B)
      dut.io.complete.ready.expect(true.B)
      dut.io.completionUnmatched.expect(true.B)
      dut.clock.step()
      dut.io.complete.valid.poke(false.B)
      dut.io.count.expect(0.U)
    }
  }

  test("one-entry line context queue elaborates without changing its contract") {
    val sv = ChiselStage.emitSystemVerilog(new ISideLineContextQueue(p, lineBytes, entries = 1))
    assert(sv.contains("module ISideLineContextQueue"))
    assert(sv.contains("io_completionUnmatched"))
    assert(sv.contains("io_carryPending"))
  }
}
