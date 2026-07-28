package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooBrobSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooBrob): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.publishFire.poke(false.B)
    dut.io.commit.valid.poke(false.B)
    dut.io.commit.bits.poke(0.U.asTypeOf(dut.io.commit.bits))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
  }

  private def pokePrepare(
      dut: OooBrob,
      stid: Int,
      transactionId: Int,
      firstRid: Int,
      boundaries: Seq[(Boolean, Boolean)],
      peId: Int = 2): Unit = {
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    val transaction = dut.io.prepare.bits.transaction
    transaction.plan.peId.poke(peId.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(9.U)
    transaction.plan.transactionId.poke(transactionId.U)
    transaction.plan.groupCount.poke(boundaries.size.U)
    transaction.decoded.peId.poke(peId.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(9.U)
    transaction.groupMask.poke(((1 << boundaries.size) - 1).U)
    boundaries.zipWithIndex.foreach { case ((start, stop), index) =>
      val group = transaction.groups(index)
      group.valid.poke(true.B)
      group.key.valid.poke(true.B)
      group.key.peId.poke(peId.U)
      group.key.stid.poke(stid.U)
      val absoluteRid = firstRid + index
      group.key.ridSlot.poke((absoluteRid % dut.p.robGroupsPerStid).U)
      group.key.ridGeneration.poke((absoluteRid / dut.p.robGroupsPerStid).U)
      group.boundaryStart.poke(start.B)
      group.boundaryStop.poke(stop.B)
      group.physicalMemberCount.poke(1.U)
      group.architecturalParentCount.poke(1.U)
    }
    dut.io.prepare.valid.poke(true.B)
  }

  private def publish(dut: OooBrob): Unit = {
    dut.io.prepareReady.expect(true.B)
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.publishFire.poke(false.B)
    dut.io.prepare.valid.poke(false.B)
  }

  private def pokeCommit(
      dut: OooBrob,
      stid: Int,
      groups: Seq[(Int, Int, Int, Boolean, Boolean)],
      peId: Int = 2): Unit = {
    dut.io.commit.bits.poke(0.U.asTypeOf(dut.io.commit.bits))
    dut.io.commit.bits.release.groupCount.poke(groups.size.U)
    val firstRid = groups.head._1
    dut.io.commit.bits.release.firstGroup.valid.poke(true.B)
    dut.io.commit.bits.release.firstGroup.peId.poke(peId.U)
    dut.io.commit.bits.release.firstGroup.stid.poke(stid.U)
    dut.io.commit.bits.release.firstGroup.ridSlot.poke(
      (firstRid % dut.p.robGroupsPerStid).U)
    dut.io.commit.bits.release.firstGroup.ridGeneration.poke(
      (firstRid / dut.p.robGroupsPerStid).U)
    groups.zipWithIndex.foreach {
      case ((ridSlot, bid, brobGeneration, start, stop), index) =>
        val group = dut.io.commit.bits.groups(index)
        group.valid.poke(true.B)
        group.key.valid.poke(true.B)
        group.key.peId.poke(peId.U)
        group.key.stid.poke(stid.U)
        group.key.ridSlot.poke((ridSlot % dut.p.robGroupsPerStid).U)
        group.key.ridGeneration.poke((ridSlot / dut.p.robGroupsPerStid).U)
        group.brob.valid.poke(true.B)
        group.brob.bid.valid.poke(true.B)
        group.brob.bid.value.poke(bid.U)
        group.brob.generation.poke(brobGeneration.U)
        group.boundaryStart.poke(start.B)
        group.boundaryStop.poke(stop.B)
    }
    dut.io.commit.valid.poke(true.B)
  }

  private def commit(dut: OooBrob): Unit = {
    dut.io.commit.ready.expect(true.B)
    dut.clock.step()
    dut.io.commit.valid.poke(false.B)
  }

  private def pokeRecoveryPlan(
      dut: OooBrob,
      stid: Int,
      staleGeneration: Boolean = false): Unit = {
    val plan = dut.io.recoveryPrepare.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.valid.poke(true.B)
    plan.request.rename.key.member.group.valid.poke(true.B)
    plan.request.rename.key.member.group.peId.poke(2.U)
    plan.request.rename.key.member.group.stid.poke(stid.U)
    plan.request.rename.key.member.bid.valid.poke(true.B)
    plan.oldOccupied.poke(5.U)
    plan.newOccupied.poke(2.U)
    plan.killedGroupCount.poke(3.U)
    plan.killedGroupMask.poke(7.U)
    plan.survivingTailValid.poke(true.B)
    plan.survivingTail.valid.poke(true.B)
    plan.survivingTail.key.valid.poke(true.B)
    plan.survivingTail.key.peId.poke(2.U)
    plan.survivingTail.key.stid.poke(stid.U)
    plan.survivingTail.key.ridSlot.poke(1.U)
    plan.survivingTail.brob.valid.poke(true.B)
    plan.survivingTail.brob.bid.valid.poke(true.B)
    plan.survivingTail.brob.bid.value.poke(0.U)
    plan.survivingTail.brob.generation.poke(0.U)

    val groups = Seq(
      (2, 1, true, true, 0),
      (3, 1, false, false, 0),
      (4, 2, true, true, 1))
    groups.zipWithIndex.foreach {
      case ((rid, bid, allocated, implicitClose, priorBid), index) =>
        val group = plan.killedGroups(index)
        group.valid.poke(true.B)
        group.key.valid.poke(true.B)
        group.key.peId.poke(2.U)
        group.key.stid.poke(stid.U)
        group.key.ridSlot.poke(rid.U)
        group.key.ridGeneration.poke(0.U)
        group.boundaryStart.poke(allocated.B)
        group.brob.valid.poke(true.B)
        group.brob.bid.valid.poke(true.B)
        group.brob.bid.value.poke(bid.U)
        group.brob.generation.poke(
          (if (staleGeneration && index == 1) 1 else 0).U)
        group.brobAllocated.poke(allocated.B)
        group.brobImplicitCloseValid.poke(implicitClose.B)
        group.brobImplicitClose.valid.poke(implicitClose.B)
        group.brobImplicitClose.bid.valid.poke(implicitClose.B)
        group.brobImplicitClose.bid.value.poke(priorBid.U)
        group.brobImplicitClose.generation.poke(0.U)
    }
    dut.io.recoveryPrepare.valid.poke(true.B)
  }

  test("requires an opening boundary before assigning the first native BID") {
    val p = OooParams(instructionDecodeWidth = 2, brobEntriesPerStid = 8)
    simulate(new OooBrob(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 0, transactionId = 0, firstRid = 0,
        boundaries = Seq(false -> false))
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.valid.expect(true.B)
      dut.io.usedBlocks(0).expect(0.U)
      dut.clock.step()
      dut.io.usedBlocks(0).expect(0.U)
    }
  }

  test("rolls back exact tail blocks and reopens an implicitly closed survivor") {
    val p = OooParams(instructionDecodeWidth = 4,
      robGroupsPerStid = 8, brobEntriesPerStid = 8)
    simulate(new OooBrob(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 1, transactionId = 0, firstRid = 0,
        boundaries = Seq(true -> false, false -> false, true -> false))
      publish(dut)
      pokePrepare(dut, stid = 1, transactionId = 1, firstRid = 3,
        boundaries = Seq(false -> false, true -> false))
      publish(dut)
      dut.io.usedBlocks(1).expect(3.U)
      dut.io.tail(1).bid.value.expect(3.U)
      dut.io.currentValid(1).expect(true.B)
      dut.io.current(1).bid.value.expect(2.U)

      pokeRecoveryPlan(dut, stid = 1)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.io.recoveryPrepared.freedBlocks.expect(2.U)
      dut.io.recoveryPrepared.tailAfter.bid.value.expect(1.U)
      dut.io.recoveryPrepared.currentAfterValid.expect(true.B)
      dut.io.recoveryPrepared.currentAfter.bid.value.expect(0.U)
      dut.clock.step()
      dut.io.usedBlocks(1).expect(3.U)

      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.usedBlocks(1).expect(1.U)
      dut.io.tail(1).bid.value.expect(1.U)
      dut.io.currentValid(1).expect(true.B)
      dut.io.current(1).bid.value.expect(0.U)

      pokePrepare(dut, stid = 1, transactionId = 2, firstRid = 2,
        boundaries = Seq(true -> false))
      dut.io.prepareReady.expect(true.B)
      dut.io.prepared.pointers(0).bid.value.expect(1.U)
      dut.io.prepared.implicitCloseMask.expect(1.U)
      dut.io.prepared.implicitClosePointers(0).bid.value.expect(0.U)
      publish(dut)
      dut.io.usedBlocks(1).expect(2.U)
      dut.io.current(1).bid.value.expect(1.U)
    }
  }

  test("rejects a stale killed-block generation without BROB mutation") {
    val p = OooParams(instructionDecodeWidth = 4,
      robGroupsPerStid = 8, brobEntriesPerStid = 8)
    simulate(new OooBrob(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 1, transactionId = 0, firstRid = 0,
        boundaries = Seq(true -> false, false -> false, true -> false))
      publish(dut)
      pokePrepare(dut, stid = 1, transactionId = 1, firstRid = 3,
        boundaries = Seq(false -> false, true -> false))
      publish(dut)

      pokeRecoveryPlan(dut, stid = 1, staleGeneration = true)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.valid.expect(true.B)
      dut.io.recoveryRejected.bits.killedRowsExact.expect(false.B)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.usedBlocks(1).expect(3.U)
      dut.io.tail(1).bid.value.expect(3.U)
      dut.io.current(1).bid.value.expect(2.U)
    }
  }

  test("publishes one BID across grouped rows and frees it only at exact close commit") {
    val p = OooParams(instructionDecodeWidth = 2, brobEntriesPerStid = 8)
    simulate(new OooBrob(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 1, transactionId = 0, firstRid = 0,
        boundaries = Seq(true -> false, false -> true))
      dut.io.prepared.allocatedBlocks.expect(1.U)
      dut.io.prepared.pointers(0).bid.value.expect(0.U)
      dut.io.prepared.pointers(1).bid.value.expect(0.U)
      publish(dut)
      dut.io.usedBlocks(1).expect(1.U)
      dut.io.currentValid(1).expect(false.B)

      pokeCommit(dut, stid = 1,
        groups = Seq((0, 0, 0, true, false), (1, 0, 0, false, true)))
      commit(dut)
      dut.io.usedBlocks(1).expect(0.U)
      dut.io.head(1).valid.expect(false.B)
      dut.io.head(1).bid.value.expect(1.U)
      dut.io.tail(1).bid.value.expect(1.U)
    }
  }

  test("holds an empty open block until its in-body BSTART close owner commits") {
    val p = OooParams(instructionDecodeWidth = 2, brobEntriesPerStid = 8)
    simulate(new OooBrob(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 2, transactionId = 0, firstRid = 0,
        boundaries = Seq(true -> false))
      publish(dut)
      dut.io.currentValid(2).expect(true.B)

      pokeCommit(dut, stid = 2, groups = Seq((0, 0, 0, true, false)))
      commit(dut)
      dut.io.usedBlocks(2).expect(1.U)
      dut.io.head(2).bid.value.expect(0.U)

      pokePrepare(dut, stid = 2, transactionId = 1, firstRid = 1,
        boundaries = Seq(true -> true))
      dut.io.prepared.implicitCloseMask.expect(1.U)
      dut.io.prepared.implicitClosePointers(0).bid.value.expect(0.U)
      dut.io.prepared.pointers(0).bid.value.expect(1.U)
      publish(dut)
      dut.io.usedBlocks(2).expect(2.U)

      pokeCommit(dut, stid = 2, groups = Seq((1, 1, 0, true, true)))
      commit(dut)
      dut.io.usedBlocks(2).expect(0.U)
      dut.io.head(2).bid.value.expect(2.U)
    }
  }

  test("rejects wrong BROB generation and preserves another STID") {
    val p = OooParams(instructionDecodeWidth = 2, brobEntriesPerStid = 8)
    simulate(new OooBrob(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 0, transactionId = 0, firstRid = 0,
        boundaries = Seq(true -> true))
      publish(dut)
      pokePrepare(dut, stid = 3, transactionId = 0, firstRid = 0,
        boundaries = Seq(true -> true))
      publish(dut)

      pokeCommit(dut, stid = 0, groups = Seq((0, 0, 1, true, true)))
      dut.io.commit.ready.expect(false.B)
      dut.io.commitRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.commit.valid.poke(false.B)
      dut.io.usedBlocks(0).expect(1.U)
      dut.io.usedBlocks(3).expect(1.U)

      pokeCommit(dut, stid = 0, groups = Seq((0, 0, 0, true, true)))
      dut.io.commit.bits.release.firstGroup.valid.poke(true.B)
      dut.io.commit.bits.release.firstGroup.peId.poke(2.U)
      dut.io.commit.bits.release.firstGroup.stid.poke(0.U)
      dut.io.commit.bits.release.firstGroup.ridSlot.poke(0.U)
      dut.io.commit.bits.release.firstGroup.ridGeneration.poke(0.U)
      commit(dut)
      dut.io.usedBlocks(0).expect(0.U)
      dut.io.usedBlocks(3).expect(1.U)
    }
  }

  test("does not let an invalid retained commit starve prepare and rejects a mismatched release header") {
    val p = OooParams(instructionDecodeWidth = 2, brobEntriesPerStid = 8)
    simulate(new OooBrob(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 0, transactionId = 0, firstRid = 0,
        boundaries = Seq(true -> true))
      publish(dut)

      pokeCommit(dut, stid = 0, groups = Seq((0, 0, 0, true, true)))
      dut.io.commit.bits.release.firstGroup.valid.poke(true.B)
      dut.io.commit.bits.release.firstGroup.peId.poke(2.U)
      dut.io.commit.bits.release.firstGroup.stid.poke(0.U)
      dut.io.commit.bits.release.firstGroup.ridSlot.poke(7.U)
      dut.io.commit.bits.release.firstGroup.ridGeneration.poke(0.U)
      dut.io.commit.ready.expect(false.B)
      dut.io.commitRejected.valid.expect(true.B)

      pokePrepare(dut, stid = 0, transactionId = 1, firstRid = 1,
        boundaries = Seq(true -> true))
      dut.io.prepareReady.expect(true.B)
      dut.io.usedBlocks(0).expect(1.U)
      dut.clock.step()
      dut.io.usedBlocks(0).expect(1.U)
    }
  }

  test("rejects skipped and duplicate ROB groups within one BID") {
    val p = OooParams(instructionDecodeWidth = 2, brobEntriesPerStid = 8)
    simulate(new OooBrob(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 1, transactionId = 0, firstRid = 0,
        boundaries = Seq(true -> false, false -> true))
      publish(dut)

      pokeCommit(dut, stid = 1, groups = Seq((1, 0, 0, false, true)))
      dut.io.commit.ready.expect(false.B)
      dut.io.commitRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.commit.valid.poke(false.B)
      dut.io.usedBlocks(1).expect(1.U)

      pokeCommit(dut, stid = 1, groups = Seq((0, 0, 0, true, false)))
      commit(dut)
      dut.io.usedBlocks(1).expect(1.U)

      pokeCommit(dut, stid = 1, groups = Seq((0, 0, 0, true, false)))
      dut.io.commit.ready.expect(false.B)
      dut.clock.step()
      dut.io.commit.valid.poke(false.B)
      dut.io.usedBlocks(1).expect(1.U)

      pokeCommit(dut, stid = 1, groups = Seq((1, 0, 0, false, true)))
      commit(dut)
      dut.io.usedBlocks(1).expect(0.U)
    }
  }

  test("wraps native BID independently from BROB generation") {
    val p = OooParams(instructionDecodeWidth = 2, brobEntriesPerStid = 4)
    simulate(new OooBrob(p)) { dut =>
      clear(dut)
      for (block <- 0 until 4) {
        pokePrepare(dut, stid = 0, transactionId = block, firstRid = block,
          boundaries = Seq(true -> true))
        dut.io.prepared.pointers(0).bid.value.expect(block.U)
        dut.io.prepared.pointers(0).generation.expect(0.U)
        publish(dut)
        pokeCommit(dut, stid = 0, groups = Seq((block, block, 0, true, true)))
        commit(dut)
      }
      dut.io.head(0).bid.value.expect(0.U)
      dut.io.head(0).generation.expect(1.U)
      dut.io.tail(0).bid.value.expect(0.U)
      dut.io.tail(0).generation.expect(1.U)

      pokePrepare(dut, stid = 0, transactionId = 4, firstRid = 4,
        boundaries = Seq(true -> true))
      dut.io.prepared.pointers(0).bid.value.expect(0.U)
      dut.io.prepared.pointers(0).generation.expect(1.U)
    }
  }

  test("accepts an exact RID slot wrap only with the next RID generation") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8)
    simulate(new OooBrob(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 0, transactionId = 0, firstRid = 7,
        boundaries = Seq(true -> false, false -> true))
      publish(dut)
      pokeCommit(dut, stid = 0,
        groups = Seq((7, 0, 0, true, false), (8, 0, 0, false, true)))
      commit(dut)
      dut.io.usedBlocks(0).expect(0.U)
    }
  }

  test("aggregates one block across 2 4 and 6-wide publication and 4-wide retire") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(
        instructionDecodeWidth = width,
        retireGroupWidth = 4,
        brobEntriesPerStid = 8)
      simulate(new OooBrob(p)) { dut =>
        clear(dut)
        val boundaries = (0 until width).map { index =>
          (index == 0) -> (index == width - 1)
        }
        pokePrepare(dut, stid = 1, transactionId = 0, firstRid = 0, boundaries)
        dut.io.prepared.allocatedBlocks.expect(1.U)
        publish(dut)

        val firstCount = math.min(width, 4)
        val firstCommit = (0 until firstCount).map { index =>
          (index, 0, 0, index == 0, index == width - 1)
        }
        pokeCommit(dut, stid = 1, groups = firstCommit)
        commit(dut)
        if (width > 4) {
          dut.io.usedBlocks(1).expect(1.U)
          val secondCommit = (4 until width).map { index =>
            (index, 0, 0, false, index == width - 1)
          }
          pokeCommit(dut, stid = 1, groups = secondCommit)
          commit(dut)
        }
        dut.io.usedBlocks(1).expect(0.U)
      }
    }
  }
}
