package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class STQCommittedStoreSerializerSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: STQCommittedStoreSerializer): Unit = {
    dut.io.batch.valid.poke(false.B)
    dut.io.batch.bits.poke(0.U.asTypeOf(dut.io.batch.bits))
    dut.io.request.ready.poke(false.B)
    dut.io.response.valid.poke(false.B)
    dut.io.response.bits.poke(0.U.asTypeOf(dut.io.response.bits))
    dut.io.recoveryActive.poke(false.B)
  }

  private def pokeOwner(owner: STQExactOwner): Unit = {
    owner.valid.poke(true.B)
    owner.peId.poke(1.U)
    owner.stid.poke(0.U)
    owner.nativeBidValid.poke(true.B)
    owner.nativeBid.poke(6.U)
    owner.brobGeneration.poke(2.U)
    owner.ridSlot.poke(1.U)
    owner.ridGeneration.poke(3.U)
    owner.memberIndex.poke(0.U)
    owner.residentGeneration.poke(4.U)
  }

  private def pokeBatch(
      dut: STQCommittedStoreSerializer,
      beats: Int,
      fragmentsPerBeat: Int,
      memoryClass: STQMemoryClass.Type = STQMemoryClass.DeviceMmio): Unit = {
    dut.io.batch.bits.poke(0.U.asTypeOf(dut.io.batch.bits))
    dut.io.batch.bits.memoryClass.poke(memoryClass)
    for (beat <- 0 until beats) {
      val issue = dut.io.batch.bits.issues(beat)
      issue.valid.poke(true.B)
      issue.stqIndex.poke((beat + 1).U)
      issue.leaseGeneration.poke((beat + 5).U)
      issue.stid.poke(0.U)
      issue.lsId.poke((10 + beat).U)
      issue.storeIdValid.poke(true.B)
      issue.storeId.poke((20 + beat).U)
      issue.logicalStoreValid.poke(true.B)
      issue.logicalFirstLsid.poke(10.U)
      issue.logicalFirstStoreId.poke(20.U)
      issue.logicalRequestCount.poke(beats.U)
      issue.logicalBeat.poke(beat.U)
      issue.memoryClass.poke(memoryClass)
      pokeOwner(issue.exactOwner)

      for (fragment <- 0 until fragmentsPerBeat) {
        val request = dut.io.batch.bits.requests(beat * 2 + fragment)
        request.valid.poke(true.B)
        request.memoryClass.poke(memoryClass)
        request.ownsStqRow.poke((fragment == fragmentsPerBeat - 1).B)
        request.stqIndex.poke((beat + 1).U)
        request.split.poke((fragmentsPerBeat == 2).B)
        request.segment.poke(fragment.U)
        request.last.poke((fragment == fragmentsPerBeat - 1).B)
        request.addr.poke((0x1000 + beat * 0x40 + fragment * 0x20).U)
        request.data.poke((0x1100 + beat * 0x10 + fragment).U)
        request.size.poke(4.U)
        request.stid.poke(0.U)
        request.lsId.poke((10 + beat).U)
      }
    }
    dut.io.batch.valid.poke(true.B)
  }

  test("retains one MMIO request through backpressure and exact response") {
    simulate(new STQCommittedStoreSerializer(
      stqEntries = 8, robEntries = 8, issueWidth = 2,
      stidWidth = 2, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeBatch(dut, beats = 1, fragmentsPerBeat = 1)
      dut.io.batch.ready.expect(true.B)
      dut.clock.step()
      dut.io.batch.valid.poke(false.B)

      dut.io.request.valid.expect(true.B)
      val transactionId = dut.io.request.bits.transactionId.peek().litValue
      val retainedAddr = dut.io.request.bits.fragment.addr.peek().litValue
      dut.io.recoveryActive.poke(true.B)
      dut.clock.step(2)
      dut.io.request.valid.expect(true.B)
      dut.io.request.bits.transactionId.expect(transactionId.U)
      dut.io.request.bits.fragment.addr.expect(retainedAddr.U)

      dut.io.request.ready.poke(true.B)
      dut.clock.step()
      dut.io.request.valid.expect(false.B)
      dut.io.waitingResponse.expect(true.B)
      dut.io.acceptedRequestCount.expect(1.U)

      dut.io.response.valid.poke(true.B)
      dut.io.response.bits.transactionId.poke((transactionId + 1).U)
      dut.io.staleResponse.expect(true.B)
      dut.io.response.ready.expect(false.B)
      dut.clock.step()
      dut.io.waitingResponse.expect(true.B)

      dut.io.response.bits.transactionId.poke(transactionId.U)
      dut.io.response.bits.error.poke(false.B)
      dut.io.response.ready.expect(true.B)
      dut.io.freeMask.valid.expect(true.B)
      dut.io.freeMask.bits.expect("b00000010".U)
      dut.io.logicalCompletion.valid.expect(true.B)
      dut.io.logicalCompletion.bits.logicalFirstLsid.expect(10.U)
      dut.clock.step()
      dut.io.response.valid.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.io.request.valid.expect(false.B)
    }
  }

  test("serializes every pair fragment and releases both rows only at the end") {
    simulate(new STQCommittedStoreSerializer(
      stqEntries = 8, robEntries = 8, issueWidth = 2,
      stidWidth = 2, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeBatch(dut, beats = 2, fragmentsPerBeat = 2,
        memoryClass = STQMemoryClass.NormalNonCacheable)
      dut.clock.step()
      dut.io.batch.valid.poke(false.B)

      for (request <- 0 until 4) {
        dut.io.request.valid.expect(true.B)
        dut.io.request.bits.memoryClass.expect(
          STQMemoryClass.NormalNonCacheable)
        val transactionId =
          dut.io.request.bits.transactionId.peek().litValue
        dut.io.request.ready.poke(true.B)
        dut.clock.step()
        dut.io.request.ready.poke(false.B)
        dut.io.request.valid.expect(false.B)
        dut.io.freeMask.valid.expect(false.B)

        dut.io.response.valid.poke(true.B)
        dut.io.response.bits.transactionId.poke(transactionId.U)
        dut.io.response.bits.error.poke((request == 2).B)
        if (request == 3) {
          dut.io.freeMask.valid.expect(true.B)
          dut.io.freeMask.bits.expect("b00000110".U)
          dut.io.logicalCompletion.valid.expect(true.B)
          dut.io.terminalError.expect(true.B)
        } else {
          dut.io.freeMask.valid.expect(false.B)
          dut.io.logicalCompletion.valid.expect(false.B)
        }
        dut.clock.step()
        dut.io.response.valid.poke(false.B)
      }

      dut.io.busy.expect(false.B)
      dut.io.acceptedRequestCount.expect(4.U)
    }
  }

  test("cacheable and malformed batches never enter the serializer") {
    simulate(new STQCommittedStoreSerializer(
      stqEntries = 8, robEntries = 8, issueWidth = 2)) { dut =>
      clear(dut)
      pokeBatch(dut, beats = 1, fragmentsPerBeat = 1,
        memoryClass = STQMemoryClass.NormalCacheable)
      dut.io.batchMalformed.expect(true.B)
      dut.io.batch.ready.expect(false.B)

      dut.io.batch.bits.memoryClass.poke(STQMemoryClass.DeviceMmio)
      dut.io.batch.bits.requests(0).stqIndex.poke(7.U)
      dut.io.batchMalformed.expect(true.B)
      dut.io.batch.ready.expect(false.B)

      pokeBatch(dut, beats = 1, fragmentsPerBeat = 2)
      dut.io.batch.bits.requests(0).ownsStqRow.poke(true.B)
      dut.io.batchMalformed.expect(true.B)
      dut.io.batch.ready.expect(false.B)
    }
  }

  test("serialized store response reuse requires the exact public generation") {
    simulate(new STQCommittedStoreSerializer(
      stqEntries = 8, robEntries = 8, issueWidth = 2,
      transactionIdWidth = 2, transactionGenerationWidth = 2)) { dut =>
      clear(dut)
      var firstValue = BigInt(0)
      var firstGeneration = BigInt(0)
      for (request <- 0 until 5) {
        pokeBatch(dut, beats = 1, fragmentsPerBeat = 1)
        dut.clock.step()
        dut.io.batch.valid.poke(false.B)
        val value = dut.io.request.bits.transactionId.peek().litValue
        val generation = dut.io.request.bits.transactionGeneration.peek().litValue
        if (request == 0) {
          firstValue = value
          firstGeneration = generation
        }
        if (request == 4) {
          assert(value == firstValue, "test must exercise value reuse")
          assert(generation != firstGeneration, "reused value needs a new generation")
        }
        dut.io.request.ready.poke(true.B)
        dut.clock.step()
        dut.io.request.ready.poke(false.B)

        dut.io.response.valid.poke(true.B)
        dut.io.response.bits.transactionId.poke(value.U)
        dut.io.response.bits.transactionGeneration.poke(
          (if (request == 4) firstGeneration else generation).U)
        if (request == 4) {
          dut.io.staleResponse.expect(true.B)
          dut.io.response.ready.expect(false.B)
          dut.clock.step()
          dut.io.response.bits.transactionGeneration.poke(generation.U)
        }
        dut.io.response.ready.expect(true.B)
        dut.clock.step()
        dut.io.response.valid.poke(false.B)
      }
    }
  }
}
