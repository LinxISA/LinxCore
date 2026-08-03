package linxcore.iex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.ooo.{OooOpcodeRecipeRule, OooOpcodeRecipeTable}
import linxcore.top.interface.FrontEndOpKind
import org.scalatest.funsuite.AnyFunSuite

class OOOIEXLSUActivationSpec extends AnyFunSuite with ChiselSim {
  private def initialize(dut: OOOIEXLSUActivationProbe): Unit = {
    dut.io.program.valid.poke(false.B)
    dut.io.program.bits.poke(0.U.asTypeOf(dut.io.program.bits))
    dut.io.commitReady.poke(true.B)
    dut.io.trapReady.poke(true.B)
    dut.io.cmdReady.poke(true.B)
    dut.io.systemReady.poke(true.B)
    dut.io.oooTraceReady.poke(true.B)
    dut.io.iexTraceReady.poke(true.B)
    dut.io.recoveryReady.poke(true.B)
    dut.io.lsuTraceReady.poke(true.B)
    dut.io.memoryReady.foreach(_.poke(true.B))
    dut.io.memoryResponseValid.poke(false.B)
    dut.io.memoryResponseId.poke(0.U)
    dut.io.memoryResponseGeneration.poke(0.U)
    dut.io.memoryResponseAddress.poke(0.U)
    dut.io.memoryResponseData.poke(0.U)
    dut.io.loadReissueRequest.valid.poke(false.B)
    dut.io.loadReissueRequest.bits.poke(
      0.U.asTypeOf(dut.io.loadReissueRequest.bits))
    dut.io.loadResultInject.valid.poke(false.B)
    dut.io.loadResultInject.bits.poke(
      0.U.asTypeOf(dut.io.loadResultInject.bits))
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)

