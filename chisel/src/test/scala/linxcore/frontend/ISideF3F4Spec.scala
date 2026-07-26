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
      transactionId: BigInt,
      lineData: BigInt): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.status.poke(ISideF2Status.Hit)
    dut.io.in.bits.lineData.poke(lineData.U)
    dut.io.in.bits.request.pc.poke(pc.U)
    dut.io.in.bits.request.lineVa.poke((pc & ~BigInt(lineBytes - 1)).U)
    dut.io.in.bits.request.transactionId.poke(transactionId.U)
    dut.io.in.bits.request.identity.peId.poke(1.U)
    dut.io.in.bits.request.identity.threadId.poke(0.U)
    dut.io.in.bits.request.identity.fetchPacketUid.poke(transactionId.U)
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
      dut.io.out.bits.entries(0).instructionUid.expect(0.U)
      dut.io.out.bits.entries(3).instructionUid.expect(3.U)
      dut.io.out.bits.entries(0).transactionId.expect(1.U)
      dut.io.out.bits.entries(3).transactionId.expect(1.U)
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
      dut.io.out.bits.entries(0).instructionUid.expect(0.U)
      dut.io.out.bits.entries(3).instructionUid.expect(3.U)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(false.B)
      dut.clock.step()

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b1111".U)
      dut.io.out.bits.entries(0).pc.expect(8.U)
      dut.io.out.bits.entries(3).pc.expect(14.U)
      dut.io.out.bits.entries(0).instructionUid.expect(4.U)
      dut.io.out.bits.entries(3).instructionUid.expect(7.U)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  test("I-F3 allocates instruction UIDs independently of fetch-packet high bits") {
    simulate(new ISideF3LineAssembler(p, lineBytes)) { dut =>
      clearF3(dut)
      val line = BigInt("0040003000200010", 16)

      pokeHit(dut, pc = 0, transactionId = BigInt(1) << 58, lineData = line)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.bits.entries(0).instructionUid.expect(0.U)
      dut.io.out.bits.entries(3).instructionUid.expect(3.U)
      dut.io.out.ready.poke(true.B)
      dut.io.terminateResident.poke(true.B)
      dut.clock.step()

      dut.io.out.ready.poke(false.B)
      dut.io.terminateResident.poke(false.B)
      pokeHit(dut, pc = 16, transactionId = 0, lineData = line)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.bits.entries(0).instructionUid.expect(4.U)
      dut.io.out.bits.entries(3).instructionUid.expect(7.U)
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
      dut.io.nextLineResponse.bits.peId.poke(1.U)
      dut.io.nextLineResponse.bits.transactionId.poke(8.U)
      dut.io.nextLineResponse.bits.threadId.poke(0.U)
      dut.io.nextLineResponse.bits.fetchPacketUid.poke(9.U)
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

  test("I-F4 recognizes only boundary metadata and truncates at the first block marker") {
    simulate(new ISideF4Predecode(p)) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.validMask.poke("b1111".U)
      dut.io.out.ready.poke(true.B)
      dut.io.boundary.ready.poke(true.B)
      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))

      val rows = Seq(0x04, 0x10, 0x0, 0x10)
      rows.zipWithIndex.foreach { case (raw, lane) =>
        dut.io.in.bits.entries(lane).pc.poke((0x1000 + lane * 2).U)
        dut.io.in.bits.entries(lane).insn.poke(raw.U)
        dut.io.in.bits.entries(lane).lenBytes.poke(2.U)
        dut.io.in.bits.entries(lane).identity.threadId.poke(0.U)
      }

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b0001".U)
      dut.io.out.bits.entries(0).isBlockStart.expect(true.B)
      dut.io.out.bits.entries(1).isBlockStart.expect(false.B)
      dut.io.out.bits.entries(2).isBlockStop.expect(false.B)
      dut.io.out.bits.entries(3).isBlockStop.expect(false.B)
      dut.io.out.bits.entries(0).insn.expect(0x04.U)
      dut.io.out.bits.transactionComplete.expect(true.B)
      dut.io.boundary.valid.expect(true.B)
      dut.io.boundary.bits.valid.expect(true.B)
      dut.io.boundary.bits.branchPc.expect(0x1000.U)
      dut.io.boundary.bits.fallthroughPc.expect(0x1002.U)
      dut.io.boundary.bits.continuationReady.expect(false.B)
      dut.io.acceptedStart.expect(true.B)
      dut.io.acceptedStop.expect(false.B)
      assert(dut.io.out.bits.entries(0).insn.getWidth == 64)
    }
  }

  test("I-F4 preserves same-cacheline followers after a standalone BSTOP marker") {
    simulate(new ISideF4Predecode(p)) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.validMask.poke("b1111".U)
      dut.io.out.ready.poke(true.B)
      dut.io.boundary.ready.poke(true.B)
      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))

      val rows = Seq(0x0000, 0x0800, 0x4086, 0x0800)
      rows.zipWithIndex.foreach { case (raw, lane) =>
        dut.io.in.bits.entries(lane).pc.poke((0x5f20 + lane * 2).U)
        dut.io.in.bits.entries(lane).insn.poke(raw.U)
        dut.io.in.bits.entries(lane).lenBytes.poke(2.U)
        dut.io.in.bits.entries(lane).transactionId.poke(7.U)
        dut.io.in.bits.entries(lane).identity.peId.poke(1.U)
        dut.io.in.bits.entries(lane).identity.threadId.poke(0.U)
        dut.io.in.bits.entries(lane).identity.fetchPacketUid.poke(7.U)
        dut.io.in.bits.entries(lane).identity.fetchSeq.poke(7.U)
      }

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b1111".U)
      dut.io.out.bits.entries(0).isBlockStop.expect(true.B)
      dut.io.out.bits.entries(1).isBlockStart.expect(true.B)
      dut.io.out.bits.entries(2).insn.expect(0x4086.U)
      dut.io.out.bits.entries(3).isBlockStart.expect(true.B)
      dut.io.out.bits.transactionComplete.expect(false.B)
      dut.io.boundary.valid.expect(false.B)
      dut.io.acceptedStop.expect(true.B)
      dut.io.terminateResident.expect(false.B)
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
        dut.io.in.bits.entries(0).transactionId.poke(transactionId.U)
      }

      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
      dut.io.out.ready.poke(true.B)
      dut.io.boundary.ready.poke(true.B)
      present(raw = 0x04, pc = 0x1000, transactionId = 1)
      dut.io.boundary.valid.expect(true.B)
      dut.io.boundary.bits.valid.expect(true.B)
      dut.io.boundary.bits.transactionId.expect(1.U)
      dut.io.boundary.bits.branchPc.expect(0x1000.U)
      dut.io.boundary.bits.continuationReady.expect(false.B)
      dut.clock.step()

      // A cacheline-complete body transaction repeats the active control
      // prediction so every D1 lane, including a later SETC, retains the
      // enclosing BSTART identity.
      present(raw = 0x0010, pc = 0x1800, transactionId = 2)
      dut.io.boundary.valid.expect(true.B)
      dut.io.boundary.bits.valid.expect(true.B)
      dut.io.boundary.bits.transactionId.expect(2.U)
      dut.io.boundary.bits.branchPc.expect(0x1000.U)
      dut.io.boundary.bits.target.expect(0x1000.U)
      dut.io.boundary.bits.fallthroughPc.expect(0x1802.U)
      dut.io.boundary.bits.continuationReady.expect(false.B)
      dut.clock.step()

      present(raw = 0, pc = 0x2000, transactionId = 3)
      dut.io.boundary.ready.poke(false.B)
      dut.io.boundary.valid.expect(true.B)
      dut.io.boundary.bits.valid.expect(true.B)
      dut.io.boundary.bits.transactionId.expect(3.U)
      dut.io.boundary.bits.branchPc.expect(0x2000.U)
      dut.io.boundary.bits.continuationReady.expect(true.B)
      dut.io.out.valid.expect(false.B)
      dut.io.in.ready.expect(false.B)

      dut.io.boundary.ready.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.acceptedStop.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
    }
  }

  test("I-F4 closes the active block before consuming the following BSTART") {
    simulate(new ISideF4Predecode(p)) { dut =>
      def present(rows: Seq[(BigInt, BigInt)], transactionId: Int): Unit = {
        dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.validMask.poke(((BigInt(1) << rows.size) - 1).U)
        rows.zipWithIndex.foreach { case ((pc, insn), lane) =>
          val entry = dut.io.in.bits.entries(lane)
          entry.pc.poke(pc.U)
          entry.insn.poke(insn.U)
          entry.lenBytes.poke(2.U)
          entry.transactionId.poke(transactionId.U)
          entry.identity.peId.poke(1.U)
          entry.identity.threadId.poke(0.U)
          entry.identity.fetchPacketUid.poke(transactionId.U)
          entry.identity.fetchSeq.poke(transactionId.U)
          entry.identity.checkpointId.poke(transactionId.U)
          entry.identity.epoch.poke(0.U)
        }
      }

      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
      dut.io.out.ready.poke(true.B)
      dut.io.boundary.ready.poke(true.B)

      // C.BSTART COND with a zero displacement targets its own marker PC.
      present(Seq(BigInt(0x1000) -> BigInt(0x0004)), transactionId = 1)
      dut.io.boundary.bits.target.expect(0x1000.U)
      dut.io.boundary.bits.continuationReady.expect(false.B)
      dut.clock.step()

      // The next BSTART is a terminator for transaction 2, not an instruction
      // or prediction source belonging to the preceding block body.
      present(
        Seq(
          BigInt(0x1002) -> BigInt(0x0010),
          BigInt(0x1004) -> BigInt(0x0020),
          BigInt(0x1006) -> BigInt(0x0194)),
        transactionId = 2)
      dut.io.out.bits.validMask.expect("b0011".U)
      dut.io.boundary.valid.expect(true.B)
      dut.io.boundary.bits.valid.expect(true.B)
      dut.io.boundary.bits.transactionId.expect(2.U)
      dut.io.boundary.bits.branchPc.expect(0x1000.U)
      dut.io.boundary.bits.target.expect(0x1000.U)
      dut.io.boundary.bits.fallthroughPc.expect(0x1006.U)
      dut.io.boundary.bits.kind.expect(BoundaryKind.Cond)
      dut.io.boundary.bits.continuationReady.expect(true.B)
      dut.io.acceptedStart.expect(false.B)
      dut.io.terminateResident.expect(true.B)
    }
  }

  test("I-F4 forwards execution-domain wrappers without replacing B-SIDE control state") {
    simulate(new ISideF4Predecode(p)) { dut =>
      def present(raw: BigInt, pc: BigInt, transactionId: Int): Unit = {
        dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.validMask.poke("b0001".U)
        dut.io.in.bits.entries(0).pc.poke(pc.U)
        dut.io.in.bits.entries(0).insn.poke(raw.U)
        dut.io.in.bits.entries(0).lenBytes.poke(2.U)
        dut.io.in.bits.entries(0).transactionId.poke(transactionId.U)
        dut.io.in.bits.entries(0).identity.peId.poke(1.U)
        dut.io.in.bits.entries(0).identity.threadId.poke(0.U)
        dut.io.in.bits.entries(0).identity.fetchPacketUid.poke(transactionId.U)
        dut.io.in.bits.entries(0).identity.fetchSeq.poke(transactionId.U)
      }

      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
      dut.io.out.ready.poke(true.B)
      dut.io.boundary.ready.poke(true.B)

      // C.BSTART.STD FALL is a backend wrapper marker, not a control-flow
      // prediction source or an I-F3 resident terminator.
      present(raw = 0x0800, pc = 0x1000, transactionId = 1)
      dut.io.out.bits.entries(0).isBlockStart.expect(true.B)
      dut.io.boundary.valid.expect(false.B)
      dut.io.out.bits.transactionComplete.expect(false.B)
      dut.io.acceptedStart.expect(false.B)
      dut.io.terminateResident.expect(false.B)
      dut.clock.step()

      // A nested C.BSTART COND owns B-SIDE state and remains active until its
      // later BSTOP even though the wrapper was already forwarded to D1.
      present(raw = 0x0004, pc = 0x1002, transactionId = 2)
      dut.io.boundary.bits.valid.expect(true.B)
      dut.io.boundary.bits.branchPc.expect(0x1002.U)
      dut.io.boundary.bits.kind.expect(BoundaryKind.Cond)
      dut.io.boundary.bits.continuationReady.expect(false.B)
      dut.clock.step()

      present(raw = 0x0000, pc = 0x1010, transactionId = 3)
      dut.io.boundary.bits.valid.expect(true.B)
      dut.io.boundary.bits.branchPc.expect(0x1010.U)
      dut.io.boundary.bits.target.expect(0x1002.U)
      dut.io.boundary.bits.continuationReady.expect(true.B)
    }
  }

  test("I-F4 uses every BSTART to close an older control block") {
    simulate(new ISideF4Predecode(p)) { dut =>
      def present(raw: BigInt, pc: BigInt, transactionId: Int): Unit = {
        dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.validMask.poke("b0001".U)
        dut.io.in.bits.entries(0).pc.poke(pc.U)
        dut.io.in.bits.entries(0).insn.poke(raw.U)
        dut.io.in.bits.entries(0).lenBytes.poke(2.U)
        dut.io.in.bits.entries(0).transactionId.poke(transactionId.U)
        dut.io.in.bits.entries(0).identity.peId.poke(1.U)
        dut.io.in.bits.entries(0).identity.threadId.poke(0.U)
        dut.io.in.bits.entries(0).identity.fetchPacketUid.poke(transactionId.U)
        dut.io.in.bits.entries(0).identity.fetchSeq.poke(transactionId.U)
      }

      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
      dut.io.out.ready.poke(true.B)
      dut.io.boundary.ready.poke(true.B)

      // Open a conditional control block whose final decision is carried until
      // the architectural start of the next block is observed.
      present(raw = 0x0004, pc = 0x1000, transactionId = 1)
      dut.io.boundary.bits.continuationReady.expect(false.B)
      dut.clock.step()

      // C.BSTART.STD FALL opens no new predictor state, but it is still the
      // next block boundary.  The old control block ends immediately before
      // this marker, so the marker must be refetched after the final steer.
      present(raw = 0x0800, pc = 0x1010, transactionId = 2)
      dut.io.out.bits.validMask.expect(0.U)
      dut.io.out.bits.transactionComplete.expect(true.B)
      dut.io.boundary.valid.expect(true.B)
      dut.io.boundary.bits.valid.expect(true.B)
      dut.io.boundary.bits.branchPc.expect(0x1000.U)
      dut.io.boundary.bits.fallthroughPc.expect(0x1010.U)
      dut.io.boundary.bits.continuationReady.expect(true.B)
      dut.io.acceptedStart.expect(false.B)
      dut.io.terminateResident.expect(true.B)
      dut.clock.step()

      dut.io.in.valid.poke(false.B)
      dut.io.boundary.valid.expect(false.B)
    }
  }

  test("I-F4 carries the CALL SETRET return cutpoint into final boundary metadata") {
    simulate(new ISideF4Predecode(p)) { dut =>
      def present(
          raw: BigInt,
          pc: BigInt,
          lenBytes: Int,
          transactionId: Int,
          lineComplete: Boolean = false): Unit = {
        dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.validMask.poke("b0001".U)
        dut.io.in.bits.lineComplete.poke(lineComplete.B)
        dut.io.in.bits.entries(0).pc.poke(pc.U)
        dut.io.in.bits.entries(0).insn.poke(raw.U)
        dut.io.in.bits.entries(0).lenBytes.poke(lenBytes.U)
        dut.io.in.bits.entries(0).transactionId.poke(transactionId.U)
        dut.io.in.bits.entries(0).identity.peId.poke(1.U)
        dut.io.in.bits.entries(0).identity.threadId.poke(0.U)
        dut.io.in.bits.entries(0).identity.fetchPacketUid.poke(transactionId.U)
        dut.io.in.bits.entries(0).identity.fetchSeq.poke(transactionId.U)
      }

      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
      dut.io.out.ready.poke(true.B)
      dut.io.boundary.ready.poke(true.B)

      // Standard BSTART CALL at 0x1000.  C.SETRET is a distinct instruction
      // at 0x1004 and encodes a +0x1c return displacement to 0x1020.
      present(raw = 0x4001, pc = 0x1000, lenBytes = 4, transactionId = 1)
      dut.io.boundary.bits.kind.expect(BoundaryKind.Call)
      dut.clock.step()

      val compressedSetret = BigInt(0x5016) | (BigInt(14) << 6)
      present(
        raw = compressedSetret,
        pc = 0x1004,
        lenBytes = 2,
        transactionId = 2,
        lineComplete = true)
      dut.io.out.bits.entries(0).isBlockStart.expect(false.B)
      dut.io.boundary.bits.branchPc.expect(0x1000.U)
      dut.io.boundary.bits.fallthroughPc.expect(0x1020.U)
      dut.io.boundary.bits.continuationReady.expect(false.B)
      dut.clock.step()

      // Reaching SETRET's return address closes the CALL before a sequential
      // FRET/FENTRY tail can escape to D1.  No synthetic BSTART is invented.
      present(raw = 0x3041, pc = 0x1020, lenBytes = 4, transactionId = 3)
      dut.io.out.bits.validMask.expect(0.U)
      dut.io.boundary.bits.branchPc.expect(0x1000.U)
      dut.io.boundary.bits.fallthroughPc.expect(0x1020.U)
      dut.io.boundary.bits.continuationReady.expect(true.B)
    }
  }

  test("I-F4 reconstructs a CALL return cutpoint from an inherited final sidecar") {
    simulate(new ISideF4Predecode(p)) { dut =>
      def present(raw: BigInt, pc: BigInt, transactionId: Int, inherited: Boolean): Unit = {
        dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.validMask.poke("b0001".U)
        dut.io.in.bits.entries(0).pc.poke(pc.U)
        dut.io.in.bits.entries(0).insn.poke(raw.U)
        dut.io.in.bits.entries(0).lenBytes.poke(2.U)
        dut.io.in.bits.entries(0).transactionId.poke(transactionId.U)
        dut.io.in.bits.entries(0).identity.peId.poke(1.U)
        dut.io.in.bits.entries(0).identity.threadId.poke(0.U)
        dut.io.in.bits.entries(0).identity.fetchPacketUid.poke(transactionId.U)
        dut.io.in.bits.entries(0).identity.fetchSeq.poke(transactionId.U)
        if (inherited) {
          dut.io.in.bits.entries(0).prediction.valid.poke(true.B)
          dut.io.in.bits.entries(0).prediction.kind.poke(BoundaryKind.Call)
          dut.io.in.bits.entries(0).prediction.branchPc.poke(0x1000.U)
          dut.io.in.bits.entries(0).prediction.target.poke(0x2000.U)
          dut.io.in.bits.entries(0).prediction.fallthroughPc.poke(0x1004.U)
        }
      }

      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
      dut.io.out.ready.poke(true.B)
      dut.io.boundary.ready.poke(true.B)

      val compressedSetret = BigInt(0x5016) | (BigInt(14) << 6)
      present(compressedSetret, pc = 0x1004, transactionId = 11, inherited = true)
      dut.clock.step()

      present(0x3041, pc = 0x1020, transactionId = 12, inherited = false)
      dut.io.out.bits.validMask.expect(0.U)
      dut.io.boundary.bits.valid.expect(true.B)
      dut.io.boundary.bits.kind.expect(BoundaryKind.Call)
      dut.io.boundary.bits.branchPc.expect(0x1000.U)
      dut.io.boundary.bits.target.expect(0x2000.U)
      dut.io.boundary.bits.fallthroughPc.expect(0x1020.U)
      dut.io.boundary.bits.continuationReady.expect(true.B)
    }
  }

  test("I-F4 keeps wrapper followers and discovers a nested control BSTART") {
    simulate(new ISideF4Predecode(p)) { dut =>
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.validMask.poke("b1111".U)
      dut.io.out.ready.poke(true.B)
      dut.io.boundary.ready.poke(true.B)
      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))

      val rows = Seq(
        (BigInt(0x1000), BigInt(0x0800)),
        (BigInt(0x1002), BigInt(0x0010)),
        (BigInt(0x1004), BigInt(0x0020)),
        (BigInt(0x1006), BigInt(0x0004)))
      rows.zipWithIndex.foreach { case ((pc, insn), lane) =>
        val entry = dut.io.in.bits.entries(lane)
        entry.pc.poke(pc.U)
        entry.insn.poke(insn.U)
        entry.lenBytes.poke(2.U)
        entry.transactionId.poke(7.U)
        entry.identity.peId.poke(1.U)
        entry.identity.threadId.poke(0.U)
        entry.identity.fetchPacketUid.poke(7.U)
        entry.identity.fetchSeq.poke(7.U)
      }

      dut.io.out.bits.validMask.expect("b1111".U)
      dut.io.out.bits.entries(0).isBlockStart.expect(true.B)
      dut.io.out.bits.entries(3).isBlockStart.expect(true.B)
      dut.io.out.bits.transactionComplete.expect(true.B)
      dut.io.boundary.valid.expect(true.B)
      dut.io.boundary.bits.valid.expect(true.B)
      dut.io.boundary.bits.branchPc.expect(0x1006.U)
      dut.io.acceptedStart.expect(true.B)
    }
  }

  test("I-F4 preserves independent transaction packet and sequence identities") {
    simulate(new ISideF4Predecode(p)) { dut =>
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.validMask.poke("b0011".U)
      dut.io.in.bits.lineComplete.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.boundary.ready.poke(true.B)
      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))

      for (lane <- 0 until 2) {
        dut.io.in.bits.entries(lane).pc.poke((0x3000 + lane * 2).U)
        dut.io.in.bits.entries(lane).insn.poke((if (lane == 0) 0x04 else 0).U)
        dut.io.in.bits.entries(lane).lenBytes.poke(2.U)
        dut.io.in.bits.entries(lane).transactionId.poke(0x11.U)
        dut.io.in.bits.entries(lane).identity.peId.poke(1.U)
        dut.io.in.bits.entries(lane).identity.threadId.poke(0.U)
        dut.io.in.bits.entries(lane).identity.fetchPacketUid.poke(0x22.U)
        dut.io.in.bits.entries(lane).identity.fetchSeq.poke(0x33.U)
        dut.io.in.bits.entries(lane).identity.epoch.poke(4.U)
      }

      dut.io.out.valid.expect(true.B)
      dut.io.boundary.valid.expect(true.B)
      dut.io.boundary.bits.transactionId.expect(0x11.U)
      dut.io.boundary.bits.fetchPacketUid.expect(0x22.U)
      dut.io.boundary.bits.fetchSeq.expect(0x33.U)

      dut.io.flush.valid.poke(true.B)
      dut.io.flush.threadId.poke(0.U)
      dut.io.flush.transactionId.poke(0x11.U)
      dut.io.flush.fetchSeq.poke(0x33.U)
      dut.io.flush.oldEpoch.poke(4.U)
      dut.io.flush.scope.poke(IfuPruneScope.KillTriggerAndYounger)
      dut.io.out.valid.expect(false.B)
      dut.io.boundary.valid.expect(false.B)
      dut.io.in.ready.expect(true.B)
    }
  }
}
