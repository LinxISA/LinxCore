package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.Valid
import linxcore.lsu._
import org.scalatest.funsuite.AnyFunSuite

private class OooRobStoreCommitStqHarnessIO(val p: OooParams)
    extends Bundle {
  val commitPrepare = Flipped(Valid(new OooRobCommitBatch(p)))
  val commitStartReady = Output(Bool())
  val commitFire = Input(Bool())
  val insertValid = Input(Bool())
  val insert = Input(new STQStoreRequest(
    p.robGroupsPerStid, lsidWidth = p.lsidWidth,
    peIdWidth = p.peIdWidth, stidWidth = p.stidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    physicalStqEntries = 4))
  val insertReady = Output(Bool())
  val insertIndex = Output(UInt(2.W))
  val issueEnable = Input(Bool())
  val storeTokenAccepted = Output(Bool())
  val storeTokenUsed = Output(UInt(p.storeCommitBufferCountWidth.W))
  val commitQueueCount = Output(UInt(3.W))
  val stqCommitMask = Output(UInt(4.W))
  val scbAcceptedMask = Output(UInt(4.W))
  val logicalCompletionCount = Output(UInt(2.W))
}

private class OooRobStoreCommitStqHarness(val p: OooParams) extends Module {
  val io = IO(new OooRobStoreCommitStqHarnessIO(p))

  val owner = Module(new OooRobStoreCommitOwner(p))
  val path = Module(new STQSCBCommitPath(
    entries = 4,
    queueEntries = 4,
    issueWidth = 2,
    scbEntries = 4,
    scbResponseBufferDepth = 2,
    robEntries = p.robGroupsPerStid,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    lsidWidth = p.lsidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    leaseGenerationWidth = p.executeSlotGenerationWidth))

  owner.io.commitPrepare <> io.commitPrepare
  owner.io.commitFire := io.commitFire
  path.io.robStoreCommit <> owner.io.storeCommit

  path.io.flush := 0.U.asTypeOf(path.io.flush)
  path.io.insertValid := io.insertValid
  path.io.insert := io.insert
  path.io.markCommitValid := false.B
  path.io.markCommitIndex := 0.U
  path.io.issueEnable := io.issueEnable
  path.io.evictEnable := false.B
  path.io.dcacheReady := true.B
  path.io.dcacheWriteHit := false.B
  path.io.dcacheTagHit := false.B
  path.io.l2RequestReady := true.B
  path.io.rawRespValid := false.B
  path.io.rawRespTxnId := 0.U
  path.io.rawRespWrite := false.B
  path.io.rawRespUpgrade := false.B

  io.commitStartReady := owner.io.commitStartReady
  io.insertReady := path.io.insertReady
  io.insertIndex := path.io.insertIndex
  io.storeTokenAccepted := path.io.robStoreCommitAccepted
  io.storeTokenUsed := owner.io.used
  io.commitQueueCount := path.io.drainQueueCount
  io.stqCommitMask := path.io.stqCommitMask
  io.scbAcceptedMask := path.io.scbAcceptedMask
  io.logicalCompletionCount := path.io.drainLogicalCompletionCount
}

