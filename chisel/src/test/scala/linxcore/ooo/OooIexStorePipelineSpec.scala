package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.log2Ceil
import linxcore.lsu.STQEntryBank
import org.scalatest.funsuite.AnyFunSuite

class OooIexStoreStqHarnessIO(
    val p: OooParams,
    val stqEntries: Int)
    extends Bundle {
  val reserveValid = Input(Bool())
  val reserveRow = Input(new OooIexIssueRow(p))
  val reserveAccepted = Output(Bool())
  val staValid = Input(Bool())
  val sta = Input(new OooIexExecuteTransaction(p))
  val staReady = Output(Bool())
  val staRejected = Output(Bool())
  val stdValid = Input(Bool())
  val std = Input(new OooIexExecuteTransaction(p))
  val stdReady = Output(Bool())
  val stdRejected = Output(Bool())
  val fillPermit = Input(Bool())
  val recoveryValid = Input(Bool())
  val recovery = Input(new OooResidencyRecoveryPlan(p))
  val addrReady = Output(UInt(stqEntries.W))
  val dataReady = Output(UInt(stqEntries.W))
  val addresses = Output(Vec(stqEntries, UInt(p.pcWidth.W)))
  val data = Output(Vec(stqEntries, UInt(p.pcWidth.W)))
  val occupied = Output(Bool())
  val residentCount = Output(UInt(log2Ceil(stqEntries + 1).W))
}

class OooIexStoreStqHarness(
    val p: OooParams,
    val stqEntries: Int = 4)
    extends Module {
  val io = IO(new OooIexStoreStqHarnessIO(p, stqEntries))
  val projection = Module(new OooStqReservationProjection(p, stqEntries))
  val pipeline = Module(new OooIexStorePipeline(p, stqEntries))
  val stq = Module(new STQEntryBank(
    entries = stqEntries,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.stidWidth,
    mapQDepth = p.tuMapQDepthPerStid,
    robEntries = p.robGroupsPerStid,
    lsidWidth = p.lsidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    leaseGenerationWidth = p.executeSlotGenerationWidth,
    maxReserveBeats = p.maxMemoryRequestsPerInstruction))

  projection.io.inputValid := io.reserveValid
  projection.io.input := io.reserveRow
  stq.io.flush := 0.U.asTypeOf(stq.io.flush)
  stq.io.insertValid := false.B
  stq.io.insert := 0.U.asTypeOf(stq.io.insert)
  stq.io.reserveValid := false.B
  stq.io.reserve := 0.U.asTypeOf(stq.io.reserve)
  stq.io.reserveBatchValid := projection.io.reserveValid
  stq.io.reserveBatchMask := projection.io.reserveMask
  stq.io.reserveBatch := projection.io.reserve
  stq.io.fillValid := pipeline.io.fill.valid && io.fillPermit
  stq.io.fill := pipeline.io.fill.bits
  pipeline.io.fill.ready := stq.io.fillReady && io.fillPermit
  stq.io.markCommitValid := false.B
  stq.io.markCommitIndex := 0.U
  stq.io.commitFreeValid := false.B
  stq.io.commitFreeIndex := 0.U
  stq.io.commitFreeMaskValid := false.B
  stq.io.commitFreeMask := 0.U

  val leaseValid = RegInit(false.B)
  val lease = Reg(new OooStqLeaseSet(p, stqEntries))
  when(stq.io.reserveBatchAccepted) {
    leaseValid := true.B
    lease.valid := true.B
    lease.logicalMember := io.reserveRow.member
    lease.logicalMember.memberIndex :=
      io.reserveRow.member.memberIndex - io.reserveRow.childIndex
    lease.requestCount := io.reserveRow.memoryOrder.requestCount
    lease.firstLsid := io.reserveRow.memoryOrder.firstLsid
    lease.firstStoreId := io.reserveRow.memoryOrder.firstTypeId
    lease.leases := stq.io.reserveBatchLeases
  }

  pipeline.io.sta.valid := io.staValid && leaseValid
  pipeline.io.sta.bits.execute := io.sta
  pipeline.io.sta.bits.lease := lease
  pipeline.io.std.valid := io.stdValid && leaseValid
  pipeline.io.std.bits.execute := io.std
  pipeline.io.std.bits.lease := lease
  pipeline.io.recoveryApply.valid := io.recoveryValid
  pipeline.io.recoveryApply.bits := io.recovery
  pipeline.io.loadCancel.foreach(cancel =>
    cancel := 0.U.asTypeOf(cancel))

  io.reserveAccepted := stq.io.reserveBatchAccepted
  io.staReady := leaseValid && pipeline.io.sta.ready
  io.staRejected := leaseValid && pipeline.io.staRejected
  io.stdReady := leaseValid && pipeline.io.std.ready
  io.stdRejected := leaseValid && pipeline.io.stdRejected
  io.addrReady := stq.io.addrReadyMask
  io.dataReady := stq.io.dataReadyMask
  io.occupied := pipeline.io.occupied
  io.residentCount := stq.io.residentCount
  for (index <- 0 until stqEntries) {
    io.addresses(index) := stq.io.rows(index).addr
    io.data(index) := stq.io.rows(index).data
  }
}

