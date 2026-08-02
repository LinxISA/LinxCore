package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.ParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class OooMemoryOrderAllocatorSpec extends AnyFunSuite with ChiselSim {
  private val base = ParamProfiles.W2
  private val p = base.copy(lsidWidth = 40,
    ooo = base.ooo.copy(stidCount = 2))

  private def clear(dut: OooMemoryOrderAllocator): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.reserveFire.poke(false.B)
    dut.io.publishPrepare.valid.poke(false.B)
    dut.io.publishPrepare.bits.poke(0.U.asTypeOf(dut.io.publishPrepare.bits))
    dut.io.publishFire.poke(false.B)
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
  }

  private def prepare(dut: OooMemoryOrderAllocator, stid: Int,
      lanes: Seq[(Boolean, Boolean, Int)]): Unit = {
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.prepare.bits.count.poke(lanes.size.U)
    lanes.zipWithIndex.foreach { case ((load, store, count), lane) =>
      val row = dut.io.prepare.bits.entries(lane).uop
      row.valid.poke(true.B)
      row.rob.stid.poke(stid.U)
      row.memory.valid.poke((load || store).B)
      row.memory.isLoad.poke(load.B)
      row.memory.isStore.poke(store.B)
      row.memory.requestCount.poke(count.U)
    }
    dut.io.prepare.valid.poke(true.B)
  }

  private def reserve(dut: OooMemoryOrderAllocator): Unit = {
    dut.io.prepareReady.expect(true.B)
    dut.io.reserveFire.poke(true.B)
    dut.clock.step()
    dut.io.reserveFire.poke(false.B)
    dut.io.prepare.valid.poke(false.B)
  }

  private def publish(dut: OooMemoryOrderAllocator, stid: Int): Unit = {
    dut.io.publishPrepare.bits.poke(0.U.asTypeOf(dut.io.publishPrepare.bits))
    dut.io.publishPrepare.bits.count.poke(dut.io.provisional(stid).count.peek())
    dut.io.publishPrepare.bits.entries(0).uop.decoded.rob.stid.poke(stid.U)
    dut.io.publishPrepare.bits.memoryOrder.poke(dut.io.provisional(stid).peek())
    for (lane <- 0 until p.ooo.d3PrefixWidth) {
      dut.io.publishPrepare.bits.entries(lane).memoryOrder.poke(
        dut.io.provisionalLanes(stid)(lane).peek())
    }
    dut.io.publishPrepare.valid.poke(true.B)
    dut.io.publishReady.expect(true.B)
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.publishFire.poke(false.B)
    dut.io.publishPrepare.valid.poke(false.B)
  }

  test("allocates one mixed prefix from DEC-normalized requestCount") {
    simulate(new OooMemoryOrderAllocator(p)) { dut =>
      clear(dut)
      prepare(dut, 0, Seq((true, false, 1), (false, true, 2)))
      dut.io.preparedLanes(0).requestCount.expect(1.U)
      dut.io.preparedLanes(0).firstLsid.expect(0.U)
      dut.io.preparedLanes(0).firstLid.expect(0.U)
      dut.io.preparedLanes(0).firstSid.expect(0.U)
      dut.io.preparedLanes(0).yostValid.expect(false.B)
      dut.io.preparedLanes(0).yoldValid.expect(false.B)
      dut.io.preparedLanes(1).requestCount.expect(2.U)
      dut.io.preparedLanes(1).firstLsid.expect(1.U)
      dut.io.preparedLanes(1).firstLid.expect(1.U)
      dut.io.preparedLanes(1).firstSid.expect(0.U)
      dut.io.preparedLanes(1).yostValid.expect(false.B)
      dut.io.preparedLanes(1).yoldValid.expect(true.B)
      dut.io.preparedLanes(1).yoldLsid.expect(0.U)
      dut.io.preparedLanes(1).yoldLid.expect(0.U)
      reserve(dut)
      dut.io.next(0).lsid.expect(3.U)
      dut.io.next(0).lid.expect(1.U)
      dut.io.next(0).sid.expect(2.U)
      dut.io.next(0).yostValid.expect(true.B)
      dut.io.next(0).yostLsid.expect(2.U)
      dut.io.next(0).yostSid.expect(1.U)
      dut.io.next(0).yoldValid.expect(true.B)
      dut.io.next(0).yoldLsid.expect(0.U)
      dut.io.next(0).yoldLid.expect(0.U)
    }
  }

  test("rejects inconsistent normalized memory shape") {
    simulate(new OooMemoryOrderAllocator(p)) { dut =>
      clear(dut)
      prepare(dut, 0, Seq((false, false, 1)))
      dut.io.prepareReady.expect(false.B)
      dut.io.next(0).lsid.expect(0.U)
    }
  }

  test("cancel rewinds only an unpublished suffix and permits replacement") {
    simulate(new OooMemoryOrderAllocator(p)) { dut =>
      clear(dut)
      prepare(dut, 0, Seq((true, false, 1)))
      reserve(dut)
      dut.io.cancel(0).poke(true.B)
      dut.clock.step()
      dut.io.cancel(0).poke(false.B)
      dut.io.next(0).lsid.expect(0.U)
      prepare(dut, 0, Seq((false, true, 2)))
      reserve(dut)
      publish(dut, 0)
      dut.io.next(0).lsid.expect(2.U)
    }
  }

  test("recovery preview and abort do not mutate while apply restores target only") {
    simulate(new OooMemoryOrderAllocator(p)) { dut =>
      clear(dut)
      for (stid <- 0 to 1) {
        prepare(dut, stid, Seq((true, false, 1)))
        reserve(dut); publish(dut, stid)
      }
      dut.io.recoveryPrepare.bits.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.stid.poke(0.U)
      dut.io.recoveryPrepare.bits.oldTail.poke(dut.io.next(0).peek())
      dut.io.recoveryPrepare.bits.newTail.poke(
        0.U.asTypeOf(dut.io.recoveryPrepare.bits.newTail))
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.clock.step(2) // Prepare then Abort/no terminal fire: no mutation.
      dut.io.next(0).lsid.expect(1.U)
      dut.io.next(1).lsid.expect(1.U)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.next(0).lsid.expect(0.U)
      dut.io.next(1).lsid.expect(1.U)
    }
  }
}