class OooRobStoreCommitStqIntegrationSpec
    extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 2,
    instructionDecodeWidth = 2,
    decodedUopWidth = 2,
    renameWidth = 2,
    dispatchWidth = 2,
    retireGroupWidth = 2,
    storeCommitBufferEntries = 8,
    robGroupsPerStid = 8,
    brobEntriesPerStid = 8,
    pcBufferEntries = 16,
    pcBankCount = 4,
    pcReadPorts = 4,
    pcReadReplicaCount = 2,
    pMapQDepthPerStid = 32,
    tuRetireSourceDepthPerStid = 32,
    lsidWidth = 40)

  private def pokeOwner(
      owner: STQExactOwner,
      beatOwner: Boolean = true): Unit = {
    owner.valid.poke(beatOwner.B)
    owner.peId.poke(3.U)
    owner.stid.poke(1.U)
    owner.nativeBidValid.poke(true.B)
    owner.nativeBid.poke(5.U)
    owner.brobGeneration.poke(2.U)
    owner.ridSlot.poke(6.U)
    owner.ridGeneration.poke(4.U)
    owner.memberIndex.poke(0.U)
    owner.residentGeneration.poke(9.U)
  }

  private def pokeStoreBeat(
      dut: OooRobStoreCommitStqHarness,
      beat: Int,
      addr: BigInt,
      data: BigInt): Unit = {
    val request = dut.io.insert
    request.poke(0.U.asTypeOf(request))
    request.storeType.poke(STQStoreType.All)
    request.peId.poke(3.U)
    request.stid.poke(1.U)
    request.bid.valid.poke(true.B)
    request.bid.value.poke(5.U)
    request.gid.valid.poke(true.B)
    request.gid.value.poke(6.U)
    request.rid.valid.poke(true.B)
    request.rid.value.poke(6.U)
    request.lsId.valid.poke(true.B)
    request.lsId.value.poke(((10 + beat) & 7).U)
    request.lsIdFull.poke((10 + beat).U)
    request.storeIdFullValid.poke(true.B)
    request.storeIdFull.poke((20 + beat).U)
    request.logicalStoreValid.poke(true.B)
    request.logicalFirstLsid.poke(10.U)
    request.logicalFirstStoreId.poke(20.U)
    request.logicalRequestCount.poke(2.U)
    request.logicalBeat.poke(beat.U)
    pokeOwner(request.exactOwner)
    request.addr.poke(addr.U)
    request.data.poke(data.U)
    request.size.poke(8.U)
    dut.io.insertValid.poke(true.B)
    dut.io.insertReady.expect(true.B)
    dut.clock.step()
    dut.io.insertValid.poke(false.B)
  }

  private def pokeCommitBatch(dut: OooRobStoreCommitStqHarness): Unit = {
    val batch = dut.io.commitPrepare.bits
    batch.poke(0.U.asTypeOf(batch))
    batch.release.firstGroup.valid.poke(true.B)
    batch.release.firstGroup.peId.poke(3.U)
    batch.release.firstGroup.stid.poke(1.U)
    batch.release.firstGroup.ridSlot.poke(6.U)
    batch.release.firstGroup.ridGeneration.poke(4.U)
    batch.release.groupCount.poke(1.U)
    val group = batch.groups(0)
    group.valid.poke(true.B)
    group.key.valid.poke(true.B)
    group.key.peId.poke(3.U)
    group.key.stid.poke(1.U)
    group.key.ridSlot.poke(6.U)
    group.key.ridGeneration.poke(4.U)
    group.brob.valid.poke(true.B)
    group.brob.bid.valid.poke(true.B)
    group.brob.bid.value.poke(5.U)
    group.brob.generation.poke(2.U)
    group.residentGeneration.poke(9.U)
    group.physicalMemberCount.poke(2.U)
    group.completedMembers.poke(3.U)
    group.logicalUopMask.poke(1.U)
    group.logicalMemberBase(0).poke(0.U)
    group.logicalMemberCount(0).poke(2.U)
    group.memoryOrderValid.poke(true.B)
    group.memoryBefore.lsid.poke(10.U)
    group.memoryBefore.loadId.poke(0.U)
    group.memoryBefore.storeId.poke(20.U)
    group.memoryAfter.lsid.poke(12.U)
    group.memoryAfter.loadId.poke(0.U)
    group.memoryAfter.storeId.poke(22.U)
    group.logicalMemoryAfter(0).lsid.poke(12.U)
    group.logicalMemoryAfter(0).loadId.poke(0.U)
    group.logicalMemoryAfter(0).storeId.poke(22.U)
    dut.io.commitPrepare.valid.poke(true.B)
  }

  test("ROB pair commit reaches CommitQ and SCB without a physical-index sideband") {
    simulate(new OooRobStoreCommitStqHarness(p)) { dut =>
      dut.io.commitPrepare.valid.poke(false.B)
      dut.io.commitPrepare.bits.poke(
        0.U.asTypeOf(dut.io.commitPrepare.bits))
      dut.io.commitFire.poke(false.B)
      dut.io.insertValid.poke(false.B)
      dut.io.insert.poke(0.U.asTypeOf(dut.io.insert))
      dut.io.issueEnable.poke(false.B)
      dut.clock.step()

      pokeStoreBeat(dut, beat = 0, addr = 0x103e,
        data = BigInt("1122334455667788", 16))
      pokeStoreBeat(dut, beat = 1, addr = 0x107e,
        data = BigInt("99aabbccddeeff00", 16))

      pokeCommitBatch(dut)
      dut.io.commitStartReady.expect(true.B)
      dut.io.commitFire.poke(true.B)
      dut.clock.step()
      dut.io.commitFire.poke(false.B)
      dut.io.commitPrepare.valid.poke(false.B)
      dut.io.storeTokenUsed.expect(2.U)

      dut.io.storeTokenAccepted.expect(true.B)
      dut.clock.step()
      dut.io.storeTokenAccepted.expect(true.B)
      dut.clock.step()
      dut.io.storeTokenUsed.expect(0.U)
      dut.io.stqCommitMask.expect("b0011".U)
      dut.io.commitQueueCount.expect(2.U)

      dut.io.issueEnable.poke(true.B)
      dut.clock.step()
      dut.io.scbAcceptedMask.expect("b1111".U)
      dut.io.logicalCompletionCount.expect(1.U)
    }
  }
}
