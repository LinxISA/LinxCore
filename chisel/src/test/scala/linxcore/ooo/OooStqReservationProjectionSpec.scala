package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.log2Ceil
import linxcore.lsu.STQEntryBank
import org.scalatest.funsuite.AnyFunSuite

class OooStqReservationHarnessIO(
    val p: OooParams,
    val stqEntries: Int)
    extends Bundle {
  val inputValid = Input(Bool())
  val input = Input(new OooIexIssueRow(p))
  val rejected = Output(Bool())
  val accepted = Output(Bool())
  val residentCount = Output(UInt(log2Ceil(stqEntries + 1).W))
  val leases = Output(Vec(p.maxMemoryRequestsPerInstruction,
    new linxcore.lsu.STQPhysicalLease(
      stqEntries, p.executeSlotGenerationWidth)))
  val rowLsid = Output(Vec(stqEntries, UInt(p.lsidWidth.W)))
  val rowStoreId = Output(Vec(stqEntries, UInt(p.lsidWidth.W)))
  val rowMember = Output(Vec(stqEntries, UInt(p.robMemberIndexWidth.W)))
}

class OooStqReservationHarness(
    val p: OooParams,
    val stqEntries: Int)
    extends Module {
  val io = IO(new OooStqReservationHarnessIO(p, stqEntries))

  val projection = Module(new OooStqReservationProjection(p, stqEntries))
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

  projection.io.inputValid := io.inputValid
  projection.io.input := io.input
  stq.io.flush := 0.U.asTypeOf(stq.io.flush)
  stq.io.insertValid := false.B
  stq.io.insert := 0.U.asTypeOf(stq.io.insert)
  stq.io.reserveValid := false.B
  stq.io.reserve := 0.U.asTypeOf(stq.io.reserve)
  stq.io.reserveBatchValid := projection.io.reserveValid
  stq.io.reserveBatchMask := projection.io.reserveMask
  stq.io.reserveBatch := projection.io.reserve
  stq.io.fillValid := false.B
  stq.io.fill := 0.U.asTypeOf(stq.io.fill)
  stq.io.markCommitValid := false.B
  stq.io.markCommitIndex := 0.U
  stq.io.commitFreeValid := false.B
  stq.io.commitFreeIndex := 0.U
  stq.io.commitFreeMaskValid := false.B
  stq.io.commitFreeMask := 0.U

  io.rejected := projection.io.rejected
  io.accepted := stq.io.reserveBatchAccepted
  io.residentCount := stq.io.residentCount
  io.leases := stq.io.reserveBatchLeases
  for (index <- 0 until stqEntries) {
    io.rowLsid(index) := stq.io.rows(index).lsIdFull
    io.rowStoreId(index) := stq.io.rows(index).storeIdFull
    io.rowMember(index) := stq.io.rows(index).exactOwner.memberIndex
  }
}

class OooStqReservationProjectionSpec extends AnyFunSuite with ChiselSim {
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

  private def pokeStore(
      row: OooIexIssueRow,
      pair: Boolean,
      childIndex: Int = 0,
      memberIndex: Int = 3): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.schedule.valid.poke(true.B)
    row.schedule.peId.poke(1.U)
    row.schedule.stid.poke(1.U)
    row.schedule.childIndex.poke(childIndex.U)
    row.schedule.member.group.valid.poke(true.B)
    row.schedule.member.group.peId.poke(1.U)
    row.schedule.member.group.stid.poke(1.U)
    row.schedule.member.group.ridSlot.poke(6.U)
    row.schedule.member.group.ridGeneration.poke(7.U)
    row.schedule.member.bid.valid.poke(true.B)
    row.schedule.member.bid.value.poke(0x93.U)
    row.schedule.member.brobGeneration.poke(8.U)
    row.schedule.member.memberIndex.poke(memberIndex.U)
    row.schedule.member.residentGeneration.poke(9.U)
    row.schedule.reservation.valid.poke(true.B)
    row.schedule.reservation.uopClass.poke(
      (if (childIndex == 0) OooUopClass.Agu else OooUopClass.Std))
    row.payload.recipe.valid.poke(true.B)
    row.payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    row.payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
    row.payload.recipe.recipeKind.poke(
      (if (pair) OooOpcodeRecipeKind.PairStore else
        OooOpcodeRecipeKind.ScalarStore).U)
    row.payload.recipe.lateSplitKind.poke(
      (if (pair) OooLateSplitKind.PairStoreAddressData else
        OooLateSplitKind.StoreAddressData).U)
    row.payload.memory.valid.poke(true.B)
    row.payload.memory.isStore.poke(true.B)
    row.payload.memory.accessBytes.poke(8.U)
    row.payload.memoryOrder.valid.poke(true.B)
    row.payload.memoryOrder.memoryValid.poke(true.B)
    row.payload.memoryOrder.isStore.poke(true.B)
    row.payload.memoryOrder.requestCount.poke((if (pair) 2 else 1).U)
    row.payload.memoryOrder.firstLsid.poke(BigInt("100000001", 16).U)
    row.payload.memoryOrder.firstTypeId.poke(BigInt("200000001", 16).U)
  }

  test("pair store projection atomically reserves consecutive full serial rows") {
    simulate(new OooStqReservationHarness(p, stqEntries = 4)) { dut =>
      dut.io.inputValid.poke(true.B)
      pokeStore(dut.io.input, pair = true)
      dut.io.rejected.expect(false.B)
      dut.io.accepted.expect(true.B)
      dut.io.leases(0).index.expect(0.U)
      dut.io.leases(1).index.expect(1.U)
      dut.clock.step()
      dut.io.inputValid.poke(false.B)
      dut.io.residentCount.expect(2.U)
      dut.io.rowLsid(0).expect(BigInt("100000001", 16).U)
      dut.io.rowLsid(1).expect(BigInt("100000002", 16).U)
      dut.io.rowStoreId(0).expect(BigInt("200000001", 16).U)
      dut.io.rowStoreId(1).expect(BigInt("200000002", 16).U)
      dut.io.rowMember(0).expect(3.U)
      dut.io.rowMember(1).expect(3.U)
    }
  }

  test("scalar store projection reserves exactly one row") {
    simulate(new OooStqReservationHarness(p, stqEntries = 4)) { dut =>
      dut.io.inputValid.poke(true.B)
      pokeStore(dut.io.input, pair = false)
      dut.io.accepted.expect(true.B)
      dut.io.leases(0).valid.expect(true.B)
      dut.io.leases(1).valid.expect(false.B)
      dut.clock.step()
      dut.io.residentCount.expect(1.U)
    }
  }

  test("STD child and malformed pair range cannot allocate STQ rows") {
    simulate(new OooStqReservationHarness(p, stqEntries = 4)) { dut =>
      dut.io.inputValid.poke(true.B)
      pokeStore(dut.io.input, pair = true, childIndex = 1, memberIndex = 4)
      dut.io.rejected.expect(true.B)
      dut.io.accepted.expect(false.B)
      dut.clock.step()
      dut.io.residentCount.expect(0.U)

      pokeStore(dut.io.input, pair = true)
      dut.io.input.payload.memoryOrder.requestCount.poke(1.U)
      dut.io.rejected.expect(true.B)
      dut.io.accepted.expect(false.B)
      dut.clock.step()
      dut.io.residentCount.expect(0.U)
    }
  }
}
