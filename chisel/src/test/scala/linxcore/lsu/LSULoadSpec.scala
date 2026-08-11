package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.params.SimulationParamProfiles
import linxcore.top.interface.{RecoveryCause, RecoveryPhase}
import org.scalatest.funsuite.AnyFunSuite

class LSULoadSpec extends AnyFunSuite with ChiselSim {
  private def initialize(dut: LSU): Unit = {
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
    dut.io.iex.loadRebindApply.foreach { port =>
      port.valid.poke(false.B); port.bits.poke(0.U.asTypeOf(port.bits))
    }
    dut.io.iex.loadRepick.foreach(_.ready.poke(true.B))
    dut.io.iex.loadCancel.foreach(_.ready.poke(true.B))
    dut.io.iex.recoveryEvent.ready.poke(true.B)
    dut.io.storeCommit.valid.poke(false.B)
    dut.io.storeCommit.bits.poke(0.U.asTypeOf(dut.io.storeCommit.bits))
    dut.io.storeResolve.ready.poke(true.B)
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
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

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
      dut.io.storeResolve.ready.poke(true.B)
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
      while (!result.valid.peek().litToBoolean && resultCycles < 192) {
        dut.io.iex.loadResult.drop(1).foreach(_.valid.expect(false.B,
          "a semantic lane-0 completion must not be routed by an internal return-pipeline slot"))
        dut.clock.step()
        resultCycles += 1
      }
      assert(resultCycles < 192,
        "an accepted lower-memory response must relaunch and complete the exact LIQ row")
      result.bits.identity.rob.ridSlot.expect(1.U)
      result.bits.identity.attemptGeneration.expect(1.U)
      result.bits.data.expect("h1122334455667788".U)
    }
  }

  test("second load lane is live and LSU alone authors its next replay attempt") {
    val p = SimulationParamProfiles.W4
    simulate(new LSU(p)) { dut =>
      initialize(dut)

      val issue = dut.io.iex.loadAddress(1)
      issue.bits.poke(0.U.asTypeOf(issue.bits))
      issue.bits.identity.rob.peId.poke(1.U)
      issue.bits.identity.rob.stid.poke(0.U)
      issue.bits.identity.rob.ridSlot.poke(3.U)
      issue.bits.identity.rob.ridGeneration.poke(4.U)
      issue.bits.identity.rob.memberIndex.poke(1.U)
      issue.bits.identity.rob.residentGeneration.poke(5.U)
      issue.bits.identity.rob.bid.poke(2.U)
      issue.bits.identity.rob.brobGeneration.poke(6.U)
      issue.bits.identity.transaction.value.poke(0x55.U)
      issue.bits.identity.transaction.generation.poke(7.U)
      issue.bits.identity.lsid.poke(9.U)
      issue.bits.identity.attemptGeneration.poke(11.U)
      issue.bits.identity.pipeId.poke(1.U)
      issue.bits.address.poke(0x3800.U)
      issue.bits.sizeBytes.poke(8.U)
      issue.bits.destination.valid.poke(true.B)
      issue.bits.destination.atag.poke(2.U)
      issue.bits.destination.ptag.poke(3.U)
      issue.bits.destination.previousPtag.poke(4.U)
      issue.bits.destinationRelativeIndex.poke(2.U)
      issue.valid.poke(true.B)
      issue.ready.expect(true.B)
      dut.io.iex.loadAllocation(0).valid.expect(false.B)
      dut.io.iex.loadAllocation(1).valid.expect(true.B)
      val allocation = dut.io.iex.loadAllocation(1).bits.allocationId.peek()
      val current = issue.bits.identity.peek()
      dut.clock.step()
      issue.valid.poke(false.B)

      var launched = false
      for (_ <- 0 until 8) {
        if (dut.io.iex.loadLaunch(1).valid.peek().litToBoolean) launched = true
        dut.io.iex.loadLaunch(0).valid.expect(false.B)
        dut.clock.step()
      }
      assert(launched, "lane-1 allocation must publish a lane-1 launch")

      val replay = dut.io.loadReissueRequest
      replay.bits.poke(0.U.asTypeOf(replay.bits))
      replay.bits.allocationId.poke(allocation)
      replay.bits.currentIdentity.poke(current)
      replay.bits.address.poke(0x3800.U)
      replay.valid.poke(true.B)
      replay.ready.expect(true.B)
      dut.io.iex.loadReissue(0).valid.expect(false.B)
      dut.io.iex.loadReissue(1).valid.expect(true.B)
      dut.io.iex.loadReissue(1).bits.currentIdentity.expect(current)
      dut.io.iex.loadReissue(1).bits.nextIdentity.transaction.value.expect(0x55.U)
      dut.io.iex.loadReissue(1).bits.nextIdentity.transaction.generation.expect(7.U)
      dut.io.iex.loadReissue(1).bits.nextIdentity.attemptGeneration.expect(12.U)
      dut.clock.step()
      replay.valid.poke(false.B)
    }
  }

  test("typed recovery prunes the affected STID while a peer STID remains replayable") {
    val base = SimulationParamProfiles.W4
    val p = base.copy(ooo = base.ooo.copy(stidCount = 2))
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      dut.io.memoryRequest.foreach(_.ready.poke(false.B))

      def issue(lane: Int, stid: Int, rid: Int, transaction: Int,
          lsid: Int, address: Int) = {
        val port = dut.io.iex.loadAddress(lane)
        port.bits.poke(0.U.asTypeOf(port.bits))
        port.bits.identity.rob.peId.poke(1.U)
        port.bits.identity.rob.stid.poke(stid.U)
        port.bits.identity.rob.ridSlot.poke(rid.U)
        port.bits.identity.rob.ridGeneration.poke(1.U)
        port.bits.identity.rob.residentGeneration.poke(1.U)
        port.bits.identity.rob.bid.poke(1.U)
        port.bits.identity.rob.brobGeneration.poke(1.U)
        port.bits.identity.transaction.value.poke(transaction.U)
        port.bits.identity.transaction.generation.poke(1.U)
        port.bits.identity.lsid.poke(lsid.U)
        port.bits.identity.attemptGeneration.poke(1.U)
        port.bits.identity.pipeId.poke(lane.U)
        port.bits.address.poke(address.U)
        port.bits.sizeBytes.poke(8.U)
        port.bits.destination.valid.poke(true.B)
        port.valid.poke(true.B)
        while (!port.ready.peek().litToBoolean) dut.clock.step()
        val allocation = dut.io.iex.loadAllocation(lane).bits.allocationId.peek()
        val identity = port.bits.identity.peek()
        dut.clock.step()
        port.valid.poke(false.B)
        (allocation, identity)
      }

      val (killedAllocation, killedIdentity) =
        issue(0, 0, 2, 0x21, 10, 0x4000)
      val (peerAllocation, peerIdentity) =
        issue(1, 1, 3, 0x22, 11, 0x5000)
      dut.clock.step(6)

      def pokePlan(phase: RecoveryPhase.Type): Unit = {
        val plan = if (phase == RecoveryPhase.Prepare)
          dut.io.recovery.prepare.bits else dut.io.recovery.apply.bits
        plan.poke(0.U.asTypeOf(plan))
        plan.transactionId.poke(0x77.U)
        plan.phase.poke(phase)
        plan.cause.poke(RecoveryCause.Branch)
        plan.trigger.poke(killedIdentity.rob)
        plan.firstKilledValid.poke(true.B)
        plan.firstKilled.poke(killedIdentity.rob)
        plan.lastKilled.poke(killedIdentity.rob)
        plan.killedGroupCount.poke(1.U)
        plan.killedMemberCount.poke(1.U)
      }
      pokePlan(RecoveryPhase.Prepare)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.io.recovery.prepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.prepared.valid.expect(true.B)
      pokePlan(RecoveryPhase.Apply)
      dut.io.recovery.apply.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)

      val replay = dut.io.loadReissueRequest
      replay.bits.poke(0.U.asTypeOf(replay.bits))
      replay.bits.allocationId.poke(killedAllocation)
      replay.bits.currentIdentity.poke(killedIdentity)
      replay.bits.address.poke(0x4000.U)
      replay.valid.poke(true.B)
      replay.ready.expect(true.B,
        "a stale killed replay is a drainable rejection event")
      dut.io.iex.loadReissue.foreach(_.valid.expect(false.B))
      dut.clock.step()
      replay.bits.allocationId.poke(peerAllocation)
      replay.bits.currentIdentity.poke(peerIdentity)
      replay.bits.address.poke(0x5000.U)
      replay.ready.expect(true.B)
      dut.io.iex.loadReissue(1).valid.expect(true.B)
      dut.clock.step()
      replay.valid.poke(false.B)
    }
  }
}
