package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooIexOldestReadyPickerSpec extends AnyFunSuite with ChiselSim {
  private val aluClassIndex = OooDispatchClass.Alu - 1

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
    tuRetireSourceDepthPerStid = 16)

  private def clear(dut: OooIexOldestReadyPicker): Unit = {
    dut.io.classBankEnables.foreach(_.poke(0.U))
    dut.io.classBankEnables(aluClassIndex).poke("b11".U)
    dut.io.stidBlock.poke(0.U)
    dut.io.candidates.foreach(_.foreach(_.foreach(
      _.poke(0.U.asTypeOf(dut.io.candidates.head.head.head)))))
    dut.io.pick.ready.poke(false.B)
    dut.io.recoveryApply.valid.poke(false.B)
    dut.io.recoveryApply.bits.poke(
      0.U.asTypeOf(dut.io.recoveryApply.bits))
  }

  private def pokeCandidate(
      dut: OooIexOldestReadyPicker,
      bank: Int,
      entry: Int,
      stid: Int,
      generation: Int,
      ridSlot: Int,
      memberIndex: Int = 0,
      classIndex: Int = OooDispatchClass.Alu - 1): Unit = {
    val candidate = dut.io.candidates(classIndex)(bank)(entry)
    candidate.poke(0.U.asTypeOf(candidate))
    candidate.eligible.poke(true.B)
    candidate.peId.poke(3.U)
    candidate.stid.poke(stid.U)
    candidate.epoch.poke(7.U)
    candidate.transactionId.poke(
      (generation * p.robGroupsPerStid + ridSlot).U)
    candidate.member.group.valid.poke(true.B)
    candidate.member.group.peId.poke(3.U)
    candidate.member.group.stid.poke(stid.U)
    candidate.member.group.ridGeneration.poke(generation.U)
    candidate.member.group.ridSlot.poke(ridSlot.U)
    candidate.member.bid.valid.poke(true.B)
    candidate.member.bid.value.poke(5.U)
    candidate.member.brobGeneration.poke(2.U)
    candidate.member.memberIndex.poke(memberIndex.U)
    candidate.member.residentGeneration.poke(4.U)
    candidate.reservation.valid.poke(true.B)
    candidate.reservation.uopClass.poke(OooUopClass.all(classIndex))
    candidate.reservation.bank.poke(bank.U)
    candidate.reservation.speculativeSlot.poke(entry.U)
    candidate.reservation.reservationEpoch.poke(9.U)
  }

  private def pokeRecovery(
      dut: OooIexOldestReadyPicker,
      stid: Int,
      newOccupied: Int): Unit = {
    val plan = dut.io.recoveryApply.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.valid.poke(true.B)
    plan.oldHead.valid.poke(true.B)
    plan.oldHead.peId.poke(3.U)
    plan.oldHead.stid.poke(stid.U)
    plan.oldHead.ridSlot.poke(0.U)
    plan.oldHead.ridGeneration.poke(1.U)
    plan.oldOccupied.poke(4.U)
    plan.newOccupied.poke(newOccupied.U)
    dut.io.recoveryApply.valid.poke(true.B)
  }

  test("selects wrap-safe oldest members and rotates fairly across STIDs") {
    simulate(new OooIexOldestReadyPicker(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeCandidate(dut, bank = 0, entry = 2, stid = 0,
        generation = 255, ridSlot = 7)
      pokeCandidate(dut, bank = 1, entry = 0, stid = 0,
        generation = 0, ridSlot = 0)
      pokeCandidate(dut, bank = 0, entry = 1, stid = 1,
        generation = 5, ridSlot = 2)

      dut.io.pick.valid.expect(false.B)
      dut.clock.step()
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.bits.candidate.stid.expect(0.U)
      dut.io.pick.bits.query.bank.expect(0.U)
      dut.io.pick.bits.query.entry.expect(2.U)

      // The retained result is stable even if its candidate projection moves.
      dut.io.candidates(aluClassIndex)(0)(2).eligible.poke(false.B)
      dut.clock.step()
      dut.io.pick.bits.query.bank.expect(0.U)
      dut.io.pick.bits.query.entry.expect(2.U)
      dut.io.candidates(aluClassIndex)(0)(2).eligible.poke(true.B)

      // A fire may refill in the same edge, excludes the fired row, and uses
      // the next STID as the work-conserving arbitration base.
      dut.io.pick.ready.poke(true.B)
      dut.clock.step()
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.bits.candidate.stid.expect(1.U)
      dut.io.pick.bits.query.bank.expect(0.U)
      dut.io.pick.bits.query.entry.expect(1.U)

      // The canonical IQ owner marks the earlier fired row in-flight on that
      // edge. The picker deliberately does not mirror that state.
      dut.io.candidates(aluClassIndex)(0)(2).eligible.poke(false.B)
      dut.clock.step()
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.bits.candidate.stid.expect(0.U)
      dut.io.pick.bits.query.bank.expect(1.U)
      dut.io.pick.bits.query.entry.expect(0.U)
    }
  }

  test("honors the bank domain and reports malformed candidates fail closed") {
    simulate(new OooIexOldestReadyPicker(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.classBankEnables(aluClassIndex).poke("b01".U)
      pokeCandidate(dut, bank = 1, entry = 0, stid = 0,
        generation = 1, ridSlot = 1)
      dut.clock.step()
      dut.io.pick.valid.expect(false.B)
      dut.io.malformed.valid.expect(false.B)

      pokeCandidate(dut, bank = 0, entry = 1, stid = 0,
        generation = 1, ridSlot = 2)
      dut.io.candidates(aluClassIndex)(0)(1).reservation.bank.poke(1.U)
      dut.io.malformed.valid.expect(true.B)
      dut.io.malformed.bits.reservationExact.expect(false.B)
      dut.clock.step()
      dut.io.pick.valid.expect(false.B)
    }
  }

  test("suppresses and clears a retained result killed by recovery") {
    simulate(new OooIexOldestReadyPicker(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeCandidate(dut, bank = 0, entry = 1, stid = 1,
        generation = 1, ridSlot = 2)
      dut.clock.step()
      dut.io.pick.valid.expect(true.B)

      pokeRecovery(dut, stid = 1, newOccupied = 1)
      dut.io.pick.valid.expect(false.B)
      dut.io.recoveryCanceled.valid.expect(true.B)
      dut.io.recoveryCanceled.bits.query.entry.expect(1.U)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.pick.valid.expect(false.B)
      dut.io.held.expect(false.B)
    }
  }

  test("drops a blocked target token and continues with a peer STID") {
    simulate(new OooIexOldestReadyPicker(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeCandidate(dut, bank = 0, entry = 1, stid = 1,
        generation = 1, ridSlot = 2)
      pokeCandidate(dut, bank = 1, entry = 0, stid = 0,
        generation = 1, ridSlot = 3)
      // Initial RR selects STID 0, so temporarily mask it to retain STID 1.
      dut.io.stidBlock.poke(1.U)
      dut.clock.step()
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.bits.candidate.stid.expect(1.U)

      dut.io.stidBlock.poke(2.U)
      dut.io.pick.valid.expect(false.B)
      dut.io.blockedCanceled.valid.expect(true.B)
      dut.io.blockedCanceled.bits.candidate.stid.expect(1.U)
      dut.clock.step()
      dut.io.stidBlock.poke(0.U)
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.bits.candidate.stid.expect(0.U)
    }
  }

  test("selects the oldest row across every class owned by one domain") {
    simulate(new OooIexOldestReadyPicker(p)) { dut =>
      clear(dut)
      val stdClassIndex = OooDispatchClass.Std - 1
      dut.io.classBankEnables(stdClassIndex).poke("b01".U)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeCandidate(dut, bank = 0, entry = 0, stid = 0,
        generation = 1, ridSlot = 4, classIndex = aluClassIndex)
      pokeCandidate(dut, bank = 0, entry = 1, stid = 0,
        generation = 1, ridSlot = 2, classIndex = stdClassIndex)

      dut.clock.step()
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.bits.query.uopClass.expect(OooUopClass.Std)
      dut.io.pick.bits.query.bank.expect(0.U)
      dut.io.pick.bits.query.entry.expect(1.U)

      dut.io.pick.ready.poke(true.B)
      dut.clock.step()
      dut.io.candidates(stdClassIndex)(0)(1).eligible.poke(false.B)
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.bits.query.uopClass.expect(OooUopClass.Alu)
      dut.io.pick.bits.query.entry.expect(0.U)
    }
  }
}
