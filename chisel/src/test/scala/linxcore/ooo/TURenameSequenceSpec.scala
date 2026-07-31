package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface.{D2AdmissionGroup, D3RenameGroup, OperandKind,
  RecoveryPhase, UopClass}
import org.scalatest.funsuite.AnyFunSuite

class TURenameSequenceSpec extends AnyFunSuite with ChiselSim {
  private def params(width: Int): CoreParams =
    ParamProfiles.forWidth(width).copy(
      ooo = ParamProfiles.forWidth(width).ooo.copy(
        stidCount = 1,
        robGroupsPerStid = 8,
        gprPhysRegs = 32,
        gprMapQDepthPerStid = 8,
        tPhysRegs = 8,
        uPhysRegs = 8,
        tuMapQDepthPerStid = 16))

  private def clear(dut: TURename): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.publish.valid.poke(false.B)
    dut.io.publish.bits.poke(0.U.asTypeOf(dut.io.publish.bits))
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.releaseApply.poke(true.B)
    dut.io.recoveryApply.valid.poke(false.B)
    dut.io.recoveryApply.bits.poke(0.U.asTypeOf(dut.io.recoveryApply.bits))
  }

  private def pokeLocal(
      group: D2AdmissionGroup,
      lane: Int,
      id: Int,
      kind: OperandKind.Type,
      relSrc: Option[Int] = None,
      destValid: Boolean = true,
      rid: Int = -1): Unit = {
    val ridSlot = if (rid >= 0) rid else lane
    val row = group.entries(lane)
    row.uop.valid.poke(true.B)
    row.uop.instruction.parent.identity.peId.poke(1.U)
    row.uop.instruction.parent.identity.stid.poke(0.U)
    row.uop.instruction.parent.identity.instructionId.poke(id.U)
    row.uop.instruction.parent.identity.epoch.poke(3.U)
    row.uop.rob.stid.poke(0.U)
    row.uop.rob.ridSlot.poke(ridSlot.U)
    row.uop.rob.ridGeneration.poke(5.U)
    row.uop.rob.memberIndex.poke((lane % 4).U)
    row.uop.uopClass.poke(UopClass.Alu)
    row.uop.destinations(0).valid.poke(destValid.B)
    row.uop.destinations(0).kind.poke(kind)
    relSrc.foreach { rel =>
      row.uop.sources(0).valid.poke(true.B)
      row.uop.sources(0).kind.poke(kind)
      row.uop.sources(0).relativeIndex.poke(rel.U)
    }
  }

  private def publishPrepared(dut: TURename, prepared: D3RenameGroup): Unit = {
    dut.io.publish.bits.poke(prepared)
    dut.io.publish.valid.poke(true.B)
    dut.clock.step()
    dut.io.publish.valid.poke(false.B)
  }

  test("tu-mapq-sequence-snapshots-use-mapq-depth") {
    val p = params(2)
    val d3 = new D3RenameGroup(p)

    assert(d3.entries.head.tSeqBefore.tag.getWidth ==
      chisel3.util.log2Ceil(p.ooo.tuMapQDepthPerStid))
    assert(d3.entries.head.uSeqBefore.tag.getWidth ==
      chisel3.util.log2Ceil(p.ooo.tuMapQDepthPerStid))
  }

  test("tu-prefix-sequence-and-physical-capacity-are-independent") {
    val p = params(4)
    simulate(new TURename(p)) { dut =>
      clear(dut)
      dut.io.prepare.bits.count.poke(3.U)
      dut.io.prepare.bits.groupCount.poke(1.U)
      pokeLocal(dut.io.prepare.bits, 0, id = 10, OperandKind.T)
      pokeLocal(dut.io.prepare.bits, 1, id = 11, OperandKind.T)
      pokeLocal(dut.io.prepare.bits, 2, id = 12, OperandKind.T, relSrc = Some(0))
      dut.io.prepare.valid.poke(true.B)

      dut.io.prepareReady.expect(true.B)
      dut.io.prepared.entries(0).tSeqBefore.tag.expect(0.U)
      dut.io.prepared.entries(1).tSeqBefore.tag.expect(1.U)
      dut.io.prepared.entries(2).tSeqBefore.tag.expect(2.U)
      dut.io.prepared.entries(2).uop.sources(0).ttag.expect(1.U)

      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      dut.io.prepare.bits.count.poke(4.U)
      dut.io.prepare.bits.groupCount.poke(1.U)
      (0 until 4).foreach { lane =>
        pokeLocal(dut.io.prepare.bits, lane, id = 20 + lane, OperandKind.U)
      }
      dut.io.prepareReady.expect(true.B)
    }
  }

  test("tu-release-requires-all-exact-prefix-and-zero-dest-survivor") {
    val p = params(2)
    simulate(new TURename(p)) { dut =>
      clear(dut)
      dut.io.prepare.bits.count.poke(2.U)
      dut.io.prepare.bits.groupCount.poke(1.U)
      pokeLocal(dut.io.prepare.bits, 0, id = 40, OperandKind.T, destValid = false,
        rid = 1)
      pokeLocal(dut.io.prepare.bits, 1, id = 41, OperandKind.T, rid = 2)
      dut.io.prepare.valid.poke(true.B)
      dut.io.prepareReady.expect(true.B)
      val prepared = dut.io.prepared.peek()
      publishPrepared(dut, prepared)
      dut.io.debugTCount(0).expect(1.U)

      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.recoveryApply.bits.transactionId.poke(0x40.U)
      dut.io.recoveryApply.bits.trigger.stid.poke(0.U)
      dut.io.recoveryApply.bits.trigger.ridSlot.poke(2.U)
      dut.io.recoveryApply.bits.trigger.ridGeneration.poke(5.U)
      dut.io.recoveryApply.bits.survivingTailValid.poke(true.B)
      dut.io.recoveryApply.bits.survivingTail.stid.poke(0.U)
      dut.io.recoveryApply.bits.survivingTail.ridSlot.poke(1.U)
      dut.io.recoveryApply.bits.survivingTail.ridGeneration.poke(5.U)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.debugTCount(0).expect(0.U)

      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      dut.io.prepare.bits.count.poke(2.U)
      dut.io.prepare.bits.groupCount.poke(1.U)
      pokeLocal(dut.io.prepare.bits, 0, id = 50, OperandKind.T, rid = 3)
      pokeLocal(dut.io.prepare.bits, 1, id = 51, OperandKind.T, rid = 4)
      dut.io.prepare.valid.poke(true.B)
      dut.io.prepareReady.expect(true.B)
      val twoRows = dut.io.prepared.peek()
      publishPrepared(dut, twoRows)
      dut.io.debugTCount(0).expect(2.U)

      dut.io.release.bits.count.poke(2.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).rob.poke(twoRows.entries(0).uop.decoded.rob)
      dut.io.release.bits.lanes(0).history(0).poke(twoRows.entries(0).history(0))
      dut.io.release.bits.lanes(1).valid.poke(true.B)
      dut.io.release.bits.lanes(1).rob.poke(twoRows.entries(1).uop.decoded.rob)
      dut.io.release.bits.lanes(1).history(0).poke(twoRows.entries(1).history(0))
      dut.io.release.bits.lanes(1).history(0).tGeneration.poke(7.U)
      dut.io.release.valid.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.debugTCount(0).expect(2.U)
    }
  }

  test("physical tag generation is independent from MapQ sequence generation") {
    val p = params(4)
    simulate(new TURename(p)) { dut =>
      clear(dut)
      val published = scala.collection.mutable.ArrayBuffer.empty[D3RenameGroup]
      for (prefix <- 0 until 2) {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        dut.io.prepare.bits.count.poke(4.U)
        dut.io.prepare.bits.groupCount.poke(1.U)
        for (lane <- 0 until 4) {
          pokeLocal(dut.io.prepare.bits, lane, id = 80 + prefix * 4 + lane,
            OperandKind.T, rid = prefix * 4 + lane)
        }
        dut.io.prepare.valid.poke(true.B)
        dut.io.prepareReady.expect(true.B)
        val row = dut.io.prepared.peek()
        published += row
        publishPrepared(dut, row)
      }

      dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
      dut.io.release.bits.count.poke(4.U)
      for (lane <- 0 until 4) {
        dut.io.release.bits.lanes(lane).valid.poke(true.B)
        dut.io.release.bits.lanes(lane).rob.poke(
          published.head.entries(lane).uop.decoded.rob)
        dut.io.release.bits.lanes(lane).history(0).poke(
          published.head.entries(lane).history(0))
      }
      dut.io.release.valid.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)

      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      dut.io.prepare.bits.count.poke(1.U)
      dut.io.prepare.bits.groupCount.poke(1.U)
      pokeLocal(dut.io.prepare.bits, 0, id = 88, OperandKind.T, rid = 0)
      dut.io.prepare.valid.poke(true.B)
      dut.io.prepareReady.expect(true.B)
      dut.io.prepared.entries(0).history(0).ttag.expect(0.U)
      dut.io.prepared.entries(0).history(0).tGeneration.expect(1.U)
      dut.io.prepared.entries(0).history(0).tMapQIndex.expect(8.U)
      dut.io.prepared.entries(0).history(0).tMapQGeneration.expect(0.U)
    }
  }
}
