package linxcore.iex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.ooo.{OooDispatchClass, OooIexDomainCapability, OooIexIssue,
  OooIexIssueIO, OooIexIssueSlotState, OooIexPhysicalProfile,
  OooLateSplitKind, OooOpcodeDisposition, OooOpcodeRecipeKind,
  OooSideEffectOwner, OooUopClass}
import linxcore.params.SimulationParamProfiles
import linxcore.top.interface.{DispatchTxn, OperandKind, StoreDispatchTxn,
  UopClass}
import org.scalatest.funsuite.AnyFunSuite

/** Contract for the private canonical OOO-to-IEX admission seam.
  *
  * OooIexIssue consumes canonical classed dispatch directly and owns the
  * resulting IQ reservation. These tests protect that post-cutover contract
  * without reconstructing the displaced grouped S1 lease.
  */
class IEXPrivateIngressSpec extends AnyFunSuite with ChiselSim {
  private val core = SimulationParamProfiles.W4.copy(
    iex = SimulationParamProfiles.W4.iex.copy(
      scalarIssueEntries = 4,
      scalarIssueBanks = 2))
  private val physical = OooIexPhysicalProfile.fromCoreParams(core).params

  private def clearDispatch(dut: OooIexIssue): Unit = {
    dut.io.dispatch.aluDispatch.foreach { channel =>
      channel.valid.poke(false.B)
      channel.bits.poke(0.U.asTypeOf(channel.bits))
    }
    dut.io.dispatch.bruDispatch.foreach { channel =>
      channel.valid.poke(false.B)
      channel.bits.poke(0.U.asTypeOf(channel.bits))
    }
    dut.io.dispatch.aguDispatch.foreach { channel =>
      channel.valid.poke(false.B)
      channel.bits.poke(0.U.asTypeOf(channel.bits))
    }
    dut.io.dispatch.storeDispatch.foreach { channel =>
      channel.valid.poke(false.B)
      channel.bits.poke(0.U.asTypeOf(channel.bits))
    }
    dut.io.dispatch.systemDispatch.foreach { channel =>
      channel.valid.poke(false.B)
      channel.bits.poke(0.U.asTypeOf(channel.bits))
    }
    dut.io.dispatch.cmdDispatch.foreach { channel =>
      channel.valid.poke(false.B)
      channel.bits.poke(0.U.asTypeOf(channel.bits))
    }
  }

  private def clearControls(dut: OooIexIssue): Unit = {
    clearDispatch(dut)
    dut.io.storeReserve.ready.poke(true.B)
    dut.io.wakeup.foreach { wakeup =>
      wakeup.valid.poke(false.B)
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
    }
    dut.io.loadCancel.foreach { cancel =>
      cancel.valid.poke(false.B)
      cancel.bits.poke(0.U.asTypeOf(cancel.bits))
    }
    dut.io.releases.foreach { release =>
      release.valid.poke(false.B)
      release.bits.poke(0.U.asTypeOf(release.bits))
    }
    dut.io.queries.foreach(_.poke(0.U.asTypeOf(dut.io.queries.head)))
    dut.io.pickBankEnables.flatten.foreach(_.poke(0.U))
    dut.io.issuePolicy.poke(0.U.asTypeOf(dut.io.issuePolicy))
    dut.io.picks.foreach(_.ready.poke(false.B))
    dut.io.pickRetries.foreach { retry =>
      retry.valid.poke(false.B)
      retry.bits.poke(0.U.asTypeOf(retry.bits))
    }
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
    dut.io.operandReadyBits.poke(
      0.U.asTypeOf(dut.io.operandReadyBits))
  }

  private def pokeRobIdentity(
      txn: DispatchTxn,
      stid: Int,
      ridSlot: Int,
      memberIndex: Int): Unit = {
    val rob = txn.uop.decoded.rob
    rob.peId.poke(3.U)
    rob.stid.poke(stid.U)
    rob.ridSlot.poke(ridSlot.U)
    rob.ridGeneration.poke(5.U)
    rob.memberIndex.poke(memberIndex.U)
    rob.residentGeneration.poke(7.U)
    rob.bid.poke(2.U)
    rob.brobGeneration.poke(11.U)
  }

