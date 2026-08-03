package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, Valid}
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.params.{CoreParams, SimulationParamProfiles}
import linxcore.top.interface.{RecoveryCause, RobResolveTxn}
import org.scalatest.funsuite.AnyFunSuite

private class OooIexAluTerminalHarnessIO(val core: CoreParams) extends Bundle {
  val p = OooIexPhysicalProfile.fromCoreParams(core).params
  private val publicationPorts = p.iexTerminalWidth * p.maxDestinationOperands
  val e1 = Flipped(Decoupled(new OooIexExecuteTransaction(p)))
  val pWrite = Vec(publicationPorts, Decoupled(new OooIexPFileWrite(p)))
  val tWrite = Vec(publicationPorts, Decoupled(new OooIexLocalFileWrite(p)))
  val uWrite = Vec(publicationPorts, Decoupled(new OooIexLocalFileWrite(p)))
  val wakeup = Vec(publicationPorts, Decoupled(new OooIexWakeup(p)))
  val bctrl = Vec(p.iexTerminalWidth,
    Decoupled(new OooIexTerminalBctrl(p)))
  val trace = Vec(p.iexTerminalWidth,
    Decoupled(new OooIexTerminalTrace(p)))
  val robResolve = Vec(p.iexTerminalWidth,
    Decoupled(new RobResolveTxn(core)))
  val terminalFireMask = Output(UInt(p.iexTerminalWidth.W))
  val retainedW2 = Valid(new OooIexAluTerminalTransaction(p))
  val w1Occupied = Output(Bool())
  val w2Occupied = Output(Bool())
}

private class OooIexAluTerminalHarness(val core: CoreParams) extends Module {
  val p = OooIexPhysicalProfile.fromCoreParams(core).params
  val io = IO(new OooIexAluTerminalHarnessIO(core))

  val alu = Module(new OooIexAluPipeline(p, core))
  val terminal = Module(new OooIexTerminalFabric(
    core, aluSourceCount = 1, bruSourceCount = 1, loadSourceCount = 1))
  terminal.io.recoveryEvent.foreach(_.ready := true.B)
  terminal.io.recovery.prepare.valid := false.B
  terminal.io.recovery.prepare.bits :=
    0.U.asTypeOf(terminal.io.recovery.prepare.bits)
  terminal.io.recovery.prepared.ready := true.B
  terminal.io.recovery.apply.valid := false.B
  terminal.io.recovery.apply.bits :=
    0.U.asTypeOf(terminal.io.recovery.apply.bits)
  terminal.io.recovery.abort.valid := false.B
  terminal.io.recovery.abort.bits :=
    0.U.asTypeOf(terminal.io.recovery.abort.bits)

  alu.io.e1.valid := io.e1.valid
  alu.io.e1.bits := io.e1.bits
  io.e1.ready := alu.io.e1.ready
  alu.io.recoveryApply.valid := false.B
  alu.io.recoveryApply.bits := 0.U.asTypeOf(alu.io.recoveryApply.bits)
  alu.io.loadCancel.foreach(_ := 0.U.asTypeOf(alu.io.loadCancel.head))
  terminal.io.alu(0) <> alu.io.w2
  terminal.io.bru(0).valid := false.B
  terminal.io.bru(0).bits := 0.U.asTypeOf(terminal.io.bru(0).bits)
  terminal.io.load(0).valid := false.B
  terminal.io.load(0).bits := 0.U.asTypeOf(terminal.io.load(0).bits)

