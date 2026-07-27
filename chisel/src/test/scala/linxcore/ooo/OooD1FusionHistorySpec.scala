package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooD1FusionHistorySpec extends AnyFunSuite with ChiselSim {
  private def rule(symbol: String): OooOpcodeRecipeTable.Rule =
    OooOpcodeRecipeTable.Rules.find(_.symbol == symbol).getOrElse(
      fail(s"missing generated recipe for $symbol"))

  private def clear(dut: OooD1ProductionDecode): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.out.ready.poke(true.B)
    dut.io.cancel.foreach(_.poke(false.B))
  }

  private def beginPacket(
      dut: OooD1ProductionDecode,
      stid: Int,
      epoch: Int = 9,
      endOfStream: Boolean = false): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.bits.peId.poke(3.U)
    dut.io.in.bits.stid.poke(stid.U)
    dut.io.in.bits.epoch.poke(epoch.U)
    dut.io.in.bits.endOfStream.poke(endOfStream.B)
  }

  private def driveEntry(
      dut: OooD1ProductionDecode,
      lane: Int,
      symbol: String,
      instructionId: Int,
      pc: BigInt,
      stid: Int,
      epoch: Int = 9): Unit = {
    val entry = dut.io.in.bits.entries(lane)
    entry.parent.key.valid.poke(true.B)
    entry.parent.key.peId.poke(3.U)
    entry.parent.key.stid.poke(stid.U)
    entry.parent.key.instructionId.poke(instructionId.U)
    entry.parent.key.epoch.poke(epoch.U)
    entry.parent.pc.poke(pc.U)
    entry.parent.rawInstruction.poke(rule(symbol).value.U)
    entry.parent.lengthBytes.poke(rule(symbol).lenBytes.U)
    entry.parent.traceOwner.poke(true.B)
    entry.parent.preciseExceptionOwner.poke(true.B)
    entry.parent.prediction.valid.poke(true.B)
    entry.parent.prediction.predictionTag.poke((0x100 + instructionId).U)
    entry.parent.prediction.transactionId.poke((0x200 + instructionId).U)
    entry.parent.prediction.fetchPacketUid.poke((0x300 + instructionId).U)
    entry.parent.prediction.fetchSeq.poke((0x400 + instructionId).U)
    entry.parent.prediction.requestPc.poke(pc.U)
    entry.parent.prediction.branchPc.poke(pc.U)
    entry.parent.prediction.fallthroughPc.poke((pc + rule(symbol).lenBytes).U)
    entry.parent.prediction.checkpointId.poke(2.U)
    entry.parent.prediction.epoch.poke(epoch.U)
  }

  test("fuses a retained BSTART with next-cycle carrier and same-packet BSTOP") {
    simulate(new OooD1ProductionDecode(OooParams())) { dut =>
      clear(dut)
      beginPacket(dut, stid = 1)
      driveEntry(dut, 0, "OP_BSTART_FALL", 10, 0x1000, stid = 1)
      dut.io.in.bits.validMask.poke(1.U)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.held(1).expect(true.B)

      beginPacket(dut, stid = 1, endOfStream = true)
      driveEntry(dut, 0, "OP_ADD", 11, 0x1004, stid = 1)
      driveEntry(dut, 1, "OP_BSTOP", 12, 0x1008, stid = 1)
      dut.io.in.bits.validMask.poke(3.U)
      dut.io.in.valid.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.out.bits.uopMask.expect(1.U)
      dut.io.out.bits.uops(0).opcode.expect(rule("OP_ADD").opcode.U)
      dut.io.out.bits.uops(0).identity.parentCount.expect(3.U)
      dut.io.out.bits.uops(0).identity.parents(0).key.instructionId.expect(10.U)
      dut.io.out.bits.uops(0).identity.parents(1).key.instructionId.expect(11.U)
      dut.io.out.bits.uops(0).identity.parents(2).key.instructionId.expect(12.U)
      dut.io.out.bits.uops(0).identity.boundary.start.expect(true.B)
      dut.io.out.bits.uops(0).identity.boundary.stop.expect(true.B)
      dut.io.out.bits.demand.instructionRows.expect(3.U)
      dut.clock.step()
      dut.io.held(1).expect(false.B)
    }
  }

  test("fuses a next-cycle BSTOP backward without patching a published carrier") {
    simulate(new OooD1ProductionDecode(OooParams())) { dut =>
      clear(dut)
      beginPacket(dut, stid = 0)
      driveEntry(dut, 0, "OP_ADD", 20, 0x2000, stid = 0)
      dut.io.in.bits.validMask.poke(1.U)
      dut.io.in.valid.poke(true.B)
      dut.io.out.valid.expect(false.B)
      dut.clock.step()

      beginPacket(dut, stid = 0, endOfStream = true)
      driveEntry(dut, 0, "OP_BSTOP", 21, 0x2004, stid = 0)
      dut.io.in.bits.validMask.poke(1.U)
      dut.io.in.valid.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.uops(0).opcode.expect(rule("OP_ADD").opcode.U)
      dut.io.out.bits.uops(0).identity.parentCount.expect(2.U)
      dut.io.out.bits.uops(0).identity.parents(0).key.instructionId.expect(20.U)
      dut.io.out.bits.uops(0).identity.parents(1).key.instructionId.expect(21.U)
      dut.io.out.bits.uops(0).identity.boundary.stop.expect(true.B)
    }
  }

  test("does not fuse a retained BSTART across a prediction epoch boundary") {
    simulate(new OooD1ProductionDecode(OooParams())) { dut =>
      clear(dut)
      beginPacket(dut, stid = 1)
      driveEntry(dut, 0, "OP_BSTART_FALL", 22, 0x2200, stid = 1)
      dut.io.in.bits.validMask.poke(1.U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()

      beginPacket(dut, stid = 1, endOfStream = true)
      driveEntry(dut, 0, "OP_ADD", 23, 0x2204, stid = 1)
      dut.io.in.bits.entries(0).parent.prediction.epoch.poke(10.U)
      dut.io.in.bits.validMask.poke(1.U)
      dut.io.in.valid.poke(true.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.uopMask.expect(3.U)
      dut.io.out.bits.uops(0).opcode.expect(rule("OP_BSTART_FALL").opcode.U)
      dut.io.out.bits.uops(0).identity.parentCount.expect(1.U)
      dut.io.out.bits.uops(1).opcode.expect(rule("OP_ADD").opcode.U)
      dut.io.out.bits.uops(1).identity.parentCount.expect(1.U)
    }
  }

  test("does not fuse a retained carrier with BSTOP across a prediction epoch boundary") {
    simulate(new OooD1ProductionDecode(OooParams())) { dut =>
      clear(dut)
      beginPacket(dut, stid = 2)
      driveEntry(dut, 0, "OP_ADD", 24, 0x2400, stid = 2)
      dut.io.in.bits.validMask.poke(1.U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()

      beginPacket(dut, stid = 2, endOfStream = true)
      driveEntry(dut, 0, "OP_BSTOP", 25, 0x2404, stid = 2)
      dut.io.in.bits.entries(0).parent.prediction.epoch.poke(10.U)
      dut.io.in.bits.validMask.poke(1.U)
      dut.io.in.valid.poke(true.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.uopMask.expect(3.U)
      dut.io.out.bits.uops(0).opcode.expect(rule("OP_ADD").opcode.U)
      dut.io.out.bits.uops(0).identity.parentCount.expect(1.U)
      dut.io.out.bits.uops(1).opcode.expect(rule("OP_BSTOP").opcode.U)
      dut.io.out.bits.uops(1).identity.parentCount.expect(1.U)
    }
  }

  test("cancels history only for the selected STID and drains another STID at end-of-stream") {
    simulate(new OooD1ProductionDecode(OooParams())) { dut =>
      clear(dut)
      for (stid <- Seq(0, 2)) {
        beginPacket(dut, stid = stid)
        driveEntry(dut, 0, "OP_ADD", 30 + stid, 0x3000 + stid * 0x100, stid = stid)
        dut.io.in.bits.validMask.poke(1.U)
        dut.io.in.valid.poke(true.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.in.valid.poke(false.B)
      dut.io.held(0).expect(true.B)
      dut.io.held(2).expect(true.B)

      dut.io.cancel(0).poke(true.B)
      dut.clock.step()
      dut.io.cancel(0).poke(false.B)
      dut.io.held(0).expect(false.B)
      dut.io.held(2).expect(true.B)

      beginPacket(dut, stid = 2, endOfStream = true)
      dut.io.in.bits.validMask.poke(0.U)
      dut.io.in.valid.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.uops(0).identity.key.primaryParent.stid.expect(2.U)
      dut.io.out.bits.uops(0).identity.key.primaryParent.instructionId.expect(32.U)
      dut.clock.step()
      dut.io.held(2).expect(false.B)
    }
  }

  test("retains the fused terminal transaction unchanged under backpressure") {
    simulate(new OooD1ProductionDecode(OooParams())) { dut =>
      clear(dut)
      beginPacket(dut, stid = 3)
      driveEntry(dut, 0, "OP_ADD", 40, 0x4000, stid = 3)
      dut.io.in.bits.validMask.poke(1.U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()

      beginPacket(dut, stid = 3, endOfStream = true)
      driveEntry(dut, 0, "OP_BSTOP", 41, 0x4004, stid = 3)
      dut.io.in.bits.validMask.poke(1.U)
      dut.io.in.valid.poke(true.B)
      dut.io.out.ready.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.in.ready.expect(false.B)
      dut.io.out.bits.uops(0).identity.parentCount.expect(2.U)
      dut.io.out.bits.uops(0).identity.parents(1).key.instructionId.expect(41.U)
      dut.clock.step(3)
      dut.io.out.bits.uops(0).identity.parentCount.expect(2.U)
      dut.io.out.bits.uops(0).identity.parents(1).key.instructionId.expect(41.U)
      dut.io.held(3).expect(true.B)

      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.held(3).expect(false.B)
    }
  }

  test("falls back to standalone marker when a full terminal packet cannot retain fusion history") {
    val p = OooParams(instructionDecodeWidth = 2)
    simulate(new OooD1ProductionDecode(p)) { dut =>
      clear(dut)
      beginPacket(dut, stid = 0)
      driveEntry(dut, 0, "OP_BSTART_FALL", 50, 0x5000, stid = 0)
      dut.io.in.bits.validMask.poke(1.U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()

      beginPacket(dut, stid = 0, endOfStream = true)
      driveEntry(dut, 0, "OP_ADD", 51, 0x5004, stid = 0)
      driveEntry(dut, 1, "OP_ADD", 52, 0x5008, stid = 0)
      dut.io.in.bits.validMask.poke(3.U)
      dut.io.in.valid.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.in.ready.expect(false.B)
      dut.io.out.bits.uops(0).opcode.expect(rule("OP_BSTART_FALL").opcode.U)
      dut.io.out.bits.uops(0).identity.parentCount.expect(1.U)
      dut.clock.step()

      dut.io.in.ready.expect(true.B)
      dut.io.out.bits.uopMask.expect(3.U)
      dut.io.out.bits.uops(0).identity.key.primaryParent.instructionId.expect(51.U)
      dut.io.out.bits.uops(1).identity.key.primaryParent.instructionId.expect(52.U)
    }
  }
}
