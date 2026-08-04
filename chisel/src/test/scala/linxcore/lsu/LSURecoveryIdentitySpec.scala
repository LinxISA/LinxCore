package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.SimulationParamProfiles
import linxcore.top.interface.{MemoryIdentity, MemoryTransactionIdentity,
  RecoveryCause, RecoveryPhase}
import org.scalatest.funsuite.AnyFunSuite

class LSURecoveryIdentitySpec extends AnyFunSuite with ChiselSim {
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
    dut.io.storeClassify.valid.poke(false.B)
    dut.io.storeClassify.bits.poke(0.U.asTypeOf(dut.io.storeClassify.bits))
    dut.io.loadReissueRequest.valid.poke(false.B)
    dut.io.loadReissueRequest.bits.poke(
      0.U.asTypeOf(dut.io.loadReissueRequest.bits))
    dut.io.memoryRequest.foreach(_.ready.poke(false.B))
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

  private def issue(dut: LSU, lane: Int, member: Int, transaction: Int,
      lsid: BigInt): (MemoryTransactionIdentity, MemoryIdentity) = {
    val port = dut.io.iex.loadAddress(lane)
    port.bits.poke(0.U.asTypeOf(port.bits))
    port.bits.identity.rob.peId.poke(1.U)
    port.bits.identity.rob.stid.poke(0.U)
    port.bits.identity.rob.ridSlot.poke(2.U)
    port.bits.identity.rob.ridGeneration.poke(3.U)
    port.bits.identity.rob.memberIndex.poke(member.U)
    port.bits.identity.rob.residentGeneration.poke(4.U)
    port.bits.identity.rob.bid.poke(1.U)
    port.bits.identity.rob.brobGeneration.poke(5.U)
    port.bits.identity.transaction.value.poke(transaction.U)
    port.bits.identity.transaction.generation.poke(6.U)
    port.bits.identity.lsid.poke(lsid.U)
    port.bits.identity.attemptGeneration.poke(1.U)
    port.bits.identity.pipeId.poke(lane.U)
    port.bits.address.poke((0x4000 + member * 8).U)
    port.bits.sizeBytes.poke(8.U)
    port.bits.destination.valid.poke(true.B)
    port.bits.destination.atag.poke((2 + member).U)
    port.bits.destination.ptag.poke((12 + member).U)
    port.bits.destination.previousPtag.poke((22 + member).U)
    port.bits.destinationRelativeIndex.poke((2 + member).U)
    port.valid.poke(true.B)
    port.ready.expect(true.B)
    val allocation = dut.io.iex.loadAllocation(lane).bits.allocationId.peek()
    val identity = port.bits.identity.peek()
    dut.clock.step()
    port.valid.poke(false.B)
    (allocation, identity)
  }

  test("recovery fails closed without an exact full LSID and preserves an older same-group row across wrap") {
    val p = SimulationParamProfiles.W4
    simulate(new LSU(p)) { dut =>
      initialize(dut)

      val prepare = dut.io.recovery.prepare
      prepare.bits.poke(0.U.asTypeOf(prepare.bits))
      prepare.bits.transactionId.poke(0x71.U)
      prepare.bits.phase.poke(RecoveryPhase.Prepare)
      prepare.bits.cause.poke(RecoveryCause.Branch)
      prepare.bits.trigger.peId.poke(1.U)
      prepare.bits.trigger.stid.poke(0.U)
      prepare.bits.firstKilledValid.poke(true.B)
      prepare.bits.firstKilled.poke(prepare.bits.trigger.peek())
      prepare.bits.lastKilled.poke(prepare.bits.trigger.peek())
      prepare.bits.killedGroupCount.poke(1.U)
      prepare.bits.killedMemberCount.poke(1.U)
      prepare.valid.poke(true.B)
      prepare.ready.expect(false.B,
        "an unresolved first-killed boundary must not acknowledge prepare")
      dut.clock.step()
      dut.io.recovery.prepared.valid.expect(false.B)
      prepare.valid.poke(false.B)

      val lsidModulus = BigInt(1) << p.lsidWidth
      val (olderAllocation, olderIdentity) =
        issue(dut, lane = 0, member = 0, transaction = 0x31,
          lsid = lsidModulus - 2)
      val (killedAllocation, killedIdentity) =
        issue(dut, lane = 1, member = 1, transaction = 0x32, lsid = 1)

      prepare.bits.trigger.poke(killedIdentity.rob)
      prepare.bits.firstKilled.poke(killedIdentity.rob)
      prepare.bits.lastKilled.poke(killedIdentity.rob)
      prepare.valid.poke(true.B)
      prepare.ready.expect(true.B)
      dut.clock.step()
      prepare.valid.poke(false.B)
      dut.io.recovery.prepared.valid.expect(true.B)

      dut.io.recovery.apply.bits.poke(dut.io.recovery.prepared.bits.peek())
      dut.io.recovery.apply.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.recovery.apply.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)

      val replay = dut.io.loadReissueRequest
      replay.bits.poke(0.U.asTypeOf(replay.bits))
      replay.bits.allocationId.poke(killedAllocation)
      replay.bits.currentIdentity.poke(killedIdentity)
      replay.bits.address.poke(0x4008.U)
      replay.valid.poke(true.B)
      replay.ready.expect(true.B)
      dut.io.iex.loadReissue.foreach(_.valid.expect(false.B))
      dut.clock.step()

      replay.bits.allocationId.poke(olderAllocation)
      replay.bits.currentIdentity.poke(olderIdentity)
      replay.bits.address.poke(0x4000.U)
      replay.ready.expect(true.B)
      dut.io.iex.loadReissue(0).valid.expect(true.B,
        "the older same-group row before LSID wrap must survive")
      dut.clock.step()
      replay.valid.poke(false.B)
    }
  }
}
