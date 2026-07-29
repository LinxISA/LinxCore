package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.lsu.{STQEntryBankRow, STQEntryStatus}
import org.scalatest.funsuite.AnyFunSuite

class OooStqRecoveryProjectionSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 2,
    instructionDecodeWidth = 2,
    decodedUopWidth = 2,
    renameWidth = 2,
    dispatchWidth = 2,
    retireGroupWidth = 2,
    robGroupsPerStid = 8,
    robBankCount = 2,
    robRecoveryScanGroupsPerCycle = 2,
    robNonFlushScanGroupsPerCycle = 2,
    pcBufferEntries = 8,
    pcBankCount = 2,
    pcRecoveryScanGroupsPerCycle = 2,
    pcWritePorts = 2,
    iqBankCount = 2,
    iqEntriesPerBank = 4,
    iqFreeSelectLeafEntries = 2,
    tuRetireSourceDepthPerStid = 16,
    lsidWidth = 40)

  private def pokeRecovery(dut: OooStqRecoveryProjection): Unit = {
    dut.io.recovery.poke(0.U.asTypeOf(dut.io.recovery))
    dut.io.recovery.valid.poke(true.B)
    dut.io.recovery.oldHead.valid.poke(true.B)
    dut.io.recovery.oldHead.peId.poke(1.U)
    dut.io.recovery.oldHead.stid.poke(1.U)
    dut.io.recovery.oldHead.ridSlot.poke(0.U)
    dut.io.recovery.oldHead.ridGeneration.poke(7.U)
    dut.io.recovery.oldOccupied.poke(8.U)
    dut.io.recovery.newOccupied.poke(2.U)
    dut.io.recoveryValid.poke(true.B)
  }

  private def pokeRow(
      row: STQEntryBankRow,
      ridSlot: Int,
      status: STQEntryStatus.Type = STQEntryStatus.Wait,
      malformedPe: Boolean = false): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.valid.poke(true.B)
    row.status.poke(status)
    row.peId.poke(1.U)
    row.stid.poke(1.U)
    row.storeIdFullValid.poke(true.B)
    row.exactOwner.valid.poke(true.B)
    row.exactOwner.peId.poke((if (malformedPe) 0 else 1).U)
    row.exactOwner.stid.poke(1.U)
    row.exactOwner.nativeBidValid.poke(true.B)
    row.exactOwner.nativeBid.poke(0x93.U)
    row.exactOwner.brobGeneration.poke(8.U)
    row.exactOwner.ridSlot.poke(ridSlot.U)
    row.exactOwner.ridGeneration.poke(7.U)
    row.exactOwner.memberIndex.poke(3.U)
    row.exactOwner.residentGeneration.poke(9.U)
  }

  test("projects only the killed speculative suffix and reports committed rows") {
    simulate(new OooStqRecoveryProjection(p, stqEntries = 4)) { dut =>
      dut.io.rows.poke(0.U.asTypeOf(dut.io.rows))
      pokeRecovery(dut)
      pokeRow(dut.io.rows(0), ridSlot = 6)
      pokeRow(dut.io.rows(1), ridSlot = 1)
      pokeRow(dut.io.rows(3), ridSlot = 7, status = STQEntryStatus.Commit)
      dut.io.rejected.expect(false.B)
      dut.io.freeMask.expect(1.U)
      dut.io.statusBlockedMask.expect(8.U)
      dut.io.malformedMask.expect(0.U)
    }
  }

  test("malformed target owner rejects the entire exact recovery projection") {
    simulate(new OooStqRecoveryProjection(p, stqEntries = 4)) { dut =>
      dut.io.rows.poke(0.U.asTypeOf(dut.io.rows))
      pokeRecovery(dut)
      pokeRow(dut.io.rows(0), ridSlot = 6)
      pokeRow(dut.io.rows(2), ridSlot = 5, malformedPe = true)
      dut.io.rejected.expect(true.B)
      dut.io.freeMask.expect(0.U)
      dut.io.malformedMask.expect(4.U)
    }
  }
}
