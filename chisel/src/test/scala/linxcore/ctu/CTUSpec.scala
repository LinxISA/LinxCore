package linxcore.ctu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import _root_.circt.stage.ChiselStage
import linxcore.common.{TemplateForm, TemplateRowKind}
import linxcore.params.{CoreParams, ParamProfiles, SimulationParamProfiles}
import linxcore.top.interface.{FrontEndOpKind, RecoveryCause, RecoveryPhase}
import org.scalatest.funsuite.AnyFunSuite

class CTUSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: CTU): Unit = {
    dut.io.fromIfu.valid.poke(false.B)
    dut.io.fromIfu.bits.poke(0.U.asTypeOf(dut.io.fromIfu.bits))
    dut.io.toOoo.ready.poke(false.B)
    dut.io.trace.ready.poke(true.B)
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

  private def macroInstruction(base: BigInt, m: Int, n: Int): BigInt =
    base | (BigInt(m) << 15) | (BigInt(n) << 20)

  private def enqueue(
      dut: CTU,
      instructions: Seq[(BigInt, Int)],
      firstId: Int = 1,
      stid: Int = 0,
      epoch: Int = 1): Unit = {
    dut.io.fromIfu.bits.poke(0.U.asTypeOf(dut.io.fromIfu.bits))
    dut.io.fromIfu.bits.count.poke(instructions.size.U)
    instructions.zipWithIndex.foreach { case ((raw, length), lane) =>
      val entry = dut.io.fromIfu.bits.entries(lane)
      entry.identity.peId.poke(1.U)
      entry.identity.stid.poke(stid.U)
      entry.identity.instructionId.poke((firstId + lane).U)
      entry.identity.epoch.poke(epoch.U)
      entry.pc.poke((0x1000 + lane * 8).U)
      entry.instruction.poke(raw.U)
      entry.lengthBytes.poke(length.U)
      entry.prediction.valid.poke(true.B)
      entry.prediction.transactionId.poke((100 + lane).U)
      entry.prediction.epoch.poke(epoch.U)
    }
    dut.io.fromIfu.valid.poke(true.B)
    var cycles = 0
    while (!dut.io.fromIfu.ready.peek().litToBoolean && cycles < 16) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < 16, "CTU input did not become ready")
    dut.io.fromIfu.ready.expect(true.B)
    dut.clock.step()
    dut.io.fromIfu.valid.poke(false.B)
  }

  private def waitForOutput(dut: CTU): Unit = {
    var cycles = 0
    while (!dut.io.toOoo.valid.peek().litToBoolean && cycles < 16) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < 16, "CTU output did not become valid")
  }

  private def drainIds(dut: CTU, count: Int): Seq[BigInt] = {
    val result = scala.collection.mutable.ArrayBuffer.empty[BigInt]
    dut.io.toOoo.ready.poke(true.B)
    while (result.size < count) {
      waitForOutput(dut)
      val lanes = dut.io.toOoo.bits.count.peek().litValue.toInt
      (0 until lanes).foreach { lane =>
        result += dut.io.toOoo.bits.entries(lane)
          .parent.identity.instructionId.peek().litValue
      }
      dut.clock.step()
    }
    result.toSeq
  }

  test("passes an ordinary width-wide packet without changing identity or prediction") {
    simulate(new CTU(ParamProfiles.W4)) { dut =>
      clear(dut)
      enqueue(dut, Seq(
        BigInt("0005", 16) -> 2,
        BigInt("0009", 16) -> 2,
        BigInt("0011", 16) -> 2,
        BigInt("0015", 16) -> 2))

      waitForOutput(dut)
      dut.io.toOoo.valid.expect(true.B)
      dut.io.toOoo.bits.count.expect(4.U)
      (0 until 4).foreach { lane =>
        dut.io.toOoo.bits.entries(lane).kind.expect(FrontEndOpKind.Encoded64)
        dut.io.toOoo.bits.entries(lane).parent.identity.instructionId
          .expect((lane + 1).U)
        dut.io.toOoo.bits.entries(lane).parent.prediction.transactionId
          .expect((100 + lane).U)
      }
    }
  }

  test("expands FENTRY into the generated row recipe and spans output packets") {
    simulate(new CTU(ParamProfiles.W4)) { dut =>
      clear(dut)
      val raw = macroInstruction(base = 0x41, m = 2, n = 7) |
        (BigInt(8) << 25) // frameImmediate = 8 * 8 = 64 bytes
      enqueue(dut, Seq(raw -> 4), firstId = 20)
      dut.io.toOoo.ready.poke(true.B)

      val kinds = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      val registers = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      val immediates = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      while (kinds.size < 9) {
        waitForOutput(dut)
        val lanes = dut.io.toOoo.bits.count.peek().litValue.toInt
        (0 until lanes).foreach { lane =>
          val op = dut.io.toOoo.bits.entries(lane)
          op.kind.expect(FrontEndOpKind.TemplateUop)
          op.parent.identity.instructionId.expect(20.U)
          op.templateOrdinal.expect(kinds.size.U)
          op.templateCount.expect(9.U)
          kinds += op.templateOpcode.peek().litValue
          registers += op.templateRegister.peek().litValue
          immediates += op.templateImmediate.peek().litValue
        }
        dut.clock.step()
      }

      assert(kinds.head == TemplateRowKind.VFORM.asUInt.litValue)
      assert(kinds(1) == TemplateRowKind.SP_SUB.asUInt.litValue)
      assert(kinds.slice(2, 8).forall(_ == TemplateRowKind.STORE.asUInt.litValue))
      assert(immediates(1) == 64)
      assert(registers.slice(2, 8) == Seq(2, 3, 4, 5, 6, 7))
      assert(immediates.slice(2, 8) == Seq(56, 48, 40, 32, 24, 16))
      assert(kinds.last == TemplateRowKind.FINAL.asUInt.litValue)
    }
  }

  test("expands FEXIT and never lets a younger packet cross the template parent") {
    simulate(new CTU(ParamProfiles.W4)) { dut =>
      clear(dut)
      val fexit = macroInstruction(base = 0x1041, m = 4, n = 6)
      enqueue(dut, Seq(BigInt("0005", 16) -> 2, fexit -> 4), firstId = 30)
      val first = drainIds(dut, 1)
      assert(first == Seq(30))

      val template = drainIds(dut, 6)
      assert(template.forall(_ == 31))

      enqueue(dut, Seq(BigInt("0009", 16) -> 2), firstId = 40)
      assert(drainIds(dut, 1) == Seq(40))
    }
  }

  test("uses the complete return-template row counts and rejects malformed ranges") {
    simulate(new CTU(ParamProfiles.W4)) { dut =>
      clear(dut)
      val fretRa = macroInstruction(base = 0x2041, m = 2, n = 3)
      val fretStk = macroInstruction(base = 0x3041, m = 2, n = 3)
      enqueue(dut, Seq(fretRa -> 4), firstId = 80)
      dut.io.toOoo.ready.poke(true.B)
      waitForOutput(dut)
      dut.io.toOoo.bits.entries(0).templateCount.expect(7.U)
      assert(drainIds(dut, 7).forall(_ == 80))

      enqueue(dut, Seq(fretStk -> 4), firstId = 81)
      waitForOutput(dut)
      dut.io.toOoo.bits.entries(0).templateCount.expect(8.U)
      assert(drainIds(dut, 8).forall(_ == 81))

      val malformed = macroInstruction(base = 0x41, m = 0, n = 3)
      enqueue(dut, Seq(malformed -> 4), firstId = 82)
      waitForOutput(dut)
      dut.io.toOoo.bits.count.expect(1.U)
      dut.io.toOoo.bits.entries(0).kind.expect(FrontEndOpKind.Encoded64)
      dut.io.toOoo.bits.entries(0).parent.identity.instructionId.expect(82.U)
    }
  }

  test("W2 W4 W6 W8 simulation profiles execute every public template form") {
    Seq(2, 4, 6, 8).foreach { width =>
      simulate(new CTU(SimulationParamProfiles.forWidth(width))) { dut =>
        clear(dut)
        val forms = Seq(
          macroInstruction(0x41, 2, 3),
          macroInstruction(0x1041, 2, 3),
          macroInstruction(0x2041, 2, 3),
          macroInstruction(0x3041, 2, 3))
        forms.zipWithIndex.foreach { case (raw, index) =>
          val instructionId = 100 + index
          enqueue(dut, Seq(raw -> 4), firstId = instructionId)
          waitForOutput(dut)
          dut.io.toOoo.bits.entries(0).kind.expect(FrontEndOpKind.TemplateUop)
          val rows = dut.io.toOoo.bits.entries(0).templateCount
            .peek().litValue.toInt
          assert(rows > 2, s"W$width template $index was truncated to $rows rows")
          assert(drainIds(dut, rows).forall(_ == instructionId))
        }
      }
    }
  }

  test("holds buffered output stable while OOO is backpressured") {
    simulate(new CTU(ParamProfiles.W4)) { dut =>
      clear(dut)
      enqueue(dut, Seq(BigInt("0005", 16) -> 2), firstId = 50)
      waitForOutput(dut)
      dut.io.toOoo.valid.expect(true.B)
      val heldCount = dut.io.toOoo.bits.count.peek().litValue
      val heldId = dut.io.toOoo.bits.entries(0)
        .parent.identity.instructionId.peek().litValue
      val heldInstruction = dut.io.toOoo.bits.entries(0)
        .parent.instruction.peek().litValue
      dut.clock.step(4)
      dut.io.toOoo.bits.count.expect(heldCount.U)
      dut.io.toOoo.bits.entries(0).parent.identity.instructionId
        .expect(heldId.U)
      dut.io.toOoo.bits.entries(0).parent.instruction
        .expect(heldInstruction.U)
      dut.io.fromIfu.ready.expect(true.B)
    }
  }

  test("prepare is non-mutating and apply prunes only the target STID") {
    simulate(new CTU(ParamProfiles.W4)) { dut =>
      clear(dut)
      enqueue(dut, Seq(BigInt("0005", 16) -> 2), firstId = 60, stid = 0)
      enqueue(dut, Seq(BigInt("0009", 16) -> 2), firstId = 70, stid = 1)

      dut.io.recovery.prepare.bits.poke(
        0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepare.bits.transactionId.poke(99.U)
      dut.io.recovery.prepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recovery.prepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recovery.prepare.bits.trigger.stid.poke(0.U)
      dut.io.recovery.prepare.bits.newEpoch.poke(2.U)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.io.recovery.prepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)

      dut.io.recovery.prepared.valid.expect(true.B)
      dut.io.recovery.prepared.bits.transactionId.expect(99.U)
      dut.io.toOoo.valid.expect(false.B)

      dut.io.recovery.apply.bits.poke(
        0.U.asTypeOf(dut.io.recovery.apply.bits))
      dut.io.recovery.apply.bits.transactionId.poke(99.U)
      dut.io.recovery.apply.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.recovery.apply.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recovery.apply.bits.trigger.stid.poke(0.U)
      dut.io.recovery.apply.bits.newEpoch.poke(2.U)
      dut.io.recovery.apply.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)

      waitForOutput(dut)
      dut.io.toOoo.valid.expect(true.B)
      dut.io.toOoo.bits.entries(0).parent.identity.instructionId.expect(70.U)
    }
  }

  test("a matching recovery abort releases the fence without changing work") {
    simulate(new CTU(ParamProfiles.W4)) { dut =>
      clear(dut)
      enqueue(dut, Seq(BigInt("0005", 16) -> 2), firstId = 75)
      waitForOutput(dut)

      dut.io.recovery.prepare.bits.poke(
        0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepare.bits.transactionId.poke(101.U)
      dut.io.recovery.prepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recovery.prepare.bits.cause.poke(RecoveryCause.Debug)
      dut.io.recovery.prepare.bits.trigger.stid.poke(0.U)
      dut.io.recovery.prepare.bits.newEpoch.poke(4.U)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.toOoo.valid.expect(false.B)

      dut.io.recovery.abort.bits.poke(
        0.U.asTypeOf(dut.io.recovery.abort.bits))
      dut.io.recovery.abort.bits.transactionId.poke(101.U)
      dut.io.recovery.abort.bits.phase.poke(RecoveryPhase.Abort)
      dut.io.recovery.abort.bits.cause.poke(RecoveryCause.Debug)
      dut.io.recovery.abort.bits.trigger.stid.poke(0.U)
      dut.io.recovery.abort.bits.newEpoch.poke(4.U)
      dut.io.recovery.abort.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.abort.valid.poke(false.B)

      waitForOutput(dut)
      dut.io.toOoo.bits.entries(0).parent.identity.instructionId.expect(75.U)
    }
  }

  test("retains exactly one template trace while its consumer is stalled") {
    simulate(new CTU(ParamProfiles.W4)) { dut =>
      clear(dut)
      dut.io.trace.ready.poke(false.B)
      val fentry = macroInstruction(base = 0x41, m = 2, n = 3)
      enqueue(dut, Seq(fentry -> 4), firstId = 90)
      waitForOutput(dut)

      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.count.expect(1.U)
      dut.io.trace.bits.entries(0).instruction.instructionId.expect(90.U)
      dut.io.trace.bits.entries(0).payload.expect(5.U)
      dut.clock.step(3)
      dut.io.trace.valid.expect(true.B)
      dut.io.trace.bits.entries(0).instruction.instructionId.expect(90.U)

      dut.io.trace.ready.poke(true.B)
      dut.clock.step()
      dut.io.trace.valid.expect(false.B)
    }
  }

  test("packetizes an ordinary full-width prefix at W2 W4 W6 and W8") {
    Seq(
      2 -> ParamProfiles.W2,
      4 -> ParamProfiles.W4,
      6 -> ParamProfiles.W6,
      8 -> ParamProfiles.W8).foreach { case (width, p) =>
      simulate(new CTU(p)) { dut =>
        clear(dut)
        val instructions =
          Seq.tabulate(width)(lane => (BigInt(5 + lane * 4), 2))
        enqueue(dut, instructions, firstId = 100 + width)
        waitForOutput(dut)
        dut.io.toOoo.bits.count.expect(width.U)
        (0 until width).foreach { lane =>
          dut.io.toOoo.bits.entries(lane).parent.identity.instructionId
            .expect((100 + width + lane).U)
        }
      }
    }
  }

  test("the CTU hierarchy does not instantiate backend state owners") {
    val sv = ChiselStage.emitSystemVerilog(new CTU(ParamProfiles.W4))
    assert(sv.contains("module CTU"))
    assert(sv.contains("module TemplateDecode"))
    assert(sv.contains("module TemplateExpand"))
    assert(sv.contains("module InstructionBuffer"))
    Seq("ROB", "BROB", "Rename", "IssueQueue", "LSU").foreach { owner =>
      assert(!sv.contains(s"module $owner"))
    }
  }

  test("elaborates W2 W4 W6 and W8 with the configured packet widths") {
    Seq(
      2 -> ParamProfiles.W2,
      4 -> ParamProfiles.W4,
      6 -> ParamProfiles.W6,
      8 -> ParamProfiles.W8).foreach { case (width, p: CoreParams) =>
      val chirrtl = ChiselStage.emitCHIRRTL(new CTU(p))
      assert(chirrtl.contains("module CTU"))
      assert(p.widths.fetchWidth == width)
      assert(p.widths.ctuOutputWidth == width)
    }
  }
}
