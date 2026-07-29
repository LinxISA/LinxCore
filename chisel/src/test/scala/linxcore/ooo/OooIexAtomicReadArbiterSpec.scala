package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.OperandClass
import org.scalatest.funsuite.AnyFunSuite

class OooIexAtomicReadArbiterSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    iexIssueDomainCount = 2,
    iexPReadPorts = 3,
    iexTReadPorts = 2,
    iexUReadPorts = 2)

  private def clear(dut: OooIexAtomicReadArbiter): Unit = {
    dut.io.attempts.foreach(_.poke(0.U.asTypeOf(dut.io.attempts.head)))
    dut.io.pReadResponses.foreach(
      _.poke(0.U.asTypeOf(dut.io.pReadResponses.head)))
    dut.io.tReadResponses.foreach(
      _.poke(0.U.asTypeOf(dut.io.tReadResponses.head)))
    dut.io.uReadResponses.foreach(
      _.poke(0.U.asTypeOf(dut.io.uReadResponses.head)))
    dut.io.pcReadResponses.foreach(
      _.poke(0.U.asTypeOf(dut.io.pcReadResponses.head)))
  }

  private def pokeAttempt(
      dut: OooIexAtomicReadArbiter,
      domain: Int,
      stid: Int,
      rid: Int,
      classes: Seq[OperandClass.Type],
      pcRequired: Boolean = false): Unit = {
    val attempt = dut.io.attempts(domain)
    attempt.poke(0.U.asTypeOf(attempt))
    attempt.valid.poke(true.B)
    attempt.bits.stid.poke(stid.U)
    attempt.bits.epoch.poke(7.U)
    attempt.bits.transactionId.poke((31 + domain).U)
    attempt.bits.member.group.valid.poke(true.B)
    attempt.bits.member.group.peId.poke(3.U)
    attempt.bits.member.group.stid.poke(stid.U)
    attempt.bits.member.group.ridSlot.poke(rid.U)
    attempt.bits.member.group.ridGeneration.poke(1.U)
    attempt.bits.member.bid.valid.poke(true.B)
    attempt.bits.member.bid.value.poke(4.U)
    attempt.bits.member.memberIndex.poke(domain.U)
    attempt.bits.reservation.valid.poke(true.B)
    attempt.bits.reservation.uopClass.poke(OooUopClass.Alu)
    attempt.bits.reservation.bank.poke((domain % p.iqBankCount).U)
    attempt.bits.reservation.writePort.poke(0.U)
    attempt.bits.reservation.speculativeSlot.poke(domain.U)
    attempt.bits.reservation.reservationEpoch.poke(9.U)
    attempt.bits.sourceMask.poke(((BigInt(1) << classes.size) - 1).U)
    for ((operandClass, sourceIndex) <- classes.zipWithIndex) {
      val source = attempt.bits.sources(sourceIndex)
      source.valid.poke(true.B)
      source.ready.poke(true.B)
      source.operandClass.poke(operandClass)
      source.ptag.poke((40 + domain * 4 + sourceIndex).U)
      source.ptagGeneration.poke(2.U)
      source.localTag.poke((sourceIndex + 1).U)
      source.localSequence.valid.poke(true.B)
      source.localSequence.index.poke(sourceIndex.U)
      source.localSequence.generation.poke((10 + sourceIndex).U)
    }
    attempt.bits.pcRequired.poke(pcRequired.B)
    if (pcRequired) {
      attempt.bits.pcToken.valid.poke(true.B)
      attempt.bits.pcToken.index.poke(5.U)
      attempt.bits.pcToken.byteOffset.poke(6.U)
      attempt.bits.pcToken.allocationEpoch.poke(3.U)
    }
  }

  test("allocates complete read groups by age and cross-STID fairness") {
    simulate(new OooIexAtomicReadArbiter(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // Five P sources cannot fit three ports. The older same-STID group wins
      // as one atomic three-port transaction; the younger gets no request.
      pokeAttempt(dut, 0, stid = 0, rid = 1,
        Seq(OperandClass.P, OperandClass.P, OperandClass.P))
      pokeAttempt(dut, 1, stid = 0, rid = 2,
        Seq(OperandClass.P, OperandClass.P))
      for (port <- 0 until p.iexPReadPorts) {
        dut.io.pReadResponses(port).valid.poke(true.B)
        dut.io.pReadResponses(port).bits.poke((100 + port).U)
      }
      dut.io.selectedMask.expect(1.U)
      dut.io.deniedMask.expect(2.U)
      dut.io.grant(0).expect(true.B)
      dut.io.grant(1).expect(false.B)
      dut.io.pReadRequests.foreach(_.valid.expect(true.B))
      dut.io.sourceDataValid(0).expect("b0111".U)
      dut.io.sourceData(0)(0).expect(100.U)
      dut.io.sourceData(0)(2).expect(102.U)
      dut.io.sourceDataValid(1).expect(0.U)
      dut.clock.step()

      // Cross-STID order starts at STID1 after the prior STID0 grant. Two
      // two-port groups contend for three ports, so STID1 wins, then STID0.
      clear(dut)
      pokeAttempt(dut, 0, stid = 0, rid = 1,
        Seq(OperandClass.P, OperandClass.P))
      pokeAttempt(dut, 1, stid = 1, rid = 1,
        Seq(OperandClass.P, OperandClass.P))
      dut.io.selectedMask.expect(2.U)
      dut.io.roundRobinStid.expect(1.U)
      dut.clock.step()
      dut.io.selectedMask.expect(1.U)
      dut.io.roundRobinStid.expect(2.U)

      // One mixed group occupies independent P/T/U/PC resources together.
      clear(dut)
      pokeAttempt(dut, 0, stid = 0, rid = 3,
        Seq(OperandClass.P, OperandClass.T, OperandClass.U),
        pcRequired = true)
      dut.io.pReadResponses(0).valid.poke(true.B)
      dut.io.pReadResponses(0).bits.poke(11.U)
      dut.io.tReadResponses(0).valid.poke(true.B)
      dut.io.tReadResponses(0).bits.poke(22.U)
      dut.io.uReadResponses(0).valid.poke(true.B)
      dut.io.uReadResponses(0).bits.poke(33.U)
      dut.io.pcReadResponses(0).valid.poke(true.B)
      dut.io.pcReadResponses(0).bits.poke(44.U)
      dut.io.selectedMask.expect(1.U)
      dut.io.pReadRequests(0).valid.expect(true.B)
      dut.io.tReadRequests(0).valid.expect(true.B)
      dut.io.uReadRequests(0).valid.expect(true.B)
      dut.io.pcReadRequests(0).valid.expect(true.B)
      dut.io.sourceDataValid(0).expect("b0111".U)
      dut.io.sourceData(0)(0).expect(11.U)
      dut.io.sourceData(0)(1).expect(22.U)
      dut.io.sourceData(0)(2).expect(33.U)
      dut.io.pcDataValid(0).expect(true.B)
      dut.io.pcData(0).expect(44.U)

      // Missing readyless T data does not change the resource grant. The lane
      // sees a partial response and returns the exact row for retry.
      dut.io.tReadResponses(0).valid.poke(false.B)
      dut.io.grant(0).expect(true.B)
      dut.io.sourceDataValid(0).expect("b0101".U)

      // A lane may remove exact bypass hits from the RF mask while retaining
      // those operands in its logical I2 source mask.
      clear(dut)
      pokeAttempt(dut, 0, stid = 0, rid = 4,
        Seq(OperandClass.P, OperandClass.T, OperandClass.U))
      dut.io.attempts(0).bits.sourceMask.poke("b0110".U)
      dut.io.shapeExact(0).expect(true.B)
      dut.io.pDemand(0).expect(0.U)
      dut.io.tDemand(0).expect(1.U)
      dut.io.uDemand(0).expect(1.U)
      dut.io.pReadRequests.foreach(_.valid.expect(false.B))
      dut.io.tReadRequests(0).valid.expect(true.B)
      dut.io.uReadRequests(0).valid.expect(true.B)

      // Malformed operand classes are decided as a denial and emit no port.
      clear(dut)
      pokeAttempt(dut, 0, stid = 0, rid = 5,
        Seq(OperandClass.Invalid))
      dut.io.decisionValid(0).expect(true.B)
      dut.io.shapeExact(0).expect(false.B)
      dut.io.grant(0).expect(false.B)
      dut.io.pReadRequests.foreach(_.valid.expect(false.B))
      dut.io.tReadRequests.foreach(_.valid.expect(false.B))
      dut.io.uReadRequests.foreach(_.valid.expect(false.B))
      dut.io.pcReadRequests.foreach(_.valid.expect(false.B))
    }
  }

  test("scales the same oldest-complete policy to twelve physical domains") {
    val profileParams = OooParams(
      iexIssueDomainCount = 12,
      iexPReadPorts = 6,
      iexTReadPorts = 4,
      iexUReadPorts = 4)
    simulate(new OooIexAtomicReadArbiter(profileParams)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      for (domain <- 0 until profileParams.iexIssueDomainCount) {
        pokeAttempt(dut, domain, stid = 0, rid = domain + 1,
          Seq(OperandClass.P))
      }

      dut.io.selectedMask.expect("h03f".U)
      dut.io.deniedMask.expect("hfc0".U)
      dut.io.pReadRequests.foreach(_.valid.expect(true.B))
      for (domain <- 0 until 6) {
        dut.io.grant(domain).expect(true.B)
      }
      for (domain <- 6 until profileParams.iexIssueDomainCount) {
        dut.io.grant(domain).expect(false.B)
      }
    }
  }
}
