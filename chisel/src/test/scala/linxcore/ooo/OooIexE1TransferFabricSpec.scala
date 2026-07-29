package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooIexE1TransferFabricSpec extends AnyFunSuite with ChiselSim {
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
    iexIssueDomainCount = 2,
    iexReleaseWidth = 2,
    tuRetireSourceDepthPerStid = 16)

  private val topology = Seq(
    OooIexIssueDomainConfig(
      OooUopClass.Alu.asUInt.litValue.toInt, 1, releasePort = 0),
    OooIexIssueDomainConfig(
      OooUopClass.Bru.asUInt.litValue.toInt, 1, releasePort = 1))

  private def clear(dut: OooIexE1TransferFabric): Unit = {
    dut.io.i2.foreach { port =>
      port.valid.poke(false.B)
      port.bits.poke(0.U.asTypeOf(port.bits))
    }
    dut.io.issueReleases.foreach(_.ready.poke(false.B))
    dut.io.e1.foreach(_.ready.poke(false.B))
    dut.io.recoveryApply.valid.poke(false.B)
    dut.io.recoveryApply.bits.poke(
      0.U.asTypeOf(dut.io.recoveryApply.bits))
    dut.io.loadCancel.foreach(
      _.poke(0.U.asTypeOf(dut.io.loadCancel.head)))
  }

  private def pokeI2(
      port: chisel3.util.DecoupledIO[OooIexI2Transaction],
      uopClass: OooUopClass.Type,
      ridSlot: Int,
      data: BigInt): Unit = {
    port.bits.poke(0.U.asTypeOf(port.bits))
    val row = port.bits.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(1.U)
    row.epoch.poke(7.U)
    row.transactionId.poke((100 + ridSlot).U)
    row.member.group.valid.poke(true.B)
    row.member.group.peId.poke(3.U)
    row.member.group.stid.poke(1.U)
    row.member.group.ridSlot.poke(ridSlot.U)
    row.member.group.ridGeneration.poke(1.U)
    row.member.bid.valid.poke(true.B)
    row.member.bid.value.poke(5.U)
    row.member.brobGeneration.poke(2.U)
    row.member.memberIndex.poke(0.U)
    row.member.residentGeneration.poke(4.U)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(uopClass)
    row.reservation.bank.poke(0.U)
    row.reservation.writePort.poke(0.U)
    row.reservation.speculativeSlot.poke(ridSlot.U)
    row.reservation.reservationEpoch.poke(9.U)
    row.inFlight.poke(true.B)
    port.bits.sourceMask.poke(0.U)
    port.bits.sourceData(0).poke(data.U)
    port.valid.poke(true.B)
  }

  test("transfers static ALU and BRU domains through independent releases") {
    simulate(new OooIexE1TransferFabric(p, topology)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.pickClasses(0).expect(OooUopClass.Alu)
      dut.io.pickClasses(1).expect(OooUopClass.Bru)
      dut.io.pickBankEnables(0).expect(1.U)
      dut.io.pickBankEnables(1).expect(1.U)

      pokeI2(dut.io.i2(0), OooUopClass.Alu, ridSlot = 1, data = 11)
      pokeI2(dut.io.i2(1), OooUopClass.Bru, ridSlot = 2, data = 22)
      dut.io.issueReleases.foreach(_.ready.poke(false.B))
      dut.io.i2(0).ready.expect(false.B)
      dut.io.i2(1).ready.expect(false.B)
      dut.io.empty.expect(true.B)

      dut.io.issueReleases.foreach(_.ready.poke(true.B))
      dut.io.releaseDomains(0).valid.expect(true.B)
      dut.io.releaseDomains(0).bits.expect(0.U)
      dut.io.releaseDomains(1).valid.expect(true.B)
      dut.io.releaseDomains(1).bits.expect(1.U)
      dut.io.i2(0).ready.expect(true.B)
      dut.io.i2(1).ready.expect(true.B)
      dut.io.issueReleases(0).bits.member.group.ridSlot.expect(1.U)
      dut.io.issueReleases(1).bits.member.group.ridSlot.expect(2.U)
      dut.clock.step()
      dut.io.i2.foreach(_.valid.poke(false.B))
      dut.io.issueReleases.foreach(_.ready.poke(false.B))

      dut.io.occupied(0).expect(true.B)
      dut.io.occupied(1).expect(true.B)
      dut.io.e1(0).valid.expect(true.B)
      dut.io.e1(1).valid.expect(true.B)
      dut.io.e1(0).bits.ownerClass.expect(OooUopClass.Alu)
      dut.io.e1(1).bits.ownerClass.expect(OooUopClass.Bru)
      dut.io.e1(0).bits.ownerLane.expect(0.U)
      dut.io.e1(1).bits.ownerLane.expect(1.U)
      dut.io.e1(0).bits.i2.sourceData(0).expect(11.U)
      dut.io.e1(1).bits.i2.sourceData(0).expect(22.U)

      // Each class has independent downstream backpressure after transfer.
      dut.io.e1(0).ready.poke(true.B)
      dut.clock.step()
      dut.io.e1(0).ready.poke(false.B)
      dut.io.occupied(0).expect(false.B)
      dut.io.occupied(1).expect(true.B)
      dut.io.e1(1).bits.i2.sourceData(0).expect(22.U)
      dut.io.e1(1).ready.poke(true.B)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }

  test("rejects overlapping static class and bank ownership") {
    val overlap = Seq(
      OooIexIssueDomainConfig(
        OooUopClass.Alu.asUInt.litValue.toInt, 1, releasePort = 0),
      OooIexIssueDomainConfig(
        OooUopClass.Alu.asUInt.litValue.toInt, 1, releasePort = 1))
    assertThrows[IllegalArgumentException](
      OooIexIssueDomainConfig.validate(p, overlap))
    assertThrows[IllegalArgumentException](
      OooIexIssueDomainConfig.validate(p, topology.take(1)))
    assertThrows[IllegalArgumentException](OooIexIssueDomainConfig.validate(p,
      topology.updated(1, OooIexIssueDomainConfig(p.iqClassCount, 1))))
    assertThrows[IllegalArgumentException](OooIexIssueDomainConfig.validate(p,
      topology.updated(1, OooIexIssueDomainConfig(
        OooUopClass.Bru.asUInt.litValue.toInt, 0))))
    assertThrows[IllegalArgumentException](OooIexIssueDomainConfig.validate(p,
      topology.updated(1, OooIexIssueDomainConfig(
        OooUopClass.Bru.asUInt.litValue.toInt, 4))))
    assertThrows[IllegalArgumentException](OooIexIssueDomainConfig.validate(p,
      topology.updated(1, OooIexIssueDomainConfig(
        OooUopClass.Bru.asUInt.litValue.toInt, 1, releasePort = 2))))
  }
}
