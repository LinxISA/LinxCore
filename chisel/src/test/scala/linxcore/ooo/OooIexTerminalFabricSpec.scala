package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.DestinationKind
import org.scalatest.funsuite.AnyFunSuite

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

  test("retains one full W-stage identity across multi-cycle terminal backpressure") {
    simulate(new OooIexTerminalFabric(
      p, aluSourceCount = 2, bruSourceCount = 1, loadSourceCount = 2)) { dut =>
      clear(dut)
      pokeAlu(dut, source = 0, ridSlot = 3, ptag = 37)
      dut.io.completion(0).ready.poke(false.B)

      for (_ <- 0 until 3) {
        dut.io.terminalFireMask.expect(0.U)
        dut.io.alu(0).ready.expect(false.B)
        dut.io.completion(0).valid.expect(true.B)
        dut.io.completion(0).ready.expect(false.B)
        dut.io.completion(0).bits.key.group.peId.expect(3.U)
        dut.io.completion(0).bits.key.group.stid.expect(1.U)
        dut.io.completion(0).bits.key.group.ridSlot.expect(3.U)
        dut.io.completion(0).bits.key.group.ridGeneration.expect(1.U)
        dut.io.completion(0).bits.key.bid.value.expect(5.U)
        dut.io.completion(0).bits.key.brobGeneration.expect(2.U)
        dut.io.completion(0).bits.key.residentGeneration.expect(4.U)
        dut.io.pWrite(0).valid.expect(false.B)
        dut.io.trace(0).valid.expect(false.B)
        dut.io.bctrl(0).valid.expect(false.B)
        dut.clock.step()
      }

      dut.io.completion(0).ready.poke(true.B)
      dut.io.terminalFireMask.expect(1.U)
      dut.io.alu(0).ready.expect(true.B)
      dut.io.pWrite(0).valid.expect(true.B)
      dut.io.pWrite(0).bits.key.ptag.expect(37.U)
      dut.io.pWrite(0).bits.key.generation.expect(3.U)
      dut.io.pWrite(0).bits.data.expect(0x103.U)
      dut.io.trace(0).valid.expect(true.B)
      dut.io.trace(0).bits.uopKey.primaryParent.instructionId.expect(43.U)
      dut.clock.step()

      dut.io.alu(0).valid.poke(false.B)
      dut.io.terminalFireMask.expect(0.U)
      dut.io.completion(0).valid.expect(false.B)
      dut.io.pWrite(0).valid.expect(false.B)
      dut.clock.step(2)
      dut.io.terminalFireMask.expect(0.U)
    }
  }
}