  private def pokeDispatch(
      dut: OooIexIssue,
      txn: DispatchTxn,
      transactionId: BigInt,
      uopClass: UopClass.Type,
      sourceReady: Boolean = true,
      stid: Int = 0,
      ridSlot: Int = 1,
      memberIndex: Int = 0,
      trapValid: Boolean = false,
      trapCause: Int = 0): Unit = {
    txn.poke(0.U.asTypeOf(txn))
    txn.transactionId.poke(transactionId.U)
    txn.uop.decoded.valid.poke(true.B)
    txn.uop.decoded.uopClass.poke(uopClass)
    txn.uop.decoded.opcode.poke(0x31.U)
    val classification = txn.uop.decoded.classification
    val uopClassValue = uopClass.asUInt.litValue.toInt
    val aluValue = UopClass.Alu.asUInt.litValue.toInt
    val bruValue = UopClass.Bru.asUInt.litValue.toInt
    val aguValue = UopClass.Agu.asUInt.litValue.toInt
    val stdValue = UopClass.Std.asUInt.litValue.toInt
    val systemValue = UopClass.System.asUInt.litValue.toInt
    val cmdValue = UopClass.Cmd.asUInt.litValue.toInt
    classification.valid.poke(true.B)
    classification.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    classification.kind.poke(
      (if (uopClassValue == stdValue) OooOpcodeRecipeKind.ScalarStore
       else if (uopClassValue == aguValue) OooOpcodeRecipeKind.ScalarLoad
       else OooOpcodeRecipeKind.Single).U)
    classification.uopCountMin.poke(1.U)
    classification.uopCountMax.poke(
      (if (uopClassValue == stdValue) 2 else 1).U)
    classification.sideEffectOwner.poke(
      (if (uopClassValue == stdValue || uopClassValue == aguValue)
         OooSideEffectOwner.Lsu
       else if (uopClassValue == bruValue) OooSideEffectOwner.Bctrl
       else if (uopClassValue == systemValue || uopClassValue == cmdValue)
         OooSideEffectOwner.Commit
       else OooSideEffectOwner.Iex).U)
    classification.dispatchWrites.poke(
      (if (uopClassValue == stdValue) 2 else 1).U)
    if (uopClassValue == aluValue) {
      classification.dispatchClass.poke(OooDispatchClass.Alu.U)
      classification.dispatchDemand(OooDispatchClass.Alu - 1).poke(1.U)
      classification.executionPipeCapability(OooDispatchClass.Alu - 1).poke(
        OooIexDomainCapability.mask(OooIexDomainCapability.SimpleAlu).U)
    } else if (uopClassValue == bruValue) {
      classification.dispatchClass.poke(OooDispatchClass.Bru.U)
      classification.dispatchDemand(OooDispatchClass.Bru - 1).poke(1.U)
      classification.executionPipeCapability(OooDispatchClass.Bru - 1).poke(
        OooIexDomainCapability.mask(OooIexDomainCapability.Branch).U)
    } else if (uopClassValue == aguValue) {
      classification.dispatchClass.poke(OooDispatchClass.Agu.U)
      classification.dispatchDemand(OooDispatchClass.Agu - 1).poke(1.U)
      classification.executionPipeCapability(OooDispatchClass.Agu - 1).poke(
        OooIexDomainCapability.mask(OooIexDomainCapability.LoadAddress).U)
    } else if (uopClassValue == stdValue) {
      classification.dispatchClass.poke(OooDispatchClass.Std.U)
      classification.splitKind.poke(OooLateSplitKind.StoreAddressData.U)
      classification.dispatchDemand(OooDispatchClass.Agu - 1).poke(1.U)
      classification.dispatchDemand(OooDispatchClass.Std - 1).poke(1.U)
      classification.executionPipeCapability(OooDispatchClass.Agu - 1).poke(
        OooIexDomainCapability.mask(OooIexDomainCapability.StoreAddress).U)
      classification.executionPipeCapability(OooDispatchClass.Std - 1).poke(
        OooIexDomainCapability.mask(OooIexDomainCapability.StoreData).U)
    } else if (uopClassValue == systemValue) {
      classification.dispatchClass.poke(OooDispatchClass.Sys.U)
      classification.dispatchDemand(OooDispatchClass.Sys - 1).poke(1.U)
      classification.executionPipeCapability(OooDispatchClass.Sys - 1).poke(
        OooIexDomainCapability.mask(OooIexDomainCapability.System).U)
    } else if (uopClassValue == cmdValue) {
      classification.dispatchClass.poke(OooDispatchClass.Cmd.U)
      classification.dispatchDemand(OooDispatchClass.Cmd - 1).poke(1.U)
      classification.executionPipeCapability(OooDispatchClass.Cmd - 1).poke(
        OooIexDomainCapability.mask(OooIexDomainCapability.EngineCommand).U)
    }
    if (uopClassValue == aguValue || uopClassValue == stdValue) {
      val memory = txn.uop.decoded.memory
      memory.valid.poke(true.B)
      memory.isLoad.poke((uopClassValue == aguValue).B)
      memory.isStore.poke((uopClassValue == stdValue).B)
      memory.requestCount.poke(1.U)
      memory.accessBytes.poke(8.U)
      memory.addressSourceMask.poke(1.U)
      memory.dataSourceMask.poke(
        (if (uopClassValue == stdValue) 1 else 0).U)
      classification.memoryRequestCount.poke(1.U)
      txn.memoryOrder.requestCount.poke(1.U)
      txn.memoryOrder.firstLsid.poke((0x100 + ridSlot).U)
      txn.memoryOrder.firstLid.poke((0x20 + ridSlot).U)
      txn.memoryOrder.firstSid.poke((0x40 + ridSlot).U)
    }
    txn.trap.valid.poke(trapValid.B)
    txn.trap.cause.poke(trapCause.U)
    pokeRobIdentity(txn, stid, ridSlot, memberIndex)
    txn.uop.decoded.instruction.parent.identity.peId.poke(3.U)
    txn.uop.decoded.instruction.parent.identity.stid.poke(stid.U)
    txn.uop.decoded.instruction.parent.identity.epoch.poke(7.U)

    val source = txn.uop.sources(0)
    source.valid.poke(true.B)
    source.kind.poke(OperandKind.Gpr)
    source.atag.poke(6.U)
    source.ptagValid.poke(true.B)
    source.ptag.poke(17.U)
    source.pGeneration.poke(9.U)
    source.ready.poke(sourceReady.B)
    dut.io.operandReadyBits.ptag(17).valid.poke(true.B)
    dut.io.operandReadyBits.ptag(17).ready.poke(sourceReady.B)
    dut.io.operandReadyBits.ptag(17).stid.poke(stid.U)
    dut.io.operandReadyBits.ptag(17).epoch.poke(7.U)
    dut.io.operandReadyBits.ptag(17).generation.poke(9.U)
  }

