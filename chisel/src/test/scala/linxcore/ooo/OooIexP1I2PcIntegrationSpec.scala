package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, Valid}
import linxcore.common.OperandClass
import org.scalatest.funsuite.AnyFunSuite

private class OooIexP1I2PcIntegrationHarnessIO(val p: OooParams)
    extends Bundle {
  val pcPrepare = Flipped(Valid(new OooD3GroupedReservation(p)))
  val pcPrepared = Output(new OooPcPreparedBindings(p))
  val pcPrepareReady = Output(Bool())
  val pcPublishFire = Input(Bool())

  val p1 = Flipped(Decoupled(new OooIexP1Request(p)))
  val readAttempt = Output(Valid(new OooIexI1ReadAttempt(p)))
  val readDecisionValid = Input(Bool())
  val readGrant = Input(Bool())
  val sourceDataValid = Input(UInt(p.maxSourceOperands.W))
  val sourceData = Input(Vec(p.maxSourceOperands, UInt(p.pcWidth.W)))
  val i2 = Decoupled(new OooIexI2Transaction(p))
}

private class OooIexP1I2PcIntegrationHarness(val p: OooParams)
    extends Module {
  val io = IO(new OooIexP1I2PcIntegrationHarnessIO(p))

  val pc = Module(new OooPcBuffer(p))
  val lane = Module(new OooIexP1I2Lane(p))

  pc.io.prepare := io.pcPrepare
  io.pcPrepared := pc.io.prepared
  io.pcPrepareReady := pc.io.prepareReady
  pc.io.publishFire := io.pcPublishFire
  pc.io.commit.valid := false.B
  pc.io.commit.bits := 0.U.asTypeOf(pc.io.commit.bits)
  pc.io.recoveryPrepare.valid := false.B
  pc.io.recoveryPrepare.bits :=
    0.U.asTypeOf(pc.io.recoveryPrepare.bits)
  pc.io.recoveryFire := false.B
  pc.io.readTokens.foreach(_ := 0.U.asTypeOf(pc.io.readTokens.head))
  when(lane.io.readAttempt.valid && lane.io.readAttempt.bits.pcRequired) {
    pc.io.readTokens(0) := lane.io.readAttempt.bits.pcToken
  }

  lane.io.p1 <> io.p1
  io.readAttempt := lane.io.readAttempt
  lane.io.readDecisionValid := io.readDecisionValid
  lane.io.readGrant := io.readGrant
  lane.io.sourceDataValid := io.sourceDataValid
  lane.io.sourceData := io.sourceData
  lane.io.pcDataValid := pc.io.readValid(0)
  lane.io.pcData := pc.io.readPc(0)
  lane.io.recoveryApply.valid := false.B
  lane.io.recoveryApply.bits :=
    0.U.asTypeOf(lane.io.recoveryApply.bits)
  lane.io.bypass.foreach(_ := 0.U.asTypeOf(lane.io.bypass.head))
  lane.io.loadCancel.foreach(_ := 0.U.asTypeOf(lane.io.loadCancel.head))
  lane.io.stageCancel.foreach { cancel =>
    cancel.valid := false.B
    cancel.bits := 0.U.asTypeOf(cancel.bits)
  }
  lane.io.repick.ready := true.B
  io.i2.valid := lane.io.i2.valid
  io.i2.bits := lane.io.i2.bits
  lane.io.i2.ready := io.i2.ready
}

