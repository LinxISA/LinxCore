package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.iex.OOOIEXLSUActivationParams
import linxcore.params.ParamProfiles
import linxcore.top.interface.FrontEndOpKind
import org.scalatest.funsuite.AnyFunSuite

class OOOExternalD1CommitSpec extends AnyFunSuite with ChiselSim {
  private def initialize(dut: OOO): Unit = {
    dut.io.fromCtu.valid.poke(false.B)
    dut.io.fromCtu.bits.poke(0.U.asTypeOf(dut.io.fromCtu.bits))
    dut.io.iex.aluDispatch.foreach(_.ready.poke(false.B))
    dut.io.iex.bruDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.aguDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.storeDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.systemDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.cmdDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.fastWriteback.ready.poke(true.B)
    dut.io.iex.fastWakeup.ready.poke(true.B)
    dut.io.iex.pcBufferReadAddress.foreach(
      _.poke(0.U.asTypeOf(dut.io.iex.pcBufferReadAddress.head)))
    dut.io.iex.robNoflushReady.valid.poke(false.B)
    dut.io.iex.robNoflushReady.bits.poke(
      0.U.asTypeOf(dut.io.iex.robNoflushReady.bits))
    dut.io.iex.robNoflush.ready.poke(true.B)
    dut.io.iex.robResolve.foreach { port =>
      port.valid.poke(false.B)
      port.bits.poke(0.U.asTypeOf(port.bits))
    }
    dut.io.iex.systemIssue.foreach(_.valid.poke(false.B))
    dut.io.iex.systemIssue.foreach(
      _.bits.poke(0.U.asTypeOf(dut.io.iex.systemIssue.head.bits)))
    dut.io.iex.recoveryEvent.valid.poke(false.B)
    dut.io.iex.recoveryEvent.bits.poke(
      0.U.asTypeOf(dut.io.iex.recoveryEvent.bits))
    dut.io.commit.ready.poke(true.B)
    dut.io.trap.ready.poke(true.B)
    dut.io.interrupt.valid.poke(false.B)
    dut.io.interrupt.bits.poke(0.U.asTypeOf(dut.io.interrupt.bits))
    Seq(dut.io.recoveryToIfu, dut.io.recoveryToCtu,
      dut.io.recoveryToLsu, dut.io.iex.recovery).foreach { target =>
      target.prepare.ready.poke(true.B)
      target.prepared.valid.poke(false.B)
      target.prepared.bits.poke(0.U.asTypeOf(target.prepared.bits))
    }
    dut.io.trace.ready.poke(true.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  test("external D1 body without block markers cannot pass BROB commit preflight") {
    val base = ParamProfiles.W2
    val p = base.copy(ooo = base.ooo.copy(
      robGroupsPerStid = 8,
      robBankCount = 2,
      brobEntriesPerStid = 8,
      pcBufferEntries = 8,
      pcBankCount = 4,
      pcRecoveryScanGroupsPerCycle = 4,
      gprPhysRegs = 64,
      gprMapQDepthPerStid = 8,
      tPhysRegs = 8,
      uPhysRegs = 8,
      tuMapQDepthPerStid = 8))
    simulate(new OOO(p)) { dut =>
      initialize(dut)

      val addi = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_ADDI").get
      val entry = dut.io.fromCtu.bits.entries.head
      dut.io.fromCtu.bits.count.poke(1.U)
      entry.kind.poke(FrontEndOpKind.Encoded64)
      entry.parent.identity.peId.poke(1.U)
      entry.parent.identity.stid.poke(0.U)
      entry.parent.identity.instructionId.poke(1.U)
      entry.parent.identity.epoch.poke(1.U)
      entry.parent.pc.poke(0x1004.U)
      entry.parent.instruction.poke(
        (addi.value | (BigInt(5) << 7) | (BigInt(7) << 20)).U)
      entry.parent.lengthBytes.poke(addi.lenBytes.U)
      entry.parent.prediction.valid.poke(true.B)
      entry.parent.prediction.requestPc.poke(0x1004.U)
      entry.parent.prediction.fallthroughPc.poke(0x1008.U)
      entry.parent.prediction.epoch.poke(1.U)
      dut.io.fromCtu.valid.poke(true.B)
      while (!dut.io.fromCtu.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.io.fromCtu.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.iex.aluDispatch.head.valid.peek().litToBoolean &&
          cycles < 64) { dut.clock.step(); cycles += 1 }
      assert(cycles < 64)
      val identity = dut.io.iex.aluDispatch.head.bits.uop.decoded.rob.peek()
      dut.io.iex.aluDispatch.head.ready.poke(true.B)
      dut.clock.step()
      val completion = dut.io.iex.robResolve.head
      completion.bits.poke(0.U.asTypeOf(completion.bits))
      completion.bits.rob.poke(identity)
      completion.valid.poke(true.B)
      completion.ready.expect(true.B)
      dut.clock.step()
      completion.valid.poke(false.B)

      dut.clock.step(64)
      dut.io.commit.valid.expect(false.B)
    }
  }

  test("external D1 closed block passes BROB and PC preflight then commits") {
    val p = OOOIEXLSUActivationParams.W4
    simulate(new OOO(p)) { dut =>
      initialize(dut)
      val start = OooOpcodeRecipeTable.Rules.find(
        _.symbol == "OP_BSTART_FALL").get
      val addi = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_ADDI").get
      val stop = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_BSTOP").get
      val encodings = Seq(
        (start, start.value, 0x1000),
        (addi, addi.value | (BigInt(5) << 7) | (BigInt(7) << 20), 0x1004),
        (stop, stop.value, 0x1008))
      dut.io.fromCtu.bits.count.poke(encodings.length.U)
      encodings.zipWithIndex.foreach { case ((rule, raw, pc), lane) =>
        val entry = dut.io.fromCtu.bits.entries(lane)
        entry.kind.poke(FrontEndOpKind.Encoded64)
        entry.parent.identity.peId.poke(1.U)
        entry.parent.identity.stid.poke(0.U)
        entry.parent.identity.instructionId.poke((lane + 1).U)
        entry.parent.identity.epoch.poke(1.U)
        entry.parent.pc.poke(pc.U)
        entry.parent.instruction.poke(raw.U)
        entry.parent.lengthBytes.poke(rule.lenBytes.U)
        entry.parent.prediction.valid.poke(true.B)
        entry.parent.prediction.requestPc.poke(pc.U)
        entry.parent.prediction.fallthroughPc.poke((pc + rule.lenBytes).U)
        entry.parent.prediction.epoch.poke(1.U)
      }
      dut.io.fromCtu.valid.poke(true.B)
      while (!dut.io.fromCtu.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.io.fromCtu.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.iex.aluDispatch.head.valid.peek().litToBoolean &&
          cycles < 64) { dut.clock.step(); cycles += 1 }
      assert(cycles < 64)
      val identity = dut.io.iex.aluDispatch.head.bits.uop.decoded.rob.peek()
      dut.io.iex.aluDispatch.head.ready.poke(true.B)
      dut.clock.step()
      val completion = dut.io.iex.robResolve.head
      completion.bits.poke(0.U.asTypeOf(completion.bits))
      completion.bits.rob.poke(identity)
      completion.bits.destinationValid.poke(true.B)
      completion.bits.value.poke(7.U)
      completion.valid.poke(true.B)
      completion.ready.expect(true.B)
      dut.clock.step()
      completion.valid.poke(false.B)

      cycles = 0
      while (!dut.io.commit.valid.peek().litToBoolean && cycles < 64) {
        dut.clock.step(); cycles += 1
      }
      assert(cycles < 64)
      dut.io.commit.bits.count.expect(1.U)
      dut.io.commit.bits.entries.head.rob.expect(identity)
      dut.clock.step()
      dut.io.commit.valid.expect(false.B)
    }
  }
}
