package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

class OooD1DecodeSpec extends AnyFunSuite with ChiselSim {
  private def rule(symbol: String): OooOpcodeRecipeTable.Rule =
    OooOpcodeRecipeTable.Rules.find(_.symbol == symbol).getOrElse(
      fail(s"missing generated recipe for $symbol"))

  private def setField(raw: BigInt, high: Int, low: Int, value: Int): BigInt = {
    val width = high - low + 1
    val mask = ((BigInt(1) << width) - 1) << low
    (raw & ~mask) | ((BigInt(value) << low) & mask)
  }

  private def encoded(symbol: String, fields: (Int, Int, Int)*): BigInt =
    fields.foldLeft(rule(symbol).value) { case (raw, (high, low, value)) =>
      setField(raw, high, low, value)
    }

  private def clear(dut: OooD1Decode): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.bits.peId.poke(2.U)
    dut.io.in.bits.stid.poke(1.U)
    dut.io.in.bits.epoch.poke(7.U)
    dut.io.out.ready.poke(true.B)
  }

  private def drive(
      dut: OooD1Decode,
      lane: Int,
      symbol: String,
      raw: BigInt,
      instructionId: Int,
      pc: BigInt,
      stid: Int = 1,
      epoch: Int = 7): Unit = {
    val entry = dut.io.in.bits.entries(lane)
    entry.parent.key.valid.poke(true.B)
    entry.parent.key.peId.poke(2.U)
    entry.parent.key.stid.poke(stid.U)
    entry.parent.key.instructionId.poke(instructionId.U)
    entry.parent.key.epoch.poke(epoch.U)
    entry.parent.pc.poke(pc.U)
    entry.parent.rawInstruction.poke(raw.U)
    entry.parent.lengthBytes.poke(rule(symbol).lenBytes.U)
    entry.parent.traceOwner.poke(true.B)
    entry.parent.preciseExceptionOwner.poke(true.B)
    entry.parent.prediction.valid.poke(true.B)
    entry.parent.prediction.predictionTag.poke((0x1000 + instructionId).U)
    entry.parent.prediction.transactionId.poke((0x2000 + instructionId).U)
    entry.parent.prediction.fetchPacketUid.poke((0x3000 + instructionId).U)
    entry.parent.prediction.fetchSeq.poke((0x4000 + instructionId).U)
    entry.parent.prediction.requestPc.poke(pc.U)
    entry.parent.prediction.branchPc.poke(pc.U)
    entry.parent.prediction.fallthroughPc.poke((pc + rule(symbol).lenBytes).U)
    entry.parent.prediction.checkpointId.poke(3.U)
    entry.parent.prediction.epoch.poke(epoch.U)
  }

  test("decodes mixed 16 32 48 and 64-bit parents into canonical ordered uops") {
    val p = OooParams()
    simulate(new OooD1Decode(p)) { dut =>
      clear(dut)
      drive(dut, 0, "OP_C_SETRET", rule("OP_C_SETRET").value, 10, 0x1000)
      drive(dut, 1, "OP_ADD", rule("OP_ADD").value, 11, 0x1002)
      val pairLoad = encoded(
        "OP_HL_LDIP", (27, 23, 3), (15, 11, 4), (35, 31, 5))
      drive(dut, 2, "OP_HL_LDIP", pairLoad, 12, 0x1006)
      drive(dut, 3, "OP_V_ADD", rule("OP_V_ADD").value, 13, 0x100c)
      dut.io.in.bits.validMask.poke("b1111".U)
      dut.io.in.valid.poke(true.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.uopMask.expect("b1111".U)
      Seq("OP_C_SETRET", "OP_ADD", "OP_HL_LDIP", "OP_V_ADD").zipWithIndex.foreach {
        case (symbol, lane) =>
          dut.io.out.bits.uops(lane).opcode.expect(rule(symbol).opcode.U)
          dut.io.out.bits.uops(lane).identity.parentCount.expect(1.U)
      }
      dut.io.out.bits.uops(2).destinations(0).atag.expect(3.U)
      dut.io.out.bits.uops(2).destinations(1).atag.expect(4.U)
      dut.io.out.bits.uops(2).sources(0).atag.expect(5.U)
      dut.io.out.bits.uops(2).destinations(0).kind.expect(DestinationKind.Gpr)
      dut.io.out.bits.demand.pDestinations.expect(5.U)
      dut.io.out.bits.demand.loadIds.expect(2.U)
    }
  }

  test("normalizes immediate and register-indexed pair stores without losing source four") {
    val p = OooParams()
    simulate(new OooD1Decode(p)) { dut =>
      clear(dut)
      val immediate = encoded(
        "OP_HL_SDIP", (35, 31, 2), (10, 6, 3), (40, 36, 4))
      val indexed = encoded(
        "OP_HL_SDP", (47, 43, 5), (10, 6, 6), (35, 31, 7), (40, 36, 8))
      drive(dut, 0, "OP_HL_SDIP", immediate, 20, 0x2000)
      drive(dut, 1, "OP_HL_SDP", indexed, 21, 0x2006)
      dut.io.in.bits.validMask.poke("b0011".U)
      dut.io.in.valid.poke(true.B)

      dut.io.out.bits.uopMask.expect("b00000011".U)
      Seq(2, 3, 4).zipWithIndex.foreach { case (atag, index) =>
        dut.io.out.bits.uops(0).sources(index).atag.expect(atag.U)
        dut.io.out.bits.uops(0).sources(index).operandClass.expect(OperandClass.P)
      }
      dut.io.out.bits.uops(0).sources(3).valid.expect(false.B)
      Seq(5, 6, 7, 8).zipWithIndex.foreach { case (atag, index) =>
        dut.io.out.bits.uops(1).sources(index).atag.expect(atag.U)
        dut.io.out.bits.uops(1).sources(index).operandClass.expect(OperandClass.P)
      }
      dut.io.out.bits.uops(0).plannedChildCount.expect(2.U)
      dut.io.out.bits.uops(1).plannedChildCount.expect(2.U)
      dut.io.out.bits.demand.dispatchWritesByClass(OooDispatchClass.Agu - 1).expect(2.U)
      dut.io.out.bits.demand.dispatchWritesByClass(OooDispatchClass.Std - 1).expect(2.U)
      dut.io.out.bits.demand.storeIds.expect(4.U)
    }
  }

  test("carries generated primary-parent PC-read policy into canonical uops") {
    val p = OooParams()
    simulate(new OooD1Decode(p)) { dut =>
      clear(dut)
      drive(dut, 0, "OP_LD_PCR", rule("OP_LD_PCR").value, 23, 0x2300)
      drive(dut, 1, "OP_LD", rule("OP_LD").value, 24, 0x2304)
      dut.io.in.bits.validMask.poke(3.U)
      dut.io.in.valid.poke(true.B)

      dut.io.out.bits.uops(0).recipe.pcReadRequired.expect(true.B)
      dut.io.out.bits.uops(1).recipe.pcReadRequired.expect(false.B)
      dut.io.out.bits.uops(0).identity.key.primaryParent.instructionId
        .expect(23.U)
    }
  }

  test("preserves P T U architectural aliases and counts their rename domains separately") {
    val p = OooParams()
    simulate(new OooD1Decode(p)) { dut =>
      clear(dut)
      val tDestination = encoded(
        "OP_ADD", (11, 7, 31), (19, 15, 24), (24, 20, 28))
      val uDestination = encoded(
        "OP_ADD", (11, 7, 30), (19, 15, 25), (24, 20, 29))
      drive(dut, 0, "OP_ADD", tDestination, 25, 0x2500)
      drive(dut, 1, "OP_ADD", uDestination, 26, 0x2504)
      dut.io.in.bits.validMask.poke(3.U)
      dut.io.in.valid.poke(true.B)

      dut.io.out.bits.uops(0).sources(0).operandClass.expect(OperandClass.T)
      dut.io.out.bits.uops(0).sources(0).relativeIndex.expect(0.U)
      dut.io.out.bits.uops(0).sources(1).operandClass.expect(OperandClass.U)
      dut.io.out.bits.uops(0).sources(1).relativeIndex.expect(0.U)
      dut.io.out.bits.uops(0).destinations(0).kind.expect(DestinationKind.T)
      dut.io.out.bits.uops(1).sources(0).operandClass.expect(OperandClass.T)
      dut.io.out.bits.uops(1).sources(0).relativeIndex.expect(1.U)
      dut.io.out.bits.uops(1).sources(1).operandClass.expect(OperandClass.U)
      dut.io.out.bits.uops(1).sources(1).relativeIndex.expect(1.U)
      dut.io.out.bits.uops(1).destinations(0).kind.expect(DestinationKind.U)
      dut.io.out.bits.demand.pDestinations.expect(0.U)
      dut.io.out.bits.demand.tAllocations.expect(1.U)
      dut.io.out.bits.demand.uAllocations.expect(1.U)
    }
  }

  test("fuses BSTART forward and BSTOP backward while preserving three parents") {
    val p = OooParams()
    simulate(new OooD1Decode(p)) { dut =>
      clear(dut)
      drive(dut, 0, "OP_BSTART_FALL", rule("OP_BSTART_FALL").value, 30, 0x3000)
      drive(dut, 1, "OP_ADD", rule("OP_ADD").value, 31, 0x3004)
      drive(dut, 2, "OP_BSTOP", rule("OP_BSTOP").value, 32, 0x3008)
      dut.io.in.bits.validMask.poke("b0111".U)
      dut.io.in.valid.poke(true.B)

      dut.io.out.bits.uopMask.expect(1.U)
      dut.io.out.bits.fusedStartMask.expect(1.U)
      dut.io.out.bits.fusedStopMask.expect(4.U)
      dut.io.out.bits.uops(0).opcode.expect(rule("OP_ADD").opcode.U)
      dut.io.out.bits.uops(0).identity.parentCount.expect(3.U)
      dut.io.out.bits.uops(0).identity.parents(0).key.instructionId.expect(30.U)
      dut.io.out.bits.uops(0).identity.parents(1).key.instructionId.expect(31.U)
      dut.io.out.bits.uops(0).identity.parents(2).key.instructionId.expect(32.U)
      dut.io.out.bits.uops(0).identity.boundary.start.expect(true.B)
      dut.io.out.bits.uops(0).identity.boundary.stop.expect(true.B)
      dut.io.out.bits.demand.instructionRows.expect(3.U)
      dut.io.out.bits.demand.decodedUops.expect(1.U)
      dut.io.out.bits.demand.brobSlots.expect(1.U)
    }
  }

  test("diverts CTU parents and converts invalid or illegal pair destinations to precise traps") {
    val p = OooParams()
    simulate(new OooD1Decode(p)) { dut =>
      clear(dut)
      drive(dut, 0, "OP_FENTRY", rule("OP_FENTRY").value, 40, 0x4000)
      drive(dut, 1, "OP_ADD", BigInt("ffffffff", 16), 41, 0x4004)
      val illegalPairDst = encoded(
        "OP_HL_LDIP", (27, 23, 31), (15, 11, 4), (35, 31, 5))
      drive(dut, 2, "OP_HL_LDIP", illegalPairDst, 42, 0x4008)
      dut.io.in.bits.entries(1).parent.lengthBytes.poke(4.U)
      dut.io.in.bits.validMask.poke("b0111".U)
      dut.io.in.valid.poke(true.B)

      dut.io.out.bits.ctuParentMask.expect(1.U)
      dut.io.out.bits.ctuParents(0).parent.key.instructionId.expect(40.U)
      dut.io.out.bits.ctuParents(0).parent.rawInstruction.expect(rule("OP_FENTRY").value.U)
      dut.io.out.bits.ctuParents(0).parent.prediction.epoch.expect(7.U)
      dut.io.out.bits.illegalParentMask.expect(6.U)
      dut.io.out.bits.uopMask.expect(3.U)
      dut.io.out.bits.uops(0).preciseTrap.expect(true.B)
      dut.io.out.bits.uops(1).preciseTrap.expect(true.B)
      dut.io.out.bits.uops(1).trapCause.expect(OooD1TrapCause.IllegalOperandClass.U)
      dut.io.out.bits.uops(0).recipe.disposition.expect(OooOpcodeDisposition.Illegal.U)
      dut.io.out.bits.demand.dispatchWritesByClass.foreach(_.expect(0.U))
    }
  }

  test("turns an IFU fetch fault into one precise trap with the original cause and parent") {
    val p = OooParams()
    simulate(new OooD1Decode(p)) { dut =>
      clear(dut)
      drive(dut, 0, "OP_ADD", rule("OP_ADD").value, 45, 0x4500)
      dut.io.in.bits.entries(0).fetchFaultValid.poke(true.B)
      dut.io.in.bits.entries(0).fetchFaultCause.poke("hdeadbeef".U)
      dut.io.in.bits.validMask.poke(1.U)
      dut.io.in.valid.poke(true.B)

      dut.io.out.bits.uopMask.expect(1.U)
      dut.io.out.bits.illegalParentMask.expect(1.U)
      dut.io.out.bits.uops(0).preciseTrap.expect(true.B)
      dut.io.out.bits.uops(0).trapCause.expect("hdeadbeef".U)
      dut.io.out.bits.uops(0).identity.parents(0).key.instructionId.expect(45.U)
      dut.io.out.bits.uops(0).immediateValid.expect(false.B)
      dut.io.out.bits.uops(0).boundaryTargetValid.expect(false.B)
      dut.io.out.bits.uops(0).recipe.fastResolveClass.expect(
        OooFastResolveClass.PreciseTrapRecord.U)
    }
  }

  test("rejects a lane-zero parent outside the packet PE STID epoch") {
    intercept[Exception] {
      simulate(new OooD1Decode(OooParams())) { dut =>
        clear(dut)
        drive(dut, 0, "OP_ADD", rule("OP_ADD").value, 46, 0x4600, stid = 0)
        dut.io.in.bits.validMask.poke(1.U)
        dut.io.in.valid.poke(true.B)
        dut.clock.step()
      }
    }
  }

  test("six-wide demand represents twelve destinations without capacity-width truncation") {
    val p = OooParams(instructionDecodeWidth = 6)
    simulate(new OooD1Decode(p)) { dut =>
      clear(dut)
      for (lane <- 0 until 6) {
        val raw = encoded(
          "OP_HL_LDIP", (27, 23, lane), (15, 11, lane + 8), (35, 31, 20))
        drive(dut, lane, "OP_HL_LDIP", raw, 50 + lane, 0x5000 + lane * 6)
      }
      dut.io.in.bits.validMask.poke("b111111".U)
      dut.io.in.valid.poke(true.B)

      dut.io.out.bits.uopMask.expect("b00111111".U)
      dut.io.out.bits.demand.pDestinations.expect(12.U)
      dut.io.out.bits.demand.mapQRows.expect(12.U)
      dut.io.out.bits.demand.loadIds.expect(12.U)
    }
  }
}
