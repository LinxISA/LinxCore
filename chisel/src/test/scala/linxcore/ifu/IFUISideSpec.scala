package linxcore.ifu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.params.ParamProfiles
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class IFUISideSpec extends AnyFunSuite with ChiselSim {
  private def clearFetchBuffer(dut: FetchBuffer): Unit = {
    dut.io.enq.valid.poke(false.B)
    dut.io.enq.bits.poke(0.U.asTypeOf(dut.io.enq.bits))
    dut.io.deq.ready.poke(false.B)
    dut.io.prune.valid.poke(false.B)
    dut.io.prune.bits.poke(0.U.asTypeOf(dut.io.prune.bits))
  }

  private def enqueueFour(
      dut: FetchBuffer,
      baseId: Int,
      stid: Int,
      lengths: Seq[Int] = Seq(2, 4, 6, 8)): Unit = {
    dut.io.enq.bits.poke(0.U.asTypeOf(dut.io.enq.bits))
    dut.io.enq.bits.count.poke(4.U)
    for (lane <- 0 until 4) {
      dut.io.enq.bits.entries(lane).identity.stid.poke(stid.U)
      dut.io.enq.bits.entries(lane).identity.instructionId.poke((baseId + lane).U)
      dut.io.enq.bits.entries(lane).pc.poke((0x1000 + (baseId + lane) * 2).U)
      dut.io.enq.bits.entries(lane).instruction.poke((0x100 + baseId + lane).U)
      dut.io.enq.bits.entries(lane).lengthBytes.poke(lengths(lane).U)
    }
    dut.io.enq.valid.poke(true.B)
    dut.io.enq.ready.expect(true.B)
    dut.clock.step()
    dut.io.enq.valid.poke(false.B)
  }

  test("repacketizes the retained instruction stream for W2 W4 W6 and W8") {
    Seq(2, 4, 6, 8).foreach { width =>
      val p = ParamProfiles.forWidth(width)
      simulate(new FetchBuffer(p, ingressWidth = 4, depth = 16)) { dut =>
        clearFetchBuffer(dut)
        enqueueFour(dut, baseId = 10, stid = 0)

        dut.io.deq.valid.expect(true.B)
        dut.io.deq.bits.count.expect(math.min(width, 4).U)
        for (lane <- 0 until math.min(width, 4)) {
          dut.io.deq.bits.entries(lane).identity.instructionId.expect((10 + lane).U)
          dut.io.deq.bits.entries(lane).lengthBytes.expect(Seq(2, 4, 6, 8)(lane).U)
        }

        val heldCount = dut.io.deq.bits.count.peek().litValue
        val heldIds = (0 until math.min(width, 4)).map(
          lane =>
            dut.io.deq.bits.entries(lane).identity.instructionId.peek().litValue)
        dut.clock.step(3)
        assert(dut.io.deq.bits.count.peek().litValue == heldCount)
        heldIds.zipWithIndex.foreach { case (id, lane) =>
          assert(
            dut.io.deq.bits.entries(lane).identity.instructionId.peek().litValue == id)
        }

        dut.io.deq.ready.poke(true.B)
        dut.clock.step()
        if (width == 2) {
          dut.io.deq.valid.expect(true.B)
          dut.io.deq.bits.count.expect(2.U)
          dut.io.deq.bits.entries(0).identity.instructionId.expect(12.U)
          dut.io.deq.bits.entries(1).identity.instructionId.expect(13.U)
        } else {
          dut.io.deq.valid.expect(false.B)
        }
      }
    }
  }

  test("scoped recovery removes only the selected STID and preserves survivor order") {
    val base = ParamProfiles.W4
    val p = base.copy(ooo = base.ooo.copy(stidCount = 2))
    simulate(new FetchBuffer(p, ingressWidth = 4, depth = 16)) { dut =>
      clearFetchBuffer(dut)
      enqueueFour(dut, baseId = 20, stid = 0)
      enqueueFour(dut, baseId = 30, stid = 1)

      dut.io.prune.bits.poke(0.U.asTypeOf(dut.io.prune.bits))
      dut.io.prune.bits.trigger.stid.poke(0.U)
      dut.io.prune.valid.poke(true.B)
      dut.io.deq.valid.expect(false.B)
      dut.clock.step()
      dut.io.prune.valid.poke(false.B)

      dut.io.deq.valid.expect(true.B)
      dut.io.deq.bits.count.expect(4.U)
      for (lane <- 0 until 4) {
        dut.io.deq.bits.entries(lane).identity.stid.expect(1.U)
        dut.io.deq.bits.entries(lane).identity.instructionId.expect((30 + lane).U)
      }
    }
  }

  test("the canonical IFU elaborates every principal delivery width") {
    Seq(2, 4, 6, 8).foreach { width =>
      val sv =
        ChiselStage.emitSystemVerilog(new IFU(ParamProfiles.forWidth(width)))
      assert(sv.contains("module IFU"))
      assert(sv.contains("module FetchBuffer"))
    }
  }

  test("replays ITLB and I-cache misses, rejects a stale response, and assembles a cross-line instruction") {
    val base = ParamProfiles.W4
    val p = base.copy(
      ooo = base.ooo.copy(stidCount = 2),
      ifu = base.ifu.copy(
        lineBytes = 16,
        pageBytes = 256,
        itlbEntries = 4,
        l1iSets = 4,
        missEntries = 4,
        joinEntries = 4,
        maxGroupsPerTransaction = 4,
        resetVector = 0x10e))

    simulate(new IFU(p)) { dut =>
      clearIfu(dut)
      dut.io.memoryRequest.ready.poke(true.B)

      var staleInjected = false
      var cycles = 0
      while (!dut.io.toCtu.valid.peek().litToBoolean && cycles < 600) {
        if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          val request = dut.io.memoryRequest.bits.peek()
          val address = request.address.litValue
          val value = request.identity.value.litValue
          val generation = request.identity.generation.litValue
          val accessKind = request.accessKind.litValue
          dut.clock.step()

          if (!staleInjected) {
            dut.io.memoryResponse.valid.poke(true.B)
            dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
            dut.io.memoryResponse.bits.identity.value.poke((value + 1).U)
            dut.io.memoryResponse.bits.identity.generation.poke(generation.U)
            dut.io.memoryResponse.bits.data.poke(0xdead.U)
            dut.io.memoryResponse.ready.expect(true.B)
            dut.clock.step()
            dut.io.memoryResponse.valid.poke(false.B)
            staleInjected = true
          }

          val data =
            if (accessKind == MemoryAccessKind.InstructionTranslation.litValue) {
              BigInt(2)
            } else {
              if (address == BigInt(0x200)) {
                BigInt(0)
              } else if (address == BigInt(0x208)) {
                BigInt(1) << 48
              } else if (address == BigInt(0x210)) {
                BigInt(0x0010)
              } else if (address == BigInt(0x218)) {
                BigInt("0080007000600050", 16)
              } else {
                BigInt(0)
              }
            }
          dut.io.memoryResponse.valid.poke(true.B)
          dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
          dut.io.memoryResponse.bits.identity.value.poke(value.U)
          dut.io.memoryResponse.bits.identity.generation.poke(generation.U)
          dut.io.memoryResponse.bits.data.poke(data.U)
          dut.io.memoryResponse.ready.expect(true.B)
          dut.clock.step()
          dut.io.memoryResponse.valid.poke(false.B)
        } else {
          dut.clock.step()
        }
        cycles += 1
      }

      assert(staleInjected, "test never exercised stale memory-response rejection")
      assert(cycles < 600, "cross-line instruction did not reach CTU")
      dut.io.toCtu.bits.count.expect(1.U)
      dut.io.toCtu.bits.entries(0).pc.expect(0x10e.U)
      dut.io.toCtu.bits.entries(0).lengthBytes.expect(4.U)
      dut.io.toCtu.bits.entries(0).instruction.expect(0x00100001.U)
      dut.io.toCtu.bits.entries(0).identity.stid.expect(0.U)
      dut.io.memoryRequest.ready.poke(false.B)

      dut.io.recovery.prepare.bits.poke(
        0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepare.bits.transactionId.poke(77.U)
      dut.io.recovery.prepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recovery.prepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recovery.prepare.bits.trigger.stid.poke(1.U)
      dut.io.recovery.prepare.bits.redirectPc.poke(0x300.U)
      dut.io.recovery.prepare.bits.newEpoch.poke(9.U)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.io.recovery.prepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)

      dut.io.recovery.prepared.valid.expect(true.B)
      dut.io.recovery.prepared.bits.transactionId.expect(77.U)
      dut.io.toCtu.valid.expect(false.B)

      dut.io.recovery.apply.bits.poke(
        0.U.asTypeOf(dut.io.recovery.apply.bits))
      dut.io.recovery.apply.bits.transactionId.poke(77.U)
      dut.io.recovery.apply.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.recovery.apply.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recovery.apply.bits.trigger.stid.poke(1.U)
      dut.io.recovery.apply.bits.redirectPc.poke(0x300.U)
      dut.io.recovery.apply.bits.newEpoch.poke(9.U)
      dut.io.recovery.apply.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.toCtu.valid.expect(false.B)

      var recoveryCycles = 0
      while (!dut.io.recovery.prepare.ready.peek().litToBoolean &&
          recoveryCycles < 16) {
        dut.clock.step()
        recoveryCycles += 1
      }
      assert(recoveryCycles < 16, "applied IFU recovery did not release its fence")

      dut.io.toCtu.valid.expect(true.B)
      dut.io.toCtu.bits.entries(0).identity.stid.expect(0.U)
      dut.io.trace.ready.poke(false.B)
      dut.io.toCtu.ready.poke(true.B)
      dut.clock.step()

      dut.io.memoryRequest.ready.poke(true.B)
      var stidOneCycles = 0
      while ((!dut.io.toCtu.valid.peek().litToBoolean ||
          dut.io.toCtu.bits.entries(0).identity.stid.peek().litValue != 1) &&
          stidOneCycles < 600) {
        if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          val request = dut.io.memoryRequest.bits.peek()
          val address = request.address.litValue
          val value = request.identity.value.litValue
          val generation = request.identity.generation.litValue
          val accessKind = request.accessKind.litValue
          dut.clock.step()

          val data =
            if (accessKind == MemoryAccessKind.InstructionTranslation.litValue) {
              address >> 8
            } else {
              BigInt(0)
            }
          dut.io.memoryResponse.valid.poke(true.B)
          dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
          dut.io.memoryResponse.bits.identity.value.poke(value.U)
          dut.io.memoryResponse.bits.identity.generation.poke(generation.U)
          dut.io.memoryResponse.bits.data.poke(data.U)
          dut.io.memoryResponse.ready.expect(true.B)
          dut.clock.step()
          dut.io.memoryResponse.valid.poke(false.B)
        } else {
          dut.clock.step()
        }
        stidOneCycles += 1
      }
      assert(stidOneCycles < 600, "redirected STID did not make independent progress")
      dut.io.toCtu.bits.entries(0).identity.stid.expect(1.U)
      assert(dut.io.toCtu.bits.entries(0).pc.peek().litValue >= 0x300)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.entries(0).instruction.stid.expect(0.U)

      dut.io.memoryRequest.ready.poke(false.B)
      dut.io.toCtu.ready.poke(false.B)
      dut.io.trace.ready.poke(true.B)
      dut.io.recovery.prepare.bits.poke(
        0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepare.bits.transactionId.poke(78.U)
      dut.io.recovery.prepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recovery.prepare.bits.cause.poke(RecoveryCause.Exception)
      dut.io.recovery.prepare.bits.trigger.stid.poke(1.U)
      dut.io.recovery.prepare.bits.redirectPc.poke(0x400.U)
      dut.io.recovery.prepare.bits.newEpoch.poke(10.U)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.io.recovery.prepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)

      dut.io.recovery.apply.bits.poke(
        0.U.asTypeOf(dut.io.recovery.apply.bits))
      dut.io.recovery.apply.bits.transactionId.poke(78.U)
      dut.io.recovery.apply.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.recovery.apply.bits.cause.poke(RecoveryCause.Exception)
      dut.io.recovery.apply.bits.trigger.stid.poke(1.U)
      dut.io.recovery.apply.bits.redirectPc.poke(0x400.U)
      dut.io.recovery.apply.bits.newEpoch.poke(10.U)
      dut.io.recovery.apply.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)

      var faultRecoveryCycles = 0
      while (!dut.io.recovery.prepare.ready.peek().litToBoolean &&
          faultRecoveryCycles < 16) {
        dut.clock.step()
        faultRecoveryCycles += 1
      }
      assert(
        faultRecoveryCycles < 16,
        "fault-path recovery did not release its fence")

      dut.io.toCtu.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      var faultCycles = 0
      while ((!dut.io.toCtu.valid.peek().litToBoolean ||
          !dut.io.toCtu.bits.entries(0).fetchFault.peek().litToBoolean ||
          dut.io.toCtu.bits.entries(0).identity.stid.peek().litValue != 1) &&
          faultCycles < 600) {
        if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          val request = dut.io.memoryRequest.bits.peek()
          val address = request.address.litValue
          val value = request.identity.value.litValue
          val generation = request.identity.generation.litValue
          val accessKind = request.accessKind.litValue
          dut.clock.step()

          dut.io.memoryResponse.valid.poke(true.B)
          dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
          dut.io.memoryResponse.bits.identity.value.poke(value.U)
          dut.io.memoryResponse.bits.identity.generation.poke(generation.U)
          if (accessKind == MemoryAccessKind.InstructionTranslation.litValue) {
            dut.io.memoryResponse.bits.data.poke((address >> 8).U)
          } else if (address >= 0x400 && address < 0x410) {
            dut.io.memoryResponse.bits.denied.poke(true.B)
            dut.io.memoryResponse.bits.errorCause.poke(0x55.U)
          }
          dut.io.memoryResponse.ready.expect(true.B)
          dut.clock.step()
          dut.io.memoryResponse.valid.poke(false.B)
        } else {
          dut.clock.step()
        }
        faultCycles += 1
      }
      assert(faultCycles < 600, "instruction-line error did not become a fetch fault")
      dut.io.toCtu.bits.count.expect(1.U)
      dut.io.toCtu.bits.entries(0).identity.stid.expect(1.U)
      dut.io.toCtu.bits.entries(0).pc.expect(0x400.U)
      dut.io.toCtu.bits.entries(0).fetchFault.expect(true.B)
      dut.io.toCtu.bits.entries(0).fetchFaultCause.expect(0x55.U)
    }
  }

  private def clearIfu(dut: IFU): Unit = {
    dut.io.toCtu.ready.poke(false.B)
    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
    dut.io.recovery.prepare.valid.poke(false.B)
    dut.io.recovery.prepare.bits.poke(0.U.asTypeOf(dut.io.recovery.prepare.bits))
    dut.io.recovery.prepared.ready.poke(true.B)
    dut.io.recovery.apply.valid.poke(false.B)
    dut.io.recovery.apply.bits.poke(0.U.asTypeOf(dut.io.recovery.apply.bits))
    dut.io.recovery.abort.valid.poke(false.B)
    dut.io.recovery.abort.bits.poke(0.U.asTypeOf(dut.io.recovery.abort.bits))
    dut.io.trace.ready.poke(true.B)
  }
}