  private def accept(channel: chisel3.util.DecoupledIO[DispatchTxn],
      dut: OooIexIssue): Unit = {
    channel.ready.expect(true.B)
    dut.clock.step()
    channel.valid.poke(false.B)
    dut.clock.step(2)
  }

  private def residentCount(dut: OooIexIssue, uopClass: Int): BigInt =
    (0 until physical.iqBankCount).map { bank =>
      dut.io.residentEntries(uopClass)(bank).peek().litValue
    }.sum

  private def findResident(
      dut: OooIexIssue,
      uopClass: OooUopClass.Type): (Int, Int) = {
    for {
      bank <- 0 until physical.iqBankCount
      entry <- 0 until physical.iqEntriesPerBank
    } {
      dut.io.queries(0).uopClass.poke(uopClass)
      dut.io.queries(0).bank.poke(bank.U)
      dut.io.queries(0).entry.poke(entry.U)
      if (dut.io.queryStates(0).peek().litValue ==
          OooIexIssueSlotState.ResidentS3.asUInt.litValue)
        return (bank, entry)
    }
    fail(s"no resident row found for $uopClass")
  }

  private def findResidentByTransaction(
      dut: OooIexIssue,
      uopClass: OooUopClass.Type,
      transactionId: BigInt): (Int, Int) = {
    for {
      bank <- 0 until physical.iqBankCount
      entry <- 0 until physical.iqEntriesPerBank
    } {
      dut.io.queries(0).uopClass.poke(uopClass)
      dut.io.queries(0).bank.poke(bank.U)
      dut.io.queries(0).entry.poke(entry.U)
      if (dut.io.queryStates(0).peek().litValue ==
          OooIexIssueSlotState.ResidentS3.asUInt.litValue &&
          dut.io.queryRows(0).transactionId.peek().litValue == transactionId)
        return (bank, entry)
    }
    fail(s"no resident $uopClass row found for transaction $transactionId")
  }

  test("W4 exposes classed canonical dispatch vectors without grouped lease ports") {
    val io = new OooIexIssueIO(core)

    assert(io.dispatch.aluDispatch.length == 2)
    assert(io.dispatch.bruDispatch.length == 1)
    assert(io.dispatch.aguDispatch.length == 2)
    assert(io.dispatch.storeDispatch.length == 2)
    assert(io.dispatch.systemDispatch.length == 1)
    assert(io.dispatch.cmdDispatch.length == 1)
    assert(io.dispatch.aluDispatch.head.bits.isInstanceOf[DispatchTxn])
    assert(io.dispatch.storeDispatch.head.bits.isInstanceOf[StoreDispatchTxn])
    assert(!io.elements.contains("s1"))
    assert(!io.elements.contains("dispatchReleases"))
  }

