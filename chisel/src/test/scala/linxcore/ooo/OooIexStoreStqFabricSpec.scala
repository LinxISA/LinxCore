package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
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
    tuRetireSourceDepthPerStid = 32,
    lsidWidth = 40)

  private def pokeMember(
      member: RobMemberKey,
      memberIndex: Int,
      ridSlot: Int): Unit = {
    member.poke(0.U.asTypeOf(member))
    member.group.valid.poke(true.B)
    member.group.peId.poke(1.U)
    member.group.stid.poke(1.U)
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
      requestCount: Int = 1): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.schedule.valid.poke(true.B)
    row.schedule.peId.poke(1.U)
    row.schedule.stid.poke(1.U)
    row.schedule.childIndex.poke(0.U)
    pokeMember(row.schedule.member, memberIndex, ridSlot)
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
      memberIndex = memberIndex + (if (addressHalf) 0 else 1),
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
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
    dut.io.loadCancel.foreach(_.poke(0.U.asTypeOf(
      dut.io.loadCancel.head)))
    dut.io.markCommitValid.poke(false.B)
    dut.io.markCommitIndex.poke(0.U)
    dut.io.commitFreeMaskValid.poke(false.B)
    dut.io.commitFreeMask.poke(0.U)
  }

  private def reserve(
      dut: OooIexStoreStqFabric,
      memberIndex: Int,
      ridSlot: Int,
      firstLsid: BigInt,
      firstStoreId: BigInt,
      requestCount: Int = 1): Unit = {
    pokeStoreRow(dut.io.reserve.bits, memberIndex, ridSlot,
      firstLsid, firstStoreId, requestCount)
    dut.io.reserve.valid.poke(true.B)
    dut.io.reserve.ready.expect(true.B)
    dut.io.reserveAccepted.expect(true.B)
    dut.clock.step()
    dut.io.reserve.valid.poke(false.B)
  }

  test("two logical stores keep independent leases across crossed STA STD lanes") {
    simulate(new OooIexStoreStqFabric(p, stqEntries = 4)) { dut =>
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
      dut.clock.step(3)
      dut.io.addrReadyMask.expect(1.U)
      dut.io.dataReadyMask.expect(1.U)
      dut.io.rows(0).addr.expect(0x1210.U)
      dut.io.rows(0).data.expect(
        (BigInt("1122334455667788", 16) + 2).U)

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
    simulate(new OooIexStoreStqFabric(p, stqEntries = 4)) { dut =>
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
    simulate(new OooIexStoreStqFabric(p, stqEntries = 4)) { dut =>
      defaults(dut)
      pokeExecute(dut.io.storeAddress(0).bits, addressHalf = true,
        6, 4, BigInt("100000003", 16), BigInt("200000003", 16))
      dut.io.storeAddress(0).valid.poke(true.B)
      dut.io.storeAddress(0).ready.expect(false.B)
      dut.io.leaseLookupRejected(0).expect(true.B)
      dut.clock.step(2)
      dut.io.residentCount.expect(0.U)
      dut.io.addrReadyMask.expect(0.U)
    }
  }

  test("one recovery plan cancels retained STD and frees its exact STQ lease") {
    simulate(new OooIexStoreStqFabric(p, stqEntries = 4)) { dut =>
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

      val plan = dut.io.recoveryPrepare.bits
      plan.poke(0.U.asTypeOf(plan))
      plan.valid.poke(true.B)
      plan.oldHead.valid.poke(true.B)
      plan.oldHead.peId.poke(1.U)
      plan.oldHead.stid.poke(1.U)
      plan.oldHead.ridSlot.poke(0.U)
      plan.oldHead.ridGeneration.poke(7.U)
      plan.oldOccupied.poke(8.U)
      plan.newOccupied.poke(2.U)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryFreeMask.expect(3.U)
      dut.io.recoveryApplied.expect(false.B)
      dut.clock.step()
      dut.io.residentCount.expect(2.U)
      dut.io.storePipelinesOccupied.expect(1.U)
      dut.io.recoveryFire.poke(true.B)
      dut.io.recoveryApplied.expect(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.residentCount.expect(0.U)
      dut.io.storePipelinesOccupied.expect(0.U)
      dut.clock.step(2)
      dut.io.dataReadyMask.expect(0.U)
    }
  }

  test("recovery prepare fences mutation and rejects a split-store partial cut") {
    simulate(new OooIexStoreStqFabric(p, stqEntries = 4)) { dut =>
      defaults(dut)
      reserve(dut, 0, 5, BigInt("100000020", 16),
        BigInt("200000020", 16))

      val plan = dut.io.recoveryPrepare.bits
      plan.poke(0.U.asTypeOf(plan))
      plan.valid.poke(true.B)
      plan.oldHead.valid.poke(true.B)
      plan.oldHead.peId.poke(1.U)
      plan.oldHead.stid.poke(1.U)
      plan.oldHead.ridSlot.poke(0.U)
      plan.oldHead.ridGeneration.poke(7.U)
      plan.oldOccupied.poke(8.U)
      plan.newOccupied.poke(1.U)
      plan.pivotOffset.poke(0.U)
      pokeMember(plan.pivot, memberIndex = 0, ridSlot = 5)
      plan.pivotPhysicalMemberCount.poke(2.U)
      plan.survivingPivotValid.poke(true.B)
      plan.survivingPivotPhysicalMemberCount.poke(1.U)
      dut.io.recoveryPrepare.valid.poke(true.B)

      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryPartialStoreCut.expect(true.B)
      dut.io.recoveryRejected.expect(true.B)
      dut.io.recoveryFreeMask.expect(0.U)

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
      dut.clock.step(2)
      dut.io.residentCount.expect(1.U)

      dut.io.reserve.valid.poke(false.B)
      dut.io.markCommitValid.poke(false.B)
      dut.io.commitFreeMaskValid.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
    }
  }
}