    while (!dut.io.bootstrapComplete.peek().litToBoolean) dut.clock.step()
    dut.io.bootstrapInitCount.expect(48.U)
  }

  test("joint W4 mainline graph accepts a canonical encoded program row") {
    simulate(new OOOIEXLSUActivationProbe(OOOIEXLSUActivationParams.W4)) { dut =>
      initialize(dut)

      val addi = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_ADDI").get
      val entry = dut.io.program.bits.entries.head
      dut.io.program.bits.count.poke(1.U)
      entry.kind.poke(FrontEndOpKind.Encoded64)
      entry.parent.identity.peId.poke(1.U)
      entry.parent.identity.stid.poke(0.U)
      entry.parent.identity.instructionId.poke(1.U)
      entry.parent.identity.epoch.poke(1.U)
      entry.parent.pc.poke(0x1000.U)
      entry.parent.instruction.poke(
        (addi.value | (BigInt(5) << 7) | (BigInt(7) << 20)).U)
      entry.parent.lengthBytes.poke(addi.lenBytes.U)
      entry.parent.prediction.valid.poke(true.B)
      entry.parent.prediction.requestPc.poke(0x1000.U)
      entry.parent.prediction.fallthroughPc.poke(0x1004.U)
      entry.parent.prediction.epoch.poke(1.U)
      dut.io.program.valid.poke(true.B)
      while (!dut.io.program.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.io.program.valid.poke(false.B)
      dut.io.ingressCount.expect(1.U)

      var activated = false
      for (_ <- 0 until 40) {
        activated ||= dut.io.aluCount.peek().litValue > 0 &&
          dut.io.rfWriteCount.peek().litValue > 0
        dut.clock.step()
      }
      assert(activated,
        s"the accepted canonical row must reach public ALU dispatch: " +
          s"alu=${dut.io.aluCount.peek().litValue} " +
          s"resolve=${dut.io.resolveCount.peek().litValue} " +
          s"commit=${dut.io.commitCount.peek().litValue} " +
          s"stall=${dut.io.dispatchStallCount.peek().litValue} " +
          s"trace=${dut.io.traceCount.peek().litValue}")
      dut.io.rfWriteCount.expect(1.U)
      dut.io.lastRfWriteValue.expect(7.U)
      dut.io.lastRfWritePtag.expect(48.U)
      dut.io.lastRfWriteGeneration.expect(0.U)
    }
  }

  test("public closed SDI block emits matching store address and data") {
    simulate(new OOOIEXLSUActivationProbe(OOOIEXLSUActivationParams.W4)) { dut =>
      initialize(dut)

      val start = OooOpcodeRecipeTable.Rules.find(
        _.symbol == "OP_BSTART_FALL").get
      val sdi = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_SDI").get
      val stop = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_BSTOP").get
      val encodings = Seq(
        (start, start.value, 0x2000),
        (sdi, sdi.value | (BigInt(0x88) << 20), 0x2004),
        (stop, stop.value, 0x2008))
      dut.io.program.bits.count.poke(encodings.length.U)
      encodings.zipWithIndex.foreach { case ((rule, raw, pc), lane) =>
        val entry = dut.io.program.bits.entries(lane)
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
      dut.io.program.valid.poke(true.B)
      while (!dut.io.program.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.io.program.valid.poke(false.B)

      var cycles = 0
      while ((dut.io.storeAddressCount.peek().litValue == 0 ||
          dut.io.storeDataCount.peek().litValue == 0) && cycles < 96) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.storeAddressCount.peek().litValue == 1,
        s"SDI must emit one public store address; " +
          s"sta=${dut.io.storeAddressCount.peek().litValue} " +
          s"std=${dut.io.storeDataCount.peek().litValue}")
      assert(dut.io.storeDataCount.peek().litValue == 1,
        s"SDI must emit one public store data payload; " +
          s"sta=${dut.io.storeAddressCount.peek().litValue} " +
          s"std=${dut.io.storeDataCount.peek().litValue}")
    }
  }

  test("loadreplay preserves transaction and rejects the stale attempt") {
    simulate(new OOOIEXLSUActivationProbe(OOOIEXLSUActivationParams.W4)) { dut =>
      initialize(dut)

      val start = OooOpcodeRecipeTable.Rules.find(
        _.symbol == "OP_BSTART_FALL").get
      val load = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_LDI").get
      val stop = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_BSTOP").get
      val encodings = Seq(
        (start, start.value, 0x2800),
        (load, load.value | (BigInt(0x180) << 20) | (BigInt(5) << 7),
          0x2804),
        (stop, stop.value, 0x2808))
      dut.io.program.bits.count.poke(encodings.length.U)
      encodings.zipWithIndex.foreach { case ((rule, raw, pc), lane) =>
        val entry = dut.io.program.bits.entries(lane)
        entry.kind.poke(FrontEndOpKind.Encoded64)
        entry.parent.identity.peId.poke(1.U)
        entry.parent.identity.stid.poke(0.U)
        entry.parent.identity.instructionId.poke((70 + lane).U)
        entry.parent.identity.epoch.poke(1.U)
        entry.parent.pc.poke(pc.U)
        entry.parent.instruction.poke(raw.U)
        entry.parent.lengthBytes.poke(rule.lenBytes.U)
        entry.parent.prediction.valid.poke(true.B)
        entry.parent.prediction.requestPc.poke(pc.U)
        entry.parent.prediction.fallthroughPc.poke((pc + rule.lenBytes).U)
        entry.parent.prediction.epoch.poke(1.U)
      }
      dut.io.program.valid.poke(true.B)
      while (!dut.io.program.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.io.program.valid.poke(false.B)

      var cycles = 0
      while ((dut.io.loadCount.peek().litValue != 1 ||
          dut.io.loadLaunchCount.peek().litValue != 1 ||
          dut.io.loadAttemptLaunchCount.peek().litValue != 1 ||
          dut.io.memoryCount.peek().litValue != 1) && cycles < 192) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 192, "initial load must allocate, launch, and miss once")
      val initialAttempt =
        dut.io.lastLoadIssueIdentity.attemptGeneration.peek().litValue
      val transactionValue =
        dut.io.lastLoadIssueIdentity.transaction.value.peek().litValue
      val transactionGeneration =
        dut.io.lastLoadIssueIdentity.transaction.generation.peek().litValue
      val initial = dut.io.lastLoadIssueIdentity.peek()
      val allocation = dut.io.lastLoadAllocationId.peek()

      val reissue = dut.io.loadReissueRequest
      reissue.bits.poke(0.U.asTypeOf(reissue.bits))
      reissue.bits.allocationId.poke(allocation)
      reissue.bits.currentIdentity.poke(initial)
      reissue.bits.nextIdentity.poke(initial)
      reissue.bits.nextIdentity.attemptGeneration.poke((initialAttempt + 1).U)
      reissue.bits.address.poke(dut.io.lastLoadAddress.peek())
      reissue.valid.poke(true.B)
      cycles = 0
      while (!reissue.ready.peek().litToBoolean && cycles < 64) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 64, "exact current-to-next attempt rebind must be accepted")
      dut.clock.step()
      reissue.valid.poke(false.B)
      dut.io.loadAttemptCancelCount.expect(1.U)
      dut.io.lastLoadRebindCurrent.expect(initial)
      dut.io.lastLoadRebindNext.transaction.value.expect(transactionValue.U)
      dut.io.lastLoadRebindNext.transaction.generation
        .expect(transactionGeneration.U)
      dut.io.lastLoadRebindNext.attemptGeneration.expect((initialAttempt + 1).U)

      cycles = 0
      while ((dut.io.loadLaunchCount.peek().litValue != 2 ||
          dut.io.loadAttemptLaunchCount.peek().litValue != 2 ||
          dut.io.memoryCount.peek().litValue != 2) && cycles < 128) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 128,
        "accepted rebind must return the LIQ row to one new-attempt launch")
      dut.io.lastLoadLaunchIdentity.transaction.value.expect(transactionValue.U)
      dut.io.lastLoadLaunchIdentity.transaction.generation
        .expect(transactionGeneration.U)
      dut.io.lastLoadLaunchIdentity.attemptGeneration
        .expect((initialAttempt + 1).U)
      dut.io.firstLoadAttemptLaunchIdentity.transaction.value
        .expect(transactionValue.U)
      dut.io.firstLoadAttemptLaunchIdentity.transaction.generation
        .expect(transactionGeneration.U)
      dut.io.firstLoadAttemptLaunchIdentity.attemptGeneration
        .expect(initialAttempt.U)
      dut.io.lastLoadAttemptLaunchIdentity.transaction.value
        .expect(transactionValue.U)
      dut.io.lastLoadAttemptLaunchIdentity.transaction.generation
        .expect(transactionGeneration.U)
      dut.io.lastLoadAttemptLaunchIdentity.attemptGeneration
        .expect((initialAttempt + 1).U)

      val stale = dut.io.loadResultInject
      stale.bits.poke(0.U.asTypeOf(stale.bits))
      stale.bits.identity.poke(initial)
      stale.bits.allocationId.poke(allocation)
      stale.bits.data.poke("hdeadbeef".U)
      stale.bits.destination.poke(dut.io.lastLoadDestination.peek())
      stale.bits.destinationRelativeIndex.poke(
        dut.io.lastLoadDestinationRelativeIndex.peek())
      val resolveBefore = dut.io.resolveCount.peek().litValue
      val writeBefore = dut.io.rfWriteCount.peek().litValue
      val commitBefore = dut.io.commitCount.peek().litValue
      stale.valid.poke(true.B)
      cycles = 0
      while (!stale.ready.peek().litToBoolean && cycles < 64) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 64, "stale old-attempt result must be accepted and dropped")
      dut.clock.step()
      stale.valid.poke(false.B)
      dut.clock.step(8)
      dut.io.resolveCount.expect(resolveBefore.U)
      dut.io.rfWriteCount.expect(writeBefore.U)
      dut.io.commitCount.expect(commitBefore.U)
      dut.io.loadLaunchCount.expect(2.U)
      dut.io.loadAttemptLaunchCount.expect(2.U)
      dut.io.memoryCount.expect(2.U)
      dut.io.loadAttemptCancelCount.expect(1.U)

      dut.io.memoryResponseId.poke(dut.io.lastMemoryId.peek())
      dut.io.memoryResponseGeneration.poke(
        dut.io.lastMemoryGeneration.peek())
      dut.io.memoryResponseAddress.poke(dut.io.lastMemoryAddress.peek())
      dut.io.memoryResponseData.poke("h1122334455667788".U)
      dut.io.memoryResponseValid.poke(true.B)
      cycles = 0
      while (!dut.io.memoryResponseReady.peek().litToBoolean && cycles < 64) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 64, "new-attempt lower-memory response must be accepted")
      dut.clock.step()
      dut.io.memoryResponseValid.poke(false.B)

      cycles = 0
      while ((dut.io.resolveCount.peek().litValue != resolveBefore + 1 ||
          dut.io.rfWriteCount.peek().litValue != writeBefore + 1 ||
          dut.io.commitCount.peek().litValue != commitBefore + 1) &&
          cycles < 192) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 192,
        "new attempt must complete with exactly one resolve, writeback, and commit")
      dut.io.lastLoadResultIdentity.transaction.value.expect(transactionValue.U)
      dut.io.lastLoadResultIdentity.transaction.generation
        .expect(transactionGeneration.U)
      dut.io.lastLoadResultIdentity.attemptGeneration
        .expect((initialAttempt + 1).U)
      dut.io.lastResolveValue.expect("h1122334455667788".U)
      dut.io.lastRfWriteValue.expect("h1122334455667788".U)
      dut.clock.step(16)
      dut.io.loadAttemptCancelCount.expect(1.U)
      dut.io.loadAttemptLaunchCount.expect(2.U)
      dut.io.loadLaunchCount.expect(3.U)
      dut.io.memoryCount.expect(2.U)
      dut.io.lastLoadLaunchIdentity.transaction.value.expect(transactionValue.U)
      dut.io.lastLoadLaunchIdentity.transaction.generation
        .expect(transactionGeneration.U)
      dut.io.lastLoadLaunchIdentity.attemptGeneration
        .expect((initialAttempt + 1).U)
      dut.io.resolveCount.expect((resolveBefore + 1).U)
      dut.io.rfWriteCount.expect((writeBefore + 1).U)
      dut.io.commitCount.expect((commitBefore + 1).U)
    }
  }

  test("public direct J publishes one exact branch recovery target") {
    simulate(new OOOIEXLSUActivationProbe(OOOIEXLSUActivationParams.W4)) { dut =>
      initialize(dut)

      val start = OooOpcodeRecipeTable.Rules.find(
        _.symbol == "OP_BSTART_FALL").get
      val jump = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_J").get
      val stop = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_BSTOP").get
      val encodings = Seq(
        (start, start.value, 0x3000),
        // J encodes simm22=2 as [11:7]@[31:15], hence a four-byte target.
        (jump, jump.value | (BigInt(2) << 15), 0x3004),
        (stop, stop.value, 0x3008))
      dut.io.program.bits.count.poke(encodings.length.U)
      encodings.zipWithIndex.foreach { case ((rule, raw, pc), lane) =>
        val entry = dut.io.program.bits.entries(lane)
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
      dut.io.program.valid.poke(true.B)
      while (!dut.io.program.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.io.program.valid.poke(false.B)

      var cycles = 0
      while (dut.io.branchCount.peek().litValue == 0 && cycles < 128) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.bruCount.expect(1.U)
      dut.io.branchCount.expect(1.U)
      dut.io.lastBranchTarget.expect(0x3008.U)
    }
  }

  test("public peer closed block expands through its exact BROB last row") {
    simulate(new OOOIEXLSUActivationProbe(OOOIEXLSUActivationParams.W4)) { dut =>
      initialize(dut)
      val start = OooOpcodeRecipeTable.Rules.find(
        _.symbol == "OP_BSTART_FALL").get
      val stop = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_BSTOP").get
      val addi = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_ADDI").get
      val encodings = Seq(
        (start, start.value, 0x5000),
        (addi, addi.value | (BigInt(5) << 7) | (BigInt(7) << 20), 0x5004),
        (addi, addi.value | (BigInt(6) << 7) | (BigInt(7) << 20), 0x5008),
        (stop, stop.value, 0x500c))
      dut.io.program.bits.poke(0.U.asTypeOf(dut.io.program.bits))
      dut.io.program.bits.count.poke(encodings.size.U)
      encodings.zipWithIndex.foreach { case ((rule, raw, pc), lane) =>
        val entry = dut.io.program.bits.entries(lane)
        entry.kind.poke(FrontEndOpKind.Encoded64)
        entry.parent.identity.peId.poke(1.U)
        entry.parent.identity.stid.poke(1.U)
        entry.parent.identity.instructionId.poke((16 + lane).U)
        entry.parent.identity.epoch.poke(1.U)
        entry.parent.pc.poke(pc.U)
        entry.parent.instruction.poke(raw.U)
        entry.parent.lengthBytes.poke(rule.lenBytes.U)
        entry.parent.prediction.valid.poke(true.B)
        entry.parent.prediction.requestPc.poke(pc.U)
        entry.parent.prediction.fallthroughPc.poke((pc + rule.lenBytes).U)
        entry.parent.prediction.epoch.poke(1.U)
      }
      dut.io.program.valid.poke(true.B)
      while (!dut.io.program.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.io.program.valid.poke(false.B)

      var cycles = 0
      while ((dut.io.stid1Progress.peek().litValue < 2 ||
          dut.io.stid1CommitProgress.peek().litValue == 0) && cycles < 256) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.stid1Progress.peek().litValue == 2 &&
        dut.io.stid1CommitProgress.peek().litValue > 0,
        s"peer block must resolve both body rows and commit its exact close; " +
          s"resolve=${dut.io.stid1Progress.peek().litValue} " +
          s"commit=${dut.io.stid1CommitProgress.peek().litValue}")
      val commitProgress = dut.io.stid1CommitProgress.peek().litValue
      dut.clock.step(16)
      dut.io.stid1Progress.expect(2.U)
      dut.io.stid1CommitProgress.expect(commitProgress.U)
    }
  }

  test("public System and CMD terminals publish exactly once under CMD backpressure") {
    simulate(new OOOIEXLSUActivationProbe(OOOIEXLSUActivationParams.W4)) { dut =>
      initialize(dut)
      val start = OooOpcodeRecipeTable.Rules.find(
        _.symbol == "OP_BSTART_FALL").get
      val stop = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_BSTOP").get
      val system = OooOpcodeRecipeTable.Rules.find(
        _.symbol == "OP_TLB_IALL").get
      val cmd = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_B_HINT").get
      val encodings = Seq(
        (start, start.value, 0x6000),
        (system, system.value, 0x6004),
        (cmd, cmd.value, 0x6008),
        (stop, stop.value, 0x600c))
      dut.io.program.bits.poke(0.U.asTypeOf(dut.io.program.bits))
      dut.io.program.bits.count.poke(encodings.size.U)
      encodings.zipWithIndex.foreach { case ((rule, raw, pc), lane) =>
        val entry = dut.io.program.bits.entries(lane)
        entry.kind.poke(FrontEndOpKind.Encoded64)
        entry.parent.identity.peId.poke(1.U)
        entry.parent.identity.stid.poke(0.U)
        entry.parent.identity.instructionId.poke((24 + lane).U)
        entry.parent.identity.epoch.poke(1.U)
        entry.parent.pc.poke(pc.U)
        entry.parent.instruction.poke(raw.U)
        entry.parent.lengthBytes.poke(rule.lenBytes.U)
        entry.parent.prediction.valid.poke(true.B)
        entry.parent.prediction.requestPc.poke(pc.U)
        entry.parent.prediction.fallthroughPc.poke((pc + rule.lenBytes).U)
        entry.parent.prediction.epoch.poke(1.U)
      }
      dut.io.cmdReady.poke(false.B)
      dut.io.systemReady.poke(false.B)
      val traceBefore = dut.io.iexTerminalTraceCount.peek().litValue
      dut.io.program.valid.poke(true.B)
      while (!dut.io.program.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.io.program.valid.poke(false.B)

      var cycles = 0
      while ((dut.io.systemCount.peek().litValue == 0 ||
          dut.io.cmdCount.peek().litValue == 0) && cycles < 128) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.systemCount.expect(1.U)
      dut.io.cmdCount.expect(1.U)
      dut.io.systemIssueCount.expect(0.U)
      dut.io.cmdIssueCount.expect(0.U)
      dut.io.iexTerminalTraceCount.expect(traceBefore.U)
      dut.clock.step(8)
      dut.io.systemIssueCount.expect(0.U)
      dut.io.cmdIssueCount.expect(0.U)

      dut.io.systemReady.poke(true.B)
      cycles = 0
      while (dut.io.systemIssueCount.peek().litValue == 0 && cycles < 128) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.systemIssueCount.expect(1.U)
      dut.io.cmdIssueCount.expect(0.U)
      dut.clock.step(8)
      dut.io.systemIssueCount.expect(1.U)
      dut.io.cmdIssueCount.expect(0.U)

      dut.io.cmdReady.poke(true.B)
      cycles = 0
      while ((dut.io.cmdIssueCount.peek().litValue == 0 ||
          dut.io.stid0CommitProgress.peek().litValue == 0) && cycles < 256) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.systemIssueCount.expect(1.U)
      assert(dut.io.cmdIssueCount.peek().litValue == 1 &&
        dut.io.iexTerminalTraceCount.peek().litValue == traceBefore + 2 &&
        dut.io.stid0CommitProgress.peek().litValue > 0,
        s"System/CMD block must commit exactly once; " +
          s"systemIssue=${dut.io.systemIssueCount.peek().litValue} " +
          s"cmdIssue=${dut.io.cmdIssueCount.peek().litValue} " +
          s"resolve=${dut.io.resolveCount.peek().litValue} " +
          s"commit=${dut.io.stid0CommitProgress.peek().litValue}")
      val commitProgress = dut.io.stid0CommitProgress.peek().litValue
      dut.clock.step(16)
      dut.io.systemIssueCount.expect(1.U)
      dut.io.cmdIssueCount.expect(1.U)
      dut.io.stid0CommitProgress.expect(commitProgress.U)
    }
  }

  test("stalled younger CMD is killed by an older open-current BRU recovery") {
    simulate(new OOOIEXLSUActivationProbe(OOOIEXLSUActivationParams.W4)) { dut =>
      initialize(dut)
      val start = OooOpcodeRecipeTable.Rules.find(
        _.symbol == "OP_BSTART_FALL").get
      val stop = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_BSTOP").get
      val jump = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_J").get
      val cmd = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_B_HINT").get
      val encodings = Seq(
        (start, start.value, 0x7000),
        (jump, jump.value | (BigInt(2) << 15), 0x7004),
        (cmd, cmd.value, 0x7008),
        (stop, stop.value, 0x700c))
      dut.io.program.bits.poke(0.U.asTypeOf(dut.io.program.bits))
      dut.io.program.bits.count.poke(encodings.size.U)
      encodings.zipWithIndex.foreach { case ((rule, raw, pc), lane) =>
        val entry = dut.io.program.bits.entries(lane)
        entry.kind.poke(FrontEndOpKind.Encoded64)
        entry.parent.identity.peId.poke(1.U)
        entry.parent.identity.stid.poke(0.U)
        entry.parent.identity.instructionId.poke((40 + lane).U)
        entry.parent.identity.epoch.poke(1.U)
        entry.parent.pc.poke(pc.U)
        entry.parent.instruction.poke(raw.U)
        entry.parent.lengthBytes.poke(rule.lenBytes.U)
        entry.parent.prediction.valid.poke(true.B)
        entry.parent.prediction.requestPc.poke(pc.U)
        entry.parent.prediction.fallthroughPc.poke((pc + rule.lenBytes).U)
        entry.parent.prediction.epoch.poke(1.U)
      }
      dut.io.cmdReady.poke(false.B)
      dut.io.recoveryReady.poke(false.B)
      val traceBefore = dut.io.iexTerminalTraceCount.peek().litValue
      dut.io.program.valid.poke(true.B)
      while (!dut.io.program.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.io.program.valid.poke(false.B)

      var cycles = 0
      while ((dut.io.bruCount.peek().litValue == 0 ||
          dut.io.cmdCount.peek().litValue == 0) && cycles < 128) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.bruCount.expect(1.U)
      dut.io.cmdCount.expect(1.U)
      dut.io.branchCount.expect(0.U)
      dut.io.cmdIssueCount.expect(0.U)
      dut.clock.step(8)
      dut.io.branchCount.expect(0.U)
      dut.io.cmdIssueCount.expect(0.U)

      dut.io.recoveryReady.poke(true.B)
      cycles = 0
      while ((dut.io.branchCount.peek().litValue == 0 ||
          dut.io.recoveryApplyCount.peek().litValue == 0) && cycles < 256) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.branchCount.expect(1.U)
      dut.io.recoveryApplyCount.expect(1.U)
      dut.io.cmdIssueCount.expect(0.U)
      dut.io.iexTerminalTraceCount.expect((traceBefore + 1).U)

      dut.clock.step(8)

      // Recovery killed the original closing marker and therefore reopens the
      // surviving branch's PC/BROB owners. Publish the redirected epoch's
      // closing marker before requiring those owners to drain.
      dut.io.program.bits.poke(0.U.asTypeOf(dut.io.program.bits))
      dut.io.program.bits.count.poke(1.U)
      val redirectedStop = dut.io.program.bits.entries.head
      redirectedStop.kind.poke(FrontEndOpKind.Encoded64)
      redirectedStop.parent.identity.peId.poke(1.U)
      redirectedStop.parent.identity.stid.poke(0.U)
      redirectedStop.parent.identity.instructionId.poke(60.U)
      redirectedStop.parent.identity.epoch.poke(2.U)
      redirectedStop.parent.pc.poke(0x7008.U)
      redirectedStop.parent.instruction.poke(stop.value.U)
      redirectedStop.parent.lengthBytes.poke(stop.lenBytes.U)
      redirectedStop.parent.prediction.valid.poke(true.B)
      redirectedStop.parent.prediction.requestPc.poke(0x7008.U)
      redirectedStop.parent.prediction.fallthroughPc.poke(0x700c.U)
      redirectedStop.parent.prediction.epoch.poke(2.U)
      dut.io.program.valid.poke(true.B)
      while (!dut.io.program.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.io.program.valid.poke(false.B)

      cycles = 0
      while (dut.io.stid0CommitProgress.peek().litValue == 0 && cycles < 256) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.cmdReady.poke(true.B)
      dut.clock.step(8)
      dut.io.cmdIssueCount.expect(0.U)
      assert(dut.io.stid0CommitProgress.peek().litValue > 0,
        s"older open-current BRU recovery must kill the stalled younger CMD " +
          s"and drain after redirected closure; " +
          s"commit=${dut.io.stid0CommitProgress.peek().litValue}")
      val commitProgress = dut.io.stid0CommitProgress.peek().litValue
      dut.clock.step(16)
      dut.io.stid0CommitProgress.expect(commitProgress.U)
      dut.io.cmdIssueCount.expect(0.U)
    }
  }

  test("backlogged branch recovery fences its commit and permits peer STID progress") {
    simulate(new OOOIEXLSUActivationProbe(OOOIEXLSUActivationParams.W4)) { dut =>
      initialize(dut)

      val start = OooOpcodeRecipeTable.Rules.find(
        _.symbol == "OP_BSTART_FALL").get
      val stop = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_BSTOP").get
      val jump = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_J").get
      val addi = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_ADDI").get

      def sendClosed(stid: Int, bodyRaw: BigInt, bodyRule:
          OooOpcodeRecipeRule, basePc: Int,
          firstInstructionId: Int): Unit = {
        dut.io.program.bits.poke(0.U.asTypeOf(dut.io.program.bits))
        val encodings = Seq(
          (start, start.value, basePc),
          (bodyRule, bodyRaw, basePc + 4),
          (stop, stop.value, basePc + 8))
        dut.io.program.bits.count.poke(3.U)
        encodings.zipWithIndex.foreach { case ((rule, raw, pc), lane) =>
          val entry = dut.io.program.bits.entries(lane)
          entry.kind.poke(FrontEndOpKind.Encoded64)
          entry.parent.identity.peId.poke(1.U)
          entry.parent.identity.stid.poke(stid.U)
          entry.parent.identity.instructionId.poke(
            (firstInstructionId + lane).U)
          entry.parent.identity.epoch.poke(1.U)
          entry.parent.pc.poke(pc.U)
          entry.parent.instruction.poke(raw.U)
          entry.parent.lengthBytes.poke(rule.lenBytes.U)
          entry.parent.prediction.valid.poke(true.B)
          entry.parent.prediction.requestPc.poke(pc.U)
          entry.parent.prediction.fallthroughPc.poke((pc + rule.lenBytes).U)
          entry.parent.prediction.epoch.poke(1.U)
        }
        dut.io.program.valid.poke(true.B)
        while (!dut.io.program.ready.peek().litToBoolean) dut.clock.step()
        dut.clock.step()
        dut.io.program.valid.poke(false.B)
      }

      def sendPeerPair(): Unit = {
        dut.io.program.bits.poke(0.U.asTypeOf(dut.io.program.bits))
        val addRaw = addi.value | (BigInt(5) << 7) | (BigInt(7) << 20)
        val encodings = Seq(
          (start, start.value, 0x5000),
          (addi, addRaw, 0x5004),
          (addi, addi.value | (BigInt(6) << 7) |
            (BigInt(7) << 20), 0x5008),
          (stop, stop.value, 0x500c))
        dut.io.program.bits.count.poke(4.U)
        encodings.zipWithIndex.foreach { case ((rule, raw, pc), lane) =>
          val entry = dut.io.program.bits.entries(lane)
          entry.kind.poke(FrontEndOpKind.Encoded64)
          entry.parent.identity.peId.poke(1.U)
          entry.parent.identity.stid.poke(1.U)
          entry.parent.identity.instructionId.poke((16 + lane).U)
          entry.parent.identity.epoch.poke(1.U)
          entry.parent.pc.poke(pc.U)
          entry.parent.instruction.poke(raw.U)
          entry.parent.lengthBytes.poke(rule.lenBytes.U)
          entry.parent.prediction.valid.poke(true.B)
          entry.parent.prediction.requestPc.poke(pc.U)
          entry.parent.prediction.fallthroughPc.poke((pc + rule.lenBytes).U)
          entry.parent.prediction.epoch.poke(1.U)
        }
        dut.io.program.valid.poke(true.B)
        while (!dut.io.program.ready.peek().litToBoolean) dut.clock.step()
        dut.clock.step()
        dut.io.program.valid.poke(false.B)
      }

      def sendRedirectStop(): Unit = {
        dut.io.program.bits.poke(0.U.asTypeOf(dut.io.program.bits))
        dut.io.program.bits.count.poke(1.U)
        val entry = dut.io.program.bits.entries.head
        entry.kind.poke(FrontEndOpKind.Encoded64)
        entry.parent.identity.peId.poke(1.U)
        entry.parent.identity.stid.poke(0.U)
        entry.parent.identity.instructionId.poke(32.U)
        entry.parent.identity.epoch.poke(2.U)
        entry.parent.pc.poke(0x4008.U)
        entry.parent.instruction.poke(stop.value.U)
        entry.parent.lengthBytes.poke(stop.lenBytes.U)
        entry.parent.prediction.valid.poke(true.B)
        entry.parent.prediction.requestPc.poke(0x4008.U)
        entry.parent.prediction.fallthroughPc.poke(0x400c.U)
        entry.parent.prediction.epoch.poke(2.U)
        dut.io.program.valid.poke(true.B)
        while (!dut.io.program.ready.peek().litToBoolean) dut.clock.step()
        dut.clock.step()
        dut.io.program.valid.poke(false.B)
      }

      dut.io.recoveryReady.poke(false.B)
      sendClosed(0, jump.value | (BigInt(2) << 15), jump,
        basePc = 0x4000, firstInstructionId = 1)
      var cycles = 0
      while (dut.io.bruCount.peek().litValue == 0 && cycles < 128) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.bruCount.expect(1.U)
      dut.clock.step(16)
      dut.io.branchCount.expect(0.U)
      dut.io.stid0Progress.expect(0.U)
      dut.io.stid0CommitProgress.expect(0.U)

      sendPeerPair()
      cycles = 0
      while (dut.io.stid1Progress.peek().litValue == 0 && cycles < 128) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.stid1Progress.peek().litValue > 0,
        s"the peer STID must resolve while branch recovery is backpressured; " +
          s"alu=${dut.io.aluCount.peek().litValue} " +
          s"rf=${dut.io.rfWriteCount.peek().litValue} " +
          s"resolve=${dut.io.resolveCount.peek().litValue} " +
          s"trace=${dut.io.iexTerminalTraceCount.peek().litValue}")
      dut.io.stid0Progress.expect(0.U)
      dut.io.stid0CommitProgress.expect(0.U)
      dut.clock.step(8)
      val traceCountBeforeRecovery =
        dut.io.iexTerminalTraceCount.peek().litValue

      dut.io.recoveryReady.poke(true.B)
      cycles = 0
      while ((dut.io.branchCount.peek().litValue == 0 ||
          dut.io.recoveryApplyCount.peek().litValue == 0 ||
          dut.io.stid0Progress.peek().litValue == 0) && cycles < 256) {
        dut.clock.step()
        cycles += 1
      }
      dut.clock.step(8)
      dut.io.branchCount.expect(1.U)
      dut.io.recoveryApplyCount.expect(1.U)
      dut.io.stid0Progress.expect(1.U)
      dut.io.iexTerminalTraceCount.expect((traceCountBeforeRecovery + 2).U)
      dut.clock.step(16)
      if (dut.io.stid0CommitProgress.peek().litValue == 0) {
        sendRedirectStop()
      }
      cycles = 0
      while (dut.io.stid0CommitProgress.peek().litValue == 0 && cycles < 256) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.stid0CommitProgress.peek().litValue > 0,
        s"surviving branch block must close and release after redirected " +
          s"target lifecycle; commit=${dut.io.stid0CommitProgress.peek().litValue}")
      val commitProgress = dut.io.stid0CommitProgress.peek().litValue
      dut.clock.step(16)
      dut.io.branchCount.expect(1.U)
      dut.io.recoveryApplyCount.expect(1.U)
      dut.io.stid0CommitProgress.expect(commitProgress.U)
    }
  }
}