  test("accepts one canonical ALU DispatchTxn directly into IQ residency") {
    simulate(new OooIexIssue(core)) { dut =>
      clearControls(dut)
      val channel = dut.io.dispatch.aluDispatch(0)
      pokeDispatch(dut, channel.bits, transactionId = 41,
        uopClass = UopClass.Alu)
      channel.bits.uop.decoded.classification.nonspeculative.poke(true.B)
      channel.bits.uop.decoded.classification.pcReadRequired.poke(true.B)
      channel.bits.uop.decoded.classification.pcReadClass.poke(6.U)
      channel.valid.poke(true.B)

      accept(channel, dut)

      assert(residentCount(dut, OooUopClass.Alu.asUInt.litValue.toInt) == 1)
      findResident(dut, OooUopClass.Alu)
      dut.io.queryRows(0).transactionId.expect(41.U)
      dut.io.queryRows(0).recipe.nonspeculative.expect(true.B)
      dut.io.queryRows(0).recipe.pcReadRequired.expect(true.B)
      dut.io.queryRows(0).recipe.pcReadClass.expect(6.U)
      dut.io.queryRows(0).recipe.dispatchCapabilities(
        OooDispatchClass.Alu - 1).expect(
          OooIexDomainCapability.mask(OooIexDomainCapability.SimpleAlu).U)
      dut.io.queryRows(0).memoryTransactionValid.expect(false.B)
      dut.io.queryRows(0).initialLoadAttemptValid.expect(false.B)
    }
  }

  test("allocates memory identity at IQ admission and shares it across STA and STD") {
    simulate(new OooIexIssue(core, requireStoreReservation = true)) { dut =>
      clearControls(dut)
      val load = dut.io.dispatch.aguDispatch(0)
      pokeDispatch(dut, load.bits, transactionId = 51,
        uopClass = UopClass.Agu, stid = 0, ridSlot = 1)
      load.valid.poke(true.B)
      accept(load, dut)
      findResidentByTransaction(dut, OooUopClass.Agu, 51)
      dut.io.queryRows(0).memoryTransactionValid.expect(true.B)
      dut.io.queryRows(0).memoryTransaction.value.expect(0.U)
      dut.io.queryRows(0).memoryTransaction.generation.expect(0.U)
      dut.io.queryRows(0).initialLoadAttemptValid.expect(true.B)
      dut.io.queryRows(0).initialLoadAttemptGeneration.expect(1.U)

      val channel = dut.io.dispatch.storeDispatch(1)
      pokeDispatch(dut, channel.bits.sta, transactionId = 52,
        uopClass = UopClass.Std, stid = 0, ridSlot = 2)
      pokeDispatch(dut, channel.bits.std, transactionId = 52,
        uopClass = UopClass.Std, stid = 0, ridSlot = 2)
      channel.bits.sta.memoryOrder.requestCount.poke(1.U)
      channel.bits.sta.memoryOrder.firstLsid.poke(0x1234.U)
      channel.bits.sta.memoryOrder.firstSid.poke(0x55.U)
      channel.bits.std.memoryOrder.poke(channel.bits.sta.memoryOrder.peek())
      channel.bits.aguPipe.poke(0.U)
      channel.bits.stdPipe.poke(1.U)
      channel.valid.poke(true.B)

      channel.ready.expect(true.B)
      dut.io.storeReserve.valid.expect(true.B)
      dut.io.storeReserve.bits.transactionId.expect(52.U)
      dut.io.storeReserve.bits.reservation.uopClass.expect(OooUopClass.Agu)
      dut.io.storeReserve.bits.memoryTransactionValid.expect(true.B)
      dut.io.storeReserve.bits.memoryTransaction.value.expect(1.U)
      dut.io.storeReserve.bits.memoryTransaction.generation.expect(0.U)
      dut.io.storeReserve.bits.initialLoadAttemptValid.expect(false.B)
      dut.io.storeReserve.bits.initialLoadAttemptGeneration.expect(0.U)
      dut.clock.step()
      channel.valid.poke(false.B)
      assert(residentCount(dut, OooUopClass.Agu.asUInt.litValue.toInt) == 1)
      assert(residentCount(dut, OooUopClass.Std.asUInt.litValue.toInt) == 0)
      dut.clock.step(2)

      assert(residentCount(dut, OooUopClass.Agu.asUInt.litValue.toInt) == 2)
      assert(residentCount(dut, OooUopClass.Std.asUInt.litValue.toInt) == 1)
      findResidentByTransaction(dut, OooUopClass.Agu, 52)
      dut.io.queryRows(0).memoryTransaction.value.expect(1.U)
      dut.io.queryRows(0).initialLoadAttemptValid.expect(false.B)
      findResidentByTransaction(dut, OooUopClass.Std, 52)
      dut.io.queryRows(0).memoryTransaction.value.expect(1.U)
      dut.io.queryRows(0).memoryTransaction.generation.expect(0.U)
      dut.io.queryRows(0).initialLoadAttemptValid.expect(false.B)
      dut.io.queryRows(0).initialLoadAttemptGeneration.expect(0.U)
    }
  }

