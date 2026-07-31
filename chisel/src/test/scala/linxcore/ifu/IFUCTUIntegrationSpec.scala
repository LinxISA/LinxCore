package linxcore.ifu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.params.ParamProfiles
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class IFUCTUIntegrationSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: IFU): Unit = {
    dut.io.toCtu.ready.poke(false.B)
    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
    dut.io.recovery.prepare.valid.poke(false.B)
    dut.io.recovery.prepare.bits.poke(0.U.asTypeOf(dut.io.recovery.prepare.bits))
    dut.io.recovery.prepared.ready.poke(true.B)
    dut.io.recovery.apply.valid.poke(false.B)
    dut.io.recovery.apply.bits.poke(0.U.asTypeOf(dut.io.recovery.apply.bits))
    dut.io.recovery.abort.valid.poke(false.B)
    dut.io.recovery.abort.bits.poke(0.U.asTypeOf(dut.io.recovery.abort.bits))
    dut.io.trace.ready.poke(true.B)
  }

  private def serveMemoryUntilCtu(dut: IFU, limit: Int = 600): Unit = {
    var cycles = 0
    while (!dut.io.toCtu.valid.peek().litToBoolean && cycles < limit) {
      if (dut.io.memoryRequest.valid.peek().litToBoolean) {
        val request = dut.io.memoryRequest.bits.peek()
        val address = request.address.litValue
        val value = request.identity.value.litValue
        val generation = request.identity.generation.litValue
        val accessKind = request.accessKind.litValue
        dut.clock.step()

        val data =
          if (accessKind == MemoryAccessKind.InstructionTranslation.litValue) {
            address >> 8
          } else {
            BigInt("0008000600040002", 16)
          }
        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
        dut.io.memoryResponse.bits.identity.value.poke(value.U)
        dut.io.memoryResponse.bits.identity.generation.poke(generation.U)
        dut.io.memoryResponse.bits.data.poke(data.U)
        dut.io.memoryResponse.ready.expect(true.B)
        dut.clock.step()
        dut.io.memoryResponse.valid.poke(false.B)
      } else {
        dut.clock.step()
      }
      cycles += 1
    }
    assert(cycles < limit, "IFU did not deliver a CTU packet")
  }

  test("W2 W4 W6 and W8 public IFU retain fixed 64-bit CTU traffic backpressure and scoped recovery") {
    Seq(2, 4, 6, 8).foreach { width =>
      val p = ParamProfiles.forWidth(width)
      assert(p.instructionWidth == 64)
      simulate(new IFU(p)) { dut =>
        assert(dut.io.toCtu.bits.entries.length == width)
        assert(dut.io.toCtu.bits.entries(0).instruction.getWidth == 64)
        clear(dut)
        dut.io.memoryRequest.ready.poke(true.B)
        serveMemoryUntilCtu(dut)

        dut.io.toCtu.valid.expect(true.B)
        val heldPc = dut.io.toCtu.bits.entries(0).pc.peek().litValue
        val heldInstruction = dut.io.toCtu.bits.entries(0).instruction.peek().litValue
        dut.clock.step(4)
        dut.io.toCtu.valid.expect(true.B)
        assert(dut.io.toCtu.bits.entries(0).pc.peek().litValue == heldPc)
        assert(dut.io.toCtu.bits.entries(0).instruction.peek().litValue == heldInstruction)

        dut.io.recovery.prepare.bits.poke(0.U.asTypeOf(dut.io.recovery.prepare.bits))
        dut.io.recovery.prepare.bits.transactionId.poke(99.U)
        dut.io.recovery.prepare.bits.phase.poke(RecoveryPhase.Prepare)
        dut.io.recovery.prepare.bits.cause.poke(RecoveryCause.Branch)
        dut.io.recovery.prepare.bits.trigger.stid.poke(0.U)
        dut.io.recovery.prepare.bits.redirectPc.poke(0x80.U)
        dut.io.recovery.prepare.bits.newEpoch.poke(3.U)
        dut.io.recovery.prepare.valid.poke(true.B)
        dut.io.recovery.prepare.ready.expect(true.B)
        dut.clock.step()
        dut.io.recovery.prepare.valid.poke(false.B)
        dut.io.toCtu.valid.expect(false.B)

        dut.io.recovery.apply.bits.poke(0.U.asTypeOf(dut.io.recovery.apply.bits))
        dut.io.recovery.apply.bits.transactionId.poke(99.U)
        dut.io.recovery.apply.bits.phase.poke(RecoveryPhase.Apply)
        dut.io.recovery.apply.bits.cause.poke(RecoveryCause.Branch)
        dut.io.recovery.apply.bits.trigger.stid.poke(0.U)
        dut.io.recovery.apply.bits.redirectPc.poke(0x80.U)
        dut.io.recovery.apply.bits.newEpoch.poke(3.U)
        dut.io.recovery.apply.valid.poke(true.B)
        dut.clock.step()
        dut.io.recovery.apply.valid.poke(false.B)
      }
    }
  }

  test("W2 W4 W6 and W8 IFU elaboration exposes explicit prediction and recovery boundaries") {
    Seq(2, 4, 6, 8).foreach { width =>
      val sv = ChiselStage.emitSystemVerilog(new IFU(ParamProfiles.forWidth(width)))
      assert(sv.contains("module IFU"))
      assert(sv.contains("module BSide"))
      assert(sv.contains("module IFURecovery"))
    }
  }
}
