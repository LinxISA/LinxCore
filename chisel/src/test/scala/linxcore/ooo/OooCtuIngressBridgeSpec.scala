package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooCtuIngressBridgeSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams()

  private def clear(dut: OooCtuIngressBridge): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.out.ready.poke(false.B)
    dut.io.parentClaim.ready.poke(false.B)
    dut.io.expansionPlan.valid.poke(false.B)
    dut.io.expansionPlan.bits.poke(0.U.asTypeOf(dut.io.expansionPlan.bits))
    dut.io.child.valid.poke(false.B)
    dut.io.child.bits.poke(0.U.asTypeOf(dut.io.child.bits))
    dut.io.fence.foreach(_.poke(false.B))
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryApply.poke(false.B)
    dut.io.recoveryAbort.poke(false.B)
  }

  private def pokeParent(
      target: ArchitecturalParentRef,
      stid: Int,
      instructionId: Int,
      epoch: Int = 3): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.key.valid.poke(true.B)
    target.key.peId.poke(1.U)
    target.key.stid.poke(stid.U)
    target.key.instructionId.poke(instructionId.U)
    target.key.epoch.poke(epoch.U)
    target.pc.poke((0x1000 + instructionId * 4).U)
    target.lengthBytes.poke(4.U)
    target.traceOwner.poke(true.B)
    target.prediction.epoch.poke(epoch.U)
  }

  private def pokeNormalUop(
      target: OooDecodedUop,
      stid: Int,
      instructionId: Int,
      opcode: Int = 7): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.valid.poke(true.B)
    target.identity.key.primaryParent.valid.poke(true.B)
    target.identity.key.primaryParent.peId.poke(1.U)
    target.identity.key.primaryParent.stid.poke(stid.U)
    target.identity.key.primaryParent.instructionId.poke(instructionId.U)
    target.identity.key.primaryParent.epoch.poke(3.U)
    target.identity.key.uopCount.poke(1.U)
    target.identity.parentCount.poke(1.U)
    pokeParent(target.identity.parents(0), stid, instructionId)
    target.opcode.poke(opcode.U)
    target.recipe.valid.poke(true.B)
    target.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    target.recipe.recipeKind.poke(OooOpcodeRecipeKind.Single.U)
    target.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
    target.recipe.dispatchWrites.poke(1.U)
    target.recipe.dispatchDemand(0).poke(1.U)
    target.plannedChildCount.poke(1.U)
  }

  private def pokeMixedPacket(
      dut: OooCtuIngressBridge,
      stid: Int,
      firstId: Int,
      ctuId: Int,
      lastId: Int): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.bits.peId.poke(1.U)
    dut.io.in.bits.stid.poke(stid.U)
    dut.io.in.bits.epoch.poke(3.U)
    dut.io.in.bits.endOfStream.poke(true.B)
    dut.io.in.bits.uopMask.poke(3.U)
    pokeNormalUop(dut.io.in.bits.uops(0), stid, firstId)
    pokeNormalUop(dut.io.in.bits.uops(1), stid, lastId, opcode = 8)
    dut.io.in.bits.ctuParentMask.poke(2.U)
    pokeParent(dut.io.in.bits.ctuParents(1).parent, stid, ctuId)
    dut.io.in.bits.ctuParents(1).parent.rawInstruction.poke(0x1234.U)
  }

  private def pokeCtuOnlyPacket(
      dut: OooCtuIngressBridge,
      stid: Int,
      parentId: Int): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.bits.peId.poke(1.U)
    dut.io.in.bits.stid.poke(stid.U)
    dut.io.in.bits.epoch.poke(3.U)
    dut.io.in.bits.endOfStream.poke(true.B)
    dut.io.in.bits.ctuParentMask.poke(1.U)
    pokeParent(dut.io.in.bits.ctuParents(0).parent, stid, parentId)
    dut.io.in.bits.ctuParents(0).parent.rawInstruction.poke(0x5678.U)
  }

  private def pokeLease(
      target: OooCtuLeaseKey,
      stid: Int,
      parentId: Int,
      groupId: Int = 0,
      generation: Int = 0): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.valid.poke(true.B)
    target.peId.poke(1.U)
    target.stid.poke(stid.U)
    target.parent.valid.poke(true.B)
    target.parent.peId.poke(1.U)
    target.parent.stid.poke(stid.U)
    target.parent.instructionId.poke(parentId.U)
    target.parent.epoch.poke(3.U)
    target.templateGroupId.poke(groupId.U)
    target.generation.poke(generation.U)
  }

  private def pokePlan(
      dut: OooCtuIngressBridge,
      stid: Int,
      parentId: Int,
      childCount: Int): Unit = {
    dut.io.expansionPlan.bits.poke(0.U.asTypeOf(dut.io.expansionPlan.bits))
    pokeLease(dut.io.expansionPlan.bits.lease, stid, parentId)
    dut.io.expansionPlan.bits.childCount.poke(childCount.U)
  }

  private def pokeChild(
      dut: OooCtuIngressBridge,
      stid: Int,
      parentId: Int,
      ordinal: Int,
      childCount: Int): Unit = {
    dut.io.child.bits.poke(0.U.asTypeOf(dut.io.child.bits))
    pokeLease(dut.io.child.bits.lease, stid, parentId)
    dut.io.child.bits.ordinal.poke(ordinal.U)
    dut.io.child.bits.childCount.poke(childCount.U)
    dut.io.child.bits.finalChild.poke((ordinal == childCount - 1).B)
    pokeNormalUop(dut.io.child.bits.uop, stid, parentId, opcode = 20 + ordinal)
  }

  private def claimAndPlan(
      dut: OooCtuIngressBridge,
      stid: Int,
      parentId: Int,
      childCount: Int): Unit = {
    dut.io.parentClaim.valid.expect(true.B)
    dut.io.parentClaim.bits.lease.stid.expect(stid.U)
    dut.io.parentClaim.bits.parent.parent.key.instructionId.expect(parentId.U)
    dut.io.parentClaim.ready.poke(true.B)
    dut.clock.step()
    dut.io.parentClaim.ready.poke(false.B)
    pokePlan(dut, stid, parentId, childCount)
    dut.io.expansionPlan.valid.poke(true.B)
    dut.io.expansionPlan.ready.expect(true.B)
    dut.clock.step()
    dut.io.expansionPlan.valid.poke(false.B)
  }

  private def pokeRecovery(
      target: OooGlobalRecoveryRequest,
      stid: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.rename.key.member.group.valid.poke(true.B)
    target.rename.key.member.group.peId.poke(1.U)
    target.rename.key.member.group.stid.poke(stid.U)
    target.rename.key.member.bid.valid.poke(true.B)
    target.rename.key.member.bid.value.poke(4.U)
    target.rename.key.epoch.poke(3.U)
    target.triggerMemberCount.poke(1.U)
  }

  test("preserves mixed parent order and reinserts a multi-RID child stream") {
    simulate(new OooCtuIngressBridge(p)) { dut =>
      clear(dut)
      pokeMixedPacket(dut, stid = 1, firstId = 10, ctuId = 11, lastId = 12)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.uopMask.expect(1.U)
      dut.io.out.bits.uops(0).identity.key.primaryParent.instructionId.expect(10.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.ready.poke(false.B)

      dut.io.parentClaim.ready.poke(false.B)
      dut.io.parentClaim.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.parentClaim.valid.expect(true.B)
      claimAndPlan(dut, stid = 1, parentId = 11, childCount = 6)

      for (ordinal <- 0 until 6) {
        pokeChild(dut, stid = 1, parentId = 11, ordinal, childCount = 6)
        dut.io.child.valid.poke(true.B)
        if (ordinal == 0) {
          dut.io.out.ready.poke(false.B)
          dut.io.child.ready.expect(false.B)
          dut.io.out.valid.expect(true.B)
          dut.clock.step(2)
        }
        dut.io.out.ready.poke(true.B)
        dut.io.child.ready.expect(true.B)
        dut.io.out.bits.uops(0).identity.templateValid.expect(true.B)
        dut.io.out.bits.uops(0).identity.key.uopOrdinal.expect(ordinal.U)
        dut.io.out.bits.uops(0).identity.key.uopCount.expect(6.U)
        dut.io.out.bits.uops(0).identity.parents(0).traceOwner.expect(
          (ordinal == 5).B)
        dut.io.out.bits.demand.instructionRows.expect(
          (if (ordinal == 5) 1 else 0).U)
        dut.clock.step()
        dut.io.child.valid.poke(false.B)
      }
      dut.io.out.ready.poke(false.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.uops(0).identity.key.primaryParent.instructionId.expect(12.U)
      dut.io.out.bits.endOfStream.expect(true.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.occupied(1).expect(false.B)
      dut.io.active(1).expect(false.B)
    }
  }

  test("keeps unrelated STIDs moving while one CTU waits for children") {
    simulate(new OooCtuIngressBridge(p)) { dut =>
      clear(dut)
      pokeCtuOnlyPacket(dut, stid = 0, parentId = 20)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      claimAndPlan(dut, stid = 0, parentId = 20, childCount = 2)
      dut.io.active(0).expect(true.B)

      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.peId.poke(1.U)
      dut.io.in.bits.stid.poke(2.U)
      dut.io.in.bits.epoch.poke(3.U)
      dut.io.in.bits.uopMask.poke(1.U)
      pokeNormalUop(dut.io.in.bits.uops(0), stid = 2, instructionId = 30)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.stid.expect(2.U)
      dut.io.out.bits.uops(0).identity.key.primaryParent.instructionId.expect(30.U)
    }
  }

  test("rejects stale plans and out-of-order children without advancing the lease") {
    simulate(new OooCtuIngressBridge(p)) { dut =>
      clear(dut)
      pokeCtuOnlyPacket(dut, stid = 1, parentId = 40)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.parentClaim.ready.poke(true.B)
      dut.clock.step()
      dut.io.parentClaim.ready.poke(false.B)

      pokePlan(dut, stid = 1, parentId = 40, childCount = 2)
      dut.io.expansionPlan.bits.lease.generation.poke(7.U)
      dut.io.expansionPlan.valid.poke(true.B)
      dut.io.planRejected.valid.expect(true.B)
      dut.io.planRejected.bits.reason.expect(OooCtuPlanRejectReason.StaleLease)
      dut.clock.step()
      pokePlan(dut, stid = 1, parentId = 40, childCount = 2)
      dut.clock.step()
      dut.io.expansionPlan.valid.poke(false.B)

      pokeChild(dut, stid = 1, parentId = 40, ordinal = 1, childCount = 2)
      dut.io.child.valid.poke(true.B)
      dut.io.childRejected.valid.expect(true.B)
      dut.io.childRejected.bits.reason.expect(OooCtuChildRejectReason.WrongOrdinal)
      dut.io.child.ready.expect(true.B)
      dut.clock.step()
      pokeChild(dut, stid = 1, parentId = 40, ordinal = 0, childCount = 2)
      dut.io.out.ready.poke(true.B)
      dut.io.childRejected.valid.expect(false.B)
      dut.io.child.ready.expect(true.B)
    }
  }

  test("recovery prepare freezes an active lease and abort or apply is exact") {
    simulate(new OooCtuIngressBridge(p)) { dut =>
      clear(dut)
      pokeCtuOnlyPacket(dut, stid = 1, parentId = 50)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      claimAndPlan(dut, stid = 1, parentId = 50, childCount = 2)

      pokeRecovery(dut.io.recoveryPrepare.bits, stid = 1)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.io.recoveryPrepared.bits.expansionActive.expect(true.B)

      dut.io.cancel(1).poke(true.B)
      dut.clock.step()
      dut.io.cancel(1).poke(false.B)
      dut.io.active(1).expect(true.B)

      pokeChild(dut, stid = 1, parentId = 50, ordinal = 0, childCount = 2)
      dut.io.child.valid.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.child.ready.expect(false.B)
      dut.io.recoveryAbort.poke(true.B)
      dut.clock.step()
      dut.io.recoveryAbort.poke(false.B)
      dut.io.child.ready.expect(true.B)
      dut.clock.step()
      dut.io.child.valid.poke(false.B)

      pokeRecovery(dut.io.recoveryPrepare.bits, stid = 1)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.poke(true.B)
      dut.clock.step()
      dut.io.recoveryApply.poke(false.B)
      dut.io.active(1).expect(false.B)
      dut.io.occupied(1).expect(false.B)

      pokeChild(dut, stid = 1, parentId = 50, ordinal = 1, childCount = 2)
      dut.io.child.valid.poke(true.B)
      dut.io.childRejected.valid.expect(true.B)
      dut.io.childRejected.bits.reason.expect(OooCtuChildRejectReason.StaleLease)
    }
  }
}