class OooIexStorePipelineSpec extends AnyFunSuite with ChiselSim {
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
    tuRetireSourceDepthPerStid = 16,
    lsidWidth = 40)

  private def pokeMember(member: RobMemberKey, memberIndex: Int): Unit = {
    member.poke(0.U.asTypeOf(member))
    member.group.valid.poke(true.B)
    member.group.peId.poke(1.U)
    member.group.stid.poke(1.U)
    member.group.ridSlot.poke(6.U)
    member.group.ridGeneration.poke(7.U)
    member.bid.valid.poke(true.B)
    member.bid.value.poke(0x93.U)
    member.brobGeneration.poke(8.U)
    member.memberIndex.poke(memberIndex.U)
    member.residentGeneration.poke(9.U)
  }

  private def pokeCommonRow(
      row: OooIexIssueRow,
      childIndex: Int,
      memberIndex: Int,
      uopClass: OooUopClass.Type): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.schedule.valid.poke(true.B)
    row.schedule.peId.poke(1.U)
    row.schedule.stid.poke(1.U)
    row.schedule.childIndex.poke(childIndex.U)
    pokeMember(row.schedule.member, memberIndex)
    row.schedule.reservation.valid.poke(true.B)
    row.schedule.reservation.uopClass.poke(uopClass)
    row.payload.recipe.valid.poke(true.B)
    row.payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    row.payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
    row.payload.recipe.recipeKind.poke(OooOpcodeRecipeKind.PairStore.U)
    row.payload.recipe.lateSplitKind.poke(
      OooLateSplitKind.PairStoreAddressData.U)
    row.payload.memory.valid.poke(true.B)
    row.payload.memory.isStore.poke(true.B)
    row.payload.memory.addressMode.poke(OooMemoryAddressMode.BaseOffset)
    row.payload.memory.accessBytes.poke(8.U)
    row.payload.memory.offset.poke(16.U)
    row.payload.memory.addressSourceMask.poke(1.U)
    row.payload.memory.dataSourceMask.poke(12.U)
    row.payload.memoryOrder.valid.poke(true.B)
    row.payload.memoryOrder.memoryValid.poke(true.B)
    row.payload.memoryOrder.isStore.poke(true.B)
    row.payload.memoryOrder.requestCount.poke(2.U)
    row.payload.memoryOrder.firstLsid.poke(BigInt("100000001", 16).U)
    row.payload.memoryOrder.firstTypeId.poke(BigInt("200000001", 16).U)
  }

  private def pokeExecute(
      execute: OooIexExecuteTransaction,
      addressHalf: Boolean): Unit = {
    execute.poke(0.U.asTypeOf(execute))
    execute.ownerClass.poke(
      (if (addressHalf) OooUopClass.Agu else OooUopClass.Std))
    pokeCommonRow(
      execute.i2.row,
      childIndex = if (addressHalf) 0 else 1,
      memberIndex = if (addressHalf) 3 else 4,
      uopClass = if (addressHalf) OooUopClass.Agu else OooUopClass.Std)
    execute.i2.sourceMask.poke((if (addressHalf) 1 else 12).U)
    execute.i2.sourceData(0).poke(0x1000.U)
    execute.i2.sourceData(2).poke(BigInt("1122334455667788", 16).U)
    execute.i2.sourceData(3).poke(BigInt("8877665544332211", 16).U)
  }

  test("retained STA and STD converge pair beats only through canonical STQ") {
    simulate(new OooIexStoreStqHarness(p)) { dut =>
      dut.io.reserveValid.poke(true.B)
      pokeCommonRow(dut.io.reserveRow, childIndex = 0, memberIndex = 3,
        uopClass = OooUopClass.Agu)
      dut.io.staValid.poke(false.B)
      dut.io.sta.poke(0.U.asTypeOf(dut.io.sta))
      dut.io.stdValid.poke(false.B)
      dut.io.std.poke(0.U.asTypeOf(dut.io.std))
      dut.io.fillPermit.poke(true.B)
      dut.io.recoveryValid.poke(false.B)
      dut.io.recovery.poke(0.U.asTypeOf(dut.io.recovery))
      dut.io.reserveAccepted.expect(true.B)
      dut.clock.step()
      dut.io.reserveValid.poke(false.B)
      dut.io.residentCount.expect(2.U)

      pokeExecute(dut.io.sta, addressHalf = true)
      pokeExecute(dut.io.std, addressHalf = false)
      dut.io.staValid.poke(true.B)
      dut.io.stdValid.poke(true.B)
      dut.io.staReady.expect(true.B)
      dut.io.stdReady.expect(true.B)
      dut.clock.step()
      dut.io.staValid.poke(false.B)
      dut.io.stdValid.poke(false.B)

      dut.clock.step(4)
      dut.io.occupied.expect(false.B)
      dut.io.addrReady.expect(3.U)
      dut.io.dataReady.expect(3.U)
      dut.io.addresses(0).expect(0x1010.U)
      dut.io.addresses(1).expect(0x1018.U)
      dut.io.data(0).expect(BigInt("1122334455667788", 16).U)
      dut.io.data(1).expect(BigInt("8877665544332211", 16).U)
    }
  }

  test("STD may finish before STA and retained fill survives STQ backpressure") {
    simulate(new OooIexStoreStqHarness(p)) { dut =>
      dut.io.reserveValid.poke(true.B)
      pokeCommonRow(dut.io.reserveRow, childIndex = 0, memberIndex = 3,
        uopClass = OooUopClass.Agu)
      dut.io.staValid.poke(false.B)
      dut.io.sta.poke(0.U.asTypeOf(dut.io.sta))
      dut.io.stdValid.poke(false.B)
      dut.io.std.poke(0.U.asTypeOf(dut.io.std))
      dut.io.fillPermit.poke(false.B)
      dut.io.recoveryValid.poke(false.B)
      dut.io.recovery.poke(0.U.asTypeOf(dut.io.recovery))
      dut.io.reserveAccepted.expect(true.B)
      dut.clock.step()
      dut.io.reserveValid.poke(false.B)

      pokeExecute(dut.io.std, addressHalf = false)
      dut.io.stdValid.poke(true.B)
      dut.io.stdReady.expect(true.B)
      dut.clock.step()
      dut.io.stdValid.poke(false.B)
      dut.clock.step(3)
      dut.io.occupied.expect(true.B)
      dut.io.addrReady.expect(0.U)
      dut.io.dataReady.expect(0.U)

      dut.io.fillPermit.poke(true.B)
      dut.io.recoveryValid.poke(false.B)
      dut.io.recovery.poke(0.U.asTypeOf(dut.io.recovery))
      dut.clock.step(2)
      dut.io.occupied.expect(false.B)
      dut.io.addrReady.expect(0.U)
      dut.io.dataReady.expect(3.U)

      pokeExecute(dut.io.sta, addressHalf = true)
      dut.io.staValid.poke(true.B)
      dut.io.staReady.expect(true.B)
      dut.clock.step()
      dut.io.staValid.poke(false.B)
      dut.clock.step(2)
      dut.io.addrReady.expect(3.U)
      dut.io.dataReady.expect(3.U)
    }
  }

  test("wrong physical child member cannot mutate a reserved logical store") {
    simulate(new OooIexStoreStqHarness(p)) { dut =>
      dut.io.reserveValid.poke(true.B)
      pokeCommonRow(dut.io.reserveRow, childIndex = 0, memberIndex = 3,
        uopClass = OooUopClass.Agu)
      dut.io.staValid.poke(false.B)
      dut.io.sta.poke(0.U.asTypeOf(dut.io.sta))
      dut.io.stdValid.poke(false.B)
      dut.io.std.poke(0.U.asTypeOf(dut.io.std))
      dut.io.fillPermit.poke(true.B)
      dut.io.recoveryValid.poke(false.B)
      dut.io.recovery.poke(0.U.asTypeOf(dut.io.recovery))
      dut.clock.step()
      dut.io.reserveValid.poke(false.B)

      pokeExecute(dut.io.std, addressHalf = false)
      dut.io.std.i2.row.schedule.member.memberIndex.poke(5.U)
      dut.io.stdValid.poke(true.B)
      dut.io.stdReady.expect(false.B)
      dut.io.stdRejected.expect(true.B)
      dut.clock.step()
      dut.io.stdValid.poke(false.B)
      dut.clock.step()
      dut.io.addrReady.expect(0.U)
      dut.io.dataReady.expect(0.U)
      dut.io.occupied.expect(false.B)
    }
  }

  test("exact recovery kills retained STD before any canonical fill") {
    simulate(new OooIexStoreStqHarness(p)) { dut =>
      dut.io.reserveValid.poke(true.B)
      pokeCommonRow(dut.io.reserveRow, childIndex = 0, memberIndex = 3,
        uopClass = OooUopClass.Agu)
      dut.io.staValid.poke(false.B)
      dut.io.sta.poke(0.U.asTypeOf(dut.io.sta))
      dut.io.stdValid.poke(false.B)
      dut.io.std.poke(0.U.asTypeOf(dut.io.std))
      dut.io.fillPermit.poke(false.B)
      dut.io.recoveryValid.poke(false.B)
      dut.io.recovery.poke(0.U.asTypeOf(dut.io.recovery))
      dut.clock.step()
      dut.io.reserveValid.poke(false.B)

      pokeExecute(dut.io.std, addressHalf = false)
      dut.io.stdValid.poke(true.B)
      dut.clock.step()
      dut.io.stdValid.poke(false.B)
      dut.io.occupied.expect(true.B)

      val recovery = dut.io.recovery
      recovery.poke(0.U.asTypeOf(recovery))
      recovery.valid.poke(true.B)
      recovery.oldHead.valid.poke(true.B)
      recovery.oldHead.peId.poke(1.U)
      recovery.oldHead.stid.poke(1.U)
      recovery.oldHead.ridSlot.poke(0.U)
      recovery.oldHead.ridGeneration.poke(7.U)
      recovery.oldOccupied.poke(8.U)
      recovery.newOccupied.poke(2.U)
      dut.io.recoveryValid.poke(true.B)
      dut.io.occupied.expect(false.B)
      dut.clock.step()
      dut.io.recoveryValid.poke(false.B)
      dut.io.fillPermit.poke(true.B)
      dut.clock.step(2)
      dut.io.occupied.expect(false.B)
      dut.io.addrReady.expect(0.U)
      dut.io.dataReady.expect(0.U)
    }
  }
}
