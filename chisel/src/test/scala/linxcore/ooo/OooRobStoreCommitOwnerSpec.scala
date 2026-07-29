package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooRobStoreCommitOwnerSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 2,
    instructionDecodeWidth = 2,
    decodedUopWidth = 2,
    renameWidth = 2,
    dispatchWidth = 2,
    retireGroupWidth = 2,
    storeCommitBufferEntries = 8,
    robGroupsPerStid = 8,
    brobEntriesPerStid = 8,
    pcBufferEntries = 16,
    pcBankCount = 4,
    pcReadPorts = 4,
    pcReadReplicaCount = 2,
    pMapQDepthPerStid = 32,
    tuRetireSourceDepthPerStid = 32)

  private def clear(dut: OooRobStoreCommitOwner): Unit = {
    dut.io.commitPrepare.valid.poke(false.B)
    dut.io.commitPrepare.bits.poke(
      0.U.asTypeOf(dut.io.commitPrepare.bits))
    dut.io.commitFire.poke(false.B)
    dut.io.storeCommit.ready.poke(false.B)
  }

  private def pokeState(
      state: OooMemoryIdState,
      lsid: BigInt,
      loadId: BigInt,
      storeId: BigInt): Unit = {
    state.lsid.poke(lsid.U)
    state.loadId.poke(loadId.U)
    state.storeId.poke(storeId.U)
  }

  private def pokeGroup(
      dut: OooRobStoreCommitOwner,
      groupIndex: Int,
      ridSlot: Int,
      ridGeneration: Int,
      bid: Int,
      brobGeneration: Int,
      residentGeneration: Int,
      memberCount: Int,
      before: (BigInt, BigInt, BigInt),
      after: (BigInt, BigInt, BigInt)): Unit = {
    val group = dut.io.commitPrepare.bits.groups(groupIndex)
    group.valid.poke(true.B)
    group.key.valid.poke(true.B)
    group.key.peId.poke(3.U)
    group.key.stid.poke(1.U)
    group.key.ridSlot.poke(ridSlot.U)
    group.key.ridGeneration.poke(ridGeneration.U)
    group.brob.valid.poke(true.B)
    group.brob.bid.valid.poke(true.B)
    group.brob.bid.value.poke(bid.U)
    group.brob.generation.poke(brobGeneration.U)
    group.residentGeneration.poke(residentGeneration.U)
    group.physicalMemberCount.poke(memberCount.U)
    group.completedMembers.poke(((BigInt(1) << memberCount) - 1).U)
    group.logicalUopMask.poke(1.U)
    group.logicalMemberBase(0).poke(0.U)
    group.logicalMemberCount(0).poke(memberCount.U)
    group.memoryOrderValid.poke(true.B)
    pokeState(group.memoryBefore, before._1, before._2, before._3)
    pokeState(group.memoryAfter, after._1, after._2, after._3)
    pokeState(group.logicalMemoryAfter(0), after._1, after._2, after._3)
  }

  private def pokeScalarPairBatch(dut: OooRobStoreCommitOwner): Unit = {
    val batch = dut.io.commitPrepare.bits
    batch.poke(0.U.asTypeOf(batch))
    batch.release.firstGroup.valid.poke(true.B)
    batch.release.firstGroup.peId.poke(3.U)
    batch.release.firstGroup.stid.poke(1.U)
    batch.release.firstGroup.ridSlot.poke(6.U)
    batch.release.firstGroup.ridGeneration.poke(4.U)
    batch.release.groupCount.poke(2.U)
    pokeGroup(dut, groupIndex = 0, ridSlot = 6, ridGeneration = 4,
      bid = 5, brobGeneration = 2, residentGeneration = 9,
      memberCount = 1,
      before = (10, 4, 7), after = (11, 4, 8))
    pokeGroup(dut, groupIndex = 1, ridSlot = 7, ridGeneration = 4,
      bid = 6, brobGeneration = 2, residentGeneration = 10,
      memberCount = 2,
      before = (11, 4, 8), after = (13, 4, 10))
    dut.io.commitPrepare.valid.poke(true.B)
  }

  test("atomically captures scalar and pair stores then preserves exact beat order") {
    simulate(new OooRobStoreCommitOwner(p)) { dut =>
      clear(dut)
      dut.clock.step()

      pokeScalarPairBatch(dut)
      dut.io.commitStartReady.expect(true.B)
      dut.io.preparedTokenCount.expect(3.U)
      dut.io.commitFire.poke(true.B)
      dut.clock.step()
      dut.io.commitFire.poke(false.B)
      dut.io.commitPrepare.valid.poke(false.B)

      dut.io.used.expect(3.U)
      dut.io.storeCommit.valid.expect(true.B)
      dut.io.storeCommit.bits.logicalFirstLsid.expect(10.U)
      dut.io.storeCommit.bits.logicalFirstStoreId.expect(7.U)
      dut.io.storeCommit.bits.logicalRequestCount.expect(1.U)
      dut.io.storeCommit.bits.logicalBeat.expect(0.U)
      dut.io.storeCommit.bits.exactOwner.ridSlot.expect(6.U)

      val heldFirstLsid =
        dut.io.storeCommit.bits.logicalFirstLsid.peek().litValue
      val heldFirstStoreId =
        dut.io.storeCommit.bits.logicalFirstStoreId.peek().litValue
      val heldOwner =
        dut.io.storeCommit.bits.exactOwner.nativeBid.peek().litValue
      dut.clock.step(2)
      assert(dut.io.storeCommit.bits.logicalFirstLsid.peek().litValue ==
        heldFirstLsid)
      assert(dut.io.storeCommit.bits.logicalFirstStoreId.peek().litValue ==
        heldFirstStoreId)
      assert(dut.io.storeCommit.bits.exactOwner.nativeBid.peek().litValue ==
        heldOwner)

      dut.io.storeCommit.ready.poke(true.B)
      dut.clock.step()
      dut.io.storeCommit.bits.logicalFirstLsid.expect(11.U)
      dut.io.storeCommit.bits.logicalFirstStoreId.expect(8.U)
      dut.io.storeCommit.bits.logicalRequestCount.expect(2.U)
      dut.io.storeCommit.bits.logicalBeat.expect(0.U)
      dut.io.storeCommit.bits.exactOwner.ridSlot.expect(7.U)
      dut.clock.step()
      dut.io.storeCommit.bits.logicalBeat.expect(1.U)
      dut.io.storeCommit.bits.logicalFirstLsid.expect(11.U)
      dut.clock.step()
      dut.io.storeCommit.valid.expect(false.B)
      dut.io.used.expect(0.U)
    }
  }

  test("rejects a broken cross-group memory chain without mutating the token buffer") {
    simulate(new OooRobStoreCommitOwner(p)) { dut =>
      clear(dut)
      dut.clock.step()
      pokeScalarPairBatch(dut)
      pokeState(dut.io.commitPrepare.bits.groups(1).memoryBefore,
        lsid = 12, loadId = 4, storeId = 8)

      dut.io.commitStartReady.expect(false.B)
      dut.io.commitRejected.valid.expect(true.B)
      dut.io.used.expect(0.U)
      dut.clock.step(2)
      dut.io.storeCommit.valid.expect(false.B)
    }
  }

  test("reconstructs a two-beat store across full-serial wrap") {
    val wrapP = p.copy(lsidWidth = 32)
    simulate(new OooRobStoreCommitOwner(wrapP)) { dut =>
      clear(dut)
      dut.clock.step()
      val batch = dut.io.commitPrepare.bits
      batch.poke(0.U.asTypeOf(batch))
      batch.release.firstGroup.valid.poke(true.B)
      batch.release.firstGroup.peId.poke(3.U)
      batch.release.firstGroup.stid.poke(1.U)
      batch.release.firstGroup.ridSlot.poke(0.U)
      batch.release.firstGroup.ridGeneration.poke(5.U)
      batch.release.groupCount.poke(1.U)
      val maxMinusOne = (BigInt(1) << 32) - 1
      pokeGroup(dut, groupIndex = 0, ridSlot = 0, ridGeneration = 5,
        bid = 7, brobGeneration = 3, residentGeneration = 11,
        memberCount = 2,
        before = (maxMinusOne, 2, maxMinusOne),
        after = (1, 2, 1))
      dut.io.commitPrepare.valid.poke(true.B)
      dut.io.commitStartReady.expect(true.B)
      dut.io.preparedTokenCount.expect(2.U)
      dut.io.commitFire.poke(true.B)
      dut.clock.step()
      dut.io.commitFire.poke(false.B)
      dut.io.commitPrepare.valid.poke(false.B)
      dut.io.storeCommit.ready.poke(true.B)
      dut.io.storeCommit.bits.logicalFirstLsid.expect(maxMinusOne.U)
      dut.io.storeCommit.bits.logicalRequestCount.expect(2.U)
      dut.io.storeCommit.bits.logicalBeat.expect(0.U)
      dut.clock.step()
      dut.io.storeCommit.bits.logicalBeat.expect(1.U)
      dut.clock.step()
      dut.io.storeCommit.valid.expect(false.B)
    }
  }
}
