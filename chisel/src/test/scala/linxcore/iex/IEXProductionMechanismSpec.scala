package linxcore.iex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import linxcore.ooo._
import linxcore.params.ParamProfiles
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

/** Aggregated evidence for the production IEX children before the public-box
  * cutover. This suite intentionally instantiates no public `IEX` owner.
  */
class IEXProductionMechanismSpec extends AnyFunSuite with ChiselSim {
  private val mechanismParams = OooParams(
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
    iexIssueDomainCount = 2,
    iexPReadPorts = 3,
    iexTReadPorts = 2,
    iexUReadPorts = 2,
    tuRetireSourceDepthPerStid = 16)

  private def pokeMember(
      target: RobMemberKey,
      stid: Int,
      ridSlot: Int,
      memberIndex: Int = 0): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(stid.U)
    target.group.ridSlot.poke(ridSlot.U)
    target.group.ridGeneration.poke(1.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(5.U)
    target.brobGeneration.poke(2.U)
    target.memberIndex.poke(memberIndex.U)
    target.residentGeneration.poke(4.U)
  }
  test("W2 W4 W6 W8 derive the requested private topology from canonical parameters") {
    Seq(2, 4, 6, 8).foreach { width =>
      val profile = OooIexProductionPhysicalProfile(
        ParamProfiles.forWidth(width))

      assert(profile.aluCount == 2)
      assert(profile.bruCount == 1)
      assert(profile.aguCount == 2)
      assert(profile.stdCount == 2)
      assert(profile.systemMulticycleCount == 1)
      assert(profile.commandCount == 1)
      assert(profile.physical.params.instructionDecodeWidth == width)
      val aluResidency = profile.physical.ownersOf(OooDispatchClass.Alu - 1)
      assert(aluResidency.count(
        _.hasCapability(OooIexDomainCapability.SimpleAlu)) == 2)
      assert(aluResidency.count(
        _.hasCapability(OooIexDomainCapability.MultiCycleAlu)) == 1)
      assert(profile.physical.ownersOf(OooDispatchClass.Bru - 1).size == 1)
      assert(profile.physical.ownersOf(OooDispatchClass.Agu - 1).size == 2)
      assert(profile.physical.ownersOf(OooDispatchClass.Std - 1).size == 2)
      assert(profile.physical.ownersOf(OooDispatchClass.Sys - 1).size == 1)
      assert(profile.physical.ownersOf(OooDispatchClass.Cmd - 1).size == 1)
      assert(profile.physical.ownersOf(OooDispatchClass.Std - 1).forall(
        _.hasCapability(OooIexDomainCapability.StoreData)))
    }
  }

  test("the W4 private profile elaborates the real execution child topology") {
    val profile = OooIexProductionPhysicalProfile(ParamProfiles.W4)
    val systemVerilog = ChiselStage.emitSystemVerilog(
      new OooIexExecutionCluster(profile.physical),
      firtoolOpts = Array("--disable-all-randomization"))

    assert(systemVerilog.contains("module OooIexExecutionCluster"))
    assert("OooIexAluPipeline alus_".r
      .findAllMatchIn(systemVerilog).length == 2)
    assert("OooIexBruPipeline brus_".r
      .findAllMatchIn(systemVerilog).length == 1)
    assert("OooIexAguPipeline agus_".r
      .findAllMatchIn(systemVerilog).length == 2)

    val compactParams = profile.physical.params.copy(
      robGroupsPerStid = 8,
      robBankCount = 8,
      robRecoveryScanGroupsPerCycle = 8,
      robNonFlushScanGroupsPerCycle = 8,
      pcBufferEntries = 32,
      pMapQDepthPerStid = 16,
      tuMapQDepthPerStid = 8,
      tuRetireSourceDepthPerStid = 32,
      iqEntriesPerBank = 1,
      iqFreeSelectLeafEntries = 1)
    val compactProfile = profile.physical.copy(params = compactParams)
    val pipelineChirrtl = ChiselStage.emitCHIRRTL(
      new OooIexExecutionPipeline(compactProfile))
    assert(pipelineChirrtl.contains("module OooIexExecutionPipeline"))
    assert(pipelineChirrtl.contains("inst issue of OooIexPipeline"))
    assert(pipelineChirrtl.contains("inst execute of OooIexExecutionCluster"))
  }

