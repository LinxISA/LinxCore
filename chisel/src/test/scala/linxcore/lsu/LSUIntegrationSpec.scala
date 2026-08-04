package linxcore.lsu

import chisel3._
import linxcore.params.SimulationParamProfiles
import linxcore.top.interface.{LSUMaintenanceCommand, LSUMemoryFaultCause,
  RecoveryCause, RecoveryPhase, StoreMemoryClass}
import org.scalatest.funsuite.AnyFunSuite

class LSUIntegrationSpec extends AnyFunSuite with LSUMemoryTestSupport {

  private def pokeRob(
      rob: linxcore.top.interface.RobIdentity): Unit = {
    rob.peId.poke(1.U)
    rob.stid.poke(0.U)
    rob.ridSlot.poke(2.U)
    rob.ridGeneration.poke(1.U)
    rob.memberIndex.poke(0.U)
    rob.residentGeneration.poke(1.U)
    rob.bid.poke(1.U)
    rob.brobGeneration.poke(1.U)
  }

  test("store address translation retains the exact STA before mutating its reserved STQ row") {
    val p = SimulationParamProfiles.W4
    val virtualAddress = BigInt("8000000000003000", 16)
    simulate(new LSU(p)) { dut =>
      initialize(dut)

      val reserve = dut.io.iex.storeReservation(0)
      reserve.bits.poke(0.U.asTypeOf(reserve.bits))
      reserve.bits.transactionId.poke(11.U)
      pokeRob(reserve.bits.rob)
      reserve.bits.memoryOrder.requestCount.poke(1.U)
      reserve.bits.memoryOrder.firstLsid.poke(21.U)
      reserve.bits.memoryOrder.firstSid.poke(31.U)
      reserve.bits.requestCount.poke(1.U)
      reserve.bits.sizeBytes.poke(8.U)
      reserve.valid.poke(true.B)
      reserve.ready.expect(true.B)
      dut.clock.step()
      reserve.valid.poke(false.B)

      val sta = dut.io.iex.storeAddress(0)
      sta.bits.poke(0.U.asTypeOf(sta.bits))
      pokeRob(sta.bits.identity.rob)
      sta.bits.identity.transaction.value.poke(11.U)
      sta.bits.identity.transaction.generation.poke(1.U)
      sta.bits.identity.lsid.poke(21.U)
      sta.bits.memoryOrder.requestCount.poke(1.U)
      sta.bits.memoryOrder.firstLsid.poke(21.U)
      sta.bits.memoryOrder.firstSid.poke(31.U)
      sta.bits.requestCount.poke(1.U)
      sta.bits.address.poke(virtualAddress.U)
      sta.bits.sizeBytes.poke(8.U)
      sta.valid.poke(true.B)
      sta.ready.expect(false.B)

      val request = dut.io.memoryRequest(0)
      var cycles = 0
      while (!request.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 16, "store DTLB miss did not reach lower memory")
      request.bits.address.expect(virtualAddress.U)
      val identity = request.bits.identity.peek()
      dut.clock.step()

      val response = dut.io.memoryResponse(0)
      response.bits.poke(0.U.asTypeOf(response.bits))
      response.bits.identity.poke(identity)
      val pageNumberWidth = p.physicalAddressWidth - 12
      val devicePpn = (BigInt(0xff) << (pageNumberWidth - 8)) | 0x53
      response.bits.data.poke(devicePpn.U)
      response.bits.attributesValid.poke(true.B)
      response.bits.readable.poke(true.B)
      response.bits.writable.poke(true.B)
      response.bits.cacheable.poke(false.B)
      response.bits.device.poke(true.B)
      response.valid.poke(true.B)
      response.ready.expect(true.B)
      dut.clock.step()
      response.valid.poke(false.B)

      var staReadyCycles = 0
      while (!sta.ready.peek().litToBoolean && staReadyCycles < 8) {
        dut.clock.step()
        staReadyCycles += 1
      }
      assert(staReadyCycles < 8,
        "exact store translation refill did not release STA")
      dut.clock.step()
      sta.valid.poke(false.B)

      val std = dut.io.iex.storeData(1)
      std.bits.poke(0.U.asTypeOf(std.bits))
      pokeRob(std.bits.identity.rob)
      std.bits.identity.transaction.value.poke(11.U)
      std.bits.identity.transaction.generation.poke(1.U)
      std.bits.identity.lsid.poke(21.U)
      std.bits.memoryOrder.requestCount.poke(1.U)
      std.bits.memoryOrder.firstLsid.poke(21.U)
      std.bits.memoryOrder.firstSid.poke(31.U)
      std.bits.requestCount.poke(1.U)
      std.bits.sizeBytes.poke(8.U)
      std.bits.data(0).poke("h1122334455667788".U)
      std.bits.byteMask(0).poke("hff".U)
      std.valid.poke(true.B)
      while (!std.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      std.valid.poke(false.B)

      val classify = dut.io.storeClassify
      classify.bits.poke(0.U.asTypeOf(classify.bits))
      pokeRob(classify.bits.rob)
      classify.bits.logicalFirstLsid.poke(21.U)
      classify.bits.logicalFirstStoreId.poke(31.U)
      classify.bits.requestCount.poke(1.U)
      classify.bits.memoryClass.poke(StoreMemoryClass.NormalCacheable)
      classify.valid.poke(true.B)
      while (!classify.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      classify.valid.poke(false.B)

      val storeMemory = dut.io.memoryRequest(p.lsu.loadPipes)
      storeMemory.ready.poke(false.B)
      val commit = dut.io.storeCommit
      commit.bits.poke(0.U.asTypeOf(commit.bits))
      pokeRob(commit.bits.rob)
      commit.bits.logicalFirstLsid.poke(21.U)
      commit.bits.logicalFirstStoreId.poke(31.U)
      commit.bits.requestCount.poke(1.U)
      commit.valid.poke(true.B)
      while (!commit.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      commit.valid.poke(false.B)

      cycles = 0
      while (!storeMemory.valid.peek().litToBoolean && cycles < 32) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 32, "translated committed store did not reach memory")
      storeMemory.bits.accessKind.expect(
        linxcore.top.interface.MemoryAccessKind.Device)
      storeMemory.bits.address.expect((devicePpn << 12).U)
      val storeIdentity = storeMemory.bits.identity.peek()
      storeMemory.ready.poke(true.B)
      dut.clock.step()

      val prepare = dut.io.recovery.prepare
      prepare.bits.poke(0.U.asTypeOf(prepare.bits))
      prepare.bits.transactionId.poke(90.U)
      prepare.bits.phase.poke(RecoveryPhase.Prepare)
      prepare.bits.cause.poke(RecoveryCause.Branch)
      prepare.bits.redirectPc.poke(0x400.U)
      prepare.bits.newEpoch.poke(2.U)
      prepare.valid.poke(true.B)
      prepare.ready.expect(true.B)
      dut.clock.step()
      prepare.valid.poke(false.B)
      dut.io.recovery.prepared.valid.expect(false.B)

      val storeResponse = dut.io.memoryResponse(p.lsu.loadPipes)
      storeResponse.bits.poke(0.U.asTypeOf(storeResponse.bits))
      storeResponse.bits.identity.poke(storeIdentity)
      storeResponse.valid.poke(true.B)
      while (!storeResponse.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      storeResponse.valid.poke(false.B)

      var storePrepared = false
      for (_ <- 0 until 64) {
        if (dut.io.recovery.prepared.valid.peek().litToBoolean) storePrepared = true
        dut.clock.step()
      }
      assert(storePrepared, "store recovery did not wait for exact drain completion")
    }
  }

  test("recovery preparation waits for an outstanding lower-memory transaction to drain") {
    val p = SimulationParamProfiles.W4
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      pokeLoad(dut, 0x1800)
      dut.io.iex.loadAddress(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.iex.loadAddress(0).valid.poke(false.B)

      val request = dut.io.memoryRequest(0)
      var cycles = 0
      while (!request.valid.peek().litToBoolean && cycles < 32) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 32, "cache miss did not reach lower memory")
      val identity = request.bits.identity.peek()
      val address = request.bits.address.peek()
      dut.clock.step()

      val prepare = dut.io.recovery.prepare
      prepare.bits.poke(0.U.asTypeOf(prepare.bits))
      prepare.bits.transactionId.poke(91.U)
      prepare.bits.phase.poke(RecoveryPhase.Prepare)
      prepare.bits.cause.poke(RecoveryCause.Branch)
      prepare.bits.trigger.poke(
        dut.io.iex.loadAddress(0).bits.identity.rob.peek())
      prepare.bits.redirectPc.poke(0x400.U)
      prepare.bits.newEpoch.poke(2.U)
      prepare.bits.firstKilledValid.poke(true.B)
      prepare.bits.firstKilled.poke(
        dut.io.iex.loadAddress(0).bits.identity.rob.peek())
      prepare.bits.lastKilled.poke(
        dut.io.iex.loadAddress(0).bits.identity.rob.peek())
      prepare.bits.killedGroupCount.poke(1.U)
      prepare.bits.killedMemberCount.poke(1.U)
      prepare.valid.poke(true.B)
      prepare.ready.expect(true.B)
      dut.clock.step()
      prepare.valid.poke(false.B)
      dut.io.recovery.prepared.valid.expect(false.B)
      dut.io.quiescent.expect(false.B)

      dut.io.recovery.prepared.ready.poke(false.B)

      val response = dut.io.memoryResponse(0)
      response.bits.poke(0.U.asTypeOf(response.bits))
      response.bits.identity.poke(identity)
      response.bits.address.poke(address)
      response.bits.data.poke("h8877665544332211".U)
      response.valid.poke(true.B)
      response.ready.expect(true.B)
      dut.clock.step()
      response.valid.poke(false.B)

      var cyclesAfterResponse = 0
      while (!dut.io.recovery.prepared.valid.peek().litToBoolean && cyclesAfterResponse < 16) {
        dut.clock.step()
        cyclesAfterResponse += 1
      }
      assert(cyclesAfterResponse < 16,
        "recovery did not publish quiescence after the exact response drained")
      dut.io.recovery.prepared.ready.poke(true.B)
      dut.clock.step()

      val apply = dut.io.recovery.apply
      apply.bits.poke(prepare.bits.peek())
      apply.bits.phase.poke(RecoveryPhase.Apply)
      apply.valid.poke(true.B)
      dut.clock.step()
      apply.valid.poke(false.B)
      var quiescentCycles = 0
      while (!dut.io.quiescent.peek().litToBoolean && quiescentCycles < 16) {
        dut.clock.step()
        quiescentCycles += 1
      }
      assert(quiescentCycles < 16, "LSU did not become quiescent after recovery apply")
    }
  }

  test("translation transactions participate in common recovery quiescence") {
    val p = SimulationParamProfiles.W4
    val virtualAddress = BigInt("8000000000007000", 16)
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      pokeLoad(dut, virtualAddress, transaction = 19)

      val request = dut.io.memoryRequest(0)
      var requestCycles = 0
      while (!request.valid.peek().litToBoolean && requestCycles < 16) {
        dut.clock.step()
        requestCycles += 1
      }
      assert(requestCycles < 16, "translation request did not reach memory")
      val identity = request.bits.identity.peek()
      dut.clock.step()
      dut.io.iex.loadAddress(0).valid.poke(false.B)

      val prepare = dut.io.recovery.prepare
      prepare.bits.poke(0.U.asTypeOf(prepare.bits))
      prepare.bits.transactionId.poke(92.U)
      prepare.bits.phase.poke(RecoveryPhase.Prepare)
      prepare.bits.cause.poke(RecoveryCause.Branch)
      prepare.bits.redirectPc.poke(0x500.U)
      prepare.bits.newEpoch.poke(3.U)
      dut.io.recovery.prepared.ready.poke(false.B)
      prepare.valid.poke(true.B)
      dut.clock.step()
      prepare.valid.poke(false.B)
      dut.io.recovery.prepared.valid.expect(false.B)
      dut.io.quiescent.expect(false.B)

      val response = dut.io.memoryResponse(0)
      response.bits.poke(0.U.asTypeOf(response.bits))
      response.bits.identity.poke(identity)
      response.bits.data.poke(0x77.U)
      response.valid.poke(true.B)
      response.ready.expect(true.B)
      dut.clock.step()
      response.valid.poke(false.B)

      var preparedCycles = 0
      while (!dut.io.recovery.prepared.valid.peek().litToBoolean && preparedCycles < 16) {
        dut.clock.step()
        preparedCycles += 1
      }
      assert(preparedCycles < 16, "translation drain did not release recovery preparation")
      dut.io.recovery.prepared.ready.poke(true.B)
      dut.clock.step()

      val apply = dut.io.recovery.apply
      apply.bits.poke(prepare.bits.peek())
      apply.bits.phase.poke(RecoveryPhase.Apply)
      apply.valid.poke(true.B)
      dut.clock.step()
      apply.valid.poke(false.B)
      dut.io.quiescent.expect(true.B)
    }
  }

}

class LSUIntegrationReviewFixSpec extends AnyFunSuite with LSUMemoryTestSupport {

