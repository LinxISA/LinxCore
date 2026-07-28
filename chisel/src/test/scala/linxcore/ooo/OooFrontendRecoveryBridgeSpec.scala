package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.InterfaceParams
import linxcore.frontend.{IfuInnerFlushReason, IfuPruneScope}
import org.scalatest.funsuite.AnyFunSuite

class OooFrontendRecoveryBridgeSpec extends AnyFunSuite with ChiselSim {
  private val ifuP = InterfaceParams()
  private val oooP = OooParams()

  private def clear(dut: OooFrontendRecoveryBridge): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.o3Request.ready.poke(false.B)
    dut.io.o3Applied.valid.poke(false.B)
    dut.io.o3Applied.bits.poke(0.U.asTypeOf(dut.io.o3Applied.bits))
    dut.io.o3Completed.valid.poke(false.B)
    dut.io.o3Completed.bits.poke(0.U.asTypeOf(dut.io.o3Completed.bits))
    dut.io.o3Aborted.valid.poke(false.B)
    dut.io.o3Aborted.bits.poke(0.U.asTypeOf(dut.io.o3Aborted.bits))
    dut.io.ctuPrepare.ready.poke(false.B)
    dut.io.ctuPrepared.valid.poke(false.B)
    dut.io.ctuPrepared.bits.poke(0.U.asTypeOf(dut.io.ctuPrepared.bits))
    dut.io.ctuRejected.valid.poke(false.B)
    dut.io.ctuRejected.bits.poke(0.U.asTypeOf(dut.io.ctuRejected.bits))
    dut.io.ifuRedirect.ready.poke(false.B)
    dut.io.canonicalFlush.valid.poke(false.B)
    dut.io.canonicalFlush.bits.poke(0.U.asTypeOf(dut.io.canonicalFlush.bits))
  }

  private def pokeO3Request(
      target: OooGlobalRecoveryRequest,
      stid: Int = 1,
      cause: OooRecoveryCause.Type = OooRecoveryCause.Branch,
      killTrigger: Boolean = true,
      epoch: Int = 7): Unit = {
    target.poke(0.U.asTypeOf(target))
    val key = target.rename.key
    key.member.group.valid.poke(true.B)
    key.member.group.peId.poke(3.U)
    key.member.group.stid.poke(stid.U)
    key.member.group.ridSlot.poke(5.U)
    key.member.group.ridGeneration.poke(2.U)
    key.member.bid.valid.poke(true.B)
    key.member.bid.value.poke(11.U)
    key.member.brobGeneration.poke(1.U)
    key.member.memberIndex.poke(2.U)
    key.member.residentGeneration.poke(4.U)
    key.cause.poke(cause)
    key.transactionId.poke(0x31.U)
    key.epoch.poke(epoch.U)
    target.rename.killTrigger.poke(killTrigger.B)
    target.triggerMemberCount.poke(2.U)
  }

  private def pokeCommand(
      dut: OooFrontendRecoveryBridge,
      stid: Int = 1,
      cause: OooRecoveryCause.Type = OooRecoveryCause.Branch,
      killTrigger: Boolean = true,
      epoch: Int = 7,
      restartPc: Int = 0x900): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    pokeO3Request(dut.io.in.bits.ooo, stid, cause, killTrigger, epoch)
    val redirect = dut.io.in.bits.redirect
    redirect.valid.poke(true.B)
    redirect.peId.poke(3.U)
    redirect.threadId.poke(stid.U)
    redirect.transactionId.poke(0x51.U)
    redirect.fetchSeq.poke(0x61.U)
    redirect.oldEpoch.poke(epoch.U)
    redirect.restartPc.poke(restartPc.U)
    redirect.checkpointId.poke(6.U)
    redirect.reason.poke(
      if (cause == OooRecoveryCause.Branch) IfuInnerFlushReason.BruRecovery
      else IfuInnerFlushReason.OooRecovery)
    redirect.scope.poke(
      if (killTrigger) IfuPruneScope.KillTriggerAndYounger
      else IfuPruneScope.PreserveTriggerKillYounger)
    redirect.historyKeyValid.poke(true.B)
    redirect.predictionTag.poke(0x71.U)
    redirect.fetchPacketUid.poke(0x81.U)
  }

  private def copyActiveRequestTo(
      dut: OooFrontendRecoveryBridge,
      target: OooGlobalRecoveryRequest): Unit = {
    pokeO3Request(target)
  }

  private def pokeCanonical(
      dut: OooFrontendRecoveryBridge,
      restartPc: Int = 0x900,
      newEpoch: Int = 8): Unit = {
    dut.io.canonicalFlush.bits.poke(0.U.asTypeOf(dut.io.canonicalFlush.bits))
    val flush = dut.io.canonicalFlush.bits
    flush.valid.poke(true.B)
    flush.peId.poke(3.U)
    flush.threadId.poke(1.U)
    flush.transactionId.poke(0x51.U)
    flush.fetchSeq.poke(0x61.U)
    flush.oldEpoch.poke(7.U)
    flush.restartPc.poke(restartPc.U)
    flush.checkpointId.poke(6.U)
    flush.newEpoch.poke(newEpoch.U)
    flush.reason.poke(IfuInnerFlushReason.BruRecovery)
    flush.scope.poke(IfuPruneScope.KillTriggerAndYounger)
    flush.historyKeyValid.poke(true.B)
    flush.predictionTag.poke(0x71.U)
    flush.fetchPacketUid.poke(0x81.U)
    dut.io.canonicalFlush.valid.poke(true.B)
  }

  private def captureAndSendO3(dut: OooFrontendRecoveryBridge): Unit = {
    pokeCommand(dut)
    dut.io.in.valid.poke(true.B)
    dut.io.in.ready.expect(true.B)
    dut.io.fence(1).expect(true.B)
    dut.io.stageCancel(1).expect(false.B)
    dut.clock.step()
    dut.io.in.valid.poke(false.B)

    dut.io.ctuPrepare.valid.expect(true.B)
    dut.io.ctuPrepare.bits.rename.key.member.group.stid.expect(1.U)
    dut.clock.step(2)
    dut.io.ctuPrepare.valid.expect(true.B)
    dut.io.ctuPrepare.ready.poke(true.B)
    dut.clock.step()
    dut.io.ctuPrepare.ready.poke(false.B)

    dut.io.ctuPrepared.bits.poke(0.U.asTypeOf(dut.io.ctuPrepared.bits))
    pokeO3Request(dut.io.ctuPrepared.bits.request)
    dut.io.ctuPrepared.valid.poke(true.B)
    dut.clock.step()
    dut.io.ctuPrepared.valid.poke(false.B)

    dut.io.o3Request.valid.expect(true.B)
    dut.io.o3Request.bits.rename.key.member.group.stid.expect(1.U)
    dut.clock.step(2)
    dut.io.o3Request.valid.expect(true.B)
    dut.io.o3Request.ready.poke(true.B)
    dut.clock.step()
    dut.io.o3Request.ready.poke(false.B)
  }

  test("fences before apply and completes only after exact O3 rebuild plus IFU echo") {
    simulate(new OooFrontendRecoveryBridge(ifuP, oooP)) { dut =>
      clear(dut)
      captureAndSendO3(dut)

      dut.io.fence(1).expect(true.B)
      dut.io.stageCancel(1).expect(false.B)
      dut.io.ifuRedirect.valid.expect(false.B)

      copyActiveRequestTo(dut, dut.io.o3Applied.bits)
      dut.io.o3Applied.valid.poke(true.B)
      dut.io.stageCancel(1).expect(true.B)
      dut.io.ctuApply.expect(true.B)
      dut.clock.step()
      dut.io.o3Applied.valid.poke(false.B)

      dut.io.ifuRedirect.valid.expect(true.B)
      dut.io.ifuRedirect.bits.restartPc.expect(0x900.U)
      dut.clock.step(2)
      dut.io.ifuRedirect.valid.expect(true.B)

      copyActiveRequestTo(dut, dut.io.o3Completed.bits)
      dut.io.o3Completed.valid.poke(true.B)
      dut.io.complete.valid.expect(false.B)
      dut.clock.step()
      dut.io.o3Completed.valid.poke(false.B)

      dut.io.ifuRedirect.ready.poke(true.B)
      dut.clock.step()
      dut.io.ifuRedirect.ready.poke(false.B)

      pokeCanonical(dut, restartPc = 0xa00, newEpoch = 8)
      dut.io.complete.valid.expect(false.B)
      dut.clock.step()
      dut.io.canonicalFlush.valid.poke(false.B)
      dut.io.fence(1).expect(true.B)

      pokeCanonical(dut, restartPc = 0x900, newEpoch = 9)
      dut.io.complete.valid.expect(true.B)
      dut.io.complete.bits.acceptedEpoch.expect(9.U)
      dut.clock.step()
      dut.io.canonicalFlush.valid.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.io.fence(1).expect(false.B)
    }
  }

  test("accepts canonical IFU acknowledgement before O3 rebuild completion") {
    simulate(new OooFrontendRecoveryBridge(ifuP, oooP)) { dut =>
      clear(dut)
      captureAndSendO3(dut)
      copyActiveRequestTo(dut, dut.io.o3Applied.bits)
      dut.io.o3Applied.valid.poke(true.B)
      dut.clock.step()
      dut.io.o3Applied.valid.poke(false.B)

      dut.io.ifuRedirect.ready.poke(true.B)
      dut.clock.step()
      dut.io.ifuRedirect.ready.poke(false.B)
      pokeCanonical(dut, newEpoch = 10)
      dut.io.complete.valid.expect(false.B)
      dut.clock.step()
      dut.io.canonicalFlush.valid.poke(false.B)

      copyActiveRequestTo(dut, dut.io.o3Completed.bits)
      dut.io.o3Completed.valid.poke(true.B)
      dut.io.complete.valid.expect(true.B)
      dut.io.complete.bits.acceptedEpoch.expect(10.U)
    }
  }

  test("O3 abort releases the fence without cancel or IFU mutation") {
    simulate(new OooFrontendRecoveryBridge(ifuP, oooP)) { dut =>
      clear(dut)
      captureAndSendO3(dut)
      copyActiveRequestTo(dut, dut.io.o3Aborted.bits)
      dut.io.o3Aborted.valid.poke(true.B)
      dut.io.rejected.valid.expect(true.B)
      dut.io.rejected.bits.reason.expect(OooFrontendRecoveryRejectReason.O3Aborted)
      dut.io.stageCancel(1).expect(false.B)
      dut.io.ctuAbort.expect(true.B)
      dut.io.ifuRedirect.valid.expect(false.B)
      dut.clock.step()
      dut.io.o3Aborted.valid.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.io.fence(1).expect(false.B)
    }
  }

  test("CTU prepare rejection releases the fence before O3 admission") {
    simulate(new OooFrontendRecoveryBridge(ifuP, oooP)) { dut =>
      clear(dut)
      pokeCommand(dut)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.ctuPrepare.valid.expect(true.B)
      dut.io.ctuPrepare.ready.poke(true.B)
      dut.io.ctuRejected.bits.poke(0.U.asTypeOf(dut.io.ctuRejected.bits))
      pokeO3Request(dut.io.ctuRejected.bits.request)
      dut.io.ctuRejected.valid.poke(true.B)
      dut.io.rejected.valid.expect(true.B)
      dut.io.rejected.bits.reason.expect(
        OooFrontendRecoveryRejectReason.CtuRejected)
      dut.io.o3Request.valid.expect(false.B)
      dut.io.stageCancel(1).expect(false.B)
      dut.io.ctuApply.expect(false.B)
      dut.io.ctuAbort.expect(false.B)
      dut.clock.step()
      dut.io.ctuRejected.valid.poke(false.B)
      dut.io.ctuPrepare.ready.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.io.fence(1).expect(false.B)
    }
  }

  test("rejects a cross-epoch command before fencing or requesting O3") {
    simulate(new OooFrontendRecoveryBridge(ifuP, oooP)) { dut =>
      clear(dut)
      pokeCommand(dut)
      dut.io.in.bits.redirect.oldEpoch.poke(6.U)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.fence(1).expect(false.B)
      dut.io.rejected.valid.expect(true.B)
      dut.io.rejected.bits.reason.expect(
        OooFrontendRecoveryRejectReason.MalformedCommand)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.o3Request.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }
}
