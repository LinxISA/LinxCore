package linxcore.top

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{BoundaryKind, InterfaceParams}
import linxcore.frontend._
import org.scalatest.funsuite.AnyFunSuite

class LinxCoreProductionCompositionSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()
  private val lineBytes = 64
  private val pageBytes = 4096

  private case class CapturedPrediction(
      peId: BigInt,
      threadId: BigInt,
      checkpointId: BigInt,
      instructionPacketUid: BigInt,
      predictionTag: BigInt,
      transactionId: BigInt,
      fetchPacketUid: BigInt,
      fetchSeq: BigInt,
      requestPc: BigInt,
      taken: Boolean,
      branchPc: BigInt,
      target: BigInt,
      fallthroughPc: BigInt,
      kind: BigInt,
      provider: BigInt,
      stage: BigInt,
      confidence: BigInt,
      epoch: BigInt)

  private def clear(dut: LinxCoreProductionComposition): Unit = {
    dut.io.start.valid.poke(false.B)
    dut.io.start.bits.poke(0.U.asTypeOf(dut.io.start.bits))
    dut.io.ptwRequest.ready.poke(true.B)
    dut.io.ptwRefill.valid.poke(false.B)
    dut.io.ptwRefill.bits.poke(0.U.asTypeOf(dut.io.ptwRefill.bits))
    dut.io.memoryRequest.ready.poke(false.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
    dut.io.fetchFault.ready.poke(true.B)
    dut.io.invalidateItlb.poke(false.B)
    dut.io.invalidateL1I.poke(false.B)
    dut.io.d1ThreadId.poke(0.U)
    dut.io.decoded.ready.poke(false.B)
    dut.io.backendValidation.valid.poke(false.B)
    dut.io.backendValidation.bits.poke(0.U.asTypeOf(dut.io.backendValidation.bits))
  }

  private def waitUntil(limit: Int, clue: String)(condition: => Boolean)(step: => Unit): Unit = {
    var cycles = 0
    while (!condition && cycles < limit) {
      step
      cycles += 1
    }
    assert(condition, s"$clue did not become true within $limit cycles")
  }

  private def preloadAndStart(dut: LinxCoreProductionComposition): Unit = {
    dut.io.ptwRefill.valid.poke(true.B)
    dut.io.ptwRefill.bits.vpn.poke(1.U)
    dut.io.ptwRefill.bits.ppn.poke(2.U)
    dut.io.ptwRefill.bits.executable.poke(true.B)
    dut.clock.step()
    dut.io.ptwRefill.valid.poke(false.B)

    dut.io.start.valid.poke(true.B)
    dut.io.start.bits.peId.poke(1.U)
    dut.io.start.bits.threadId.poke(0.U)
    dut.io.start.bits.pc.poke(0x1200.U)
    dut.clock.step()
    dut.io.start.valid.poke(false.B)
  }

  private def denseLineData: BigInt =
    BigInt(List.fill(lineBytes / 2)("1048").mkString, 16)

  private def boundaryLineData: BigInt = {
    val words = Seq(BigInt(0x0002), BigInt(0x1048), BigInt(0x0000)) ++
      Seq.fill(lineBytes / 2 - 3)(BigInt(0x1048))
    words.zipWithIndex.map { case (word, index) => word << (index * 16) }.sum
  }

  private def mixedWidthBoundaryLineData: BigInt =
    BigInt(
      "01cc7f0500001f97000e02000f152086080090a5004100000002000000003507" +
        "00000391000181950000918710460800000002a53041002c0059f806080002a5",
      16)

  private def returnFirstLine(dut: LinxCoreProductionComposition, lineData: BigInt): Unit = {
    waitUntil(40, "tagged memory request")(dut.io.memoryRequest.valid.peek().litToBoolean) {
      dut.clock.step()
    }
    dut.io.memoryRequest.bits.linePa.expect(0x2200.U)
    val tag = dut.io.memoryRequest.bits.tag.peek().litValue
    dut.io.lineOutstandingCount.expect(1.U)

    dut.clock.step(2)
    dut.io.memoryRequest.bits.tag.expect(tag.U)
    dut.io.memoryRequest.bits.linePa.expect(0x2200.U)
    dut.io.memoryRequest.ready.poke(true.B)
    dut.clock.step()
    dut.io.memoryRequest.ready.poke(false.B)

    dut.io.memoryResponse.valid.poke(true.B)
    dut.io.memoryResponse.bits.tag.poke(tag.U)
    dut.io.memoryResponse.bits.linePa.poke(0x2200.U)
    dut.io.memoryResponse.bits.lineData.poke(lineData.U)
    dut.io.memoryResponse.ready.expect(true.B)
    dut.clock.step()
    dut.io.memoryResponse.valid.poke(false.B)
  }

  private def captureFirstPrediction(dut: LinxCoreProductionComposition): CapturedPrediction = {
    val lane = dut.io.decoded.bits.entries(0)
    val pred = lane.prediction
    CapturedPrediction(
      lane.peId.peek().litValue,
      lane.threadId.peek().litValue,
      lane.checkpointId.peek().litValue,
      lane.uid.fetchPacketUid.peek().litValue,
      pred.predictionTag.peek().litValue,
      pred.transactionId.peek().litValue,
      pred.fetchPacketUid.peek().litValue,
      pred.fetchSeq.peek().litValue,
      pred.requestPc.peek().litValue,
      pred.taken.peek().litToBoolean,
      pred.branchPc.peek().litValue,
      pred.target.peek().litValue,
      pred.fallthroughPc.peek().litValue,
      pred.kind.peek().litValue,
      pred.provider.peek().litValue,
      pred.stage.peek().litValue,
      pred.confidence.peek().litValue,
      pred.epoch.peek().litValue)
  }

  private def validateDirectMispredict(
      dut: LinxCoreProductionComposition,
      captured: CapturedPrediction,
      actualTarget: BigInt): Unit = {
    val event = dut.io.backendValidation.bits
    event.poke(0.U.asTypeOf(event))
    event.uop.valid.poke(true.B)
    event.uop.peId.poke(captured.peId.U)
    event.uop.threadId.poke(captured.threadId.U)
    event.uop.checkpointId.poke(captured.checkpointId.U)
    event.uop.uid.fetchPacketUid.poke(captured.instructionPacketUid.U)
    event.uop.prediction.valid.poke(true.B)
    event.uop.prediction.predictionTag.poke(captured.predictionTag.U)
    event.uop.prediction.transactionId.poke(captured.transactionId.U)
    event.uop.prediction.fetchPacketUid.poke(captured.fetchPacketUid.U)
    event.uop.prediction.fetchSeq.poke(captured.fetchSeq.U)
    event.uop.prediction.requestPc.poke(captured.requestPc.U)
    event.uop.prediction.taken.poke(captured.taken.B)
    event.uop.prediction.branchPc.poke(captured.branchPc.U)
    event.uop.prediction.target.poke(captured.target.U)
    event.uop.prediction.fallthroughPc.poke(captured.fallthroughPc.U)
    event.uop.prediction.kind.poke(BoundaryKind.Direct)
    event.uop.prediction.provider.poke(captured.provider.U)
    event.uop.prediction.stage.poke(captured.stage.U)
    event.uop.prediction.confidence.poke(captured.confidence.U)
    event.uop.prediction.checkpointId.poke(captured.checkpointId.U)
    event.uop.prediction.epoch.poke(captured.epoch.U)
    event.point.poke(BranchValidationPoint.Dispatch)
    event.setcKind.poke(SetcValidationKind.None)
    event.actualTaken.poke(true.B)
    event.actualBranchPc.poke(captured.branchPc.U)
    event.actualTarget.poke(actualTarget.U)
    event.actualFallthroughPc.poke(captured.fallthroughPc.U)
    event.actualKind.poke(BoundaryKind.Direct)
    dut.io.backendValidation.valid.poke(true.B)
    dut.io.backendValidation.ready.expect(true.B)
    dut.clock.step()
    dut.io.backendValidation.valid.poke(false.B)
  }

  test("composes tagged line transport, final B-F4 join, and four-wide production D1") {
    simulate(
      new LinxCoreProductionComposition(
        p,
        threadCount = 1,
        lineBytes = lineBytes,
        pageBytes = pageBytes,
        itlbEntries = 4,
        l1iSets = 4,
        missEntries = 4,
        joinEntries = 4,
        maxGroupsPerTransaction = 8,
        instructionBufferDepth = 16,
        lineBridgeEntries = 4)) { dut =>
      clear(dut)
      preloadAndStart(dut)
      returnFirstLine(dut, denseLineData)

      waitUntil(100, "decoded D1 group")(dut.io.decoded.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.decoded.bits.validMask.expect("b1111".U)
      dut.io.decoded.bits.invalidOpcodeMask.expect(0.U)
      for (lane <- 0 until p.decodeWidth) {
        val entry = dut.io.decoded.bits.entries(lane)
        entry.valid.expect(true.B)
        entry.pc.expect((0x1200 + lane * 2).U)
        entry.insnRaw.expect(0x1048.U)
        entry.insnLen.expect(2.U)
        entry.prediction.stage.expect(BSideStage.BF4.asUInt)
        entry.prediction.epoch.expect(0.U)
      }
      dut.io.lineResponsePendingMask.expect(0.U)
    }
  }

  test("routes exact backend validation into atomic training and canonical BRU recovery") {
    simulate(
      new LinxCoreProductionComposition(
        p,
        threadCount = 1,
        lineBytes = lineBytes,
        pageBytes = pageBytes,
        itlbEntries = 4,
        l1iSets = 4,
        missEntries = 4,
        joinEntries = 4,
        maxGroupsPerTransaction = 8,
        instructionBufferDepth = 16,
        lineBridgeEntries = 4)) { dut =>
      clear(dut)
      preloadAndStart(dut)
      returnFirstLine(dut, boundaryLineData)
      waitUntil(100, "decoded D1 group")(dut.io.decoded.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      val captured = captureFirstPrediction(dut)
      // The direct BSTART publishes an early B-F4 steer; its body and BSTOP
      // are refetched under the final prediction rather than leaking into the
      // marker's first D1 group.
      dut.io.decoded.bits.validMask.expect("b0001".U)
      assert(captured.kind == BoundaryKind.Direct.litValue)
      assert(captured.taken)
      dut.io.decoded.ready.poke(true.B)
      dut.clock.step()
      dut.io.decoded.ready.poke(false.B)

      val correctedTarget = captured.target + 0x40
      validateDirectMispredict(dut, captured, correctedTarget)
      waitUntil(20, "canonical BRU recovery")(dut.io.canonicalFlush.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.canonicalFlush.bits.reason.expect(IfuInnerFlushReason.BruRecovery)
      dut.io.canonicalFlush.bits.restartPc.expect(correctedTarget.U)
      dut.io.canonicalFlush.bits.predictionTag.expect(captured.predictionTag.U)
      dut.io.canonicalFlush.bits.fetchPacketUid.expect(captured.fetchPacketUid.U)
      dut.io.canonicalFlush.bits.fetchSeq.expect(captured.fetchSeq.U)
      dut.io.canonicalFlush.bits.newEpoch.expect(2.U)
      dut.clock.step()
      dut.io.currentPc(0).expect(correctedTarget.U)
      dut.io.epochs(0).expect(2.U)
      dut.io.feedbackPending.expect(false.B)
    }
  }


  test("cold ITLB replay and a mixed-width boundary line reach production D1") {
    simulate(
      new LinxCoreProductionComposition(
        p,
        threadCount = 1,
        lineBytes = lineBytes,
        pageBytes = pageBytes,
        itlbEntries = 4,
        l1iSets = 4,
        missEntries = 8,
        joinEntries = 8,
        maxGroupsPerTransaction = 8,
        instructionBufferDepth = 16,
        lineBridgeEntries = 8)) { dut =>
      clear(dut)

      dut.io.start.valid.poke(true.B)
      dut.io.start.bits.peId.poke(1.U)
      dut.io.start.bits.threadId.poke(0.U)
      dut.io.start.bits.pc.poke(0x1210.U)
      dut.clock.step()
      dut.io.start.valid.poke(false.B)

      waitUntil(40, "cold PTW request")(dut.io.ptwRequest.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.ptwRequest.bits.vpn.expect(1.U)
      dut.clock.step()
      dut.io.ptwRefill.valid.poke(true.B)
      dut.io.ptwRefill.bits.vpn.poke(1.U)
      dut.io.ptwRefill.bits.ppn.poke(1.U)
      dut.io.ptwRefill.bits.executable.poke(true.B)
      dut.clock.step()
      dut.io.ptwRefill.valid.poke(false.B)

      waitUntil(80, "mixed-width line request")(dut.io.memoryRequest.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.memoryRequest.bits.linePa.expect(0x1200.U)
      val tag = dut.io.memoryRequest.bits.tag.peek().litValue
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.tag.poke(tag.U)
      dut.io.memoryResponse.bits.linePa.poke(0x1200.U)
      dut.io.memoryResponse.bits.lineData.poke(mixedWidthBoundaryLineData.U)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      waitUntil(120, "mixed-width decoded group")(dut.io.decoded.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      // C.BSTART.STD.FALL is an execution-domain boundary marker, not a
      // control-flow prediction source. Preserve its same-group followers.
      dut.io.decoded.bits.validMask.expect("b1111".U)
      Seq(0x1210, 0x1212, 0x1214, 0x1218).zip(Seq(2, 2, 4, 4)).zipWithIndex.foreach {
        case ((pc, len), lane) =>
          dut.io.decoded.bits.entries(lane).pc.expect(pc.U)
          dut.io.decoded.bits.entries(lane).insnLen.expect(len.U)
          dut.io.decoded.bits.entries(lane).prediction.stage.expect(BSideStage.BF4.asUInt)
      }
      dut.io.decoded.ready.poke(true.B)
      dut.clock.step()
      waitUntil(120, "post-BSTART mixed-width decoded group")(
        dut.io.decoded.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      assert(dut.io.decoded.bits.validMask.peek().litValue != 0)
      dut.io.decoded.bits.entries(0).pc.expect(0x121c.U)
      dut.io.decoded.bits.entries(0).prediction.stage.expect(BSideStage.BF4.asUInt)
    }
  }
}
