package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, Valid}
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

class OooIexTerminalOperandHarnessIO(val p: OooParams) extends Bundle {
  val alu = Flipped(Decoupled(new OooIexAluTerminalTransaction(p)))
  val pInit = Flipped(Valid(new OooIexPFileInit(p)))
  val tClear = Flipped(Valid(new OooIexLocalFileKey(p)))
  val uClear = Flipped(Valid(new OooIexLocalFileKey(p)))
  val pRead = Flipped(Valid(new OooIexOperandReadPortRequest(p)))
  val tRead = Flipped(Valid(new OooIexOperandReadPortRequest(p)))
  val uRead = Flipped(Valid(new OooIexOperandReadPortRequest(p)))
  val pResponse = Valid(UInt(p.pcWidth.W))
  val tResponse = Valid(UInt(p.pcWidth.W))
  val uResponse = Valid(UInt(p.pcWidth.W))
  val wakeup = Vec(p.maxDestinationOperands,
    Decoupled(new OooIexWakeup(p)))
  val trace = Decoupled(new OooIexTerminalTrace(p))
  val completion = Decoupled(new OooRobMemberCompletion(p))
  val terminalFire = Output(Bool())
}

/** Composition shell proving the publication pulse mutates the canonical RF. */
class OooIexTerminalOperandHarness(val p: OooParams) extends Module {
  val io = IO(new OooIexTerminalOperandHarnessIO(p))
  val publish = Module(new OooIexTerminalPublish(p))
  val files = Module(new OooIexOperandFiles(p))

  publish.io.alu <> io.alu
  publish.io.bru.valid := false.B
  publish.io.bru.bits := 0.U.asTypeOf(publish.io.bru.bits)
  publish.io.load.valid := false.B
  publish.io.load.bits := 0.U.asTypeOf(publish.io.load.bits)

  files.io.pInit := io.pInit
  for (index <- 0 until 2) {
    files.io.pClear(index).valid := false.B
    files.io.pClear(index).bits := 0.U.asTypeOf(files.io.pClear(index).bits)
  }
  for (index <- 0 until p.tuAllocationWidth) {
    files.io.tClear(index).valid := index.U === 0.U && io.tClear.valid
    files.io.tClear(index).bits := io.tClear.bits
    files.io.uClear(index).valid := index.U === 0.U && io.uClear.valid
    files.io.uClear(index).bits := io.uClear.bits
  }

  for (index <- 0 until p.iexPReadPorts) {
    files.io.pReadRequests(index).valid := index.U === 0.U && io.pRead.valid
    files.io.pReadRequests(index).bits := io.pRead.bits
  }
  for (index <- 0 until p.iexTReadPorts) {
    files.io.tReadRequests(index).valid := index.U === 0.U && io.tRead.valid
    files.io.tReadRequests(index).bits := io.tRead.bits
  }
  for (index <- 0 until p.iexUReadPorts) {
    files.io.uReadRequests(index).valid := index.U === 0.U && io.uRead.valid
    files.io.uReadRequests(index).bits := io.uRead.bits
  }
  io.pResponse := files.io.pReadResponses(0)
  io.tResponse := files.io.tReadResponses(0)
  io.uResponse := files.io.uReadResponses(0)

  for (index <- 0 until p.iexPWritePorts) {
    if (index < p.maxDestinationOperands) {
      files.io.pWrite(index).valid := publish.io.pWrite(index).valid
      files.io.pWrite(index).bits := publish.io.pWrite(index).bits
      publish.io.pWrite(index).ready := files.io.pWriteReady(index)
    } else {
      files.io.pWrite(index).valid := false.B
      files.io.pWrite(index).bits :=
        0.U.asTypeOf(files.io.pWrite(index).bits)
    }
  }
  for (index <- 0 until p.iexTWritePorts) {
    if (index < p.maxDestinationOperands) {
      files.io.tWrite(index).valid := publish.io.tWrite(index).valid
      files.io.tWrite(index).bits := publish.io.tWrite(index).bits
      publish.io.tWrite(index).ready := files.io.tWriteReady(index)
    } else {
      files.io.tWrite(index).valid := false.B
      files.io.tWrite(index).bits :=
        0.U.asTypeOf(files.io.tWrite(index).bits)
    }
  }
  for (index <- 0 until p.iexUWritePorts) {
    if (index < p.maxDestinationOperands) {
      files.io.uWrite(index).valid := publish.io.uWrite(index).valid
      files.io.uWrite(index).bits := publish.io.uWrite(index).bits
      publish.io.uWrite(index).ready := files.io.uWriteReady(index)
    } else {
      files.io.uWrite(index).valid := false.B
      files.io.uWrite(index).bits :=
        0.U.asTypeOf(files.io.uWrite(index).bits)
    }
  }

