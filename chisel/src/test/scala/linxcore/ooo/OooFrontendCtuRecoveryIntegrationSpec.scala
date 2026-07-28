package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, Valid}
import linxcore.common.InterfaceParams
import linxcore.frontend.{IfuInnerFlushReason, IfuPruneScope}
import org.scalatest.funsuite.AnyFunSuite

class OooFrontendCtuRecoveryIntegrationIO(
    val ifuP: InterfaceParams,
    val oooP: OooParams)
    extends Bundle {
  val ctuIn = Flipped(Decoupled(new OooD1DecodedPacket(oooP)))
  val ctuParentClaim = Decoupled(new OooCtuParentClaim(oooP))
  val ctuExpansionPlan = Flipped(Decoupled(new OooCtuExpansionPlan(oooP)))
  val ctuChild = Flipped(Decoupled(new OooCtuCanonicalChild(oooP)))

  val command = Flipped(Decoupled(new OooFrontendRecoveryCommand(ifuP, oooP)))
  val o3Request = Decoupled(new OooGlobalRecoveryRequest(oooP))
  val o3Applied = Flipped(Valid(new OooGlobalRecoveryRequest(oooP)))
  val o3Completed = Flipped(Valid(new OooGlobalRecoveryRequest(oooP)))
  val o3Aborted = Flipped(Valid(new OooGlobalRecoveryRequest(oooP)))

  val ctuActive = Output(Vec(oooP.stidCount, Bool()))
  val ctuOccupied = Output(Vec(oooP.stidCount, Bool()))
  val fence = Output(Vec(oooP.stidCount, Bool()))
  val stageCancel = Output(Vec(oooP.stidCount, Bool()))
}

class OooFrontendCtuRecoveryIntegration(
    val ifuP: InterfaceParams = InterfaceParams(),
    val oooP: OooParams = OooParams())
    extends Module {
  val io = IO(new OooFrontendCtuRecoveryIntegrationIO(ifuP, oooP))
  val recovery = Module(new OooFrontendRecoveryBridge(ifuP, oooP))
  val ctu = Module(new OooCtuIngressBridge(oooP))

  ctu.io.in <> io.ctuIn
  io.ctuParentClaim <> ctu.io.parentClaim
  ctu.io.expansionPlan <> io.ctuExpansionPlan
  ctu.io.child <> io.ctuChild
  ctu.io.fence := recovery.io.fence
  ctu.io.cancel.foreach(_ := false.B)

  recovery.io.in <> io.command
  io.o3Request <> recovery.io.o3Request
  recovery.io.o3Applied := io.o3Applied
  recovery.io.o3Completed := io.o3Completed
  recovery.io.o3Aborted := io.o3Aborted
  ctu.io.recoveryPrepare <> recovery.io.ctuPrepare
  recovery.io.ctuPrepared := ctu.io.recoveryPrepared
  recovery.io.ctuRejected := ctu.io.recoveryRejected
  ctu.io.recoveryApply := recovery.io.ctuApply
  ctu.io.recoveryAbort := recovery.io.ctuAbort

  recovery.io.ifuRedirect.ready := false.B
  recovery.io.canonicalFlush.valid := false.B
  recovery.io.canonicalFlush.bits := 0.U.asTypeOf(recovery.io.canonicalFlush.bits)
  ctu.io.out.ready := false.B

  io.ctuActive := ctu.io.active
  io.ctuOccupied := ctu.io.occupied
  io.fence := recovery.io.fence
  io.stageCancel := recovery.io.stageCancel
}

class OooFrontendCtuRecoveryIntegrationSpec extends AnyFunSuite with ChiselSim {
  private val ifuP = InterfaceParams()
  private val oooP = OooParams()

  private def clear(dut: OooFrontendCtuRecoveryIntegration): Unit = {
    dut.io.ctuIn.valid.poke(false.B)
    dut.io.ctuIn.bits.poke(0.U.asTypeOf(dut.io.ctuIn.bits))
    dut.io.ctuParentClaim.ready.poke(false.B)
    dut.io.ctuExpansionPlan.valid.poke(false.B)
    dut.io.ctuExpansionPlan.bits.poke(
      0.U.asTypeOf(dut.io.ctuExpansionPlan.bits))
    dut.io.ctuChild.valid.poke(false.B)
    dut.io.ctuChild.bits.poke(0.U.asTypeOf(dut.io.ctuChild.bits))
    dut.io.command.valid.poke(false.B)
    dut.io.command.bits.poke(0.U.asTypeOf(dut.io.command.bits))
    dut.io.o3Request.ready.poke(false.B)
    dut.io.o3Applied.valid.poke(false.B)
    dut.io.o3Applied.bits.poke(0.U.asTypeOf(dut.io.o3Applied.bits))
    dut.io.o3Completed.valid.poke(false.B)
    dut.io.o3Completed.bits.poke(0.U.asTypeOf(dut.io.o3Completed.bits))
    dut.io.o3Aborted.valid.poke(false.B)
    dut.io.o3Aborted.bits.poke(0.U.asTypeOf(dut.io.o3Aborted.bits))
  }

