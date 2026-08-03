package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

private class OOOCommitApplyPolicyProbeIO extends Bundle {
  val transactionFire = Input(Bool())
  val commitCount = Input(UInt(4.W))
  val robTransactionApply = Output(Bool())
  val releaseOwnersApply = Output(Bool())
}

private class OOOCommitApplyPolicyProbe extends Module {
  val io = IO(new OOOCommitApplyPolicyProbeIO)
  io.robTransactionApply := io.transactionFire
  io.releaseOwnersApply := OOOCommitApplyPolicy.releaseFire(
    io.transactionFire, io.commitCount)
}

class OOOCommitApplyPolicySpec extends AnyFunSuite with ChiselSim {
  test("trap-only acceptance applies ROB transaction but no release owner") {
    simulate(new OOOCommitApplyPolicyProbe) { dut =>
      dut.io.transactionFire.poke(true.B)
      dut.io.commitCount.poke(0.U)
      dut.io.robTransactionApply.expect(true.B)
      dut.io.releaseOwnersApply.expect(false.B)

      dut.io.commitCount.poke(1.U)
      dut.io.robTransactionApply.expect(true.B)
      dut.io.releaseOwnersApply.expect(true.B)

      dut.io.transactionFire.poke(false.B)
      dut.io.releaseOwnersApply.expect(false.B)
    }
  }
}
