package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class LinxCoreIfuSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()
  private val lineBytes = 16
  private val pageBytes = 256

  private def clear(dut: LinxCoreIfu): Unit = {
    dut.io.start.valid.poke(false.B)
    dut.io.start.bits.poke(0.U.asTypeOf(dut.io.start.bits))
    dut.io.backendRedirect.valid.poke(false.B)
    dut.io.backendRedirect.bits.poke(0.U.asTypeOf(dut.io.backendRedirect.bits))
    dut.io.branchResolve.valid.poke(false.B)
    dut.io.branchResolve.bits.poke(0.U.asTypeOf(dut.io.branchResolve.bits))
    dut.io.ptwRequest.ready.poke(true.B)
    dut.io.ptwRefill.valid.poke(false.B)
    dut.io.ptwRefill.bits.poke(0.U.asTypeOf(dut.io.ptwRefill.bits))
    dut.io.lineRead.ready.poke(true.B)
    dut.io.lineRefill.valid.poke(false.B)
    dut.io.lineRefill.bits.poke(0.U.asTypeOf(dut.io.lineRefill.bits))
    dut.io.fetchFault.ready.poke(true.B)
    dut.io.invalidateItlb.poke(false.B)
    dut.io.invalidateL1I.poke(false.B)
    dut.io.d1ThreadId.poke(0.U)
    dut.io.d1.ready.poke(false.B)
  }

  private def start(dut: LinxCoreIfu, pc: BigInt): Unit = {
    dut.io.start.valid.poke(true.B)
    dut.io.start.bits.peId.poke(1.U)
    dut.io.start.bits.threadId.poke(0.U)
    dut.io.start.bits.pc.poke(pc.U)
    dut.clock.step()
    dut.io.start.valid.poke(false.B)
  }

  private def waitUntil(limit: Int)(condition: => Boolean)(step: => Unit): Unit = {
    var cycles = 0
    while (!condition && cycles < limit) {
      step
      cycles += 1
    }
    assert(condition, s"condition did not become true within $limit cycles")
  }

  private def waitUntilClue(limit: Int, clue: String)(condition: => Boolean)(step: => Unit): Unit = {
    var cycles = 0
    while (!condition && cycles < limit) {
      step
      cycles += 1
    }
    assert(condition, s"$clue did not become true within $limit cycles")
  }

  private def refillLine(
      dut: LinxCoreIfu,
      transactionId: Int,
      linePa: BigInt,
      lineData: BigInt,
      epoch: Int = 0): Unit = {
    dut.io.lineRefill.valid.poke(true.B)
    dut.io.lineRefill.bits.transactionId.poke(transactionId.U)
    dut.io.lineRefill.bits.threadId.poke(0.U)
    dut.io.lineRefill.bits.epoch.poke(epoch.U)
    dut.io.lineRefill.bits.linePa.poke(linePa.U)
    dut.io.lineRefill.bits.lineData.poke(lineData.U)
    waitUntil(8)(dut.io.lineRefill.ready.peek().litToBoolean) {
      dut.clock.step()
    }
    dut.clock.step()
    dut.io.lineRefill.valid.poke(false.B)
  }

  test("production composition replays an L1I miss and delivers final B-F4 metadata to D1") {
    simulate(
      new LinxCoreIfu(
        p,
        threadCount = 1,
        lineBytes = lineBytes,
        pageBytes = pageBytes,
        itlbEntries = 4,
        l1iSets = 4,
        missEntries = 4,
        joinEntries = 4,
        maxGroupsPerTransaction = 4,
        instructionBufferDepth = 8)) { dut =>
      clear(dut)

      dut.io.ptwRefill.valid.poke(true.B)
      dut.io.ptwRefill.bits.vpn.poke(1.U)
      dut.io.ptwRefill.bits.ppn.poke(2.U)
      dut.io.ptwRefill.bits.executable.poke(true.B)
      dut.clock.step()
      dut.io.ptwRefill.valid.poke(false.B)

      start(dut, 0x120)

      waitUntil(20)(dut.io.lineRead.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.lineRead.bits.request.transactionId.expect(0.U)
      dut.io.lineRead.bits.linePa.expect(0x220.U)
      dut.clock.step()

      val lineData = BigInt("00800070006000500040003000200010", 16)
      refillLine(dut, transactionId = 0, linePa = 0x220, lineData = lineData)

      waitUntil(80)(dut.io.d1.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.d1.bits.validMask.expect("b1111".U)
      dut.io.d1.bits.entries(0).pc.expect(0x120.U)
      dut.io.d1.bits.entries(3).pc.expect(0x126.U)
      for (lane <- 0 until p.decodeWidth) {
        dut.io.d1.bits.entries(lane).prediction.stage.expect(BSideStage.BF4)
        dut.io.d1.bits.entries(lane).identity.epoch.expect(0.U)
      }

      dut.io.d1.ready.poke(true.B)
      dut.clock.step()
      dut.io.d1.ready.poke(false.B)
      waitUntil(12)(dut.io.d1.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.d1.bits.entries(0).pc.expect(0x128.U)
      dut.io.d1.bits.entries(3).pc.expect(0x12e.U)
    }
  }

  test("ITLB miss produces one canonical redirect epoch and exact PTW request") {
    simulate(
      new LinxCoreIfu(
        p,
        threadCount = 1,
        lineBytes = lineBytes,
        pageBytes = pageBytes,
        itlbEntries = 4,
        l1iSets = 4,
        missEntries = 4,
        joinEntries = 4,
        maxGroupsPerTransaction = 4,
        instructionBufferDepth = 8)) { dut =>
      clear(dut)
      start(dut, 0x500)

      waitUntil(20)(dut.io.ptwRequest.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.ptwRequest.bits.request.pc.expect(0x500.U)
      dut.io.ptwRequest.bits.request.transactionId.expect(0.U)
      dut.io.ptwRequest.bits.vpn.expect(5.U)
      dut.clock.step()

      waitUntil(8)(dut.io.canonicalFlush.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.canonicalFlush.bits.reason.expect(IfuInnerFlushReason.ItlbMiss)
      dut.io.canonicalFlush.bits.scope.expect(IfuPruneScope.KillTriggerAndYounger)
      dut.io.canonicalFlush.bits.restartPc.expect(0x500.U)
      dut.io.canonicalFlush.bits.newEpoch.expect(1.U)
      dut.io.epochs(0).expect(1.U)

      dut.clock.step()
      dut.io.ptwPending.expect(true.B)
      for (_ <- 0 until 8) {
        dut.io.ptwRequest.valid.expect(false.B)
        dut.clock.step()
      }
      dut.io.epochs(0).expect(1.U)

      dut.io.ptwRefill.valid.poke(true.B)
      dut.io.ptwRefill.bits.vpn.poke(5.U)
      dut.io.ptwRefill.bits.ppn.poke(6.U)
      dut.io.ptwRefill.bits.executable.poke(true.B)
      dut.clock.step()
      dut.io.ptwRefill.valid.poke(false.B)
      dut.io.ptwPending.expect(false.B)

      waitUntil(20)(dut.io.lineRead.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.lineRead.bits.linePa.expect(0x600.U)
      dut.io.lineRead.bits.request.identity.epoch.expect(1.U)
    }
  }

  test("a new start cancels a retained PTW owner before restarting another context") {
    simulate(
      new LinxCoreIfu(
        p,
        threadCount = 1,
        lineBytes = lineBytes,
        pageBytes = pageBytes,
        itlbEntries = 4,
        l1iSets = 4,
        missEntries = 4,
        joinEntries = 4,
        maxGroupsPerTransaction = 4,
        instructionBufferDepth = 8)) { dut =>
      clear(dut)
      start(dut, 0x500)

      waitUntil(20)(dut.io.ptwRequest.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step()
      waitUntil(8)(dut.io.ptwPending.peek().litToBoolean) {
        dut.clock.step()
      }
      waitUntil(8)(!dut.io.canonicalFlush.valid.peek().litToBoolean) {
        dut.clock.step()
      }

      start(dut, 0x700)
      dut.io.ptwPending.expect(false.B)
      dut.io.currentPc(0).expect(0x700.U)

      waitUntil(20)(dut.io.ptwRequest.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.ptwRequest.bits.request.pc.expect(0x700.U)
      dut.io.ptwRequest.bits.vpn.expect(7.U)
    }
  }

  test("back-to-back cross-line continuations use normal lookup and preserve consumed prefixes") {
    simulate(
      new LinxCoreIfu(
        p,
        threadCount = 1,
        lineBytes = lineBytes,
        pageBytes = pageBytes,
        itlbEntries = 4,
        l1iSets = 4,
        missEntries = 4,
        joinEntries = 1,
        maxGroupsPerTransaction = 4,
        instructionBufferDepth = 8)) { dut =>
      clear(dut)

      dut.io.ptwRefill.valid.poke(true.B)
      dut.io.ptwRefill.bits.vpn.poke(1.U)
      dut.io.ptwRefill.bits.ppn.poke(2.U)
      dut.io.ptwRefill.bits.executable.poke(true.B)
      dut.clock.step()
      dut.io.ptwRefill.valid.poke(false.B)

      start(dut, 0x12e)

      waitUntil(20)(
        dut.io.lineRead.valid.peek().litToBoolean &&
          dut.io.lineRead.bits.linePa.peek().litValue == 0x220) {
        dut.clock.step()
      }
      dut.io.lineRead.bits.request.transactionId.expect(0.U)
      dut.clock.step()
      refillLine(
        dut,
        transactionId = 0,
        linePa = 0x220,
        lineData = BigInt(1) << (14 * 8))

      waitUntil(40)(
        dut.io.lineRead.valid.peek().litToBoolean &&
          dut.io.lineRead.bits.linePa.peek().litValue == 0x230) {
        dut.clock.step()
      }
      dut.io.lineRead.bits.request.transactionId.expect(0.U)
      dut.io.crossLinePending.expect(true.B)
      dut.clock.step()
      refillLine(
        dut,
        transactionId = 0,
        linePa = 0x230,
        lineData = BigInt("00010010001000100010001000100010", 16))

      waitUntil(80)(dut.io.d1.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.d1.bits.validMask.expect("b0001".U)
      dut.io.d1.bits.entries(0).pc.expect(0x12e.U)
      dut.io.d1.bits.entries(0).lenBytes.expect(4.U)
      dut.io.d1.bits.entries(0).insn.expect(0x00100001.U)
      dut.io.d1.ready.poke(true.B)
      dut.clock.step()
      dut.io.d1.ready.poke(false.B)

      // The prediction join releases one transaction only after I-F4 marks
      // it complete.  The successor transaction therefore cannot reach D1
      // until its own crossing instruction has consumed line 0x240.
      waitUntilClue(80, "second continuation line read")(
        dut.io.lineRead.valid.peek().litToBoolean &&
          dut.io.lineRead.bits.linePa.peek().litValue == 0x240) {
        dut.clock.step()
      }
      val secondCrossTransactionId =
        dut.io.lineRead.bits.request.transactionId.peek().litValue
      dut.clock.step()
      refillLine(
        dut,
        transactionId = secondCrossTransactionId.toInt,
        linePa = 0x240,
        lineData = 0)

      var sawSecondCrossing = false
      var sawPostPrefixInstruction = false
      var sawSuccessorStart = false
      var groups = 0
      while (!sawPostPrefixInstruction && groups < 12) {
        waitUntilClue(80, "post-second-crossing D1 group")(dut.io.d1.valid.peek().litToBoolean) {
          dut.clock.step()
        }
        val validMask = dut.io.d1.bits.validMask.peek().litValue
        for (lane <- 0 until p.decodeWidth if ((validMask >> lane) & 1) == 1) {
          val pc = dut.io.d1.bits.entries(lane).pc.peek().litValue
          assert(pc != 0x130, "first consumed continuation prefix must not be decoded")
          assert(pc != 0x140, "second consumed continuation prefix must not be decoded")
          if (pc == 0x132) {
            sawSuccessorStart = true
          }
          if (pc == 0x13e) {
            dut.io.d1.bits.entries(lane).lenBytes.expect(4.U)
            sawSecondCrossing = true
          }
          if (pc >= 0x140) {
            assert(sawSecondCrossing, "crossing instruction must precede its next sequential PC")
            assert(pc == 0x142, f"next instruction after second crossing was 0x$pc%x")
            sawPostPrefixInstruction = true
          }
        }
        dut.io.d1.ready.poke(true.B)
        dut.clock.step()
        dut.io.d1.ready.poke(false.B)
        groups += 1
      }
      assert(sawSuccessorStart, "successor transaction did not resume at the carried PC")
      assert(sawSecondCrossing, "second crossing instruction was not delivered to D1")
      assert(sawPostPrefixInstruction, "second consumed prefix was not skipped")
    }
  }

  test("hot I-SIDE recycles line contexts across twenty consecutive four-wide groups") {
    simulate(
      new LinxCoreIfu(
        p,
        threadCount = 1,
        lineBytes = lineBytes,
        pageBytes = pageBytes,
        itlbEntries = 4,
        l1iSets = 16,
        missEntries = 8,
        joinEntries = 8,
        maxGroupsPerTransaction = 4,
        instructionBufferDepth = 32)) { dut =>
      clear(dut)

      dut.io.ptwRefill.valid.poke(true.B)
      dut.io.ptwRefill.bits.vpn.poke(1.U)
      dut.io.ptwRefill.bits.ppn.poke(2.U)
      dut.io.ptwRefill.bits.executable.poke(true.B)
      dut.clock.step()
      dut.io.ptwRefill.valid.poke(false.B)

      dut.io.lineRead.ready.poke(false.B)
      start(dut, 0x120)
      val lineData = BigInt("00100010001000100010001000100010", 16)
      for (line <- 0 until 10) {
        val expectedPa = 0x220 + line * lineBytes
        waitUntil(80)(
          dut.io.lineRead.valid.peek().litToBoolean &&
            dut.io.lineRead.bits.linePa.peek().litValue == expectedPa) {
          dut.clock.step()
        }
        val transactionId = dut.io.lineRead.bits.request.transactionId.peek().litValue.toInt
        dut.io.lineRead.ready.poke(true.B)
        dut.clock.step()
        dut.io.lineRead.ready.poke(false.B)
        refillLine(dut, transactionId, expectedPa, lineData)
      }

      // A new start removes all warm-up transactions but deliberately retains
      // physical ITLB/L1I contents.
      start(dut, 0x120)
      dut.io.d1.ready.poke(true.B)

      var lineContextPeak = BigInt(0)
      waitUntil(80)(dut.io.d1.valid.peek().litToBoolean) {
        lineContextPeak = lineContextPeak.max(dut.io.lineContextCount.peek().litValue)
        dut.clock.step()
      }

      for (group <- 0 until 20) {
        lineContextPeak = lineContextPeak.max(dut.io.lineContextCount.peek().litValue)
        dut.io.d1.valid.expect(true.B)
        dut.io.d1.bits.validMask.expect("b1111".U)
        for (lane <- 0 until p.decodeWidth) {
          val expectedPc = 0x120 + group * 8 + lane * 2
          dut.io.d1.bits.entries(lane).pc.expect(expectedPc.U)
          dut.io.d1.bits.entries(lane).lenBytes.expect(2.U)
          dut.io.d1.bits.entries(lane).prediction.stage.expect(BSideStage.BF4)
        }
        dut.clock.step()
      }
      assert(lineContextPeak >= 2, s"expected multiple live line contexts, peak=$lineContextPeak")
    }
  }

  test("backend redirect wins the canonical epoch and clears younger frontend state") {
    simulate(
      new LinxCoreIfu(
        p,
        threadCount = 1,
        lineBytes = lineBytes,
        pageBytes = pageBytes,
        itlbEntries = 4,
        l1iSets = 4,
        missEntries = 4,
        joinEntries = 4,
        maxGroupsPerTransaction = 4,
        instructionBufferDepth = 8)) { dut =>
      clear(dut)
      dut.io.lineRead.ready.poke(false.B)

      dut.io.ptwRefill.valid.poke(true.B)
      dut.io.ptwRefill.bits.vpn.poke(1.U)
      dut.io.ptwRefill.bits.ppn.poke(2.U)
      dut.io.ptwRefill.bits.executable.poke(true.B)
      dut.clock.step()
      dut.io.ptwRefill.valid.poke(false.B)
      start(dut, 0x120)

      waitUntil(12)(dut.io.joinCount.peek().litValue == 1) {
        dut.clock.step()
      }

      dut.io.backendRedirect.valid.poke(true.B)
      dut.io.backendRedirect.bits.valid.poke(true.B)
      dut.io.backendRedirect.bits.peId.poke(1.U)
      dut.io.backendRedirect.bits.threadId.poke(0.U)
      dut.io.backendRedirect.bits.transactionId.poke(0.U)
      dut.io.backendRedirect.bits.fetchSeq.poke(0.U)
      dut.io.backendRedirect.bits.oldEpoch.poke(0.U)
      dut.io.backendRedirect.bits.restartPc.poke(0x900.U)
      dut.io.backendRedirect.bits.checkpointId.poke(7.U)
      dut.io.backendRedirect.bits.reason.poke(IfuInnerFlushReason.FetchReplay)
      dut.io.backendRedirect.bits.scope.poke(IfuPruneScope.KillAllThreadState)
      dut.io.backendRedirect.ready.expect(true.B)
      dut.clock.step()
      dut.io.backendRedirect.valid.poke(false.B)

      waitUntil(8)(dut.io.canonicalFlush.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.canonicalFlush.bits.restartPc.expect(0x900.U)
      dut.io.canonicalFlush.bits.newEpoch.expect(1.U)
      dut.io.canonicalFlush.bits.scope.expect(IfuPruneScope.KillAllThreadState)
      dut.clock.step()
      dut.io.currentPc(0).expect(0x900.U)
      dut.io.epochs(0).expect(1.U)
      dut.io.joinCount.expect(0.U)
    }
  }

  test("simultaneous start cannot block an accepted backend redirect") {
    simulate(
      new LinxCoreIfu(
        p,
        threadCount = 1,
        lineBytes = lineBytes,
        pageBytes = pageBytes,
        itlbEntries = 4,
        l1iSets = 4,
        missEntries = 4,
        joinEntries = 4,
        maxGroupsPerTransaction = 4,
        instructionBufferDepth = 8)) { dut =>
      clear(dut)

      dut.io.start.valid.poke(true.B)
      dut.io.start.bits.peId.poke(1.U)
      dut.io.start.bits.threadId.poke(0.U)
      dut.io.start.bits.pc.poke(0x120.U)
      dut.io.backendRedirect.valid.poke(true.B)
      dut.io.backendRedirect.bits.valid.poke(true.B)
      dut.io.backendRedirect.bits.peId.poke(1.U)
      dut.io.backendRedirect.bits.threadId.poke(0.U)
      dut.io.backendRedirect.bits.restartPc.poke(0xa00.U)
      dut.io.backendRedirect.bits.reason.poke(IfuInnerFlushReason.FetchReplay)
      dut.io.backendRedirect.bits.scope.poke(IfuPruneScope.KillAllThreadState)

      dut.io.backendRedirect.ready.expect(true.B)
      dut.clock.step()
      dut.io.start.valid.poke(false.B)
      dut.io.backendRedirect.valid.poke(false.B)

      dut.io.canonicalFlush.valid.expect(true.B)
      dut.io.canonicalFlush.bits.restartPc.expect(0xa00.U)
      dut.io.canonicalFlush.bits.newEpoch.expect(1.U)
      dut.clock.step()
      dut.io.currentPc(0).expect(0xa00.U)
      dut.io.epochs(0).expect(1.U)
    }
  }
}
