package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.SimulationParamProfiles
import linxcore.top.interface.MemoryAccessKind
import org.scalatest.funsuite.AnyFunSuite

trait LSUMemoryTestSupport extends ChiselSim {
  this: org.scalatest.TestSuite =>
  def initialize(dut: LSU): Unit = {
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
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  def pokeLoad(dut: LSU, address: BigInt, transaction: Int = 5): Unit = {
    val issue = dut.io.iex.loadAddress(0)
    issue.bits.poke(0.U.asTypeOf(issue.bits))
    issue.bits.identity.rob.peId.poke(1.U)
    issue.bits.identity.rob.stid.poke(0.U)
    issue.bits.identity.rob.ridSlot.poke(1.U)
    issue.bits.identity.rob.ridGeneration.poke(2.U)
    issue.bits.identity.rob.memberIndex.poke(0.U)
    issue.bits.identity.rob.residentGeneration.poke(3.U)
    issue.bits.identity.rob.bid.poke(1.U)
    issue.bits.identity.rob.brobGeneration.poke(4.U)
    issue.bits.identity.transaction.value.poke(transaction.U)
    issue.bits.identity.transaction.generation.poke(1.U)
    issue.bits.identity.lsid.poke(7.U)
    issue.bits.identity.attemptGeneration.poke(1.U)
    issue.bits.identity.pipeId.poke(0.U)
    issue.bits.pc.poke(0x80.U)
    issue.bits.address.poke(address.U)
    issue.bits.sizeBytes.poke(8.U)
    issue.bits.destination.valid.poke(true.B)
    issue.bits.destination.atag.poke(2.U)
    issue.bits.destination.ptag.poke(3.U)
    issue.bits.destination.previousPtag.poke(4.U)
    issue.bits.destinationRelativeIndex.poke(2.U)
    issue.valid.poke(true.B)
  }
}

class LSUMemorySpec extends AnyFunSuite with LSUMemoryTestSupport {

  test("a virtual load misses in DTLB before allocating LIQ and ignores a stale translation response") {
    val p = SimulationParamProfiles.W4
    val virtualAddress = BigInt("8000000000001238", 16)
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      pokeLoad(dut, virtualAddress)

      dut.io.iex.loadAddress(0).ready.expect(false.B)
      dut.io.iex.loadAllocation(0).valid.expect(false.B)

      val request = dut.io.memoryRequest(0)
      var cycles = 0
      while (!request.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 16, "DTLB miss did not publish its retained page request")
      request.bits.address.expect(BigInt("8000000000001000", 16).U)
      request.bits.sizeBytes.expect((p.dataWidth / 8).U)
      val requestId = request.bits.identity.value.peek().litValue
      val requestGeneration = request.bits.identity.generation.peek().litValue
      dut.clock.step()

      val response = dut.io.memoryResponse(0)
      response.bits.poke(0.U.asTypeOf(response.bits))
      response.bits.identity.value.poke(requestId.U)
      response.bits.identity.generation.poke((requestGeneration + 1).U)
      response.bits.data.poke(0x42.U)
      response.valid.poke(true.B)
      response.ready.expect(true.B)
      dut.clock.step()
      response.valid.poke(false.B)
      dut.io.iex.loadAddress(0).ready.expect(false.B)

      response.bits.identity.value.poke(requestId.U)
      response.bits.identity.generation.poke(requestGeneration.U)
      response.bits.data.poke(0x42.U)
      response.valid.poke(true.B)
      response.ready.expect(true.B)
      dut.clock.step()
      response.valid.poke(false.B)

      var accepted = false
      for (_ <- 0 until 8) {
        if (dut.io.iex.loadAddress(0).ready.peek().litToBoolean) accepted = true
        dut.clock.step()
      }
      assert(accepted, "exact DTLB refill did not release the retained virtual load")
    }
  }

