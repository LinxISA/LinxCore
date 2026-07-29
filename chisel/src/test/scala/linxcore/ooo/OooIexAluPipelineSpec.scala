package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite

class OooIexAluPipelineSpec extends AnyFunSuite with ChiselSim {
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

  private def clear(dut: OooIexAluPipeline): Unit = {
    dut.io.e1.valid.poke(false.B)
    dut.io.e1.bits.poke(0.U.asTypeOf(dut.io.e1.bits))
    dut.io.w2.ready.poke(false.B)
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
      dut: OooIexAluPipeline,
      opcode: Int,
      ridSlot: Int,
      sources: Seq[BigInt],
      immediate: Option[BigInt] = None,
      pc: Option[BigInt] = None,
      speculativeSource: Boolean = false,
      destinationKind: DestinationKind.Type = DestinationKind.Gpr): Unit = {
    val execute = dut.io.e1.bits
    execute.poke(0.U.asTypeOf(execute))
    execute.ownerClass.poke(OooUopClass.Alu)
    execute.ownerLane.poke(0.U)
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
    row.reservation.uopClass.poke(OooUopClass.Alu)
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
    payload.opcode.poke(opcode.U)
    payload.recipe.valid.poke(true.B)
    payload.recipe.opcode.poke(opcode.U)
    payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Iex.U)
    payload.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
    val rule = OooOpcodeRecipeTable.Rules.find(_.opcode == opcode).get
    payload.recipe.pSourceCount.poke(rule.pSourceCount.U)
    payload.recipe.pDestinationCount.poke(rule.pDestinationCount.U)
    payload.recipe.tAllocationCount.poke(rule.tAllocationCount.U)
    payload.recipe.uAllocationCount.poke(rule.uAllocationCount.U)
    payload.immediateValid.poke(immediate.nonEmpty.B)
    immediate.foreach(value => payload.immediate.poke(value.U))

    row.destinations(0).valid.poke(true.B)
    row.destinations(0).kind.poke(destinationKind)
    row.destinations(0).atag.poke(6.U)
    row.destinations(0).ptag.poke(31.U)
    row.destinations(0).ptagGeneration.poke(2.U)
    dut.io.e1.valid.poke(true.B)
  }

  private def pokeRecovery(
      dut: OooIexAluPipeline,
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

  test("keeps the supported ALU whitelist aligned with generated recipes") {
    OooIexAluPipeline.SupportedOpcodes.foreach { opcode =>
      val rules = OooOpcodeRecipeTable.Rules.filter(_.opcode == opcode)
      assert(rules.nonEmpty, s"ALU opcode $opcode is absent from the catalog")
      assert(rules.forall(_.disposition == OooOpcodeDisposition.Dispatch))
      assert(rules.forall(_.dispatchClass == OooDispatchClass.Alu))
      assert(rules.forall(_.sideEffectOwner == OooSideEffectOwner.Iex))
    }
  }

  test("computes into retained W1 bypass and W2 terminal ownership") {
    simulate(new OooIexAluPipeline(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_ADDI, ridSlot = 1,
        sources = Seq(41), immediate = Some(1))
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      dut.io.w1Bypass.valid.expect(true.B)
      dut.io.w1Bypass.bits.writebacks(0).valid.expect(true.B)
      dut.io.w1Bypass.bits.writebacks(0).data.expect(42.U)
      dut.io.w1Bypass.bits.writebacks(0).destination.ptag.expect(31.U)
      dut.io.w2.valid.expect(false.B)

      // W1 advances into the empty retained W2 stage; W2 then remains stable.
      dut.clock.step()
      dut.io.w1Bypass.valid.expect(false.B)
      dut.io.w2.valid.expect(true.B)
      dut.io.w2.bits.execute.i2.row.member.group.ridSlot.expect(1.U)
      dut.io.w2.bits.writebacks(0).data.expect(42.U)
      dut.clock.step(2)
      dut.io.w2.bits.writebacks(0).data.expect(42.U)

      // A blocked W2 does not prevent the independent W1 stage from filling.
      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_ADDIW, ridSlot = 2,
        sources = Seq(BigInt("7fffffff", 16)), immediate = Some(1))
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      dut.io.w1Bypass.valid.expect(true.B)
      dut.io.w1Bypass.bits.writebacks(0).data.expect(
        BigInt("ffffffff80000000", 16).U)
      dut.io.w2.bits.execute.i2.row.member.group.ridSlot.expect(1.U)

      dut.io.w2.ready.poke(true.B)
      dut.clock.step()
      dut.io.w2.ready.poke(false.B)
      dut.io.w2.valid.expect(true.B)
      dut.io.w2.bits.execute.i2.row.member.group.ridSlot.expect(2.U)
      dut.io.w2.bits.writebacks(0).data.expect(
        BigInt("ffffffff80000000", 16).U)

      dut.io.w2.ready.poke(true.B)
      dut.clock.step()
      dut.io.w2.ready.poke(false.B)
      dut.io.empty.expect(true.B)

      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_C_MOVI, ridSlot = 3,
        sources = Seq.empty, immediate = Some(0x34))
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      dut.io.w1Bypass.bits.writebacks(0).data.expect(0x34.U)
    }
  }

  test("kills exact retained stages and rejects malformed execution") {
    simulate(new OooIexAluPipeline(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_C_ADD, ridSlot = 2,
        sources = Seq(10, 20), destinationKind = DestinationKind.T)
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      dut.io.w1Bypass.bits.writebacks(0).data.expect(30.U)
      dut.io.w1Bypass.bits.writebacks(0).destination.kind.expect(
        DestinationKind.T)
      pokeRecovery(dut, newOccupied = 2)
      dut.io.w1Bypass.valid.expect(false.B)
      dut.io.killedW1.valid.expect(true.B)
      dut.io.killedW1.bits.execute.i2.row.member.group.ridSlot.expect(2.U)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.empty.expect(true.B)

      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_C_MOVR, ridSlot = 1,
        sources = Seq(99), speculativeSource = true)
      dut.io.e1.ready.expect(true.B)
      dut.clock.step(2)
      dut.io.e1.valid.poke(false.B)
      dut.io.w2.valid.expect(true.B)
      val cancel = dut.io.loadCancel(0)
      cancel.bits.poke(0.U.asTypeOf(cancel.bits))
      cancel.bits.stid.poke(1.U)
      cancel.bits.epoch.poke(7.U)
      pokeLoadToken(cancel.bits.load, generation = 6)
      cancel.valid.poke(true.B)
      dut.io.killedW2.valid.expect(false.B)
      cancel.bits.load.generation.poke(7.U)
      dut.io.w2.valid.expect(false.B)
      dut.io.killedW2.valid.expect(true.B)
      dut.clock.step()
      cancel.valid.poke(false.B)

      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_ADD, ridSlot = 3,
        sources = Seq(1, 2))
      dut.io.e1.ready.expect(false.B)
      dut.io.rejected.valid.expect(true.B)
      dut.io.rejected.bits.supportedOpcode.expect(false.B)

      dut.io.e1.bits.ownerClass.poke(OooUopClass.Bru)
      dut.io.rejected.bits.classExact.expect(false.B)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }
}
