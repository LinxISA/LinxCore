package linxcore.iex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.DestinationKind
import linxcore.ooo._
import linxcore.params.{CoreParams, SimulationParamProfiles}
import org.scalatest.funsuite.AnyFunSuite

class IEXTerminalTraceIndependenceSpec extends AnyFunSuite with ChiselSim {
  private val core: CoreParams = SimulationParamProfiles.W4

  private def initialize(dut: OooIexTerminalPublish): Unit = {
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
    dut.io.trace.ready.poke(false.B)
    dut.io.robResolve.ready.poke(true.B)
    dut.io.recoveryEvent.ready.poke(true.B)
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

  private def pokeDestination(target: OooIexDestinationState): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.valid.poke(true.B)
    target.kind.poke(DestinationKind.Gpr)
    target.atag.poke(4.U)
    target.ptag.poke(30.U)
    target.ptagGeneration.poke(9.U)
  }

  private def pokeAlu(dut: OooIexTerminalPublish): Unit = {
    val terminal = dut.io.alu.bits
    terminal.poke(0.U.asTypeOf(terminal))
    val execute = terminal.execute
    execute.ownerClass.poke(OooUopClass.Alu)
    val row = execute.i2.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(0.U)
    row.epoch.poke(11.U)
    row.transactionId.poke(43.U)
    row.member.group.valid.poke(true.B)
    row.member.group.peId.poke(3.U)
    row.member.group.stid.poke(0.U)
    row.member.group.ridSlot.poke(2.U)
    row.member.group.ridGeneration.poke(5.U)
    row.member.bid.valid.poke(true.B)
    row.member.bid.value.poke(2.U)
    row.member.brobGeneration.poke(7.U)
    row.member.memberIndex.poke(0.U)
    row.member.residentGeneration.poke(8.U)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(OooUopClass.Alu)
    pokeDestination(row.destinations(0))

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
    payload.uopKey.primaryParent.instructionId.poke(91.U)
    payload.uopKey.uopCount.poke(1.U)

    terminal.writebacks(0).valid.poke(true.B)
    pokeDestination(terminal.writebacks(0).destination)
    terminal.writebacks(0).data.poke(0x1234.U)
    dut.io.alu.valid.poke(true.B)
  }

  test("trace backpressure cannot stall or duplicate architectural publication") {
    simulate(new OooIexTerminalPublish(core)) { dut =>
      initialize(dut)
      pokeAlu(dut)

      dut.io.alu.ready.expect(true.B)
      dut.io.pWrite(0).valid.expect(true.B)
      dut.io.pWrite(0).bits.data.expect(0x1234.U)
      dut.io.wakeup(0).valid.expect(true.B)
      dut.io.robResolve.valid.expect(true.B)
      dut.io.robResolve.bits.transactionId.expect(43.U)
      dut.io.terminalFire.expect(true.B)
      dut.io.architecturalAccepted.valid.expect(true.B)
      dut.io.architecturalAccepted.bits.transactionId.expect(43.U)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.ready.expect(false.B)
      dut.clock.step()

      dut.io.alu.valid.poke(false.B)
      for (_ <- 0 until 3) {
        dut.io.pWrite(0).valid.expect(false.B)
        dut.io.wakeup(0).valid.expect(false.B)
        dut.io.robResolve.valid.expect(false.B)
        dut.io.terminalFire.expect(false.B)
        dut.io.architecturalAccepted.valid.expect(false.B)
        dut.io.trace.valid.expect(false.B)
        dut.clock.step()
      }
    }
  }
}
