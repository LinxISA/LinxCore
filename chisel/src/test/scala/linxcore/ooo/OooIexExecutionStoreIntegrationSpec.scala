package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, Valid}
import linxcore.lsu.STQEntryBankRow
import org.scalatest.funsuite.AnyFunSuite

class OooIexExecutionStoreHarnessIO(
    val p: OooParams,
    val stqEntries: Int) extends Bundle {
  val reserve = Flipped(Decoupled(new OooIexIssueRow(p)))
  val e1 = Flipped(Vec(OooIexLinxPhysicalProfile.ExecutionLaneCount,
    Decoupled(new OooIexExecuteTransaction(p))))
  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryFire = Input(Bool())
  val rows = Output(Vec(stqEntries, new STQEntryBankRow(
    p.robGroupsPerStid,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.stidWidth,
    mapQDepth = p.tuMapQDepthPerStid,
    lsidWidth = p.lsidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    leaseGenerationWidth = p.executeSlotGenerationWidth)))
  val addressReadyMask = Output(UInt(stqEntries.W))
  val dataReadyMask = Output(UInt(stqEntries.W))
  val routeRejected = Output(Vec(
    OooIexLinxPhysicalProfile.ExecutionLaneCount, Bool()))
}

class OooIexExecutionStoreHarness(
    val profile: OooIexPhysicalProfile,
    val stqEntries: Int) extends Module {
  val p = profile.params
  val io = IO(new OooIexExecutionStoreHarnessIO(p, stqEntries))

  val execute = Module(new OooIexExecutionCluster(profile))
  val store = Module(new OooIexStoreStqFabric(p, stqEntries))

  for (lane <- 0 until OooIexLinxPhysicalProfile.ExecutionLaneCount) {
    execute.io.e1(lane).valid := io.e1(lane).valid
    execute.io.e1(lane).bits := io.e1(lane).bits
    io.e1(lane).ready := execute.io.e1(lane).ready
    io.routeRejected(lane) := execute.io.routeRejected(lane).valid
  }
  store.io.reserve <> io.reserve
  store.io.storeAddress <> execute.io.storeAddress
  store.io.storeData <> execute.io.storeData
  store.io.loadCancel := execute.io.loadCancel

  store.io.recoveryPrepare := io.recoveryPrepare
  io.recoveryPrepareReady := store.io.recoveryPrepareReady
  val recoveryApply = io.recoveryFire && store.io.recoveryPrepareReady
  store.io.recoveryFire := recoveryApply
  execute.io.recoveryApply.valid := recoveryApply
  execute.io.recoveryApply.bits := io.recoveryPrepare.bits

  execute.io.multiCycleAlu.foreach(_.ready := true.B)
  execute.io.system.foreach(_.ready := true.B)
  execute.io.pointerAuth.foreach(_.ready := true.B)
  execute.io.floatingVector.ready := true.B
  execute.io.engineCommand.ready := true.B
  execute.io.memoryRequest.foreach(_.ready := true.B)
  execute.io.memoryResponse.foreach { response =>
    response.valid := false.B
    response.bits := 0.U.asTypeOf(response.bits)
  }
  execute.io.pWrite.foreach(_.ready := true.B)
  execute.io.tWrite.foreach(_.ready := true.B)
  execute.io.uWrite.foreach(_.ready := true.B)
  execute.io.bctrl.foreach(_.ready := true.B)
  execute.io.trace.foreach(_.ready := true.B)
  execute.io.completion.foreach(_.ready := true.B)

  store.io.markCommitValid := false.B
  store.io.markCommitIndex := 0.U
  store.io.commitFreeMaskValid := false.B
  store.io.commitFreeMask := 0.U
  io.rows := store.io.rows
  io.addressReadyMask := store.io.addrReadyMask
  io.dataReadyMask := store.io.dataReadyMask
}

