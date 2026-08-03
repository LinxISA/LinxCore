package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface.{D2AdmissionGroup, D3RenameGroup, OperandKind,
  RecoveryPhase, UopClass}
import org.scalatest.funsuite.AnyFunSuite

class RENUAtomicSpec extends AnyFunSuite with ChiselSim {
  private def base(width: Int, stids: Int = 1): CoreParams =
    ParamProfiles.forWidth(width).copy(
      ooo = ParamProfiles.forWidth(width).ooo.copy(
        stidCount = stids,
        robGroupsPerStid = 8,
        gprPhysRegs = if (stids == 1) 32 else 64,
        gprMapQDepthPerStid = 8,
        tPhysRegs = 8,
        uPhysRegs = 8,
        tuMapQDepthPerStid = 8))

  private def clear(dut: RENU): Unit = {
    dut.io.fromD2.valid.poke(false.B)
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.toD3.ready.poke(true.B)
    dut.io.prefixLimit.valid.poke(true.B)
    dut.io.prefixLimit.bits.count.poke(1.U)
    dut.io.prefixLimit.bits.groupCount.poke(1.U)
    dut.io.publicationIdentity.valid.poke(false.B)
    dut.io.publicationIdentity.bits.poke(
      0.U.asTypeOf(dut.io.publicationIdentity.bits))
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.releaseApply.poke(true.B)
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
  }

  private def pokeGpr(
      group: D2AdmissionGroup,
      lane: Int,
      id: Int,
      dst: Int,
      stid: Int = 0,
      rid: Int = -1,
      member: Int = -1): Unit = {
    val ridSlot = if (rid >= 0) rid else lane
    val memberIndex = if (member >= 0) member else lane
    val row = group.entries(lane)
    row.uop.valid.poke(true.B)
    row.uop.instruction.parent.identity.peId.poke(1.U)
    row.uop.instruction.parent.identity.stid.poke(stid.U)
    row.uop.instruction.parent.identity.instructionId.poke(id.U)
    row.uop.instruction.parent.identity.epoch.poke(3.U)
    row.uop.rob.peId.poke(1.U)
    row.uop.rob.stid.poke(stid.U)
    row.uop.rob.ridSlot.poke(ridSlot.U)
    row.uop.rob.ridGeneration.poke(5.U)
    row.uop.rob.memberIndex.poke(memberIndex.U)
    row.uop.uopClass.poke(UopClass.Alu)
    row.uop.destinations(0).valid.poke(true.B)
    row.uop.destinations(0).kind.poke(OperandKind.Gpr)
    row.uop.destinations(0).atag.poke(dst.U)
    row.uop.sources(0).valid.poke(true.B)
    row.uop.sources(0).kind.poke(OperandKind.Gpr)
    row.uop.sources(0).atag.poke(2.U)
    if (group.groupCount.peek().litValue == 1) {
      val intent = group.groups(0)
      intent.valid.poke(true.B)
      intent.peId.poke(1.U)
      intent.stid.poke(stid.U)
      intent.ridSlot.poke(ridSlot.U)
      intent.ridGeneration.poke(5.U)
    }
  }

  private def pokeBoundary(
      group: D2AdmissionGroup,
      lane: Int,
      id: Int,
      stid: Int,
      rid: Int): Unit = {
    val row = group.entries(lane)
    row.uop.valid.poke(true.B)
    row.uop.instruction.parent.identity.peId.poke(1.U)
    row.uop.instruction.parent.identity.stid.poke(stid.U)
    row.uop.instruction.parent.identity.instructionId.poke(id.U)
    row.uop.instruction.parent.identity.epoch.poke(3.U)
    row.uop.rob.peId.poke(1.U)
    row.uop.rob.stid.poke(stid.U)
    row.uop.rob.ridSlot.poke(rid.U)
    row.uop.rob.ridGeneration.poke(5.U)
    row.uop.rob.memberIndex.poke(0.U)
    row.uop.uopClass.poke(UopClass.Boundary)
    row.uop.blockBoundary.poke(true.B)
    if (group.groupCount.peek().litValue == 1) {
      val intent = group.groups(0)
      intent.valid.poke(true.B)
      intent.peId.poke(1.U)
      intent.stid.poke(stid.U)
      intent.ridSlot.poke(rid.U)
      intent.ridGeneration.poke(5.U)
    }
  }

