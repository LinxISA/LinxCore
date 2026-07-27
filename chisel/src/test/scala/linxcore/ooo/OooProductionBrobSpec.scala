package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooProductionBrobSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooProductionBrob): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.publishFire.poke(false.B)
    dut.io.commit.valid.poke(false.B)
    dut.io.commit.bits.poke(0.U.asTypeOf(dut.io.commit.bits))
  }

  private def pokePrepare(
      dut: OooProductionBrob,
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

  private def publish(dut: OooProductionBrob): Unit = {
    dut.io.prepareReady.expect(true.B)
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.publishFire.poke(false.B)
    dut.io.prepare.valid.poke(false.B)
  }

  private def pokeCommit(
      dut: OooProductionBrob,
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

  private def commit(dut: OooProductionBrob): Unit = {
    dut.io.commit.ready.expect(true.B)
    dut.clock.step()
    dut.io.commit.valid.poke(false.B)
  }

  test("requires an opening boundary before assigning the first native BID") {
    val p = OooParams(instructionDecodeWidth = 2, brobEntriesPerStid = 8)
    simulate(new OooProductionBrob(p)) { dut =>
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

  test("publishes one BID across grouped rows and frees it only at exact close commit") {
    val p = OooParams(instructionDecodeWidth = 2, brobEntriesPerStid = 8)
    simulate(new OooProductionBrob(p)) { dut =>
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
    simulate(new OooProductionBrob(p)) { dut =>
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
    simulate(new OooProductionBrob(p)) { dut =>
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
    simulate(new OooProductionBrob(p)) { dut =>
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
    simulate(new OooProductionBrob(p)) { dut =>
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
    simulate(new OooProductionBrob(p)) { dut =>
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
    simulate(new OooProductionBrob(p)) { dut =>
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
      simulate(new OooProductionBrob(p)) { dut =>
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
