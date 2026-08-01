package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface.{D2AdmissionGroup, D3RenameGroup, OperandKind,
  UopClass}
import org.scalatest.funsuite.AnyFunSuite

class RENUSpec extends AnyFunSuite with ChiselSim {
  private def base(width: Int, stids: Int = 1): CoreParams =
    ParamProfiles.forWidth(width).copy(
      ooo = ParamProfiles.forWidth(width).ooo.copy(
        stidCount = stids,
        robGroupsPerStid = 8,
        gprPhysRegs = if (stids == 1 && width <= 4) 32 else 64,
        gprMapQDepthPerStid = if (width <= 4) 8 else 16,
        tPhysRegs = if (width <= 4) 8 else 16,
        uPhysRegs = if (width <= 4) 8 else 16,
        tuMapQDepthPerStid = if (width <= 4) 8 else 16))

  private def clear(dut: RENU): Unit = {
    dut.io.fromD2.valid.poke(false.B)
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.toD3.ready.poke(true.B)
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

  private def pokeGprAdd(
      group: D2AdmissionGroup,
      lane: Int,
      id: Int,
      dst: Int,
      src0: Int,
      src1: Int,
      stid: Int = 0,
      rid: Int = -1): Unit = {
    val ridSlot = if (rid >= 0) rid else lane
    val row = group.entries(lane)
    row.uop.valid.poke(true.B)
    row.uop.instruction.parent.identity.peId.poke(1.U)
    row.uop.instruction.parent.identity.stid.poke(stid.U)
    row.uop.instruction.parent.identity.instructionId.poke(id.U)
    row.uop.instruction.parent.identity.epoch.poke(3.U)
    row.uop.rob.stid.poke(stid.U)
    row.uop.rob.ridSlot.poke(ridSlot.U)
    row.uop.rob.ridGeneration.poke(5.U)
    row.uop.rob.memberIndex.poke((lane % 4).U)
    row.uop.uopClass.poke(UopClass.Alu)
    row.uop.destinations(0).valid.poke(true.B)
    row.uop.destinations(0).kind.poke(OperandKind.Gpr)
    row.uop.destinations(0).atag.poke(dst.U)
    row.uop.sources(0).valid.poke(true.B)
    row.uop.sources(0).kind.poke(OperandKind.Gpr)
    row.uop.sources(0).atag.poke(src0.U)
    row.uop.sources(1).valid.poke(true.B)
    row.uop.sources(1).kind.poke(OperandKind.Gpr)
    row.uop.sources(1).atag.poke(src1.U)
  }

  private def pokeLocal(
      group: D2AdmissionGroup,
      lane: Int,
      id: Int,
      kind: OperandKind.Type,
      relSrc: Option[Int],
      stid: Int = 0,
      rid: Int = -1): Unit = {
    val ridSlot = if (rid >= 0) rid else lane
    val row = group.entries(lane)
    row.uop.valid.poke(true.B)
    row.uop.instruction.parent.identity.peId.poke(1.U)
    row.uop.instruction.parent.identity.stid.poke(stid.U)
    row.uop.instruction.parent.identity.instructionId.poke(id.U)
    row.uop.instruction.parent.identity.epoch.poke(3.U)
    row.uop.rob.stid.poke(stid.U)
    row.uop.rob.ridSlot.poke(ridSlot.U)
    row.uop.rob.ridGeneration.poke(5.U)
    row.uop.rob.memberIndex.poke((lane % 4).U)
    row.uop.uopClass.poke(UopClass.Alu)
    row.uop.destinations(0).valid.poke(true.B)
    row.uop.destinations(0).kind.poke(kind)
    row.uop.destinations(0).relativeIndex.poke(0.U)
    relSrc.foreach { rel =>
      row.uop.sources(0).valid.poke(true.B)
      row.uop.sources(0).kind.poke(kind)
      row.uop.sources(0).relativeIndex.poke(rel.U)
    }
  }

  private def publishOne(dut: RENU): Unit = {
    dut.io.fromD2.valid.poke(true.B)
    dut.io.fromD2.ready.expect(true.B)
    dut.clock.step()
    dut.io.fromD2.valid.poke(false.B)
    dut.io.toD3.valid.expect(true.B)
    dut.io.toD3.ready.poke(true.B)
    dut.clock.step()
    dut.io.toD3.ready.poke(true.B)
  }

  private def acceptHeld(dut: RENU): D3RenameGroup = {
    dut.io.toD3.valid.expect(true.B)
    val held = dut.io.toD3.bits.peek()
    dut.io.toD3.ready.poke(true.B)
    dut.clock.step()
    held
  }

  private def releaseHeld(dut: RENU, held: D3RenameGroup, laneCount: Int): Unit = {
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.release.bits.count.poke(laneCount.U)
    (0 until laneCount).foreach { lane =>
      dut.io.release.bits.lanes(lane).valid.poke(true.B)
      dut.io.release.bits.lanes(lane).rob.poke(
        held.entries(lane).uop.decoded.rob)
      dut.io.release.bits.lanes(lane).blockLast.poke(
        held.entries(lane).uop.decoded.blockBoundary)
      (0 until 2).foreach { dest =>
        dut.io.release.bits.lanes(lane).history(dest).poke(
          held.entries(lane).history(dest))
      }
    }
    dut.io.release.valid.poke(true.B)
    dut.clock.step()
    dut.io.release.valid.poke(false.B)
  }

  private def applyRecovery(
      dut: RENU,
      transactionId: Int,
      triggerRid: Int,
      surviving: Option[Int],
      stid: Int = 0): Unit = {
    dut.io.recovery.prepare.valid.poke(true.B)
    dut.io.recovery.prepare.bits.phase.poke(
      linxcore.top.interface.RecoveryPhase.Prepare)
    dut.io.recovery.prepare.bits.transactionId.poke(transactionId.U)
    dut.io.recovery.prepare.bits.trigger.stid.poke(stid.U)
    dut.io.recovery.prepare.bits.trigger.ridSlot.poke(triggerRid.U)
    dut.io.recovery.prepare.bits.trigger.ridGeneration.poke(5.U)
    surviving.foreach { rid =>
      dut.io.recovery.prepare.bits.survivingTailValid.poke(true.B)
      dut.io.recovery.prepare.bits.survivingTail.stid.poke(stid.U)
      dut.io.recovery.prepare.bits.survivingTail.ridSlot.poke(rid.U)
      dut.io.recovery.prepare.bits.survivingTail.ridGeneration.poke(5.U)
    }
    dut.io.recovery.prepare.ready.expect(true.B)
    dut.clock.step()
    dut.io.recovery.prepare.valid.poke(false.B)
    dut.io.recovery.apply.valid.poke(true.B)
    dut.io.recovery.apply.bits.phase.poke(
      linxcore.top.interface.RecoveryPhase.Apply)
    dut.io.recovery.apply.bits.transactionId.poke(transactionId.U)
    dut.io.recovery.apply.bits.trigger.stid.poke(stid.U)
    dut.io.recovery.apply.bits.trigger.ridSlot.poke(triggerRid.U)
    dut.io.recovery.apply.bits.trigger.ridGeneration.poke(5.U)
    surviving.foreach { rid =>
      dut.io.recovery.apply.bits.survivingTailValid.poke(true.B)
      dut.io.recovery.apply.bits.survivingTail.stid.poke(stid.U)
      dut.io.recovery.apply.bits.survivingTail.ridSlot.poke(rid.U)
      dut.io.recovery.apply.bits.survivingTail.ridGeneration.poke(5.U)
    }
    dut.clock.step()
    dut.io.recovery.apply.valid.poke(false.B)
  }

  test("publishes P SMAP and same-prefix forwarding only on common D3 fire") {
    simulate(new RENU(base(2))) { dut =>
      clear(dut)
      dut.io.fromD2.bits.count.poke(2.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 10, dst = 1, src0 = 2, src1 = 3)
      pokeGprAdd(dut.io.fromD2.bits, 1, id = 11, dst = 4, src0 = 1, src1 = 5)
      dut.io.toD3.ready.poke(false.B)
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()

      dut.io.fromD2.valid.poke(false.B)
      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.count.expect(2.U)
      dut.io.toD3.bits.entries(0).uop.destinations(0).ptagValid.expect(true.B)
      val firstPTag =
        dut.io.toD3.bits.entries(0).uop.destinations(0).ptag.peek().litValue
      dut.io.toD3.bits.entries(1).uop.sources(0).ptag.expect(firstPTag.U)
      dut.io.toD3.bits.entries(1).uop.sources(0).ready.expect(false.B)

      // The D2 fire retained only a provisional lease; the public SMAP has not
      // changed until the common D3 publication fires.
      dut.io.debugPMap(0)(1).expect(1.U)
      dut.clock.step(2)
      dut.io.debugPMap(0)(1).expect(1.U)

      dut.io.toD3.ready.poke(true.B)
      dut.clock.step()
      dut.io.debugPMap(0)(1).expect(firstPTag.U)
    }
  }

  test("allocates T and U as ordered relative namespaces independent from P") {
    simulate(new RENU(base(4))) { dut =>
      clear(dut)
      dut.io.fromD2.bits.count.poke(3.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeLocal(dut.io.fromD2.bits, 0, id = 20, OperandKind.T, None)
      pokeLocal(dut.io.fromD2.bits, 1, id = 21, OperandKind.T, Some(0))
      pokeLocal(dut.io.fromD2.bits, 2, id = 22, OperandKind.U, None)
      dut.io.fromD2.valid.poke(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)

      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.entries(0).uop.destinations(0).ttagValid.expect(true.B)
      val firstT =
        dut.io.toD3.bits.entries(0).uop.destinations(0).ttag.peek().litValue
      dut.io.toD3.bits.entries(1).uop.sources(0).ttag.expect(firstT.U)
      dut.io.toD3.bits.entries(2).uop.destinations(0).utagValid.expect(true.B)
      dut.io.toD3.bits.entries(2).uop.destinations(0).ttagValid.expect(false.B)
      dut.clock.step()
      dut.io.debugTCount(0).expect(2.U)
      dut.io.debugUCount(0).expect(1.U)
    }
  }

  test("rejects a whole prefix on T or U source underflow without mutation") {
    simulate(new RENU(base(2))) { dut =>
      clear(dut)
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeLocal(dut.io.fromD2.bits, 0, id = 30, OperandKind.T, Some(0))
      dut.io.fromD2.valid.poke(true.B)

      dut.io.fromD2.ready.expect(false.B)
      dut.clock.step()
      dut.io.debugTCount(0).expect(0.U)
      dut.io.toD3.valid.expect(false.B)
    }
  }

  test("same-lane dual GPR destinations carry WAW history in order") {
    simulate(new RENU(base(2))) { dut =>
      clear(dut)
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 40, dst = 1, src0 = 2, src1 = 3)
      val second = dut.io.fromD2.bits.entries(0).uop.destinations(1)
      second.valid.poke(true.B)
      second.kind.poke(OperandKind.Gpr)
      second.atag.poke(1.U)
      dut.io.toD3.ready.poke(false.B)
      dut.io.fromD2.valid.poke(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)

      val firstP =
        dut.io.toD3.bits.entries(0).uop.destinations(0).ptag.peek().litValue
      dut.io.toD3.bits.entries(0).history(0).previousPtag.expect(1.U)
      dut.io.toD3.bits.entries(0).history(1).previousPtag.expect(firstP.U)
      dut.io.toD3.ready.poke(true.B)
      dut.clock.step()
      val finalP =
        dut.io.toD3.bits.entries(0).uop.destinations(1).ptag.peek().litValue
      dut.io.debugPMap(0)(1).expect(finalP.U)
    }
  }

  test("recovery apply is STID scoped and preserves non-target state") {
    simulate(new RENU(base(2, stids = 2))) { dut =>
      clear(dut)
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 50, dst = 1, src0 = 2, src1 = 3,
        stid = 0, rid = 1)
      publishOne(dut)
      val survivingStid0P = dut.io.debugPMap(0)(1).peek().litValue

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 51, dst = 1, src0 = 2, src1 = 3,
        stid = 0, rid = 2)
      publishOne(dut)
      val killedStid0P = dut.io.debugPMap(0)(1).peek().litValue
      assert(killedStid0P != survivingStid0P)

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 52, dst = 1, src0 = 2, src1 = 3,
        stid = 1, rid = 1)
      publishOne(dut)
      val stid1P = dut.io.debugPMap(1)(1).peek().litValue

