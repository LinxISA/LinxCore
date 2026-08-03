package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class SCBLoadSnapshotLookupSpec extends AnyFunSuite with ChiselSim {
  test("accepts one exact row and rejects stale or ambiguous snapshots") {
    simulate(new SCBLoadSnapshotLookup(2, 16, 64)) { dut =>
      dut.io.rows.foreach(_.poke(0.U.asTypeOf(dut.io.rows.head)))
      dut.io.lineAddress.poke(0x1200.U)

      dut.io.rows(0).valid.poke(true.B)
      dut.io.rows(0).state.poke(SCBEntryState.Valid)
      dut.io.rows(0).lineAddr.poke(0x1200.U)
      dut.io.rows(0).byteMask.poke(0x5a.U)
      dut.io.rows(0).data.poke(0x1234.U)
      dut.io.returned.expect(true.B)
      dut.io.ambiguous.expect(false.B)
      dut.io.validMask.expect(0x5a.U)
      dut.io.data.expect(0x1234.U)

      dut.io.rows(0).state.poke(SCBEntryState.Miss)
      dut.io.returned.expect(true.B)
      dut.io.ambiguous.expect(false.B)
      dut.io.validMask.expect(0.U)
      dut.io.data.expect(0.U)

      dut.io.rows(0).state.poke(SCBEntryState.Valid)
      dut.io.rows(1).valid.poke(true.B)
      dut.io.rows(1).state.poke(SCBEntryState.Valid)
      dut.io.rows(1).lineAddr.poke(0x1200.U)
      dut.io.rows(1).byteMask.poke(0xa5.U)
      dut.io.rows(1).data.poke(0x5678.U)
      dut.io.returned.expect(false.B)
      dut.io.ambiguous.expect(true.B)
      dut.io.validMask.expect(0.U)
      dut.io.data.expect(0.U)
    }
  }
}
