package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite
import linxcore.top.interface.RecoveryPhase

class OooIexAguPipelineSpec extends AnyFunSuite with ChiselSim {
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

  private def clear(dut: OooIexAguPipeline): Unit = {
    dut.io.e1.valid.poke(false.B)
    dut.io.e1.bits.poke(0.U.asTypeOf(dut.io.e1.bits))
    dut.io.request.ready.poke(false.B)
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
      dut: OooIexAguPipeline,
      opcode: Int,
      ridSlot: Int,
      sources: Seq[BigInt],
      mode: OooMemoryAddressMode.Type,
      offset: BigInt = 0,
      accessBytes: Int = 8,
      signExtend: Boolean = false,
      pc: Option[BigInt] = Some(0x4000),
      indexMode: OooMemoryIndexMode.Type = OooMemoryIndexMode.Identity,
      indexShift: Int = 0,
      speculativeSource: Boolean = false): Unit = {
    val execute = dut.io.e1.bits
    execute.poke(0.U.asTypeOf(execute))
    execute.ownerClass.poke(OooUopClass.Agu)
    execute.ownerLane.poke(0.U)
    execute.slotGeneration.poke(ridSlot.U)

    val i2 = execute.i2
    val row = i2.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(1.U)
    row.epoch.poke(7.U)
    row.transactionId.poke((200 + ridSlot).U)
    pokeMember(row.member, ridSlot)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(OooUopClass.Agu)
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
    val sourceMask = (BigInt(1) << sources.length) - 1
    i2.sourceMask.poke(sourceMask.U)
    i2.bypassMask.poke((if (speculativeSource) 1 else 0).U)
    i2.pcValid.poke(pc.nonEmpty.B)
    pc.foreach(value => i2.pc.poke(value.U))

    val payload = i2.row.payload
    val rule = OooOpcodeRecipeTable.Rules.find(_.opcode == opcode).get
    payload.opcode.poke(opcode.U)
    payload.recipe.valid.poke(true.B)
    payload.recipe.opcode.poke(opcode.U)
    payload.recipe.disposition.poke(rule.disposition.U)
    payload.recipe.recipeKind.poke(rule.recipeKind.U)
    payload.recipe.sideEffectOwner.poke(rule.sideEffectOwner.U)
    payload.recipe.dispatchClass.poke(rule.dispatchClass.U)
    payload.recipe.memoryRequestCount.poke(rule.memoryRequestCount.U)
    payload.recipe.pcReadRequired.poke(rule.pcReadRequired.B)
    payload.recipe.pSourceCount.poke(rule.pSourceCount.U)
    payload.recipe.pDestinationCount.poke(rule.pDestinationCount.U)
    payload.immediateValid.poke((sources.length < 2).B)
    payload.immediate.poke(offset.U)
    payload.memory.valid.poke(true.B)
    payload.memory.isLoad.poke(true.B)
    payload.memory.addressMode.poke(mode)
    payload.memory.accessBytes.poke(accessBytes.U)
    payload.memory.signExtend.poke(signExtend.B)
    payload.memory.offset.poke(offset.U)
    payload.memory.indexMode.poke(indexMode)
    payload.memory.indexShift.poke(indexShift.U)
    payload.memory.addressSourceMask.poke(sourceMask.U)

    row.destinations(0).valid.poke(true.B)
    row.destinations(0).kind.poke(DestinationKind.Gpr)
    row.destinations(0).atag.poke(6.U)
    row.destinations(0).ptag.poke(31.U)
    row.destinations(0).ptagGeneration.poke(2.U)
    dut.io.e1.valid.poke(true.B)
  }

  private def pokeRecovery(
      dut: OooIexAguPipeline,
      firstKilledSlot: Int): Unit = {
    val plan = dut.io.recoveryApply.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.phase.poke(RecoveryPhase.Apply)
    plan.trigger.peId.poke(3.U)
    plan.trigger.stid.poke(1.U)
    if (firstKilledSlot < 4) {
      plan.firstKilledValid.poke(true.B)
      plan.firstKilled.peId.poke(3.U)
      plan.firstKilled.stid.poke(1.U)
      plan.firstKilled.ridSlot.poke(firstKilledSlot.U)
      plan.firstKilled.ridGeneration.poke(1.U)
      plan.lastKilled.peId.poke(3.U)
      plan.lastKilled.stid.poke(1.U)
      plan.lastKilled.ridSlot.poke(3.U)
      plan.lastKilled.ridGeneration.poke(1.U)
      plan.killedGroupCount.poke((4 - firstKilledSlot).U)
      plan.killedMemberCount.poke((4 - firstKilledSlot).U)
    }
    dut.io.recoveryApply.valid.poke(true.B)
  }

