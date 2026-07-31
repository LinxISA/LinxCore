package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.Decoupled
import linxcore.ctu.CTU
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface.{FetchedPacket, RecoveryTargetIO}
import org.scalatest.funsuite.AnyFunSuite

private class CTUOOOHarnessIO(val p: CoreParams) extends Bundle {
  val in = Flipped(Decoupled(new FetchedPacket(p)))
  val d2 = Decoupled(new D2AdmissionGroup(p))
}

private class CTUOOOHarness(val p: CoreParams) extends Module {
  val io = IO(new CTUOOOHarnessIO(p))
  val ctu = Module(new CTU(p))
  val ooo = Module(new OOO(p))
  ctu.io.fromIfu <> io.in
  ooo.io.fromCtu <> ctu.io.toOoo
  io.d2 <> ooo.io.d2
  ctu.io.trace.ready := true.B
  ooo.io.ridTailSlot.foreach(_ := 0.U)
  ooo.io.ridTailGeneration.foreach(_ := 0.U)

  private def idleRecovery(target: RecoveryTargetIO): Unit = {
    target.prepare.valid := false.B
    target.prepare.bits := 0.U.asTypeOf(target.prepare.bits)
    target.prepared.ready := true.B
    target.apply.valid := false.B
    target.apply.bits := 0.U.asTypeOf(target.apply.bits)
    target.abort.valid := false.B
    target.abort.bits := 0.U.asTypeOf(target.abort.bits)
  }
  idleRecovery(ctu.io.recovery)
  idleRecovery(ooo.io.recovery)
}

class CTUOOOIntegrationSpec extends AnyFunSuite with ChiselSim {
  private def rule(symbol: String): OooOpcodeRecipeTable.Rule =
    OooOpcodeRecipeTable.Rules.find(_.symbol == symbol).get

  private def clear(dut: CTUOOOHarness): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.d2.ready.poke(false.B)
  }

  private def send(dut: CTUOOOHarness, raw: BigInt, len: Int, id: Int): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.bits.count.poke(1.U)
    val entry = dut.io.in.bits.entries(0)
    entry.identity.peId.poke(1.U)
    entry.identity.stid.poke(0.U)
    entry.identity.instructionId.poke(id.U)
    entry.identity.epoch.poke(4.U)
    entry.pc.poke(0x1000.U)
    entry.instruction.poke(raw.U)
    entry.lengthBytes.poke(len.U)
    entry.prediction.valid.poke(true.B)
    entry.prediction.transactionId.poke((0x500 + id).U)
    entry.prediction.epoch.poke(4.U)
    dut.io.in.valid.poke(true.B)
    var cycles = 0
    while (!dut.io.in.ready.peek().litToBoolean && cycles < 32) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < 32)
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  private def waitD2(dut: CTUOOOHarness): Unit = {
    var cycles = 0
    while (!dut.io.d2.valid.peek().litToBoolean && cycles < 64) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < 64)
  }

  test("ordinary encoded traffic survives CTU and OOO backpressure exactly once") {
    simulate(new CTUOOOHarness(ParamProfiles.W4)) { dut =>
      clear(dut)
      send(dut, rule("OP_ADD").value, rule("OP_ADD").lenBytes, 41)
      waitD2(dut)
      dut.io.d2.bits.count.expect(1.U)
      dut.io.d2.bits.entries(0).uop.opcode.expect(rule("OP_ADD").opcode.U)
      dut.io.d2.bits.entries(0).uop.instruction.parent.identity.instructionId
        .expect(41.U)
      val heldId = dut.io.d2.bits.entries(0).uop.instruction.parent.identity
        .instructionId.peek().litValue
      val heldOpcode = dut.io.d2.bits.entries(0).uop.opcode.peek().litValue
      dut.clock.step(4)
      dut.io.d2.bits.entries(0).uop.instruction.parent.identity.instructionId
        .expect(heldId.U)
      dut.io.d2.bits.entries(0).uop.opcode.expect(heldOpcode.U)
      dut.io.d2.ready.poke(true.B)
      dut.clock.step()
      dut.io.d2.valid.expect(false.B)
    }
  }

  test("CTU template expansion reaches OOO as ordered non-recursive uops") {
    simulate(new CTUOOOHarness(ParamProfiles.W4)) { dut =>
      clear(dut)
      val fentry = rule("OP_FENTRY").value |
        (BigInt(2) << 15) | (BigInt(3) << 20)
      send(dut, fentry, rule("OP_FENTRY").lenBytes, 77)
      dut.io.d2.ready.poke(true.B)

      var seen = 0
      while (seen < 5) {
        waitD2(dut)
        val count = dut.io.d2.bits.count.peek().litValue.toInt
        (0 until count).foreach { lane =>
          val row = dut.io.d2.bits.entries(lane).uop
          row.instruction.parent.identity.instructionId.expect(77.U)
          row.instruction.templateOrdinal.expect((seen + lane).U)
          row.instruction.templateCount.expect(5.U)
        }
        seen += count
        dut.clock.step()
      }
      assert(seen == 5)
    }
  }
}