class OooIexP1I2PcIntegrationSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 2,
    instructionDecodeWidth = 2,
    decodedUopWidth = 2,
    renameWidth = 2,
    dispatchWidth = 2,
    retireGroupWidth = 2,
    robGroupsPerStid = 8,
    robBankCount = 2,
    robRecoveryScanGroupsPerCycle = 2,
    robNonFlushScanGroupsPerCycle = 2,
    pcBufferEntries = 8,
    pcBankCount = 2,
    pcRecoveryScanGroupsPerCycle = 2,
    pcWritePorts = 2,
    iqBankCount = 2,
    iqEntriesPerBank = 4,
    iqFreeSelectLeafEntries = 2,
    tuRetireSourceDepthPerStid = 16)

  private def clear(dut: OooIexP1I2PcIntegrationHarness): Unit = {
    dut.io.pcPrepare.valid.poke(false.B)
    dut.io.pcPrepare.bits.poke(0.U.asTypeOf(dut.io.pcPrepare.bits))
    dut.io.pcPublishFire.poke(false.B)
    dut.io.p1.valid.poke(false.B)
    dut.io.p1.bits.poke(0.U.asTypeOf(dut.io.p1.bits))
    dut.io.readDecisionValid.poke(false.B)
    dut.io.readGrant.poke(false.B)
    dut.io.sourceDataValid.poke(0.U)
    dut.io.sourceData.foreach(_.poke(0.U))
    dut.io.i2.ready.poke(false.B)
  }

  private def pokePcPrepare(
      dut: OooIexP1I2PcIntegrationHarness,
      basePc: Long): Unit = {
    val prepare = dut.io.pcPrepare.bits
    prepare.poke(0.U.asTypeOf(prepare))
    val transaction = prepare.transaction
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(1.U)
    transaction.plan.epoch.poke(7.U)
    transaction.plan.transactionId.poke(102.U)
    transaction.plan.groupCount.poke(2.U)
    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(1.U)
    transaction.decoded.epoch.poke(7.U)
    transaction.decoded.uopMask.poke(3.U)
    transaction.groupMask.poke(3.U)
    Seq(basePc, basePc + 6).zipWithIndex.foreach { case (pc, index) =>
      transaction.uopGroupIndex(index).poke(index.U)
      val group = transaction.groups(index)
      group.valid.poke(true.B)
      group.key.valid.poke(true.B)
      group.key.peId.poke(3.U)
      group.key.stid.poke(1.U)
      group.key.ridSlot.poke((2 + index).U)
      group.key.ridGeneration.poke(1.U)
      group.logicalUopMask.poke((1 << index).U)
      group.physicalMemberCount.poke(1.U)
      group.architecturalParentCount.poke(1.U)

      val uop = transaction.decoded.uops(index)
      uop.valid.poke(true.B)
      uop.identity.parentCount.poke(1.U)
      uop.identity.parents(0).key.valid.poke(true.B)
      uop.identity.parents(0).key.peId.poke(3.U)
      uop.identity.parents(0).key.stid.poke(1.U)
      uop.identity.parents(0).key.instructionId.poke((202 + index).U)
      uop.identity.parents(0).key.epoch.poke(7.U)
      uop.identity.parents(0).pc.poke(pc.U)
    }
    dut.io.pcPrepare.valid.poke(true.B)
  }

  private def pokeP1(
      dut: OooIexP1I2PcIntegrationHarness,
      tokenIndex: BigInt,
      tokenOffset: BigInt,
      tokenEpoch: BigInt): Unit = {
    val request = dut.io.p1.bits
    request.poke(0.U.asTypeOf(request))
    val row = request.row
    row.schedule.valid.poke(true.B)
    row.schedule.peId.poke(3.U)
    row.schedule.stid.poke(1.U)
    row.schedule.epoch.poke(7.U)
    row.schedule.transactionId.poke(102.U)
    row.schedule.member.group.valid.poke(true.B)
    row.schedule.member.group.peId.poke(3.U)
    row.schedule.member.group.stid.poke(1.U)
    row.schedule.member.group.ridSlot.poke(3.U)
    row.schedule.member.group.ridGeneration.poke(1.U)
    row.schedule.member.bid.valid.poke(true.B)
    row.schedule.member.bid.value.poke(5.U)
    row.schedule.member.brobGeneration.poke(2.U)
    row.schedule.member.residentGeneration.poke(4.U)
    row.schedule.reservation.valid.poke(true.B)
    row.schedule.reservation.uopClass.poke(OooUopClass.Alu)
    row.schedule.reservation.bank.poke(0.U)
    row.schedule.reservation.speculativeSlot.poke(1.U)
    row.payload.uopKey.primaryParent.valid.poke(true.B)
    row.payload.uopKey.primaryParent.peId.poke(3.U)
    row.payload.uopKey.primaryParent.stid.poke(1.U)
    row.payload.uopKey.primaryParent.instructionId.poke(203.U)
    row.payload.uopKey.primaryParent.epoch.poke(7.U)
    row.schedule.sources(0).valid.poke(true.B)
    row.schedule.sources(0).ready.poke(true.B)
    row.schedule.sources(0).operandClass.poke(OperandClass.P)
    row.schedule.sources(0).ptag.poke(17.U)
    row.schedule.sources(0).ptagGeneration.poke(3.U)
    row.payload.parentCount.poke(1.U)
    row.payload.parentPcTokens(0).valid.poke(true.B)
    row.payload.parentPcTokens(0).index.poke(tokenIndex.U)
    row.payload.parentPcTokens(0).byteOffset.poke(tokenOffset.U)
    row.payload.parentPcTokens(0).allocationEpoch.poke(tokenEpoch.U)
    request.pcReadRequired.poke(true.B)
    request.pcParentIndex.poke(0.U)
    dut.io.p1.valid.poke(true.B)
  }

  test("reads an allocated PC token into retained I2") {
    simulate(new OooIexP1I2PcIntegrationHarness(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokePcPrepare(dut, basePc = 0x80000120L)
      dut.io.pcPrepareReady.expect(true.B)
      val token = dut.io.pcPrepared.parentTokens(1)(0)
      val tokenIndex = token.index.peek().litValue
      val tokenOffset = token.byteOffset.peek().litValue
      val tokenEpoch = token.allocationEpoch.peek().litValue
      token.valid.expect(true.B)
      token.byteOffset.expect(6.U)
      dut.io.pcPublishFire.poke(true.B)
      dut.clock.step()
      dut.io.pcPublishFire.poke(false.B)
      dut.io.pcPrepare.valid.poke(false.B)

      pokeP1(dut, tokenIndex, tokenOffset, tokenEpoch)
      dut.io.p1.ready.expect(true.B)
      dut.clock.step()
      dut.io.p1.valid.poke(false.B)
      dut.io.readAttempt.valid.expect(true.B)
      dut.io.readAttempt.bits.pcToken.index.expect(tokenIndex.U)

      dut.io.readDecisionValid.poke(true.B)
      dut.io.readGrant.poke(true.B)
      dut.io.sourceDataValid.poke(1.U)
      dut.io.sourceData(0).poke("h1122334455667788".U)
      dut.clock.step()
      dut.io.readDecisionValid.poke(false.B)
      dut.io.readGrant.poke(false.B)

      dut.io.i2.valid.expect(true.B)
      dut.io.i2.bits.pcValid.expect(true.B)
      dut.io.i2.bits.pc.expect("h80000126".U)
      dut.io.i2.bits.sourceData(0).expect("h1122334455667788".U)
    }
  }
}