  test("keeps scalar-load AGU coverage aligned with generated recipes") {
    assert(OooIexAguPipeline.SupportedOpcodes.nonEmpty)
    OooIexAguPipeline.SupportedOpcodes.foreach { opcode =>
      val rules = OooOpcodeRecipeTable.Rules.filter(_.opcode == opcode)
      assert(rules.forall(_.recipeKind == OooOpcodeRecipeKind.ScalarLoad))
      assert(rules.forall(_.dispatchClass == OooDispatchClass.Agu))
      assert(rules.forall(_.sideEffectOwner == OooSideEffectOwner.Lsu))
      assert(rules.forall(_.memoryRequestCount == 1))
    }
  }

  test("retains normalized base offset PC relative and indexed addresses") {
    simulate(new OooIexAguPipeline(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_LDI, ridSlot = 1,
        sources = Seq(0x1000), mode = OooMemoryAddressMode.BaseOffset,
        offset = BigInt("fffffffffffffff0", 16), accessBytes = 8)
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      dut.io.request.valid.expect(true.B)
      dut.io.request.bits.address.expect(0xff0.U)
      dut.io.request.bits.accessBytes.expect(8.U)
      dut.clock.step(2)
      dut.io.request.bits.address.expect(0xff0.U)

      dut.io.request.ready.poke(true.B)
      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_LD_PCR, ridSlot = 2,
        sources = Seq.empty, mode = OooMemoryAddressMode.PcOffset,
        offset = 3, accessBytes = 8, pc = Some(0x4000))
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      dut.io.request.ready.poke(false.B)
      dut.io.request.bits.address.expect(0x4003.U)

      dut.io.request.ready.poke(true.B)
      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_LD, ridSlot = 3,
        sources = Seq(BigInt("100000000", 16), BigInt("ffffffff", 16)),
        mode = OooMemoryAddressMode.BaseIndex, accessBytes = 8,
        indexMode = OooMemoryIndexMode.SignExtend32, indexShift = 3)
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      dut.io.request.ready.poke(false.B)
      dut.io.request.bits.address.expect(BigInt("fffffff8", 16).U)
    }
  }

  test("kills exact retained requests and rejects malformed load ownership") {
    simulate(new OooIexAguPipeline(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_LDI, ridSlot = 2,
        sources = Seq(0x2000), mode = OooMemoryAddressMode.BaseOffset,
        offset = 8, speculativeSource = true)
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      val cancel = dut.io.loadCancel(0)
      cancel.bits.poke(0.U.asTypeOf(cancel.bits))
      cancel.bits.stid.poke(1.U)
      cancel.bits.epoch.poke(7.U)
      pokeLoadToken(cancel.bits.load, generation = 6)
      cancel.valid.poke(true.B)
      dut.io.request.valid.expect(true.B)
      cancel.bits.load.generation.poke(7.U)
      dut.io.request.valid.expect(false.B)
      dut.io.killedRequest.valid.expect(true.B)
      dut.clock.step()
      cancel.valid.poke(false.B)

      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_LDI, ridSlot = 3,
        sources = Seq(0x3000), mode = OooMemoryAddressMode.BaseOffset)
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      pokeRecovery(dut, firstKilledSlot = 3)
      dut.io.request.valid.expect(false.B)
      dut.io.killedRequest.valid.expect(true.B)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)

      pokeExecute(dut, FrontendOpcodeDecodeTable.OP_LDI, ridSlot = 1,
        sources = Seq(0x1000), mode = OooMemoryAddressMode.BaseOffset)
      dut.io.e1.bits.i2.row.payload.memory.valid.poke(false.B)
      dut.io.e1.ready.expect(false.B)
      dut.io.rejected.valid.expect(true.B)
      dut.io.rejected.bits.memoryExact.expect(false.B)
      dut.io.e1.bits.ownerClass.poke(OooUopClass.Alu)
      dut.io.rejected.bits.classExact.expect(false.B)
    }
  }
}
