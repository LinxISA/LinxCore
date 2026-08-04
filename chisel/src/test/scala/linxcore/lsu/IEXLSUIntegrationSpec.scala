package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.{MemoryAccessAttributes, PhysicalMemoryRegion,
  SimulationParamProfiles}
import linxcore.top.interface.{IEXLSUIO, LSUIO, StoreCommitAuthorizationTxn,
  StoreMemoryClass, StoreMemoryClassifyTxn, StoreReservationTxn}
import org.scalatest.funsuite.AnyFunSuite

class IEXLSUIntegrationSpec extends AnyFunSuite with ChiselSim {
  test("public boundaries carry semantic store reservation commit and classification") {
    val p = SimulationParamProfiles.W4
    val iexLsu = new IEXLSUIO(p)
    val lsu = new LSUIO(p)
    assert(iexLsu.storeReservation.length == p.lsu.storePipes)
    assert(iexLsu.storeReservation.head.bits
      .isInstanceOf[StoreReservationTxn])
    assert(lsu.storeCommit.bits.isInstanceOf[StoreCommitAuthorizationTxn])
    assert(lsu.storeClassify.bits.isInstanceOf[StoreMemoryClassifyTxn])
    assert(StoreMemoryClass.all.toSet == Set(
      StoreMemoryClass.NormalCacheable,
      StoreMemoryClass.NormalNonCacheable,
      StoreMemoryClass.Device,
      StoreMemoryClass.Fault))
  }

