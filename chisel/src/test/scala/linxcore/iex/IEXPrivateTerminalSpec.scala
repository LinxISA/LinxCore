package linxcore.iex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.DestinationKind
import linxcore.ooo._
import linxcore.params.{CoreParams, SimulationParamProfiles}
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class IEXPrivateTerminalSpec extends AnyFunSuite with ChiselSim {
  private val core: CoreParams = SimulationParamProfiles.W4

  private def pokeMember(
      target: RobMemberKey,
      ridSlot: Int = 2,
      memberIndex: Int = 0): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(0.U)
    target.group.ridSlot.poke(ridSlot.U)
    target.group.ridGeneration.poke(5.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(2.U)
    target.brobGeneration.poke(7.U)
    target.memberIndex.poke(memberIndex.U)
    target.residentGeneration.poke(8.U)
  }

  private def pokeRob(
      target: RobIdentity,
      ridSlot: Int = 2,
      memberIndex: Int = 0): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.peId.poke(3.U)
    target.stid.poke(0.U)
    target.ridSlot.poke(ridSlot.U)
    target.ridGeneration.poke(5.U)
    target.memberIndex.poke(memberIndex.U)
    target.residentGeneration.poke(8.U)
    target.bid.poke(2.U)
    target.brobGeneration.poke(7.U)
  }

  private def expectRob(
      target: RobIdentity,
      ridSlot: Int = 2,
      memberIndex: Int = 0): Unit = {
    target.peId.expect(3.U)
    target.stid.expect(0.U)
    target.ridSlot.expect(ridSlot.U)
    target.ridGeneration.expect(5.U)
    target.memberIndex.expect(memberIndex.U)
    target.residentGeneration.expect(8.U)
    target.bid.expect(2.U)
    target.brobGeneration.expect(7.U)
  }

  private def expectInstruction(target: InstructionIdentity): Unit = {
    target.peId.expect(3.U)
    target.stid.expect(0.U)
    target.instructionId.expect(91.U)
    target.epoch.expect(11.U)
  }

  private def pokeDestination(
      target: OooIexDestinationState,
      kind: DestinationKind.Type,
      ordinal: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.valid.poke(true.B)
    target.kind.poke(kind)
    target.atag.poke((4 + ordinal).U)
    target.ptag.poke((30 + ordinal).U)
    target.ptagGeneration.poke(9.U)
    target.localTag.poke((4 + ordinal).U)
    target.localSequence.valid.poke(true.B)
    target.localSequence.index.poke((4 + ordinal).U)
    target.localSequence.generation.poke(13.U)
  }

  private def pokeExecute(
      execute: OooIexExecuteTransaction,
      owner: OooUopClass.Type,
      dispatchClass: Int,
      sideEffectOwner: Int,
      destinations: Seq[DestinationKind.Type],
      ridSlot: Int = 2): Unit = {
    execute.poke(0.U.asTypeOf(execute))
    execute.ownerClass.poke(owner)
    val row = execute.i2.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(0.U)
    row.epoch.poke(11.U)
    row.transactionId.poke(43.U)
    pokeMember(row.member, ridSlot)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(owner)

    val payload = execute.i2.row.payload
    payload.opcode.poke(0x51.U)
    payload.recipe.valid.poke(true.B)
    payload.recipe.opcode.poke(0x51.U)
    payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    payload.recipe.dispatchClass.poke(dispatchClass.U)
    payload.recipe.sideEffectOwner.poke(sideEffectOwner.U)
    payload.uopKey.primaryParent.valid.poke(true.B)
    payload.uopKey.primaryParent.peId.poke(3.U)
    payload.uopKey.primaryParent.stid.poke(0.U)
    payload.uopKey.primaryParent.instructionId.poke(91.U)
    payload.uopKey.uopCount.poke(1.U)
    destinations.zipWithIndex.foreach { case (kind, ordinal) =>
      pokeDestination(row.destinations(ordinal), kind, ordinal)
    }
  }

  private def pokeAlu(
      dut: OooIexTerminalPublish,
      destinations: Seq[DestinationKind.Type] = Seq(DestinationKind.Gpr),
      values: Seq[BigInt] = Seq(BigInt(0x100))): Unit = {
    val terminal = dut.io.alu.bits
    terminal.poke(0.U.asTypeOf(terminal))
    pokeExecute(
      terminal.execute,
      OooUopClass.Alu,
      OooDispatchClass.Alu,
      OooSideEffectOwner.Iex,
      destinations)
    destinations.zip(values).zipWithIndex.foreach {
      case ((kind, value), ordinal) =>
        terminal.writebacks(ordinal).valid.poke(true.B)
        pokeDestination(terminal.writebacks(ordinal).destination, kind, ordinal)
        terminal.writebacks(ordinal).data.poke(value.U)
    }
    dut.io.alu.valid.poke(true.B)
  }

  private def pokeNoDestinationBru(dut: OooIexTerminalPublish): Unit = {
    val terminal = dut.io.bru.bits
    terminal.poke(0.U.asTypeOf(terminal))
    pokeExecute(
      terminal.execute,
      OooUopClass.Bru,
      OooDispatchClass.Bru,
      OooSideEffectOwner.Bctrl,
      Seq.empty)
    terminal.bctrl.valid.poke(true.B)
    terminal.bctrl.kind.poke(OooIexBctrlUpdateKind.Condition)
    terminal.bctrl.condition.poke(true.B)
    dut.io.bru.valid.poke(true.B)
  }

  private def pokeTrapLoad(dut: OooIexTerminalPublish): Unit = {
    val result = dut.io.load.bits
    result.poke(0.U.asTypeOf(result))
    pokeExecute(
      result.agu.execute,
      OooUopClass.Agu,
      OooDispatchClass.Agu,
      OooSideEffectOwner.Lsu,
      Seq(DestinationKind.Gpr),
      ridSlot = 2)
    pokeDestination(result.agu.destination, DestinationKind.Gpr, 0)
    result.load.valid.poke(true.B)
    pokeMember(result.load.producer, ridSlot = 2)
    result.load.generation.poke(17.U)
    result.data.poke(0x5555.U)
    result.faultValid.poke(true.B)
    result.faultCause.poke(23.U)
    dut.io.load.valid.poke(true.B)
  }

  private def pokePlan(
      target: RecoveryPlan,
      phase: RecoveryPhase.Type,
      transactionId: BigInt = 70,
      redirectPc: BigInt = 0x8000,
      newEpoch: Int = 12): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.transactionId.poke(transactionId.U)
    target.phase.poke(phase)
    target.cause.poke(RecoveryCause.Exception)
    pokeRob(target.trigger, ridSlot = 1)
    target.survivingTailValid.poke(true.B)
    pokeRob(target.survivingTail, ridSlot = 1)
    target.redirectPc.poke(redirectPc.U)
    target.newEpoch.poke(newEpoch.U)
    target.firstKilledValid.poke(true.B)
    pokeRob(target.firstKilled, ridSlot = 2)
    pokeRob(target.lastKilled, ridSlot = 2)
    target.killedGroupCount.poke(1.U)
    target.killedMemberCount.poke(1.U)
  }

  private def expectPlan(
      target: RecoveryPlan,
      phase: RecoveryPhase.Type,
      transactionId: BigInt = 70,
      redirectPc: BigInt = 0x8000,
      newEpoch: Int = 12): Unit = {
    target.transactionId.expect(transactionId.U)
    target.phase.expect(phase)
    target.cause.expect(RecoveryCause.Exception)
    expectRob(target.trigger, ridSlot = 1)
    target.survivingTailValid.expect(true.B)
    expectRob(target.survivingTail, ridSlot = 1)
    target.redirectPc.expect(redirectPc.U)
    target.newEpoch.expect(newEpoch.U)
    target.firstKilledValid.expect(true.B)
    expectRob(target.firstKilled, ridSlot = 2)
    expectRob(target.lastKilled, ridSlot = 2)
    target.killedGroupCount.expect(1.U)
    target.killedMemberCount.expect(1.U)
  }

  private def clear(dut: OooIexTerminalPublish): Unit = {
    dut.io.alu.valid.poke(false.B)
    dut.io.alu.bits.poke(0.U.asTypeOf(dut.io.alu.bits))
    dut.io.bru.valid.poke(false.B)
    dut.io.bru.bits.poke(0.U.asTypeOf(dut.io.bru.bits))
    dut.io.load.valid.poke(false.B)
    dut.io.load.bits.poke(0.U.asTypeOf(dut.io.load.bits))
    dut.io.pWrite.foreach(_.ready.poke(true.B))
    dut.io.tWrite.foreach(_.ready.poke(true.B))
    dut.io.uWrite.foreach(_.ready.poke(true.B))
    dut.io.wakeup.foreach(_.ready.poke(true.B))
    dut.io.bctrl.ready.poke(true.B)
    dut.io.trace.ready.poke(true.B)
    dut.io.robResolve.ready.poke(true.B)
    dut.io.recoveryEvent.ready.poke(true.B)
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
  }

  private def resetAndClear(dut: OooIexTerminalPublish): Unit = {
    clear(dut)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def clearSystemCmd(dut: OooIexSystemCmdTerminal): Unit = {
    dut.io.system.foreach { source =>
      source.valid.poke(false.B)
      source.bits.poke(0.U.asTypeOf(source.bits))
    }
    dut.io.cmd.foreach { source =>
      source.valid.poke(false.B)
      source.bits.poke(0.U.asTypeOf(source.bits))
    }
    dut.io.robNoflushReady.ready.poke(false.B)
    dut.io.robNoflush.valid.poke(false.B)
    dut.io.robNoflush.bits.poke(0.U.asTypeOf(dut.io.robNoflush.bits))
    dut.io.systemIssue.foreach(_.ready.poke(true.B))
    dut.io.cmdIssue.ready.poke(true.B)
    dut.io.robResolve.ready.poke(true.B)
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
  }

  private def resetAndClearSystemCmd(dut: OooIexSystemCmdTerminal): Unit = {
    clearSystemCmd(dut)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def pokeSystem(dut: OooIexSystemCmdTerminal): Unit = {
    val execute = dut.io.system.head.bits
    pokeExecute(
      execute,
      OooUopClass.Sys,
      OooDispatchClass.Sys,
      OooSideEffectOwner.Commit,
      Seq.empty)
    execute.i2.row.payload.immediateValid.poke(true.B)
    execute.i2.row.payload.immediate.poke(0x1234.U)
    dut.io.system.head.valid.poke(true.B)
  }

  private def pokeCmd(dut: OooIexSystemCmdTerminal): Unit = {
    val execute = dut.io.cmd.head.bits
    pokeExecute(
      execute,
      OooUopClass.Cmd,
      OooDispatchClass.Cmd,
      OooSideEffectOwner.Commit,
      Seq.empty)
    execute.i2.sourceMask.poke("b011".U)
    execute.i2.row.sources(0).valid.poke(true.B)
    execute.i2.row.sources(1).valid.poke(true.B)
    execute.i2.sourceData(0).poke(0x1111.U)
    execute.i2.sourceData(1).poke(0x2222.U)
    dut.io.cmd.head.valid.poke(true.B)
  }

  private def pokePermit(
      dut: OooIexSystemCmdTerminal,
      transactionId: BigInt = 43,
      ridSlot: Int = 2): Unit = {
    dut.io.robNoflush.bits.poke(0.U.asTypeOf(dut.io.robNoflush.bits))
    dut.io.robNoflush.bits.transactionId.poke(transactionId.U)
    dut.io.robNoflush.bits.instruction.peId.poke(3.U)
    dut.io.robNoflush.bits.instruction.stid.poke(0.U)
    dut.io.robNoflush.bits.instruction.instructionId.poke(91.U)
    dut.io.robNoflush.bits.instruction.epoch.poke(11.U)
    pokeRob(dut.io.robNoflush.bits.rob, ridSlot)
    dut.io.robNoflush.valid.poke(true.B)
  }

  test("publishes destination ordinal zero while every destination fires atomically") {
    simulate(new OooIexTerminalPublish(core)) { dut =>
      resetAndClear(dut)
      pokeAlu(
        dut,
        Seq(DestinationKind.T, DestinationKind.U),
        Seq(BigInt(0x1111), BigInt(0x2222)))

      dut.io.robResolve.valid.expect(true.B)
      dut.io.robResolve.bits.transactionId.expect(43.U)
      expectRob(dut.io.robResolve.bits.rob)
      dut.io.robResolve.bits.destinationValid.expect(true.B)
      dut.io.robResolve.bits.destinationIndex.expect(0.U)
      dut.io.robResolve.bits.value.expect(0x1111.U)
      dut.io.robResolve.bits.trap.valid.expect(false.B)
      dut.io.tWrite(0).valid.expect(true.B)
      dut.io.uWrite(1).valid.expect(true.B)
      dut.io.wakeup(0).valid.expect(true.B)
      dut.io.wakeup(1).valid.expect(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.recoveryEvent.valid.expect(false.B)
      dut.io.terminalFire.expect(true.B)
    }
  }

  test("publishes zero destination fields for a resolved no-destination member") {
    simulate(new OooIexTerminalPublish(core)) { dut =>
      resetAndClear(dut)
      pokeNoDestinationBru(dut)

      dut.io.robResolve.valid.expect(true.B)
      dut.io.robResolve.bits.destinationValid.expect(false.B)
      dut.io.robResolve.bits.destinationIndex.expect(0.U)
      dut.io.robResolve.bits.value.expect(0.U)
      dut.io.pWrite.foreach(_.valid.expect(false.B))
      dut.io.tWrite.foreach(_.valid.expect(false.B))
      dut.io.uWrite.foreach(_.valid.expect(false.B))
      dut.io.wakeup.foreach(_.valid.expect(false.B))
      dut.io.recoveryEvent.valid.expect(false.B)
      dut.io.terminalFire.expect(true.B)
    }
  }

  test("does not let an unused recovery-event sink block an ordinary resolve") {
    simulate(new OooIexTerminalPublish(core)) { dut =>
      resetAndClear(dut)
      pokeAlu(dut)
      dut.io.recoveryEvent.ready.poke(false.B)

      dut.io.recoveryEvent.valid.expect(false.B)
      dut.io.robResolve.valid.expect(true.B)
      dut.io.pWrite(0).valid.expect(true.B)
      dut.io.wakeup(0).valid.expect(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.terminalFire.expect(true.B)
    }
  }

  test("holds RF wakeup trace and resolve until one ordinary terminal fire") {
    simulate(new OooIexTerminalPublish(core)) { dut =>
      resetAndClear(dut)
      pokeAlu(dut)
      dut.io.robResolve.ready.poke(false.B)

      dut.io.alu.ready.expect(false.B)
      dut.io.pWrite(0).valid.expect(false.B)
      dut.io.wakeup(0).valid.expect(false.B)
      dut.io.trace.valid.expect(false.B)
      dut.io.terminalFire.expect(false.B)

      dut.io.robResolve.ready.poke(true.B)
      dut.io.alu.ready.expect(true.B)
      dut.io.pWrite(0).valid.expect(true.B)
      dut.io.wakeup(0).valid.expect(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.robResolve.valid.expect(true.B)
      dut.io.terminalFire.expect(true.B)
    }
  }

  test("suppresses RF and wakeup for a trap and emits the exact trap recovery event") {
    simulate(new OooIexTerminalPublish(core)) { dut =>
      resetAndClear(dut)
      pokeTrapLoad(dut)

      dut.io.pWrite.foreach(_.valid.expect(false.B))
      dut.io.tWrite.foreach(_.valid.expect(false.B))
      dut.io.uWrite.foreach(_.valid.expect(false.B))
      dut.io.wakeup.foreach(_.valid.expect(false.B))
      dut.io.robResolve.valid.expect(true.B)
      dut.io.robResolve.bits.transactionId.expect(43.U)
      expectRob(dut.io.robResolve.bits.rob)
      dut.io.robResolve.bits.destinationValid.expect(false.B)
      dut.io.robResolve.bits.destinationIndex.expect(0.U)
      dut.io.robResolve.bits.value.expect(0.U)
      dut.io.robResolve.bits.trap.valid.expect(true.B)
      dut.io.robResolve.bits.trap.kind.expect(TrapKind.Exception)
      expectInstruction(dut.io.robResolve.bits.trap.instruction)
      expectRob(dut.io.robResolve.bits.trap.rob)
      dut.io.robResolve.bits.trap.cause.expect(23.U)
      dut.io.robResolve.bits.trap.targetPc.expect(0.U)
      dut.io.robResolve.bits.trap.tval.expect(0.U)

      dut.io.recoveryEvent.valid.expect(true.B)
      dut.io.recoveryEvent.bits.transactionId.expect(43.U)
      dut.io.recoveryEvent.bits.cause.expect(RecoveryCause.Exception)
      expectRob(dut.io.recoveryEvent.bits.trigger)
      expectInstruction(dut.io.recoveryEvent.bits.instruction)
      dut.io.recoveryEvent.bits.redirectPc.expect(0.U)
      dut.io.recoveryEvent.bits.trap.valid.expect(true.B)
      dut.io.recoveryEvent.bits.trap.kind.expect(TrapKind.Exception)
      expectInstruction(dut.io.recoveryEvent.bits.trap.instruction)
      expectRob(dut.io.recoveryEvent.bits.trap.rob)
      dut.io.recoveryEvent.bits.trap.cause.expect(23.U)
      dut.io.recoveryEvent.bits.trap.targetPc.expect(0.U)
      dut.io.recoveryEvent.bits.trap.tval.expect(0.U)
      dut.io.terminalFire.expect(true.B)
    }
  }

  test("holds trap trace resolve and recovery event until one terminal fire") {
    simulate(new OooIexTerminalPublish(core)) { dut =>
      resetAndClear(dut)
      pokeTrapLoad(dut)
      dut.io.recoveryEvent.ready.poke(false.B)

      dut.io.load.ready.expect(false.B)
      dut.io.trace.valid.expect(false.B)
      dut.io.robResolve.valid.expect(false.B)
      dut.io.terminalFire.expect(false.B)

      dut.io.recoveryEvent.ready.poke(true.B)
      dut.io.load.ready.expect(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.robResolve.valid.expect(true.B)
      dut.io.recoveryEvent.valid.expect(true.B)
      dut.io.terminalFire.expect(true.B)
    }
  }

  test("returns the complete canonical recovery plan during prepare without mutation") {
    simulate(new OooIexTerminalPublish(core)) { dut =>
      resetAndClear(dut)
      pokeAlu(dut)
      dut.io.robResolve.ready.poke(false.B)
      dut.io.recovery.prepare.valid.poke(true.B)
      pokePlan(dut.io.recovery.prepare.bits, RecoveryPhase.Prepare)

      dut.io.recovery.prepare.ready.expect(true.B)
      dut.io.recovery.prepared.valid.expect(true.B)
      expectPlan(dut.io.recovery.prepared.bits, RecoveryPhase.Prepare)
      dut.io.robResolve.valid.expect(false.B)
      dut.io.terminalFire.expect(false.B)
    }
  }

  test("preserves a held terminal transaction after matching recovery abort") {
    simulate(new OooIexTerminalPublish(core)) { dut =>
      resetAndClear(dut)
      pokeAlu(dut)
      dut.io.robResolve.ready.poke(false.B)
      dut.io.recovery.prepare.valid.poke(true.B)
      pokePlan(dut.io.recovery.prepare.bits, RecoveryPhase.Prepare)
      dut.io.recovery.prepared.valid.expect(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.abort.valid.poke(true.B)
      pokePlan(dut.io.recovery.abort.bits, RecoveryPhase.Abort)
      dut.clock.step()
      dut.io.recovery.abort.valid.poke(false.B)
      dut.io.robResolve.ready.poke(true.B)

      dut.io.alu.ready.expect(true.B)
      dut.io.robResolve.valid.expect(true.B)
      dut.io.terminalFire.expect(true.B)
    }
  }

  test("keeps a held terminal fenced after nonmatching apply until matching abort") {
    simulate(new OooIexTerminalPublish(core)) { dut =>
      resetAndClear(dut)
      pokeAlu(dut)
      dut.io.robResolve.ready.poke(false.B)
      dut.io.recovery.prepare.valid.poke(true.B)
      pokePlan(dut.io.recovery.prepare.bits, RecoveryPhase.Prepare)
      dut.io.recovery.prepared.valid.expect(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.apply.valid.poke(true.B)
      pokePlan(
        dut.io.recovery.apply.bits,
        RecoveryPhase.Apply,
        redirectPc = 0x9000,
        newEpoch = 13)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.robResolve.ready.poke(true.B)

      dut.io.alu.ready.expect(false.B)
      dut.io.robResolve.valid.expect(false.B)
      dut.io.terminalFire.expect(false.B)

      dut.io.robResolve.ready.poke(false.B)
      dut.io.recovery.abort.valid.poke(true.B)
      pokePlan(dut.io.recovery.abort.bits, RecoveryPhase.Abort)
      dut.clock.step()
      dut.io.recovery.abort.valid.poke(false.B)
      dut.io.robResolve.ready.poke(true.B)

      dut.io.alu.ready.expect(true.B)
      dut.io.robResolve.valid.expect(true.B)
      dut.io.terminalFire.expect(true.B)
    }
  }

  test("never resolves a terminal transaction killed by the matching recovery apply") {
    simulate(new OooIexTerminalPublish(core)) { dut =>
      resetAndClear(dut)
      pokeAlu(dut)
      dut.io.robResolve.ready.poke(false.B)
      dut.io.recovery.prepare.valid.poke(true.B)
      pokePlan(dut.io.recovery.prepare.bits, RecoveryPhase.Prepare)
      dut.io.recovery.prepared.valid.expect(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.apply.valid.poke(true.B)
      pokePlan(dut.io.recovery.apply.bits, RecoveryPhase.Apply)

      dut.io.alu.ready.expect(true.B)
      dut.io.robResolve.valid.expect(false.B)
      dut.io.pWrite.foreach(_.valid.expect(false.B))
      dut.io.wakeup.foreach(_.valid.expect(false.B))
      dut.io.trace.valid.expect(false.B)
      dut.io.terminalFire.expect(false.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.alu.valid.poke(false.B)
      dut.io.robResolve.ready.poke(true.B)
      dut.io.robResolve.valid.expect(false.B)
      dut.io.recoveryEvent.valid.expect(false.B)
      dut.io.terminalFire.expect(false.B)
    }
  }

  test("fires one head-authorized system side effect and no-value resolve atomically") {
    simulate(new OooIexSystemCmdTerminal(core)) { dut =>
      resetAndClearSystemCmd(dut)
      pokeSystem(dut)

      dut.io.system.head.ready.expect(false.B)
      dut.io.robNoflushReady.valid.expect(true.B)
      dut.io.robNoflushReady.bits.transactionId.expect(43.U)
      expectInstruction(dut.io.robNoflushReady.bits.instruction)
      expectRob(dut.io.robNoflushReady.bits.rob)
      dut.io.systemIssue.head.valid.expect(false.B)
      dut.io.robResolve.valid.expect(false.B)

      pokePermit(dut)
      dut.io.robNoflushReady.ready.poke(true.B)
      dut.io.robNoflush.ready.expect(true.B)
      dut.io.system.head.ready.expect(true.B)
      dut.io.systemIssue.head.valid.expect(true.B)
      dut.io.systemIssue.head.bits.transactionId.expect(43.U)
      dut.io.systemIssue.head.bits.opcode.expect(0x51.U)
      dut.io.systemIssue.head.bits.immediate.expect(0x1234.U)
      dut.io.cmdIssue.valid.expect(false.B)
      dut.io.robResolve.valid.expect(true.B)
      dut.io.robResolve.bits.destinationValid.expect(false.B)
      dut.io.robResolve.bits.destinationIndex.expect(0.U)
      dut.io.robResolve.bits.value.expect(0.U)
      dut.io.robResolve.bits.trap.valid.expect(false.B)
      dut.io.terminalFire.expect(true.B)
    }
  }

  test("holds CMD permit issue and resolve while the external sink is blocked") {
    simulate(new OooIexSystemCmdTerminal(core)) { dut =>
      resetAndClearSystemCmd(dut)
      pokeCmd(dut)
      pokePermit(dut)
      dut.io.cmdIssue.ready.poke(false.B)

      dut.io.robNoflush.ready.expect(false.B)
      dut.io.cmd.head.ready.expect(false.B)
      dut.io.cmdIssue.valid.expect(true.B)
      val held = dut.io.cmdIssue.bits.peek()
      dut.io.robResolve.valid.expect(false.B)
      dut.io.terminalFire.expect(false.B)
      dut.clock.step(2)
      dut.io.cmdIssue.valid.expect(true.B)
      dut.io.cmdIssue.bits.expect(held)

      dut.io.cmdIssue.ready.poke(true.B)
      dut.io.robNoflushReady.ready.poke(true.B)
      dut.io.robNoflush.ready.expect(true.B)
      dut.io.cmd.head.ready.expect(true.B)
      dut.io.cmdIssue.valid.expect(true.B)
      dut.io.cmdIssue.bits.sourceValid.expect("b011".U)
      dut.io.cmdIssue.bits.sourceValues(0).expect(0x1111.U)
      dut.io.cmdIssue.bits.sourceValues(1).expect(0x2222.U)
      dut.io.robResolve.valid.expect(true.B)
      dut.io.terminalFire.expect(true.B)
    }
  }

  test("fails closed on a destination-producing system row and a stale permit") {
    simulate(new OooIexSystemCmdTerminal(core)) { dut =>
      resetAndClearSystemCmd(dut)
      pokeSystem(dut)
      pokeDestination(
        dut.io.system.head.bits.i2.row.destinations(0),
        DestinationKind.Gpr,
        0)
      dut.io.robNoflushReady.valid.expect(false.B)
      dut.io.system.head.ready.expect(false.B)
      dut.io.systemIssue.head.valid.expect(false.B)

      dut.io.system.head.bits.i2.row.destinations(0).valid.poke(false.B)
      pokePermit(dut, transactionId = 44)
      dut.io.robNoflushReady.valid.expect(true.B)
      dut.io.robNoflush.ready.expect(false.B)
      dut.io.systemIssue.head.valid.expect(false.B)
      dut.io.robResolve.valid.expect(false.B)
      dut.io.terminalFire.expect(false.B)
    }
  }

  test("fences a killed system row from prepare through nonmatching apply and resumes on abort") {
    simulate(new OooIexSystemCmdTerminal(core)) { dut =>
      resetAndClearSystemCmd(dut)
      pokeSystem(dut)
      dut.io.recovery.prepared.ready.poke(false.B)
      dut.io.recovery.prepare.valid.poke(true.B)
      pokePlan(dut.io.recovery.prepare.bits, RecoveryPhase.Prepare)

      dut.io.robNoflushReady.valid.expect(false.B)
      dut.io.system.head.ready.expect(false.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.prepared.valid.expect(true.B)
      expectPlan(dut.io.recovery.prepared.bits, RecoveryPhase.Prepare)

      dut.io.recovery.prepared.ready.poke(true.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(true.B)
      pokePlan(
        dut.io.recovery.apply.bits,
        RecoveryPhase.Apply,
        redirectPc = 0x9000,
        newEpoch = 13)
      dut.io.robNoflushReady.valid.expect(false.B)
      dut.io.system.head.ready.expect(false.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.robNoflushReady.valid.expect(false.B)

      dut.io.recovery.abort.valid.poke(true.B)
      pokePlan(dut.io.recovery.abort.bits, RecoveryPhase.Abort)
      dut.clock.step()
      dut.io.recovery.abort.valid.poke(false.B)
      dut.io.robNoflushReady.valid.expect(true.B)
      dut.io.system.head.ready.expect(false.B)
    }
  }

  test("matching apply releases a killed CMD row without permit side effects") {
    simulate(new OooIexSystemCmdTerminal(core)) { dut =>
      resetAndClearSystemCmd(dut)
      pokeCmd(dut)
      dut.io.recovery.prepared.ready.poke(false.B)
      dut.io.recovery.prepare.valid.poke(true.B)
      pokePlan(dut.io.recovery.prepare.bits, RecoveryPhase.Prepare)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.prepared.ready.poke(true.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(true.B)
      pokePlan(dut.io.recovery.apply.bits, RecoveryPhase.Apply)

      dut.io.cmd.head.ready.expect(true.B)
      dut.io.robNoflushReady.valid.expect(false.B)
      dut.io.robNoflush.ready.expect(false.B)
      dut.io.cmdIssue.valid.expect(false.B)
      dut.io.robResolve.valid.expect(false.B)
      dut.io.terminalFire.expect(false.B)
    }
  }

  test("a peer STID command remains eligible during another STID recovery") {
    val peerCore = core.copy(ooo = core.ooo.copy(
      stidCount = 2,
      pcBufferEntries = 8,
      gprPhysRegs = 64))
    simulate(new OooIexSystemCmdTerminal(peerCore)) { dut =>
      resetAndClearSystemCmd(dut)
      pokeCmd(dut)
      val execute = dut.io.cmd.head.bits
      execute.i2.row.stid.poke(1.U)
      execute.i2.row.member.group.stid.poke(1.U)
      execute.i2.row.uopKey.primaryParent.stid.poke(1.U)
      dut.io.recovery.prepare.valid.poke(true.B)
      pokePlan(dut.io.recovery.prepare.bits, RecoveryPhase.Prepare)

      dut.io.robNoflushReady.valid.expect(true.B)
      dut.io.robNoflushReady.bits.instruction.stid.expect(1.U)
      dut.io.robNoflushReady.bits.rob.stid.expect(1.U)
      dut.io.cmd.head.ready.expect(false.B)
    }
  }
}
