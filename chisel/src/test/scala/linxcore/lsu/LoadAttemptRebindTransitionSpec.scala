package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

private class LoadAttemptRebindTransitionHarnessIO extends Bundle {
  val current = Input(new LoadInflightRow(
    liqEntries = 4, idEntries = 8, storeEntries = 4))
  val nextAttempt = Input(new LoadAttemptIdentity)
  val lifecycleReady = Output(Bool())
  val next = Output(new LoadInflightRow(
    liqEntries = 4, idEntries = 8, storeEntries = 4))
}

private class LoadAttemptRebindTransitionHarness extends Module {
  val io = IO(new LoadAttemptRebindTransitionHarnessIO)
  io.lifecycleReady :=
    LoadAttemptRebindTransition.lifecycleReady(io.current.status)
  io.next := LoadAttemptRebindTransition.next(io.current, io.nextAttempt)
}

class LoadAttemptRebindTransitionSpec extends AnyFunSuite with ChiselSim {
  private def pokeAttempt(attempt: LoadAttemptIdentity,
      generation: Int): Unit = {
    attempt.poke(0.U.asTypeOf(attempt))
    attempt.valid.poke(true.B)
    attempt.producer.valid.poke(true.B)
    attempt.producer.peId.poke(3.U)
    attempt.producer.stid.poke(1.U)
    attempt.producer.nativeBidValid.poke(true.B)
    attempt.producer.nativeBid.poke(6.U)
    attempt.producer.brobGeneration.poke(7.U)
    attempt.producer.ridSlot.poke(5.U)
    attempt.producer.ridGeneration.poke(4.U)
    attempt.producer.memberIndex.poke(2.U)
    attempt.producer.residentGeneration.poke(9.U)
    attempt.generation.poke(generation.U)
  }

  private def pokeDirtyRow(dut: LoadAttemptRebindTransitionHarness,
      status: LoadInflightStatus.Type): Unit = {
    val row = dut.io.current
    row.poke(0.U.asTypeOf(row))
    row.valid.poke(true.B)
    row.status.poke(status)
    row.loadId.valid.poke(true.B)
    row.loadId.value.poke(2.U)
    row.loadId.wrap.poke(true.B)
    row.pc.poke(0x1234.U)
    row.addr.poke(0x5678.U)
    row.loadLsIdFullValid.poke(true.B)
    row.loadLsIdFull.poke(0x45.U)
    row.returnPipeIndex.poke(0.U)
    pokeAttempt(row.attempt, 8)
    row.forwardPending.poke(true.B)
    row.lineData.poke(0xdead.U)
    row.validMask.poke(0xff.U)
    row.loadByteMask.poke(0xf0.U)
    row.forwardMask.poke(0x0f.U)
    row.waitMask.poke(0x33.U)
    row.secondSegmentActive.poke(true.B)
    row.firstSegmentDone.poke(true.B)
    row.firstLineData.poke(0xbeef.U)
    row.firstValidMask.poke(0xaa.U)
    row.firstLoadByteMask.poke(0x55.U)
    row.firstForwardMask.poke(0x11.U)
    row.waitStore.poke(true.B)
    row.storeBypass.poke(true.B)
    row.dataComplete.poke(true.B)
    row.sourcesReturned.poke(true.B)
    row.scbReturned.poke(true.B)
    row.stqReturned.poke(true.B)
    row.l1Hit.poke(true.B)
    row.l1Miss.poke(true.B)
    row.missKind.poke(LoadForwardMissKind.DataNotComplete)
    pokeAttempt(dut.io.nextAttempt, 9)
  }

  for (status <- Seq(LoadInflightStatus.Wait, LoadInflightStatus.L1DcMiss,
      LoadInflightStatus.L2Wait)) {
    test(s"accepted ${status.toString} rebind preserves ownership and clears old-attempt evidence") {
      simulate(new LoadAttemptRebindTransitionHarness) { dut =>
        pokeDirtyRow(dut, status)
        dut.io.lifecycleReady.expect(true.B)
        dut.io.next.valid.expect(true.B)
        dut.io.next.status.expect(LoadInflightStatus.Wait)
        dut.io.next.loadId.valid.expect(true.B)
        dut.io.next.loadId.value.expect(2.U)
        dut.io.next.loadId.wrap.expect(true.B)
        dut.io.next.pc.expect(0x1234.U)
        dut.io.next.addr.expect(0x5678.U)
        dut.io.next.loadLsIdFull.expect(0x45.U)
        dut.io.next.returnPipeIndex.expect(0.U)
        dut.io.next.attempt.generation.expect(9.U)
        dut.io.next.attempt.producer.expect(dut.io.current.attempt.producer.peek())
        dut.io.next.forwardPending.expect(false.B)
        dut.io.next.lineData.expect(0.U)
        dut.io.next.validMask.expect(0.U)
        dut.io.next.loadByteMask.expect(0.U)
        dut.io.next.forwardMask.expect(0.U)
        dut.io.next.waitMask.expect(0.U)
        dut.io.next.secondSegmentActive.expect(false.B)
        dut.io.next.firstSegmentDone.expect(false.B)
        dut.io.next.firstLineData.expect(0.U)
        dut.io.next.waitStore.expect(false.B)
        dut.io.next.storeBypass.expect(false.B)
        dut.io.next.dataComplete.expect(false.B)
        dut.io.next.sourcesReturned.expect(false.B)
        dut.io.next.scbReturned.expect(false.B)
        dut.io.next.stqReturned.expect(false.B)
        dut.io.next.l1Hit.expect(false.B)
        dut.io.next.l1Miss.expect(false.B)
        dut.io.next.missKind.expect(LoadForwardMissKind.NoMiss)
      }
    }
  }

  test("terminal and in-flight nonreplayable states reject rebind") {
    simulate(new LoadAttemptRebindTransitionHarness) { dut =>
      for (status <- Seq(LoadInflightStatus.Idle, LoadInflightStatus.Repick,
          LoadInflightStatus.Resolved)) {
        pokeDirtyRow(dut, status)
        dut.io.lifecycleReady.expect(false.B)
      }
    }
  }
}
