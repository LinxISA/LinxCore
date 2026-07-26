package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ISideF0PcSelectSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()

  private def clear(dut: ISideF0PcSelect): Unit = {
    dut.io.start.valid.poke(false.B)
    dut.io.start.bits.poke(0.U.asTypeOf(dut.io.start.bits))
    dut.io.backendRestart.valid.poke(false.B)
    dut.io.backendRestart.bits.poke(0.U.asTypeOf(dut.io.backendRestart.bits))
    dut.io.predictionCorrection.poke(0.U.asTypeOf(dut.io.predictionCorrection))
    dut.io.predictionContext.valid.poke(false.B)
    dut.io.predictionContext.bits.poke(0.U.asTypeOf(dut.io.predictionContext.bits))
    dut.io.resolvedNextPc.valid.poke(false.B)
    dut.io.resolvedNextPc.bits.poke(0.U.asTypeOf(dut.io.resolvedNextPc.bits))
    dut.io.fetch.ready.poke(false.B)
    dut.io.predictionRequest.ready.poke(false.B)
  }

  test("I-F0 allocates sequential line requests while B-SIDE backpressure is absorbed by its queue") {
    simulate(new ISideF0PcSelect(p, threadCount = 1, lineBytes = 16, predictionQueueDepth = 2)) { dut =>
      clear(dut)
      dut.io.start.valid.poke(true.B)
      dut.io.start.bits.peId.poke(3.U)
      dut.io.start.bits.threadId.poke(0.U)
      dut.io.start.bits.pc.poke(0x123.U)
      dut.clock.step()
      dut.io.start.valid.poke(false.B)

      dut.io.fetch.ready.poke(true.B)
      dut.io.fetch.valid.expect(true.B)
      dut.io.fetch.bits.pc.expect(0x123.U)
      dut.io.fetch.bits.lineVa.expect(0x120.U)
      dut.clock.step()

      dut.io.fetch.bits.pc.expect(0x130.U)
      dut.io.predictionRequest.valid.expect(true.B)
      dut.io.predictionRequest.bits.pc.expect(0x123.U)
      dut.io.predictionRequest.bits.identity.fetchSeq.expect(0.U)

      dut.io.predictionRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.predictionRequest.bits.pc.expect(0x130.U)
    }
  }

  test("backend restart has priority over prediction correction and start") {
    simulate(new ISideF0PcSelect(p, threadCount = 1, lineBytes = 16)) { dut =>
      clear(dut)
      dut.io.start.valid.poke(true.B)
      dut.io.start.bits.threadId.poke(0.U)
      dut.io.start.bits.pc.poke(0x1000.U)
      dut.io.predictionCorrection.valid.poke(true.B)
      dut.io.predictionCorrection.threadId.poke(0.U)
      dut.io.predictionCorrection.restartPc.poke(0x2000.U)
      dut.io.predictionCorrection.newEpoch.poke(2.U)
      dut.io.backendRestart.valid.poke(true.B)
      dut.io.backendRestart.bits.threadId.poke(0.U)
      dut.io.backendRestart.bits.pc.poke(0x3000.U)
      dut.io.backendRestart.bits.newEpoch.poke(3.U)
      dut.io.backendRestart.bits.checkpointId.poke(7.U)
      dut.clock.step()

      dut.io.start.valid.poke(false.B)
      dut.io.predictionCorrection.valid.poke(false.B)
      dut.io.backendRestart.valid.poke(false.B)
      dut.io.currentPc(0).expect(0x3000.U)
      dut.io.epochs(0).expect(3.U)
      dut.io.fetch.bits.identity.checkpointId.expect(7.U)
    }
  }

  test("start/restart has priority over a colliding stale prediction correction") {
    simulate(new ISideF0PcSelect(p, threadCount = 1, lineBytes = 16)) { dut =>
      clear(dut)
      dut.io.start.valid.poke(true.B)
      dut.io.start.bits.threadId.poke(0.U)
      dut.io.start.bits.pc.poke(0x1000.U)
      dut.clock.step()

      dut.io.start.bits.pc.poke(0x6276.U)
      dut.io.predictionCorrection.valid.poke(true.B)
      dut.io.predictionCorrection.threadId.poke(0.U)
      dut.io.predictionCorrection.restartPc.poke(0x849c.U)
      dut.io.predictionCorrection.newEpoch.poke(7.U)
      dut.io.predictionContext.valid.poke(true.B)
      dut.io.predictionContext.bits.request.identity.threadId.poke(0.U)
      dut.io.predictionContext.bits.prediction.valid.poke(true.B)
      dut.io.predictionContext.bits.prediction.epoch.poke(7.U)
      dut.clock.step()

      dut.io.start.valid.poke(false.B)
      dut.io.predictionCorrection.valid.poke(false.B)
      dut.io.predictionContext.valid.poke(false.B)
      dut.io.currentPc(0).expect(0x6276.U)
      dut.io.epochs(0).expect(1.U)
      dut.io.fetch.ready.poke(true.B)
      dut.io.fetch.bits.prediction.valid.expect(false.B)
    }
  }

  test("I-F0 discards queued B-SIDE requests from an older epoch after restart") {
    simulate(new ISideF0PcSelect(p, threadCount = 1, lineBytes = 16)) { dut =>
      clear(dut)
      dut.io.start.valid.poke(true.B)
      dut.io.start.bits.threadId.poke(0.U)
      dut.io.start.bits.pc.poke(0x1000.U)
      dut.clock.step()
      dut.io.start.valid.poke(false.B)
      dut.io.fetch.ready.poke(true.B)
      dut.clock.step()

      dut.io.backendRestart.valid.poke(true.B)
      dut.io.backendRestart.bits.threadId.poke(0.U)
      dut.io.backendRestart.bits.pc.poke(0x8000.U)
      dut.io.backendRestart.bits.newEpoch.poke(4.U)
      dut.clock.step()
      dut.io.backendRestart.valid.poke(false.B)

      dut.io.predictionDroppedStale.expect(true.B)
      dut.io.predictionRequest.valid.expect(false.B)
      dut.clock.step()
      dut.io.predictionDroppedStale.expect(false.B)
    }
  }

  test("I-F0 carries the accepted BF4 record across prediction correction and clears it on backend restart") {
    simulate(new ISideF0PcSelect(p, threadCount = 1, lineBytes = 16)) { dut =>
      clear(dut)
      dut.io.start.valid.poke(true.B)
      dut.io.start.bits.threadId.poke(0.U)
      dut.io.start.bits.pc.poke(0x1000.U)
      dut.clock.step()
      dut.io.start.valid.poke(false.B)

      dut.io.predictionContext.valid.poke(true.B)
      dut.io.predictionContext.bits.request.identity.threadId.poke(0.U)
      dut.io.predictionContext.bits.prediction.valid.poke(true.B)
      dut.io.predictionContext.bits.prediction.requestPc.poke(0x1000.U)
      dut.io.predictionContext.bits.prediction.taken.poke(false.B)
      dut.io.predictionContext.bits.prediction.branchPc.poke(0x1010.U)
      dut.io.predictionContext.bits.prediction.target.poke(0x1800.U)
      dut.io.predictionContext.bits.prediction.fallthroughPc.poke(0x1020.U)
      dut.io.predictionContext.bits.prediction.kind.poke(linxcore.common.BoundaryKind.Cond)
      dut.io.predictionContext.bits.prediction.provider.poke(PredictionProvider.Static)
      dut.io.predictionContext.bits.prediction.stage.poke(BSideStage.BF4)
      dut.io.predictionContext.bits.prediction.epoch.poke(0.U)
      dut.clock.step()
      dut.io.predictionContext.valid.poke(false.B)

      dut.io.predictionCorrection.valid.poke(true.B)
      dut.io.predictionCorrection.threadId.poke(0.U)
      dut.io.predictionCorrection.restartPc.poke(0x1002.U)
      dut.io.predictionCorrection.newEpoch.poke(0.U)
      dut.io.predictionCorrection.reason.poke(IfuInnerFlushReason.PredictionCorrection)
      dut.clock.step()
      dut.io.predictionCorrection.valid.poke(false.B)

      dut.io.fetch.ready.poke(true.B)
      dut.io.fetch.valid.expect(true.B)
      dut.io.fetch.bits.pc.expect(0x1002.U)
      dut.io.fetch.bits.prediction.valid.expect(true.B)
      dut.io.fetch.bits.prediction.branchPc.expect(0x1010.U)
      dut.io.fetch.bits.prediction.target.expect(0x1800.U)
      dut.io.fetch.bits.prediction.stage.expect(BSideStage.BF4)

      dut.io.predictionContext.valid.poke(true.B)
      dut.io.predictionContext.bits.finalSteer.poke(true.B)
      dut.clock.step()
      dut.io.predictionContext.valid.poke(false.B)
      dut.io.fetch.bits.prediction.valid.expect(false.B)

      dut.io.predictionContext.valid.poke(true.B)
      dut.io.predictionContext.bits.finalSteer.poke(false.B)
      dut.clock.step()
      dut.io.predictionContext.valid.poke(false.B)
      dut.io.fetch.bits.prediction.valid.expect(true.B)

      dut.io.backendRestart.valid.poke(true.B)
      dut.io.backendRestart.bits.threadId.poke(0.U)
      dut.io.backendRestart.bits.pc.poke(0x2000.U)
      dut.io.backendRestart.bits.newEpoch.poke(1.U)
      dut.clock.step()
      dut.io.backendRestart.valid.poke(false.B)

      dut.io.fetch.bits.pc.expect(0x2000.U)
      dut.io.fetch.bits.prediction.valid.expect(false.B)
    }
  }
}
