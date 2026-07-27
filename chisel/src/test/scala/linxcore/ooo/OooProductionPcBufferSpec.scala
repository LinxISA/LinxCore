package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooProductionPcBufferSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooProductionPcBuffer): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.publishFire.poke(false.B)
    dut.io.commit.valid.poke(false.B)
    dut.io.commit.bits.poke(0.U.asTypeOf(dut.io.commit.bits))
    dut.io.readTokens.foreach(_.poke(0.U.asTypeOf(dut.io.readTokens.head)))
  }

  private def pokePrepare(
      dut: OooProductionPcBuffer,
      stid: Int,
      transactionId: Int,
      firstRid: Int,
      pcs: Seq[Long],
      releases: Set[Int] = Set.empty,
      traps: Set[Int] = Set.empty,
      peId: Int = 3): Unit = {
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    val transaction = dut.io.prepare.bits.transaction
    transaction.plan.peId.poke(peId.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(5.U)
    transaction.plan.transactionId.poke(transactionId.U)
    transaction.plan.groupCount.poke(pcs.size.U)
    transaction.decoded.peId.poke(peId.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(5.U)
    transaction.decoded.uopMask.poke(((1 << pcs.size) - 1).U)
    transaction.groupMask.poke(((1 << pcs.size) - 1).U)
    pcs.zipWithIndex.foreach { case (pc, index) =>
      val absoluteRid = firstRid + index
      val group = transaction.groups(index)
      group.valid.poke(true.B)
      group.key.valid.poke(true.B)
      group.key.peId.poke(peId.U)
      group.key.stid.poke(stid.U)
      group.key.ridSlot.poke((absoluteRid % dut.p.robGroupsPerStid).U)
      group.key.ridGeneration.poke((absoluteRid / dut.p.robGroupsPerStid).U)
      group.logicalUopMask.poke((1 << index).U)
      group.physicalMemberCount.poke(1.U)
      group.architecturalParentCount.poke(1.U)
      group.releasePcBase.poke(releases(index).B)
      group.preciseTrap.poke(traps(index).B)
      transaction.uopGroupIndex(index).poke(index.U)

      val uop = transaction.decoded.uops(index)
      uop.valid.poke(true.B)
      uop.identity.parentCount.poke(1.U)
      uop.identity.parents(0).key.valid.poke(true.B)
      uop.identity.parents(0).key.peId.poke(peId.U)
      uop.identity.parents(0).key.stid.poke(stid.U)
      uop.identity.parents(0).key.instructionId.poke((100 + index).U)
      uop.identity.parents(0).key.epoch.poke(5.U)
      uop.identity.parents(0).pc.poke(pc.U)
    }
    dut.io.prepare.valid.poke(true.B)
  }

  private def publish(dut: OooProductionPcBuffer): Unit = {
    dut.io.prepareReady.expect(true.B)
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.publishFire.poke(false.B)
    dut.io.prepare.valid.poke(false.B)
  }

  private def pokeCommit(
      dut: OooProductionPcBuffer,
      stid: Int,
      groups: Seq[(Int, Int, Int)],
      peId: Int = 3): Unit = {
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
    groups.zipWithIndex.foreach { case ((absoluteRid, pcIndex, pcEpoch), index) =>
      val group = dut.io.commit.bits.groups(index)
      group.valid.poke(true.B)
      group.key.valid.poke(true.B)
      group.key.peId.poke(peId.U)
      group.key.stid.poke(stid.U)
      group.key.ridSlot.poke((absoluteRid % dut.p.robGroupsPerStid).U)
      group.key.ridGeneration.poke((absoluteRid / dut.p.robGroupsPerStid).U)
      group.pcBase.valid.poke(true.B)
      group.pcBase.index.poke(pcIndex.U)
      group.pcBase.allocationEpoch.poke(pcEpoch.U)
    }
    dut.io.commit.valid.poke(true.B)
  }

  private def commit(dut: OooProductionPcBuffer): Unit = {
    dut.io.commit.ready.expect(true.B)
    dut.clock.step()
    dut.io.commit.valid.poke(false.B)
  }

  test("reuses one base with byte offsets for 2 4 6 and 8-byte instruction PCs") {
    val p = OooParams(instructionDecodeWidth = 4, pcBufferEntries = 64)
    simulate(new OooProductionPcBuffer(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 1, transactionId = 0, firstRid = 0,
        pcs = Seq(100, 102, 106, 112), releases = Set(3))
      dut.io.prepared.allocatedBases.expect(1.U)
      for (index <- 0 until 4) {
        dut.io.prepared.groupTokens(index).index.expect(16.U)
      }
      Seq(0, 2, 6, 12).zipWithIndex.foreach { case (offset, index) =>
        dut.io.prepared.parentTokens(index)(0).byteOffset.expect(offset.U)
      }
      publish(dut)
      dut.io.usedBases(1).expect(1.U)
      dut.io.currentValid(1).expect(false.B)
      dut.io.readTokens(0).valid.poke(true.B)
      dut.io.readTokens(0).index.poke(16.U)
      dut.io.readTokens(0).byteOffset.poke(12.U)
      dut.io.readTokens(0).allocationEpoch.poke(0.U)
      dut.io.readValid(0).expect(true.B)
      dut.io.readPc(0).expect(112.U)
      dut.io.readTokens(1).valid.poke(true.B)
      dut.io.readTokens(1).index.poke(16.U)
      dut.io.readTokens(1).byteOffset.poke(2.U)
      dut.io.readTokens(1).allocationEpoch.poke(1.U)
      dut.io.readValid(1).expect(false.B)

      pokeCommit(dut, stid = 1,
        groups = (0 until 4).map(index => (index, 16, 0)))
      commit(dut)
      dut.io.usedBases(1).expect(0.U)
    }
  }

  test("allocates a new base on offset overflow and retains the old close owner") {
    val p = OooParams(instructionDecodeWidth = 2, pcBufferEntries = 64)
    simulate(new OooProductionPcBuffer(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 2, transactionId = 0, firstRid = 0,
        pcs = Seq(100, 300), releases = Set(1))
      dut.io.prepared.allocatedBases.expect(2.U)
      dut.io.prepared.groupTokens(0).index.expect(32.U)
      dut.io.prepared.groupTokens(1).index.expect(33.U)
      dut.io.prepared.implicitCloseMask.expect("b10".U)
      publish(dut)
      dut.io.usedBases(2).expect(2.U)

      pokeCommit(dut, stid = 2, groups = Seq((0, 32, 0), (1, 33, 0)))
      commit(dut)
      dut.io.usedBases(2).expect(0.U)
    }
  }

  test("closes a predicted-taken base and allocates the following discontinuity") {
    val p = OooParams(instructionDecodeWidth = 2, pcBufferEntries = 64)
    simulate(new OooProductionPcBuffer(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 0, transactionId = 0, firstRid = 0,
        pcs = Seq(40, 1000), releases = Set(0, 1))
      dut.io.prepared.allocatedBases.expect(2.U)
      dut.io.prepared.groupTokens(0).index.expect(0.U)
      dut.io.prepared.groupTokens(1).index.expect(1.U)
      publish(dut)
      pokeCommit(dut, stid = 0, groups = Seq((0, 0, 0), (1, 1, 0)))
      commit(dut)
      dut.io.usedBases(0).expect(0.U)
    }
  }

  test("rejects more than three base writes without partial publication") {
    val p = OooParams(instructionDecodeWidth = 4, pcBufferEntries = 64, pcWritePorts = 3)
    simulate(new OooProductionPcBuffer(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 3, transactionId = 0, firstRid = 0,
        pcs = Seq(0, 256, 512, 768), releases = Set(0, 1, 2, 3))
      dut.io.prepared.allocatedBases.expect(4.U)
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.valid.expect(true.B)
      dut.io.publishFire.poke(false.B)
      dut.clock.step()
      dut.io.usedBases(3).expect(0.U)
    }
  }

  test("rejects an inconsistent uop group index and logical mask") {
    val p = OooParams(instructionDecodeWidth = 2, pcBufferEntries = 64)
    simulate(new OooProductionPcBuffer(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 1, transactionId = 0, firstRid = 0,
        pcs = Seq(80, 88), releases = Set(1))
      dut.io.prepare.bits.transaction.uopGroupIndex(1).poke(0.U)
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.usedBases(1).expect(0.U)
    }
  }

  test("rejects skipped and duplicate ROB groups for one PC base") {
    val p = OooParams(instructionDecodeWidth = 2, pcBufferEntries = 64)
    simulate(new OooProductionPcBuffer(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 0, transactionId = 0, firstRid = 7,
        pcs = Seq(120, 126), releases = Set(1))
      publish(dut)

      pokeCommit(dut, stid = 0, groups = Seq((8, 0, 0)))
      dut.io.commit.ready.expect(false.B)
      dut.clock.step()
      dut.io.commit.valid.poke(false.B)

      pokeCommit(dut, stid = 0, groups = Seq((7, 0, 0)))
      commit(dut)
      pokeCommit(dut, stid = 0, groups = Seq((7, 0, 0)))
      dut.io.commit.ready.expect(false.B)
      dut.clock.step()
      dut.io.commit.valid.poke(false.B)

      pokeCommit(dut, stid = 0, groups = Seq((8, 0, 0)))
      commit(dut)
      dut.io.usedBases(0).expect(0.U)
    }
  }

  test("keeps fixed STID partitions and wraps allocation epoch independently") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      pcBufferEntries = 8,
      stidCount = 4)
    simulate(new OooProductionPcBuffer(p)) { dut =>
      clear(dut)
      for (iteration <- 0 until 2) {
        pokePrepare(dut, stid = 3, transactionId = iteration, firstRid = iteration,
          pcs = Seq(1000 + iteration * 256), releases = Set(0))
        dut.io.prepared.groupTokens(0).index.expect((6 + iteration).U)
        dut.io.prepared.groupTokens(0).allocationEpoch.expect(0.U)
        publish(dut)
        pokeCommit(dut, stid = 3, groups = Seq((iteration, 6 + iteration, 0)))
        commit(dut)
        dut.io.usedBases(0).expect(0.U)
      }
      pokePrepare(dut, stid = 3, transactionId = 2, firstRid = 2,
        pcs = Seq(2000), releases = Set(0))
      dut.io.prepared.groupTokens(0).index.expect(6.U)
      dut.io.prepared.groupTokens(0).allocationEpoch.expect(1.U)
    }
  }

  test("elaborates sequential prefixes at 2 4 and 6 decode width") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(instructionDecodeWidth = width, pcBufferEntries = 64)
      simulate(new OooProductionPcBuffer(p)) { dut =>
        clear(dut)
        pokePrepare(dut, stid = 0, transactionId = 0, firstRid = 0,
          pcs = (0 until width).map(index => 64L + index * 8),
          releases = Set(width - 1))
        dut.io.prepareReady.expect(true.B)
        dut.io.prepared.allocatedBases.expect(1.U)
      }
    }
  }
}
