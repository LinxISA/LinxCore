package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

private object OooMemoryOrderAllocatorSpec {
  final case class MemoryUop(load: Boolean, store: Boolean, requests: Int)
}

class OooMemoryOrderAllocatorSpec extends AnyFunSuite with ChiselSim {
  import OooMemoryOrderAllocatorSpec.MemoryUop

  private def clear(dut: OooMemoryOrderAllocator): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.reserveFire.poke(false.B)
    dut.io.publishPrepare.valid.poke(false.B)
    dut.io.publishPrepare.bits.poke(
      0.U.asTypeOf(dut.io.publishPrepare.bits))
    dut.io.publishFire.poke(false.B)
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
  }

  private def pokeTransaction(
      dut: OooMemoryOrderAllocator,
      stid: Int,
      transactionId: Int,
      uops: Seq[MemoryUop],
      peId: Int = 3,
      epoch: Int = 7): Unit = {
    val transaction = dut.io.prepare.bits
    transaction.poke(0.U.asTypeOf(transaction))
    transaction.plan.peId.poke(peId.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(epoch.U)
    transaction.plan.transactionId.poke(transactionId.U)
    transaction.plan.uopMask.poke(((BigInt(1) << uops.size) - 1).U)
    transaction.decoded.peId.poke(peId.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(epoch.U)
    transaction.decoded.uopMask.poke(((BigInt(1) << uops.size) - 1).U)
    val loadIds = uops.filter(_.load).map(_.requests).sum
    val storeIds = uops.filter(_.store).map(_.requests).sum
    transaction.plan.demand.loadIds.poke(loadIds.U)
    transaction.plan.demand.storeIds.poke(storeIds.U)
    transaction.decoded.demand.loadIds.poke(loadIds.U)
    transaction.decoded.demand.storeIds.poke(storeIds.U)

    uops.zipWithIndex.foreach { case (shape, index) =>
      val uop = transaction.decoded.uops(index)
      uop.valid.poke(true.B)
      uop.recipe.valid.poke(true.B)
      uop.recipe.memoryRequestCount.poke(shape.requests.U)
      uop.memory.valid.poke((shape.load || shape.store).B)
      uop.memory.isLoad.poke(shape.load.B)
      uop.memory.isStore.poke(shape.store.B)
    }
    dut.io.prepare.valid.poke(true.B)
  }

  private def reserve(dut: OooMemoryOrderAllocator): Unit = {
    dut.io.prepareReady.expect(true.B)
    dut.io.prepared.valid.expect(true.B)
    dut.io.reserveFire.poke(true.B)
    dut.clock.step()
    dut.io.reserveFire.poke(false.B)
    dut.io.prepare.valid.poke(false.B)
  }

  private def publish(
      dut: OooMemoryOrderAllocator,
      stid: Int,
      transactionId: Int,
      peId: Int = 3,
      epoch: Int = 7): Unit = {
    val reservation = dut.io.publishPrepare.bits
    reservation.poke(0.U.asTypeOf(reservation))
    reservation.transaction.plan.peId.poke(peId.U)
    reservation.transaction.plan.stid.poke(stid.U)
    reservation.transaction.plan.epoch.poke(epoch.U)
    reservation.transaction.plan.transactionId.poke(transactionId.U)
    reservation.transaction.plan.uopMask.poke(
      dut.io.provisional(stid).uopMask.peek())
    dut.io.publishPrepare.valid.poke(true.B)
    dut.io.publishReady.expect(true.B)
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.publishFire.poke(false.B)
    dut.io.publishPrepare.valid.poke(false.B)
  }

  test("assigns unified and type-local full serial ranges atomically") {
    val p = OooParams(stidCount = 2, instructionDecodeWidth = 4,
      decodedUopWidth = 4, renameWidth = 4, dispatchWidth = 4,
      retireGroupWidth = 2, lsidWidth = 40)
    simulate(new OooMemoryOrderAllocator(p)) { dut =>
      clear(dut)
      dut.clock.step()

      pokeTransaction(dut, stid = 1, transactionId = 0, Seq(
        MemoryUop(load = true, store = false, requests = 1),
        MemoryUop(load = false, store = false, requests = 0),
        MemoryUop(load = false, store = true, requests = 1),
        MemoryUop(load = false, store = true, requests = 2)))

      dut.io.prepared.before.lsid.expect(0.U)
      dut.io.prepared.after.lsid.expect(4.U)
      dut.io.prepared.after.loadId.expect(1.U)
      dut.io.prepared.after.storeId.expect(3.U)
      dut.io.prepared.uops(0).firstLsid.expect(0.U)
      dut.io.prepared.uops(0).firstTypeId.expect(0.U)
      dut.io.prepared.uops(1).memoryValid.expect(false.B)
      dut.io.prepared.uops(1).before.lsid.expect(1.U)
      dut.io.prepared.uops(1).after.lsid.expect(1.U)
      dut.io.prepared.uops(2).firstLsid.expect(1.U)
      dut.io.prepared.uops(2).firstTypeId.expect(0.U)
      dut.io.prepared.uops(2).before.youngestStoreLsidValid.expect(false.B)
      dut.io.prepared.uops(2).after.youngestStoreLsidValid.expect(true.B)
      dut.io.prepared.uops(2).after.youngestStoreLsid.expect(1.U)
      dut.io.prepared.uops(3).firstLsid.expect(2.U)
      dut.io.prepared.uops(3).firstTypeId.expect(1.U)
      dut.io.prepared.uops(3).before.youngestStoreLsid.expect(1.U)
      dut.io.prepared.uops(3).after.youngestStoreLsid.expect(3.U)

      reserve(dut)
      dut.io.provisional(1).valid.expect(true.B)
      dut.io.next(1).lsid.expect(4.U)
      dut.io.next(1).loadId.expect(1.U)
      dut.io.next(1).storeId.expect(3.U)
      dut.io.next(1).youngestStoreLsidValid.expect(true.B)
      dut.io.next(1).youngestStoreLsid.expect(3.U)
      dut.io.next(0).lsid.expect(0.U)

      publish(dut, stid = 1, transactionId = 0)
      dut.io.provisional(1).valid.expect(false.B)
      dut.io.next(1).lsid.expect(4.U)
    }
  }

  test("cancel rolls back only the exact provisional STID suffix") {
    val p = OooParams(stidCount = 2, instructionDecodeWidth = 2,
      decodedUopWidth = 2, renameWidth = 2, dispatchWidth = 2,
      retireGroupWidth = 2)
    simulate(new OooMemoryOrderAllocator(p)) { dut =>
      clear(dut)
      dut.clock.step()

      pokeTransaction(dut, stid = 0, transactionId = 0,
        Seq(MemoryUop(load = true, store = false, requests = 1)))
      reserve(dut)
      dut.io.next(0).lsid.expect(1.U)
      dut.io.cancel(0).poke(true.B)
      dut.clock.step()
      dut.io.cancel(0).poke(false.B)
      dut.io.next(0).lsid.expect(0.U)
      dut.io.provisional(0).valid.expect(false.B)
      dut.io.next(1).lsid.expect(0.U)
    }
  }

  test("same-cycle publication replaces the exact STID lease without a bubble") {
    val p = OooParams(stidCount = 2, instructionDecodeWidth = 2,
      decodedUopWidth = 2, renameWidth = 2, dispatchWidth = 2,
      retireGroupWidth = 2)
    simulate(new OooMemoryOrderAllocator(p)) { dut =>
      clear(dut)
      dut.clock.step()

      pokeTransaction(dut, stid = 0, transactionId = 0,
        Seq(MemoryUop(load = true, store = false, requests = 1)))
      reserve(dut)

      pokeTransaction(dut, stid = 0, transactionId = 1,
        Seq(MemoryUop(load = false, store = true, requests = 1)))
      val outgoing = dut.io.publishPrepare.bits
      outgoing.poke(0.U.asTypeOf(outgoing))
      outgoing.transaction.plan.peId.poke(3.U)
      outgoing.transaction.plan.stid.poke(0.U)
      outgoing.transaction.plan.epoch.poke(7.U)
      outgoing.transaction.plan.transactionId.poke(0.U)
      outgoing.transaction.plan.uopMask.poke(1.U)
      dut.io.publishPrepare.valid.poke(true.B)
      dut.io.publishReady.expect(true.B)
      dut.io.publishFire.poke(true.B)
      dut.io.prepareReady.expect(true.B)
      dut.io.prepared.before.lsid.expect(1.U)
      dut.io.prepared.after.lsid.expect(2.U)
      dut.io.reserveFire.poke(true.B)
      dut.clock.step()
      dut.io.reserveFire.poke(false.B)
      dut.io.publishFire.poke(false.B)
      dut.io.publishPrepare.valid.poke(false.B)
      dut.io.prepare.valid.poke(false.B)

      dut.io.provisional(0).valid.expect(true.B)
      dut.io.provisional(0).transactionId.expect(1.U)
      dut.io.provisional(0).before.lsid.expect(1.U)
      dut.io.provisional(0).after.lsid.expect(2.U)
      dut.io.next(0).lsid.expect(2.U)
      dut.io.next(0).loadId.expect(1.U)
      dut.io.next(0).storeId.expect(1.U)
    }
  }

  test("rejects demand or typed-memory mismatch without claiming serials") {
    val p = OooParams(stidCount = 2, instructionDecodeWidth = 2,
      decodedUopWidth = 2, renameWidth = 2, dispatchWidth = 2,
      retireGroupWidth = 2)
    simulate(new OooMemoryOrderAllocator(p)) { dut =>
      clear(dut)
      dut.clock.step()

      pokeTransaction(dut, stid = 0, transactionId = 0,
        Seq(MemoryUop(load = true, store = false, requests = 1)))
      dut.io.prepare.bits.plan.demand.loadIds.poke(2.U)
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.valid.expect(true.B)
      dut.io.reserveFire.poke(false.B)
      dut.clock.step()
      dut.io.next(0).lsid.expect(0.U)
      dut.io.provisional(0).valid.expect(false.B)

      dut.io.prepare.bits.plan.demand.loadIds.poke(1.U)
      dut.io.prepare.bits.decoded.uops(0).memory.isLoad.poke(false.B)
      dut.io.prepareReady.expect(false.B)
      dut.io.next(0).lsid.expect(0.U)
    }
  }

  test("global recovery restores the surviving ROB memory tail and kills a provisional suffix") {
    val p = OooParams(stidCount = 2, instructionDecodeWidth = 2,
      decodedUopWidth = 2, renameWidth = 2, dispatchWidth = 2,
      retireGroupWidth = 2)
    simulate(new OooMemoryOrderAllocator(p)) { dut =>
      clear(dut)
      dut.clock.step()

      pokeTransaction(dut, stid = 0, transactionId = 0, Seq(
        MemoryUop(load = true, store = false, requests = 1),
        MemoryUop(load = false, store = true, requests = 2)))
      reserve(dut)
      publish(dut, stid = 0, transactionId = 0)
      dut.io.next(0).lsid.expect(3.U)

      pokeTransaction(dut, stid = 0, transactionId = 1,
        Seq(MemoryUop(load = true, store = false, requests = 1)))
      reserve(dut)
      dut.io.next(0).lsid.expect(4.U)

      val plan = dut.io.recoveryPrepare.bits
      plan.poke(0.U.asTypeOf(plan))
      plan.valid.poke(true.B)
      plan.request.rename.key.member.group.valid.poke(true.B)
      plan.request.rename.key.member.group.stid.poke(0.U)
      plan.oldOccupied.poke(2.U)
      plan.newOccupied.poke(1.U)
      plan.killedGroupCount.poke(1.U)
      plan.pivot.valid.poke(true.B)
      plan.pivot.memoryOrderValid.poke(true.B)
      plan.pivot.memoryBefore.lsid.poke(0.U)
      plan.pivot.memoryAfter.lsid.poke(1.U)
      plan.pivot.memoryAfter.loadId.poke(1.U)
      plan.survivingTailValid.poke(true.B)
      plan.survivingTail.valid.poke(true.B)
      plan.survivingTail.memoryOrderValid.poke(true.B)
      plan.survivingTail.memoryBefore.lsid.poke(0.U)
      plan.survivingTail.memoryAfter.lsid.poke(1.U)
      plan.survivingTail.memoryAfter.loadId.poke(1.U)
      plan.killedGroups(0).valid.poke(true.B)
      plan.killedGroups(0).memoryOrderValid.poke(true.B)
      plan.killedGroups(0).memoryBefore.lsid.poke(1.U)
      plan.killedGroups(0).memoryBefore.loadId.poke(1.U)
      plan.killedGroups(0).memoryAfter.lsid.poke(3.U)
      plan.killedGroups(0).memoryAfter.loadId.poke(1.U)
      plan.killedGroups(0).memoryAfter.storeId.poke(2.U)
      plan.killedGroups(0).memoryAfter.youngestStoreLsidValid.poke(true.B)
      plan.killedGroups(0).memoryAfter.youngestStoreLsid.poke(2.U)
      dut.io.recoveryPrepare.valid.poke(true.B)

      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.provisionalKilled.expect(true.B)
      dut.io.recoveryPrepared.oldPublishedTail.lsid.expect(3.U)
      dut.io.recoveryPrepared.newTail.lsid.expect(1.U)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.next(0).lsid.expect(1.U)
      dut.io.next(0).loadId.expect(1.U)
      dut.io.next(0).storeId.expect(0.U)
      dut.io.provisional(0).valid.expect(false.B)
    }
  }

  test("partial pivot recovery distinguishes the old published tail from the trimmed tail") {
    val p = OooParams(stidCount = 2, instructionDecodeWidth = 2,
      decodedUopWidth = 2, renameWidth = 2, dispatchWidth = 2,
      retireGroupWidth = 2)
    simulate(new OooMemoryOrderAllocator(p)) { dut =>
      clear(dut)
      dut.clock.step()

      pokeTransaction(dut, stid = 0, transactionId = 0, Seq(
        MemoryUop(load = true, store = false, requests = 1),
        MemoryUop(load = false, store = true, requests = 1)))
      reserve(dut)
      publish(dut, stid = 0, transactionId = 0)
      dut.io.next(0).lsid.expect(2.U)

      val plan = dut.io.recoveryPrepare.bits
      plan.poke(0.U.asTypeOf(plan))
      plan.valid.poke(true.B)
      plan.request.rename.key.member.group.valid.poke(true.B)
      plan.request.rename.key.member.group.stid.poke(0.U)
      plan.oldOccupied.poke(1.U)
      plan.newOccupied.poke(1.U)
      plan.killedGroupCount.poke(0.U)
      plan.pivot.valid.poke(true.B)
      plan.pivot.memoryOrderValid.poke(true.B)
      plan.pivot.memoryAfter.lsid.poke(2.U)
      plan.pivot.memoryAfter.loadId.poke(1.U)
      plan.pivot.memoryAfter.storeId.poke(1.U)
      plan.pivot.memoryAfter.youngestStoreLsidValid.poke(true.B)
      plan.pivot.memoryAfter.youngestStoreLsid.poke(1.U)
      plan.survivingPivotValid.poke(true.B)
      plan.survivingTailValid.poke(true.B)
      plan.survivingTail.valid.poke(true.B)
      plan.survivingTail.memoryOrderValid.poke(true.B)
      plan.survivingTail.memoryAfter.lsid.poke(1.U)
      plan.survivingTail.memoryAfter.loadId.poke(1.U)
      dut.io.recoveryPrepare.valid.poke(true.B)

      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.oldPublishedTail.lsid.expect(2.U)
      dut.io.recoveryPrepared.newTail.lsid.expect(1.U)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.next(0).lsid.expect(1.U)
      dut.io.next(0).loadId.expect(1.U)
      dut.io.next(0).storeId.expect(0.U)
    }
  }
}