  test("oldest-ready selection preserves same-STID age and peer fairness under scoped recovery") {
    val p = mechanismParams
    val aluClass = OooDispatchClass.Alu - 1
    simulate(new OooIexOldestReadyPicker(p)) { dut =>
      dut.io.classBankEnables.foreach(_.poke(0.U))
      dut.io.classBankEnables(aluClass).poke("b11".U)
      dut.io.stidBlock.poke(0.U)
      dut.io.candidates.foreach(_.foreach(_.foreach(
        _.poke(0.U.asTypeOf(dut.io.candidates.head.head.head)))))
      dut.io.pick.ready.poke(false.B)
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.recoveryApply.bits.poke(0.U.asTypeOf(dut.io.recoveryApply.bits))

      def candidate(bank: Int, entry: Int, stid: Int, ridSlot: Int): Unit = {
        val row = dut.io.candidates(aluClass)(bank)(entry)
        row.poke(0.U.asTypeOf(row))
        row.eligible.poke(true.B)
        row.peId.poke(3.U)
        row.stid.poke(stid.U)
        row.epoch.poke(7.U)
        row.transactionId.poke((100 + ridSlot).U)
        pokeMember(row.member, stid, ridSlot)
        row.reservation.valid.poke(true.B)
        row.reservation.uopClass.poke(OooUopClass.Alu)
        row.reservation.bank.poke(bank.U)
        row.reservation.speculativeSlot.poke(entry.U)
        row.reservation.reservationEpoch.poke(9.U)
      }

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      candidate(bank = 0, entry = 0, stid = 0, ridSlot = 1)
      candidate(bank = 1, entry = 0, stid = 0, ridSlot = 3)
      candidate(bank = 0, entry = 1, stid = 1, ridSlot = 2)
      dut.clock.step()
      dut.io.pick.bits.candidate.stid.expect(0.U)
      dut.io.pick.bits.query.bank.expect(0.U)
      dut.io.pick.bits.query.entry.expect(0.U)

      dut.io.pick.ready.poke(true.B)
      dut.clock.step()
      dut.io.candidates(aluClass)(0)(0).eligible.poke(false.B)
      dut.io.pick.bits.candidate.stid.expect(1.U)

      val recovery = dut.io.recoveryApply.bits
      recovery.poke(0.U.asTypeOf(recovery))
      recovery.valid.poke(true.B)
      recovery.oldHead.valid.poke(true.B)
      recovery.oldHead.peId.poke(3.U)
      recovery.oldHead.stid.poke(0.U)
      recovery.oldHead.ridSlot.poke(0.U)
      recovery.oldHead.ridGeneration.poke(1.U)
      recovery.oldOccupied.poke(4.U)
      recovery.newOccupied.poke(0.U)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.pick.bits.candidate.stid.expect(1.U)
    }
  }

  test("atomic read admission grants one complete P T U group or no group") {
    val p = mechanismParams
    simulate(new OooIexAtomicReadArbiter(p)) { dut =>
      dut.io.attempts.foreach(_.poke(0.U.asTypeOf(dut.io.attempts.head)))
      dut.io.pReadResponses.foreach(
        _.poke(0.U.asTypeOf(dut.io.pReadResponses.head)))
      dut.io.tReadResponses.foreach(
        _.poke(0.U.asTypeOf(dut.io.tReadResponses.head)))
      dut.io.uReadResponses.foreach(
        _.poke(0.U.asTypeOf(dut.io.uReadResponses.head)))
      dut.io.pcReadResponses.foreach(
        _.poke(0.U.asTypeOf(dut.io.pcReadResponses.head)))

      val attempt = dut.io.attempts(0)
      attempt.valid.poke(true.B)
      attempt.bits.stid.poke(0.U)
      attempt.bits.epoch.poke(7.U)
      attempt.bits.transactionId.poke(31.U)
      pokeMember(attempt.bits.member, stid = 0, ridSlot = 2)
      attempt.bits.reservation.valid.poke(true.B)
      attempt.bits.reservation.uopClass.poke(OooUopClass.Alu)
      attempt.bits.reservation.bank.poke(0.U)
      attempt.bits.reservation.speculativeSlot.poke(1.U)
      attempt.bits.reservation.reservationEpoch.poke(9.U)
      attempt.bits.sourceMask.poke("b0111".U)
      Seq(OperandClass.P, OperandClass.T, OperandClass.U)
        .zipWithIndex.foreach { case (operandClass, index) =>
          val source = attempt.bits.sources(index)
          source.valid.poke(true.B)
          source.ready.poke(true.B)
          source.operandClass.poke(operandClass)
          source.ptag.poke((40 + index).U)
          source.ptagGeneration.poke(2.U)
          source.localTag.poke((index + 1).U)
          source.localSequence.valid.poke(true.B)
          source.localSequence.index.poke(index.U)
          source.localSequence.generation.poke((10 + index).U)
        }
      dut.io.pReadResponses(0).valid.poke(true.B)
      dut.io.pReadResponses(0).bits.poke(11.U)
      dut.io.tReadResponses(0).valid.poke(true.B)
      dut.io.tReadResponses(0).bits.poke(22.U)
      dut.io.uReadResponses(0).valid.poke(true.B)
      dut.io.uReadResponses(0).bits.poke(33.U)

      dut.io.selectedMask.expect(1.U)
      dut.io.grant(0).expect(true.B)
      dut.io.pReadRequests(0).valid.expect(true.B)
      dut.io.tReadRequests(0).valid.expect(true.B)
      dut.io.uReadRequests(0).valid.expect(true.B)
      dut.io.sourceDataValid(0).expect("b0111".U)
      dut.io.sourceData(0)(0).expect(11.U)
      dut.io.sourceData(0)(1).expect(22.U)
      dut.io.sourceData(0)(2).expect(33.U)

      dut.io.tReadResponses(0).valid.poke(false.B)
      dut.io.grant(0).expect(true.B)
      dut.io.sourceDataValid(0).expect("b0101".U)
    }
  }

