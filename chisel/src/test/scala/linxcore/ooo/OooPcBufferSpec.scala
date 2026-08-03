package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class OooPcBufferSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooPcBuffer): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.publicationIdentity.valid.poke(false.B)
    dut.io.publicationIdentity.bits.poke(
      0.U.asTypeOf(dut.io.publicationIdentity.bits))
    dut.io.publishFire.poke(false.B)
    dut.io.commitPreview.valid.poke(false.B)
    dut.io.commitPreview.bits.poke(0.U.asTypeOf(dut.io.commitPreview.bits))
    dut.io.commitApply.poke(false.B)
    dut.io.recovery.prepare.valid.poke(false.B)
    dut.io.recovery.prepare.bits.poke(
      0.U.asTypeOf(dut.io.recovery.prepare.bits))
    dut.io.recovery.prepared.ready.poke(true.B)
    dut.io.recovery.apply.valid.poke(false.B)
    dut.io.recovery.apply.bits.poke(
      0.U.asTypeOf(dut.io.recovery.apply.bits))
    dut.io.recovery.abort.valid.poke(false.B)
    dut.io.recovery.abort.bits.poke(
      0.U.asTypeOf(dut.io.recovery.abort.bits))
    dut.io.readAddress.foreach(_.poke(0.U.asTypeOf(dut.io.readAddress.head)))
  }

  private def pokeRob(
      rob: RobIdentity,
      p: CoreParams,
      stid: Int,
      rid: Int,
      member: Int = 0,
      peId: Int = 3): Unit = {
    rob.peId.poke(peId.U)
    rob.stid.poke(stid.U)
    rob.ridSlot.poke((rid % p.ooo.robGroupsPerStid).U)
    rob.ridGeneration.poke((rid / p.ooo.robGroupsPerStid).U)
    rob.memberIndex.poke(member.U)
    rob.residentGeneration.poke((0x20 + rid * 4 + member).U)
    rob.bid.poke((7 + rid).U)
    rob.brobGeneration.poke((0x30 + rid).U)
  }

  private def pokePrepare(
      dut: OooPcBuffer,
      lanes: Seq[(Int, Int, Long)],
      stid: Int = 0,
      blockStops: Set[Int] = Set.empty,
      traps: Set[Int] = Set.empty): Unit = {
    val p = dut.p
    val groups = lanes.map(_._1).distinct
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.prepare.bits.count.poke(lanes.size.U)
    dut.io.prepare.bits.groupCount.poke(groups.size.U)
    groups.zipWithIndex.foreach { case (rid, groupIndex) =>
      val group = dut.io.prepare.bits.groups(groupIndex)
      group.valid.poke(true.B)
      group.peId.poke(3.U)
      group.stid.poke(stid.U)
      group.ridSlot.poke((rid % p.ooo.robGroupsPerStid).U)
      group.ridGeneration.poke((rid / p.ooo.robGroupsPerStid).U)
    }
    lanes.zipWithIndex.foreach { case ((rid, member, pc), laneIndex) =>
      val lane = dut.io.prepare.bits.entries(laneIndex)
      lane.uop.decoded.valid.poke(true.B)
      pokeRob(lane.uop.decoded.rob, p, stid, rid, member)
      lane.uop.decoded.instruction.parent.pc.poke(pc.U)
      lane.uop.decoded.instruction.parent.lengthBytes.poke(2.U)
      lane.uop.decoded.instruction.parent.identity.peId.poke(3.U)
      lane.uop.decoded.instruction.parent.identity.stid.poke(stid.U)
      lane.uop.decoded.instruction.parent.identity.instructionId.poke(
        (0x100 + laneIndex).U)
      lane.uop.decoded.instruction.parent.identity.epoch.poke(5.U)
      lane.uop.decoded.blockStop.poke(blockStops(laneIndex).B)
      lane.blockStop.poke(blockStops(laneIndex).B)
      lane.trap.valid.poke(traps(laneIndex).B)
      lane.trap.cause.poke((if (traps(laneIndex)) 9 else 0).U)
    }
    dut.io.prepare.valid.poke(true.B)
  }

  private def publish(
      dut: OooPcBuffer,
      residentGenerationDelta: Int = 0,
      bidDelta: Int = 0,
      brobGenerationDelta: Int = 0): Unit = {
    dut.io.prepareReady.expect(true.B)
    val count = dut.io.prepared.count.peek().litValue.toInt
    dut.io.publicationIdentity.bits.poke(
      0.U.asTypeOf(dut.io.publicationIdentity.bits))
    dut.io.publicationIdentity.bits.count.poke(count.U)
    for (lane <- 0 until count) {
      val source = dut.io.prepare.bits.entries(lane).uop.decoded.rob
      val bound = dut.io.publicationIdentity.bits.entries(lane)
      bound.valid.poke(true.B)
      bound.rob.poke(source.peek())
      bound.rob.residentGeneration.poke(
        (source.residentGeneration.peek().litValue + residentGenerationDelta).U)
      bound.rob.bid.poke((source.bid.peek().litValue + bidDelta).U)
      bound.rob.brobGeneration.poke(
        (source.brobGeneration.peek().litValue + brobGenerationDelta).U)
    }
    dut.io.publicationIdentity.valid.poke(true.B)
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.publishFire.poke(false.B)
    dut.io.publicationIdentity.valid.poke(false.B)
    dut.io.prepare.valid.poke(false.B)
  }

  private def readBase(
      dut: OooPcBuffer,
      port: Int,
      stid: Int,
      index: Int,
      epoch: Int,
      valid: Boolean,
      base: Long = 0): Unit = {
    val address = dut.io.readAddress(port)
    address.valid.poke(true.B)
    address.stid.poke(stid.U)
    address.pcBufferIndex.poke(index.U)
    address.allocationEpoch.poke(epoch.U)
    dut.io.readPcBase(port).valid.expect(valid.B)
    if (valid) dut.io.readPcBase(port).bits.expect(base.U)
  }

  private def pokeCommit(
      dut: OooPcBuffer,
      rows: Seq[(Int, Int, Int, Boolean)],
      stid: Int = 0): Unit = {
    dut.io.commitPreview.bits.poke(
      0.U.asTypeOf(dut.io.commitPreview.bits))
    dut.io.commitPreview.bits.count.poke(rows.size.U)
    rows.zipWithIndex.foreach { case ((rid, index, epoch, groupLast), lane) =>
      val entry = dut.io.commitPreview.bits.entries(lane)
      pokeRob(entry.rob, dut.p, stid, rid)
      entry.pcBufferIndexOffset.valid.poke(true.B)
      entry.pcBufferIndexOffset.pcBufferIndex.poke(index.U)
      entry.pcBufferIndexOffset.pcOffset.poke(0.U)
      entry.pcBufferIndexOffset.allocationEpoch.poke(epoch.U)
      entry.robGroupLast.poke(groupLast.B)
    }
    dut.io.commitPreview.valid.poke(true.B)
  }

  private def pokeRecoveryPrepare(
      dut: OooPcBuffer,
      firstRid: Int,
      lastRid: Int,
      survivingRid: Option[Int],
      stid: Int = 0,
      transactionId: Int = 0x55,
      residentGenerationDelta: Int = 0,
      bidDelta: Int = 0,
      brobGenerationDelta: Int = 0): Unit = {
    def bindIdentity(rob: RobIdentity, rid: Int): Unit = {
      rob.residentGeneration.poke((0x20 + rid * 4 +
        residentGenerationDelta).U)
      rob.bid.poke((7 + rid + bidDelta).U)
      rob.brobGeneration.poke((0x30 + rid +
        brobGenerationDelta).U)
    }
    val plan = dut.io.recovery.prepare.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.transactionId.poke(transactionId.U)
    plan.phase.poke(RecoveryPhase.Prepare)
    plan.cause.poke(RecoveryCause.Branch)
    pokeRob(plan.trigger, dut.p, stid, firstRid)
    bindIdentity(plan.trigger, firstRid)
    plan.firstKilledValid.poke(true.B)
    pokeRob(plan.firstKilled, dut.p, stid, firstRid)
    bindIdentity(plan.firstKilled, firstRid)
    pokeRob(plan.lastKilled, dut.p, stid, lastRid)
    bindIdentity(plan.lastKilled, lastRid)
    plan.killedGroupCount.poke((lastRid - firstRid + 1).U)
    plan.killedMemberCount.poke((lastRid - firstRid + 1).U)
    plan.survivingTailValid.poke(survivingRid.nonEmpty.B)
    survivingRid.foreach { rid =>
      pokeRob(plan.survivingTail, dut.p, stid, rid)
      bindIdentity(plan.survivingTail, rid)
    }
    dut.io.recovery.prepare.valid.poke(true.B)
    dut.io.recovery.prepare.ready.expect(true.B)
    dut.clock.step()
    dut.io.recovery.prepare.valid.poke(false.B)
  }

  private def waitPrepared(dut: OooPcBuffer, maxCycles: Int = 64): Unit = {
    var cycles = 0
    while (!dut.io.recovery.prepared.valid.peek().litToBoolean &&
        cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < maxCycles, "PC-buffer recovery preparation did not finish")
    dut.io.recovery.prepared.bits.phase.expect(RecoveryPhase.Prepare)
  }

  private def terminateRecovery(dut: OooPcBuffer, apply: Boolean): Unit = {
    val terminal = if (apply) dut.io.recovery.apply else dut.io.recovery.abort
    terminal.bits.poke(dut.io.recovery.prepared.bits.peek())
    terminal.bits.phase.poke(
      if (apply) RecoveryPhase.Apply else RecoveryPhase.Abort)
    terminal.valid.poke(true.B)
    dut.clock.step()
    terminal.valid.poke(false.B)
  }

  test("groups exact ROB members onto one base and returns six stale-safe base reads") {
    simulate(new OooPcBuffer(ParamProfiles.W4)) { dut =>
      clear(dut)
      pokePrepare(dut, Seq((0, 0, 100L), (0, 1, 102L), (1, 0, 106L),
        (2, 0, 112L)), blockStops = Set(3))
      dut.io.prepared.count.expect(4.U)
      Seq(0, 2, 6, 12).zipWithIndex.foreach { case (offset, lane) =>
        dut.io.prepared.lanes(lane).valid.expect(true.B)
        dut.io.prepared.lanes(lane).pcBufferIndex.expect(0.U)
        dut.io.prepared.lanes(lane).pcOffset.expect(offset.U)
      }
      readBase(dut, 0, stid = 0, index = 0, epoch = 0, valid = false)
      publish(dut)
      for (port <- 0 until dut.p.ooo.pcReadPorts) {
        readBase(dut, port, stid = 0, index = 0, epoch = 0,
          valid = true, base = 100)
      }
      readBase(dut, 1, stid = 0, index = 0, epoch = 1, valid = false)
    }
  }

  test("admits the oldest three-base prefix and retries the younger suffix") {
    simulate(new OooPcBuffer(ParamProfiles.W4)) { dut =>
      clear(dut)
      pokePrepare(dut,
        Seq((0, 0, 0L), (1, 0, 1000L), (2, 0, 2000L), (3, 0, 3000L)))
      dut.io.prepareReady.expect(true.B)
      dut.io.prepared.count.expect(3.U)
      dut.io.prepared.groupCount.expect(3.U)
      dut.io.prepared.lanes(0).valid.expect(true.B)
      dut.io.prepared.lanes(1).valid.expect(true.B)
      dut.io.prepared.lanes(2).valid.expect(true.B)
      dut.io.prepared.lanes(3).valid.expect(false.B)
      publish(dut)
      readBase(dut, 0, 0, 0, 0, valid = true, base = 0)
      readBase(dut, 1, 0, 1, 0, valid = true, base = 1000)
      readBase(dut, 2, 0, 2, 0, valid = true, base = 2000)

      pokePrepare(dut, Seq((3, 0, 3000L)))
      dut.io.prepared.count.expect(1.U)
      dut.io.prepared.lanes(0).pcBufferIndex.expect(3.U)
      publish(dut)
      readBase(dut, 0, 0, 3, 0, valid = true, base = 3000)

      pokePrepare(dut,
        Seq((4, 0, 4000L), (5, 0, 5000L), (6, 0, 6000L)),
        traps = Set(2))
      dut.io.prepared.lanes(0).pcBufferIndex.expect(4.U)
      dut.io.prepared.lanes(1).pcBufferIndex.expect(5.U)
      dut.io.prepared.lanes(2).pcBufferIndex.expect(6.U)
      publish(dut)
      readBase(dut, 0, 0, 6, 0, valid = true, base = 6000)
    }
  }

  test("never splits a ROB group at the three-write boundary") {
    simulate(new OooPcBuffer(ParamProfiles.W8)) { dut =>
      clear(dut)
      pokePrepare(dut, Seq(
        (0, 0, 0L),
        (0, 1, 2L),
        (1, 0, 1000L),
        (2, 0, 2000L),
        (3, 0, 3000L),
        (3, 1, 3002L),
        (3, 2, 3004L),
        (3, 3, 3006L),
      ))

      dut.io.prepareReady.expect(true.B)
      dut.io.prepared.count.expect(4.U)
      dut.io.prepared.groupCount.expect(3.U)
      (0 until 4).foreach(lane =>
        dut.io.prepared.lanes(lane).valid.expect(true.B))
      (4 until 8).foreach(lane =>
        dut.io.prepared.lanes(lane).valid.expect(false.B))
      publish(dut)

      readBase(dut, 0, 0, 0, 0, valid = true, base = 0)
      readBase(dut, 1, 0, 1, 0, valid = true, base = 1000)
      readBase(dut, 2, 0, 2, 0, valid = true, base = 2000)
      readBase(dut, 3, 0, 3, 0, valid = false)
    }
  }

  test("applies an ordered common commit only on commitApply and rejects stale epochs") {
    simulate(new OooPcBuffer(ParamProfiles.W4)) { dut =>
      clear(dut)
      pokePrepare(dut, Seq((0, 0, 100L), (1, 0, 102L)),
        blockStops = Set(1))
      publish(dut)

      pokeCommit(dut, Seq((0, 0, 1, true), (1, 0, 0, true)))
      dut.io.commitReady.expect(false.B)
      pokeCommit(dut, Seq((0, 0, 0, true), (1, 0, 0, true)))
      dut.io.commitReady.expect(true.B)
      dut.clock.step()
      readBase(dut, 0, 0, 0, 0, valid = true, base = 100)
      dut.io.commitApply.poke(true.B)
      dut.clock.step()
      dut.io.commitApply.poke(false.B)
      dut.io.commitPreview.valid.poke(false.B)
      readBase(dut, 0, 0, 0, 0, valid = false)
    }
  }

  test("aborts without mutation then applies an exact suffix and reopens its survivor") {
    simulate(new OooPcBuffer(ParamProfiles.W4)) { dut =>
      clear(dut)
      pokePrepare(dut,
        Seq((0, 0, 100L), (1, 0, 1000L), (2, 0, 2000L)))
      publish(dut)

      pokeRecoveryPrepare(dut, firstRid = 1, lastRid = 2,
        survivingRid = Some(0))
      waitPrepared(dut)
      terminateRecovery(dut, apply = false)
      readBase(dut, 0, 0, 1, 0, valid = true, base = 1000)
      readBase(dut, 1, 0, 2, 0, valid = true, base = 2000)

      pokeRecoveryPrepare(dut, firstRid = 1, lastRid = 2,
        survivingRid = Some(0), transactionId = 0x56)
      waitPrepared(dut)
      terminateRecovery(dut, apply = true)
      readBase(dut, 0, 0, 0, 0, valid = true, base = 100)
      readBase(dut, 1, 0, 1, 0, valid = false)
      readBase(dut, 2, 0, 2, 0, valid = false)

      pokePrepare(dut, Seq((1, 0, 1000L)))
      dut.io.prepared.lanes(0).pcBufferIndex.expect(1.U)
      publish(dut)
    }
  }

  test("redirected stop closes and frees an open-current recovery survivor") {
    simulate(new OooPcBuffer(ParamProfiles.W4)) { dut =>
      clear(dut)
      pokePrepare(dut,
        Seq((0, 0, 0x7000L), (1, 0, 0x7004L), (2, 0, 0x7008L)),
        blockStops = Set(2))
      publish(dut)
      readBase(dut, 0, 0, 0, 0, valid = true, base = 0x7000L)

      pokeRecoveryPrepare(dut, firstRid = 1, lastRid = 2,
        survivingRid = Some(0))
      waitPrepared(dut)
      terminateRecovery(dut, apply = true)

      pokeCommit(dut, Seq((0, 0, 0, true)))
      dut.io.commitReady.expect(true.B)
      dut.io.commitApply.poke(true.B)
      dut.clock.step()
      dut.io.commitApply.poke(false.B)
      dut.io.commitPreview.valid.poke(false.B)
      readBase(dut, 0, 0, 0, 0, valid = true, base = 0x7000L)

      pokePrepare(dut, Seq((1, 0, 0x7008L)), blockStops = Set(0))
      dut.io.prepared.lanes.head.pcBufferIndex.expect(0.U)
      dut.io.prepared.lanes.head.allocationEpoch.expect(0.U)
      publish(dut)

      pokeCommit(dut, Seq((1, 0, 0, true)))
      dut.io.commitReady.expect(true.B)
      dut.io.commitApply.poke(true.B)
      dut.clock.step()
      dut.io.commitApply.poke(false.B)
      dut.io.commitPreview.valid.poke(false.B)
      readBase(dut, 0, 0, 0, 0, valid = false)
    }
  }

  test("retains ROB-bound endpoint identities across PC-buffer publication") {
    simulate(new OooPcBuffer(ParamProfiles.W4)) { dut =>
      clear(dut)
      pokePrepare(dut,
        Seq((0, 0, 100L), (1, 0, 1000L), (2, 0, 2000L)))
      publish(dut, residentGenerationDelta = 9, bidDelta = 1,
        brobGenerationDelta = 7)

      pokeRecoveryPrepare(dut, firstRid = 1, lastRid = 2,
        survivingRid = Some(0), residentGenerationDelta = 9, bidDelta = 1,
        brobGenerationDelta = 7)
      waitPrepared(dut)
      terminateRecovery(dut, apply = true)
      readBase(dut, 0, 0, 0, 0, valid = true, base = 100)
      readBase(dut, 1, 0, 1, 0, valid = false)
      readBase(dut, 2, 0, 2, 0, valid = false)
    }
  }

  test("fences only the recovery target STID while a peer publishes") {
    val p = ParamProfiles.W4.copy(
      ooo = ParamProfiles.W4.ooo.copy(stidCount = 2))
    simulate(new OooPcBuffer(p)) { dut =>
      clear(dut)
      pokePrepare(dut,
        Seq((0, 0, 100L), (1, 0, 1000L), (2, 0, 2000L)), stid = 0)
      publish(dut)
      pokeRecoveryPrepare(dut, firstRid = 1, lastRid = 2,
        survivingRid = Some(0), stid = 0)

      pokePrepare(dut, Seq((0, 0, 500L)), stid = 1)
      dut.io.prepareReady.expect(true.B)
      publish(dut)
      readBase(dut, 0, 1, 32, 0, valid = true, base = 500)

      pokePrepare(dut, Seq((3, 0, 3000L)), stid = 0)
      dut.io.prepareReady.expect(false.B)
      dut.io.prepare.valid.poke(false.B)
      waitPrepared(dut)
      terminateRecovery(dut, apply = true)
    }
  }
}
