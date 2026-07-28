package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooThreadStageBufferSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams()

  private def clear(dut: OooThreadStageBuffer): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.out.ready.poke(false.B)
    dut.io.fence.foreach(_.poke(false.B))
    dut.io.cancel.foreach(_.poke(false.B))
  }

  private def enqueue(dut: OooThreadStageBuffer, stid: Int, transactionId: Int): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.bits.stid.poke(stid.U)
    dut.io.in.bits.transactionId.poke(transactionId.U)
    dut.io.in.bits.instructionMask.poke(1.U)
    dut.io.in.bits.uopMask.poke(1.U)
    dut.io.in.valid.poke(true.B)
    dut.io.in.ready.expect(true.B)
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  test("retains one row per STID and holds the selected grant under backpressure") {
    simulate(new OooThreadStageBuffer(p)) { dut =>
      clear(dut)
      enqueue(dut, stid = 2, transactionId = 22)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transactionId.expect(22.U)
      dut.clock.step()

      enqueue(dut, stid = 0, transactionId = 10)
      dut.io.occupancy.expect(2.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.stid.expect(2.U)
      dut.io.out.bits.transactionId.expect(22.U)

      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.stid.expect(0.U)
      dut.io.out.bits.transactionId.expect(10.U)
    }
  }

  test("cancellation removes only the selected STID row") {
    simulate(new OooThreadStageBuffer(p)) { dut =>
      clear(dut)
      enqueue(dut, stid = 0, transactionId = 10)
      enqueue(dut, stid = 1, transactionId = 11)
      dut.io.occupancy.expect(2.U)

      dut.io.cancel(0).poke(true.B)
      dut.clock.step()
      dut.io.cancel(0).poke(false.B)
      dut.io.occupancy.expect(1.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.stid.expect(1.U)
      dut.io.out.bits.transactionId.expect(11.U)
    }
  }

  test("fence suppresses selection and intake without deleting the retained row") {
    simulate(new OooThreadStageBuffer(p)) { dut =>
      clear(dut)
      enqueue(dut, stid = 0, transactionId = 10)
      enqueue(dut, stid = 2, transactionId = 12)

      dut.io.fence(0).poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.stid.expect(2.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.occupancy.expect(1.U)

      dut.io.in.bits.stid.poke(0.U)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.fence(0).poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transactionId.expect(10.U)
    }
  }
}

class LinxCoreOooShellSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams()

  private def clear(dut: LinxCoreOooShell): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.out.ready.poke(false.B)
    dut.io.fence.foreach(_.poke(false.B))
    dut.io.cancel.foreach(_.poke(false.B))
  }

  private def enqueue(dut: LinxCoreOooShell, stid: Int, transactionId: Int): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.bits.stid.poke(stid.U)
    dut.io.in.bits.transactionId.poke(transactionId.U)
    dut.io.in.bits.instructionMask.poke(1.U)
    dut.io.in.bits.uopMask.poke(1.U)
    dut.io.in.valid.poke(true.B)
    dut.io.in.ready.expect(true.B)
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  test("D2 D3 and S1 may retain different STIDs in the same cycle") {
    simulate(new LinxCoreOooShell(p)) { dut =>
      clear(dut)
      enqueue(dut, stid = 0, transactionId = 10)
      enqueue(dut, stid = 1, transactionId = 11)
      enqueue(dut, stid = 2, transactionId = 12)

      dut.io.d2SelectedValid.expect(true.B)
      dut.io.d3SelectedValid.expect(true.B)
      dut.io.s1SelectedValid.expect(true.B)
      dut.io.d2SelectedStid.expect(2.U)
      dut.io.d3SelectedStid.expect(1.U)
      dut.io.s1SelectedStid.expect(0.U)

      dut.io.cancel(1).poke(true.B)
      dut.clock.step()
      dut.io.cancel(1).poke(false.B)
      dut.io.s1SelectedValid.expect(true.B)
      dut.io.s1SelectedStid.expect(0.U)
    }
  }

  test("shell elaborates for one two and four STIDs at decode widths 2 4 and 6") {
    for {
      stids <- Seq(1, 2, 4)
      width <- Seq(2, 4, 6)
    } {
      val params = OooParams(
        stidCount = stids,
        instructionDecodeWidth = width,
        pPhysRegs = 128)
      simulate(new LinxCoreOooShell(params)) { dut =>
        clear(dut)
        dut.io.d2Occupancy.expect(0.U)
        dut.io.d3Occupancy.expect(0.U)
        dut.io.s1Occupancy.expect(0.U)
      }
    }
  }
}
