package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite

class OooIexBruPipelineSpec extends AnyFunSuite with ChiselSim {
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

  private def clear(dut: OooIexBruPipeline): Unit = {
    dut.io.e1.valid.poke(false.B)
    dut.io.e1.bits.poke(0.U.asTypeOf(dut.io.e1.bits))
    dut.io.e2.ready.poke(false.B)
    dut.io.recoveryApply.valid.poke(false.B)
    dut.io.recoveryApply.bits.poke(
      0.U.asTypeOf(dut.io.recoveryApply.bits))
    dut.io.loadCancel.foreach(
      _.poke(0.U.asTypeOf(dut.io.loadCancel.head)))
  }

  private def pokeMember(target: RobMemberKey, ridSlot: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(1.U)
    target.group.ridSlot.poke(ridSlot.U)
    target.group.ridGeneration.poke(1.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(5.U)
    target.brobGeneration.poke(2.U)
    target.memberIndex.poke(0.U)
    target.residentGeneration.poke(4.U)
  }

  private def pokeLoadToken(
      target: OooIexLoadGeneration,
      generation: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.valid.poke(true.B)
    pokeMember(target.producer, ridSlot = 0)
    target.generation.poke(generation.U)
  }

  private def pokeExecute(
      dut: OooIexBruPipeline,
      opcode: Int,
      ridSlot: Int,
      sources: Seq[BigInt],
      immediate: Option[BigInt] = None,
      pc: Option[BigInt] = None,
      destination: Option[DestinationKind.Type] = None,
      speculativeSource: Boolean = false): Unit = {
    val execute = dut.io.e1.bits
    execute.poke(0.U.asTypeOf(execute))
    execute.ownerClass.poke(OooUopClass.Bru)
    execute.ownerLane.poke(1.U)
    execute.slotGeneration.poke(ridSlot.U)

    val i2 = execute.i2
    val row = i2.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(1.U)
    row.epoch.poke(7.U)
    row.transactionId.poke((100 + ridSlot).U)
    pokeMember(row.member, ridSlot)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(OooUopClass.Bru)
    row.reservation.bank.poke(0.U)
    row.reservation.writePort.poke(0.U)
    row.reservation.speculativeSlot.poke(ridSlot.U)
    row.reservation.reservationEpoch.poke(9.U)
    row.inFlight.poke(true.B)
    sources.zipWithIndex.foreach { case (value, index) =>
      row.sources(index).valid.poke(true.B)
      row.sources(index).ready.poke((!speculativeSource).B)
      row.sources(index).specReady.poke(speculativeSource.B)
      row.sources(index).operandClass.poke(OperandClass.P)
      row.sources(index).ptag.poke((17 + index).U)
      row.sources(index).ptagGeneration.poke(3.U)
      i2.sourceData(index).poke(value.U)
      if (speculativeSource && index == 0) {
        pokeLoadToken(row.sources(index).load, generation = 7)
        pokeLoadToken(i2.bypass(index).load, generation = 7)
      }
    }
    i2.sourceMask.poke(((BigInt(1) << sources.length) - 1).U)
    i2.bypassMask.poke((if (speculativeSource) 1 else 0).U)
    i2.pcValid.poke(pc.nonEmpty.B)
    pc.foreach(value => i2.pc.poke(value.U))

    val payload = i2.row.payload
    val rule = OooOpcodeRecipeTable.Rules.find(_.opcode == opcode).get
    payload.opcode.poke(opcode.U)
    payload.recipe.valid.poke(true.B)
    payload.recipe.opcode.poke(opcode.U)
    payload.recipe.disposition.poke(rule.disposition.U)
    payload.recipe.sideEffectOwner.poke(rule.sideEffectOwner.U)
    payload.recipe.dispatchClass.poke(rule.dispatchClass.U)
    payload.recipe.pSourceCount.poke(rule.pSourceCount.U)
    payload.recipe.pDestinationCount.poke(rule.pDestinationCount.U)
    payload.immediateValid.poke(immediate.nonEmpty.B)
    immediate.foreach(value => payload.immediate.poke(value.U))

    destination.foreach { kind =>
      row.destinations(0).valid.poke(true.B)
      row.destinations(0).kind.poke(kind)
      row.destinations(0).atag.poke(6.U)
      row.destinations(0).ptag.poke(31.U)
      row.destinations(0).ptagGeneration.poke(2.U)
      row.destinations(0).localTag.poke(5.U)
      row.destinations(0).localSequence.valid.poke(true.B)
      row.destinations(0).localSequence.index.poke(5.U)
      row.destinations(0).localSequence.generation.poke(1.U)
    }
    dut.io.e1.valid.poke(true.B)
  }

  private def pokeRecovery(
      dut: OooIexBruPipeline,
      newOccupied: Int): Unit = {
    val plan = dut.io.recoveryApply.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.valid.poke(true.B)
    plan.oldHead.valid.poke(true.B)
    plan.oldHead.peId.poke(3.U)
    plan.oldHead.stid.poke(1.U)
    plan.oldHead.ridSlot.poke(0.U)
    plan.oldHead.ridGeneration.poke(1.U)
    plan.oldOccupied.poke(4.U)
    plan.newOccupied.poke(newOccupied.U)
    dut.io.recoveryApply.valid.poke(true.B)
  }

  test("keeps the supported BRU whitelist aligned with generated recipes") {
    OooIexBruPipeline.SupportedOpcodes.foreach { opcode =>
      val rules = OooOpcodeRecipeTable.Rules.filter(_.opcode == opcode)
      assert(rules.nonEmpty, s"BRU opcode $opcode is absent from the catalog")
      assert(rules.forall(_.disposition == OooOpcodeDisposition.Dispatch))
      assert(rules.forall(_.dispatchClass == OooDispatchClass.Bru))
      assert(rules.forall(_.sideEffectOwner == OooSideEffectOwner.Bctrl))
    }
    OooIexBruPipeline.PcValueOpcodes.foreach { opcode =>
      assert(OooOpcodeRecipeTable.Rules.filter(_.opcode == opcode)
        .forall(_.pcReadRequired))
    }
  }

  test("retains PC values and immediate comparisons in E2") {
    simulate(new OooIexBruPipeline(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_ADDTPC, ridSlot = 1,
        sources = Seq.empty, immediate = Some(0x9000),
        pc = Some(BigInt("400054f8", 16)),
        destination = Some(DestinationKind.T))
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      dut.io.e2.valid.expect(true.B)
      dut.io.e2.bits.writeback.valid.expect(true.B)
      dut.io.e2.bits.writeback.data.expect(BigInt("4000e000", 16).U)
      dut.io.e2.bits.writeback.destination.kind.expect(DestinationKind.T)
      dut.clock.step(2)
      dut.io.e2.bits.writeback.data.expect(BigInt("4000e000", 16).U)

      dut.io.e2.ready.poke(true.B)
      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_CMP_LTI, ridSlot = 2,
        sources = Seq(BigInt("ffffffffffffffff", 16)), immediate = Some(0),
        destination = Some(DestinationKind.Gpr))
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      dut.io.e2.ready.poke(false.B)
      dut.io.e2.valid.expect(true.B)
      dut.io.e2.bits.execute.i2.row.member.group.ridSlot.expect(2.U)
      dut.io.e2.bits.writeback.data.expect(1.U)
      dut.io.e2.bits.bctrl.valid.expect(false.B)
    }
  }

