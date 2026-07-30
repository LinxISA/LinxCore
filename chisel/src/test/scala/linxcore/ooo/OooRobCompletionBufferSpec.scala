package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooRobCompletionBufferSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 2,
    robCompletionBufferEntries = 8,
    robGroupsPerStid = 8,
    robBankCount = 8,
    robRecoveryScanGroupsPerCycle = 8,
    robNonFlushScanGroupsPerCycle = 8,
    brobEntriesPerStid = 8,
    iexTerminalWidth = 2)

  private def clear(dut: OooRobCompletionBuffer): Unit = {
    dut.io.enqueues.foreach { port =>
      port.valid.poke(false.B)
      port.bits.poke(0.U.asTypeOf(port.bits))
    }
    dut.io.dequeue.ready.poke(false.B)
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
  }

  private def pokeCompletion(
      port: chisel3.util.DecoupledIO[OooRobMemberCompletion],
      stid: Int,
      slot: Int,
      member: Int,
      peId: Int = 3,
      generation: Int = 0,
      residentGeneration: Int = 1): Unit = {
    port.bits.poke(0.U.asTypeOf(port.bits))
    port.bits.key.group.valid.poke(true.B)
    port.bits.key.group.peId.poke(peId.U)
    port.bits.key.group.stid.poke(stid.U)
    port.bits.key.group.ridSlot.poke(slot.U)
    port.bits.key.group.ridGeneration.poke(generation.U)
    port.bits.key.bid.valid.poke(true.B)
    port.bits.key.bid.value.poke(slot.U)
    port.bits.key.brobGeneration.poke(0.U)
    port.bits.key.memberIndex.poke(member.U)
    port.bits.key.residentGeneration.poke(residentGeneration.U)
    port.valid.poke(true.B)
  }

  private def pokeRecovery(
      dut: OooRobCompletionBuffer,
      oldOccupied: Int,
      newOccupied: Int,
      pivotSlot: Int = 0,
      pivotMember: Int = 0,
      pivotPhysicalMembers: Int = 1,
      survivingPivotValid: Boolean = false,
      survivingPivotPhysicalMembers: Int = 0): Unit = {
    val plan = dut.io.recoveryPrepare.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.valid.poke(true.B)
    plan.oldHead.valid.poke(true.B)
    plan.oldHead.peId.poke(3.U)
    plan.oldHead.stid.poke(0.U)
    plan.oldHead.ridSlot.poke(0.U)
    plan.oldHead.ridGeneration.poke(0.U)
    plan.oldOccupied.poke(oldOccupied.U)
    plan.newOccupied.poke(newOccupied.U)
    plan.pivotOffset.poke(pivotSlot.U)
    plan.pivot.group.valid.poke(true.B)
    plan.pivot.group.peId.poke(3.U)
    plan.pivot.group.stid.poke(0.U)
    plan.pivot.group.ridSlot.poke(pivotSlot.U)
    plan.pivot.group.ridGeneration.poke(0.U)
    plan.pivot.bid.valid.poke(true.B)
    plan.pivot.bid.value.poke(pivotSlot.U)
    plan.pivot.brobGeneration.poke(0.U)
    plan.pivot.memberIndex.poke(pivotMember.U)
    plan.pivot.residentGeneration.poke(1.U)
    plan.pivotPhysicalMemberCount.poke(pivotPhysicalMembers.U)
    plan.survivingPivotValid.poke(survivingPivotValid.B)
    plan.survivingPivotPhysicalMemberCount.poke(
      survivingPivotPhysicalMembers.U)
    dut.io.recoveryPrepare.valid.poke(true.B)
  }

  test("accepts every terminal producer together and drains in lane order") {
    simulate(new OooRobCompletionBuffer(p)) { dut =>
      clear(dut)
      for (lane <- 0 until p.robCompletionInputWidth) {
        pokeCompletion(dut.io.enqueues(lane), stid = lane % 2,
          slot = lane, member = 0)
        dut.io.enqueues(lane).ready.expect(true.B)
      }
      dut.io.enqueueCount.expect(3.U)
      dut.clock.step()

      dut.io.enqueues.foreach(_.valid.poke(false.B))
      dut.io.used.expect(3.U)
      dut.io.dequeue.valid.expect(true.B)
      dut.io.dequeue.bits.key.group.ridSlot.expect(0.U)
      dut.clock.step(2)
      dut.io.used.expect(3.U)
      dut.io.dequeue.bits.key.group.ridSlot.expect(0.U)

      dut.io.dequeue.ready.poke(true.B)
      for (expected <- 0 until p.robCompletionInputWidth) {
        dut.io.dequeue.valid.expect(true.B)
        dut.io.dequeue.bits.key.group.ridSlot.expect(expected.U)
        dut.clock.step()
      }
      dut.io.empty.expect(true.B)
      dut.io.used.expect(0.U)
    }
  }

  test("recovery removes only a killed target-STID suffix and preserves FIFO order") {
    simulate(new OooRobCompletionBuffer(p)) { dut =>
      clear(dut)
      pokeCompletion(dut.io.enqueues(0), stid = 0, slot = 0, member = 0)
      pokeCompletion(dut.io.enqueues(1), stid = 1, slot = 0, member = 0)
      pokeCompletion(dut.io.enqueues(2), stid = 0, slot = 1, member = 0)
      dut.clock.step()
      dut.io.enqueues.foreach(_.valid.poke(false.B))

      pokeRecovery(dut, oldOccupied = 2, newOccupied = 1)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.io.recoveryPrepared.retained.expect(2.U)
      dut.io.recoveryPrepared.killed.expect(1.U)
      dut.io.dequeue.valid.expect(false.B)
      dut.io.enqueues.foreach(_.ready.expect(false.B))
      dut.clock.step(2)
      dut.io.used.expect(3.U)

      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.used.expect(2.U)

      dut.io.dequeue.ready.poke(true.B)
      dut.io.dequeue.bits.key.group.stid.expect(0.U)
      dut.io.dequeue.bits.key.group.ridSlot.expect(0.U)
      dut.clock.step()
      dut.io.dequeue.bits.key.group.stid.expect(1.U)
      dut.io.dequeue.bits.key.group.ridSlot.expect(0.U)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }

  test("recovery compacts a killed partial-pivot member and rejects stale rows") {
    simulate(new OooRobCompletionBuffer(p)) { dut =>
      clear(dut)
      pokeCompletion(dut.io.enqueues(0), stid = 0, slot = 0, member = 0)
      pokeCompletion(dut.io.enqueues(1), stid = 0, slot = 0, member = 1)
      dut.clock.step()
      dut.io.enqueues.foreach(_.valid.poke(false.B))

      pokeRecovery(dut, oldOccupied = 1, newOccupied = 1,
        pivotPhysicalMembers = 2, survivingPivotValid = true,
        survivingPivotPhysicalMembers = 1)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.killed.expect(1.U)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.used.expect(1.U)
      dut.io.dequeue.bits.key.memberIndex.expect(0.U)

      dut.io.dequeue.ready.poke(true.B)
      dut.clock.step()
      dut.io.dequeue.ready.poke(false.B)
      pokeCompletion(dut.io.enqueues(0), stid = 0, slot = 3, member = 0)
      dut.clock.step()
      dut.io.enqueues(0).valid.poke(false.B)

      pokeRecovery(dut, oldOccupied = 1, newOccupied = 0)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.valid.expect(true.B)
      assert(dut.io.recoveryRejected.bits.malformedMask.peek().litValue != 0)
      dut.io.used.expect(1.U)
      dut.clock.step()
      dut.io.used.expect(1.U)
    }
  }
}
