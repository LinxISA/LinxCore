package linxcore.iex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

import linxcore.common.{CoreParams, DestinationKind, ScalarLsuParams}
import linxcore.ooo._
import linxcore.top.interface.{RecoveryPhase, RecoveryPlan}

class LoadIexIssuePipelineSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 4,
    instructionDecodeWidth = 2,
    decodedUopWidth = 2,
    renameWidth = 2,
    dispatchWidth = 2,
    retireGroupWidth = 2,
    robGroupsPerStid = 8,
    robBankCount = 2,
    robRecoveryScanGroupsPerCycle = 2,
    robNonFlushScanGroupsPerCycle = 2,
    brobEntriesPerStid = 16,
    pcBufferEntries = 16,
    pcBankCount = 4,
    pcRecoveryScanGroupsPerCycle = 2,
    pcWritePorts = 3,
    iqBankCount = 2,
    iqEntriesPerBank = 4,
    iqFreeSelectLeafEntries = 2,
    tuRetireSourceDepthPerStid = 16,
    lsidWidth = 40)

  private val core = CoreParams(
    robEntries = 8,
    lsidWidth = 40,
    scalarLsu = ScalarLsuParams(
      stqEntries = 4,
      liqEntries = 4,
      loadReturnPipeCount = 3,
      stidCount = 4))

  test("derives the W4 two-lane issue geometry from the canonical LSU profile") {
    val twoPipeCore = core.copy(
      scalarLsu = core.scalarLsu.copy(loadReturnPipeCount = 2))

    simulate(new LoadIexIssuePipeline(p, twoPipeCore)) { dut =>
      assert(dut.io.agu.length == 2)
      assert(dut.io.rejected.length == 2)
    }
  }

  private def clear(dut: LoadIexIssuePipeline): Unit = {
    dut.io.agu.foreach { lane =>
      lane.valid.poke(false.B)
      lane.bits.poke(0.U.asTypeOf(lane.bits))
    }
    dut.io.alloc.ready.poke(false.B)
    dut.io.recovery.prepare.valid.poke(false.B)
    dut.io.recovery.prepare.bits.poke(
      0.U.asTypeOf(dut.io.recovery.prepare.bits))
    dut.io.recovery.prepared.ready.poke(false.B)
    dut.io.recovery.apply.valid.poke(false.B)
    dut.io.recovery.apply.bits.poke(
      0.U.asTypeOf(dut.io.recovery.apply.bits))
    dut.io.recovery.abort.valid.poke(false.B)
    dut.io.recovery.abort.bits.poke(
      0.U.asTypeOf(dut.io.recovery.abort.bits))
  }

  private def pokeRecoveryPlan(
      plan: RecoveryPlan,
      phase: RecoveryPhase.Type,
      transactionId: Int,
      stid: Int,
      firstRid: Int,
      lastRid: Int): Unit = {
    plan.poke(0.U.asTypeOf(plan))
    plan.transactionId.poke(transactionId.U)
    plan.phase.poke(phase)
    plan.trigger.peId.poke(3.U)
    plan.trigger.stid.poke(stid.U)
    plan.trigger.ridSlot.poke(firstRid.U)
    plan.trigger.ridGeneration.poke(5.U)
    plan.trigger.memberIndex.poke(1.U)
    plan.firstKilledValid.poke(true.B)
    plan.firstKilled.peId.poke(3.U)
    plan.firstKilled.stid.poke(stid.U)
    plan.firstKilled.ridSlot.poke(firstRid.U)
    plan.firstKilled.ridGeneration.poke(5.U)
    plan.firstKilled.memberIndex.poke(1.U)
    plan.lastKilled.peId.poke(3.U)
    plan.lastKilled.stid.poke(stid.U)
    plan.lastKilled.ridSlot.poke(lastRid.U)
    plan.lastKilled.ridGeneration.poke(5.U)
    plan.lastKilled.memberIndex.poke(1.U)
    plan.killedGroupCount.poke((lastRid - firstRid + 1).U)
    plan.killedMemberCount.poke((lastRid - firstRid + 1).U)
  }

  private def pokeRequest(
      dut: LoadIexIssuePipeline,
      lane: Int,
      ridSlot: Int,
      nativeBid: Int,
      firstLsid: BigInt,
      firstLoadId: BigInt,
      storeTail: BigInt,
      transactionValue: BigInt = 0,
      transactionGeneration: BigInt = 0,
      attemptGeneration: BigInt = 1,
      pcValid: Boolean = true,
      stid: Int = -1): Unit = {
    val input = dut.io.agu(lane)
    val requestStid = if (stid >= 0) stid else lane + 1
    val youngestStoreLsid = if (storeTail != 0) firstLsid - 3 else BigInt(0)
    input.bits.poke(0.U.asTypeOf(input.bits))
    input.valid.poke(true.B)
    input.bits.pcValid.poke(pcValid.B)
    input.bits.pc.poke((0x4000 + ridSlot * 4).U)
    input.bits.address.poke((0x8000 + ridSlot * 8).U)
    input.bits.accessBytes.poke(8.U)
    input.bits.signExtend.poke(true.B)

    val execute = input.bits.execute
    execute.ownerClass.poke(OooUopClass.Agu)
    val row = execute.i2.row
    row.schedule.valid.poke(true.B)
    row.schedule.peId.poke(3.U)
    row.schedule.stid.poke(requestStid.U)
    row.schedule.epoch.poke(7.U)
    row.schedule.memoryTransactionValid.poke(true.B)
    row.schedule.memoryTransaction.value.poke(transactionValue.U)
    row.schedule.memoryTransaction.generation.poke(transactionGeneration.U)
    row.schedule.initialLoadAttemptValid.poke(true.B)
    row.schedule.initialLoadAttemptGeneration.poke(attemptGeneration.U)
    row.schedule.member.group.valid.poke(true.B)
    row.schedule.member.group.peId.poke(3.U)
    row.schedule.member.group.stid.poke(requestStid.U)
    row.schedule.member.group.ridSlot.poke(ridSlot.U)
    row.schedule.member.group.ridGeneration.poke(5.U)
    row.schedule.member.bid.valid.poke(true.B)
    row.schedule.member.bid.value.poke(nativeBid.U)
    row.schedule.member.brobGeneration.poke(9.U)
    row.schedule.member.memberIndex.poke(1.U)
    row.schedule.member.residentGeneration.poke(11.U)
    row.schedule.reservation.valid.poke(true.B)
    row.schedule.reservation.uopClass.poke(OooUopClass.Agu)

    row.payload.opcode.poke(101.U)
    row.payload.recipe.valid.poke(true.B)
    row.payload.recipe.opcode.poke(101.U)
    row.payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    row.payload.recipe.recipeKind.poke(OooOpcodeRecipeKind.ScalarLoad.U)
    row.payload.recipe.dispatchClass.poke(OooDispatchClass.Agu.U)
    row.payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
    row.payload.recipe.memoryRequestCount.poke(1.U)
    row.payload.memory.valid.poke(true.B)
    row.payload.memory.isLoad.poke(true.B)
    row.payload.memory.isStore.poke(false.B)
    row.payload.memoryOrder.valid.poke(true.B)
    row.payload.memoryOrder.memoryValid.poke(true.B)
    row.payload.memoryOrder.isLoad.poke(true.B)
    row.payload.memoryOrder.isStore.poke(false.B)
    row.payload.memoryOrder.requestCount.poke(1.U)
    row.payload.memoryOrder.firstLsid.poke(firstLsid.U)
    row.payload.memoryOrder.firstTypeId.poke(firstLoadId.U)
    row.payload.memoryOrder.before.lsid.poke(firstLsid.U)
    row.payload.memoryOrder.before.loadId.poke(firstLoadId.U)
    row.payload.memoryOrder.before.storeId.poke(storeTail.U)
    row.payload.memoryOrder.before.youngestStoreLsidValid
      .poke((storeTail != 0).B)
    row.payload.memoryOrder.before.youngestStoreLsid
      .poke(youngestStoreLsid.U)
    row.payload.memoryOrder.after.lsid.poke((firstLsid + 1).U)
    row.payload.memoryOrder.after.loadId.poke((firstLoadId + 1).U)
    row.payload.memoryOrder.after.storeId.poke(storeTail.U)
    row.payload.memoryOrder.after.youngestStoreLsidValid
      .poke((storeTail != 0).B)
    row.payload.memoryOrder.after.youngestStoreLsid
      .poke(youngestStoreLsid.U)

    row.schedule.destinations(0).valid.poke(true.B)
    row.schedule.destinations(0).kind.poke(DestinationKind.Gpr)
    row.schedule.destinations(0).atag.poke(6.U)
    row.schedule.destinations(0).ptag.poke((31 + lane).U)
    row.schedule.destinations(0).ptagGeneration.poke(4.U)
    row.payload.previousPDestinations(0).valid.poke(true.B)
    row.payload.previousPDestinations(0).ptag.poke((21 + lane).U)
    row.payload.previousPDestinations(0).ptagGeneration.poke(3.U)
    input.bits.destination.valid.poke(true.B)
    input.bits.destination.kind.poke(DestinationKind.Gpr)
    input.bits.destination.atag.poke(6.U)
    input.bits.destination.ptag.poke((31 + lane).U)
    input.bits.destination.ptagGeneration.poke(4.U)
  }

  test("maps the exact OOO producer and 40-bit memory order into one LIQ alloc") {
    simulate(new LoadIexIssuePipeline(p, core)) { dut =>
      clear(dut)
      val lsid = BigInt("100000001", 16)
      pokeRequest(dut, lane = 2, ridSlot = 6, nativeBid = 13,
        firstLsid = lsid, firstLoadId = 9, storeTail = 4,
        transactionValue = 0x1234, transactionGeneration = 7,
        attemptGeneration = 9)

      dut.io.alloc.valid.expect(true.B)
      dut.io.agu(2).ready.expect(false.B)
      dut.io.accepted.valid.expect(false.B)
      dut.io.alloc.bits.attempt.producer.nativeBid.expect(13.U)
      dut.io.alloc.bits.attempt.producer.ridSlot.expect(6.U)
      dut.io.alloc.bits.attempt.producer.ridGeneration.expect(5.U)
      dut.io.alloc.bits.attempt.producer.memberIndex.expect(1.U)
      dut.io.alloc.bits.attempt.producer.residentGeneration.expect(11.U)
      dut.io.alloc.bits.attempt.generation.expect(9.U)
      dut.io.accepted.bits.load.transaction.value.expect(0x1234.U)
      dut.io.accepted.bits.load.transaction.generation.expect(7.U)
      dut.clock.step(2)
      dut.io.alloc.valid.expect(true.B)
      dut.io.alloc.bits.attempt.generation.expect(9.U)
      dut.io.accepted.bits.load.transaction.value.expect(0x1234.U)
      dut.io.accepted.bits.load.transaction.generation.expect(7.U)
      dut.io.alloc.bits.bid.valid.expect(true.B)
      dut.io.alloc.bits.bid.value.expect(5.U)
      dut.io.alloc.bits.bid.wrap.expect(true.B)
      dut.io.alloc.bits.gid.valid.expect(true.B)
      dut.io.alloc.bits.gid.value.expect(6.U)
      dut.io.alloc.bits.gid.wrap.expect(true.B)
      dut.io.alloc.bits.rid.valid.expect(true.B)
      dut.io.alloc.bits.rid.value.expect(6.U)
      dut.io.alloc.bits.rid.wrap.expect(true.B)
      dut.io.alloc.bits.loadLsId.valid.expect(true.B)
      dut.io.alloc.bits.loadLsId.value.expect(1.U)
      dut.io.alloc.bits.loadLsId.wrap.expect(false.B)
      dut.io.alloc.bits.loadLsIdFull.expect(lsid.U)
      dut.io.alloc.bits.loadLsIdFullValid.expect(true.B)
      dut.io.alloc.bits.youngestStoreId.valid.expect(true.B)
      dut.io.alloc.bits.youngestStoreId.value.expect(3.U)
      dut.io.alloc.bits.youngestStoreId.wrap.expect(false.B)
      dut.io.alloc.bits.youngestStoreLsId.valid.expect(true.B)
      dut.io.alloc.bits.youngestStoreLsId.value.expect(6.U)
      dut.io.alloc.bits.youngestStoreLsId.wrap.expect(true.B)
      dut.io.alloc.bits.youngestStoreLsIdFull.expect((lsid - 3).U)
      dut.io.alloc.bits.youngestStoreLsIdFullValid.expect(true.B)
      dut.io.alloc.bits.pc.expect((0x4000 + 6 * 4).U)
      dut.io.alloc.bits.addr.expect((0x8000 + 6 * 8).U)
      dut.io.alloc.bits.returnPipeIndex.expect(2.U)
      dut.io.alloc.bits.dst.physTag.expect(33.U)
      dut.io.alloc.bits.dst.oldPhysTag.expect(23.U)
      dut.io.alloc.bits.specWakeup.expect(false.B)

      dut.io.alloc.ready.poke(true.B)
      dut.io.agu(2).ready.expect(true.B)
      dut.io.accepted.valid.expect(true.B)
      dut.io.accepted.bits.lane.expect(2.U)
      dut.io.accepted.bits.load.generation.expect(9.U)
      dut.clock.step()

      dut.io.agu(2).valid.poke(false.B)
      pokeRequest(dut, lane = 2, ridSlot = 7, nativeBid = 14,
        firstLsid = lsid + 1, firstLoadId = 10, storeTail = 4,
        transactionValue = 0x1235, transactionGeneration = 7,
        attemptGeneration = 10)
      dut.io.alloc.bits.attempt.generation.expect(10.U)
      dut.io.accepted.bits.load.transaction.value.expect(0x1235.U)
      dut.io.accepted.bits.load.transaction.generation.expect(7.U)
    }
  }

  test("maps a compact-load T destination into the canonical LIQ identity") {
    val identityCore = core.copy(robEntries = math.max(
      p.robIdentityGroupsPerStid, 1 << (p.nativeBidWidth - 1)))
    simulate(new LoadIexIssuePipeline(p, identityCore)) { dut =>
      clear(dut)
      pokeRequest(dut, lane = 0, ridSlot = 2, nativeBid = 4,
        firstLsid = 7, firstLoadId = 5, storeTail = 0)
      val row = dut.io.agu(0).bits.execute.i2.row
      row.schedule.destinations(0).kind.poke(DestinationKind.T)
      row.schedule.destinations(0).atag.poke(31.U)
      row.schedule.destinations(0).relativeIndex.poke(2.U)
      row.schedule.destinations(0).localTag.poke(3.U)
      row.schedule.destinations(0).localSequence.valid.poke(true.B)
      row.schedule.destinations(0).localSequence.index.poke(9.U)
      row.schedule.destinations(0).localSequence.generation.poke(4.U)
      row.payload.previousPDestinations(0).valid.poke(false.B)
      dut.io.agu(0).bits.destination.poke(
        row.schedule.destinations(0).peek())
      dut.io.alloc.ready.poke(true.B)

      dut.io.alloc.valid.expect(true.B)
      dut.io.agu(0).ready.expect(true.B)
      dut.io.alloc.bits.dst.kind.expect(DestinationKind.T)
      dut.io.alloc.bits.dst.archTag.expect(31.U)
      dut.io.alloc.bits.dst.relTag.expect(2.U)
      dut.io.alloc.bits.dst.physTag.expect(3.U)
      dut.io.alloc.bits.dst.oldPhysTag.expect(0.U)
    }
  }

  test("rejects a load without the retained IEX transaction or initial attempt") {
    simulate(new LoadIexIssuePipeline(p, core)) { dut =>
      clear(dut)
      pokeRequest(dut, lane = 0, ridSlot = 1, nativeBid = 2,
        firstLsid = 1, firstLoadId = 1, storeTail = 0)
      dut.io.alloc.ready.poke(true.B)

      dut.io.agu(0).bits.execute.i2.row.schedule.memoryTransactionValid
        .poke(false.B)
      dut.io.alloc.valid.expect(false.B)
      dut.io.rejected(0).valid.expect(true.B)
      dut.io.rejected(0).bits.identityExact.expect(false.B)

      dut.io.agu(0).bits.execute.i2.row.schedule.memoryTransactionValid
        .poke(true.B)
      dut.io.agu(0).bits.execute.i2.row.schedule.initialLoadAttemptValid
        .poke(false.B)
      dut.io.alloc.valid.expect(false.B)
      dut.io.rejected(0).valid.expect(true.B)
      dut.io.rejected(0).bits.loadShapeExact.expect(false.B)
    }
  }

  test("arbitrates three retained AGUs fairly without adding residency") {
    simulate(new LoadIexIssuePipeline(p, core)) { dut =>
      clear(dut)
      for (lane <- 0 until 3) {
        pokeRequest(dut, lane, ridSlot = lane + 1, nativeBid = lane + 3,
          firstLsid = 20 + lane, firstLoadId = 10 + lane, storeTail = 0,
          transactionValue = lane, attemptGeneration = lane + 1)
      }
      dut.io.alloc.ready.poke(true.B)

      var accepted = Set.empty[Int]
      for (_ <- 0 until 3) {
        dut.io.accepted.valid.expect(true.B)
        val lane = dut.io.accepted.bits.lane.peek().litValue.toInt
        assert(!accepted.contains(lane), s"lane $lane granted twice")
        accepted += lane
        dut.io.alloc.bits.returnPipeIndex.expect(lane.U)
        dut.clock.step()
        dut.io.agu(lane).valid.poke(false.B)
      }
      assert(accepted == Set(0, 1, 2))
      dut.io.alloc.valid.expect(false.B)
    }
  }

  test("fails closed for missing PC") {
    simulate(new LoadIexIssuePipeline(p, core)) { dut =>
      clear(dut)
      pokeRequest(dut, lane = 0, ridSlot = 1, nativeBid = 2,
        firstLsid = 1, firstLoadId = 1, storeTail = 0, pcValid = false)
      dut.io.alloc.ready.poke(true.B)
      dut.io.alloc.valid.expect(false.B)
      dut.io.agu(0).ready.expect(false.B)
      dut.io.rejected(0).valid.expect(true.B)
      dut.io.rejected(0).bits.pcExact.expect(false.B)

      pokeRequest(dut, lane = 0, ridSlot = 1, nativeBid = 2,
        firstLsid = 1, firstLoadId = 1, storeTail = 0)
      dut.io.alloc.valid.expect(true.B)
      dut.io.alloc.bits.attempt.generation.expect(1.U)
      dut.clock.step()

      dut.io.agu(0).valid.poke(false.B)
      pokeRequest(dut, lane = 0, ridSlot = 2, nativeBid = 3,
        firstLsid = 2, firstLoadId = 2, storeTail = 0,
        transactionValue = 1, attemptGeneration = 2)
      dut.io.alloc.bits.attempt.generation.expect(2.U)
    }
  }

  test("prepares without mutation fences only the target STID and lets peers progress") {
    simulate(new LoadIexIssuePipeline(p, core)) { dut =>
      clear(dut)
      pokeRequest(dut, lane = 0, ridSlot = 2, nativeBid = 4,
        firstLsid = 9, firstLoadId = 3, storeTail = 0)
      pokeRequest(dut, lane = 1, ridSlot = 4, nativeBid = 6,
        firstLsid = 11, firstLoadId = 5, storeTail = 0,
        transactionValue = 1, attemptGeneration = 2)
      dut.io.alloc.ready.poke(true.B)
      pokeRecoveryPlan(dut.io.recovery.prepare.bits, RecoveryPhase.Prepare,
        transactionId = 17, stid = 1, firstRid = 2, lastRid = 3)
      dut.io.recovery.prepare.valid.poke(true.B)

      dut.io.recovery.prepare.ready.expect(true.B)
      dut.io.agu(0).ready.expect(false.B)
      dut.io.alloc.valid.expect(true.B)
      dut.io.accepted.bits.lane.expect(1.U)
      dut.io.agu(1).ready.expect(true.B)
      dut.clock.step()

      dut.io.agu(1).valid.poke(false.B)
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.prepared.valid.expect(true.B)
      dut.io.recovery.prepared.bits.transactionId.expect(17.U)
      dut.io.recovery.prepared.bits.phase.expect(RecoveryPhase.Prepare)
      dut.io.agu(0).ready.expect(false.B)

      pokeRecoveryPlan(dut.io.recovery.abort.bits, RecoveryPhase.Abort,
        transactionId = 17, stid = 1, firstRid = 2, lastRid = 3)
      dut.io.recovery.abort.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.abort.valid.poke(false.B)

      dut.io.alloc.valid.expect(true.B)
      dut.io.agu(0).ready.expect(true.B)
      // IEX allocated both identities before this bridge. Prepare and abort
      // preserve the target request's original initial attempt exactly.
      dut.io.alloc.bits.attempt.generation.expect(1.U)
    }
  }

  test("Apply drains only an exact suffix member without allocating or rewinding generation") {
    simulate(new LoadIexIssuePipeline(p, core)) { dut =>
      clear(dut)
      pokeRequest(dut, lane = 0, ridSlot = 2, nativeBid = 4,
        firstLsid = 9, firstLoadId = 3, storeTail = 0, stid = 2)
      pokeRequest(dut, lane = 1, ridSlot = 4, nativeBid = 6,
        firstLsid = 10, firstLoadId = 4, storeTail = 0,
        transactionValue = 1, attemptGeneration = 2, stid = 2)
      dut.io.alloc.ready.poke(true.B)
      pokeRecoveryPlan(dut.io.recovery.prepare.bits, RecoveryPhase.Prepare,
        transactionId = 23, stid = 2, firstRid = 2, lastRid = 3)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.io.recovery.prepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)

      dut.io.alloc.valid.expect(false.B)
      dut.io.agu(0).ready.expect(false.B)
      dut.io.agu(1).ready.expect(false.B)
      dut.io.accepted.valid.expect(false.B)
      dut.io.recovery.prepared.valid.expect(true.B)
      dut.io.recovery.prepared.ready.poke(true.B)
      dut.clock.step()
      dut.io.recovery.prepared.ready.poke(false.B)

      pokeRecoveryPlan(dut.io.recovery.apply.bits, RecoveryPhase.Apply,
        transactionId = 23, stid = 2, firstRid = 2, lastRid = 3)
      dut.io.recovery.apply.valid.poke(true.B)
      dut.io.alloc.valid.expect(false.B)
      dut.io.accepted.valid.expect(false.B)
      dut.io.agu(0).ready.expect(true.B)
      dut.io.rejected(0).valid.expect(true.B)
      dut.io.rejected(0).bits.killed.expect(true.B)
      dut.io.agu(1).ready.expect(false.B)
      dut.io.rejected(1).valid.expect(false.B)
      dut.clock.step()

      dut.io.agu(0).valid.poke(false.B)
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.alloc.valid.expect(true.B)
      dut.io.agu(1).ready.expect(true.B)
      dut.io.alloc.bits.attempt.generation.expect(2.U)
    }
  }
}
