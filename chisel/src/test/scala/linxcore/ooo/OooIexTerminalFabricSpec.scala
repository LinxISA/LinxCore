package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, Valid}
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite

private class OooIexAluTerminalHarnessIO(val p: OooParams) extends Bundle {
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
  val completion = Vec(p.iexTerminalWidth,
    Decoupled(new OooRobMemberCompletion(p)))
  val terminalFireMask = Output(UInt(p.iexTerminalWidth.W))
  val retainedW2 = Valid(new OooIexAluTerminalTransaction(p))
  val w1Occupied = Output(Bool())
  val w2Occupied = Output(Bool())
}

private class OooIexAluTerminalHarness(val p: OooParams) extends Module {
  val io = IO(new OooIexAluTerminalHarnessIO(p))

  val alu = Module(new OooIexAluPipeline(p))
  val terminal = Module(new OooIexTerminalFabric(
    p, aluSourceCount = 1, bruSourceCount = 1, loadSourceCount = 1))

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
  io.completion.zip(terminal.io.completion).foreach {
    case (o, i) => forward(o, i)
  }
  io.terminalFireMask := terminal.io.terminalFireMask
  io.retainedW2.valid := alu.io.w2.valid
  io.retainedW2.bits := alu.io.w2.bits
  io.w1Occupied := alu.io.w1Occupied
  io.w2Occupied := alu.io.w2Occupied
}