  test("retains BCTRL updates and applies exact kill or rejection") {
    simulate(new OooIexBruPipeline(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_C_SETC_EQ, ridSlot = 2,
        sources = Seq(55, 55))
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      dut.io.e2.bits.writeback.valid.expect(false.B)
      dut.io.e2.bits.bctrl.valid.expect(true.B)
      dut.io.e2.bits.bctrl.kind.expect(OooIexBctrlUpdateKind.Condition)
      dut.io.e2.bits.bctrl.condition.expect(true.B)
      pokeRecovery(dut, newOccupied = 2)
      dut.io.e2.valid.expect(false.B)
      dut.io.killedE2.valid.expect(true.B)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)

      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_C_SETC_TGT, ridSlot = 1,
        sources = Seq(0x1234), speculativeSource = true)
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      dut.io.e2.bits.bctrl.kind.expect(OooIexBctrlUpdateKind.Target)
      dut.io.e2.bits.bctrl.targetValid.expect(true.B)
      dut.io.e2.bits.bctrl.target.expect(0x1234.U)
      val cancel = dut.io.loadCancel(0)
      cancel.bits.poke(0.U.asTypeOf(cancel.bits))
      cancel.bits.stid.poke(1.U)
      cancel.bits.epoch.poke(7.U)
      pokeLoadToken(cancel.bits.load, generation = 6)
      cancel.valid.poke(true.B)
      dut.io.e2.valid.expect(true.B)
      cancel.bits.load.generation.poke(7.U)
      dut.io.e2.valid.expect(false.B)
      dut.io.killedE2.valid.expect(true.B)
      dut.clock.step()
      cancel.valid.poke(false.B)

      // J needs redirect ownership and B.Z/B.NZ need explicit EXEC/condition
      // state, so both remain outside this first fail-closed BRU subset.
      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_J, ridSlot = 3,
        sources = Seq.empty, immediate = Some(4), pc = Some(0x1000))
      dut.io.e1.ready.expect(false.B)
      dut.io.rejected.valid.expect(true.B)
      dut.io.rejected.bits.supportedOpcode.expect(false.B)
      dut.io.e1.bits.ownerClass.poke(OooUopClass.Alu)
      dut.io.rejected.bits.classExact.expect(false.B)
      dut.clock.step()
      dut.io.occupied.expect(false.B)
    }
  }
}