  test("a denied or partial I1 read returns the exact row for retry") {
    val p = mechanismParams
    simulate(new OooIexP1I2Lane(p)) { dut =>
      dut.io.p1.valid.poke(false.B)
      dut.io.p1.bits.poke(0.U.asTypeOf(dut.io.p1.bits))
      dut.io.readDecisionValid.poke(false.B)
      dut.io.readGrant.poke(false.B)
      dut.io.sourceDataValid.poke(0.U)
      dut.io.sourceData.foreach(_.poke(0.U))
      dut.io.pcDataValid.poke(false.B)
      dut.io.pcData.poke(0.U)
      dut.io.bypass.foreach(
        _.poke(0.U.asTypeOf(dut.io.bypass.head)))
      dut.io.loadCancel.foreach(
        _.poke(0.U.asTypeOf(dut.io.loadCancel.head)))
      dut.io.stageCancel.foreach { cancel =>
        cancel.valid.poke(false.B)
        cancel.bits.poke(0.U.asTypeOf(cancel.bits))
      }
      dut.io.repick.ready.poke(true.B)
      dut.io.i2.ready.poke(false.B)
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.recoveryApply.bits.poke(
        0.U.asTypeOf(dut.io.recoveryApply.bits))

      val row = dut.io.p1.bits.row.schedule
      row.valid.poke(true.B)
      row.peId.poke(3.U)
      row.stid.poke(0.U)
      row.epoch.poke(7.U)
      row.transactionId.poke(44.U)
      pokeMember(row.member, stid = 0, ridSlot = 4)
      row.reservation.valid.poke(true.B)
      row.reservation.uopClass.poke(OooUopClass.Alu)
      row.reservation.bank.poke(0.U)
      row.reservation.speculativeSlot.poke(1.U)
      row.reservation.reservationEpoch.poke(9.U)
      row.sources(0).valid.poke(true.B)
      row.sources(0).ready.poke(true.B)
      row.sources(0).operandClass.poke(OperandClass.P)
      row.sources(1).valid.poke(true.B)
      row.sources(1).ready.poke(true.B)
      row.sources(1).operandClass.poke(OperandClass.T)
      dut.io.p1.bits.row.payload.uopKey.primaryParent.valid.poke(true.B)
      dut.io.p1.bits.row.payload.uopKey.primaryParent.peId.poke(3.U)
      dut.io.p1.bits.row.payload.uopKey.primaryParent.stid.poke(0.U)
      dut.io.p1.bits.row.payload.uopKey.primaryParent.instructionId.poke(9.U)

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.p1.valid.poke(true.B)
      dut.clock.step()
      dut.io.p1.valid.poke(false.B)
      dut.io.readAttempt.valid.expect(true.B)
      dut.io.readDecisionValid.poke(true.B)
      dut.io.readGrant.poke(true.B)
      dut.io.sourceDataValid.poke("b0001".U)
      dut.io.repick.valid.expect(true.B)
      dut.io.repick.bits.member.group.ridSlot.expect(4.U)
      dut.io.repick.bits.reservation.speculativeSlot.expect(1.U)
      dut.io.i2.valid.expect(false.B)
    }
  }