  test("public LSU joins reservation STA STD classification and commit by exact identity") {
    val storeAddress = BigInt("7000000000000000", 16)
    val base = SimulationParamProfiles.W4
    val p = base.copy(lsu = base.lsu.copy(physicalMemoryRegions = Seq(
      PhysicalMemoryRegion(storeAddress,
        BigInt("ffff000000000000", 16),
        MemoryAccessAttributes(cacheable = false, device = true)))))
    simulate(new LSU(p)) { dut =>
      dut.io.iex.storeReservation.foreach { port =>
        port.valid.poke(false.B)
        port.bits.poke(0.U.asTypeOf(port.bits))
      }
      dut.io.iex.storeAddress.foreach { port =>
        port.valid.poke(false.B)
        port.bits.poke(0.U.asTypeOf(port.bits))
      }
      dut.io.iex.storeData.foreach { port =>
        port.valid.poke(false.B)
        port.bits.poke(0.U.asTypeOf(port.bits))
      }
      dut.io.iex.loadAddress.foreach { port =>
        port.valid.poke(false.B)
        port.bits.poke(0.U.asTypeOf(port.bits))
      }
      dut.io.iex.loadResult.foreach(_.ready.poke(true.B))
      dut.io.iex.loadReissue.foreach(_.ready.poke(true.B))
      dut.io.iex.loadRebindApply.foreach { port =>
        port.valid.poke(false.B)
        port.bits.poke(0.U.asTypeOf(port.bits))
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
        port.valid.poke(false.B)
        port.bits.poke(0.U.asTypeOf(port.bits))
      }
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.prepare.bits.poke(0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepared.ready.poke(true.B)
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.recovery.apply.bits.poke(0.U.asTypeOf(dut.io.recovery.apply.bits))
      dut.io.recovery.abort.valid.poke(false.B)
      dut.io.recovery.abort.bits.poke(0.U.asTypeOf(dut.io.recovery.abort.bits))
      dut.io.memoryFault.ready.poke(true.B)
      dut.io.maintenance.valid.poke(false.B)
      dut.io.maintenance.bits.poke(0.U.asTypeOf(dut.io.maintenance.bits))
      dut.io.maintenanceResult.ready.poke(true.B)
      dut.io.trace.ready.poke(true.B)

      def pokeRob(rob: linxcore.top.interface.RobIdentity): Unit = {
        rob.peId.poke(1.U)
        rob.stid.poke(0.U)
        rob.ridSlot.poke(2.U)
        rob.ridGeneration.poke(1.U)
        rob.memberIndex.poke(1.U)
        rob.residentGeneration.poke(1.U)
        rob.bid.poke(1.U)
        rob.brobGeneration.poke(1.U)
      }
      def pokeIdentity(identity: linxcore.top.interface.MemoryIdentity): Unit = {
        identity.poke(0.U.asTypeOf(identity))
        pokeRob(identity.rob)
        identity.transaction.value.poke(1.U)
        identity.transaction.generation.poke(1.U)
        identity.lsid.poke(11.U)
      }

      val reserve = dut.io.iex.storeReservation(0)
      reserve.bits.poke(0.U.asTypeOf(reserve.bits))
      reserve.bits.transactionId.poke(7.U)
      pokeRob(reserve.bits.rob)
      reserve.bits.memoryOrder.requestCount.poke(1.U)
      reserve.bits.memoryOrder.firstLsid.poke(11.U)
      reserve.bits.memoryOrder.firstSid.poke(13.U)
      reserve.bits.requestCount.poke(1.U)
      reserve.bits.sizeBytes.poke(8.U)
      reserve.valid.poke(true.B)
      reserve.ready.expect(true.B)
      dut.clock.step()
      reserve.valid.poke(false.B)

      val sta = dut.io.iex.storeAddress(0)
      sta.bits.poke(0.U.asTypeOf(sta.bits))
      pokeIdentity(sta.bits.identity)
      sta.bits.memoryOrder.requestCount.poke(1.U)
      sta.bits.memoryOrder.firstLsid.poke(11.U)
      sta.bits.memoryOrder.firstSid.poke(13.U)
      sta.bits.requestCount.poke(1.U)
      sta.bits.address.poke(storeAddress.U)
      sta.bits.sizeBytes.poke(8.U)
      sta.valid.poke(true.B)
      sta.ready.expect(true.B)
      dut.clock.step()
      sta.valid.poke(false.B)

      val std = dut.io.iex.storeData(1)
      std.bits.poke(0.U.asTypeOf(std.bits))
      pokeIdentity(std.bits.identity)
      std.bits.memoryOrder.requestCount.poke(1.U)
      std.bits.memoryOrder.firstLsid.poke(11.U)
      std.bits.memoryOrder.firstSid.poke(13.U)
      std.bits.requestCount.poke(1.U)
      std.bits.sizeBytes.poke(8.U)
      std.bits.data(0).poke("h1122334455667788".U)
      std.bits.byteMask(0).poke("hff".U)
      std.valid.poke(true.B)
      std.ready.expect(true.B)
      dut.clock.step()
      std.valid.poke(false.B)
      dut.clock.step(2)

      val classify = dut.io.storeClassify
      classify.bits.poke(0.U.asTypeOf(classify.bits))
      pokeRob(classify.bits.rob)
      classify.bits.logicalFirstLsid.poke(11.U)
      classify.bits.logicalFirstStoreId.poke(13.U)
      classify.bits.requestCount.poke(1.U)
      classify.bits.beat.poke(0.U)
      classify.bits.memoryClass.poke(StoreMemoryClass.NormalNonCacheable)
      classify.valid.poke(true.B)
      classify.ready.expect(true.B)
      dut.clock.step()
      classify.valid.poke(false.B)

      val storeMemory = dut.io.memoryRequest(p.lsu.loadPipes)
      storeMemory.ready.poke(false.B)

      val commit = dut.io.storeCommit
      commit.bits.poke(0.U.asTypeOf(commit.bits))
      pokeRob(commit.bits.rob)
      commit.bits.logicalFirstLsid.poke(11.U)
      commit.bits.logicalFirstStoreId.poke(13.U)
      commit.bits.requestCount.poke(1.U)
      commit.bits.beat.poke(0.U)
      commit.valid.poke(true.B)
      commit.ready.expect(true.B)
      dut.clock.step()
      commit.valid.poke(false.B)

      var requestCycles = 0
      while (!storeMemory.valid.peek().litToBoolean && requestCycles < 32) {
        dut.clock.step()
        requestCycles += 1
      }
      assert(requestCycles < 32,
        "a committed non-cacheable store must reach its canonical memory lane")
      storeMemory.bits.command.expect(linxcore.top.interface.MemoryCommand.Write)
      storeMemory.bits.address.expect(storeAddress.U)
      storeMemory.bits.data.expect("h1122334455667788".U)
      val responseId = storeMemory.bits.identity.peek()
      storeMemory.ready.poke(true.B)
      dut.clock.step()

      val storeResponse = dut.io.memoryResponse(p.lsu.loadPipes)
      storeResponse.bits.poke(0.U.asTypeOf(storeResponse.bits))
      storeResponse.bits.identity.poke(responseId)
      storeResponse.valid.poke(true.B)
      while (!storeResponse.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      storeResponse.valid.poke(false.B)
    }
  }
}