  publish.io.wakeup <> io.wakeup
  publish.io.trace <> io.trace
  publish.io.completion <> io.completion
  publish.io.bctrl.ready := true.B
  io.terminalFire := publish.io.terminalFire
}

class OooIexTerminalPublishSpec extends AnyFunSuite with ChiselSim {
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

  private def pokeMember(target: RobMemberKey, ridSlot: Int = 2): Unit = {
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

  private def pokeDestination(
      destination: OooIexDestinationState,
      kind: DestinationKind.Type,
      ordinal: Int): Unit = {
    destination.poke(0.U.asTypeOf(destination))
    destination.valid.poke(true.B)
    destination.kind.poke(kind)
    destination.atag.poke((4 + ordinal).U)
    destination.ptag.poke((30 + ordinal).U)
    destination.ptagGeneration.poke(3.U)
    destination.localTag.poke((6 + ordinal).U)
    destination.localSequence.valid.poke(true.B)
    destination.localSequence.index.poke((10 + ordinal).U)
    destination.localSequence.generation.poke(2.U)
  }

  private def pokeExecute(
      execute: OooIexExecuteTransaction,
      destinations: Seq[DestinationKind.Type],
      ridSlot: Int = 2): Unit = {
    execute.poke(0.U.asTypeOf(execute))
    val row = execute.i2.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(1.U)
    row.epoch.poke(7.U)
    pokeMember(row.member, ridSlot)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(OooUopClass.Alu)
    execute.ownerClass.poke(OooUopClass.Alu)
    execute.i2.row.payload.opcode.poke(0x51.U)
    execute.i2.row.payload.recipe.valid.poke(true.B)
    execute.i2.row.payload.recipe.opcode.poke(0x51.U)
    execute.i2.row.payload.recipe.disposition.poke(
      OooOpcodeDisposition.Dispatch.U)
    execute.i2.row.payload.recipe.dispatchClass.poke(
      OooDispatchClass.Alu.U)
    execute.i2.row.payload.recipe.sideEffectOwner.poke(
      OooSideEffectOwner.Iex.U)
    execute.i2.row.payload.uopKey.primaryParent.valid.poke(true.B)
    execute.i2.row.payload.uopKey.primaryParent.peId.poke(3.U)
    execute.i2.row.payload.uopKey.primaryParent.stid.poke(1.U)
    execute.i2.row.payload.uopKey.primaryParent.instructionId.poke(9.U)
    execute.i2.row.payload.uopKey.uopCount.poke(1.U)
    destinations.zipWithIndex.foreach { case (kind, index) =>
      pokeDestination(row.destinations(index), kind, index)
    }
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
    dut.io.completion.ready.poke(true.B)
  }

  private def pokeAlu(
      dut: OooIexTerminalPublish,
      destinations: Seq[DestinationKind.Type]): Unit = {
    val terminal = dut.io.alu.bits
    terminal.poke(0.U.asTypeOf(terminal))
    pokeExecute(terminal.execute, destinations)
    destinations.zipWithIndex.foreach { case (kind, index) =>
      terminal.writebacks(index).valid.poke(true.B)
      pokeDestination(terminal.writebacks(index).destination, kind, index)
      terminal.writebacks(index).data.poke((0x100 + index).U)
    }
    dut.io.alu.valid.poke(true.B)
  }

  test("holds every peer until P write and committed wakeup can fire together") {
    simulate(new OooIexTerminalPublish(p)) { dut =>
      clear(dut)
      pokeAlu(dut, Seq(DestinationKind.Gpr))
      dut.io.pWrite(0).ready.poke(false.B)
      dut.io.alu.ready.expect(false.B)
      dut.io.pWrite(0).valid.expect(true.B)
      dut.io.wakeup(0).valid.expect(false.B)
      dut.io.completion.valid.expect(false.B)
      dut.io.trace.valid.expect(false.B)

      dut.io.pWrite(0).ready.poke(true.B)
      dut.io.wakeup(0).ready.poke(false.B)
      dut.io.pWrite(0).valid.expect(false.B)
      dut.io.wakeup(0).valid.expect(true.B)
      dut.io.alu.ready.expect(false.B)

      dut.io.wakeup(0).ready.poke(true.B)
      dut.io.alu.ready.expect(true.B)
      dut.io.terminalFire.expect(true.B)
      dut.io.pWrite(0).valid.expect(true.B)
      dut.io.wakeup(0).valid.expect(true.B)
      dut.io.wakeup(0).bits.kind.expect(OooIexWakeupKind.Committed)
      dut.io.wakeup(0).bits.operandClass.expect(OperandClass.P)
      dut.io.completion.valid.expect(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.writebacks(0).data.expect(0x100.U)
    }
  }

  test("publishes T and U destinations as one multi-write transaction") {
    simulate(new OooIexTerminalPublish(p)) { dut =>
      clear(dut)
      pokeAlu(dut, Seq(DestinationKind.T, DestinationKind.U))
      dut.io.uWrite(1).ready.poke(false.B)
      dut.io.alu.ready.expect(false.B)
      dut.io.tWrite(0).valid.expect(false.B)
      dut.io.uWrite(1).valid.expect(true.B)
      dut.io.wakeup(1).valid.expect(false.B)
      dut.io.terminalFire.expect(false.B)

      dut.io.uWrite(1).ready.poke(true.B)
      dut.io.alu.ready.expect(true.B)
      dut.io.tWrite(0).valid.expect(true.B)
      dut.io.uWrite(1).valid.expect(true.B)
      dut.io.wakeup(0).bits.operandClass.expect(OperandClass.T)
      dut.io.wakeup(1).bits.operandClass.expect(OperandClass.U)
      dut.io.terminalFire.expect(true.B)
    }
  }

  test("keeps BRU BCTRL and load fault publication under the same terminal fire") {
    simulate(new OooIexTerminalPublish(p)) { dut =>
      clear(dut)
      pokeExecute(dut.io.bru.bits.execute, Seq.empty)
      dut.io.bru.bits.execute.ownerClass.poke(OooUopClass.Bru)
      dut.io.bru.bits.execute.i2.row.schedule.reservation.uopClass.poke(
        OooUopClass.Bru)
      dut.io.bru.bits.execute.i2.row.payload.recipe.dispatchClass.poke(
        OooDispatchClass.Bru.U)
      dut.io.bru.bits.execute.i2.row.payload.recipe.sideEffectOwner.poke(
        OooSideEffectOwner.Bctrl.U)
      dut.io.bru.bits.bctrl.valid.poke(true.B)
      dut.io.bru.bits.bctrl.kind.poke(OooIexBctrlUpdateKind.Condition)
      dut.io.bru.bits.bctrl.condition.poke(true.B)
      dut.io.bru.valid.poke(true.B)
      dut.io.bctrl.ready.poke(false.B)
      dut.io.bctrl.valid.expect(true.B)
      dut.io.completion.valid.expect(false.B)
      dut.io.bru.ready.expect(false.B)
      dut.io.bctrl.ready.poke(true.B)
      dut.io.bru.ready.expect(true.B)
      dut.io.bctrl.valid.expect(true.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.trace.valid.expect(true.B)
      dut.clock.step()

      dut.io.bru.valid.poke(false.B)
      val load = dut.io.load.bits
      load.poke(0.U.asTypeOf(load))
      pokeExecute(load.agu.execute, Seq(DestinationKind.Gpr), ridSlot = 3)
      load.agu.execute.ownerClass.poke(OooUopClass.Agu)
      load.agu.execute.i2.row.schedule.reservation.uopClass.poke(
        OooUopClass.Agu)
      load.agu.execute.i2.row.payload.recipe.dispatchClass.poke(
        OooDispatchClass.Agu.U)
      load.agu.execute.i2.row.payload.recipe.sideEffectOwner.poke(
        OooSideEffectOwner.Lsu.U)
      pokeDestination(load.agu.destination, DestinationKind.Gpr, 0)
      load.faultValid.poke(true.B)
      load.faultCause.poke(13.U)
      load.load.valid.poke(true.B)
      pokeMember(load.load.producer, ridSlot = 3)
      load.load.generation.poke(6.U)
      dut.io.load.valid.poke(true.B)
      dut.io.load.ready.expect(true.B)
      dut.io.pWrite.foreach(_.valid.expect(false.B))
      dut.io.wakeup.foreach(_.valid.expect(false.B))
      dut.io.trace.bits.trapValid.expect(true.B)
      dut.io.trace.bits.trapCause.expect(13.U)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.faultValid.expect(true.B)
      dut.io.completion.bits.faultCause.expect(13.U)
    }
  }

  test("rejects duplicated destinations without consuming the owner") {
    simulate(new OooIexTerminalPublish(p)) { dut =>
      clear(dut)
      pokeAlu(dut, Seq(DestinationKind.Gpr, DestinationKind.Gpr))
      pokeDestination(
        dut.io.alu.bits.execute.i2.row.schedule.destinations(1),
        DestinationKind.Gpr, 0)
      pokeDestination(dut.io.alu.bits.writebacks(1).destination,
        DestinationKind.Gpr, 0)
      dut.io.alu.ready.expect(false.B)
      dut.io.rejected(0).valid.expect(true.B)
      dut.io.rejected(0).bits.duplicateDestination.expect(true.B)
      dut.io.terminalFire.expect(false.B)
    }
  }

  test("writes P T and U data into the canonical operand files") {
    simulate(new OooIexTerminalOperandHarness(p)) { dut =>
      dut.io.alu.valid.poke(false.B)
      dut.io.alu.bits.poke(0.U.asTypeOf(dut.io.alu.bits))
      dut.io.pInit.valid.poke(false.B)
      dut.io.pInit.bits.poke(0.U.asTypeOf(dut.io.pInit.bits))
      dut.io.tClear.valid.poke(false.B)
      dut.io.tClear.bits.poke(0.U.asTypeOf(dut.io.tClear.bits))
      dut.io.uClear.valid.poke(false.B)
      dut.io.uClear.bits.poke(0.U.asTypeOf(dut.io.uClear.bits))
      dut.io.pRead.valid.poke(false.B)
      dut.io.pRead.bits.poke(0.U.asTypeOf(dut.io.pRead.bits))
      dut.io.tRead.valid.poke(false.B)
      dut.io.tRead.bits.poke(0.U.asTypeOf(dut.io.tRead.bits))
      dut.io.uRead.valid.poke(false.B)
      dut.io.uRead.bits.poke(0.U.asTypeOf(dut.io.uRead.bits))
      dut.io.wakeup.foreach(_.ready.poke(true.B))
      dut.io.trace.ready.poke(true.B)
      dut.io.completion.ready.poke(true.B)
      dut.clock.step()

      dut.io.pInit.bits.key.stid.poke(1.U)
      dut.io.pInit.bits.key.epoch.poke(7.U)
      dut.io.pInit.bits.key.ptag.poke(30.U)
      dut.io.pInit.bits.key.generation.poke(3.U)
      dut.io.pInit.bits.data.poke(0.U)
      dut.io.pInit.valid.poke(true.B)
      dut.io.tClear.bits.stid.poke(1.U)
      dut.io.tClear.bits.epoch.poke(7.U)
      dut.io.tClear.bits.tag.poke(7.U)
      dut.io.tClear.bits.sequence.valid.poke(true.B)
      dut.io.tClear.bits.sequence.index.poke(11.U)
      dut.io.tClear.bits.sequence.generation.poke(2.U)
      dut.io.tClear.valid.poke(true.B)
      dut.io.uClear.bits.stid.poke(1.U)
      dut.io.uClear.bits.epoch.poke(7.U)
      dut.io.uClear.bits.tag.poke(6.U)
      dut.io.uClear.bits.sequence.valid.poke(true.B)
      dut.io.uClear.bits.sequence.index.poke(10.U)
      dut.io.uClear.bits.sequence.generation.poke(2.U)
      dut.io.uClear.valid.poke(true.B)
      dut.clock.step()
      dut.io.pInit.valid.poke(false.B)
      dut.io.tClear.valid.poke(false.B)
      dut.io.uClear.valid.poke(false.B)

      val terminal = dut.io.alu.bits
      terminal.poke(0.U.asTypeOf(terminal))
      pokeExecute(terminal.execute,
        Seq(DestinationKind.Gpr, DestinationKind.T))
      terminal.writebacks(0).valid.poke(true.B)
      pokeDestination(terminal.writebacks(0).destination,
        DestinationKind.Gpr, 0)
      terminal.writebacks(0).data.poke(0x1111.U)
      terminal.writebacks(1).valid.poke(true.B)
      pokeDestination(terminal.writebacks(1).destination,
        DestinationKind.T, 1)
      terminal.writebacks(1).data.poke(0x2222.U)
      dut.io.alu.valid.poke(true.B)
      dut.io.alu.ready.expect(true.B)
      dut.io.terminalFire.expect(true.B)
      dut.clock.step()
      dut.io.alu.valid.poke(false.B)

      dut.io.pRead.bits.stid.poke(1.U)
      dut.io.pRead.bits.epoch.poke(7.U)
      dut.io.pRead.bits.source.operandClass.poke(OperandClass.P)
      dut.io.pRead.bits.source.ptag.poke(30.U)
      dut.io.pRead.bits.source.ptagGeneration.poke(3.U)
      dut.io.pRead.valid.poke(true.B)
      dut.io.pResponse.valid.expect(true.B)
      dut.io.pResponse.bits.expect(0x1111.U)

      dut.io.tRead.bits.stid.poke(1.U)
      dut.io.tRead.bits.epoch.poke(7.U)
      dut.io.tRead.bits.source.operandClass.poke(OperandClass.T)
      dut.io.tRead.bits.source.localTag.poke(7.U)
      dut.io.tRead.bits.source.localSequence.valid.poke(true.B)
      dut.io.tRead.bits.source.localSequence.index.poke(11.U)
      dut.io.tRead.bits.source.localSequence.generation.poke(2.U)
      dut.io.tRead.valid.poke(true.B)
      dut.io.tResponse.valid.expect(true.B)
      dut.io.tResponse.bits.expect(0x2222.U)

      dut.io.pRead.valid.poke(false.B)
      dut.io.tRead.valid.poke(false.B)
      terminal.poke(0.U.asTypeOf(terminal))
      pokeExecute(terminal.execute, Seq(DestinationKind.U), ridSlot = 3)
      terminal.writebacks(0).valid.poke(true.B)
      pokeDestination(terminal.writebacks(0).destination,
        DestinationKind.U, 0)
      terminal.writebacks(0).data.poke(0x3333.U)
      dut.io.alu.valid.poke(true.B)
      dut.io.alu.ready.expect(true.B)
      dut.io.terminalFire.expect(true.B)
      dut.clock.step()
      dut.io.alu.valid.poke(false.B)

      dut.io.uRead.bits.stid.poke(1.U)
      dut.io.uRead.bits.epoch.poke(7.U)
      dut.io.uRead.bits.source.operandClass.poke(OperandClass.U)
      dut.io.uRead.bits.source.localTag.poke(6.U)
      dut.io.uRead.bits.source.localSequence.valid.poke(true.B)
      dut.io.uRead.bits.source.localSequence.index.poke(10.U)
      dut.io.uRead.bits.source.localSequence.generation.poke(2.U)
      dut.io.uRead.valid.poke(true.B)
      dut.io.uResponse.valid.expect(true.B)
      dut.io.uResponse.bits.expect(0x3333.U)
    }
  }
}