  private def pokeParent(
      target: ArchitecturalParentRef,
      stid: Int,
      instructionId: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.key.valid.poke(true.B)
    target.key.peId.poke(2.U)
    target.key.stid.poke(stid.U)
    target.key.instructionId.poke(instructionId.U)
    target.key.epoch.poke(7.U)
    target.pc.poke(0x2000.U)
    target.lengthBytes.poke(4.U)
    target.traceOwner.poke(true.B)
    target.prediction.epoch.poke(7.U)
  }

  private def pokeLease(
      target: OooCtuLeaseKey,
      stid: Int,
      instructionId: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.valid.poke(true.B)
    target.peId.poke(2.U)
    target.stid.poke(stid.U)
    target.parent.valid.poke(true.B)
    target.parent.peId.poke(2.U)
    target.parent.stid.poke(stid.U)
    target.parent.instructionId.poke(instructionId.U)
    target.parent.epoch.poke(7.U)
  }

  private def seedActiveCtu(
      dut: OooFrontendCtuRecoveryIntegration,
      stid: Int,
      instructionId: Int): Unit = {
    dut.io.ctuIn.bits.poke(0.U.asTypeOf(dut.io.ctuIn.bits))
    dut.io.ctuIn.bits.peId.poke(2.U)
    dut.io.ctuIn.bits.stid.poke(stid.U)
    dut.io.ctuIn.bits.epoch.poke(7.U)
    dut.io.ctuIn.bits.ctuParentMask.poke(1.U)
    pokeParent(dut.io.ctuIn.bits.ctuParents(0).parent, stid, instructionId)
    dut.io.ctuIn.valid.poke(true.B)
    dut.io.ctuIn.ready.expect(true.B)
    dut.clock.step()
    dut.io.ctuIn.valid.poke(false.B)

    dut.io.ctuParentClaim.valid.expect(true.B)
    dut.io.ctuParentClaim.ready.poke(true.B)
    dut.clock.step()
    dut.io.ctuParentClaim.ready.poke(false.B)
    pokeLease(dut.io.ctuExpansionPlan.bits.lease, stid, instructionId)
    dut.io.ctuExpansionPlan.bits.childCount.poke(4.U)
    dut.io.ctuExpansionPlan.valid.poke(true.B)
    dut.io.ctuExpansionPlan.ready.expect(true.B)
    dut.clock.step()
    dut.io.ctuExpansionPlan.valid.poke(false.B)
    dut.io.ctuActive(stid).expect(true.B)
  }

  private def pokeRecovery(
      target: OooGlobalRecoveryRequest,
      stid: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.rename.key.member.group.valid.poke(true.B)
    target.rename.key.member.group.peId.poke(2.U)
    target.rename.key.member.group.stid.poke(stid.U)
    target.rename.key.member.bid.valid.poke(true.B)
    target.rename.key.member.bid.value.poke(3.U)
    target.rename.key.cause.poke(OooRecoveryCause.CtuCancel)
    target.rename.key.epoch.poke(7.U)
    target.rename.killTrigger.poke(true.B)
    target.triggerMemberCount.poke(1.U)
  }

  private def pokeCommand(
      dut: OooFrontendCtuRecoveryIntegration,
      stid: Int): Unit = {
    dut.io.command.bits.poke(0.U.asTypeOf(dut.io.command.bits))
    pokeRecovery(dut.io.command.bits.ooo, stid)
    val redirect = dut.io.command.bits.redirect
    redirect.valid.poke(true.B)
    redirect.peId.poke(2.U)
    redirect.threadId.poke(stid.U)
    redirect.oldEpoch.poke(7.U)
    redirect.restartPc.poke(0x3000.U)
    redirect.reason.poke(IfuInnerFlushReason.OooRecovery)
    redirect.scope.poke(IfuPruneScope.KillTriggerAndYounger)
  }

  test("CTU prepare gates O3 admission and CTU cancellation shares common apply") {
    simulate(new OooFrontendCtuRecoveryIntegration(ifuP, oooP)) { dut =>
      clear(dut)
      seedActiveCtu(dut, stid = 1, instructionId = 60)

      pokeCommand(dut, stid = 1)
      dut.io.command.valid.poke(true.B)
      dut.io.command.ready.expect(true.B)
      dut.io.fence(1).expect(true.B)
      dut.clock.step()
      dut.io.command.valid.poke(false.B)

      dut.io.o3Request.valid.expect(false.B)
      var cycles = 0
      while (!dut.io.o3Request.valid.peek().litToBoolean && cycles < 8) {
        dut.io.ctuActive(1).expect(true.B)
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 8, "CTU prepare did not release exact O3 admission")
      dut.io.ctuActive(1).expect(true.B)

      dut.io.o3Request.ready.poke(true.B)
      dut.clock.step()
      dut.io.o3Request.ready.poke(false.B)
      pokeRecovery(dut.io.o3Applied.bits, stid = 1)
      dut.io.o3Applied.valid.poke(true.B)
      dut.io.stageCancel(1).expect(true.B)
      dut.io.ctuActive(1).expect(true.B)
      dut.clock.step()
      dut.io.o3Applied.valid.poke(false.B)
      dut.io.ctuActive(1).expect(false.B)
      dut.io.ctuOccupied(1).expect(false.B)
    }
  }
}
