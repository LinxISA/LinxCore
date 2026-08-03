package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.params.ParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class OooIexIssueBlockMatrixSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 2,
    iqBankCount = 2,
    iqEntriesPerBank = 4,
    iexIssueDomainCount = 2)

  private def clear(dut: OooIexIssueBlockMatrix): Unit = {
    dut.io.policy.poke(0.U.asTypeOf(dut.io.policy))
    dut.io.queries.poke(0.U.asTypeOf(dut.io.queries))
  }

  test("applies load and store pressure only to matching row types") {
    simulate(new OooIexIssueBlockMatrix(p)) { dut =>
      clear(dut)
      dut.io.queries(0).uopClass.poke(OooUopClass.Agu)
      dut.io.queries(0).stid.poke(1.U)
      dut.io.queries(0).isStore.poke(false.B)
      dut.io.queries(1).uopClass.poke(OooUopClass.Std)
      dut.io.queries(1).stid.poke(0.U)
      dut.io.queries(1).isStore.poke(true.B)

      dut.io.policy.loadQueuePressure.poke("b10".U)
      dut.io.policy.storeWindowPressure.poke("b01".U)
      dut.io.blocked.expect("b11".U)
      dut.io.reasonMasks(0).expect(
        (1 << OooIexIssueBlockReason.LoadQueuePressure).U)
      dut.io.reasonMasks(1).expect(
        (1 << OooIexIssueBlockReason.StoreWindowPressure).U)

      // A store-address row is not a load-pressure victim, and a non-store
      // AGU row is not blocked by the STQ window.
      dut.io.queries(0).isStore.poke(true.B)
      dut.io.queries(1).uopClass.poke(OooUopClass.Agu)
      dut.io.queries(1).isStore.poke(false.B)
      dut.io.blocked.expect(0.U)
    }
  }

  test("accumulates shared class and private pipe reasons independently") {
    simulate(new OooIexIssueBlockMatrix(p)) { dut =>
      clear(dut)
      dut.io.queries.foreach(_.uopClass.poke(OooUopClass.Alu))
      dut.io.queries(0).stid.poke(0.U)
      dut.io.queries(1).stid.poke(1.U)

      dut.io.policy.powerThrottle.poke(true.B)
      dut.io.policy.classPressure(0).poke("b01".U)
      dut.io.policy.domainStructural(0).poke("b01".U)
      dut.io.policy.latencyReservation(0).poke("b01".U)
      dut.io.policy.reflowReservation(1).poke("b10".U)
      dut.io.policy.sideDoorConflict(1).poke("b10".U)
      dut.io.policy.resultBusReservation(1).poke("b10".U)

      val domain0 =
        (1 << OooIexIssueBlockReason.PowerThrottle) |
          (1 << OooIexIssueBlockReason.ClassPressure) |
          (1 << OooIexIssueBlockReason.DomainStructural) |
          (1 << OooIexIssueBlockReason.LatencyReservation)
      val domain1 =
        (1 << OooIexIssueBlockReason.PowerThrottle) |
          (1 << OooIexIssueBlockReason.ReflowReservation) |
          (1 << OooIexIssueBlockReason.SideDoorConflict) |
          (1 << OooIexIssueBlockReason.ResultBusReservation)
      dut.io.reasonMasks(0).expect(domain0.U)
      dut.io.reasonMasks(1).expect(domain1.U)
      dut.io.blocked.expect("b11".U)
    }
  }

  test("global quiesce blocks every domain without fabricating local reasons") {
    simulate(new OooIexIssueBlockMatrix(p)) { dut =>
      clear(dut)
      dut.io.queries(0).uopClass.poke(OooUopClass.Bru)
      dut.io.queries(1).uopClass.poke(OooUopClass.Sys)
      dut.io.queries(1).stid.poke(1.U)
      dut.io.policy.globalQuiesce.poke(true.B)

      val global = 1 << OooIexIssueBlockReason.GlobalQuiesce
      dut.io.reasonMasks.foreach(_.expect(global.U))
      dut.io.blocked.expect("b11".U)
    }
  }

  test("canonical operand-read composition exposes policy and diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new OooIexIssueReadFabric(
      ParamProfiles.W2))

    assert(sv.contains("module OooIexIssueReadFabric"))
    assert(sv.contains("io_issuePolicy_globalQuiesce"))
    assert(sv.contains("io_pickPolicyBlocked_0_valid"))
    assert(sv.contains("io_queryPolicyReasons_0"))
    assert(sv.contains("io_policyBlockedCount_0"))
  }
}
