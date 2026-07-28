package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, Valid}
import linxcore.common.InterfaceParams
import linxcore.frontend.{IfuInnerFlush, IfuInnerFlushReason, IfuPruneScope}
import linxcore.top.LinxCoreProductionComposition
import org.scalatest.funsuite.AnyFunSuite

class OooFrontendIfuRecoveryIntegrationIO(
    val ifuP: InterfaceParams,
    val oooP: OooParams)
    extends Bundle {
  val command = Flipped(Decoupled(new OooFrontendRecoveryCommand(ifuP, oooP)))
  val o3Request = Decoupled(new OooGlobalRecoveryRequest(oooP))
  val o3Applied = Flipped(Valid(new OooGlobalRecoveryRequest(oooP)))
  val o3Completed = Flipped(Valid(new OooGlobalRecoveryRequest(oooP)))
  val o3Aborted = Flipped(Valid(new OooGlobalRecoveryRequest(oooP)))
  val fence = Output(Vec(oooP.stidCount, Bool()))
  val stageCancel = Output(Vec(oooP.stidCount, Bool()))
  val canonicalFlush = Valid(new IfuInnerFlush(ifuP))
  val complete = Valid(new OooFrontendRecoveryCompletion(ifuP, oooP))
  val ifuEpochs = Output(Vec(oooP.stidCount, UInt(ifuP.blockEpochWidth.W)))
}

/** Test-only integration of the production R4 bridge with the real IFU
  * redirect arbiter and canonical flush broadcast.
  */
class OooFrontendIfuRecoveryIntegration(
    val ifuP: InterfaceParams = InterfaceParams(),
    val oooP: OooParams = OooParams())
    extends Module {
  val io = IO(new OooFrontendIfuRecoveryIntegrationIO(ifuP, oooP))
  val bridge = Module(new OooFrontendRecoveryBridge(ifuP, oooP))
  val ifu = Module(new LinxCoreProductionComposition(
    ifuP,
    threadCount = oooP.stidCount,
    lineBytes = 64,
    pageBytes = 4096,
    itlbEntries = 4,
    l1iSets = 4,
    missEntries = 4,
    joinEntries = 4,
    maxGroupsPerTransaction = 8,
    instructionBufferDepth = 16,
    lineBridgeEntries = 4,
    feedbackEntries = 2))

  bridge.io.in <> io.command
  io.o3Request <> bridge.io.o3Request
  bridge.io.o3Applied := io.o3Applied
  bridge.io.o3Completed := io.o3Completed
  bridge.io.o3Aborted := io.o3Aborted
  ifu.io.recoveryRedirect <> bridge.io.ifuRedirect
  bridge.io.canonicalFlush := ifu.io.canonicalFlush

  ifu.io.start.valid := false.B
  ifu.io.start.bits := 0.U.asTypeOf(ifu.io.start.bits)
  ifu.io.ptwRequest.ready := true.B
  ifu.io.ptwRefill.valid := false.B
  ifu.io.ptwRefill.bits := 0.U.asTypeOf(ifu.io.ptwRefill.bits)
  ifu.io.memoryRequest.ready := false.B
  ifu.io.memoryResponse.valid := false.B
  ifu.io.memoryResponse.bits := 0.U.asTypeOf(ifu.io.memoryResponse.bits)
  ifu.io.fetchFault.ready := true.B
  ifu.io.invalidateItlb := false.B
  ifu.io.invalidateL1I := false.B
  ifu.io.d1ThreadId := 0.U
  ifu.io.decoded.ready := false.B
  ifu.io.backendValidation.valid := false.B
  ifu.io.backendValidation.bits := 0.U.asTypeOf(ifu.io.backendValidation.bits)

  io.fence := bridge.io.fence
  io.stageCancel := bridge.io.stageCancel
  io.canonicalFlush := ifu.io.canonicalFlush
  io.complete := bridge.io.complete
  io.ifuEpochs := ifu.io.epochs
}

class OooFrontendIfuRecoveryIntegrationSpec extends AnyFunSuite with ChiselSim {
  private val ifuP = InterfaceParams()
  private val oooP = OooParams()

