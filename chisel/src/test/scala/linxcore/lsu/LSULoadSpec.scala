package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class LSULoadSpec extends AnyFunSuite with ChiselSim {
  test("public LSU retains the two-pipe LIQ replay refill and return graph") {
    val chirrtl = ChiselStage.emitCHIRRTL(new LSU(SimulationParamProfiles.W4))
    assert(chirrtl.contains("module LoadInflightQueue"))
    assert(chirrtl.contains("module ScalarLSUMDBPath"))
    assert(chirrtl.contains("module LoadMissQueue"))
    assert(chirrtl.contains("module LoadRefillTransport"))
    assert(chirrtl.contains("module ScalarLSULoadReturnPipeline"))
    assert(!chirrtl.contains("module ReducedLoadReplayLiqAllocPath"))
  }

  test("public load issue receives an atomic LIQ binding miss refill and exact result") {
    val p = SimulationParamProfiles.W4
    simulate(new LSU(p)) { dut =>
      dut.io.iex.storeReservation.foreach { port =>
        port.valid.poke(false.B); port.bits.poke(0.U.asTypeOf(port.bits))
      }
      dut.io.iex.storeAddress.foreach { port =>
        port.valid.poke(false.B); port.bits.poke(0.U.asTypeOf(port.bits))
      }
      dut.io.iex.storeData.foreach { port =>
        port.valid.poke(false.B); port.bits.poke(0.U.asTypeOf(port.bits))
      }
      dut.io.iex.loadAddress.foreach { port =>
        port.valid.poke(false.B); port.bits.poke(0.U.asTypeOf(port.bits))
      }
      dut.io.iex.loadResult.foreach(_.ready.poke(true.B))
      dut.io.iex.loadReissue.foreach(_.ready.poke(true.B))
      dut.io.iex.loadRepick.foreach(_.ready.poke(true.B))
      dut.io.iex.loadCancel.foreach(_.ready.poke(true.B))
      dut.io.iex.recoveryEvent.ready.poke(true.B)
      dut.io.storeCommit.valid.poke(false.B)
      dut.io.storeCommit.bits.poke(0.U.asTypeOf(dut.io.storeCommit.bits))
      dut.io.storeClassify.valid.poke(false.B)
      dut.io.storeClassify.bits.poke(0.U.asTypeOf(dut.io.storeClassify.bits))
      dut.io.loadReissueRequest.valid.poke(false.B)
      dut.io.loadReissueRequest.bits.poke(
        0.U.asTypeOf(dut.io.loadReissueRequest.bits))
      dut.io.memoryRequest.foreach(_.ready.poke(true.B))
      dut.io.memoryResponse.foreach { port =>
        port.valid.poke(false.B); port.bits.poke(0.U.asTypeOf(port.bits))
      }
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.prepare.bits.poke(0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepared.ready.poke(true.B)
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.recovery.apply.bits.poke(0.U.asTypeOf(dut.io.recovery.apply.bits))
      dut.io.recovery.abort.valid.poke(false.B)
      dut.io.recovery.abort.bits.poke(0.U.asTypeOf(dut.io.recovery.abort.bits))
      dut.io.trace.ready.poke(true.B)

      val issue = dut.io.iex.loadAddress(0)
      issue.bits.poke(0.U.asTypeOf(issue.bits))
      issue.bits.identity.rob.peId.poke(1.U)
      issue.bits.identity.rob.stid.poke(0.U)
      issue.bits.identity.rob.ridSlot.poke(1.U)
      issue.bits.identity.rob.ridGeneration.poke(1.U)
      issue.bits.identity.rob.memberIndex.poke(0.U)
      issue.bits.identity.rob.residentGeneration.poke(1.U)
      issue.bits.identity.rob.bid.poke(1.U)
      issue.bits.identity.rob.brobGeneration.poke(1.U)
      issue.bits.identity.lsid.poke(3.U)
      issue.bits.identity.attemptGeneration.poke(1.U)
      issue.bits.identity.pipeId.poke(0.U)
      issue.bits.address.poke(0x1800.U)
      issue.bits.sizeBytes.poke(8.U)
      issue.bits.destination.valid.poke(true.B)
      issue.bits.destination.atag.poke(2.U)
      issue.bits.destination.ptag.poke(3.U)
      issue.bits.destination.previousPtag.poke(4.U)
      issue.bits.destinationRelativeIndex.poke(2.U)
      issue.valid.poke(true.B)
      issue.ready.expect(true.B)
      dut.io.iex.loadAllocation(0).valid.expect(true.B)
      val allocated = dut.io.iex.loadAllocation(0).bits.allocationId.value.peek().litValue
      dut.clock.step()
      issue.valid.poke(false.B)

      var launched = false
      for (_ <- 0 until 4) {
        if (dut.io.iex.loadLaunch(0).valid.peek().litToBoolean) {
          dut.io.iex.loadLaunch(0).bits.allocationId.value.expect(allocated.U)
          dut.io.iex.loadLaunch(0).bits.identity.rob.ridSlot.expect(1.U)
          launched = true
        }
        dut.clock.step()
      }
      assert(launched, "accepted LIQ allocation must publish its exact launch")

      val request = dut.io.memoryRequest(0)
      var requestCycles = 0
      while (!request.valid.peek().litToBoolean && requestCycles < 32) {
        dut.clock.step()
        requestCycles += 1
      }
      assert(requestCycles < 32, "a load miss must publish a lower-memory request")
      val requestId = request.bits.identity.value.peek().litValue
      val requestGeneration = request.bits.identity.generation.peek().litValue
      val requestAddress = request.bits.address.peek().litValue
      dut.clock.step()

      val response = dut.io.memoryResponse(0)
      response.bits.poke(0.U.asTypeOf(response.bits))
      response.bits.identity.value.poke(requestId.U)
      response.bits.identity.generation.poke(requestGeneration.U)
      response.bits.address.poke(requestAddress.U)
      response.bits.data.poke("h1122334455667788".U)
      response.valid.poke(true.B)
      response.ready.expect(true.B)
      dut.clock.step()
      response.valid.poke(false.B)

      val result = dut.io.iex.loadResult(0)
      var resultCycles = 0
      while (!result.valid.peek().litToBoolean && resultCycles < 64) {
        dut.clock.step()
        resultCycles += 1
      }
      assert(resultCycles < 64,
        "an accepted lower-memory response must relaunch and complete the exact LIQ row")
      result.bits.identity.rob.ridSlot.expect(1.U)
      result.bits.identity.attemptGeneration.expect(1.U)
      result.bits.data.expect("h1122334455667788".U)
    }
  }
}
