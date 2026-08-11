package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.TemplateRowKind
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface.{FrontEndOpKind, OperandKind, RecoveryCause,
  RecoveryPhase, UopClass}
import org.scalatest.funsuite.AnyFunSuite

class OOODecodeSpec extends AnyFunSuite with ChiselSim {
  private def rule(symbol: String): OooOpcodeRecipeTable.Rule =
    OooOpcodeRecipeTable.Rules.find(_.symbol == symbol).getOrElse(
      fail(s"missing generated recipe for $symbol"))

  private def clearDec(dut: DEC): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.out.ready.poke(true.B)
  }

  private def clearOoo(dut: OOOD1D2Stage): Unit = {
    dut.io.fromCtu.valid.poke(false.B)
    dut.io.fromCtu.bits.poke(0.U.asTypeOf(dut.io.fromCtu.bits))
    dut.io.d2.ready.poke(true.B)
    dut.io.ridTailSlot.foreach(_.poke(0.U))
    dut.io.ridTailGeneration.foreach(_.poke(0.U))
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

  private def pokeEncoded(
      packet: linxcore.top.interface.D1Packet,
      lane: Int,
      symbol: String,
      instructionId: Int,
      stid: Int = 0,
      epoch: Int = 1,
      rawOverride: Option[BigInt] = None): Unit = {
    val entry = packet.entries(lane)
    val selected = rule(symbol)
    entry.kind.poke(FrontEndOpKind.Encoded64)
    entry.parent.identity.peId.poke(3.U)
    entry.parent.identity.stid.poke(stid.U)
    entry.parent.identity.instructionId.poke(instructionId.U)
    entry.parent.identity.epoch.poke(epoch.U)
    entry.parent.pc.poke((0x1000 + lane * 8).U)
    entry.parent.instruction.poke(rawOverride.getOrElse(selected.value).U)
    entry.parent.lengthBytes.poke(selected.lenBytes.U)
    entry.parent.prediction.valid.poke(true.B)
    entry.parent.prediction.predictionTag.poke((0x100 + lane).U)
    entry.parent.prediction.transactionId.poke((0x200 + lane).U)
    entry.parent.prediction.checkpointId.poke((lane + 1).U)
    entry.parent.prediction.requestPc.poke((0x1000 + lane * 8).U)
    entry.parent.prediction.taken.poke((lane == 1).B)
    entry.parent.prediction.target.poke((0x4000 + lane * 8).U)
    entry.parent.prediction.fallthroughPc.poke(
      (0x1000 + lane * 8 + selected.lenBytes).U)
    entry.parent.prediction.provider.poke(2.U)
    entry.parent.prediction.confidence.poke(3.U)
    entry.parent.prediction.epoch.poke(epoch.U)
  }

  test("normalizes 16 32 48 and 64-bit containers without losing decode metadata") {
    simulate(new DEC(ParamProfiles.W4)) { dut =>
      clearDec(dut)
      dut.io.in.bits.count.poke(4.U)
      Seq("OP_C_SETRET", "OP_ADD", "OP_LD", "OP_V_ADD")
        .zipWithIndex.foreach { case (symbol, lane) =>
          pokeEncoded(dut.io.in.bits, lane, symbol, 10 + lane)
        }
      dut.io.in.valid.poke(true.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.count.expect(4.U)
      Seq("OP_C_SETRET", "OP_ADD", "OP_LD", "OP_V_ADD")
        .zipWithIndex.foreach { case (symbol, lane) =>
          val decoded = dut.io.out.bits.entries(lane)
          decoded.uop.opcode.expect(rule(symbol).opcode.U)
          decoded.uop.instruction.parent.lengthBytes
            .expect(rule(symbol).lenBytes.U)
          decoded.uop.instruction.parent.identity.instructionId
            .expect((10 + lane).U)
          decoded.uop.instruction.parent.prediction.predictionTag
            .expect((0x100 + lane).U)
          decoded.trap.valid.expect(false.B)
        }
      dut.io.out.bits.entries(1).uop.uopClass.expect(UopClass.Alu)
      val encodedLoad = dut.io.out.bits.entries(2).uop.memory
      encodedLoad.valid.expect(true.B)
      encodedLoad.isLoad.expect(true.B)
      encodedLoad.requestCount.expect(rule("OP_LD").memoryRequestCount.U)
    }
  }

  test("classifies a standalone pure stop marker as early-complete boundary metadata") {
    simulate(new DEC(ParamProfiles.W4)) { dut =>
      clearDec(dut)
      dut.io.in.bits.count.poke(1.U)
      pokeEncoded(dut.io.in.bits, 0, "OP_BSTOP", 90, epoch = 2)
      dut.io.in.valid.poke(true.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.count.expect(1.U)
      val decoded = dut.io.out.bits.entries.head
      decoded.trap.valid.expect(false.B)
      decoded.uop.uopClass.expect(UopClass.Boundary)
      decoded.uop.blockStart.expect(false.B)
      decoded.uop.blockStop.expect(true.B)
      decoded.uop.blockBoundary.expect(true.B)
      decoded.uop.earlyComplete.expect(true.B)
    }
  }

  test("bypasses encoded decode for one CTU template child") {
    simulate(new DEC(ParamProfiles.W4)) { dut =>
      clearDec(dut)
      val op = dut.io.in.bits.entries(0)
      dut.io.in.bits.count.poke(1.U)
      op.kind.poke(FrontEndOpKind.TemplateUop)
      op.parent.identity.peId.poke(3.U)
      op.parent.identity.stid.poke(0.U)
      op.parent.identity.instructionId.poke(91.U)
      op.parent.identity.epoch.poke(7.U)
      op.parent.pc.poke(0x8000.U)
      op.parent.instruction.poke(rule("OP_FENTRY").value.U)
      op.parent.lengthBytes.poke(rule("OP_FENTRY").lenBytes.U)
      op.parent.prediction.valid.poke(true.B)
      op.parent.prediction.transactionId.poke(0x1234.U)
      op.templateOrdinal.poke(2.U)
      op.templateCount.poke(9.U)
      op.templateOpcode.poke(TemplateRowKind.STORE.asUInt)
      op.templateRegister.poke(23.U)
      op.templateImmediate.poke(248.U)
      dut.io.in.valid.poke(true.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.count.expect(1.U)
      val decoded = dut.io.out.bits.entries(0)
      decoded.uop.instruction.kind.expect(FrontEndOpKind.TemplateUop)
      decoded.uop.instruction.templateOrdinal.expect(2.U)
      decoded.uop.instruction.templateCount.expect(9.U)
      decoded.uop.opcode.expect(rule("OP_SDI").opcode.U)
      decoded.uop.uopClass.expect(UopClass.Std)
      decoded.uop.sources(0).valid.expect(true.B)
      decoded.uop.sources(0).kind.expect(OperandKind.Gpr)
      decoded.uop.sources(0).atag.expect(23.U)
      decoded.uop.sources(1).valid.expect(true.B)
      decoded.uop.sources(1).kind.expect(OperandKind.Gpr)
      decoded.uop.sources(1).atag.expect(1.U)
      decoded.uop.classification.pSourceCount.expect(2.U)
      decoded.uop.classification.pDestinationCount.expect(0.U)
      decoded.uop.memory.valid.expect(true.B)
      decoded.uop.memory.isStore.expect(true.B)
      decoded.uop.memory.requestCount.expect(1.U)
      decoded.uop.immediateValid.expect(true.B)
      decoded.uop.immediate.expect(248.U)
      decoded.uop.memory.offset.expect(248.U)
      decoded.uop.instruction.parent.identity.instructionId.expect(91.U)
      decoded.uop.instruction.parent.prediction.transactionId.expect(0x1234.U)
      decoded.trap.valid.expect(false.B)
    }
  }

  test("turns a fetch-faulted template child into the same typed trap intent") {
    simulate(new DEC(ParamProfiles.W2)) { dut =>
      clearDec(dut)
      val op = dut.io.in.bits.entries(0)
      dut.io.in.bits.count.poke(1.U)
      op.kind.poke(FrontEndOpKind.TemplateUop)
      op.parent.identity.peId.poke(3.U)
      op.parent.identity.stid.poke(0.U)
      op.parent.identity.instructionId.poke(92.U)
      op.parent.identity.epoch.poke(0x8004.U)
      op.parent.fetchFault.poke(true.B)
      op.parent.fetchFaultCause.poke(0x12345678.U)
      op.templateOrdinal.poke(0.U)
      op.templateCount.poke(1.U)
      op.templateOpcode.poke(TemplateRowKind.VFORM.asUInt)
      dut.io.in.valid.poke(true.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.entries(0).trap.valid.expect(true.B)
      dut.io.out.bits.entries(0).trap.cause.expect(0x12345678.U)
      dut.io.out.bits.entries(0).uop.earlyComplete.expect(true.B)
      dut.io.out.bits.entries(0).uop.instruction.parent.identity.epoch
        .expect(0x8004.U)
    }
  }

  test("VFORM_rob_only completes without consuming an issue queue") {
    simulate(new DEC(ParamProfiles.W2)) { dut =>
      clearDec(dut)
      val op = dut.io.in.bits.entries(0)
      dut.io.in.bits.count.poke(1.U)
      op.kind.poke(FrontEndOpKind.TemplateUop)
      op.parent.identity.peId.poke(3.U)
      op.parent.identity.stid.poke(0.U)
      op.parent.identity.instructionId.poke(93.U)
      op.parent.identity.epoch.poke(4.U)
      op.templateOrdinal.poke(0.U)
      op.templateCount.poke(5.U)
      op.templateOpcode.poke(TemplateRowKind.VFORM.asUInt)
      dut.io.in.valid.poke(true.B)

      val decoded = dut.io.out.bits.entries(0).uop
      decoded.uopClass.expect(UopClass.System)
      decoded.blockStart.expect(true.B)
      decoded.earlyComplete.expect(true.B)
      decoded.classification.disposition.expect(
        OooOpcodeDisposition.FastResolve.U)
      decoded.classification.dispatchWrites.expect(0.U)
      decoded.classification.dispatchDemand.foreach(_.expect(0.U))
    }
  }

  test("SP_SUB carries an exact scalar SP source and destination") {
    simulate(new DEC(ParamProfiles.W2)) { dut =>
      clearDec(dut)
      val op = dut.io.in.bits.entries(0)
      dut.io.in.bits.count.poke(1.U)
      op.kind.poke(FrontEndOpKind.TemplateUop)
      op.parent.identity.peId.poke(3.U)
      op.parent.identity.stid.poke(0.U)
      op.parent.identity.instructionId.poke(94.U)
      op.parent.identity.epoch.poke(4.U)
      op.templateOrdinal.poke(1.U)
      op.templateCount.poke(5.U)
      op.templateOpcode.poke(TemplateRowKind.SP_SUB.asUInt)
      op.templateImmediate.poke(64.U)
      dut.io.in.valid.poke(true.B)

      val decoded = dut.io.out.bits.entries(0).uop
      decoded.uopClass.expect(UopClass.Alu)
      decoded.opcode.expect(rule("OP_SUBI").opcode.U)
      decoded.sources(0).valid.expect(true.B)
      decoded.sources(0).kind.expect(OperandKind.Gpr)
      decoded.sources(0).atag.expect(1.U)
      decoded.destinations(0).valid.expect(true.B)
      decoded.destinations(0).kind.expect(OperandKind.Gpr)
      decoded.destinations(0).atag.expect(1.U)
      decoded.classification.pSourceCount.expect(1.U)
      decoded.classification.pDestinationCount.expect(1.U)
      decoded.classification.dispatchWrites.expect(1.U)
    }
  }

  test("merges mixed encoded and template lanes without fusing across the tag boundary") {
    simulate(new DEC(ParamProfiles.W4)) { dut =>
      clearDec(dut)
      dut.io.in.bits.count.poke(3.U)
      pokeEncoded(dut.io.in.bits, 0, "OP_BSTART_FALL", 20)
      val template = dut.io.in.bits.entries(1)
      template.kind.poke(FrontEndOpKind.TemplateUop)
      template.parent.identity.peId.poke(3.U)
      template.parent.identity.stid.poke(0.U)
      template.parent.identity.instructionId.poke(21.U)
      template.parent.identity.epoch.poke(1.U)
      template.templateOrdinal.poke(0.U)
      template.templateCount.poke(1.U)
      template.templateOpcode.poke(TemplateRowKind.VFORM.asUInt)
      pokeEncoded(dut.io.in.bits, 2, "OP_ADD", 22)
      dut.io.in.valid.poke(true.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.count.expect(3.U)
      Seq(20, 21, 22).zipWithIndex.foreach { case (id, lane) =>
        dut.io.out.bits.entries(lane).uop.instruction.parent.identity
          .instructionId.expect(id.U)
      }
      dut.io.out.bits.entries(0).uop.blockBoundary.expect(true.B)
      dut.io.out.bits.entries(1).uop.instruction.kind
        .expect(FrontEndOpKind.TemplateUop)
      dut.io.out.bits.entries(2).uop.opcode.expect(rule("OP_ADD").opcode.U)
    }
  }

  test("preserves operands and produces typed precise traps") {
    simulate(new DEC(ParamProfiles.W2)) { dut =>
      clearDec(dut)
      dut.io.in.bits.count.poke(2.U)
      val add = rule("OP_ADD").value |
        (BigInt(5) << 7) | (BigInt(6) << 15) | (BigInt(7) << 20)
      pokeEncoded(dut.io.in.bits, 0, "OP_ADD", 30, rawOverride = Some(add))
      pokeEncoded(dut.io.in.bits, 1, "OP_ADD", 31,
        rawOverride = Some(BigInt("ffffffffffffffff", 16)))
      dut.io.in.valid.poke(true.B)

      val first = dut.io.out.bits.entries(0)
      first.uop.destinations(0).valid.expect(true.B)
      first.uop.destinations(0).kind.expect(OperandKind.Gpr)
      first.uop.destinations(0).atag.expect(5.U)
      first.uop.sources(0).valid.expect(true.B)
      first.uop.sources(0).atag.expect(6.U)
      first.uop.sources(1).atag.expect(7.U)
      dut.io.out.bits.entries(1).trap.valid.expect(true.B)
      dut.io.out.bits.entries(1).trap.cause
        .expect(OooD1TrapCause.IllegalEncoding.U)
    }
  }

  test("retains an atomic D2 prefix with full-width virtual RID intent") {
    val p = ParamProfiles.W8.copy(
      ooo = ParamProfiles.W8.ooo.copy(robGroupsPerStid = 8))
    simulate(new OOOD1D2Stage(p)) { dut =>
      clearOoo(dut)
      dut.io.ridTailSlot(0).poke(7.U)
      dut.io.ridTailGeneration(0).poke(0x8001.U)
      dut.io.fromCtu.bits.count.poke(8.U)
      (0 until 8).foreach { lane =>
        pokeEncoded(dut.io.fromCtu.bits, lane, "OP_ADD", 100 + lane)
      }
      dut.io.d2.ready.poke(false.B)
      dut.io.fromCtu.valid.poke(true.B)
      dut.io.fromCtu.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromCtu.valid.poke(false.B)

      dut.io.d2.valid.expect(true.B)
      dut.io.d2.bits.count.expect(8.U)
      dut.io.d2.bits.groupCount.expect(2.U)
      (0 until 8).foreach { lane =>
        val row = dut.io.d2.bits.entries(lane)
        row.uop.rob.ridSlot.expect((if (lane < 4) 7 else 0).U)
        row.uop.rob.ridGeneration
          .expect((if (lane < 4) 0x8001 else 0x8002).U)
        row.uop.rob.memberIndex.expect((lane % 4).U)
        row.brobBound.expect(false.B)
      }
      val heldId = dut.io.d2.bits.entries(7).uop.instruction.parent.identity
        .instructionId.peek().litValue
      val heldGeneration = dut.io.d2.bits.entries(7).uop.rob.ridGeneration
        .peek().litValue
      dut.clock.step(3)
      dut.io.d2.bits.entries(7).uop.instruction.parent.identity.instructionId
        .expect(heldId.U)
      dut.io.d2.bits.entries(7).uop.rob.ridGeneration
        .expect(heldGeneration.U)
      dut.io.fromCtu.ready.expect(false.B)
    }
  }

  test("elaborates W2 W4 W6 and W8 without fixed lane assumptions") {
    Seq(2, 4, 6, 8).foreach { width =>
      simulate(new OOOD1D2Stage(ParamProfiles.forWidth(width))) { dut =>
        clearOoo(dut)
        dut.io.fromCtu.bits.count.poke(width.U)
        (0 until width).foreach { lane =>
          pokeEncoded(dut.io.fromCtu.bits, lane, "OP_ADD", 200 + lane)
        }
        dut.io.fromCtu.valid.poke(true.B)
        dut.io.fromCtu.ready.expect(true.B)
        dut.clock.step()
        dut.io.d2.valid.expect(true.B)
        dut.io.d2.bits.count.expect(width.U)
      }
    }
  }

  test("prepare fences one STID and matching apply cancels only its retained row") {
    val p = ParamProfiles.W2.copy(
      ooo = ParamProfiles.W2.ooo.copy(stidCount = 2))
    simulate(new OOOD1D2Stage(p)) { dut =>
      clearOoo(dut)
      dut.io.d2.ready.poke(false.B)

      def accept(stid: Int, id: Int): Unit = {
        dut.io.fromCtu.bits.poke(0.U.asTypeOf(dut.io.fromCtu.bits))
        dut.io.fromCtu.bits.count.poke(1.U)
        pokeEncoded(dut.io.fromCtu.bits, 0, "OP_ADD", id, stid = stid)
        dut.io.fromCtu.valid.poke(true.B)
        dut.io.fromCtu.ready.expect(true.B)
        dut.clock.step()
        dut.io.fromCtu.valid.poke(false.B)
      }
      accept(0, 300)
      accept(1, 301)

      dut.io.recovery.prepare.bits.poke(
        0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepare.bits.transactionId.poke(55.U)
      dut.io.recovery.prepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recovery.prepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recovery.prepare.bits.trigger.stid.poke(0.U)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.prepared.valid.expect(true.B)

      dut.io.recovery.apply.bits.poke(
        0.U.asTypeOf(dut.io.recovery.apply.bits))
      dut.io.recovery.apply.bits.transactionId.poke(55.U)
      dut.io.recovery.apply.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recovery.apply.bits.trigger.stid.poke(0.U)
      dut.io.recovery.apply.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.recovery.apply.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)

      dut.io.d2.valid.expect(true.B)
      dut.io.d2.bits.entries(0).uop.instruction.parent.identity.stid.expect(1.U)
      dut.io.d2.bits.entries(0).uop.instruction.parent.identity.instructionId
        .expect(301.U)
    }
  }
}
