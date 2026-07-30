package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class STQLoadForwardResultPipelineSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: STQLoadForwardResultPipeline): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.response.valid.poke(false.B)
    dut.io.response.bits.poke(0.U.asTypeOf(dut.io.response.bits))
    dut.io.normalReady.poke(true.B)
    dut.io.returnReady.poke(true.B)
    dut.io.hardBlock.ready.poke(true.B)
  }

  private def pokeBaseResponse(
      dut: STQLoadForwardResultPipeline,
      token: Int,
      loadMask: BigInt,
      baseMask: BigInt,
      forwardMask: BigInt,
      waitMask: BigInt,
      data: BigInt): Unit = {
    dut.io.response.bits.poke(0.U.asTypeOf(dut.io.response.bits))
    dut.io.response.bits.query.token.poke(token.U)
    dut.io.response.bits.query.loadId.valid.poke(true.B)
    dut.io.response.bits.query.loadId.slot.poke((token & 3).U)
    dut.io.response.bits.query.loadId.generation.poke((token & 1).U)
    dut.io.response.bits.query.attempt.valid.poke(true.B)
    dut.io.response.bits.query.attempt.producer.valid.poke(true.B)
    dut.io.response.bits.query.attempt.producer.nativeBidValid.poke(true.B)
    dut.io.response.bits.query.attempt.producer.stid.poke(1.U)
    dut.io.response.bits.query.attempt.generation.poke(token.U)
    dut.io.response.bits.query.returnPipeIndex.poke((token % 3).U)
    dut.io.response.bits.query.stid.poke(1.U)
    dut.io.response.bits.query.loadBid.valid.poke(true.B)
    dut.io.response.bits.query.loadLsIdFullValid.poke(true.B)
    dut.io.response.bits.query.size.poke(8.U)
    dut.io.response.bits.query.baseValidMask.poke(baseMask.U)
    dut.io.response.bits.query.loadDataReturned.poke(true.B)
    dut.io.response.bits.query.scbReturned.poke(true.B)
    dut.io.response.bits.loadByteMask.poke(loadMask.U)
    dut.io.response.bits.forwardMask.poke(forwardMask.U)
    dut.io.response.bits.waitMask.poke(waitMask.U)
    dut.io.response.bits.mergedLineData.poke(data.U)
  }

  test("SCB bytes override only their valid L1D byte positions") {
    simulate(new LoadSourceLineMerge) { dut =>
      dut.io.l1d.poke(0.U.asTypeOf(dut.io.l1d))
      dut.io.scb.poke(0.U.asTypeOf(dut.io.scb))
      dut.io.l1d.returned.poke(true.B)
      dut.io.l1d.validMask.poke(0xf.U)
      dut.io.l1d.data.poke(BigInt("44332211", 16).U)
      dut.io.scb.returned.poke(true.B)
      dut.io.scb.validMask.poke(0x6.U)
      dut.io.scb.data.poke(BigInt("00bbaa00", 16).U)

      dut.io.mergedValidMask.expect(0xf.U)
      dut.io.mergedData.expect(BigInt("44bbaa11", 16).U)
      dut.io.loadDataReturned.expect(true.B)
      dut.io.scbReturned.expect(true.B)
    }
  }

  test("canonical STQ bytes complete a partial L1D SCB image at E4") {
    simulate(new STQLoadForwardResultPipeline(
      robEntries = 8, stqEntries = 4, lsidWidth = 40,
      tokenWidth = 11)) { dut =>
      clear(dut)
      pokeBaseResponse(dut, token = 17, loadMask = 0xf,
        baseMask = 0x3, forwardMask = 0xc, waitMask = 0,
        data = BigInt("ddccbbaa", 16))
      dut.io.response.valid.poke(true.B)
      dut.io.response.ready.expect(true.B)
      dut.io.accepted.expect(true.B)
      dut.clock.step()

      dut.io.response.valid.poke(false.B)
      dut.io.e3Valid.expect(true.B)
      dut.io.e3Identity.loadId.slot.expect(1.U)
      dut.io.e3Identity.attempt.generation.expect(17.U)
      dut.io.e3Identity.returnPipeIndex.expect(2.U)
      dut.io.e3LoadByteMask.expect(0xf.U)
      dut.io.e3ForwardMask.expect(0xc.U)
      dut.clock.step()

      dut.io.e4Valid.expect(true.B)
      dut.io.e4Identity.loadId.slot.expect(1.U)
      dut.io.e4Identity.attempt.generation.expect(17.U)
      dut.io.e4Identity.returnPipeIndex.expect(2.U)
      dut.io.e4ValidMask.expect(0xf.U)
      dut.io.e4DataComplete.expect(true.B)
      dut.io.e4SourcesReturned.expect(true.B)
      dut.io.e4ScbReturned.expect(true.B)
      dut.io.e4StqReturned.expect(true.B)
      dut.io.e4WakeupValid.expect(true.B)
      dut.io.e4MissKind.expect(LoadForwardMissKind.NoMiss)
      dut.io.e4LineData.expect(BigInt("ddccbbaa", 16).U)
    }
  }

  test("normal result credit backpressures data responses but not hard blocks") {
    simulate(new STQLoadForwardResultPipeline(
      robEntries = 8, stqEntries = 4, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeBaseResponse(dut, token = 23, loadMask = 0xff,
        baseMask = 0xff, forwardMask = 0, waitMask = 0,
        data = BigInt("8877665544332211", 16))
      dut.io.normalReady.poke(false.B)
      dut.io.response.valid.poke(true.B)
      dut.io.response.ready.expect(false.B)
      dut.io.accepted.expect(false.B)
      dut.clock.step(2)
      dut.io.e3Valid.expect(false.B)
      dut.io.e4Valid.expect(false.B)

      dut.io.response.bits.unknownOlderMask.poke(1.U)
      dut.io.response.ready.expect(true.B)
      dut.io.hardBlock.valid.expect(true.B)
      dut.io.hardBlockAccepted.expect(true.B)
    }
  }

  test("back to back responses keep exact canonical identities aligned") {
    simulate(new STQLoadForwardResultPipeline(
      robEntries = 8, stqEntries = 4, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeBaseResponse(dut, token = 21, loadMask = 0xff,
        baseMask = 0xff, forwardMask = 0, waitMask = 0,
        data = BigInt("1111111111111111", 16))
      dut.io.response.valid.poke(true.B)
      dut.clock.step()

      pokeBaseResponse(dut, token = 22, loadMask = 0xff,
        baseMask = 0xff, forwardMask = 0, waitMask = 0,
        data = BigInt("2222222222222222", 16))
      dut.io.response.valid.poke(true.B)
      dut.io.e3Identity.attempt.generation.expect(21.U)
      dut.clock.step()

      dut.io.response.valid.poke(false.B)
      dut.io.e4Valid.expect(true.B)
      dut.io.e4Identity.attempt.generation.expect(21.U)
      dut.io.e3Valid.expect(true.B)
      dut.io.e3Identity.attempt.generation.expect(22.U)
      dut.clock.step()

      dut.io.e4Valid.expect(true.B)
      dut.io.e4Identity.attempt.generation.expect(22.U)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.e3Valid.expect(false.B)
      dut.io.e4Valid.expect(false.B)
    }
  }

  test("selected not-ready store remains an ordinary precise replay result") {
    simulate(new STQLoadForwardResultPipeline(
      robEntries = 8, stqEntries = 4, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeBaseResponse(dut, token = 18, loadMask = 0xf,
        baseMask = 0xc, forwardMask = 0x3, waitMask = 0xc,
        data = BigInt("44332211", 16))
      dut.io.response.bits.blocked.poke(true.B)
      dut.io.response.bits.waitStore.valid.poke(true.B)
      dut.io.response.bits.waitStore.storeIndex.poke(2.U)
      dut.io.response.bits.waitStore.storeLsIdFullValid.poke(true.B)
      dut.io.response.bits.waitStore.storeLsIdFull.poke(
        BigInt("8000000001", 16).U)
      dut.io.response.valid.poke(true.B)
      dut.io.response.ready.expect(true.B)
      dut.io.hardBlock.valid.expect(false.B)
      dut.clock.step()

      dut.io.response.valid.poke(false.B)
      dut.clock.step()
      dut.io.e4Valid.expect(true.B)
      dut.io.e4DataComplete.expect(true.B)
      dut.io.e4WakeupValid.expect(false.B)
      dut.io.e4MissKind.expect(LoadForwardMissKind.StoreDataNotReady)
      dut.io.e4WaitStore.valid.expect(true.B)
      dut.io.e4WaitStore.storeIndex.expect(2.U)
      dut.io.e4WaitStore.storeLsIdFull.expect(
        BigInt("8000000001", 16).U)
    }
  }

  test("structural STQ uncertainty is retained and never enters E3") {
    simulate(new STQLoadForwardResultPipeline(
      robEntries = 8, stqEntries = 4, lsidWidth = 40,
      tokenWidth = 11)) { dut =>
      clear(dut)
      pokeBaseResponse(dut, token = 19, loadMask = 0xff,
        baseMask = 0xff, forwardMask = 0, waitMask = 0,
        data = BigInt("8877665544332211", 16))
      dut.io.response.bits.unknownOlderMask.poke(4.U)
      dut.io.response.bits.unknownWaitStore.valid.poke(true.B)
      dut.io.response.bits.unknownWaitStore.storeIndex.poke(2.U)
      dut.io.response.valid.poke(true.B)
      dut.io.hardBlock.ready.poke(false.B)

      dut.io.response.ready.expect(false.B)
      dut.io.hardBlock.valid.expect(true.B)
      dut.io.hardBlock.bits.query.token.expect(19.U)
      dut.clock.step(2)
      dut.io.e3Valid.expect(false.B)
      dut.io.e4Valid.expect(false.B)

      dut.io.hardBlock.ready.poke(true.B)
      dut.io.response.ready.expect(true.B)
      dut.io.hardBlockAccepted.expect(true.B)
      dut.clock.step()
      dut.io.response.valid.poke(false.B)
      dut.clock.step(2)
      dut.io.e3Valid.expect(false.B)
      dut.io.e4Valid.expect(false.B)
    }
  }

  test("result consumer elaborates with unequal STQ ROB and 40 bit LSID") {
    val io = new STQLoadForwardResultPipelineIO(
      robEntries = 8, stqEntries = 4, lsidWidth = 40,
      tokenWidth = 11)
    assert(io.response.bits.query.token.getWidth == 11)
    assert(io.e4WaitStore.storeLsIdFull.getWidth == 40)

    val sv = ChiselStage.emitSystemVerilog(
      new STQLoadForwardResultPipeline(
        robEntries = 8, stqEntries = 4, lsidWidth = 40,
        tokenWidth = 11))
    assert(sv.contains("module STQLoadForwardResultPipeline"))
    assert(sv.contains("LoadForwardResultPipeline"))
    assert(sv.contains("io_hardBlock_valid"))
    assert(sv.contains("io_e4MissKind"))
  }
}
