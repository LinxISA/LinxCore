package linxcore.ctu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.ParamProfiles
import linxcore.top.interface.{D1Packet, FrontEndOpKind, RecoveryCause, RecoveryPhase}
import org.scalatest.funsuite.AnyFunSuite

class InstructionBufferSpec extends AnyFunSuite with ChiselSim {
  private val p = ParamProfiles.W4

  private def clear(dut: InstructionBuffer): Unit = {
    dut.io.enq.valid.poke(false.B)
    dut.io.enq.bits.poke(0.U.asTypeOf(dut.io.enq.bits))
    dut.io.deq.ready.poke(false.B)
    dut.io.prune.valid.poke(false.B)
    dut.io.prune.bits.poke(0.U.asTypeOf(dut.io.prune.bits))
  }

  private def pokePacket(
      packet: D1Packet,
      ids: Seq[Int],
      stid: Int = 0,
      epoch: Int = 1): Unit = {
    packet.poke(0.U.asTypeOf(packet))
    packet.count.poke(ids.size.U)
    ids.zipWithIndex.foreach { case (id, lane) =>
      packet.entries(lane).kind.poke(FrontEndOpKind.Encoded64)
      packet.entries(lane).parent.identity.stid.poke(stid.U)
      packet.entries(lane).parent.identity.instructionId.poke(id.U)
      packet.entries(lane).parent.identity.epoch.poke(epoch.U)
      packet.entries(lane).parent.instruction.poke((0x1000 + id).U)
      packet.entries(lane).parent.prediction.transactionId.poke((0x2000 + id).U)
    }
  }

  private def enqueue(
      dut: InstructionBuffer,
      ids: Seq[Int],
      stid: Int = 0,
      epoch: Int = 1): Unit = {
    pokePacket(dut.io.enq.bits, ids, stid, epoch)
    dut.io.enq.valid.poke(true.B)
    dut.io.enq.ready.expect(true.B)
    dut.clock.step()
    dut.io.enq.valid.poke(false.B)
  }

  test("retains a dense prefix and keeps every payload bit stable under backpressure") {
    simulate(new InstructionBuffer(p, depth = 8)) { dut =>
      clear(dut)
      enqueue(dut, Seq(10, 11, 12, 13))

      dut.io.deq.valid.expect(true.B)
      dut.io.deq.bits.count.expect(4.U)
      val heldCount = dut.io.deq.bits.count.peek().litValue
      val heldIds = dut.io.deq.bits.entries.map(
        _.parent.identity.instructionId.peek().litValue)
      val heldInstructions = dut.io.deq.bits.entries.map(
        _.parent.instruction.peek().litValue)
      dut.clock.step(3)
      dut.io.deq.bits.count.expect(heldCount.U)
      heldIds.zipWithIndex.foreach { case (id, lane) =>
        dut.io.deq.bits.entries(lane).parent.identity.instructionId.expect(id.U)
      }
      heldInstructions.zipWithIndex.foreach { case (instruction, lane) =>
        dut.io.deq.bits.entries(lane).parent.instruction.expect(instruction.U)
      }

      dut.io.deq.ready.poke(true.B)
      dut.clock.step()
      dut.io.deq.valid.expect(false.B)
      dut.io.occupancy.expect(0.U)
    }
  }

  test("uses pre-cycle free space so downstream ready never bypasses to enqueue ready") {
    simulate(new InstructionBuffer(p, depth = 4)) { dut =>
      clear(dut)
      enqueue(dut, Seq(1, 2, 3, 4))

      pokePacket(dut.io.enq.bits, Seq(5))
      dut.io.enq.valid.poke(true.B)
      dut.io.deq.ready.poke(false.B)
      dut.io.enq.ready.expect(false.B)
      dut.io.deq.ready.poke(true.B)
      dut.io.enq.ready.expect(false.B)

      dut.clock.step()
      dut.io.enq.ready.expect(true.B)
    }
  }

  test("compacts only the recovering STID and preserves unrelated FIFO order") {
    simulate(new InstructionBuffer(p, depth = 12)) { dut =>
      clear(dut)
      enqueue(dut, Seq(10, 11), stid = 0)
      enqueue(dut, Seq(20, 21), stid = 1)
      enqueue(dut, Seq(12), stid = 0)

      dut.io.prune.bits.poke(0.U.asTypeOf(dut.io.prune.bits))
      dut.io.prune.bits.transactionId.poke(77.U)
      dut.io.prune.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.prune.bits.cause.poke(RecoveryCause.Branch)
      dut.io.prune.bits.trigger.stid.poke(0.U)
      dut.io.prune.bits.newEpoch.poke(2.U)
      dut.io.prune.valid.poke(true.B)
      dut.io.deq.ready.poke(true.B)
      dut.io.deq.valid.expect(false.B)
      dut.clock.step()
      dut.io.prune.valid.poke(false.B)

      dut.io.deq.valid.expect(true.B)
      dut.io.deq.bits.count.expect(2.U)
      dut.io.deq.bits.entries(0).parent.identity.instructionId.expect(20.U)
      dut.io.deq.bits.entries(1).parent.identity.instructionId.expect(21.U)
      dut.io.occupancy.expect(2.U)
    }
  }

  test("accepts and drains simultaneous independent prefixes without reordering") {
    simulate(new InstructionBuffer(p, depth = 8)) { dut =>
      clear(dut)
      enqueue(dut, Seq(1, 2))

      pokePacket(dut.io.enq.bits, Seq(3, 4))
      dut.io.enq.valid.poke(true.B)
      dut.io.deq.ready.poke(true.B)
      dut.io.deq.bits.entries(0).parent.identity.instructionId.expect(1.U)
      dut.io.deq.bits.entries(1).parent.identity.instructionId.expect(2.U)
      dut.clock.step()
      dut.io.enq.valid.poke(false.B)

      dut.io.deq.bits.entries(0).parent.identity.instructionId.expect(3.U)
      dut.io.deq.bits.entries(1).parent.identity.instructionId.expect(4.U)
    }
  }
}