class OooIexExecutionStoreIntegrationSpec
    extends AnyFunSuite with ChiselSim {
  private val base = OooParams(
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
    iqBankCount = 8,
    iqEntriesPerBank = 2,
    iqWritePortsPerBank = 2,
    iqFreeSelectLeafEntries = 1,
    pMapQDepthPerStid = 4,
    tuMapQDepthPerStid = 4,
    tuRetireSourceDepthPerStid = 16,
    lsidWidth = 40)
  private val profile = OooIexLinxPhysicalProfile(base)
  private val p = profile.params

  private def pokeMember(
      member: RobMemberKey,
      memberIndex: Int,
      ridSlot: Int): Unit = {
    member.group.valid.poke(true.B)
    member.group.peId.poke(1.U)
    member.group.stid.poke(1.U)
    member.group.ridSlot.poke(ridSlot.U)
    member.group.ridGeneration.poke(3.U)
    member.bid.valid.poke(true.B)
    member.bid.value.poke(0x81.U)
    member.brobGeneration.poke(4.U)
    member.memberIndex.poke(memberIndex.U)
    member.residentGeneration.poke(5.U)
  }

  private def pokeStoreRow(
      row: OooIexIssueRow,
      memberIndex: Int,
      ridSlot: Int,
      firstLsid: BigInt,
      firstStoreId: BigInt): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.schedule.valid.poke(true.B)
    row.schedule.peId.poke(1.U)
    row.schedule.stid.poke(1.U)
    pokeMember(row.schedule.member, memberIndex, ridSlot)
    row.schedule.reservation.valid.poke(true.B)
    row.schedule.reservation.uopClass.poke(OooUopClass.Agu)
    row.payload.recipe.valid.poke(true.B)
    row.payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    row.payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
    row.payload.recipe.recipeKind.poke(OooOpcodeRecipeKind.ScalarStore.U)
    row.payload.recipe.lateSplitKind.poke(
      OooLateSplitKind.StoreAddressData.U)
    row.payload.memory.valid.poke(true.B)
    row.payload.memory.isStore.poke(true.B)
    row.payload.memory.addressMode.poke(OooMemoryAddressMode.BaseOffset)
    row.payload.memory.accessBytes.poke(8.U)
    row.payload.memory.offset.poke(16.U)
    row.payload.memory.addressSourceMask.poke(1.U)
    row.payload.memory.dataSourceMask.poke(4.U)
    row.payload.memoryOrder.valid.poke(true.B)
    row.payload.memoryOrder.memoryValid.poke(true.B)
    row.payload.memoryOrder.isStore.poke(true.B)
    row.payload.memoryOrder.requestCount.poke(1.U)
    row.payload.memoryOrder.firstLsid.poke(firstLsid.U)
    row.payload.memoryOrder.firstTypeId.poke(firstStoreId.U)
  }

  private def pokeStoreExecute(
      execute: OooIexExecuteTransaction,
      lane: Int,
      addressHalf: Boolean,
      ridSlot: Int,
      firstLsid: BigInt,
      firstStoreId: BigInt): Unit = {
    execute.poke(0.U.asTypeOf(execute))
    val ownerClass = if (addressHalf) OooUopClass.Agu else OooUopClass.Std
    val dispatchClass = if (addressHalf) OooDispatchClass.Agu
      else OooDispatchClass.Std
    val capability = if (addressHalf) OooIexDomainCapability.StoreAddress
      else OooIexDomainCapability.StoreData
    execute.ownerClass.poke(ownerClass)
    execute.ownerLane.poke(lane.U)
    execute.slotGeneration.poke(2.U)
    pokeStoreRow(execute.i2.row,
      memberIndex = if (addressHalf) 0 else 1,
      ridSlot = ridSlot,
      firstLsid = firstLsid,
      firstStoreId = firstStoreId)
    execute.i2.row.schedule.childIndex.poke(
      (if (addressHalf) 0 else 1).U)
    execute.i2.row.schedule.reservation.uopClass.poke(ownerClass)
    execute.i2.row.payload.recipe.dispatchClass.poke(dispatchClass.U)
    execute.i2.row.payload.recipe.dispatchCapabilities(dispatchClass - 1)
      .poke(OooIexDomainCapability.mask(capability).U)
    execute.i2.sourceMask.poke((if (addressHalf) 1 else 4).U)
    execute.i2.sourceData(0).poke(0x1000.U)
    execute.i2.sourceData(2).poke(
      BigInt("1122334455667788", 16).U)
  }

  test("recovery join requires both owners and emits one common fire") {
    simulate(new OooIexExecutionStoreRecoveryJoin(p)) { dut =>
      dut.io.requested.poke(0.U.asTypeOf(dut.io.requested))
      dut.io.executionReady.poke(false.B)
      dut.io.executionPrepared.poke(
        0.U.asTypeOf(dut.io.executionPrepared))
      dut.io.executionRejected.poke(
        0.U.asTypeOf(dut.io.executionRejected))
      dut.io.storeReady.poke(false.B)
      dut.io.storeRejected.poke(false.B)
      dut.io.fire.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.requested.valid.poke(true.B)
      dut.io.requested.bits.valid.poke(true.B)
      dut.io.requested.bits.oldHead.stid.poke(1.U)
      dut.io.executionReady.poke(true.B)
      dut.io.executionPrepared.valid.poke(true.B)
      dut.io.executionPrepared.stid.poke(1.U)
      dut.io.prepareReady.expect(false.B)
      dut.io.commonFire.expect(false.B)

      dut.io.storeReady.poke(true.B)
      dut.io.prepareReady.expect(true.B)
      dut.io.prepared.valid.expect(true.B)
      dut.io.prepared.stid.expect(1.U)
      dut.io.fire.poke(true.B)
      dut.io.commonFire.expect(true.B)

      dut.io.storeReady.poke(false.B)
      dut.io.storeRejected.poke(true.B)
      dut.io.prepareReady.expect(false.B)
      dut.io.commonFire.expect(false.B)
      dut.io.rejected.valid.expect(true.B)
      dut.io.rejected.bits.residentRowsExact.expect(false.B)
      dut.io.rejected.bits.s1RowsExact.expect(true.B)

      dut.io.executionRejected.valid.poke(true.B)
      dut.io.executionRejected.bits.residentRowsExact.poke(true.B)
      dut.io.executionRejected.bits.s1RowsExact.poke(false.B)
      dut.io.rejected.bits.residentRowsExact.expect(true.B)
      dut.io.rejected.bits.s1RowsExact.expect(false.B)
    }
  }

  test("formal STA and STD lanes fill one pre-reserved canonical STQ row") {
    simulate(new OooIexExecutionStoreHarness(profile, stqEntries = 4)) {
      dut =>
        dut.io.reserve.valid.poke(false.B)
        dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
        dut.io.e1.foreach { lane =>
          lane.valid.poke(false.B)
          lane.bits.poke(0.U.asTypeOf(lane.bits))
        }
        dut.io.recoveryPrepare.valid.poke(false.B)
        dut.io.recoveryPrepare.bits.poke(
          0.U.asTypeOf(dut.io.recoveryPrepare.bits))
        dut.io.recoveryFire.poke(false.B)
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        val firstLsid = BigInt("100000001", 16)
        val firstStoreId = BigInt("200000001", 16)
        pokeStoreRow(dut.io.reserve.bits, 0, 2, firstLsid, firstStoreId)
        dut.io.reserve.valid.poke(true.B)
        dut.io.reserve.ready.expect(true.B)
        dut.clock.step()
        dut.io.reserve.valid.poke(false.B)

        val staLane = profile.pickerIndex("agu0-sta")
        val stdLane = profile.pickerIndex("alu0")
        pokeStoreExecute(dut.io.e1(staLane).bits, staLane,
          addressHalf = true, 2, firstLsid, firstStoreId)
        pokeStoreExecute(dut.io.e1(stdLane).bits, stdLane,
          addressHalf = false, 2, firstLsid, firstStoreId)
        dut.io.e1(staLane).valid.poke(true.B)
        dut.io.e1(stdLane).valid.poke(true.B)
        dut.io.e1(staLane).ready.expect(true.B)
        dut.io.e1(stdLane).ready.expect(true.B)
        dut.io.routeRejected(staLane).expect(false.B)
        dut.io.routeRejected(stdLane).expect(false.B)
        dut.clock.step()
        dut.io.e1(staLane).valid.poke(false.B)
        dut.io.e1(stdLane).valid.poke(false.B)

        dut.clock.step(3)
        dut.io.addressReadyMask.expect(1.U)
        dut.io.dataReadyMask.expect(1.U)
        dut.io.rows(0).addr.expect(0x1010.U)
        dut.io.rows(0).data.expect(
          BigInt("1122334455667788", 16).U)
    }
  }
}