class OooIexTerminalFabricSpec extends AnyFunSuite with ChiselSim {
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
    iexIssueDomainCount = 6,
    iexReleaseWidth = 6,
    tuRetireSourceDepthPerStid = 16)

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
    dut.io.completion.foreach(_.ready.poke(true.B))
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

  private def expectRetainedMember(target: RobMemberKey): Unit = {
    target.group.valid.expect(true.B)
    target.group.peId.expect(3.U)
    target.group.stid.expect(1.U)
    target.group.ridSlot.expect(3.U)
    target.group.ridGeneration.expect(1.U)
    target.bid.valid.expect(true.B)
    target.bid.value.expect(5.U)
    target.brobGeneration.expect(2.U)
    target.memberIndex.expect(0.U)
    target.residentGeneration.expect(4.U)
  }

  private def expectRetainedDestination(
      destination: OooIexDestinationState): Unit = {
    destination.valid.expect(true.B)
    destination.kind.expect(DestinationKind.Gpr)
    destination.atag.expect(6.U)
    destination.ptag.expect(37.U)
    destination.ptagGeneration.expect(3.U)
    destination.localTag.expect(0.U)
    destination.localSequence.valid.expect(false.B)
    destination.localSequence.index.expect(0.U)
    destination.localSequence.generation.expect(0.U)
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
    row.stid.poke(1.U)
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
    payload.uopKey.primaryParent.stid.poke(1.U)
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

  private def clearHarness(dut: OooIexAluTerminalHarness): Unit = {
    dut.io.e1.valid.poke(false.B)
    dut.io.e1.bits.poke(0.U.asTypeOf(dut.io.e1.bits))
    dut.io.pWrite.foreach(_.ready.poke(true.B))
    dut.io.tWrite.foreach(_.ready.poke(true.B))
    dut.io.uWrite.foreach(_.ready.poke(true.B))
    dut.io.wakeup.foreach(_.ready.poke(true.B))
    dut.io.bctrl.foreach(_.ready.poke(true.B))
    dut.io.trace.foreach(_.ready.poke(true.B))
    dut.io.completion.foreach(_.ready.poke(true.B))
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
    row.stid.poke(1.U)
    row.epoch.poke(9.U)
    row.transactionId.poke(43.U)
    pokeMember(row.member, ridSlot = 3)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(OooUopClass.Alu)
    row.reservation.bank.poke(0.U)
    row.reservation.writePort.poke(0.U)
    row.reservation.speculativeSlot.poke(3.U)
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
    payload.uopKey.primaryParent.stid.poke(1.U)
    payload.uopKey.primaryParent.instructionId.poke(43.U)
    payload.uopKey.uopCount.poke(1.U)
    row.destinations(0).valid.poke(true.B)
    row.destinations(0).kind.poke(DestinationKind.Gpr)
    row.destinations(0).atag.poke(6.U)
    row.destinations(0).ptag.poke(37.U)
    row.destinations(0).ptagGeneration.poke(3.U)
    dut.io.e1.valid.poke(true.B)
  }

  test("publishes two independent terminal lanes in the same cycle") {
    simulate(new OooIexTerminalFabric(p)) { dut =>
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
      dut.io.completion(0).bits.key.group.ridSlot.expect(1.U)
      dut.io.completion(1).bits.key.group.ridSlot.expect(2.U)
    }
  }

  test("keeps terminal clusters independently backpressured") {
    simulate(new OooIexTerminalFabric(p)) { dut =>
      clear(dut)
      pokeAlu(dut, source = 0, ridSlot = 1, ptag = 30)
      pokeAlu(dut, source = 1, ridSlot = 2, ptag = 31)
      dut.io.completion(0).ready.poke(false.B)

      dut.io.terminalFireMask.expect(2.U)
      dut.io.alu(0).ready.expect(false.B)
      dut.io.alu(1).ready.expect(true.B)
      dut.io.pWrite(0).valid.expect(false.B)
      dut.io.pWrite(2).valid.expect(true.B)

      dut.io.completion(0).ready.poke(true.B)
      dut.io.terminalFireMask.expect(3.U)
    }
  }

  test("round robins same-cluster ALU owners only on terminal fire") {
    simulate(new OooIexTerminalFabric(p)) { dut =>
      clear(dut)
      pokeAlu(dut, source = 0, ridSlot = 1, ptag = 30)
      pokeAlu(dut, source = 2, ridSlot = 3, ptag = 32)
      dut.io.completion(0).ready.poke(false.B)
      dut.io.alu(0).ready.expect(false.B)
      dut.io.alu(2).ready.expect(false.B)
      dut.clock.step(2)
      dut.io.completion(0).ready.poke(true.B)
      dut.io.alu(0).ready.expect(true.B)
      dut.io.alu(2).ready.expect(false.B)
      dut.clock.step()

      dut.io.alu(0).valid.poke(false.B)
      dut.io.alu(2).ready.expect(true.B)
      dut.io.completion(0).bits.key.group.ridSlot.expect(3.U)
    }
  }

  test("retains a real ALU W1 W2 transaction until one atomic terminal fire") {
    simulate(new OooIexAluTerminalHarness(p)) { dut =>
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
      dut.io.completion(0).ready.poke(false.B)

      for (_ <- 0 until 3) {
        dut.io.terminalFireMask.expect(0.U)
        dut.io.completion(0).valid.expect(true.B)
        dut.io.completion(0).ready.expect(false.B)
        expectRetainedMember(dut.io.completion(0).bits.key)
        dut.io.completion.drop(1).foreach(_.valid.expect(false.B))
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

      dut.io.completion(0).ready.poke(true.B)
      dut.io.terminalFireMask.expect(1.U)
      dut.io.completion(0).valid.expect(true.B)
      expectRetainedMember(dut.io.completion(0).bits.key)
      dut.io.completion.drop(1).foreach(_.valid.expect(false.B))
      dut.io.pWrite(0).valid.expect(true.B)
      dut.io.pWrite(0).bits.key.ptag.expect(37.U)
      dut.io.pWrite(0).bits.key.generation.expect(3.U)
      dut.io.pWrite(0).bits.data.expect(42.U)
      dut.io.pWrite.drop(1).foreach(_.valid.expect(false.B))
      dut.io.wakeup(0).valid.expect(true.B)
      dut.io.wakeup(0).bits.ptag.expect(37.U)
      dut.io.wakeup(0).bits.ptagGeneration.expect(3.U)
      dut.io.wakeup.drop(1).foreach(_.valid.expect(false.B))
      dut.io.trace(0).valid.expect(true.B)
      dut.io.trace(0).bits.uopKey.primaryParent.instructionId.expect(43.U)
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
        dut.io.completion.foreach(_.valid.expect(false.B))
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