  private def pokeLocalDest(
      group: D2AdmissionGroup,
      lane: Int,
      id: Int,
      rid: Int,
      kinds: Seq[OperandKind.Type]): Unit = {
    val row = group.entries(lane)
    row.uop.valid.poke(true.B)
    row.uop.instruction.parent.identity.peId.poke(1.U)
    row.uop.instruction.parent.identity.stid.poke(0.U)
    row.uop.instruction.parent.identity.instructionId.poke(id.U)
    row.uop.instruction.parent.identity.epoch.poke(3.U)
    row.uop.rob.peId.poke(1.U)
    row.uop.rob.stid.poke(0.U)
    row.uop.rob.ridSlot.poke(rid.U)
    row.uop.rob.ridGeneration.poke(5.U)
    row.uop.rob.memberIndex.poke(0.U)
    row.uop.uopClass.poke(UopClass.Alu)
    kinds.zipWithIndex.foreach { case (kind, dest) =>
      row.uop.destinations(dest).valid.poke(true.B)
      row.uop.destinations(dest).kind.poke(kind)
      row.uop.destinations(dest).atag.poke((dest + 1).U)
    }
    if (group.groupCount.peek().litValue == 1) {
      val intent = group.groups(0)
      intent.valid.poke(true.B)
      intent.peId.poke(1.U)
      intent.stid.poke(0.U)
      intent.ridSlot.poke(rid.U)
      intent.ridGeneration.poke(5.U)
    }
  }

  private def driveOne(
      dut: RENU,
      id: Int,
      dst: Int,
      stid: Int = 0,
      rid: Int = 0): Unit = {
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.fromD2.bits.count.poke(1.U)
    dut.io.fromD2.bits.groupCount.poke(1.U)
    dut.io.prefixLimit.bits.count.poke(1.U)
    dut.io.prefixLimit.bits.groupCount.poke(1.U)
    pokeGpr(dut.io.fromD2.bits, 0, id = id, dst = dst, stid = stid, rid = rid)
    dut.io.fromD2.valid.poke(true.B)
    dut.io.fromD2.ready.expect(true.B)
    dut.clock.step()
    dut.io.fromD2.valid.poke(false.B)
  }

  private def acceptHeld(dut: RENU): D3RenameGroup = {
    dut.io.toD3.valid.expect(true.B)
    val held = dut.io.toD3.bits.peek()
    dut.io.toD3.ready.poke(true.B)
    dut.clock.step()
    dut.io.toD3.ready.poke(false.B)
    held
  }

  private def releaseRows(dut: RENU, rows: Seq[D3RenameGroup]): Unit = {
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.release.bits.count.poke(rows.length.U)
    rows.zipWithIndex.foreach { case (row, lane) =>
      dut.io.release.bits.lanes(lane).valid.poke(true.B)
      dut.io.release.bits.lanes(lane).rob.poke(
        row.entries(0).uop.decoded.rob)
      dut.io.release.bits.lanes(lane).history(0).poke(row.entries(0).history(0))
    }
    dut.io.release.valid.poke(true.B)
    dut.clock.step()
    dut.io.release.valid.poke(false.B)
  }