  def forward[T <: Data](outer: DecoupledIO[T], inner: DecoupledIO[T]): Unit = {
    outer.valid := inner.valid
    outer.bits := inner.bits
    inner.ready := outer.ready
  }
  io.pWrite.zip(terminal.io.pWrite).foreach { case (o, i) => forward(o, i) }
  io.tWrite.zip(terminal.io.tWrite).foreach { case (o, i) => forward(o, i) }
  io.uWrite.zip(terminal.io.uWrite).foreach { case (o, i) => forward(o, i) }
  io.wakeup.zip(terminal.io.wakeup).foreach { case (o, i) => forward(o, i) }
  io.bctrl.zip(terminal.io.bctrl).foreach { case (o, i) => forward(o, i) }
  io.trace.zip(terminal.io.trace).foreach { case (o, i) => forward(o, i) }
  io.robResolve.zip(terminal.io.robResolve).foreach {
    case (o, i) => forward(o, i)
  }
  io.terminalFireMask := terminal.io.terminalFireMask
  io.retainedW2.valid := alu.io.w2.valid
  io.retainedW2.bits := alu.io.w2.bits
  io.w1Occupied := alu.io.w1Occupied
  io.w2Occupied := alu.io.w2Occupied
}

class OooIexTerminalFabricSpec extends AnyFunSuite with ChiselSim {
  private val core = SimulationParamProfiles.W4
  private val p = OooIexPhysicalProfile.fromCoreParams(core).params