  private def pokeRequest(target: OooGlobalRecoveryRequest): Unit = {
    target.poke(0.U.asTypeOf(target))
    val key = target.rename.key
    key.member.group.valid.poke(true.B)
    key.member.group.peId.poke(2.U)
    key.member.group.stid.poke(1.U)
    key.member.group.ridSlot.poke(3.U)
    key.member.group.ridGeneration.poke(1.U)
    key.member.bid.valid.poke(true.B)
    key.member.bid.value.poke(9.U)
    key.member.brobGeneration.poke(2.U)
    key.member.memberIndex.poke(0.U)
    key.member.residentGeneration.poke(5.U)
    key.cause.poke(OooRecoveryCause.Branch)
    key.transactionId.poke(0x23.U)
    key.epoch.poke(0.U)
    target.rename.killTrigger.poke(true.B)
    target.triggerMemberCount.poke(1.U)
  }

  private def pokeCommand(dut: OooFrontendIfuRecoveryIntegration): Unit = {
    dut.io.command.bits.poke(0.U.asTypeOf(dut.io.command.bits))
    pokeRequest(dut.io.command.bits.ooo)
    val redirect = dut.io.command.bits.redirect
    redirect.valid.poke(true.B)
    redirect.peId.poke(2.U)
    redirect.threadId.poke(1.U)
    redirect.transactionId.poke(0x44.U)
    redirect.fetchSeq.poke(0x55.U)
    redirect.oldEpoch.poke(0.U)
    redirect.restartPc.poke(0x12340.U)
    redirect.checkpointId.poke(7.U)
    redirect.reason.poke(IfuInnerFlushReason.BruRecovery)
    redirect.scope.poke(IfuPruneScope.KillTriggerAndYounger)
    redirect.historyKeyValid.poke(true.B)
    redirect.predictionTag.poke(0x66.U)
    redirect.fetchPacketUid.poke(0x77.U)
  }

  test("real IFU canonical flush closes R4 only after exact O3 completion") {
    simulate(new OooFrontendIfuRecoveryIntegration(ifuP, oooP)) { dut =>
      dut.io.command.valid.poke(false.B)
      dut.io.o3Request.ready.poke(false.B)
      dut.io.o3Applied.valid.poke(false.B)
      dut.io.o3Applied.bits.poke(0.U.asTypeOf(dut.io.o3Applied.bits))
      dut.io.o3Completed.valid.poke(false.B)
      dut.io.o3Completed.bits.poke(0.U.asTypeOf(dut.io.o3Completed.bits))
      dut.io.o3Aborted.valid.poke(false.B)
      dut.io.o3Aborted.bits.poke(0.U.asTypeOf(dut.io.o3Aborted.bits))

      pokeCommand(dut)
      dut.io.command.valid.poke(true.B)
      dut.io.command.ready.expect(true.B)
      dut.io.fence(1).expect(true.B)
      dut.clock.step()
      dut.io.command.valid.poke(false.B)

      dut.io.o3Request.valid.expect(true.B)
      dut.io.o3Request.ready.poke(true.B)
      dut.clock.step()
      dut.io.o3Request.ready.poke(false.B)

      pokeRequest(dut.io.o3Applied.bits)
      dut.io.o3Applied.valid.poke(true.B)
      dut.io.stageCancel(1).expect(true.B)
      dut.clock.step()
      dut.io.o3Applied.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.canonicalFlush.valid.peek().litToBoolean && cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 12, "real IFU did not canonicalize the OOO redirect")
      dut.io.canonicalFlush.bits.threadId.expect(1.U)
      dut.io.canonicalFlush.bits.restartPc.expect(0x12340.U)
      dut.io.canonicalFlush.bits.reason.expect(IfuInnerFlushReason.BruRecovery)
      dut.io.canonicalFlush.bits.oldEpoch.expect(0.U)
      dut.io.canonicalFlush.bits.newEpoch.expect(1.U)
      dut.io.complete.valid.expect(false.B)
      dut.clock.step()
      dut.io.ifuEpochs(1).expect(1.U)
      dut.io.fence(1).expect(true.B)

      pokeRequest(dut.io.o3Completed.bits)
      dut.io.o3Completed.valid.poke(true.B)
      dut.io.complete.valid.expect(true.B)
      dut.io.complete.bits.acceptedEpoch.expect(1.U)
      dut.clock.step()
      dut.io.o3Completed.valid.poke(false.B)
      dut.io.fence(1).expect(false.B)
      dut.io.fence(0).expect(false.B)
      dut.io.fence(2).expect(false.B)
      dut.io.fence(3).expect(false.B)
    }
  }
}