  private def applyRecovery(
      dut: RENU,
      transactionId: Int,
      triggerRid: Int,
      survivingRid: Option[Int]): Unit = {
    dut.io.recovery.prepare.valid.poke(true.B)
    dut.io.recovery.prepare.bits.phase.poke(RecoveryPhase.Prepare)
    dut.io.recovery.prepare.bits.transactionId.poke(transactionId.U)
    dut.io.recovery.prepare.bits.trigger.peId.poke(1.U)
    dut.io.recovery.prepare.bits.trigger.stid.poke(0.U)
    dut.io.recovery.prepare.bits.trigger.ridSlot.poke(triggerRid.U)
    dut.io.recovery.prepare.bits.trigger.ridGeneration.poke(5.U)
    dut.io.recovery.prepare.bits.survivingTailValid.poke(
      survivingRid.isDefined.B)
    dut.io.recovery.prepare.bits.survivingTail.peId.poke(1.U)
    dut.io.recovery.prepare.bits.survivingTail.stid.poke(0.U)
    dut.io.recovery.prepare.bits.survivingTail.ridSlot.poke(
      survivingRid.getOrElse(0).U)
    dut.io.recovery.prepare.bits.survivingTail.ridGeneration.poke(5.U)
    dut.io.recovery.prepare.ready.expect(true.B)
    dut.clock.step()
    dut.io.recovery.prepare.valid.poke(false.B)
    dut.io.recovery.apply.valid.poke(true.B)
    dut.io.recovery.apply.bits.poke(dut.io.recovery.prepare.bits.peek())
    dut.io.recovery.apply.bits.phase.poke(RecoveryPhase.Apply)
    dut.clock.step()
    dut.io.recovery.apply.valid.poke(false.B)
  }

  test("held D3 arbitration does not switch when lower STID arrives") {
    simulate(new RENU(base(2, stids = 2))) { dut =>
      clear(dut)
      dut.io.toD3.ready.poke(false.B)
      driveOne(dut, id = 10, dst = 1, stid = 1, rid = 1)
      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.entries(0).uop.decoded.instruction.parent.identity.stid
        .expect(1.U)
      val stid1P = dut.io.toD3.bits.entries(0).history(0).ptag.peek().litValue

      driveOne(dut, id = 11, dst = 1, stid = 0, rid = 1)
      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.entries(0).uop.decoded.instruction.parent.identity.stid
        .expect(1.U)
      dut.io.toD3.bits.entries(0).history(0).ptag.expect(stid1P.U)

      dut.io.recovery.prepare.bits.poke(
        0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recovery.prepare.bits.transactionId.poke(0x11.U)
      dut.io.recovery.prepare.bits.trigger.stid.poke(1.U)
      dut.io.recovery.prepare.bits.trigger.ridSlot.poke(1.U)
      dut.io.recovery.prepare.bits.trigger.ridGeneration.poke(5.U)
      dut.io.recovery.prepare.valid.poke(true.B)

      // A recovery request targeting the currently presented D3 row cannot
      // revoke or replace that Decoupled payload while it is stalled.
      dut.io.recovery.prepare.ready.expect(false.B)
      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.entries(0).uop.decoded.instruction.parent.identity.stid
        .expect(1.U)
      dut.io.toD3.bits.entries(0).history(0).ptag.expect(stid1P.U)

      // Let the irrevocable target row fire first.  The still-valid recovery
      // request can then be accepted while the unrelated retained row fires.
      dut.io.toD3.ready.poke(true.B)
      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.entries(0).uop.decoded.instruction.parent.identity.stid
        .expect(1.U)
      dut.clock.step()
      dut.io.recovery.prepare.ready.expect(true.B)
      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.entries(0).uop.decoded.instruction.parent.identity.stid
        .expect(0.U)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)

      // Prepare fences only the target STID; new unrelated work remains
      // admissible while the recovery transaction is pending.
      dut.io.toD3.ready.poke(false.B)
      driveOne(dut, id = 12, dst = 2, stid = 0, rid = 2)
    }
  }