  private def clear(dut: OooIexTerminalFabric): Unit = {
    dut.io.alu.foreach { source =>
      source.valid.poke(false.B)
      source.bits.poke(0.U.asTypeOf(source.bits))
    }
    dut.io.bru.foreach { source =>
      source.valid.poke(false.B)
      source.bits.poke(0.U.asTypeOf(source.bits))
    }
    dut.io.load.foreach { source =>
      source.valid.poke(false.B)
      source.bits.poke(0.U.asTypeOf(source.bits))
    }
    dut.io.pWrite.foreach(_.ready.poke(true.B))
    dut.io.tWrite.foreach(_.ready.poke(true.B))
    dut.io.uWrite.foreach(_.ready.poke(true.B))
    dut.io.wakeup.foreach(_.ready.poke(true.B))
    dut.io.bctrl.foreach(_.ready.poke(true.B))
    dut.io.trace.foreach(_.ready.poke(true.B))
    dut.io.robResolve.foreach(_.ready.poke(true.B))
    dut.io.recoveryEvent.foreach(_.ready.poke(true.B))
    dut.io.recovery.prepare.valid.poke(false.B)
    dut.io.recovery.prepare.bits.poke(
      0.U.asTypeOf(dut.io.recovery.prepare.bits))
    dut.io.recovery.prepared.ready.poke(true.B)
    dut.io.recovery.apply.valid.poke(false.B)
    dut.io.recovery.apply.bits.poke(0.U.asTypeOf(dut.io.recovery.apply.bits))
    dut.io.recovery.abort.valid.poke(false.B)
    dut.io.recovery.abort.bits.poke(0.U.asTypeOf(dut.io.recovery.abort.bits))
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def pokeMember(target: RobMemberKey, ridSlot: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(0.U)
    target.group.ridSlot.poke(ridSlot.U)
    target.group.ridGeneration.poke(1.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(2.U)
    target.brobGeneration.poke(2.U)
    target.memberIndex.poke(0.U)
    target.residentGeneration.poke(4.U)
  }

  private def expectRetainedMember(target: RobMemberKey): Unit = {
    target.group.valid.expect(true.B)
    target.group.peId.expect(3.U)
    target.group.stid.expect(0.U)
    target.group.ridSlot.expect(3.U)
    target.group.ridGeneration.expect(1.U)
    target.bid.valid.expect(true.B)
    target.bid.value.expect(2.U)
    target.brobGeneration.expect(2.U)
    target.memberIndex.expect(0.U)
    target.residentGeneration.expect(4.U)
  }

  private def expectRetainedDestination(
      destination: OooIexDestinationState): Unit = {
    destination.valid.expect(true.B)
    destination.kind.expect(DestinationKind.Gpr)
    destination.atag.expect(6.U)
    destination.ptag.expect(27.U)
    destination.ptagGeneration.expect(3.U)
    destination.localTag.expect(0.U)
    destination.localSequence.valid.expect(false.B)
    destination.localSequence.index.expect(0.U)
    destination.localSequence.generation.expect(0.U)
  }

  private def expectEmptyMember(target: RobMemberKey): Unit = {
    target.group.valid.expect(false.B)
    target.group.peId.expect(0.U)
    target.group.stid.expect(0.U)
    target.group.ridSlot.expect(0.U)
    target.group.ridGeneration.expect(0.U)
    target.bid.valid.expect(false.B)
    target.bid.value.expect(0.U)
    target.brobGeneration.expect(0.U)
    target.memberIndex.expect(0.U)
    target.residentGeneration.expect(0.U)
  }

  private def expectEmptyLoad(load: OooIexLoadGeneration): Unit = {
    load.valid.expect(false.B)
    expectEmptyMember(load.producer)
    load.transaction.value.expect(0.U)
    load.transaction.generation.expect(0.U)
    load.generation.expect(0.U)
  }

  private def pokeAlu(
      dut: OooIexTerminalFabric,
      source: Int,
      ridSlot: Int,
      ptag: Int): Unit = {
    val terminal = dut.io.alu(source).bits
    terminal.poke(0.U.asTypeOf(terminal))
    val execute = terminal.execute
    execute.ownerClass.poke(OooUopClass.Alu)
    execute.ownerLane.poke(source.U)
    execute.slotGeneration.poke(7.U)
    val row = execute.i2.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(0.U)
    row.epoch.poke(9.U)
    pokeMember(row.member, ridSlot)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(OooUopClass.Alu)
    val payload = execute.i2.row.payload
    payload.opcode.poke(0x51.U)
    payload.recipe.valid.poke(true.B)
    payload.recipe.opcode.poke(0x51.U)
    payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    payload.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
    payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Iex.U)
    payload.uopKey.primaryParent.valid.poke(true.B)
    payload.uopKey.primaryParent.peId.poke(3.U)
    payload.uopKey.primaryParent.stid.poke(0.U)
    payload.uopKey.primaryParent.instructionId.poke((40 + ridSlot).U)
    payload.uopKey.uopCount.poke(1.U)

    def pokeDestination(destination: OooIexDestinationState): Unit = {
      destination.valid.poke(true.B)
      destination.kind.poke(DestinationKind.Gpr)
      destination.atag.poke(6.U)
      destination.ptag.poke(ptag.U)
      destination.ptagGeneration.poke(3.U)
    }
    pokeDestination(row.destinations(0))
    terminal.writebacks(0).valid.poke(true.B)
    pokeDestination(terminal.writebacks(0).destination)
    terminal.writebacks(0).data.poke((0x100 + ridSlot).U)
    dut.io.alu(source).valid.poke(true.B)
  }

  private def pokeDirectJump(
      dut: OooIexTerminalFabric,
      source: Int,
      ridSlot: Int,
      target: BigInt): Unit = {
    val terminal = dut.io.bru(source).bits
    terminal.poke(0.U.asTypeOf(terminal))
    val execute = terminal.execute
    execute.ownerClass.poke(OooUopClass.Bru)
    val row = execute.i2.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(0.U)
    row.epoch.poke(9.U)
    row.transactionId.poke(71.U)
    pokeMember(row.member, ridSlot)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(OooUopClass.Bru)
    val payload = execute.i2.row.payload
    payload.opcode.poke(FrontendOpcodeDecodeTable.OP_J.U)
    payload.recipe.valid.poke(true.B)
    payload.recipe.opcode.poke(FrontendOpcodeDecodeTable.OP_J.U)
    payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    payload.recipe.dispatchClass.poke(OooDispatchClass.Bru.U)
    payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Bctrl.U)
    payload.uopKey.primaryParent.valid.poke(true.B)
    payload.uopKey.primaryParent.peId.poke(3.U)
    payload.uopKey.primaryParent.stid.poke(0.U)
    payload.uopKey.primaryParent.instructionId.poke(71.U)
    payload.uopKey.primaryParent.epoch.poke(9.U)
    payload.uopKey.uopCount.poke(1.U)
    terminal.bctrl.valid.poke(true.B)
    terminal.bctrl.kind.poke(OooIexBctrlUpdateKind.Target)
    terminal.bctrl.targetValid.poke(true.B)
    terminal.bctrl.target.poke(target.U)
    dut.io.bru(source).valid.poke(true.B)
  }