  test("terminal publication retains its owner until P T U peers can fire atomically") {
    val p = mechanismParams
    simulate(new OooIexTerminalPublish(p)) { dut =>
      dut.io.alu.valid.poke(false.B)
      dut.io.alu.bits.poke(0.U.asTypeOf(dut.io.alu.bits))
      dut.io.bru.valid.poke(false.B)
      dut.io.bru.bits.poke(0.U.asTypeOf(dut.io.bru.bits))
      dut.io.load.valid.poke(false.B)
      dut.io.load.bits.poke(0.U.asTypeOf(dut.io.load.bits))
      dut.io.pWrite.foreach(_.ready.poke(true.B))
      dut.io.tWrite.foreach(_.ready.poke(true.B))
      dut.io.uWrite.foreach(_.ready.poke(true.B))
      dut.io.wakeup.foreach(_.ready.poke(true.B))
      dut.io.bctrl.ready.poke(true.B)
      dut.io.trace.ready.poke(true.B)
      dut.io.completion.ready.poke(true.B)

      val terminal = dut.io.alu.bits
      val execute = terminal.execute
      execute.ownerClass.poke(OooUopClass.Alu)
      val row = execute.i2.row.schedule
      row.valid.poke(true.B)
      row.peId.poke(3.U)
      row.stid.poke(1.U)
      row.epoch.poke(7.U)
      pokeMember(row.member, stid = 1, ridSlot = 2)
      row.reservation.valid.poke(true.B)
      row.reservation.uopClass.poke(OooUopClass.Alu)
      execute.i2.row.payload.opcode.poke(0x51.U)
      execute.i2.row.payload.recipe.valid.poke(true.B)
      execute.i2.row.payload.recipe.opcode.poke(0x51.U)
      execute.i2.row.payload.recipe.disposition.poke(
        OooOpcodeDisposition.Dispatch.U)
      execute.i2.row.payload.recipe.dispatchClass.poke(
        OooDispatchClass.Alu.U)
      execute.i2.row.payload.recipe.sideEffectOwner.poke(
        OooSideEffectOwner.Iex.U)
      execute.i2.row.payload.uopKey.primaryParent.valid.poke(true.B)
      execute.i2.row.payload.uopKey.primaryParent.peId.poke(3.U)
      execute.i2.row.payload.uopKey.primaryParent.stid.poke(1.U)
      execute.i2.row.payload.uopKey.primaryParent.instructionId.poke(9.U)
      execute.i2.row.payload.uopKey.uopCount.poke(1.U)

      def destination(target: OooIexDestinationState,
          kind: DestinationKind.Type, ordinal: Int): Unit = {
        target.valid.poke(true.B)
        target.kind.poke(kind)
        target.atag.poke((4 + ordinal).U)
        target.ptag.poke((30 + ordinal).U)
        target.ptagGeneration.poke(3.U)
        target.localTag.poke((6 + ordinal).U)
        target.localSequence.valid.poke(true.B)
        target.localSequence.index.poke((10 + ordinal).U)
        target.localSequence.generation.poke(2.U)
      }
      destination(row.destinations(0), DestinationKind.T, 0)
      destination(row.destinations(1), DestinationKind.U, 1)
      terminal.writebacks(0).valid.poke(true.B)
      destination(terminal.writebacks(0).destination, DestinationKind.T, 0)
      terminal.writebacks(0).data.poke(0x100.U)
      terminal.writebacks(1).valid.poke(true.B)
      destination(terminal.writebacks(1).destination, DestinationKind.U, 1)
      terminal.writebacks(1).data.poke(0x101.U)
      dut.io.alu.valid.poke(true.B)

      dut.io.uWrite(1).ready.poke(false.B)
      dut.io.alu.ready.expect(false.B)
      dut.io.tWrite(0).valid.expect(false.B)
      dut.io.uWrite(1).valid.expect(true.B)
      dut.io.terminalFire.expect(false.B)

      dut.io.uWrite(1).ready.poke(true.B)
      dut.io.alu.ready.expect(true.B)
      dut.io.tWrite(0).valid.expect(true.B)
      dut.io.uWrite(1).valid.expect(true.B)
      dut.io.wakeup(0).bits.operandClass.expect(OperandClass.T)
      dut.io.wakeup(1).bits.operandClass.expect(OperandClass.U)
      dut.io.terminalFire.expect(true.B)
    }
  }
}