  test("rejects malformed memory admission without consuming IEX identity") {
    simulate(new OooIexIssue(core)) { dut =>
      clearControls(dut)
      val channel = dut.io.dispatch.aguDispatch(0)
      pokeDispatch(dut, channel.bits, transactionId = 57,
        uopClass = UopClass.Agu, ridSlot = 1)
      channel.bits.uop.decoded.memory.valid.poke(false.B)
      channel.valid.poke(true.B)
      channel.ready.expect(false.B)
      dut.clock.step(2)
      assert(residentCount(dut, OooUopClass.Agu.asUInt.litValue.toInt) == 0)

      channel.bits.uop.decoded.memory.valid.poke(true.B)
      accept(channel, dut)
      findResidentByTransaction(dut, OooUopClass.Agu, 57)
      dut.io.queryRows(0).memoryTransaction.value.expect(0.U)
      dut.io.queryRows(0).initialLoadAttemptGeneration.expect(1.U)
    }
  }

  test("does not mutate IQ state while a blocked canonical payload stays stable") {
    val compactCore = core.copy(
      iex = core.iex.copy(scalarIssueEntries = 4, scalarIssueBanks = 2))
    simulate(new OooIexIssue(compactCore)) { dut =>
      clearControls(dut)
      val channel = dut.io.dispatch.aluDispatch(0)
      Seq(61, 62).foreach { transactionId =>
        pokeDispatch(dut, channel.bits, transactionId,
          uopClass = UopClass.Alu)
        channel.valid.poke(true.B)
        accept(channel, dut)
      }
      assert(residentCount(dut, OooUopClass.Alu.asUInt.litValue.toInt) == 2)

      pokeDispatch(dut, channel.bits, transactionId = 63,
        uopClass = UopClass.Alu)
      channel.valid.poke(true.B)
      val retainedPayload = channel.bits.peek().litValue
      channel.ready.expect(false.B)
      dut.clock.step(3)

      channel.ready.expect(false.B)
      assert(channel.bits.peek().litValue == retainedPayload)
      assert(residentCount(dut, OooUopClass.Alu.asUInt.litValue.toInt) == 2)
    }
  }

  test("preserves initial renamed-source readiness when the row becomes resident") {
    simulate(new OooIexIssue(core)) { dut =>
      clearControls(dut)
      val channel = dut.io.dispatch.aluDispatch(0)
      pokeDispatch(dut, channel.bits, transactionId = 74,
        uopClass = UopClass.Alu, sourceReady = false)
      channel.valid.poke(true.B)
      accept(channel, dut)

      findResident(dut, OooUopClass.Alu)
      dut.io.queryRows(0).sources(0).valid.expect(true.B)
      dut.io.queryRows(0).sources(0).ptag.expect(17.U)
      dut.io.queryRows(0).sources(0).ptagGeneration.expect(9.U)
      dut.io.queryRows(0).sources(0).ready.expect(false.B)
      dut.io.queryPickables(0).expect(false.B)
    }
  }

  test("retains the precise decode trap without reconstructing it") {
    simulate(new OooIexIssue(core)) { dut =>
      clearControls(dut)
      val channel = dut.io.dispatch.aluDispatch(0)
      pokeDispatch(dut, channel.bits, transactionId = 83,
        uopClass = UopClass.Alu, trapValid = true, trapCause = 0x2d)
      channel.valid.poke(true.B)
      accept(channel, dut)

      findResident(dut, OooUopClass.Alu)
      dut.io.queryRows(0).preciseTrap.expect(true.B)
      dut.io.queryRows(0).trapCause.expect(0x2d.U)
    }
  }
}
