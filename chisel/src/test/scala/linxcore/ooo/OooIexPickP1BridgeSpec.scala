package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooIexPickP1BridgeSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
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
    tuRetireSourceDepthPerStid = 16)

  private def clear(dut: OooIexPickP1Bridge): Unit = {
    dut.io.pick.valid.poke(false.B)
    dut.io.pick.bits.poke(0.U.asTypeOf(dut.io.pick.bits))
    dut.io.queryState.poke(OooIexIssueSlotState.Free)
    dut.io.queryRow.poke(0.U.asTypeOf(dut.io.queryRow))
    dut.io.p1.ready.poke(false.B)
  }

  private def pokeExact(
      dut: OooIexPickP1Bridge,
      pcRequired: Boolean = true): Unit = {
    val token = dut.io.pick.bits
    val row = dut.io.queryRow
    token.poke(0.U.asTypeOf(token))
    row.poke(0.U.asTypeOf(row))

    token.query.uopClass.poke(OooUopClass.Bru)
    token.query.bank.poke(1.U)
    token.query.entry.poke(2.U)
    token.candidate.eligible.poke(true.B)
    token.candidate.peId.poke(3.U)
    token.candidate.stid.poke(1.U)
    token.candidate.epoch.poke(7.U)
    token.candidate.transactionId.poke(19.U)
    token.candidate.member.group.valid.poke(true.B)
    token.candidate.member.group.peId.poke(3.U)
    token.candidate.member.group.stid.poke(1.U)
    token.candidate.member.group.ridSlot.poke(4.U)
    token.candidate.member.group.ridGeneration.poke(2.U)
    token.candidate.member.bid.valid.poke(true.B)
    token.candidate.member.bid.value.poke(5.U)
    token.candidate.member.brobGeneration.poke(3.U)
    token.candidate.member.memberIndex.poke(1.U)
    token.candidate.member.residentGeneration.poke(6.U)
    token.candidate.reservation.valid.poke(true.B)
    token.candidate.reservation.uopClass.poke(OooUopClass.Bru)
    token.candidate.reservation.bank.poke(1.U)
    token.candidate.reservation.writePort.poke(0.U)
    token.candidate.reservation.speculativeSlot.poke(2.U)
    token.candidate.reservation.reservationEpoch.poke(9.U)

    row.schedule.valid.poke(true.B)
    row.schedule.inFlight.poke(false.B)
    row.schedule.peId.poke(3.U)
    row.schedule.stid.poke(1.U)
    row.schedule.epoch.poke(7.U)
    row.schedule.transactionId.poke(19.U)
    row.schedule.member.poke(token.candidate.member.peek())
    row.schedule.reservation.poke(token.candidate.reservation.peek())
    row.payload.uopKey.primaryParent.valid.poke(true.B)
    row.payload.uopKey.primaryParent.peId.poke(3.U)
    row.payload.uopKey.primaryParent.stid.poke(1.U)
    row.payload.uopKey.primaryParent.instructionId.poke(22.U)
    row.payload.uopKey.primaryParent.epoch.poke(7.U)
    row.payload.parentCount.poke(2.U)
    row.payload.pcParentIndexValid.poke(true.B)
    row.payload.pcParentIndex.poke(1.U)
    row.payload.parentPcTokens(1).valid.poke(true.B)
    row.payload.parentPcTokens(1).index.poke(5.U)
    row.payload.parentPcTokens(1).byteOffset.poke(6.U)
    row.payload.parentPcTokens(1).allocationEpoch.poke(4.U)
    row.payload.opcode.poke(73.U)
    row.payload.recipe.valid.poke(true.B)
    row.payload.recipe.opcode.poke(73.U)
    row.payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    row.payload.recipe.pcReadRequired.poke(pcRequired.B)
    row.payload.recipe.pcReadClass.poke(OooDispatchClass.Bru.U)

    dut.io.queryState.poke(OooIexIssueSlotState.ResidentS3)
    dut.io.pick.valid.poke(true.B)
  }

  test("joins one exact picker token to generated PC-read controls") {
    simulate(new OooIexPickP1Bridge(p)) { dut =>
      clear(dut)
      pokeExact(dut)
      dut.io.p1.ready.poke(false.B)

      dut.io.query.uopClass.expect(OooUopClass.Bru)
      dut.io.query.bank.expect(1.U)
      dut.io.query.entry.expect(2.U)
      dut.io.p1.valid.expect(true.B)
      dut.io.pick.ready.expect(false.B)
      dut.io.p1.bits.pcReadRequired.expect(true.B)
      dut.io.p1.bits.pcParentIndex.expect(1.U)
      dut.io.p1.bits.row.parentPcTokens(1).index.expect(5.U)
      dut.io.repick.valid.expect(false.B)

      dut.io.p1.ready.poke(true.B)
      dut.io.pick.ready.expect(true.B)
      dut.io.p1.valid.expect(true.B)
      dut.clock.step()
    }
  }

  test("does not require a PC token for a recipe that does not read PC") {
    simulate(new OooIexPickP1Bridge(p)) { dut =>
      clear(dut)
      pokeExact(dut, pcRequired = false)
      dut.io.queryRow.payload.pcParentIndexValid.poke(false.B)
      dut.io.queryRow.payload.parentPcTokens(1).valid.poke(false.B)
      dut.io.p1.ready.poke(true.B)

      dut.io.p1.valid.expect(true.B)
      dut.io.p1.bits.pcReadRequired.expect(false.B)
      dut.io.rejected.valid.expect(false.B)
      dut.io.repick.valid.expect(false.B)
    }
  }

  test("reads PC only on the generated physical child class") {
    simulate(new OooIexPickP1Bridge(p)) { dut =>
      clear(dut)
      pokeExact(dut)
      // Models the data child of a split PCR store: the instruction requires
      // PC on AGU, while this exact row is in another class.
      dut.io.queryRow.payload.recipe.pcReadClass
        .poke(OooDispatchClass.Agu.U)
      dut.io.queryRow.payload.pcParentIndexValid.poke(false.B)
      dut.io.queryRow.payload.parentPcTokens(1).valid.poke(false.B)
      dut.io.p1.ready.poke(true.B)

      dut.io.p1.valid.expect(true.B)
      dut.io.p1.bits.pcReadRequired.expect(false.B)
      dut.io.rejected.valid.expect(false.B)
    }
  }

  test("fails closed and returns the exact member when PC metadata is missing") {
    simulate(new OooIexPickP1Bridge(p)) { dut =>
      clear(dut)
      pokeExact(dut)
      dut.io.queryRow.payload.pcParentIndexValid.poke(false.B)

      dut.io.p1.ready.poke(false.B)
      dut.io.pick.ready.expect(false.B)
      dut.io.rejected.valid.expect(false.B)
      dut.io.repick.valid.expect(false.B)

      dut.io.p1.ready.poke(true.B)
      dut.io.p1.valid.expect(false.B)
      dut.io.pick.ready.expect(true.B)
      dut.io.rejected.valid.expect(true.B)
      dut.io.rejected.bits.pcMetadataExact.expect(false.B)
      dut.io.repick.valid.expect(true.B)
      dut.io.repick.bits.member.group.ridSlot.expect(4.U)
      dut.io.repick.bits.reservation.speculativeSlot.expect(2.U)
      dut.clock.step()
    }
  }
}
