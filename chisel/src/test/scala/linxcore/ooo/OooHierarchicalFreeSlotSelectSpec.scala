package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooHierarchicalFreeSlotSelectSpec extends AnyFunSuite with ChiselSim {
  test("selects the lowest free slot across bounded groups") {
    simulate(new OooHierarchicalFreeSlotSelect(entries = 32,
      groupEntries = 4)) { dut =>
      val cases = Seq(
        BigInt(0) -> None,
        (BigInt(1) << 31) -> Some(31),
        ((BigInt(1) << 4) | (BigInt(1) << 29)) -> Some(4),
        ((BigInt(1) << 3) | (BigInt(1) << 4)) -> Some(3),
        ((BigInt(1) << 7) | (BigInt(1) << 8) | BigInt(1)) -> Some(0))

      cases.foreach { case (mask, expected) =>
        dut.io.available.poke(mask.U)
        dut.io.selectedValid.expect(expected.nonEmpty.B)
        expected.foreach(index => dut.io.selectedIndex.expect(index.U))
      }
    }
  }

  test("supports one-entry groups and one-group banks") {
    simulate(new OooHierarchicalFreeSlotSelect(entries = 8,
      groupEntries = 1)) { dut =>
      dut.io.available.poke("b00101000".U)
      dut.io.selectedValid.expect(true.B)
      dut.io.selectedIndex.expect(3.U)
    }
    simulate(new OooHierarchicalFreeSlotSelect(entries = 8,
      groupEntries = 8)) { dut =>
      dut.io.available.poke("b10100000".U)
      dut.io.selectedValid.expect(true.B)
      dut.io.selectedIndex.expect(5.U)
    }
  }
}