  private def clearHarness(dut: OooIexAluTerminalHarness): Unit = {
    dut.io.e1.valid.poke(false.B)
    dut.io.e1.bits.poke(0.U.asTypeOf(dut.io.e1.bits))
    dut.io.pWrite.foreach(_.ready.poke(true.B))
    dut.io.tWrite.foreach(_.ready.poke(true.B))
    dut.io.uWrite.foreach(_.ready.poke(true.B))
    dut.io.wakeup.foreach(_.ready.poke(true.B))
    dut.io.bctrl.foreach(_.ready.poke(true.B))
    dut.io.trace.foreach(_.ready.poke(true.B))
    dut.io.robResolve.foreach(_.ready.poke(true.B))
  }

  private def pokeHarnessExecute(dut: OooIexAluTerminalHarness): Unit = {
    val execute = dut.io.e1.bits
    execute.poke(0.U.asTypeOf(execute))
    execute.ownerClass.poke(OooUopClass.Alu)
    execute.ownerLane.poke(0.U)
    execute.slotGeneration.poke(7.U)
    val i2 = execute.i2
    val row = i2.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(0.U)
    row.epoch.poke(9.U)
    row.transactionId.poke(43.U)
    pokeMember(row.member, ridSlot = 3)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(OooUopClass.Alu)
    row.reservation.bank.poke(0.U)
    row.reservation.writePort.poke(0.U)
    row.reservation.speculativeSlot.poke(1.U)
    row.reservation.reservationEpoch.poke(9.U)
    row.inFlight.poke(true.B)
    row.sources(0).valid.poke(true.B)
    row.sources(0).ready.poke(true.B)
    row.sources(0).operandClass.poke(OperandClass.P)
    row.sources(0).ptag.poke(17.U)
    row.sources(0).ptagGeneration.poke(3.U)
    i2.sourceMask.poke(1.U)
    i2.sourceData(0).poke(41.U)
    val payload = i2.row.payload
    payload.opcode.poke(FrontendOpcodeDecodeTable.OP_ADDI.U)
    payload.recipe.valid.poke(true.B)
    payload.recipe.opcode.poke(FrontendOpcodeDecodeTable.OP_ADDI.U)
    payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    payload.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
    payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Iex.U)
    payload.recipe.pSourceCount.poke(1.U)
    payload.recipe.pDestinationCount.poke(1.U)
    payload.immediateValid.poke(true.B)
    payload.immediate.poke(1.U)
    payload.uopKey.primaryParent.valid.poke(true.B)
    payload.uopKey.primaryParent.peId.poke(3.U)
    payload.uopKey.primaryParent.stid.poke(0.U)
    payload.uopKey.primaryParent.instructionId.poke(43.U)
    payload.uopKey.uopCount.poke(1.U)
    row.destinations(0).valid.poke(true.B)
    row.destinations(0).kind.poke(DestinationKind.Gpr)
    row.destinations(0).atag.poke(6.U)
    row.destinations(0).ptag.poke(27.U)
    row.destinations(0).ptagGeneration.poke(3.U)
    dut.io.e1.valid.poke(true.B)
  }

  test("publishes two independent terminal lanes in the same cycle") {
    simulate(new OooIexTerminalFabric(
      core, aluSourceCount = 3, bruSourceCount = 1, loadSourceCount = 1)) { dut =>
      clear(dut)
      pokeAlu(dut, source = 0, ridSlot = 1, ptag = 30)
      pokeAlu(dut, source = 1, ridSlot = 2, ptag = 31)

      dut.io.terminalFireMask.expect(3.U)
      dut.io.alu(0).ready.expect(true.B)
      dut.io.alu(1).ready.expect(true.B)
      dut.io.pWrite(0).valid.expect(true.B)
      dut.io.pWrite(0).bits.key.ptag.expect(30.U)
      dut.io.pWrite(2).valid.expect(true.B)
      dut.io.pWrite(2).bits.key.ptag.expect(31.U)
      dut.io.robResolve(0).bits.rob.ridSlot.expect(1.U)
      dut.io.robResolve(1).bits.rob.ridSlot.expect(2.U)
    }
  }

  test("keeps terminal clusters independently backpressured") {
    simulate(new OooIexTerminalFabric(
      core, aluSourceCount = 3, bruSourceCount = 1, loadSourceCount = 1)) { dut =>
      clear(dut)
      pokeAlu(dut, source = 0, ridSlot = 1, ptag = 30)
      pokeAlu(dut, source = 1, ridSlot = 2, ptag = 31)
      dut.io.robResolve(0).ready.poke(false.B)

      dut.io.terminalFireMask.expect(2.U)
      dut.io.alu(0).ready.expect(false.B)
      dut.io.alu(1).ready.expect(true.B)
      dut.io.pWrite(0).valid.expect(false.B)
      dut.io.pWrite(2).valid.expect(true.B)

      dut.io.robResolve(0).ready.poke(true.B)
      dut.io.terminalFireMask.expect(3.U)
    }
  }

  test("direct J waits for recovery and publishes redirect atomically once") {
    simulate(new OooIexTerminalFabric(
      core, aluSourceCount = 1, bruSourceCount = 1, loadSourceCount = 1)) { dut =>
      clear(dut)
      pokeDirectJump(dut, source = 0, ridSlot = 3, target = 0x3008)
      dut.io.recoveryEvent.head.ready.poke(false.B)

      dut.io.bru.head.ready.expect(false.B)
      dut.io.terminalFireMask.expect(0.U)
      dut.io.robResolve.head.valid.expect(false.B)
      dut.io.trace.head.valid.expect(false.B)
      dut.io.recoveryEvent.head.valid.expect(true.B)
      dut.io.bctrl.head.valid.expect(false.B)

      dut.io.recoveryEvent.head.ready.poke(true.B)
      dut.io.bru.head.ready.expect(true.B)
      dut.io.terminalFireMask.expect(1.U)
      dut.io.robResolve.head.valid.expect(true.B)
      dut.io.trace.head.valid.expect(true.B)
      dut.io.recoveryEvent.head.valid.expect(true.B)
      dut.io.recoveryEvent.head.bits.cause.expect(RecoveryCause.Branch)
      dut.io.recoveryEvent.head.bits.redirectPc.expect(0x3008.U)
      dut.clock.step()
      dut.io.bru.head.valid.poke(false.B)
      dut.io.recoveryEvent.head.valid.expect(false.B)
      dut.io.robResolve.head.valid.expect(false.B)
      dut.io.trace.head.valid.expect(false.B)
    }
  }

  test("backpressured direct J does not block a peer terminal lane") {
    simulate(new OooIexTerminalFabric(
      core, aluSourceCount = 2, bruSourceCount = 1, loadSourceCount = 1)) { dut =>
      clear(dut)
      pokeDirectJump(dut, source = 0, ridSlot = 3, target = 0x3008)
      pokeAlu(dut, source = 1, ridSlot = 4, ptag = 31)
      dut.io.recoveryEvent.head.ready.poke(false.B)

      dut.io.bru.head.ready.expect(false.B)
      dut.io.alu(1).ready.expect(true.B)
      dut.io.terminalFireMask.expect(2.U)
      dut.io.robResolve(0).valid.expect(false.B)
      dut.io.robResolve(1).valid.expect(true.B)
      dut.io.pWrite(2).valid.expect(true.B)
    }
  }

  test("round robins same-cluster ALU owners only on terminal fire") {
    simulate(new OooIexTerminalFabric(
      core, aluSourceCount = 3, bruSourceCount = 1, loadSourceCount = 1)) { dut =>
      clear(dut)
      pokeAlu(dut, source = 0, ridSlot = 1, ptag = 30)
      pokeAlu(dut, source = 2, ridSlot = 3, ptag = 29)
      dut.io.robResolve(0).ready.poke(false.B)
      dut.io.alu(0).ready.expect(false.B)
      dut.io.alu(2).ready.expect(false.B)
      dut.io.robResolve(0).bits.rob.ridSlot.expect(3.U)
      dut.clock.step(2)
      dut.io.robResolve(0).bits.rob.ridSlot.expect(3.U)
      dut.io.robResolve(0).ready.poke(true.B)
      dut.io.alu(0).ready.expect(false.B)
      dut.io.alu(2).ready.expect(true.B)
      dut.clock.step()

      dut.io.alu(2).valid.poke(false.B)
      dut.io.alu(0).ready.expect(true.B)
      dut.io.robResolve(0).bits.rob.ridSlot.expect(1.U)
    }
  }

  test("retains a real ALU W1 W2 transaction until one atomic terminal fire") {
    simulate(new OooIexAluTerminalHarness(core)) { dut =>
      clearHarness(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      pokeHarnessExecute(dut)
      dut.io.e1.ready.expect(true.B)
      dut.clock.step()
      dut.io.e1.valid.poke(false.B)
      dut.io.w1Occupied.expect(true.B)
      dut.io.w2Occupied.expect(false.B)
      dut.clock.step()
      dut.io.w1Occupied.expect(false.B)
      dut.io.w2Occupied.expect(true.B)
      dut.io.robResolve(0).ready.poke(false.B)

      for (_ <- 0 until 3) {
        dut.io.terminalFireMask.expect(0.U)
        dut.io.robResolve(0).valid.expect(true.B)
        dut.io.robResolve(0).ready.expect(false.B)
        dut.io.robResolve(0).bits.rob.ridSlot.expect(3.U)
        dut.io.robResolve.drop(1).foreach(_.valid.expect(false.B))
        dut.io.retainedW2.valid.expect(true.B)
        expectRetainedMember(dut.io.retainedW2.bits.execute.i2.row
          .schedule.member)
        expectRetainedDestination(dut.io.retainedW2.bits.execute.i2.row
          .schedule.destinations(0))
        dut.io.retainedW2.bits.writebacks(0).valid.expect(true.B)
        expectRetainedDestination(
          dut.io.retainedW2.bits.writebacks(0).destination)
        dut.io.retainedW2.bits.writebacks(0).data.expect(42.U)
        dut.io.pWrite.foreach(_.valid.expect(false.B))
        dut.io.tWrite.foreach(_.valid.expect(false.B))
        dut.io.uWrite.foreach(_.valid.expect(false.B))
        dut.io.wakeup.foreach(_.valid.expect(false.B))
        dut.io.trace.foreach(_.valid.expect(false.B))
        dut.io.bctrl.foreach(_.valid.expect(false.B))
        dut.io.w2Occupied.expect(true.B)
        dut.clock.step()
      }

      dut.io.robResolve(0).ready.poke(true.B)
      dut.io.terminalFireMask.expect(1.U)
      dut.io.robResolve(0).valid.expect(true.B)
      dut.io.robResolve(0).bits.rob.ridSlot.expect(3.U)
      dut.io.robResolve(0).bits.trap.valid.expect(false.B)
      dut.io.robResolve(0).bits.trap.cause.expect(0.U)
      dut.io.robResolve.drop(1).foreach(_.valid.expect(false.B))
      dut.io.pWrite(0).valid.expect(true.B)
      dut.io.pWrite(0).bits.commit.expect(true.B)
      dut.io.pWrite(0).bits.key.stid.expect(0.U)
      dut.io.pWrite(0).bits.key.epoch.expect(9.U)
      dut.io.pWrite(0).bits.key.ptag.expect(27.U)
      dut.io.pWrite(0).bits.key.generation.expect(3.U)
      dut.io.pWrite(0).bits.data.expect(42.U)
      dut.io.pWrite.drop(1).foreach(_.valid.expect(false.B))
      dut.io.wakeup(0).valid.expect(true.B)
      dut.io.wakeup(0).bits.kind.expect(OooIexWakeupKind.Committed)
      dut.io.wakeup(0).bits.stid.expect(0.U)
      dut.io.wakeup(0).bits.epoch.expect(9.U)
      dut.io.wakeup(0).bits.operandClass.expect(OperandClass.P)
      dut.io.wakeup(0).bits.ptag.expect(27.U)
      dut.io.wakeup(0).bits.ptagGeneration.expect(3.U)
      dut.io.wakeup(0).bits.localTag.expect(0.U)
      dut.io.wakeup(0).bits.localSequence.valid.expect(false.B)
      dut.io.wakeup(0).bits.localSequence.index.expect(0.U)
      dut.io.wakeup(0).bits.localSequence.generation.expect(0.U)
      expectEmptyLoad(dut.io.wakeup(0).bits.load)
      dut.io.wakeup.drop(1).foreach(_.valid.expect(false.B))
      dut.io.trace(0).valid.expect(true.B)
      val trace = dut.io.trace(0).bits
      trace.source.expect(OooIexTerminalSource.Alu)
      expectRetainedMember(trace.member)
      trace.uopKey.primaryParent.valid.expect(true.B)
      trace.uopKey.primaryParent.peId.expect(3.U)
      trace.uopKey.primaryParent.stid.expect(0.U)
      trace.uopKey.primaryParent.instructionId.expect(43.U)
      trace.uopKey.primaryParent.epoch.expect(0.U)
      trace.uopKey.uopOrdinal.expect(0.U)
      trace.uopKey.uopCount.expect(1.U)
      trace.opcode.expect(62.U)
      trace.writebacks(0).valid.expect(true.B)
      expectRetainedDestination(trace.writebacks(0).destination)
      trace.writebacks(0).data.expect(42.U)
      trace.writebacks.drop(1).foreach(_.valid.expect(false.B))
      expectEmptyLoad(trace.load)
      trace.trapValid.expect(false.B)
      trace.trapCause.expect(0.U)
      dut.io.trace.drop(1).foreach(_.valid.expect(false.B))
      dut.io.tWrite.foreach(_.valid.expect(false.B))
      dut.io.uWrite.foreach(_.valid.expect(false.B))
      dut.io.bctrl.foreach(_.valid.expect(false.B))
      dut.io.retainedW2.valid.expect(true.B)
      expectRetainedMember(dut.io.retainedW2.bits.execute.i2.row
        .schedule.member)
      expectRetainedDestination(
        dut.io.retainedW2.bits.writebacks(0).destination)
      dut.io.retainedW2.bits.writebacks(0).data.expect(42.U)
      dut.clock.step()

      for (_ <- 0 until 2) {
        dut.io.w2Occupied.expect(false.B)
        dut.io.retainedW2.valid.expect(false.B)
        dut.io.terminalFireMask.expect(0.U)
        dut.io.robResolve.foreach(_.valid.expect(false.B))
        dut.io.pWrite.foreach(_.valid.expect(false.B))
        dut.io.wakeup.foreach(_.valid.expect(false.B))
        dut.io.trace.foreach(_.valid.expect(false.B))
        dut.io.tWrite.foreach(_.valid.expect(false.B))
        dut.io.uWrite.foreach(_.valid.expect(false.B))
        dut.io.bctrl.foreach(_.valid.expect(false.B))
        dut.clock.step()
      }
    }
  }
}
