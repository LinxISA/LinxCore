package linxcore.iex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.log2Ceil
import linxcore.common.OperandClass
import linxcore.ooo.{OooDispatchClass, OooIexDomainCapability, OooIexIssue,
  OooIexIssueIO, OooIexIssueSlotState, OooIexPhysicalProfile,
  OooIexWakeupKind,
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
    dut.io.storeReserve.foreach(_.ready.poke(true.B))
    dut.io.wakeup.foreach { wakeup =>
      wakeup.valid.poke(false.B)
    }
    dut.io.loadCancel.foreach { cancel =>
      cancel.valid.poke(false.B)
    }
    dut.io.loadCancelRetries.flatten.foreach { retry =>
      retry.valid.poke(false.B)
    }
    dut.io.releases.foreach { release =>
      release.valid.poke(false.B)
    }
    dut.io.queries.foreach { query =>
      query.uopClass.poke(OooUopClass.Alu)
      if (dut.p.iqBankCount > 1) query.bank.poke(0.U)
      if (dut.p.iqEntriesPerBank > 1) query.entry.poke(0.U)
    }
    dut.io.pickBankEnables.flatten.foreach(_.poke(0.U))
    dut.io.issuePolicy.poke(0.U.asTypeOf(dut.io.issuePolicy))
    dut.io.picks.foreach(_.ready.poke(false.B))
    dut.io.pickRetries.foreach { retry =>
      retry.valid.poke(false.B)
    }
    dut.io.recovery.prepare.valid.poke(false.B)
    dut.io.recovery.prepared.ready.poke(true.B)
    dut.io.recovery.apply.valid.poke(false.B)
    dut.io.recovery.abort.valid.poke(false.B)
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
    (0 until dut.p.iqBankCount).map { bank =>
      dut.io.residentEntries(uopClass)(bank).peek().litValue
    }.sum

  private def findResident(
      dut: OooIexIssue,
      uopClass: OooUopClass.Type): (Int, Int) = {
    for {
      bank <- 0 until dut.p.iqBankCount
      entry <- 0 until dut.p.iqEntriesPerBank
    } {
      dut.io.queries(0).uopClass.poke(uopClass)
      if (dut.p.iqBankCount > 1) dut.io.queries(0).bank.poke(bank.U)
      if (dut.p.iqEntriesPerBank > 1) dut.io.queries(0).entry.poke(entry.U)
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
      bank <- 0 until dut.p.iqBankCount
      entry <- 0 until dut.p.iqEntriesPerBank
    } {
      dut.io.queries(0).uopClass.poke(uopClass)
      if (dut.p.iqBankCount > 1) dut.io.queries(0).bank.poke(bank.U)
      if (dut.p.iqEntriesPerBank > 1) dut.io.queries(0).entry.poke(entry.U)
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

  test("spills ALU admission into an eligible bank when the first bank is full") {
    val compactCore = core.copy(
      iex = core.iex.copy(scalarIssueEntries = 4, scalarIssueBanks = 2))
    simulate(new OooIexIssue(compactCore)) { dut =>
      clearControls(dut)
      val channel = dut.io.dispatch.aluDispatch(0)
      val aluClass = OooUopClass.Alu.asUInt.litValue.toInt

      Seq(47, 48).foreach { transactionId =>
        pokeDispatch(dut, channel.bits, transactionId,
          uopClass = UopClass.Alu)
        channel.valid.poke(true.B)
        accept(channel, dut)
      }
      dut.io.residentEntries(aluClass)(0).expect(2.U)
      dut.io.residentEntries(aluClass)(1).expect(0.U)

      pokeDispatch(dut, channel.bits, transactionId = 49,
        uopClass = UopClass.Alu)
      channel.valid.poke(true.B)
      channel.ready.expect(true.B)
      dut.clock.step()
      channel.valid.poke(false.B)
      dut.clock.step(2)

      dut.io.residentEntries(aluClass)(0).expect(2.U)
      dut.io.residentEntries(aluClass)(1).expect(1.U)
      findResidentByTransaction(dut, OooUopClass.Alu, 49)
      dut.io.queryRows(0).reservation.bank.expect(1.U)
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
      dut.io.storeReserve(1).valid.expect(true.B)
      dut.io.storeReserve(1).bits.transactionId.expect(52.U)
      dut.io.storeReserve(1).bits.reservation.uopClass.expect(OooUopClass.Agu)
      dut.io.storeReserve(1).bits.memoryTransactionValid.expect(true.B)
      dut.io.storeReserve(1).bits.memoryTransaction.value.expect(1.U)
      dut.io.storeReserve(1).bits.memoryTransaction.generation.expect(0.U)
      dut.io.storeReserve(1).bits.initialLoadAttemptValid.expect(false.B)
      dut.io.storeReserve(1).bits.initialLoadAttemptGeneration.expect(0.U)
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
      Seq(61, 62, 63, 64).foreach { transactionId =>
        pokeDispatch(dut, channel.bits, transactionId,
          uopClass = UopClass.Alu)
        channel.valid.poke(true.B)
        accept(channel, dut)
      }
      assert(residentCount(dut, OooUopClass.Alu.asUInt.litValue.toInt) == 4)

      pokeDispatch(dut, channel.bits, transactionId = 65,
        uopClass = UopClass.Alu)
      channel.valid.poke(true.B)
      val retainedPayload = channel.bits.peek().litValue
      channel.ready.expect(false.B)
      dut.clock.step(3)

      channel.ready.expect(false.B)
      assert(channel.bits.peek().litValue == retainedPayload)
      assert(residentCount(dut, OooUopClass.Alu.asUInt.litValue.toInt) == 4)
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

  test("load-canceled private stages return the exact in-flight IQ row") {
    simulate(new OooIexIssue(core)) { dut =>
      clearControls(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      val channel = dut.io.dispatch.aluDispatch(0)
      pokeDispatch(dut, channel.bits, transactionId = 740,
        uopClass = UopClass.Alu)
      channel.valid.poke(true.B)
      accept(channel, dut)

      val aluClass = OooUopClass.Alu.asUInt.litValue.toInt
      val (bank, entry) = findResidentByTransaction(
        dut, OooUopClass.Alu, 740)
      val capability = OooIexDomainCapability.mask(
        OooIexDomainCapability.SimpleAlu)
      val domain = OooIexPhysicalProfile.fromCoreParams(core).transferConfigs
        .zipWithIndex.collectFirst {
          case (config, index)
              if (config.classBankEnables(aluClass) & (1 << bank)) != 0 &&
                (config.capabilities & capability) != 0 => index
        }.get

      dut.io.pickBankEnables(domain)(aluClass).poke((1 << bank).U)
      dut.io.picks(domain).ready.poke(false.B)
      var pickVisible = false
      for (_ <- 0 until 4 if !pickVisible) {
        pickVisible = dut.io.picks(domain).valid.peek().litToBoolean
        if (!pickVisible) dut.clock.step()
      }
      assert(pickVisible)
      dut.io.picks(domain).ready.poke(true.B)
      dut.clock.step()
      dut.io.picks(domain).ready.poke(false.B)

      dut.io.queries(0).uopClass.poke(OooUopClass.Alu)
      dut.io.queries(0).bank.poke(bank.U)
      dut.io.queries(0).entry.poke(entry.U)
      dut.io.queryRows(0).schedule.inFlight.expect(true.B)

      val retry = dut.io.loadCancelRetries(domain)(1)
      retry.bits.member.poke(dut.io.queryRows(0).member.peek())
      retry.bits.reservation.poke(dut.io.queryRows(0).reservation.peek())
      retry.valid.poke(true.B)
      dut.clock.step()
      retry.valid.poke(false.B)

      dut.io.queryRows(0).schedule.inFlight.expect(false.B)
      dut.io.queryStates(0).expect(OooIexIssueSlotState.ResidentS3)
    }
  }

  test("accepts an exact PTag generation across frontend epochs") {
    simulate(new OooIexIssue(core)) { dut =>
      clearControls(dut)
      val channel = dut.io.dispatch.aluDispatch(0)
      pokeDispatch(dut, channel.bits, transactionId = 741,
        uopClass = UopClass.Alu, sourceReady = false)
      channel.bits.uop.decoded.instruction.parent.identity.epoch.poke(8.U)
      dut.io.operandReadyBits.ptag(17).ready.poke(true.B)
      // The committed physical mapping was created in epoch 7.  Generation
      // 9 remains its exact reuse identity for a consumer in epoch 8.
      dut.io.operandReadyBits.ptag(17).epoch.poke(7.U)
      channel.valid.poke(true.B)
      accept(channel, dut)

      findResidentByTransaction(dut, OooUopClass.Alu, 741)
      dut.io.queryRows(0).sources(0).ready.expect(true.B)
    }
  }

  test("wakes-resident-p-source-across-frontend-epochs") {
    simulate(new OooIexIssue(SimulationParamProfiles.W2)) { dut =>
      clearControls(dut)
      val channel = dut.io.dispatch.aluDispatch(0)
      pokeDispatch(dut, channel.bits, transactionId = 742,
        uopClass = UopClass.Alu, sourceReady = false)
      channel.bits.uop.decoded.instruction.parent.identity.epoch.poke(8.U)
      channel.valid.poke(true.B)
      accept(channel, dut)

      findResidentByTransaction(dut, OooUopClass.Alu, 742)
      dut.io.queryRows(0).sources(0).ready.expect(false.B)

      val wakeup = dut.io.wakeup(0)
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.kind.poke(OooIexWakeupKind.Committed)
      wakeup.bits.stid.poke(0.U)
      // A P mapping can be produced in an older frontend epoch.  Its
      // generation, rather than that producer epoch, qualifies the wakeup.
      wakeup.bits.epoch.poke(7.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(17.U)
      wakeup.bits.ptagGeneration.poke(9.U)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)

      dut.io.queryRows(0).sources(0).ready.expect(true.B)

      pokeDispatch(dut, channel.bits, transactionId = 743,
        uopClass = UopClass.Alu, sourceReady = false, ridSlot = 2)
      channel.bits.uop.decoded.instruction.parent.identity.epoch.poke(9.U)
      channel.valid.poke(true.B)
      accept(channel, dut)
      findResidentByTransaction(dut, OooUopClass.Alu, 743)
      dut.io.queryRows(0).sources(0).specReady.expect(false.B)

      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.kind.poke(OooIexWakeupKind.SpeculativeLoad)
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(7.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(17.U)
      wakeup.bits.ptagGeneration.poke(9.U)
      wakeup.bits.load.valid.poke(true.B)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)

      dut.io.queryRows(0).sources(0).specReady.expect(true.B)

      pokeDispatch(dut, channel.bits, transactionId = 744,
        uopClass = UopClass.Alu, sourceReady = false, ridSlot = 3)
      channel.bits.uop.decoded.instruction.parent.identity.epoch.poke(10.U)
      channel.valid.poke(true.B)
      accept(channel, dut)
      findResidentByTransaction(dut, OooUopClass.Alu, 744)
      dut.io.queryRows(0).sources(0).ready.expect(false.B)

      // A file write and row admission can cross the same edge after the
      // one-cycle wakeup.  The resident row must converge from the exact
      // generation-qualified readiness state instead of waiting forever for
      // a second wakeup pulse.
      dut.io.operandReadyBits.ptag(17).ready.poke(true.B)
      dut.io.queryRows(0).sources(0).ready.expect(true.B)
      dut.io.queryRows(0).sources(0).specReady.expect(false.B)
      dut.clock.step()
      dut.io.queryRows(0).sources(0).ready.expect(true.B)
    }
  }

  test("accepts-exact-local-source-owner-epoch-across-frontend-epochs") {
    simulate(new OooIexIssue(SimulationParamProfiles.W2)) { dut =>
      clearControls(dut)
      val channel = dut.io.dispatch.aluDispatch(0)
      pokeDispatch(dut, channel.bits, transactionId = 745,
        uopClass = UopClass.Alu, sourceReady = false)
      channel.bits.uop.decoded.instruction.parent.identity.epoch.poke(11.U)
      val source = channel.bits.uop.sources(0)
      source.kind.poke(OperandKind.T)
      source.ptagValid.poke(false.B)
      source.ttagValid.poke(true.B)
      source.ttag.poke(4.U)
      source.tSeqIndex.poke(4.U)
      source.tSeqGeneration.poke(0.U)
      source.localEpoch.poke(9.U)
      val ready = dut.io.operandReadyBits.ttag(0)(4)
      ready.allocated.poke(true.B)
      ready.ready.poke(true.B)
      ready.epoch.poke(9.U)
      ready.sequence.valid.poke(true.B)
      ready.sequence.index.poke(4.U)
      ready.sequence.generation.poke(0.U)
      channel.valid.poke(true.B)
      accept(channel, dut)

      findResidentByTransaction(dut, OooUopClass.Alu, 745)
      dut.io.queryRows(0).sources(0).localEpoch.expect(9.U)
      dut.io.queryRows(0).sources(0).ready.expect(true.B)
    }
  }

  test("unequal identity and physical tag capacities reject aliases for P T and U readiness") {
    simulate(new OooIexIssue(core)) { dut =>
      clearControls(dut)
      assert(physical.pTagWidth > log2Ceil(physical.pPhysRegs))
      assert(physical.tTagWidth > log2Ceil(physical.tPhysRegs))
      assert(physical.uTagWidth > log2Ceil(physical.uPhysRegs))

      def configureLocalSource(
          source: linxcore.top.interface.RenamedSource,
          kind: OperandKind.Type,
          tag: Int,
          sequenceIndex: Int,
          sequenceGeneration: Int): Unit = {
        source.valid.poke(true.B)
        source.kind.poke(kind)
        source.ready.poke(false.B)
        if (kind == OperandKind.T) {
          source.ttagValid.poke(true.B)
          source.ttag.poke(tag.U)
          source.tSeqIndex.poke(sequenceIndex.U)
          source.tSeqGeneration.poke(sequenceGeneration.U)
        } else {
          source.utagValid.poke(true.B)
          source.utag.poke(tag.U)
          source.uSeqIndex.poke(sequenceIndex.U)
          source.uSeqGeneration.poke(sequenceGeneration.U)
        }
      }

      def configureLocalReady(
          entry: linxcore.ooo.OooIexLocalReadyBitsEntry,
          sequenceIndex: Int,
          sequenceGeneration: Int): Unit = {
        entry.allocated.poke(true.B)
        entry.ready.poke(true.B)
        entry.epoch.poke(7.U)
        entry.sequence.valid.poke(true.B)
        entry.sequence.index.poke(sequenceIndex.U)
        entry.sequence.generation.poke(sequenceGeneration.U)
      }

      val channel = dut.io.dispatch.aluDispatch(0)
      pokeDispatch(dut, channel.bits, transactionId = 75,
        uopClass = UopClass.Alu, sourceReady = false)
      configureLocalSource(channel.bits.uop.sources(1), OperandKind.T,
        tag = 3, sequenceIndex = 1, sequenceGeneration = 2)
      configureLocalSource(channel.bits.uop.sources(2), OperandKind.U,
        tag = 4, sequenceIndex = 3, sequenceGeneration = 4)
      dut.io.operandReadyBits.ptag(17).ready.poke(true.B)
      configureLocalReady(dut.io.operandReadyBits.ttag(0)(3),
        sequenceIndex = 1, sequenceGeneration = 2)
      configureLocalReady(dut.io.operandReadyBits.utag(0)(4),
        sequenceIndex = 3, sequenceGeneration = 4)
      channel.valid.poke(true.B)
      accept(channel, dut)
      findResidentByTransaction(dut, OooUopClass.Alu, 75)
      dut.io.queryRows(0).sources(0).ready.expect(true.B)
      dut.io.queryRows(0).sources(1).ready.expect(true.B)
      dut.io.queryRows(0).sources(2).ready.expect(true.B)

      pokeDispatch(dut, channel.bits, transactionId = 76,
        uopClass = UopClass.Alu, sourceReady = false)
      channel.bits.uop.sources(0).ptag.poke(physical.pPhysRegs.U)
      configureLocalSource(channel.bits.uop.sources(1), OperandKind.T,
        tag = physical.tPhysRegs, sequenceIndex = 5,
        sequenceGeneration = 6)
      configureLocalSource(channel.bits.uop.sources(2), OperandKind.U,
        tag = physical.uPhysRegs, sequenceIndex = 7,
        sequenceGeneration = 8)
      dut.io.operandReadyBits.ptag(0).valid.poke(true.B)
      dut.io.operandReadyBits.ptag(0).ready.poke(true.B)
      dut.io.operandReadyBits.ptag(0).stid.poke(0.U)
      dut.io.operandReadyBits.ptag(0).epoch.poke(7.U)
      dut.io.operandReadyBits.ptag(0).generation.poke(9.U)
      configureLocalReady(dut.io.operandReadyBits.ttag(0)(0),
        sequenceIndex = 5, sequenceGeneration = 6)
      configureLocalReady(dut.io.operandReadyBits.utag(0)(0),
        sequenceIndex = 7, sequenceGeneration = 8)
      channel.valid.poke(true.B)
      accept(channel, dut)
      findResidentByTransaction(dut, OooUopClass.Alu, 76)
      dut.io.queryRows(0).sources(0).ready.expect(false.B)
      dut.io.queryRows(0).sources(1).ready.expect(false.B)
      dut.io.queryRows(0).sources(2).ready.expect(false.B)
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