      applyRecovery(dut, transactionId = 0x55, triggerRid = 2,
        surviving = Some(1), stid = 0)

      dut.io.debugPMap(0)(1).expect(survivingStid0P.U)
      dut.io.debugPMap(1)(1).expect(stid1P.U)
    }
  }

  test("stale or non-head P release does not free the live tag") {
    simulate(new RENU(base(2))) { dut =>
      clear(dut)
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 60, dst = 1, src0 = 2, src1 = 3)
      publishOne(dut)
      val live = dut.io.debugPMap(0)(1).peek().litValue

      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).rob.stid.poke(0.U)
      dut.io.release.bits.lanes(0).rob.ridSlot.poke(7.U)
      dut.io.release.bits.lanes(0).history(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).history(0).kind.poke(OperandKind.Gpr)
      dut.io.release.bits.lanes(0).history(0).atag.poke(1.U)
      dut.io.release.bits.lanes(0).history(0).ptag.poke(live.U)
      dut.io.release.bits.lanes(0).history(0).previousPtag.poke(1.U)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.debugPMap(0)(1).expect(live.U)

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 61, dst = 2, src0 = 1, src1 = 3)
      dut.io.fromD2.valid.poke(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      dut.io.toD3.bits.entries(0).uop.destinations(0).ptag.expect((live + 1).U)
    }
  }

  test("recovery cancels an unpublished target-STID provisional row") {
    simulate(new RENU(base(2, stids = 2))) { dut =>
      clear(dut)
      dut.io.toD3.ready.poke(false.B)
      val initialStid1P = dut.io.debugPMap(1)(1).peek().litValue

      // Hold an unrelated STID 0 row at D3 so the target STID 1 lease remains
      // provisional but is not the irrevocable output transaction.
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 70, dst = 1, src0 = 2, src1 = 3,
        stid = 0, rid = 1)
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.entries(0).uop.decoded.instruction.parent.identity.stid
        .expect(0.U)

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 71, dst = 1, src0 = 2, src1 = 3,
        stid = 1, rid = 1)
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)

      dut.io.recovery.prepare.valid.poke(true.B)
      dut.io.recovery.prepare.bits.phase.poke(
        linxcore.top.interface.RecoveryPhase.Prepare)
      dut.io.recovery.prepare.bits.transactionId.poke(0x70.U)
      dut.io.recovery.prepare.bits.trigger.stid.poke(1.U)
      dut.io.recovery.prepare.bits.trigger.ridSlot.poke(1.U)
      dut.io.recovery.prepare.bits.trigger.ridGeneration.poke(5.U)
      dut.io.recovery.prepare.ready.expect(true.B)
      dut.io.toD3.bits.entries(0).uop.decoded.instruction.parent.identity.stid
        .expect(0.U)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.apply.valid.poke(true.B)
      dut.io.recovery.apply.bits.poke(dut.io.recovery.prepare.bits.peek())
      dut.io.recovery.apply.bits.phase.poke(
        linxcore.top.interface.RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)

      // Only the unrelated STID 0 row remains.  Publishing it must not reveal
      // the cancelled target row afterward.
      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.entries(0).uop.decoded.instruction.parent.identity.stid
        .expect(0.U)
      dut.io.toD3.ready.poke(true.B)
      dut.clock.step()
      dut.io.toD3.valid.expect(false.B)
      dut.io.debugPMap(1)(1).expect(initialStid1P.U)
    }
  }

  test("dual-destination commit frees an older same-uop P tag in exact order") {
    simulate(new RENU(base(2))) { dut =>
      clear(dut)
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 80, dst = 1, src0 = 2, src1 = 3,
        rid = 1)
      dut.io.fromD2.bits.entries(0).uop.destinations(1).valid.poke(true.B)
      dut.io.fromD2.bits.entries(0).uop.destinations(1).kind.poke(OperandKind.Gpr)
      dut.io.fromD2.bits.entries(0).uop.destinations(1).atag.poke(1.U)
      dut.io.toD3.ready.poke(false.B)
      dut.io.fromD2.valid.poke(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      val firstP =
        dut.io.toD3.bits.entries(0).history(0).ptag.peek().litValue
      val secondP =
        dut.io.toD3.bits.entries(0).history(1).ptag.peek().litValue
      assert(firstP != secondP)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).rob.poke(
        dut.io.toD3.bits.entries(0).uop.decoded.rob.peek())
      dut.io.release.bits.lanes(0).history(0).poke(
        dut.io.toD3.bits.entries(0).history(0).peek())
      dut.io.release.bits.lanes(0).history(1).poke(
        dut.io.toD3.bits.entries(0).history(1).peek())
      dut.io.toD3.ready.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 81, dst = 2, src0 = 1, src1 = 3,
        rid = 2)
      dut.io.fromD2.valid.poke(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      dut.io.toD3.bits.entries(0).history(0).ptag.expect(firstP.U)
    }
  }

  test("survivor recovery prunes suffix frees P tags and rewinds T and U") {
    simulate(new RENU(base(2))) { dut =>
      clear(dut)
      dut.io.toD3.ready.poke(false.B)
      dut.io.fromD2.bits.count.poke(2.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 90, dst = 1, src0 = 2, src1 = 3,
        rid = 1)
      pokeLocal(dut.io.fromD2.bits, 1, id = 91, OperandKind.T, None, rid = 2)
      dut.io.fromD2.bits.entries(1).uop.destinations(1).valid.poke(true.B)
      dut.io.fromD2.bits.entries(1).uop.destinations(1).kind.poke(OperandKind.U)
      dut.io.fromD2.valid.poke(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      val first = acceptHeld(dut)
      val survivorP = first.entries(0).history(0).ptag.litValue
      val killedPFreeCandidate = first.entries(1).history(0).ptag.litValue
      assert(killedPFreeCandidate != survivorP)
      dut.io.debugPMap(0)(1).expect(survivorP.U)
      dut.io.debugTCount(0).expect(1.U)
      dut.io.debugUCount(0).expect(1.U)

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 92, dst = 4, src0 = 1, src1 = 3,
        rid = 3)
      dut.io.fromD2.valid.poke(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      val suffixP = dut.io.toD3.bits.entries(0).history(0).ptag.peek().litValue
      acceptHeld(dut)

      applyRecovery(dut, transactionId = 0x90, triggerRid = 3,
        surviving = Some(1))
      dut.io.debugPMap(0)(1).expect(survivorP.U)
      dut.io.debugTCount(0).expect(0.U)
      dut.io.debugUCount(0).expect(0.U)

      dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 93, dst = 5, src0 = 1, src1 = 3,
        rid = 4)
      dut.io.fromD2.valid.poke(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      dut.io.toD3.bits.entries(0).history(0).ptag.expect(suffixP.U)
    }
  }

  test("stale T and U releases after wrap do not advance exact heads") {
    simulate(new RENU(base(2).copy(ooo = base(2).ooo.copy(
      tPhysRegs = 4,
      uPhysRegs = 4,
      tuMapQDepthPerStid = 4)))) { dut =>
      clear(dut)
      dut.io.toD3.ready.poke(false.B)
      (0 until 4).foreach { i =>
        dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
        dut.io.fromD2.bits.count.poke(1.U)
        dut.io.fromD2.bits.groupCount.poke(1.U)
        pokeLocal(dut.io.fromD2.bits, 0, id = 100 + i, OperandKind.T, None,
          rid = i)
        dut.io.fromD2.bits.entries(0).uop.destinations(1).valid.poke(true.B)
        dut.io.fromD2.bits.entries(0).uop.destinations(1).kind.poke(OperandKind.U)
        dut.io.fromD2.valid.poke(true.B)
        dut.clock.step()
        dut.io.fromD2.valid.poke(false.B)
        acceptHeld(dut)
      }
      dut.io.debugTCount(0).expect(4.U)
      dut.io.debugUCount(0).expect(4.U)

      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).rob.stid.poke(0.U)
      dut.io.release.bits.lanes(0).rob.ridSlot.poke(3.U)
      dut.io.release.bits.lanes(0).rob.ridGeneration.poke(5.U)
      dut.io.release.bits.lanes(0).history(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).history(0).kind.poke(OperandKind.T)
      dut.io.release.bits.lanes(0).history(0).ttag.poke(0.U)
      dut.io.release.bits.lanes(0).history(0).tGeneration.poke(0.U)
      dut.io.release.bits.lanes(0).history(1).valid.poke(true.B)
      dut.io.release.bits.lanes(0).history(1).kind.poke(OperandKind.U)
      dut.io.release.bits.lanes(0).history(1).utag.poke(0.U)
      dut.io.release.bits.lanes(0).history(1).uGeneration.poke(0.U)
      dut.io.release.valid.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.debugTCount(0).expect(4.U)
      dut.io.debugUCount(0).expect(4.U)
    }
  }

  test("D3 backpressure keeps payload stable and prevents duplicate mutation") {
    simulate(new RENU(base(2))) { dut =>
      clear(dut)
      dut.io.toD3.ready.poke(false.B)
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      pokeGprAdd(dut.io.fromD2.bits, 0, id = 110, dst = 1, src0 = 2, src1 = 3)
      dut.io.fromD2.valid.poke(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      val ptag = dut.io.toD3.bits.entries(0).history(0).ptag.peek().litValue
      val instructionId =
        dut.io.toD3.bits.entries(0).uop.decoded.instruction.parent.identity
          .instructionId.peek().litValue
      val ridSlot =
        dut.io.toD3.bits.entries(0).uop.decoded.rob.ridSlot.peek().litValue
      dut.clock.step(3)
      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.entries(0).history(0).ptag.expect(ptag.U)
      dut.io.toD3.bits.entries(0).uop.decoded.instruction.parent.identity
        .instructionId.expect(instructionId.U)
      dut.io.toD3.bits.entries(0).uop.decoded.rob.ridSlot.expect(ridSlot.U)
      dut.io.debugPMap(0)(1).expect(1.U)
      dut.io.toD3.ready.poke(true.B)
      dut.clock.step()
      dut.io.debugPMap(0)(1).expect(ptag.U)
      dut.clock.step(2)
      dut.io.debugPMap(0)(1).expect(ptag.U)
    }
  }

  test("zero-destination boundary retains a sidecar without allocating maps") {
    simulate(new RENU(base(2))) { dut =>
      clear(dut)
      dut.io.fromD2.bits.count.poke(1.U)
      dut.io.fromD2.bits.groupCount.poke(1.U)
      val row = dut.io.fromD2.bits.entries(0)
      row.uop.valid.poke(true.B)
      row.uop.instruction.parent.identity.peId.poke(1.U)
      row.uop.instruction.parent.identity.stid.poke(0.U)
      row.uop.instruction.parent.identity.instructionId.poke(120.U)
      row.uop.instruction.parent.identity.epoch.poke(3.U)
      row.uop.rob.stid.poke(0.U)
      row.uop.rob.ridSlot.poke(1.U)
      row.uop.rob.ridGeneration.poke(5.U)
      row.uop.uopClass.poke(UopClass.Boundary)
      row.uop.blockBoundary.poke(true.B)
      dut.io.fromD2.valid.poke(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.count.expect(1.U)
      dut.io.toD3.bits.entries(0).earlyRobComplete.expect(true.B)
      dut.io.toD3.bits.entries(0).history(0).valid.expect(false.B)
      dut.io.toD3.bits.entries(0).history(1).valid.expect(false.B)
      dut.io.debugPMap(0)(1).expect(1.U)
      dut.io.debugTCount(0).expect(0.U)
      dut.io.debugUCount(0).expect(0.U)
    }
  }

  test("unequal P T U and ROB capacities accept one legal prefix") {
    val p = base(6).copy(ooo = base(6).ooo.copy(
      robGroupsPerStid = 16,
      gprPhysRegs = 64,
      gprMapQDepthPerStid = 16,
      tPhysRegs = 16,
      uPhysRegs = 32,
      tuMapQDepthPerStid = 32))
    simulate(new RENU(p)) { dut =>
      clear(dut)
      dut.io.fromD2.bits.count.poke(6.U)
      dut.io.fromD2.bits.groupCount.poke(2.U)
      (0 until 6).foreach { lane =>
        pokeGprAdd(dut.io.fromD2.bits, lane, id = 130 + lane,
          dst = lane % 6, src0 = 1, src1 = 2, rid = lane)
      }
      dut.io.fromD2.bits.entries(4).uop.destinations(0).kind.poke(OperandKind.T)
      dut.io.fromD2.bits.entries(5).uop.destinations(0).kind.poke(OperandKind.U)
      dut.io.fromD2.valid.poke(true.B)
      dut.io.fromD2.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromD2.valid.poke(false.B)
      dut.io.toD3.valid.expect(true.B)
      dut.io.toD3.bits.count.expect(6.U)
      dut.io.toD3.bits.entries(4).history(0).kind.expect(OperandKind.T)
      dut.io.toD3.bits.entries(5).history(0).kind.expect(OperandKind.U)
    }
  }

  test("behavioral W2 W4 W6 and W8 each accept an atomic prefix") {
    Seq(2, 4, 6, 8).foreach { width =>
      simulate(new RENU(base(width))) { dut =>
        clear(dut)
        dut.io.fromD2.bits.count.poke(width.U)
        dut.io.fromD2.bits.groupCount.poke(1.U)
        (0 until width).foreach { lane =>
          pokeGprAdd(dut.io.fromD2.bits, lane, id = 140 + lane,
            dst = (lane % 6) + 1, src0 = 2, src1 = 3, rid = lane)
        }
        dut.io.fromD2.valid.poke(true.B)
        dut.io.fromD2.ready.expect(true.B)
        dut.clock.step()
        dut.io.fromD2.valid.poke(false.B)
        dut.io.toD3.valid.expect(true.B)
        dut.io.toD3.bits.count.expect(width.U)
      }
    }
  }

  test("elaborates W2 W4 W6 and W8 with central generation widths") {
    Seq(2, 4, 6, 8).foreach { width =>
      val p = base(width)
      val d3 = new linxcore.top.interface.D3RenameGroup(p)

      assert(d3.entries.head.history.head.pGeneration.getWidth ==
        p.ooo.gprTagGenerationWidth)
      assert(d3.entries.head.history.head.pMapQGeneration.getWidth ==
        p.ooo.gprTagGenerationWidth)
      assert(d3.entries.head.history.head.tGeneration.getWidth ==
        p.ooo.localSeqGenerationWidth)
      assert(d3.entries.head.tSeqBefore.generation.getWidth ==
        p.ooo.localSeqGenerationWidth)
      assert(d3.entries.head.history.head.uGeneration.getWidth ==
        p.ooo.localSeqGenerationWidth)
      assert(d3.entries.length == width)
    }
  }
}
