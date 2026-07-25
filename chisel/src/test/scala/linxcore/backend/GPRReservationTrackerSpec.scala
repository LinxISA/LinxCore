package linxcore.backend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class GPRReservationTrackerSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: GPRReservationTracker): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.pushValid.poke(false.B)
    dut.io.pushStid.poke(0.U)
    dut.io.pushCount.poke(0.U)
    dut.io.popValid.poke(false.B)
    dut.io.popStid.poke(0.U)
    dut.io.popCount.poke(0.U)
    dut.io.selectedValid.poke(false.B)
    dut.io.selectedStid.poke(0.U)
    dut.io.selectedCount.poke(0.U)
    dut.io.freePhysCount.poke(64.U)
    dut.io.selectedMapQFreeCount.poke(8.U)
  }

  test("pair destination reserves and releases two physical and MapQ credits atomically") {
    simulate(new GPRReservationTracker(
      queueDepth = 4,
      physRegs = 64,
      mapQDepth = 8,
      stidWidth = 2,
      stidCount = 2
    )) { dut =>
      clear(dut)
      dut.io.selectedValid.poke(true.B)
      dut.io.selectedStid.poke(0.U)
      dut.io.selectedCount.poke(2.U)
      dut.io.freePhysCount.poke(1.U)
      dut.io.selectedMapQFreeCount.poke(2.U)
      dut.io.ready.expect(false.B)
      dut.io.freePhysCount.poke(2.U)
      dut.io.selectedMapQFreeCount.poke(1.U)
      dut.io.ready.expect(false.B)
      dut.io.selectedMapQFreeCount.poke(2.U)
      dut.io.ready.expect(true.B)

      dut.io.pushValid.poke(true.B)
      dut.io.pushCount.poke(2.U)
      dut.clock.step()
      clear(dut)
      dut.io.physReservationCount.expect(2.U)
      dut.io.selectedMapQReservationCount.expect(2.U)
      dut.io.stateError.expect(false.B)

      dut.io.selectedValid.poke(true.B)
      dut.io.selectedCount.poke(2.U)
      dut.io.freePhysCount.poke(3.U)
      dut.io.selectedMapQFreeCount.poke(4.U)
      dut.io.ready.expect(false.B)
      dut.io.freePhysCount.poke(4.U)
      dut.io.ready.expect(true.B)

      clear(dut)
      dut.io.popValid.poke(true.B)
      dut.io.popCount.poke(2.U)
      dut.clock.step()
      clear(dut)
      dut.io.physReservationCount.expect(0.U)
      dut.io.selectedMapQReservationCount.expect(0.U)
      dut.io.stateError.expect(false.B)
    }
  }

  test("same-cycle pair exchange transfers lane-local ownership without changing global count") {
    simulate(new GPRReservationTracker(
      queueDepth = 4,
      physRegs = 64,
      mapQDepth = 8,
      stidWidth = 2,
      stidCount = 2
    )) { dut =>
      clear(dut)
      dut.io.pushValid.poke(true.B)
      dut.io.pushStid.poke(0.U)
      dut.io.pushCount.poke(2.U)
      dut.clock.step()

      clear(dut)
      dut.io.pushValid.poke(true.B)
      dut.io.pushStid.poke(1.U)
      dut.io.pushCount.poke(2.U)
      dut.io.popValid.poke(true.B)
      dut.io.popStid.poke(0.U)
      dut.io.popCount.poke(2.U)
      dut.clock.step()

      clear(dut)
      dut.io.selectedStid.poke(0.U)
      dut.io.physReservationCount.expect(2.U)
      dut.io.selectedMapQReservationCount.expect(0.U)
      dut.io.selectedStid.poke(1.U)
      dut.io.selectedMapQReservationCount.expect(2.U)
      dut.io.stateError.expect(false.B)
    }
  }
}
