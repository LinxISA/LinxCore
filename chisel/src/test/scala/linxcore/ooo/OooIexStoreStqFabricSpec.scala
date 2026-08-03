package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.top.interface.{RecoveryCause, RecoveryPhase, RecoveryPlan,
  RobIdentity}
import org.scalatest.funsuite.AnyFunSuite

class OooIexStoreStqFabricSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 2,
    instructionDecodeWidth = 2,
    decodedUopWidth = 4,
    renameWidth = 4,
    dispatchWidth = 4,
    retireGroupWidth = 2,
    robGroupsPerStid = 8,
    robBankCount = 4,
    robRecoveryScanGroupsPerCycle = 2,
    robNonFlushScanGroupsPerCycle = 2,
    pcBufferEntries = 8,
    pcBankCount = 4,
    pcRecoveryScanGroupsPerCycle = 2,
    pcWritePorts = 3,
    iqBankCount = 2,
    iqEntriesPerBank = 4,
    iqFreeSelectLeafEntries = 2,
    tuRetireSourceDepthPerStid = 32,
    lsidWidth = 40)
  private val core = OooRecoveryMembership.coreParams(p)

  private def pokeMember(
      member: RobMemberKey,
      memberIndex: Int,
      ridSlot: Int,
      stid: Int = 1): Unit = {
    member.poke(0.U.asTypeOf(member))
    member.group.valid.poke(true.B)
    member.group.peId.poke(1.U)
    member.group.stid.poke(stid.U)
    member.group.ridSlot.poke(ridSlot.U)
    member.group.ridGeneration.poke(7.U)
    member.bid.valid.poke(true.B)
    member.bid.value.poke(0x93.U)
    member.brobGeneration.poke(8.U)
    member.memberIndex.poke(memberIndex.U)
    member.residentGeneration.poke(9.U)
  }

  private def pokeStoreRow(
      row: OooIexIssueRow,
      memberIndex: Int,
      ridSlot: Int,
      firstLsid: BigInt,
      firstStoreId: BigInt,
      requestCount: Int = 1,
      stid: Int = 1): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.schedule.valid.poke(true.B)
    row.schedule.peId.poke(1.U)
    row.schedule.stid.poke(stid.U)
    row.schedule.childIndex.poke(0.U)
    pokeMember(row.schedule.member, memberIndex, ridSlot, stid)
    row.schedule.reservation.valid.poke(true.B)
    row.schedule.reservation.uopClass.poke(OooUopClass.Agu)
    row.payload.recipe.valid.poke(true.B)
    row.payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    row.payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
    row.payload.recipe.recipeKind.poke(
      (if (requestCount == 2) OooOpcodeRecipeKind.PairStore
       else OooOpcodeRecipeKind.ScalarStore))
    row.payload.recipe.lateSplitKind.poke(
      (if (requestCount == 2) OooLateSplitKind.PairStoreAddressData
       else OooLateSplitKind.StoreAddressData))
    row.payload.memory.valid.poke(true.B)
    row.payload.memory.isStore.poke(true.B)
    row.payload.memory.addressMode.poke(OooMemoryAddressMode.BaseOffset)
    row.payload.memory.accessBytes.poke(8.U)
    row.payload.memory.offset.poke(16.U)
    row.payload.memory.addressSourceMask.poke(1.U)
    row.payload.memory.dataSourceMask.poke(
      (if (requestCount == 2) 12 else 4).U)
    row.payload.memoryOrder.valid.poke(true.B)
    row.payload.memoryOrder.memoryValid.poke(true.B)
    row.payload.memoryOrder.isStore.poke(true.B)
    row.payload.memoryOrder.requestCount.poke(requestCount.U)
    row.payload.memoryOrder.firstLsid.poke(firstLsid.U)
    row.payload.memoryOrder.firstTypeId.poke(firstStoreId.U)
  }

  private def pokeExecute(
      execute: OooIexExecuteTransaction,
      addressHalf: Boolean,
      memberIndex: Int,
      ridSlot: Int,
      firstLsid: BigInt,
      firstStoreId: BigInt,
      requestCount: Int = 1): Unit = {
    execute.poke(0.U.asTypeOf(execute))
    execute.ownerClass.poke(
      (if (addressHalf) OooUopClass.Agu else OooUopClass.Std))
    pokeStoreRow(execute.i2.row,
      memberIndex = memberIndex,
      ridSlot = ridSlot,
      firstLsid = firstLsid,
      firstStoreId = firstStoreId,
      requestCount = requestCount)
    execute.i2.row.schedule.childIndex.poke(
      (if (addressHalf) 0 else 1).U)
    execute.i2.row.schedule.reservation.uopClass.poke(
      (if (addressHalf) OooUopClass.Agu else OooUopClass.Std))
    execute.i2.sourceMask.poke((if (addressHalf) 1 else
      (if (requestCount == 2) 12 else 4)).U)
    execute.i2.sourceData(0).poke((0x1000 + ridSlot * 0x100).U)
    execute.i2.sourceData(2).poke(
      (BigInt("1122334455667788", 16) + ridSlot).U)
    execute.i2.sourceData(3).poke(
      (BigInt("8877665544332211", 16) + ridSlot).U)
  }

  private def defaults(dut: OooIexStoreStqFabric): Unit = {
    dut.io.reserve.valid.poke(false.B)
    dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
    for (lane <- 0 until 2) {
      dut.io.storeAddress(lane).valid.poke(false.B)
      dut.io.storeAddress(lane).bits.poke(
        0.U.asTypeOf(dut.io.storeAddress(lane).bits))
      dut.io.storeData(lane).valid.poke(false.B)
      dut.io.storeData(lane).bits.poke(
        0.U.asTypeOf(dut.io.storeData(lane).bits))
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
    dut.io.loadCancel.foreach(_.poke(0.U.asTypeOf(
      dut.io.loadCancel.head)))
    dut.io.loadForwardQuery.foreach { query =>
      query.valid.poke(false.B)
      query.bits.poke(0.U.asTypeOf(query.bits))
    }
    dut.io.loadForwardResponse.foreach(_.ready.poke(true.B))
    dut.io.lateStaPermit.poke(true.B)
    dut.io.markCommitValid.poke(false.B)
    dut.io.markCommitIndex.poke(0.U)
    dut.io.commitFreeMaskValid.poke(false.B)
    dut.io.commitFreeMask.poke(0.U)
  }

  test("retains an address fill until its late STA side effect is permitted") {
    simulate(new OooIexStoreStqFabric(core, stqEntries = 4)) { dut =>
      defaults(dut)
      reserve(dut, 2, 2, BigInt("100000041", 16),
        BigInt("200000041", 16))

      dut.io.lateStaPermit.poke(false.B)
      pokeExecute(dut.io.storeAddress(0).bits, addressHalf = true,
        2, 2, BigInt("100000041", 16), BigInt("200000041", 16))
      dut.io.storeAddress(0).valid.poke(true.B)
      dut.io.storeAddress(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.storeAddress(0).valid.poke(false.B)

      dut.io.lateStaCandidate.valid.expect(true.B)
      dut.io.lateStaCandidate.bits.addr.expect(0x1210.U)
      dut.io.lateStaProbe.valid.expect(false.B)
      dut.clock.step(2)
      dut.io.lateStaCandidate.valid.expect(true.B)
      dut.io.addrReadyMask.expect(0.U)

      dut.io.lateStaPermit.poke(true.B)
      dut.io.lateStaProbe.valid.expect(true.B)
      dut.clock.step()
      dut.io.addrReadyMask.expect(1.U)
    }
  }

  private def pokeLoadIdentity(
      query: linxcore.lsu.STQLoadForwardQuery,
      token: Int,
      pipeIndex: Int): Unit = {
    query.loadId.valid.poke(true.B)
    query.loadId.slot.poke((token & 3).U)
    query.loadId.generation.poke((token & 1).U)
    query.attempt.valid.poke(true.B)
    query.attempt.producer.valid.poke(true.B)
    query.attempt.producer.nativeBidValid.poke(true.B)
    query.attempt.producer.stid.poke(1.U)
    query.attempt.generation.poke(token.U)
    query.returnPipeIndex.poke(pipeIndex.U)
  }

  private def reserve(
      dut: OooIexStoreStqFabric,
      memberIndex: Int,
      ridSlot: Int,
      firstLsid: BigInt,
      firstStoreId: BigInt,
      requestCount: Int = 1,
      stid: Int = 1): Unit = {
    pokeStoreRow(dut.io.reserve.bits, memberIndex, ridSlot,
      firstLsid, firstStoreId, requestCount, stid)
    dut.io.reserve.valid.poke(true.B)
    dut.io.reserve.ready.expect(true.B)
    dut.io.reserveAccepted.expect(true.B)
    dut.clock.step()
    dut.io.reserve.valid.poke(false.B)
  }

  private def pokeIdentity(
      identity: RobIdentity,
      memberIndex: Int,
      ridSlot: Int,
      stid: Int = 1): Unit = {
    identity.poke(0.U.asTypeOf(identity))
    identity.peId.poke(1.U)
    identity.stid.poke(stid.U)
    identity.ridSlot.poke(ridSlot.U)
    identity.ridGeneration.poke(7.U)
    identity.memberIndex.poke(memberIndex.U)
    identity.residentGeneration.poke(9.U)
    identity.bid.poke(0x93.U)
    identity.brobGeneration.poke(8.U)
  }

  private def pokeRecoveryPlan(
      plan: RecoveryPlan,
      phase: RecoveryPhase.Type,
      transactionId: Int,
      memberIndex: Int,
      ridSlot: Int,
      stid: Int = 1,
      lastMemberIndex: Int = -1): Unit = {
    plan.poke(0.U.asTypeOf(plan))
    plan.transactionId.poke(transactionId.U)
    plan.phase.poke(phase)
    plan.cause.poke(RecoveryCause.Branch)
    pokeIdentity(plan.trigger, memberIndex, ridSlot, stid)
    plan.firstKilledValid.poke(true.B)
    pokeIdentity(plan.firstKilled, memberIndex, ridSlot, stid)
    val finalMember = if (lastMemberIndex < 0) memberIndex else lastMemberIndex
    pokeIdentity(plan.lastKilled, finalMember, ridSlot, stid)
    plan.killedGroupCount.poke(1.U)
    plan.killedMemberCount.poke((finalMember - memberIndex + 1).U)
  }

  test("two logical stores keep independent leases across crossed STA STD lanes") {
    simulate(new OooIexStoreStqFabric(core, stqEntries = 4)) { dut =>
      defaults(dut)
      reserve(dut, 2, 2, BigInt("100000001", 16),
        BigInt("200000001", 16))
      reserve(dut, 4, 3, BigInt("100000002", 16),
        BigInt("200000002", 16))
      dut.io.residentCount.expect(2.U)

      pokeExecute(dut.io.storeAddress(0).bits, addressHalf = true,
        2, 2, BigInt("100000001", 16), BigInt("200000001", 16))
      pokeExecute(dut.io.storeData(1).bits, addressHalf = false,
        2, 2, BigInt("100000001", 16), BigInt("200000001", 16))
      dut.io.storeAddress(0).valid.poke(true.B)
      dut.io.storeData(1).valid.poke(true.B)
      dut.io.storeAddress(0).ready.expect(true.B)
      dut.io.storeData(1).ready.expect(true.B)
      dut.clock.step()
      dut.io.storeAddress(0).valid.poke(false.B)
      dut.io.storeData(1).valid.poke(false.B)
      dut.io.lateStaProbe.valid.expect(true.B)
      dut.io.lateStaProbe.bits.valid.expect(true.B)
      dut.io.lateStaProbe.bits.addrOnly.expect(true.B)
      dut.io.lateStaProbe.bits.isTile.expect(false.B)
      dut.io.lateStaProbe.bits.peId.expect(1.U)
      dut.io.lateStaProbe.bits.stid.expect(1.U)
      dut.io.lateStaProbe.bits.tid.expect(1.U)
      dut.io.lateStaProbe.bits.bid.valid.expect(true.B)
      dut.io.lateStaProbe.bits.bid.value.expect(3.U)
      dut.io.lateStaProbe.bits.bid.wrap.expect(false.B)
      dut.io.lateStaProbe.bits.gid.valid.expect(true.B)
      dut.io.lateStaProbe.bits.gid.value.expect(2.U)
      dut.io.lateStaProbe.bits.gid.wrap.expect(true.B)
      dut.io.lateStaProbe.bits.rid.valid.expect(true.B)
      dut.io.lateStaProbe.bits.rid.value.expect(2.U)
      dut.io.lateStaProbe.bits.rid.wrap.expect(true.B)
      dut.io.lateStaProbe.bits.lsId.valid.expect(true.B)
      dut.io.lateStaProbe.bits.lsId.value.expect(1.U)
      dut.io.lateStaProbe.bits.lsId.wrap.expect(false.B)
      dut.io.lateStaProbe.bits.lsIdFullValid.expect(true.B)
      dut.io.lateStaProbe.bits.lsIdFull.expect(
        BigInt("100000001", 16).U)
      dut.io.lateStaProbe.bits.pc.expect(0.U)
      dut.io.lateStaProbe.bits.addr.expect(0x1210.U)
      dut.io.lateStaProbe.bits.size.expect(8.U)
      dut.clock.step(3)
      dut.io.addrReadyMask.expect(1.U)
      dut.io.dataReadyMask.expect(1.U)
      dut.io.rows(0).addr.expect(0x1210.U)
      dut.io.rows(0).data.expect(
        (BigInt("1122334455667788", 16) + 2).U)

      val loadQuery = dut.io.loadForwardQuery(0)
      loadQuery.bits.poke(0.U.asTypeOf(loadQuery.bits))
      loadQuery.bits.token.poke(0x55.U)
      pokeLoadIdentity(loadQuery.bits, 0x55, pipeIndex = 0)
      loadQuery.bits.stid.poke(1.U)
      loadQuery.bits.loadBid.valid.poke(true.B)
      loadQuery.bits.loadBid.wrap.poke(false.B)
      loadQuery.bits.loadBid.value.poke(3.U)
      loadQuery.bits.loadLsIdFullValid.poke(true.B)
      loadQuery.bits.loadLsIdFull.poke(BigInt("100000002", 16).U)
      loadQuery.bits.address.poke(0x1210.U)
      loadQuery.bits.size.poke(8.U)
      loadQuery.valid.poke(true.B)
      loadQuery.ready.expect(true.B)
      dut.clock.step()
      loadQuery.valid.poke(false.B)
      dut.clock.step()
      dut.io.loadForwardResponse(0).valid.expect(true.B)
      dut.io.loadForwardResponse(0).bits.query.token.expect(0x55.U)
      dut.io.loadForwardResponse(0).bits.bypassComplete.expect(true.B)
      dut.io.loadForwardResponse(0).bits.unknownOlderMask.expect(0.U)
      dut.io.loadForwardResponse(0).bits.forwardMask.expect(
        (BigInt(0xff) << 16).U)
      dut.io.loadForwardResponse(0).bits.blocked.expect(false.B)

      pokeExecute(dut.io.storeData(0).bits, addressHalf = false,
        4, 3, BigInt("100000002", 16), BigInt("200000002", 16))
      pokeExecute(dut.io.storeAddress(1).bits, addressHalf = true,
        4, 3, BigInt("100000002", 16), BigInt("200000002", 16))
      dut.io.storeData(0).valid.poke(true.B)
      dut.io.storeAddress(1).valid.poke(true.B)
      dut.io.storeData(0).ready.expect(true.B)
      dut.io.storeAddress(1).ready.expect(true.B)
      dut.clock.step()
      dut.io.storeData(0).valid.poke(false.B)
      dut.io.storeAddress(1).valid.poke(false.B)
      dut.clock.step(3)
      dut.io.addrReadyMask.expect(3.U)
      dut.io.dataReadyMask.expect(3.U)
      dut.io.rows(1).addr.expect(0x1310.U)
    }
  }

  test("both STD lanes write independent physical data-bank ports in one cycle") {
    simulate(new OooIexStoreStqFabric(core, stqEntries = 4)) { dut =>
      defaults(dut)
      reserve(dut, 2, 2, BigInt("100000011", 16),
        BigInt("200000011", 16))
      reserve(dut, 4, 3, BigInt("100000012", 16),
        BigInt("200000012", 16))

      pokeExecute(dut.io.storeData(0).bits, addressHalf = false,
        2, 2, BigInt("100000011", 16), BigInt("200000011", 16))
      pokeExecute(dut.io.storeData(1).bits, addressHalf = false,
        4, 3, BigInt("100000012", 16), BigInt("200000012", 16))
      dut.io.storeData(0).valid.poke(true.B)
      dut.io.storeData(1).valid.poke(true.B)
      dut.io.storeData(0).ready.expect(true.B)
      dut.io.storeData(1).ready.expect(true.B)
      dut.clock.step()
      dut.io.storeData(0).valid.poke(false.B)
      dut.io.storeData(1).valid.poke(false.B)

      dut.clock.step(4)
      dut.io.dataReadyMask.expect(3.U)
      dut.io.rows(0).data.expect(
        (BigInt("1122334455667788", 16) + 2).U)
      dut.io.rows(1).data.expect(
        (BigInt("1122334455667788", 16) + 3).U)
    }
  }

  test("unreserved execution is retained upstream and cannot allocate by CAM") {
    simulate(new OooIexStoreStqFabric(core, stqEntries = 4)) { dut =>
      defaults(dut)
      pokeExecute(dut.io.storeAddress(0).bits, addressHalf = true,
        6, 4, BigInt("100000003", 16), BigInt("200000003", 16))
      dut.io.storeAddress(0).valid.poke(true.B)
      dut.io.storeAddress(0).ready.expect(false.B)
      dut.io.leaseLookupRejected(0).expect(true.B)
      dut.io.lateStaProbe.valid.expect(false.B)
      dut.clock.step(2)
      dut.io.residentCount.expect(0.U)
      dut.io.addrReadyMask.expect(0.U)
      dut.io.lateStaProbe.valid.expect(false.B)
    }
  }

  test("STD child one may share ROB member zero but mismatched child or member is rejected") {
    simulate(new OooIexStoreStqFabric(core, stqEntries = 4)) { dut =>
      defaults(dut)
      val firstLsid = BigInt("100000020", 16)
      val firstStoreId = BigInt("200000020", 16)
      reserve(dut, memberIndex = 0, ridSlot = 5,
        firstLsid = firstLsid, firstStoreId = firstStoreId)

      val data = dut.io.storeData(0)
      pokeExecute(data.bits, addressHalf = false,
        memberIndex = 0, ridSlot = 5,
        firstLsid = firstLsid, firstStoreId = firstStoreId)
      data.bits.i2.row.schedule.childIndex.poke(0.U)
      data.valid.poke(true.B)
      data.ready.expect(false.B)
      dut.io.leaseLookupRejected(2).expect(true.B)

      data.bits.i2.row.schedule.childIndex.poke(1.U)
      data.bits.i2.row.schedule.member.memberIndex.poke(1.U)
      data.ready.expect(false.B)
      dut.io.leaseLookupRejected(2).expect(true.B)

      data.bits.i2.row.schedule.member.memberIndex.poke(0.U)
      data.ready.expect(true.B)
      dut.io.leaseLookupRejected(2).expect(false.B)
      dut.clock.step()
      data.valid.poke(false.B)
      dut.clock.step(4)
      dut.io.dataReadyMask.expect(1.U)
    }
  }

  test("one recovery plan cancels retained STD and frees its exact STQ lease") {
    simulate(new OooIexStoreStqFabric(core, stqEntries = 4)) { dut =>
      defaults(dut)
      reserve(dut, 3, 6, BigInt("100000010", 16),
        BigInt("200000010", 16), requestCount = 2)
      pokeExecute(dut.io.storeData(0).bits, addressHalf = false,
        3, 6, BigInt("100000010", 16), BigInt("200000010", 16),
        requestCount = 2)
      dut.io.storeData(0).valid.poke(true.B)
      dut.io.storeData(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.storeData(0).valid.poke(false.B)
      dut.io.storePipelinesOccupied.expect(1.U)

      pokeRecoveryPlan(dut.io.recovery.prepare.bits,
        RecoveryPhase.Prepare, transactionId = 91,
        memberIndex = 3, ridSlot = 6)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.io.recovery.prepare.ready.expect(true.B)
      dut.io.lateStaProbe.valid.expect(false.B)
      dut.io.recoveryFreeMask.expect(3.U)
      dut.io.recoveryApplied.expect(false.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.residentCount.expect(2.U)
      dut.io.storePipelinesOccupied.expect(1.U)
      dut.io.recovery.prepared.valid.expect(true.B)
      dut.clock.step()
      pokeRecoveryPlan(dut.io.recovery.apply.bits,
        RecoveryPhase.Apply, transactionId = 91,
        memberIndex = 3, ridSlot = 6)
      dut.io.recovery.apply.valid.poke(true.B)
      dut.io.recoveryApplied.expect(true.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.residentCount.expect(0.U)
      dut.io.storePipelinesOccupied.expect(0.U)
      dut.clock.step(2)
      dut.io.dataReadyMask.expect(0.U)
    }
  }

  test("recovery Prepare fences its STID and matching Abort preserves rows") {
    simulate(new OooIexStoreStqFabric(core, stqEntries = 4)) { dut =>
      defaults(dut)
      reserve(dut, 0, 5, BigInt("100000020", 16),
        BigInt("200000020", 16))

      pokeRecoveryPlan(dut.io.recovery.prepare.bits,
        RecoveryPhase.Prepare, transactionId = 92,
        memberIndex = 0, ridSlot = 5)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.io.recovery.prepare.ready.expect(true.B)
      dut.io.lateStaProbe.valid.expect(false.B)
      dut.io.recoveryRejected.expect(false.B)
      dut.io.recoveryFreeMask.expect(1.U)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)

      pokeStoreRow(dut.io.reserve.bits, 2, 6,
        BigInt("100000021", 16), BigInt("200000021", 16))
      dut.io.reserve.valid.poke(true.B)
      dut.io.reserve.ready.expect(false.B)
      dut.io.markCommitValid.poke(true.B)
      dut.io.markCommitIndex.poke(0.U)
      dut.io.markCommitAccepted.expect(false.B)
      dut.io.commitFreeMaskValid.poke(true.B)
      dut.io.commitFreeMask.poke(1.U)
      dut.io.commitFreeAcceptedMask.expect(0.U)
      dut.clock.step()
      dut.io.residentCount.expect(1.U)

      dut.io.reserve.valid.poke(false.B)
      dut.io.markCommitValid.poke(false.B)
      dut.io.commitFreeMaskValid.poke(false.B)
      pokeRecoveryPlan(dut.io.recovery.abort.bits,
        RecoveryPhase.Abort, transactionId = 92,
        memberIndex = 0, ridSlot = 5)
      dut.io.recovery.abort.valid.poke(true.B)
      dut.clock.step()
      dut.io.recovery.abort.valid.poke(false.B)
      dut.io.residentCount.expect(1.U)
      pokeStoreRow(dut.io.reserve.bits, 2, 6,
        BigInt("100000021", 16), BigInt("200000021", 16))
      dut.io.reserve.valid.poke(true.B)
      dut.io.reserve.ready.expect(true.B)
    }
  }

  test("peer STID reservation survives and progresses across target Apply") {
    simulate(new OooIexStoreStqFabric(core, stqEntries = 4)) { dut =>
      defaults(dut)
      reserve(dut, 0, 5, BigInt("100000050", 16),
        BigInt("200000050", 16), stid = 1)

      pokeRecoveryPlan(dut.io.recovery.prepare.bits,
        RecoveryPhase.Prepare, transactionId = 93,
        memberIndex = 0, ridSlot = 5, stid = 1)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.io.recovery.prepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)

      reserve(dut, 1, 2, BigInt("100000051", 16),
        BigInt("200000051", 16), stid = 0)
      dut.io.residentCount.expect(2.U)

      pokeRecoveryPlan(dut.io.recovery.apply.bits,
        RecoveryPhase.Apply, transactionId = 93,
        memberIndex = 0, ridSlot = 5, stid = 1)
      dut.io.recovery.apply.valid.poke(true.B)
      dut.io.recoveryApplied.expect(true.B)
      dut.clock.step()
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.residentCount.expect(1.U)
      dut.io.rows(1).valid.expect(true.B)
      dut.io.rows(1).stid.expect(0.U)
    }
  }

  test("cross-line stores block only overlapping load queries") {
    simulate(new OooIexStoreStqFabric(core, stqEntries = 4)) { dut =>
      defaults(dut)
      val storeLsid = BigInt("100000030", 16)
      reserve(dut, 2, 2, storeLsid, BigInt("200000030", 16))

      pokeExecute(dut.io.storeAddress(0).bits, addressHalf = true,
        2, 2, storeLsid, BigInt("200000030", 16))
      dut.io.storeAddress(0).bits.i2.sourceData(0).poke(0x122c.U)
      dut.io.storeAddress(0).valid.poke(true.B)
      dut.io.storeAddress(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.storeAddress(0).valid.poke(false.B)
      dut.io.lateStaProbe.valid.expect(true.B)
      dut.clock.step(3)
      dut.io.rows(0).addr.expect(0x123c.U)
      dut.io.addrReadyMask.expect(1.U)

      val query = dut.io.loadForwardQuery(0)
      query.bits.poke(0.U.asTypeOf(query.bits))
      query.bits.token.poke(0x61.U)
      pokeLoadIdentity(query.bits, 0x61, pipeIndex = 0)
      query.bits.stid.poke(1.U)
      query.bits.loadBid.valid.poke(true.B)
      query.bits.loadBid.value.poke(4.U)
      query.bits.loadLsIdFullValid.poke(true.B)
      query.bits.loadLsIdFull.poke((storeLsid + 1).U)
      query.bits.address.poke(0x123e.U)
      query.bits.size.poke(2.U)
      query.valid.poke(true.B)
      query.ready.expect(true.B)
      dut.clock.step()
      query.valid.poke(false.B)
      dut.clock.step()
      dut.io.loadForwardResponse(0).valid.expect(true.B)
      dut.io.loadForwardResponse(0).bits.crossLineStoreMask.expect(1.U)
      dut.io.loadForwardResponse(0).bits.blocked.expect(true.B)

      query.bits.token.poke(0x62.U)
      pokeLoadIdentity(query.bits, 0x62, pipeIndex = 0)
      query.bits.address.poke(0x1200.U)
      query.bits.size.poke(8.U)
      query.valid.poke(true.B)
      query.ready.expect(true.B)
      dut.clock.step()
      query.valid.poke(false.B)
      dut.clock.step()
      dut.io.loadForwardResponse(0).valid.expect(true.B)
      dut.io.loadForwardResponse(0).bits.query.token.expect(0x62.U)
      dut.io.loadForwardResponse(0).bits.crossLineStoreMask.expect(0.U)
      dut.io.loadForwardResponse(0).bits.blocked.expect(false.B)
    }
  }
}