  test("two STID provisional P leases allocate unique physical tags") {
    simulate(new RENU(base(2, stids = 2))) { dut =>
      clear(dut)
      dut.io.toD3.ready.poke(false.B)
      driveOne(dut, id = 20, dst = 1, stid = 1, rid = 1)
      val stid1P = dut.io.toD3.bits.entries(0).history(0).ptag.peek().litValue

      driveOne(dut, id = 21, dst = 1, stid = 0, rid = 1)
      dut.io.toD3.ready.poke(true.B)
      dut.clock.step()
      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.entries(0).uop.decoded.instruction.parent.identity.stid
        .expect(0.U)
      val stid0P = dut.io.toD3.bits.entries(0).history(0).ptag.peek().litValue
      assert(stid0P != stid1P)
    }
  }

  test("P release is all-or-none and a bad head row blocks later head plus one") {
    simulate(new RENU(base(2))) { dut =>
      clear(dut)
      dut.io.toD3.ready.poke(false.B)
      driveOne(dut, id = 30, dst = 1, rid = 1)
      val first = acceptHeld(dut)
      val firstP = first.entries(0).history(0).ptag.litValue

      driveOne(dut, id = 31, dst = 1, rid = 2)
      val second = acceptHeld(dut)

      dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
      dut.io.release.bits.count.poke(2.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).rob.poke(first.entries(0).uop.decoded.rob)
      dut.io.release.bits.lanes(0).rob.ridSlot.poke(7.U)
      dut.io.release.bits.lanes(0).history(0).poke(first.entries(0).history(0))
      dut.io.release.bits.lanes(1).valid.poke(true.B)
      dut.io.release.bits.lanes(1).rob.poke(second.entries(0).uop.decoded.rob)
      dut.io.release.bits.lanes(1).history(0).poke(second.entries(0).history(0))
      dut.io.release.valid.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)

      driveOne(dut, id = 32, dst = 2, rid = 3)
      dut.io.toD3.bits.entries(0).history(0).ptag.expect((firstP + 2).U)
    }
  }

  test("P MapQ index and generation advance exactly across circular wrap") {
    val params = base(2).copy(ooo = base(2).ooo.copy(
      gprMapQDepthPerStid = 4))
    simulate(new RENU(params)) { dut =>
      clear(dut)
      dut.io.toD3.ready.poke(false.B)
      (0 until 3).foreach { i =>
        driveOne(dut, id = 40 + i, dst = 1 + i, rid = 1 + i)
        val row = acceptHeld(dut)
        releaseRows(dut, Seq(row))
      }

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(2.U)
      dut.io.fromD2.bits.groupCount.poke(2.U)
      dut.io.prefixLimit.bits.count.poke(2.U)
      dut.io.prefixLimit.bits.groupCount.poke(2.U)
      pokeGpr(dut.io.fromD2.bits, 0, id = 41, dst = 2, rid = 2, member = 0)
      pokeGpr(dut.io.fromD2.bits, 1, id = 42, dst = 3, rid = 3, member = 0)
      for ((rid, group) <- Seq(2, 3).zipWithIndex) {
        val intent = dut.io.fromD2.bits.groups(group)
        intent.valid.poke(true.B)
        intent.peId.poke(1.U)
        intent.stid.poke(0.U)
        intent.ridSlot.poke(rid.U)
        intent.ridGeneration.poke(5.U)
      }
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)

      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.entries(0).history(0).pMapQIndex.expect(3.U)
      dut.io.toD3.bits.entries(0).history(0).pMapQGeneration.expect(0.U)
      dut.io.toD3.bits.entries(1).history(0).pMapQIndex.expect(0.U)
      dut.io.toD3.bits.entries(1).history(0).pMapQGeneration.expect(1.U)
    }
  }

  test("zero-destination survivor preserves older P rows during recovery") {
    simulate(new RENU(base(2))) { dut =>
      clear(dut)
      dut.io.toD3.ready.poke(false.B)
      driveOne(dut, id = 50, dst = 1, rid = 1)
      val oldest = acceptHeld(dut)
      val oldestP = oldest.entries(0).history(0).ptag.litValue

      driveOne(dut, id = 51, dst = 2, rid = 2)
      val older = acceptHeld(dut)
      val olderP = older.entries(0).history(0).ptag.litValue

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeBoundary(dut.io.fromD2.bits, 0, id = 52, stid = 0, rid = 3)
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      acceptHeld(dut)

      driveOne(dut, id = 53, dst = 3, rid = 4)
      val younger = acceptHeld(dut)
      val youngerP = younger.entries(0).history(0).ptag.litValue

      applyRecovery(dut, transactionId = 0x52, triggerRid = 4,
        survivingRid = Some(3))
      dut.io.debugPMap(0)(1).expect(oldestP.U)
      dut.io.debugPMap(0)(2).expect(olderP.U)

      driveOne(dut, id = 54, dst = 4, rid = 5)
      dut.io.toD3.bits.entries(0).history(0).ptag.expect(youngerP.U)
    }
  }

  test("previous P generation is preserved when a recycled tag is overwritten") {
    simulate(new RENU(base(2))) { dut =>
      clear(dut)
      dut.io.toD3.ready.poke(false.B)

      driveOne(dut, id = 60, dst = 1, rid = 1)
      val first = acceptHeld(dut)
      releaseRows(dut, Seq(first))

      driveOne(dut, id = 61, dst = 1, rid = 2)
      val second = acceptHeld(dut)
      releaseRows(dut, Seq(second))

      driveOne(dut, id = 62, dst = 1, rid = 3)
      val third = acceptHeld(dut)
      assert(third.entries(0).history(0).pGeneration.litValue == 1)
      assert(third.entries(0).history(0).previousPGeneration.litValue == 0)
      releaseRows(dut, Seq(third))

      driveOne(dut, id = 63, dst = 1, rid = 4)
      dut.io.toD3.bits.entries(0).history(0).pGeneration.expect(1.U)
      dut.io.toD3.bits.entries(0).history(0).previousPGeneration.expect(1.U)
      dut.io.toD3.bits.entries(0).uop.destinations(0)
        .previousPGeneration.expect(1.U)
    }
  }

  test("release is atomic across P T and U owners") {
    simulate(new RENU(base(2))) { dut =>
      clear(dut)
      dut.io.toD3.ready.poke(false.B)

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeLocalDest(dut.io.fromD2.bits, lane = 0, id = 70, rid = 1,
        kinds = Seq(OperandKind.Gpr, OperandKind.T))
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      val mixedPAndT = acceptHeld(dut)

      dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).rob.poke(
        mixedPAndT.entries(0).uop.decoded.rob)
      dut.io.release.bits.lanes(0).history(0).poke(
        mixedPAndT.entries(0).history(0))
      dut.io.release.bits.lanes(0).history(1).poke(
        mixedPAndT.entries(0).history(1))
      dut.io.release.bits.lanes(0).history(1).tMapQGeneration.poke(7.U)
      dut.io.release.valid.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)

      applyRecovery(dut, transactionId = 0x70, triggerRid = 1,
        survivingRid = None)
      dut.io.debugPMap(0)(1).expect(1.U)

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeLocalDest(dut.io.fromD2.bits, lane = 0, id = 71, rid = 2,
        kinds = Seq(OperandKind.T, OperandKind.U))
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      val mixedTAndU = acceptHeld(dut)

      dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).rob.poke(
        mixedTAndU.entries(0).uop.decoded.rob)
      dut.io.release.bits.lanes(0).history(0).poke(
        mixedTAndU.entries(0).history(0))
      dut.io.release.bits.lanes(0).history(1).poke(
        mixedTAndU.entries(0).history(1))
      dut.io.release.bits.lanes(0).history(1).uMapQGeneration.poke(7.U)
      dut.io.release.valid.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)

      applyRecovery(dut, transactionId = 0x71, triggerRid = 2,
        survivingRid = None)

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeLocalDest(dut.io.fromD2.bits, lane = 0, id = 72, rid = 3,
        kinds = Seq(OperandKind.T, OperandKind.U))
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      dut.io.toD3.bits.entries(0).history(0).ttag.expect(0.U)
      dut.io.toD3.bits.entries(0).history(1).utag.expect(0.U)
      acceptHeld(dut)
      applyRecovery(dut, transactionId = 0x72, triggerRid = 3,
        survivingRid = None)

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(2.U)
      dut.io.fromD2.bits.groupCount.poke(2.U)
      dut.io.prefixLimit.bits.count.poke(2.U)
      dut.io.prefixLimit.bits.groupCount.poke(2.U)
      pokeGpr(dut.io.fromD2.bits, 0, id = 73, dst = 1, rid = 4, member = 0)
      pokeGpr(dut.io.fromD2.bits, 1, id = 74, dst = 2, rid = 5, member = 0)
      for ((rid, group) <- Seq(4, 5).zipWithIndex) {
        val intent = dut.io.fromD2.bits.groups(group)
        intent.valid.poke(true.B)
        intent.peId.poke(1.U)
        intent.stid.poke(0.U)
        intent.ridSlot.poke(rid.U)
        intent.ridGeneration.poke(5.U)
      }
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      val twoP = acceptHeld(dut)

      // A count prefix with a validity hole is malformed. It must not let the
      // later lane commit the current head row.
      dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
      dut.io.release.bits.count.poke(2.U)
      dut.io.release.bits.lanes(1).valid.poke(true.B)
      dut.io.release.bits.lanes(1).rob.poke(twoP.entries(0).uop.decoded.rob)
      dut.io.release.bits.lanes(1).history(0).poke(twoP.entries(0).history(0))
      dut.io.release.valid.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      applyRecovery(dut, transactionId = 0x73, triggerRid = 5,
        survivingRid = None)
      dut.io.debugPMap(0)(1).expect(1.U)
      dut.io.debugPMap(0)(2).expect(2.U)

      driveOne(dut, id = 75, dst = 3, rid = 6)
      val releaseDuringRecovery = acceptHeld(dut)
      dut.io.recovery.prepare.bits.poke(
        0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recovery.prepare.bits.transactionId.poke(0x74.U)
      dut.io.recovery.prepare.bits.trigger.peId.poke(1.U)
      dut.io.recovery.prepare.bits.trigger.stid.poke(0.U)
      dut.io.recovery.prepare.bits.trigger.ridSlot.poke(6.U)
      dut.io.recovery.prepare.bits.trigger.ridGeneration.poke(5.U)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.io.recovery.prepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)

      dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).rob.poke(
        releaseDuringRecovery.entries(0).uop.decoded.rob)
      dut.io.release.bits.lanes(0).history(0).poke(
        releaseDuringRecovery.entries(0).history(0))
      dut.io.release.valid.poke(true.B)
      dut.io.recovery.apply.bits.poke(
        0.U.asTypeOf(dut.io.recovery.apply.bits))
      dut.io.recovery.apply.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.recovery.apply.bits.transactionId.poke(0x74.U)
      dut.io.recovery.apply.bits.trigger.peId.poke(1.U)
      dut.io.recovery.apply.bits.trigger.stid.poke(0.U)
      dut.io.recovery.apply.bits.trigger.ridSlot.poke(6.U)
      dut.io.recovery.apply.bits.trigger.ridGeneration.poke(5.U)
      dut.io.recovery.apply.valid.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.recovery.apply.valid.poke(false.B)

      applyRecovery(dut, transactionId = 0x75, triggerRid = 6,
        survivingRid = None)
      dut.io.debugPMap(0)(3).expect(3.U)

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(2.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGpr(dut.io.fromD2.bits, 1, id = 76, dst = 4, rid = 7)
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(false.B)
    }
  }
}
