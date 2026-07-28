package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooPcBufferSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooPcBuffer): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.publishFire.poke(false.B)
    dut.io.commit.valid.poke(false.B)
    dut.io.commit.bits.poke(0.U.asTypeOf(dut.io.commit.bits))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
    dut.io.readTokens.foreach(_.poke(0.U.asTypeOf(dut.io.readTokens.head)))
  }

  private def pokePrepare(
      dut: OooPcBuffer,
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

  private def publish(dut: OooPcBuffer): Unit = {
    dut.io.prepareReady.expect(true.B)
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.publishFire.poke(false.B)
    dut.io.prepare.valid.poke(false.B)
  }

  private def pokeCommit(
      dut: OooPcBuffer,
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

  private def commit(dut: OooPcBuffer): Unit = {
    dut.io.commit.ready.expect(true.B)
    dut.clock.step()
    dut.io.commit.valid.poke(false.B)
  }

  private def pokeRecoveryPlan(
      dut: OooPcBuffer,
      stid: Int,
      staleEpoch: Boolean = false): Unit = {
    val partitionBase = stid * dut.p.pcEntriesPerStid
    val plan = dut.io.recoveryPrepare.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.valid.poke(true.B)
    plan.request.rename.key.member.group.valid.poke(true.B)
    plan.request.rename.key.member.group.peId.poke(3.U)
    plan.request.rename.key.member.group.stid.poke(stid.U)
    plan.request.rename.key.member.bid.valid.poke(true.B)
    plan.oldOccupied.poke(5.U)
    plan.newOccupied.poke(2.U)
    plan.killedGroupCount.poke(3.U)
    plan.killedGroupMask.poke(7.U)
    plan.survivingTailValid.poke(true.B)
    plan.survivingTail.valid.poke(true.B)
    plan.survivingTail.key.valid.poke(true.B)
    plan.survivingTail.key.peId.poke(3.U)
    plan.survivingTail.key.stid.poke(stid.U)
    plan.survivingTail.key.ridSlot.poke(1.U)
    plan.survivingTail.pcBase.valid.poke(true.B)
    plan.survivingTail.pcBase.index.poke(partitionBase.U)
    plan.survivingTail.pcBase.allocationEpoch.poke(0.U)

    val groups = Seq(
      (2, 1, true, true, 0),
      (3, 1, false, false, 0),
      (4, 2, true, true, 1))
    groups.zipWithIndex.foreach {
      case ((rid, local, allocated, implicitClose, priorLocal), index) =>
        val group = plan.killedGroups(index)
        group.valid.poke(true.B)
        group.key.valid.poke(true.B)
        group.key.peId.poke(3.U)
        group.key.stid.poke(stid.U)
        group.key.ridSlot.poke(rid.U)
        group.key.ridGeneration.poke(0.U)
        group.pcBase.valid.poke(true.B)
        group.pcBase.index.poke((partitionBase + local).U)
        group.pcBase.allocationEpoch.poke(
          (if (staleEpoch && index == 1) 1 else 0).U)
        group.pcBaseAllocated.poke(allocated.B)
        group.pcImplicitCloseValid.poke(implicitClose.B)
        group.pcImplicitClose.valid.poke(implicitClose.B)
        group.pcImplicitClose.index.poke((partitionBase + priorLocal).U)
        group.pcImplicitClose.allocationEpoch.poke(0.U)
    }
    dut.io.recoveryPrepare.valid.poke(true.B)
  }

  test("reuses one base with byte offsets for 2 4 6 and 8-byte instruction PCs") {
    val p = OooParams(instructionDecodeWidth = 4, pcBufferEntries = 64)
    simulate(new OooPcBuffer(p)) { dut =>
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

  test("rolls back exact PC-base tail and reopens the surviving base") {
    val p = OooParams(instructionDecodeWidth = 4,
      robGroupsPerStid = 8, pcBufferEntries = 64)
    simulate(new OooPcBuffer(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 1, transactionId = 0, firstRid = 0,
        pcs = Seq(100, 102, 1000))
      publish(dut)
      pokePrepare(dut, stid = 1, transactionId = 1, firstRid = 3,
        pcs = Seq(1002, 2000))
      publish(dut)
      dut.io.usedBases(1).expect(3.U)
      dut.io.currentValid(1).expect(true.B)
      dut.io.current(1).index.expect(18.U)

      pokeRecoveryPlan(dut, stid = 1)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.io.recoveryPrepared.freedBases.expect(2.U)
      dut.io.recoveryPrepared.tailAfter.index.expect(17.U)
      dut.io.recoveryPrepared.currentAfterValid.expect(true.B)
      dut.io.recoveryPrepared.currentAfter.index.expect(16.U)
      dut.io.recoveryPrepared.currentBaseAfter.expect(100.U)
      dut.clock.step()
      dut.io.usedBases(1).expect(3.U)

      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.usedBases(1).expect(1.U)
      dut.io.currentValid(1).expect(true.B)
      dut.io.current(1).index.expect(16.U)
      dut.io.readTokens(0).valid.poke(true.B)
      dut.io.readTokens(0).index.poke(17.U)
      dut.io.readTokens(0).allocationEpoch.poke(0.U)
      dut.io.readValid(0).expect(false.B)

      pokePrepare(dut, stid = 1, transactionId = 2, firstRid = 2,
        pcs = Seq(1000))
      dut.io.prepareReady.expect(true.B)
      dut.io.prepared.groupTokens(0).index.expect(17.U)
      dut.io.prepared.implicitCloseMask.expect(1.U)
      dut.io.prepared.implicitCloseTokens(0).index.expect(16.U)
      publish(dut)
      dut.io.usedBases(1).expect(2.U)
      dut.io.current(1).index.expect(17.U)
    }
  }

  test("rejects a stale killed PC epoch without buffer mutation") {
    val p = OooParams(instructionDecodeWidth = 4,
      robGroupsPerStid = 8, pcBufferEntries = 64)
    simulate(new OooPcBuffer(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 1, transactionId = 0, firstRid = 0,
        pcs = Seq(100, 102, 1000))
      publish(dut)
      pokePrepare(dut, stid = 1, transactionId = 1, firstRid = 3,
        pcs = Seq(1002, 2000))
      publish(dut)

      pokeRecoveryPlan(dut, stid = 1, staleEpoch = true)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.valid.expect(true.B)
      dut.io.recoveryRejected.bits.killedRowsExact.expect(false.B)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.usedBases(1).expect(3.U)
      dut.io.current(1).index.expect(18.U)
    }
  }

  test("allocates a new base on offset overflow and retains the old close owner") {
    val p = OooParams(instructionDecodeWidth = 2, pcBufferEntries = 64)
    simulate(new OooPcBuffer(p)) { dut =>
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
    simulate(new OooPcBuffer(p)) { dut =>
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
    simulate(new OooPcBuffer(p)) { dut =>
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
    simulate(new OooPcBuffer(p)) { dut =>
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
    simulate(new OooPcBuffer(p)) { dut =>
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
      pcBankCount = 2,
      pcWritePorts = 2,
      retireGroupWidth = 2,
      stidCount = 4)
    simulate(new OooPcBuffer(p)) { dut =>
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

  test("maps consecutive bases across banks and rows while preserving all six reads") {
    val p = OooParams(instructionDecodeWidth = 4, pcBufferEntries = 64,
      pcBankCount = 4)
    simulate(new OooPcBuffer(p)) { dut =>
      clear(dut)
      pokePrepare(dut, stid = 0, transactionId = 0, firstRid = 0,
        pcs = Seq(0, 256, 512), releases = Set(0, 1, 2))
      (0 until 3).foreach { index =>
        dut.io.prepared.groupTokens(index).index.expect(index.U)
      }
      publish(dut)

      pokePrepare(dut, stid = 0, transactionId = 1, firstRid = 3,
        pcs = Seq(768), releases = Set(0))
      dut.io.prepared.groupTokens(0).index.expect(3.U)
      publish(dut)
      pokePrepare(dut, stid = 0, transactionId = 2, firstRid = 4,
        pcs = Seq(1024), releases = Set(0))
      dut.io.prepared.groupTokens(0).index.expect(4.U)
      publish(dut)
      dut.io.usedBases(0).expect(5.U)

      Seq(0, 1, 2, 3, 4, 0).zipWithIndex.foreach {
        case (tokenIndex, port) =>
          dut.io.readTokens(port).valid.poke(true.B)
          dut.io.readTokens(port).index.poke(tokenIndex.U)
          dut.io.readTokens(port).allocationEpoch.poke(0.U)
          dut.io.readValid(port).expect(true.B)
          dut.io.readPc(port).expect((tokenIndex * 256).U)
      }

      pokeCommit(dut, stid = 0,
        groups = (0 until 4).map(index => (index, index, 0)))
      commit(dut)
      dut.io.usedBases(0).expect(1.U)
      dut.io.readValid(0).expect(false.B)
      dut.io.readValid(4).expect(true.B)
      dut.io.readPc(4).expect(1024.U)

      pokeCommit(dut, stid = 0, groups = Seq((4, 4, 0)))
      commit(dut)
      dut.io.usedBases(0).expect(0.U)
    }
  }

  test("elaborates 1 2 and 4 PC banks with 2 4 and 6 decode width") {
    Seq((2, 1, 1, 1), (4, 2, 2, 2), (6, 4, 3, 4)).foreach {
      case (width, bankCount, writePorts, retireWidth) =>
        val p = OooParams(
          instructionDecodeWidth = width,
          pcBufferEntries = 64,
          pcBankCount = bankCount,
          pcWritePorts = writePorts,
          retireGroupWidth = retireWidth)
        simulate(new OooPcBuffer(p)) { dut =>
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
