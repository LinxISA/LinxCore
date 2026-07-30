package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class LoadStructuralBlockPolicySpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: LoadStructuralBlockPolicy): Unit = {
    dut.io.hardFlush.poke(false.B)
    dut.io.recoveryKill.poke(false.B)
    dut.io.recoveryFire.poke(false.B)
    dut.io.hardBlock.valid.poke(false.B)
    dut.io.hardBlock.bits.poke(0.U.asTypeOf(dut.io.hardBlock.bits))
    dut.io.retry.ready.poke(false.B)
  }

  private def pokeAttempt(
      attempt: LoadAttemptIdentity,
      generation: BigInt): Unit = {
    attempt.valid.poke(true.B)
    attempt.producer.valid.poke(true.B)
    attempt.producer.peId.poke(2.U)
    attempt.producer.stid.poke(1.U)
    attempt.producer.nativeBidValid.poke(true.B)
    attempt.producer.nativeBid.poke(7.U)
    attempt.producer.brobGeneration.poke(11.U)
    attempt.producer.ridSlot.poke(13.U)
    attempt.producer.ridGeneration.poke(17.U)
    attempt.producer.memberIndex.poke(19.U)
    attempt.producer.residentGeneration.poke(23.U)
    attempt.generation.poke(generation.U)
  }

  private def pokeBaseHardBlock(
      dut: LoadStructuralBlockPolicy,
      generation: BigInt = 9): Unit = {
    dut.io.hardBlock.bits.poke(0.U.asTypeOf(dut.io.hardBlock.bits))
    dut.io.hardBlock.bits.query.loadId.valid.poke(true.B)
    dut.io.hardBlock.bits.query.loadId.slot.poke(3.U)
    dut.io.hardBlock.bits.query.loadId.generation.poke(1.U)
    pokeAttempt(dut.io.hardBlock.bits.query.attempt, generation)
    dut.io.hardBlock.bits.query.returnPipeIndex.poke(2.U)
    dut.io.hardBlock.bits.query.loadLsIdFullValid.poke(true.B)
    dut.io.hardBlock.bits.query.loadLsIdFull.poke("h456".U)
    dut.io.hardBlock.bits.unknownOlderMask.poke(4.U)
    dut.io.hardBlock.bits.unknownWaitStore.valid.poke(true.B)
    dut.io.hardBlock.bits.unknownWaitStore.storeIndex.poke(2.U)
    dut.io.hardBlock.bits.unknownWaitStore.storeId.valid.poke(true.B)
    dut.io.hardBlock.bits.unknownWaitStore.storeId.value.poke(5.U)
    dut.io.hardBlock.bits.unknownWaitStore.storeLsId.valid.poke(true.B)
    dut.io.hardBlock.bits.unknownWaitStore.storeLsId.value.poke(6.U)
    dut.io.hardBlock.bits.unknownWaitStore.storeLsIdFullValid.poke(true.B)
    dut.io.hardBlock.bits.unknownWaitStore.storeLsIdFull.poke("h123".U)
    dut.io.hardBlock.bits.unknownWaitStore.pc.poke("h80000100".U)
  }

  private def newPolicy = new LoadStructuralBlockPolicy(
    robEntries = 8,
    liqEntries = 4,
    stqEntries = 4,
    stidWidth = 4,
    lsidWidth = 40,
    tokenWidth = 11)

  test("unknown older store retains an exact wait retry under backpressure") {
    simulate(newPolicy) { dut =>
      clear(dut)
      pokeBaseHardBlock(dut)
      dut.io.hardBlock.valid.poke(true.B)
      dut.io.hardBlock.ready.expect(true.B)
      dut.clock.step()

      dut.io.hardBlock.valid.poke(false.B)
      for (_ <- 0 until 3) {
        dut.io.pending.expect(true.B)
        dut.io.unsupported.expect(false.B)
        dut.io.disposition.expect(LoadStructuralBlockDisposition.WaitStore)
        dut.io.retry.valid.expect(true.B)
        dut.io.retry.bits.loadId.valid.expect(true.B)
        dut.io.retry.bits.loadId.slot.expect(3.U)
        dut.io.retry.bits.loadId.generation.expect(1.U)
        dut.io.retry.bits.current.generation.expect(9.U)
        dut.io.retry.bits.next.generation.expect(10.U)
        dut.io.retry.bits.returnPipeIndex.expect(2.U)
        dut.io.retry.bits.waitStore.expect(true.B)
        dut.io.retry.bits.waitStoreInfo.storeIndex.expect(2.U)
        dut.io.retry.bits.waitStoreInfo.storeId.value.expect(5.U)
        dut.io.retry.bits.waitStoreInfo.storeLsIdFull.expect("h123".U)
        dut.clock.step()
      }

      dut.io.retry.ready.poke(true.B)
      dut.io.retry.valid.expect(true.B)
      dut.clock.step()
      dut.io.pending.expect(false.B)
      dut.io.empty.expect(true.B)
    }
  }

  test("stale snapshot outranks an unknown wait store") {
    simulate(newPolicy) { dut =>
      clear(dut)
      pokeBaseHardBlock(dut)
      dut.io.hardBlock.bits.staleSnapshotMask.poke(2.U)
      dut.io.hardBlock.valid.poke(true.B)
      dut.clock.step()

      dut.io.hardBlock.valid.poke(false.B)
      dut.io.disposition.expect(LoadStructuralBlockDisposition.RetrySnapshot)
      dut.io.retry.valid.expect(true.B)
      dut.io.retry.bits.waitStore.expect(false.B)
      dut.io.retry.bits.waitStoreInfo.valid.expect(false.B)
      dut.io.retry.bits.next.generation.expect(10.U)
    }
  }

  test("missing ordering authority remains fail closed until hard flush") {
    simulate(newPolicy) { dut =>
      clear(dut)
      pokeBaseHardBlock(dut)
      dut.io.hardBlock.bits.fullLsIdMissingMask.poke(1.U)
      dut.io.hardBlock.valid.poke(true.B)
      dut.clock.step()

      dut.io.hardBlock.valid.poke(false.B)
      dut.io.pending.expect(true.B)
      dut.io.unsupported.expect(true.B)
      dut.io.disposition.expect(LoadStructuralBlockDisposition.Unsupported)
      dut.io.protocolError.expect(true.B)
      dut.io.retry.valid.expect(false.B)
      dut.clock.step(3)
      dut.io.pending.expect(true.B)
      dut.io.protocolError.expect(true.B)

      dut.io.hardFlush.poke(true.B)
      dut.clock.step()
      dut.io.pending.expect(false.B)
      dut.io.protocolError.expect(false.B)
      dut.io.empty.expect(true.B)
    }
  }

  test("only an exact typed recovery kill clears a resident record") {
    simulate(newPolicy) { dut =>
      clear(dut)
      pokeBaseHardBlock(dut)
      dut.io.hardBlock.valid.poke(true.B)
      dut.clock.step()

      dut.io.hardBlock.valid.poke(false.B)
      dut.io.recoveryKill.poke(false.B)
      dut.io.recoveryReady.expect(false.B)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.pending.expect(true.B)

      dut.io.recoveryKill.poke(true.B)
      dut.io.recoveryReady.expect(true.B)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.pending.expect(false.B)
      dut.io.empty.expect(true.B)
    }
  }
}