  test("translation classifies alignment protection cacheable and device accesses") {
    val p = SimulationParamProfiles.W4
    val virtualAddress = BigInt("8000000000002000", 16)
    simulate(new DSideTranslation(p, entries = 4, pageBytes = 4096)) { dut =>
      dut.io.lookupValid.poke(false.B)
      dut.io.virtualAddress.poke(0.U)
      dut.io.sizeBytes.poke(8.U)
      dut.io.write.poke(false.B)
      dut.io.invalidate.poke(false.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(
        0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.lookupValid.poke(true.B)
      dut.io.virtualAddress.poke(0x1003.U)
      dut.io.alignmentFault.expect(true.B)
      dut.io.lookupReady.expect(false.B)

      dut.io.virtualAddress.poke(virtualAddress.U)
      dut.io.alignmentFault.expect(false.B)
      dut.io.miss.expect(true.B)
      dut.clock.step()
      while (!dut.io.memoryRequest.valid.peek().litToBoolean) dut.clock.step()
      val deniedIdentity = dut.io.memoryRequest.bits.identity.peek()
      dut.clock.step()
      dut.io.memoryResponse.bits.poke(
        0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.io.memoryResponse.bits.identity.poke(deniedIdentity)
      dut.io.memoryResponse.bits.denied.poke(true.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.hit.expect(true.B)
      dut.io.accessFault.expect(true.B)
      dut.io.lookupReady.expect(false.B)

      dut.io.lookupValid.poke(false.B)
      dut.io.invalidate.poke(true.B)
      dut.clock.step()
      dut.io.invalidate.poke(false.B)
      dut.io.lookupValid.poke(true.B)
      dut.io.virtualAddress.poke(virtualAddress.U)
      dut.clock.step()
      while (!dut.io.memoryRequest.valid.peek().litToBoolean) dut.clock.step()
      val deviceIdentity = dut.io.memoryRequest.bits.identity.peek()
      dut.clock.step()
      val pageNumberWidth = p.physicalAddressWidth - 12
      val devicePpn = BigInt(0xff) << (pageNumberWidth - 8)
      dut.io.memoryResponse.bits.poke(
        0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.io.memoryResponse.bits.identity.poke(deviceIdentity)
      dut.io.memoryResponse.bits.data.poke(devicePpn.U)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.lookupReady.expect(true.B)
      dut.io.device.expect(true.B)
      dut.io.cacheable.expect(false.B)
    }
  }

  test("cache miss rejects a response generation mismatch then refills and returns exact data") {
    val p = SimulationParamProfiles.W4
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      pokeLoad(dut, 0x2800, transaction = 9)
      dut.io.iex.loadAddress(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.iex.loadAddress(0).valid.poke(false.B)

      val request = dut.io.memoryRequest(0)
      var requestCycles = 0
      while (!request.valid.peek().litToBoolean && requestCycles < 32) {
        dut.clock.step()
        requestCycles += 1
      }
      assert(requestCycles < 32, "load miss did not reach lower memory")
      val requestId = request.bits.identity.value.peek().litValue
      val requestGeneration = request.bits.identity.generation.peek().litValue
      val requestAddress = request.bits.address.peek().litValue
      dut.clock.step()

      val response = dut.io.memoryResponse(0)
      response.bits.poke(0.U.asTypeOf(response.bits))
      response.bits.identity.value.poke(requestId.U)
      response.bits.identity.generation.poke((requestGeneration + 1).U)
      response.bits.address.poke(requestAddress.U)
      response.bits.data.poke("hdeadbeefdeadbeef".U)
      response.valid.poke(true.B)
      response.ready.expect(true.B)
      dut.clock.step()
      response.valid.poke(false.B)
      for (_ <- 0 until 8) {
        dut.io.iex.loadResult(0).valid.expect(false.B)
        dut.clock.step()
      }

      response.bits.identity.generation.poke(requestGeneration.U)
      response.bits.data.poke("h1122334455667788".U)
      response.valid.poke(true.B)
      response.ready.expect(true.B)
      dut.clock.step()
      response.valid.poke(false.B)

      var resultCycles = 0
      while (!dut.io.iex.loadResult(0).valid.peek().litToBoolean &&
          resultCycles < 192) {
        dut.clock.step()
        resultCycles += 1
      }
      assert(resultCycles < 192,
        "exact response did not retry the cache miss to completion")
      dut.io.iex.loadResult(0).bits.data.expect("h1122334455667788".U)
    }
  }

  test("translated device loads route to the device lower-memory class") {
    val p = SimulationParamProfiles.W4
    val virtualAddress = BigInt("8000000000004000", 16)
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      pokeLoad(dut, virtualAddress, transaction = 12)

      val request = dut.io.memoryRequest(0)
      while (!request.valid.peek().litToBoolean) dut.clock.step()
      val translationIdentity = request.bits.identity.peek()
      dut.clock.step()
      val pageNumberWidth = p.physicalAddressWidth - 12
      val devicePpn = BigInt(0xff) << (pageNumberWidth - 8)
      val response = dut.io.memoryResponse(0)
      response.bits.poke(0.U.asTypeOf(response.bits))
      response.bits.identity.poke(translationIdentity)
      response.bits.data.poke(devicePpn.U)
      response.valid.poke(true.B)
      dut.clock.step()
      response.valid.poke(false.B)

      while (!dut.io.iex.loadAddress(0).ready.peek().litToBoolean)
        dut.clock.step()
      dut.clock.step()
      dut.io.iex.loadAddress(0).valid.poke(false.B)
      var missCycles = 0
      while (!request.valid.peek().litToBoolean && missCycles < 32) {
        dut.clock.step()
        missCycles += 1
      }
      assert(missCycles < 32, "translated device load did not reach lower memory")
      request.bits.accessKind.expect(MemoryAccessKind.Device)
    }
  }

  test("lower transaction recovery handles zero-cycle completion and retains stale generations") {
    val p = SimulationParamProfiles.W4
    simulate(new LSULowerTransactionRecovery(
        p, lanes = 2, entries = 4)) { dut =>
      dut.io.prepareFire.poke(false.B)
      dut.io.applyFire.poke(false.B)
      dut.io.abortFire.poke(false.B)
      dut.io.requestFire.foreach(_.poke(false.B))
      dut.io.requestIdentity.foreach(
        _.poke(0.U.asTypeOf(dut.io.requestIdentity.head)))
      dut.io.responseFire.foreach(_.poke(false.B))
      dut.io.responseIdentity.foreach(
        _.poke(0.U.asTypeOf(dut.io.responseIdentity.head)))
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.requestIdentity(0).value.poke(7.U)
      dut.io.requestIdentity(0).generation.poke(3.U)
      dut.io.responseIdentity(0).poke(dut.io.requestIdentity(0).peek())
      dut.io.requestFire(0).poke(true.B)
      dut.io.responseFire(0).poke(true.B)
      dut.clock.step()
      dut.io.requestFire(0).poke(false.B)
      dut.io.responseFire(0).poke(false.B)
      dut.io.outstandingCount.expect(0.U)
      dut.io.quiescent.expect(true.B)

      dut.io.requestFire(0).poke(true.B)
      dut.clock.step()
      dut.io.requestFire(0).poke(false.B)
      dut.io.outstandingCount.expect(1.U)
      dut.io.responseIdentity(0).value.poke(7.U)
      dut.io.responseIdentity(0).generation.poke(4.U)
      dut.io.responseFire(0).poke(true.B)
      dut.io.staleResponse.expect(true.B)
      dut.clock.step()
      dut.io.responseFire(0).poke(false.B)
      dut.io.outstandingCount.expect(1.U)

      dut.io.responseIdentity(0).generation.poke(3.U)
      dut.io.responseFire(0).poke(true.B)
      dut.clock.step()
      dut.io.responseFire(0).poke(false.B)
      dut.io.outstandingCount.expect(0.U)
      dut.io.quiescent.expect(true.B)
    }
  }

  test("LR SC reservation recovery clears only the exact memory identity") {
    simulate(new ScalarLrScReservationOwner()) { dut =>
      dut.io.enable.poke(true.B)
      dut.io.contextInvalidate.poke(false.B)
      dut.io.flushAll.poke(false.B)
      dut.io.flushValid.poke(false.B)
      dut.io.flushStid.poke(0.U)
      dut.io.flushIdentityValid.poke(false.B)
      dut.io.flushIdentity.poke(0.U.asTypeOf(dut.io.flushIdentity))
      dut.io.lrCompleteValid.poke(false.B)
      dut.io.lrCompleteAccepted.poke(false.B)
      dut.io.lrStid.poke(0.U)
      dut.io.lrLineAddr.poke(0.U)
      dut.io.lrSize.poke(0.U)
      dut.io.lrRawData.poke(0.U)
      dut.io.lrIdentity.poke(0.U.asTypeOf(dut.io.lrIdentity))
      dut.io.scReqValid.poke(false.B)
      dut.io.scReqStid.poke(0.U)
      dut.io.scReqLineAddr.poke(0.U)
      dut.io.scReqSize.poke(0.U)
      dut.io.scReqData.poke(0.U)
      dut.io.scReqIdentity.poke(0.U.asTypeOf(dut.io.scReqIdentity))
      dut.io.scCommitReady.poke(false.B)
      dut.io.committedStoreInvalidateValid.poke(false.B)
      dut.io.committedStoreInvalidateStid.poke(0.U)
      dut.io.committedStoreInvalidateLineAddr.poke(0.U)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.lrCompleteValid.poke(true.B)
      dut.io.lrCompleteAccepted.poke(true.B)
      dut.io.lrStid.poke(0.U)
      dut.io.lrLineAddr.poke(0x5000.U)
      dut.io.lrSize.poke(8.U)
      dut.io.lrIdentity.bid.poke(3.U)
      dut.io.lrIdentity.gid.valid.poke(true.B)
      dut.io.lrIdentity.gid.value.poke(4.U)
      dut.io.lrIdentity.rid.valid.poke(true.B)
      dut.io.lrIdentity.rid.value.poke(5.U)
      dut.io.lrIdentity.lsIdFull.poke(99.U)
      dut.clock.step()
      dut.io.lrCompleteValid.poke(false.B)
      dut.io.lrCompleteAccepted.poke(false.B)
      dut.io.reservationValidByStid(0).expect(true.B)

      dut.io.flushValid.poke(true.B)
      dut.io.flushIdentityValid.poke(true.B)
      dut.io.flushIdentity.poke(dut.io.lrIdentity.peek())
      dut.io.flushIdentity.lsIdFull.poke(100.U)
      dut.clock.step()
      dut.io.reservationValidByStid(0).expect(true.B)

      dut.io.flushIdentity.lsIdFull.poke(99.U)
      dut.clock.step()
      dut.io.flushValid.poke(false.B)
      dut.io.flushIdentityValid.poke(false.B)
      dut.io.reservationValidByStid(0).expect(false.B)
    }
  }

  test("L1D maintenance preserves peer lines and fails closed on dirty line or global invalidation") {
    simulate(new ScalarL1D(
        sets = 2, ways = 2, scbEntries = 2,
        addrWidth = 64, lineBytes = 64)) { dut =>
      dut.io.loadLookupValid.poke(false.B)
      dut.io.loadLookupLineAddr.poke(0.U)
      dut.io.storeLookupValid.poke(false.B)
      dut.io.storeLookupLineAddr.poke(0.U)
      dut.io.grantWriteValid.poke(false.B)
      dut.io.grantWriteLineAddr.poke(0.U)
      dut.io.storeUpdate.poke(0.U.asTypeOf(dut.io.storeUpdate))
      dut.io.refill.poke(0.U.asTypeOf(dut.io.refill))
      dut.io.evictionReady.poke(true.B)
      dut.io.invalidateAll.poke(false.B)
      dut.io.invalidateLineValid.poke(false.B)
      dut.io.invalidateLineAddr.poke(0.U)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      for ((address, data) <- Seq(
          (BigInt(0x1000), BigInt(0x11)),
          (BigInt(0x1040), BigInt(0x22)))) {
        dut.io.refill.valid.poke(true.B)
        dut.io.refill.lineAddr.poke(address.U)
        dut.io.refill.data.poke(data.U)
        dut.io.refill.writable.poke(true.B)
        dut.io.refillReady.expect(true.B)
        dut.clock.step()
      }
      dut.io.refill.valid.poke(false.B)
      dut.io.invalidateLineAddr.poke(0x1000.U)
      dut.io.invalidateLineValid.poke(true.B)
      dut.clock.step()
      dut.io.invalidateLineValid.poke(false.B)

      dut.io.loadLookupValid.poke(true.B)
      dut.io.loadLookupLineAddr.poke(0x1000.U)
      dut.io.loadLookup.readHit.expect(false.B)
      dut.io.loadLookupLineAddr.poke(0x1040.U)
      dut.io.loadLookup.readHit.expect(true.B)
      dut.io.loadLookup.data.expect(0x22.U)

      dut.io.storeUpdate.lineAddr.poke(0x1040.U)
      dut.io.storeUpdate.byteMask.poke(1.U)
      dut.io.storeUpdate.data.poke(0xaa.U)
      dut.io.storeUpdate.valid.poke(true.B)
      dut.clock.step()
      dut.io.storeUpdate.valid.poke(false.B)
      dut.io.dirtyCount.expect(1.U)

      dut.io.invalidateLineAddr.poke(0x1040.U)
      dut.io.invalidateLineValid.poke(true.B)
      dut.io.protocolError.expect(true.B)
      dut.clock.step()
      dut.io.invalidateLineValid.poke(false.B)
      dut.io.loadLookup.readHit.expect(true.B)
      dut.io.dirtyCount.expect(1.U)

      dut.io.invalidateAll.poke(true.B)
      dut.io.protocolError.expect(true.B)
      dut.clock.step()
      dut.io.invalidateAll.poke(false.B)
      dut.io.loadLookup.readHit.expect(true.B)
      dut.io.loadLookup.data.expect(0xaa.U)
      dut.io.dirtyCount.expect(1.U)
    }
  }
}
