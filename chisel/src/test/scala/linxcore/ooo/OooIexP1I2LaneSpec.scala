package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

class OooIexP1I2LaneSpec extends AnyFunSuite with ChiselSim {
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

  private def clear(dut: OooIexP1I2Lane): Unit = {
    dut.io.p1.valid.poke(false.B)
    dut.io.p1.bits.poke(0.U.asTypeOf(dut.io.p1.bits))
    dut.io.readDecisionValid.poke(false.B)
    dut.io.readGrant.poke(false.B)
    dut.io.sourceDataValid.poke(0.U)
    dut.io.sourceData.foreach(_.poke(0.U))
    dut.io.pcDataValid.poke(false.B)
    dut.io.pcData.poke(0.U)
    dut.io.bypass.foreach { candidate =>
      candidate.valid.poke(false.B)
      candidate.bits.poke(0.U.asTypeOf(candidate.bits))
    }
    dut.io.loadCancel.foreach(
      _.poke(0.U.asTypeOf(dut.io.loadCancel.head)))
    dut.io.i2.ready.poke(false.B)
    dut.io.recoveryApply.valid.poke(false.B)
    dut.io.recoveryApply.bits.poke(
      0.U.asTypeOf(dut.io.recoveryApply.bits))
  }

  private def pokeLoadProducer(target: RobMemberKey, stid: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(stid.U)
    target.group.ridSlot.poke(1.U)
    target.group.ridGeneration.poke(2.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(4.U)
    target.brobGeneration.poke(5.U)
    target.memberIndex.poke(1.U)
    target.residentGeneration.poke(6.U)
  }

  private def pokeLoadToken(
      target: OooIexLoadGeneration,
      stid: Int,
      generation: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.valid.poke(true.B)
    pokeLoadProducer(target.producer, stid)
    target.generation.poke(generation.U)
  }

  private def pokeRequest(
      dut: OooIexP1I2Lane,
      stid: Int,
      ridSlot: Int,
      memberIndex: Int = 0,
      pcRequired: Boolean = true): Unit = {
    val request = dut.io.p1.bits
    request.poke(0.U.asTypeOf(request))
    val row = request.row
    row.schedule.valid.poke(true.B)
    row.schedule.peId.poke(3.U)
    row.schedule.stid.poke(stid.U)
    row.schedule.epoch.poke(7.U)
    row.schedule.transactionId.poke((100 + ridSlot).U)
    row.schedule.member.group.valid.poke(true.B)
    row.schedule.member.group.peId.poke(3.U)
    row.schedule.member.group.stid.poke(stid.U)
    row.schedule.member.group.ridSlot.poke(ridSlot.U)
    row.schedule.member.group.ridGeneration.poke(1.U)
    row.schedule.member.bid.valid.poke(true.B)
    row.schedule.member.bid.value.poke(5.U)
    row.schedule.member.brobGeneration.poke(2.U)
    row.schedule.member.memberIndex.poke(memberIndex.U)
    row.schedule.member.residentGeneration.poke(4.U)
    row.schedule.reservation.valid.poke(true.B)
    row.schedule.reservation.uopClass.poke(OooUopClass.Alu)
    row.schedule.reservation.bank.poke(0.U)
    row.schedule.reservation.writePort.poke(0.U)
    row.schedule.reservation.speculativeSlot.poke(1.U)
    row.schedule.reservation.reservationEpoch.poke(9.U)
    row.payload.uopKey.primaryParent.valid.poke(true.B)
    row.payload.uopKey.primaryParent.peId.poke(3.U)
    row.payload.uopKey.primaryParent.stid.poke(stid.U)
    row.payload.uopKey.primaryParent.instructionId.poke((200 + ridSlot).U)
    row.payload.uopKey.primaryParent.epoch.poke(7.U)

    row.schedule.sources(0).valid.poke(true.B)
    row.schedule.sources(0).ready.poke(true.B)
    row.schedule.sources(0).operandClass.poke(OperandClass.P)
    row.schedule.sources(0).ptag.poke(17.U)
    row.schedule.sources(0).ptagGeneration.poke(3.U)
    row.schedule.sources(1).valid.poke(true.B)
    row.schedule.sources(1).ready.poke(true.B)
    row.schedule.sources(1).operandClass.poke(OperandClass.T)
    row.schedule.sources(1).localTag.poke(6.U)
    row.schedule.sources(1).localSequence.valid.poke(true.B)
    row.schedule.sources(1).localSequence.index.poke(6.U)
    row.schedule.sources(1).localSequence.generation.poke(2.U)

    row.schedule.destinations(0).valid.poke(true.B)
    row.schedule.destinations(0).kind.poke(DestinationKind.Gpr)
    row.schedule.destinations(0).ptag.poke(31.U)
    row.payload.parentCount.poke(1.U)
    row.payload.parentPcTokens(0).valid.poke(true.B)
    row.payload.parentPcTokens(0).index.poke((stid * 4 + 1).U)
    row.payload.parentPcTokens(0).byteOffset.poke(6.U)
    row.payload.parentPcTokens(0).allocationEpoch.poke(11.U)
    row.payload.opcode.poke(42.U)
    request.pcReadRequired.poke(pcRequired.B)
    request.pcParentIndex.poke(0.U)
    dut.io.p1.valid.poke(true.B)
  }

  private def grantRead(dut: OooIexP1I2Lane): Unit = {
    dut.io.readDecisionValid.poke(true.B)
    dut.io.readGrant.poke(true.B)
    dut.io.sourceDataValid.poke("b0011".U)
    dut.io.sourceData(0).poke("h1122334455667788".U)
    dut.io.sourceData(1).poke("h8877665544332211".U)
    dut.io.pcDataValid.poke(true.B)
    dut.io.pcData.poke("h80000126".U)
  }

  private def pokeRecovery(
      dut: OooIexP1I2Lane,
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

  test("stages exact P T and PC reads from P1 through retained I2") {
    simulate(new OooIexP1I2Lane(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeRequest(dut, stid = 1, ridSlot = 2)
      dut.io.p1.ready.expect(true.B)
      dut.clock.step()
      dut.io.p1.valid.poke(false.B)

      dut.io.i1Occupied.expect(true.B)
      dut.io.readAttempt.valid.expect(true.B)
      dut.io.readAttempt.bits.sourceMask.expect("b0011".U)
      dut.io.readAttempt.bits.sources(0).operandClass.expect(OperandClass.P)
      dut.io.readAttempt.bits.sources(1).operandClass.expect(OperandClass.T)
      dut.io.readAttempt.bits.pcRequired.expect(true.B)
      dut.io.readAttempt.bits.pcToken.index.expect(5.U)

      grantRead(dut)
      dut.clock.step()
      dut.io.readDecisionValid.poke(false.B)
      dut.io.readGrant.poke(false.B)
      dut.io.i2.valid.expect(true.B)
      dut.io.i2.bits.sourceMask.expect("b0011".U)
      dut.io.i2.bits.sourceData(0).expect("h1122334455667788".U)
      dut.io.i2.bits.sourceData(1).expect("h8877665544332211".U)
      dut.io.i2.bits.pcValid.expect(true.B)
      dut.io.i2.bits.pc.expect("h80000126".U)

      dut.io.sourceData.foreach(_.poke("hdeadbeef".U))
      dut.io.pcData.poke("h55".U)
      dut.clock.step(2)
      dut.io.i2.bits.sourceData(0).expect("h1122334455667788".U)
      dut.io.i2.bits.pc.expect("h80000126".U)

      dut.io.i2.ready.poke(true.B)
      dut.clock.step()
      dut.io.i2.valid.expect(false.B)
      dut.io.empty.expect(true.B)
    }
  }

  test("turns an I1 read-port denial into an exact repick") {
    simulate(new OooIexP1I2Lane(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeRequest(dut, stid = 0, ridSlot = 3, pcRequired = false)
      dut.clock.step()
      dut.io.p1.valid.poke(false.B)
      dut.io.readAttempt.valid.expect(true.B)
      dut.io.readDecisionValid.poke(true.B)
      dut.io.readGrant.poke(false.B)
      dut.io.repick.valid.expect(true.B)
      dut.io.repick.bits.member.group.ridSlot.expect(3.U)
      dut.io.repick.bits.reservation.speculativeSlot.expect(1.U)
      dut.clock.step()
      dut.io.i1Occupied.expect(false.B)
      dut.io.i2.valid.expect(false.B)
      dut.io.empty.expect(true.B)
    }
  }

  test("uses exact load bypass data without requesting its RF source") {
    simulate(new OooIexP1I2Lane(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeRequest(dut, stid = 0, ridSlot = 2, pcRequired = false)
      val source = dut.io.p1.bits.row.schedule.sources(0)
      source.ready.poke(false.B)
      source.specReady.poke(true.B)
      pokeLoadToken(source.load, stid = 0, generation = 7)
      dut.io.p1.ready.expect(true.B)
      dut.clock.step()
      dut.io.p1.valid.poke(false.B)

      // A speculative source may not silently fall back to RF data.
      dut.io.i1Occupied.expect(true.B)
      dut.io.readAttempt.valid.expect(false.B)

      val candidate = dut.io.bypass(0)
      candidate.bits.poke(0.U.asTypeOf(candidate.bits))
      candidate.bits.stid.poke(0.U)
      candidate.bits.epoch.poke(7.U)
      candidate.bits.operandClass.poke(OperandClass.P)
      candidate.bits.ptag.poke(17.U)
      candidate.bits.ptagGeneration.poke(3.U)
      candidate.bits.stage.poke(OooIexBypassStage.W1)
      candidate.bits.data.poke("h1020304050607080".U)
      pokeLoadToken(candidate.bits.load, stid = 0, generation = 6)
      pokeLoadProducer(candidate.bits.producer, stid = 0)
      candidate.valid.poke(true.B)
      dut.io.readAttempt.valid.expect(false.B)

      candidate.bits.load.generation.poke(7.U)
      dut.io.readAttempt.valid.expect(true.B)
      dut.io.readAttempt.bits.sourceMask.expect("b0010".U)
      dut.io.readDecisionValid.poke(true.B)
      dut.io.readGrant.poke(true.B)
      dut.io.sourceDataValid.poke("b0010".U)
      dut.io.sourceData(1).poke("h8877665544332211".U)
      dut.clock.step()
      dut.io.readDecisionValid.poke(false.B)
      dut.io.readGrant.poke(false.B)
      candidate.valid.poke(false.B)

      dut.io.i2.valid.expect(true.B)
      dut.io.i2.bits.sourceMask.expect("b0011".U)
      dut.io.i2.bits.bypassMask.expect("b0001".U)
      dut.io.i2.bits.sourceData(0).expect("h1020304050607080".U)
      dut.io.i2.bits.sourceData(1).expect("h8877665544332211".U)
      dut.io.i2.bits.bypass(0).load.generation.expect(7.U)
      dut.clock.step()
      dut.io.i2.bits.sourceData(0).expect("h1020304050607080".U)
      dut.io.i2.bits.bypass(0).stage.expect(OooIexBypassStage.W1)

      // Retain a second dependent in I1 so one cancel must poison both lane
      // stages without serializing through a one-entry retry channel.
      pokeRequest(dut, stid = 0, ridSlot = 3, pcRequired = false)
      val secondSource = dut.io.p1.bits.row.schedule.sources(0)
      secondSource.ready.poke(false.B)
      secondSource.specReady.poke(true.B)
      pokeLoadToken(secondSource.load, stid = 0, generation = 7)
      dut.clock.step()
      dut.io.p1.valid.poke(false.B)
      dut.io.i1Occupied.expect(true.B)
      dut.io.i2.valid.expect(true.B)

      val cancel = dut.io.loadCancel(0)
      cancel.bits.poke(0.U.asTypeOf(cancel.bits))
      cancel.bits.stid.poke(0.U)
      cancel.bits.epoch.poke(7.U)
      pokeLoadToken(cancel.bits.load, stid = 0, generation = 6)
      cancel.valid.poke(true.B)
      dut.io.i2.valid.expect(true.B)
      dut.io.loadCanceled(2).valid.expect(false.B)
      dut.clock.step()
      cancel.valid.poke(false.B)
      dut.io.i2.valid.expect(true.B)

      cancel.bits.load.generation.poke(7.U)
      cancel.valid.poke(true.B)
      dut.io.i2.valid.expect(false.B)
      dut.io.i1Occupied.expect(false.B)
      dut.io.loadCanceled(1).valid.expect(true.B)
      dut.io.loadCanceled(1).bits.member.group.ridSlot.expect(3.U)
      dut.io.loadCanceled(2).valid.expect(true.B)
      dut.io.loadCanceled(2).bits.member.group.ridSlot.expect(2.U)
      dut.clock.step()
      cancel.valid.poke(false.B)
      dut.io.empty.expect(true.B)
    }
  }

  test("consumes a malformed P1 row as a typed reject") {
    simulate(new OooIexP1I2Lane(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeRequest(dut, stid = 0, ridSlot = 3)
      dut.io.p1.bits.row.schedule.member.bid.valid.poke(false.B)
      dut.io.p1.ready.expect(true.B)
      dut.io.p1Rejected.valid.expect(true.B)
      dut.io.p1Rejected.bits.identityExact.expect(false.B)
      dut.clock.step()
      dut.io.p1.valid.poke(false.B)
      dut.io.empty.expect(true.B)
    }
  }

  test("rejects a partial readyless response without publishing I2") {
    simulate(new OooIexP1I2Lane(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeRequest(dut, stid = 0, ridSlot = 4)
      dut.clock.step()
      dut.io.p1.valid.poke(false.B)
      dut.io.readDecisionValid.poke(true.B)
      dut.io.readGrant.poke(true.B)
      dut.io.sourceDataValid.poke("b0001".U)
      dut.io.pcDataValid.poke(true.B)
      dut.io.readRejected.valid.expect(true.B)
      dut.io.readRejected.bits.sourceMask.expect("b0011".U)
      dut.io.readRejected.bits.sourceDataValid.expect("b0001".U)
      dut.io.repick.valid.expect(true.B)
      dut.io.repick.bits.member.group.ridSlot.expect(4.U)
      dut.clock.step()
      dut.io.readDecisionValid.poke(false.B)
      dut.io.readGrant.poke(false.B)
      dut.io.i1Occupied.expect(false.B)
      dut.io.i2.valid.expect(false.B)
      dut.io.empty.expect(true.B)
    }
  }

  test("applies recovery only to a matching retained I1 or I2 member") {
    simulate(new OooIexP1I2Lane(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeRequest(dut, stid = 1, ridSlot = 2)
      dut.clock.step()
      dut.io.p1.valid.poke(false.B)

      pokeRecovery(dut, stid = 0, newOccupied = 1)
      dut.io.i1Occupied.expect(true.B)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.i1Occupied.expect(true.B)

      grantRead(dut)
      dut.clock.step()
      dut.io.readDecisionValid.poke(false.B)
      dut.io.readGrant.poke(false.B)
      dut.io.i2.valid.expect(true.B)

      pokeRecovery(dut, stid = 1, newOccupied = 1)
      dut.io.i2.valid.expect(false.B)
      dut.io.recoveryCanceled(1).valid.expect(true.B)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.i2.valid.expect(false.B)
      dut.io.empty.expect(true.B)
    }
  }
}
