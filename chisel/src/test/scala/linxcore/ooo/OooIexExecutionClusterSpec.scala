package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.{DestinationKind, OperandClass}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.params.ParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class OooIexExecutionClusterSpec extends AnyFunSuite with ChiselSim {
  private val core = ParamProfiles.W4
  private val profile = OooIexPhysicalProfile.fromCoreParams(core)
  private val p = profile.params

  private def clear(dut: OooIexExecutionCluster): Unit = {
    dut.io.e1.foreach { input =>
      input.valid.poke(false.B)
      input.bits.poke(0.U.asTypeOf(input.bits))
    }
    dut.io.recovery.prepare.valid.poke(false.B)
    dut.io.recovery.prepare.bits.poke(
      0.U.asTypeOf(dut.io.recovery.prepare.bits))
    dut.io.recovery.prepared.ready.poke(true.B)
    dut.io.recovery.apply.valid.poke(false.B)
    dut.io.recovery.apply.bits.poke(
      0.U.asTypeOf(dut.io.recovery.apply.bits))
    dut.io.recovery.abort.valid.poke(false.B)
    dut.io.recovery.abort.bits.poke(
      0.U.asTypeOf(dut.io.recovery.abort.bits))
    dut.io.storeAddress.foreach(_.ready.poke(true.B))
    dut.io.storeData.foreach(_.ready.poke(true.B))
    dut.io.multiCycleAlu.foreach(_.ready.poke(true.B))
    dut.io.pointerAuth.foreach(_.ready.poke(true.B))
    dut.io.floatingVector.ready.poke(true.B)
    dut.io.robNoflushReady.ready.poke(false.B)
    dut.io.robNoflush.valid.poke(false.B)
    dut.io.robNoflush.bits.poke(0.U.asTypeOf(dut.io.robNoflush.bits))
    dut.io.systemIssue.foreach(_.ready.poke(true.B))
    dut.io.cmdIssue.ready.poke(true.B)
    dut.io.systemCmdResolve.ready.poke(true.B)
    dut.io.load.liqAlloc.ready.poke(true.B)
    dut.io.load.liqAllocLoadId.poke(
      0.U.asTypeOf(dut.io.load.liqAllocLoadId))
    dut.io.load.rebind.valid.poke(false.B)
    dut.io.load.rebind.bits.poke(0.U.asTypeOf(dut.io.load.rebind.bits))
    dut.io.load.liqRebind.ready.poke(true.B)
    dut.io.load.attemptLaunch.valid.poke(false.B)
    dut.io.load.attemptLaunch.bits.poke(
      0.U.asTypeOf(dut.io.load.attemptLaunch.bits))
    dut.io.load.completion.valid.poke(false.B)
    dut.io.load.completion.bits.poke(
      0.U.asTypeOf(dut.io.load.completion.bits))
    dut.io.pWrite.foreach(_.ready.poke(true.B))
    dut.io.tWrite.foreach(_.ready.poke(true.B))
    dut.io.uWrite.foreach(_.ready.poke(true.B))
    dut.io.bctrl.foreach(_.ready.poke(true.B))
    dut.io.trace.foreach(_.ready.poke(true.B))
    dut.io.robResolve.foreach(_.ready.poke(true.B))
    dut.io.recoveryEvent.foreach(_.ready.poke(true.B))
  }

  private def pokeMember(target: RobMemberKey, ridSlot: Int): Unit = {
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(0.U)
    target.group.ridSlot.poke(ridSlot.U)
    target.group.ridGeneration.poke(1.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(5.U)
    target.brobGeneration.poke(2.U)
    target.memberIndex.poke(0.U)
    target.residentGeneration.poke(4.U)
  }

  private def pokeRoute(
      dut: OooIexExecutionCluster,
      lane: Int,
      ownerClass: OooUopClass.Type,
      dispatchClass: Int,
      capability: BigInt,
      ridSlot: Int): Unit = {
    val execute = dut.io.e1(lane).bits
    execute.poke(0.U.asTypeOf(execute))
    execute.ownerClass.poke(ownerClass)
    execute.ownerLane.poke(lane.U)
    execute.slotGeneration.poke(2.U)
    val row = execute.i2.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(0.U)
    row.epoch.poke(7.U)
    pokeMember(row.member, ridSlot)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(ownerClass)
    execute.i2.row.payload.recipe.valid.poke(true.B)
    execute.i2.row.payload.recipe.dispatchClass.poke(dispatchClass.U)
    execute.i2.row.payload.recipe.dispatchCapabilities(dispatchClass - 1)
      .poke(capability.U)
    dut.io.e1(lane).valid.poke(true.B)
  }

  private def pokeSimpleAlu(
      dut: OooIexExecutionCluster,
      lane: Int,
      ridSlot: Int,
      immediate: Int,
      ptag: Int): Unit = {
    val opcode = FrontendOpcodeDecodeTable.OP_C_MOVI
    pokeRoute(dut, lane, OooUopClass.Alu, OooDispatchClass.Alu,
      OooIexDomainCapability.mask(OooIexDomainCapability.SimpleAlu), ridSlot)
    val execute = dut.io.e1(lane).bits
    val row = execute.i2.row.schedule
    val payload = execute.i2.row.payload
    payload.opcode.poke(opcode.U)
    payload.recipe.opcode.poke(opcode.U)
    payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    payload.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
    payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Iex.U)
    payload.recipe.pSourceCount.poke(0.U)
    payload.recipe.pDestinationCount.poke(1.U)
    payload.immediateValid.poke(true.B)
    payload.immediate.poke(immediate.U)
    payload.uopKey.primaryParent.valid.poke(true.B)
    payload.uopKey.primaryParent.peId.poke(3.U)
    payload.uopKey.primaryParent.stid.poke(0.U)
    payload.uopKey.primaryParent.instructionId.poke((20 + ridSlot).U)
    payload.uopKey.uopCount.poke(1.U)
    row.destinations(0).valid.poke(true.B)
    row.destinations(0).kind.poke(DestinationKind.Gpr)
    row.destinations(0).atag.poke(6.U)
    row.destinations(0).ptag.poke(ptag.U)
    row.destinations(0).ptagGeneration.poke(3.U)
  }

  private def pokeCanonicalLoad(
      dut: OooIexExecutionCluster,
      lane: Int,
      ridSlot: Int,
      ptag: Int): Unit = {
    val opcode = FrontendOpcodeDecodeTable.OP_LDI
    val rule = OooOpcodeRecipeTable.Rules.find(_.opcode == opcode).get
    pokeRoute(dut, lane, OooUopClass.Agu, OooDispatchClass.Agu,
      OooIexDomainCapability.mask(OooIexDomainCapability.LoadAddress),
      ridSlot)

    val execute = dut.io.e1(lane).bits
    val i2 = execute.i2
    val row = i2.row.schedule
    val payload = i2.row.payload
    row.transactionId.poke((200 + ridSlot).U)
    row.inFlight.poke(true.B)
    row.reservation.bank.poke(0.U)
    row.reservation.writePort.poke(0.U)
    row.reservation.speculativeSlot.poke((ridSlot % p.iqEntriesPerBank).U)
    row.reservation.reservationEpoch.poke(9.U)
    row.sources(0).valid.poke(true.B)
    row.sources(0).ready.poke(true.B)
    row.sources(0).operandClass.poke(OperandClass.P)
    row.sources(0).ptag.poke(17.U)
    row.sources(0).ptagGeneration.poke(3.U)
    i2.sourceMask.poke(1.U)
    i2.sourceData(0).poke(0x1000.U)
    i2.pcValid.poke(true.B)
    i2.pc.poke(0x4000.U)

    payload.opcode.poke(opcode.U)
    payload.recipe.opcode.poke(opcode.U)
    payload.recipe.disposition.poke(rule.disposition.U)
    payload.recipe.recipeKind.poke(rule.recipeKind.U)
    payload.recipe.dispatchClass.poke(rule.dispatchClass.U)
    payload.recipe.sideEffectOwner.poke(rule.sideEffectOwner.U)
    payload.recipe.memoryRequestCount.poke(rule.memoryRequestCount.U)
    payload.recipe.pcReadRequired.poke(rule.pcReadRequired.B)
    payload.recipe.pSourceCount.poke(rule.pSourceCount.U)
    payload.recipe.pDestinationCount.poke(rule.pDestinationCount.U)
    payload.immediateValid.poke(true.B)
    payload.immediate.poke(8.U)
    payload.memory.valid.poke(true.B)
    payload.memory.isLoad.poke(true.B)
    payload.memory.addressMode.poke(OooMemoryAddressMode.BaseOffset)
    payload.memory.accessBytes.poke(8.U)
    payload.memory.signExtend.poke(false.B)
    payload.memory.offset.poke(8.U)
    payload.memory.addressSourceMask.poke(1.U)

    payload.memoryOrder.valid.poke(true.B)
    payload.memoryOrder.memoryValid.poke(true.B)
    payload.memoryOrder.isLoad.poke(true.B)
    payload.memoryOrder.requestCount.poke(1.U)
    payload.memoryOrder.firstLsid.poke(7.U)
    payload.memoryOrder.firstTypeId.poke(4.U)
    payload.memoryOrder.before.lsid.poke(7.U)
    payload.memoryOrder.before.loadId.poke(4.U)
    payload.memoryOrder.after.lsid.poke(8.U)
    payload.memoryOrder.after.loadId.poke(5.U)

    row.destinations(0).valid.poke(true.B)
    row.destinations(0).kind.poke(DestinationKind.Gpr)
    row.destinations(0).atag.poke(6.U)
    row.destinations(0).ptag.poke(ptag.U)
    row.destinations(0).ptagGeneration.poke(3.U)
    payload.previousPDestinations(0).valid.poke(true.B)
    payload.previousPDestinations(0).ptag.poke((ptag - 10).U)
  }

  private def pokeAttempt(
      target: linxcore.lsu.LoadAttemptIdentity,
      ridSlot: Int,
      generation: BigInt): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.valid.poke(true.B)
    target.producer.valid.poke(true.B)
    target.producer.peId.poke(3.U)
    target.producer.stid.poke(0.U)
    target.producer.nativeBidValid.poke(true.B)
    target.producer.nativeBid.poke(5.U)
    target.producer.brobGeneration.poke(2.U)
    target.producer.ridSlot.poke(ridSlot.U)
    target.producer.ridGeneration.poke(1.U)
    target.producer.memberIndex.poke(0.U)
    target.producer.residentGeneration.poke(4.U)
    target.generation.poke(generation.U)
  }

  test("routes every nonlocal execution family through an explicit boundary") {
    simulate(new OooIexExecutionCluster(core)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      val stdLane = profile.pickerIndex("alu0")
      pokeRoute(dut, stdLane, OooUopClass.Std, OooDispatchClass.Std,
        OooIexDomainCapability.mask(OooIexDomainCapability.StoreData), 1)
      dut.io.storeData(0).ready.poke(false.B)
      dut.io.storeData(0).valid.expect(true.B)
      dut.io.e1(stdLane).ready.expect(false.B)
      dut.io.routeRejected(stdLane).valid.expect(false.B)
      dut.io.storeData(0).ready.poke(true.B)
      dut.io.e1(stdLane).ready.expect(true.B)
      dut.io.e1(stdLane).valid.poke(false.B)

      val cmdLane = profile.pickerIndex("cmd0")
      pokeRoute(dut, cmdLane, OooUopClass.Cmd, OooDispatchClass.Cmd,
        OooIexDomainCapability.mask(OooIexDomainCapability.EngineCommand), 2)
      val cmd = dut.io.e1(cmdLane).bits
      cmd.i2.row.transactionId.poke(202.U)
      cmd.i2.row.payload.opcode.poke(0x77.U)
      cmd.i2.row.payload.recipe.opcode.poke(0x77.U)
      cmd.i2.row.payload.recipe.disposition.poke(
        OooOpcodeDisposition.Dispatch.U)
      cmd.i2.row.payload.recipe.sideEffectOwner.poke(
        OooSideEffectOwner.Commit.U)
      cmd.i2.row.payload.uopKey.primaryParent.valid.poke(true.B)
      cmd.i2.row.payload.uopKey.primaryParent.peId.poke(3.U)
      cmd.i2.row.payload.uopKey.primaryParent.stid.poke(0.U)
      cmd.i2.row.payload.uopKey.primaryParent.instructionId.poke(22.U)
      dut.io.robNoflushReady.valid.expect(true.B)
      dut.io.robNoflushReady.bits.transactionId.expect(202.U)
      dut.io.cmdIssue.valid.expect(false.B)
      dut.io.floatingVector.valid.expect(false.B)
      dut.io.e1(cmdLane).ready.expect(false.B)

      val permit = dut.io.robNoflush.bits
      permit.transactionId.poke(202.U)
      permit.instruction.peId.poke(3.U)
      permit.instruction.stid.poke(0.U)
      permit.instruction.instructionId.poke(22.U)
      permit.instruction.epoch.poke(7.U)
      permit.rob.peId.poke(3.U)
      permit.rob.stid.poke(0.U)
      permit.rob.ridSlot.poke(2.U)
      permit.rob.ridGeneration.poke(1.U)
      permit.rob.memberIndex.poke(0.U)
      permit.rob.residentGeneration.poke(4.U)
      permit.rob.bid.poke(5.U)
      permit.rob.brobGeneration.poke(2.U)
      dut.io.robNoflush.valid.poke(true.B)
      dut.io.robNoflushReady.ready.poke(true.B)
      dut.io.cmdIssue.valid.expect(true.B)
      dut.io.systemCmdResolve.valid.expect(true.B)
      dut.io.systemCmdTerminalFire.expect(true.B)
      dut.io.e1(cmdLane).ready.expect(true.B)
      dut.io.e1(cmdLane).valid.poke(false.B)

      val specialLane = profile.pickerIndex("sys0")
      pokeRoute(dut, specialLane, OooUopClass.Alu, OooDispatchClass.Alu,
        OooIexDomainCapability.mask(OooIexDomainCapability.MultiCycleAlu,
          OooIexDomainCapability.PointerAuth), 3)
      dut.io.e1(specialLane).ready.expect(false.B)
      dut.io.routeRejected(specialLane).valid.expect(true.B)
      dut.io.routeRejected(specialLane).bits.capabilityOneHot.expect(false.B)
    }
  }

  test("executes two simple ALUs through W1 and two atomic W2 terminals") {
    simulate(new OooIexExecutionCluster(core)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      val lane0 = profile.pickerIndex("alu0")
      val lane1 = profile.pickerIndex("alu1")
      pokeSimpleAlu(dut, lane0, ridSlot = 1, immediate = 0x41, ptag = 30)
      pokeSimpleAlu(dut, lane1, ridSlot = 2, immediate = 0x52, ptag = 31)
      dut.io.e1(lane0).ready.expect(true.B)
      dut.io.e1(lane1).ready.expect(true.B)
      dut.clock.step()
      dut.io.e1(lane0).valid.poke(false.B)
      dut.io.e1(lane1).valid.poke(false.B)

      dut.io.bypass(0).valid.expect(true.B)
      dut.io.bypass(0).bits.data.expect(0x41.U)
      dut.io.bypass(1).valid.expect(true.B)
      dut.io.bypass(1).bits.data.expect(0x52.U)
      dut.clock.step()

      dut.io.terminalFireMask.expect(3.U)
      dut.io.pWrite(0).valid.expect(true.B)
      dut.io.pWrite(0).bits.key.ptag.expect(30.U)
      dut.io.pWrite(2).valid.expect(true.B)
      dut.io.pWrite(2).bits.key.ptag.expect(31.U)
      dut.io.robResolve(0).bits.rob.ridSlot.expect(1.U)
      dut.io.robResolve(1).bits.rob.ridSlot.expect(2.U)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }

  test("owns one scalar load from E1 through canonical launch and terminal") {
    simulate(new OooIexExecutionCluster(core)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      val lane = profile.pickerIndex("agu1-lda")
      val ridSlot = 3
      val loadSlot = 2
      val ptag = 31
      dut.io.load.liqAllocLoadId.valid.poke(true.B)
      dut.io.load.liqAllocLoadId.value.poke(loadSlot.U)
      dut.io.load.liqAllocLoadId.wrap.poke(false.B)
      pokeCanonicalLoad(dut, lane, ridSlot, ptag)
      dut.io.e1(lane).ready.expect(true.B)
      dut.clock.step()
      dut.io.e1(lane).valid.poke(false.B)

      dut.io.load.liqAlloc.valid.expect(true.B)
      dut.io.load.liqAlloc.bits.addr.expect(0x1008.U)
      dut.io.load.liqAlloc.bits.returnPipeIndex.expect(1.U)
      val attemptGeneration =
        dut.io.load.liqAlloc.bits.attempt.generation.peek().litValue
      dut.clock.step()
      dut.io.loadMetadataOccupied.expect(1.U)

      dut.io.load.attemptLaunch.bits.poke(
        0.U.asTypeOf(dut.io.load.attemptLaunch.bits))
      dut.io.load.attemptLaunch.bits.loadId.valid.poke(true.B)
      dut.io.load.attemptLaunch.bits.loadId.slot.poke(loadSlot.U)
      dut.io.load.attemptLaunch.bits.loadId.generation.poke(0.U)
      pokeAttempt(dut.io.load.attemptLaunch.bits.attempt,
        ridSlot, attemptGeneration)
      dut.io.load.attemptLaunch.valid.poke(true.B)
      val speculativePort = p.iexTerminalWidth * p.maxDestinationOperands + 1
      dut.io.wakeup(speculativePort).valid.expect(true.B)
      dut.io.wakeup(speculativePort).bits.ptag.expect(ptag.U)
      dut.clock.step()
      dut.io.load.attemptLaunch.valid.poke(false.B)

      val completion = dut.io.load.completion.bits
      completion.poke(0.U.asTypeOf(completion))
      completion.peId.poke(3.U)
      completion.stid.poke(0.U)
      completion.tid.poke(0.U)
      completion.payload.valid.poke(true.B)
      completion.payload.loadId.valid.poke(true.B)
      completion.payload.loadId.slot.poke(loadSlot.U)
      completion.payload.loadId.generation.poke(0.U)
      pokeAttempt(completion.payload.attempt, ridSlot, attemptGeneration)
      completion.payload.dst.valid.poke(true.B)
      completion.payload.dst.kind.poke(DestinationKind.Gpr)
      completion.payload.dst.archTag.poke(6.U)
      completion.payload.dst.physTag.poke(ptag.U)
      completion.payload.dst.oldPhysTag.poke((ptag - 10).U)
      completion.payload.data.poke(BigInt("1122334455667788", 16).U)
      dut.io.load.completion.valid.poke(true.B)

      dut.io.load.completion.ready.expect(true.B)
      dut.io.bypass(3).valid.expect(true.B)
      dut.io.bypass(3).bits.ptag.expect(ptag.U)
      dut.io.pWrite(2).valid.expect(true.B)
      dut.io.pWrite(2).bits.key.ptag.expect(ptag.U)
      dut.io.pWrite(2).bits.data.expect(
        BigInt("1122334455667788", 16).U)
      dut.io.robResolve(1).valid.expect(true.B)
      dut.io.robResolve(1).bits.rob.ridSlot.expect(ridSlot.U)
      dut.io.terminalFireMask.expect(2.U)
      dut.clock.step()
      dut.io.load.completion.valid.poke(false.B)
      dut.io.loadMetadataOccupied.expect(0.U)
      dut.io.empty.expect(true.B)
    }
  }

  test("elaborates with one canonical load owner and no migration tracker") {
    val systemVerilog = ChiselStage.emitSystemVerilog(
      new OooIexExecutionCluster(core))

    assert(systemVerilog.contains("OooIexCanonicalLoadOwnership load"))
    assert(!systemVerilog.contains("OooIexLoadUnit"))
  }
}
