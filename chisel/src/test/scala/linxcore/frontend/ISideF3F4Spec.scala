package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{BoundaryKind, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

class ISideF3F4Spec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()
  private val lineBytes = 16

  private def clearF3(dut: ISideF3LineAssembler): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.nextLineRequest.ready.poke(false.B)
    dut.io.nextLineResponse.valid.poke(false.B)
    dut.io.nextLineResponse.bits.poke(0.U.asTypeOf(dut.io.nextLineResponse.bits))
    dut.io.out.ready.poke(false.B)
    dut.io.terminateResident.poke(false.B)
    dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
  }

  private def pokeHit(
      dut: ISideF3LineAssembler,
      pc: BigInt,
      transactionId: Int,
      lineData: BigInt): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.status.poke(ISideF2Status.Hit)
    dut.io.in.bits.lineData.poke(lineData.U)
    dut.io.in.bits.request.pc.poke(pc.U)
    dut.io.in.bits.request.lineVa.poke((pc & ~BigInt(lineBytes - 1)).U)
    dut.io.in.bits.request.transactionId.poke(transactionId.U)
    dut.io.in.bits.request.identity.threadId.poke(0.U)
    dut.io.in.bits.request.identity.fetchSeq.poke(transactionId.U)
    dut.io.in.bits.request.identity.epoch.poke(0.U)
    dut.io.in.bits.request.prediction.kind.poke(BoundaryKind.Fall)
  }

  test("I-F3 emits four variable-length candidates from one line") {
    simulate(new ISideF3LineAssembler(p, lineBytes)) { dut =>
      clearF3(dut)
      val line = BigInt("0040003000200010", 16)
      pokeHit(dut, pc = 0, transactionId = 1, lineData = line)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b1111".U)
      dut.io.out.bits.entries(0).pc.expect(0.U)
      dut.io.out.bits.entries(3).pc.expect(6.U)
      dut.io.out.bits.entries(0).lenBytes.expect(2.U)
      dut.io.out.bits.entries(3).insn.expect(0x40.U)
      dut.io.waitingForNextLine.expect(false.B)
    }
  }

  test("I-F3 drains every instruction in a cacheline across consecutive four-wide groups") {
    simulate(new ISideF3LineAssembler(p, lineBytes)) { dut =>
      clearF3(dut)
      val eightCompressedInstructions = BigInt("00800070006000500040003000200010", 16)
      pokeHit(dut, pc = 0, transactionId = 2, lineData = eightCompressedInstructions)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b1111".U)
      dut.io.out.bits.entries(0).pc.expect(0.U)
      dut.io.out.bits.entries(3).pc.expect(6.U)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(false.B)
      dut.clock.step()

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b1111".U)
      dut.io.out.bits.entries(0).pc.expect(8.U)
      dut.io.out.bits.entries(3).pc.expect(14.U)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  test("I-F3 releases a cacheline after a BSTOP instead of emitting younger bytes") {
    simulate(new ISideF3LineAssembler(p, lineBytes)) { dut =>
      clearF3(dut)
      val stopInFirstGroup = BigInt("00800070006000500040000000200010", 16)
      pokeHit(dut, pc = 0, transactionId = 3, lineData = stopInFirstGroup)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b1111".U)
      dut.io.out.bits.entries(2).insn.expect(0.U)
      dut.io.out.ready.poke(true.B)
      dut.io.terminateResident.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  test("I-F3 alone owns cross-line assembly and rejects a mismatched response") {
    simulate(new ISideF3LineAssembler(p, lineBytes)) { dut =>
      clearF3(dut)
      val firstLine = BigInt(1) << (14 * 8)
      pokeHit(dut, pc = 14, transactionId = 9, lineData = firstLine)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.waitingForNextLine.expect(true.B)
      dut.io.nextLineRequest.valid.expect(true.B)
      dut.io.nextLineRequest.bits.lineVa.expect(16.U)
      dut.io.nextLineRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.nextLineRequest.ready.poke(false.B)

      dut.io.nextLineResponse.valid.poke(true.B)
      dut.io.nextLineResponse.bits.peId.poke(0.U)
      dut.io.nextLineResponse.bits.transactionId.poke(8.U)
      dut.io.nextLineResponse.bits.threadId.poke(0.U)
      dut.io.nextLineResponse.bits.fetchPacketUid.poke(0.U)
      dut.io.nextLineResponse.bits.fetchSeq.poke(9.U)
      dut.io.nextLineResponse.bits.checkpointId.poke(0.U)
      dut.io.nextLineResponse.bits.epoch.poke(0.U)
      dut.io.nextLineResponse.bits.lineVa.poke(16.U)
      dut.io.staleNextLineResponse.expect(true.B)
      dut.io.nextLineResponse.ready.expect(false.B)

      dut.io.nextLineResponse.bits.transactionId.poke(9.U)
      dut.io.nextLineResponse.bits.fetchSeq.poke(8.U)
      dut.io.staleNextLineResponse.expect(true.B)
      dut.io.nextLineResponse.ready.expect(false.B)

      dut.io.nextLineResponse.bits.fetchSeq.poke(9.U)
      dut.io.nextLineResponse.ready.expect(true.B)
      dut.clock.step()
      dut.io.nextLineResponse.valid.poke(false.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.entries(0).pc.expect(14.U)
      dut.io.out.bits.entries(0).lenBytes.expect(4.U)
      dut.io.out.bits.entries(0).insn.expect(1.U)
      dut.io.out.bits.entries(0).crossesLine.expect(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.prefixCarry.valid.expect(true.B)
      dut.io.prefixCarry.bits.successorTransactionId.expect(10.U)
      dut.io.prefixCarry.bits.successorIdentity.fetchSeq.expect(10.U)
      dut.io.prefixCarry.bits.successorLineVa.expect(16.U)
      dut.io.prefixCarry.bits.successorPc.expect(18.U)
    }
  }

  test("I-F4 recognizes only boundary metadata and truncates after BSTOP") {
    simulate(new ISideF4Predecode(p)) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.validMask.poke("b1111".U)
      dut.io.out.ready.poke(true.B)
      dut.io.boundary.ready.poke(true.B)
      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))

      val rows = Seq(0x80, 0x10, 0x0, 0x10)
      rows.zipWithIndex.foreach { case (raw, lane) =>
        dut.io.in.bits.entries(lane).pc.poke((0x1000 + lane * 2).U)
        dut.io.in.bits.entries(lane).insn.poke(raw.U)
        dut.io.in.bits.entries(lane).lenBytes.poke(2.U)
        dut.io.in.bits.entries(lane).identity.threadId.poke(0.U)
      }

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b0111".U)
      dut.io.out.bits.entries(0).isBlockStart.expect(true.B)
      dut.io.out.bits.entries(1).isBlockStart.expect(false.B)
      dut.io.out.bits.entries(2).isBlockStop.expect(true.B)
      dut.io.out.bits.entries(3).isBlockStop.expect(false.B)
      dut.io.out.bits.entries(0).insn.expect(0x80.U)
      dut.io.out.bits.transactionComplete.expect(true.B)
      dut.io.boundary.valid.expect(true.B)
      dut.io.boundary.bits.valid.expect(true.B)
      dut.io.boundary.bits.branchPc.expect(0x1004.U)
      dut.io.boundary.bits.fallthroughPc.expect(0x1006.U)
      dut.io.acceptedStop.expect(true.B)
      assert(dut.io.out.bits.entries(0).insn.getWidth == 64)
    }
  }

  test("I-F4 carries BSTART context across cachelines and backpressures terminal boundary completion") {
    simulate(new ISideF4Predecode(p)) { dut =>
      def present(raw: BigInt, pc: BigInt, transactionId: Int): Unit = {
        dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.validMask.poke("b0001".U)
        dut.io.in.bits.lineComplete.poke(true.B)
        dut.io.in.bits.entries(0).pc.poke(pc.U)
        dut.io.in.bits.entries(0).insn.poke(raw.U)
        dut.io.in.bits.entries(0).lenBytes.poke(2.U)
        dut.io.in.bits.entries(0).identity.peId.poke(1.U)
        dut.io.in.bits.entries(0).identity.threadId.poke(0.U)
        dut.io.in.bits.entries(0).identity.fetchPacketUid.poke(transactionId.U)
        dut.io.in.bits.entries(0).identity.fetchSeq.poke(transactionId.U)
        dut.io.in.bits.entries(0).identity.checkpointId.poke(transactionId.U)
        dut.io.in.bits.entries(0).identity.epoch.poke(0.U)
      }

      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
      dut.io.out.ready.poke(true.B)
      dut.io.boundary.ready.poke(true.B)
      present(raw = 0x80, pc = 0x1000, transactionId = 1)
      dut.io.boundary.valid.expect(true.B)
      dut.io.boundary.bits.valid.expect(false.B)
      dut.clock.step()

      present(raw = 0, pc = 0x2000, transactionId = 2)
      dut.io.boundary.ready.poke(false.B)
      dut.io.boundary.valid.expect(true.B)
      dut.io.boundary.bits.valid.expect(true.B)
      dut.io.boundary.bits.transactionId.expect(2.U)
      dut.io.boundary.bits.branchPc.expect(0x2000.U)
      dut.io.out.valid.expect(false.B)
      dut.io.in.ready.expect(false.B)

      dut.io.boundary.ready.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.acceptedStop.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
    }
  }
}