  test("misaligned and denied requests complete once through the precise LSU fault boundary") {
    val p = SimulationParamProfiles.W4
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      dut.io.memoryFault.ready.poke(false.B)
      pokeLoad(dut, 0x1003, transaction = 41)
      dut.io.iex.loadAddress(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.iex.loadAddress(0).valid.poke(false.B)
      dut.io.memoryFault.valid.expect(true.B)
      dut.io.memoryFault.bits.identity.transaction.value.expect(41.U)
      dut.io.memoryFault.bits.cause.expect(LSUMemoryFaultCause.Alignment)
      dut.clock.step(2)
      dut.io.memoryFault.valid.expect(true.B)
      dut.io.memoryFault.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryFault.valid.expect(false.B)

      val virtualAddress = BigInt("800000000000a000", 16)
      pokeLoad(dut, virtualAddress, transaction = 42)
      val request = dut.io.memoryRequest(0)
      while (!request.valid.peek().litToBoolean) dut.clock.step()
      val identity = request.bits.identity.peek()
      dut.clock.step()
      dut.io.memoryResponse(0).bits.poke(0.U.asTypeOf(dut.io.memoryResponse(0).bits))
      dut.io.memoryResponse(0).bits.identity.poke(identity)
      dut.io.memoryResponse(0).bits.data.poke(0xa1.U)
      dut.io.memoryResponse(0).bits.attributesValid.poke(true.B)
      dut.io.memoryResponse(0).bits.readable.poke(false.B)
      dut.io.memoryResponse(0).bits.writable.poke(false.B)
      dut.io.memoryResponse(0).bits.cacheable.poke(false.B)
      dut.io.memoryResponse(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse(0).valid.poke(false.B)
      dut.io.iex.loadAddress(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.iex.loadAddress(0).valid.poke(false.B)
      dut.io.memoryFault.valid.expect(true.B)
      dut.io.memoryFault.bits.identity.transaction.value.expect(42.U)
      dut.io.memoryFault.bits.cause.expect(LSUMemoryFaultCause.Access)
      dut.clock.step()
      dut.io.memoryFault.valid.expect(false.B)
    }
  }

  test("wrong-lane translation and data responses complete exact owners without stranding recovery") {
    val p = SimulationParamProfiles.W4
    val virtualAddress = BigInt("800000000000b000", 16)
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      pokeLoad(dut, virtualAddress, transaction = 43)
      while (!dut.io.memoryRequest(0).valid.peek().litToBoolean) dut.clock.step()
      val translationIdentity = dut.io.memoryRequest(0).bits.identity.peek()
      dut.clock.step()
      dut.io.memoryResponse(1).bits.poke(0.U.asTypeOf(dut.io.memoryResponse(1).bits))
      dut.io.memoryResponse(1).bits.identity.poke(translationIdentity)
      dut.io.memoryResponse(1).bits.data.poke(0xb1.U)
      dut.io.memoryResponse(1).bits.attributesValid.poke(true.B)
      dut.io.memoryResponse(1).bits.readable.poke(true.B)
      dut.io.memoryResponse(1).bits.writable.poke(true.B)
      dut.io.memoryResponse(1).bits.cacheable.poke(true.B)
      dut.io.memoryResponse(1).valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse(1).valid.poke(false.B)
      while (!dut.io.iex.loadAddress(0).ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.io.iex.loadAddress(0).valid.poke(false.B)

      var dataLaneOption = (0 until p.lsu.loadPipes).find(
        lane => dut.io.memoryRequest(lane).valid.peek().litToBoolean)
      var dataRequestCycles = 0
      while (dataLaneOption.isEmpty && dataRequestCycles < 32) {
        dut.clock.step()
        dataRequestCycles += 1
        dataLaneOption = (0 until p.lsu.loadPipes).find(
          lane => dut.io.memoryRequest(lane).valid.peek().litToBoolean)
      }
      assert(dataLaneOption.nonEmpty,
        "translated load did not publish its data request")
      val dataLane = dataLaneOption.get
      val dataIdentity = dut.io.memoryRequest(dataLane).bits.identity.peek()
      val dataAddress = dut.io.memoryRequest(dataLane).bits.address.peek()
      val wrongLane = 1 - dataLane
      dut.clock.step()
      dut.io.memoryResponse(wrongLane).bits.poke(
        0.U.asTypeOf(dut.io.memoryResponse(wrongLane).bits))
      dut.io.memoryResponse(wrongLane).bits.identity.poke(dataIdentity)
      dut.io.memoryResponse(wrongLane).bits.address.poke(dataAddress)
      dut.io.memoryResponse(wrongLane).bits.data.poke("h0102030405060708".U)
      dut.io.memoryResponse(wrongLane).valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse(wrongLane).valid.poke(false.B)
      var resultCycles = 0
      while (!dut.io.iex.loadResult(0).valid.peek().litToBoolean && resultCycles < 32) {
        dut.clock.step()
        resultCycles += 1
      }
      assert(resultCycles < 32, "wrong-lane exact data response stranded the load")
      dut.clock.step()
      dut.io.quiescent.expect(true.B)
    }
  }

  test("public maintenance retains fence and invalidation until their completion is accepted") {
    val p = SimulationParamProfiles.W4
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      dut.io.maintenanceResult.ready.poke(false.B)
      dut.io.maintenance.bits.command.poke(LSUMaintenanceCommand.Fence)
      dut.io.maintenance.bits.address.poke(0.U)
      dut.io.maintenance.valid.poke(true.B)
      dut.io.maintenance.ready.expect(true.B)
      dut.clock.step()
      dut.io.maintenance.valid.poke(false.B)
      dut.io.maintenanceResult.valid.expect(true.B)
      dut.io.quiescent.expect(false.B)
      dut.clock.step(2)
      dut.io.maintenanceResult.valid.expect(true.B)
      dut.io.maintenanceResult.ready.poke(true.B)
      dut.clock.step()
      dut.io.quiescent.expect(true.B)

      for (command <- Seq(
          LSUMaintenanceCommand.InvalidateTranslation,
          LSUMaintenanceCommand.InvalidateLine,
          LSUMaintenanceCommand.InvalidateAll)) {
        dut.io.maintenance.bits.command.poke(command)
        dut.io.maintenance.bits.address.poke(0x1000.U)
        dut.io.maintenance.valid.poke(true.B)
        while (!dut.io.maintenance.ready.peek().litToBoolean) dut.clock.step()
        dut.clock.step()
        dut.io.maintenance.valid.poke(false.B)
        dut.io.maintenanceResult.valid.expect(true.B)
        dut.io.maintenanceResult.bits.command.expect(command)
        dut.io.maintenanceResult.bits.success.expect(true.B)
        dut.clock.step()
      }
      dut.io.protocolError.expect(false.B)
    }
  }

  test("recovery prepared waits for a retained load return to be consumed") {
    val p = SimulationParamProfiles.W4
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      dut.io.iex.loadResult(0).ready.poke(false.B)
      pokeLoad(dut, 0x3800, transaction = 44)
      dut.clock.step()
      dut.io.iex.loadAddress(0).valid.poke(false.B)
      while (!dut.io.memoryRequest(0).valid.peek().litToBoolean) dut.clock.step()
      val identity = dut.io.memoryRequest(0).bits.identity.peek()
      val address = dut.io.memoryRequest(0).bits.address.peek()
      dut.clock.step()
      dut.io.memoryResponse(0).bits.poke(0.U.asTypeOf(dut.io.memoryResponse(0).bits))
      dut.io.memoryResponse(0).bits.identity.poke(identity)
      dut.io.memoryResponse(0).bits.address.poke(address)
      dut.io.memoryResponse(0).bits.data.poke("h123456789abcdef0".U)
      dut.io.memoryResponse(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse(0).valid.poke(false.B)
      while (!dut.io.iex.loadResult(0).valid.peek().litToBoolean) dut.clock.step()

      val prepare = dut.io.recovery.prepare
      prepare.bits.poke(0.U.asTypeOf(prepare.bits))
      prepare.bits.transactionId.poke(93.U)
      prepare.bits.phase.poke(RecoveryPhase.Prepare)
      prepare.bits.cause.poke(RecoveryCause.Branch)
      prepare.valid.poke(true.B)
      dut.clock.step()
      prepare.valid.poke(false.B)
      dut.io.recovery.prepared.valid.expect(false.B)
      dut.clock.step(2)
      dut.io.recovery.prepared.valid.expect(false.B)
      dut.io.iex.loadResult(0).ready.poke(true.B)
      dut.clock.step()
      var preparedCycles = 0
      while (!dut.io.recovery.prepared.valid.peek().litToBoolean && preparedCycles < 64) {
        dut.clock.step()
        preparedCycles += 1
      }
      assert(preparedCycles < 64, "retained LRET did not release recovery quiescence")
    }
  }
}
