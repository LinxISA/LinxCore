package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, Valid, log2Ceil}
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

import linxcore.common.{CoreParams, DestinationKind, OperandClass}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.lsu.LoadStructuralBlockDisposition

class OooIexScalarLoadStorePathHarnessIO(
    val p: OooParams,
    val coreParams: CoreParams,
    val stqEntries: Int) extends Bundle {
  val external = new OooIexScalarLoadExternalIO(p, coreParams, stqEntries)
  val agu = Flipped(Vec(3, Decoupled(new OooIexAguLoadRequest(p))))
  val storeReserve = Flipped(Decoupled(new OooIexIssueRow(p)))
  val storeAddress = Flipped(Vec(2,
    Decoupled(new OooIexExecuteTransaction(p))))
  val storeData = Flipped(Vec(2,
    Decoupled(new OooIexExecuteTransaction(p))))
  val result = Decoupled(new OooIexLoadResult(p))
  val loadCancel = Output(Vec(3, Valid(new OooIexLoadCancel(p))))
  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryRejected = Output(Bool())
  val recoveryFire = Input(Bool())
  val markCommitValid = Input(Bool())
  val markCommitIndex = Input(UInt(log2Ceil(stqEntries).W))
  val commitFreeMaskValid = Input(Bool())
  val commitFreeMask = Input(UInt(stqEntries.W))

  def rebind = external.rebind
  def launch = external.launch
  def pick = external.pick
  def scbReturn = external.scbReturn
  def replayWake = external.replayWake
  def refill = external.refill
  def missRequest = external.missRequest
  def missResponse = external.missResponse
  def resolveRetireValid = external.resolveRetireValid
  def resolveRetireBid = external.resolveRetireBid
  def resolveRetireLsId = external.resolveRetireLsId
  def resolveRetireLsIdFullValid = external.resolveRetireLsIdFullValid
  def resolveRetireLsIdFull = external.resolveRetireLsIdFull
  def l1dEviction = external.l1dEviction
  def scbSource = external.scbSource
  def scbCacheUpdate = external.scbCacheUpdate
  def scbLookupValid = external.scbLookupValid
  def scbLookupLineAddr = external.scbLookupLineAddr
  def scbGrantWriteValid = external.scbGrantWriteValid
  def scbGrantWriteLineAddr = external.scbGrantWriteLineAddr
  def robLookupValid = external.robLookupValid
  def robLookupPeId = external.robLookupPeId
  def robLookupStid = external.robLookupStid
  def robLookupBid = external.robLookupBid
  def robLookupLoadLsIdFullValid = external.robLookupLoadLsIdFullValid
  def robLookupLoadLsIdFull = external.robLookupLoadLsIdFull
  def robLookupAttempt = external.robLookupAttempt
  def robRowValid = external.robRowValid
  def robRowNeedFlush = external.robRowNeedFlush
  def lsuRecoveryProjection = external.lsuRecoveryProjection
  def hardFlush = external.hardFlush
  def structuralBlockPending = external.structuralBlockPending
  def structuralBlockUnsupported = external.structuralBlockUnsupported
  def structuralBlockDisposition = external.structuralBlockDisposition
  def structuralBlockReason = external.structuralBlockReason
  def structuralBlockLoadId = external.structuralBlockLoadId
  def structuralBlockAttempt = external.structuralBlockAttempt
  def mdbRecoveryReady = external.mdbRecoveryReady
  def allocAccepted = external.allocAccepted
  def liqOccupiedMask = external.liqOccupiedMask
  def liqRepickMask = external.liqRepickMask
  def liqWaitStoreMask = external.liqWaitStoreMask
  def structuralRetryAccepted = external.structuralRetryAccepted
  def protocolError = external.protocolError
}

class OooIexScalarLoadStorePathHarness(
    val p: OooParams,
    val coreParams: CoreParams,
    val stqEntries: Int) extends Module {
  val io = IO(new OooIexScalarLoadStorePathHarnessIO(
    p, coreParams, stqEntries))
  val ownership = Module(new OooIexCanonicalLoadOwnership(
    p, coreParams, laneCount = 3))
  val store = Module(new OooIexStoreStqFabric(p, stqEntries))
  val path = Module(new OooIexScalarLoadStorePath(
    p, coreParams, stqEntries))

  ownership.io.agu <> io.agu
  path.io.owner.liqAlloc <> ownership.io.liqAlloc
  ownership.io.liqAllocLoadId := path.io.owner.liqAllocLoadId
  ownership.io.rebind <> path.io.owner.rebind
  path.io.owner.liqRebind <> ownership.io.liqRebind
  ownership.io.attemptLaunch := path.io.owner.attemptLaunch
  path.io.owner.attemptLaunchAccepted := ownership.io.attemptLaunchAccepted
  ownership.io.completion <> path.io.owner.completion
  io.result <> ownership.io.result
  io.loadCancel := ownership.io.loadCancel

  store.io.reserve <> io.storeReserve
  store.io.storeAddress <> io.storeAddress
  store.io.storeData <> io.storeData
  store.io.loadCancel.foreach(_ := 0.U.asTypeOf(store.io.loadCancel.head))
  for (lane <- 0 until 3) {
    store.io.loadCancel(lane) := ownership.io.loadCancel(lane)
  }
  path.io.store.forwardQuery <> store.io.loadForwardQuery
  store.io.loadForwardResponse <> path.io.store.forwardResponse
  path.io.store.rows := store.io.rows
  path.io.store.lateStaProbe := store.io.lateStaProbe
  path.io.store.lateStaCandidate := store.io.lateStaCandidate
  store.io.lateStaPermit := path.io.store.lateStaPermit
  path.io.store.occupiedMask := store.io.occupiedMask
  path.io.store.forwardingOccupied := store.io.loadForwardOccupied

  path.io.external <> io.external
  ownership.io.flush := io.external.hardFlush
  path.io.recoveryPrepare := io.recoveryPrepare
  ownership.io.recoveryPrepare := io.recoveryPrepare
  store.io.recoveryPrepare := io.recoveryPrepare
  io.recoveryPrepareReady := path.io.recoveryPrepareReady &&
    ownership.io.recoveryPrepareReady && store.io.recoveryPrepareReady
  io.recoveryRejected := path.io.recoveryRejected ||
    ownership.io.recoveryRejected || store.io.recoveryRejected
  val recoveryApply = io.recoveryFire && io.recoveryPrepareReady
  path.io.recoveryFire := recoveryApply
  ownership.io.recoveryFire := recoveryApply
  store.io.recoveryFire := recoveryApply

  store.io.markCommitValid := io.markCommitValid
  store.io.markCommitIndex := io.markCommitIndex
  store.io.commitFreeMaskValid := io.commitFreeMaskValid
  store.io.commitFreeMask := io.commitFreeMask
}

class OooIexScalarLoadStorePathSpec extends AnyFunSuite with ChiselSim {
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
  private val p = OooIexLinxPhysicalProfile(base).params
  private val coreParams = {
    val default = OooIexCanonicalLoadOwnership.defaultCoreParams(p)
    default.copy(scalarLsu = default.scalarLsu.copy(
      stqEntries = 4,
      liqEntries = 4,
      commitQueueEntries = 4,
      commitIssueWidth = 1,
      scbEntries = 4,
      loadMissQueueEntries = 2,
      loadRefillQueueEntries = 2,
      resolveQueueEntries = 8,
      loadReturnQueueEntries = 2,
      loadReturnPipeCount = 3,
      mapQDepth = 8))
  }

  private def clear(dut: OooIexScalarLoadStorePathHarness): Unit = {
    dut.io.agu.foreach { lane =>
      lane.valid.poke(false.B)
      lane.bits.poke(0.U.asTypeOf(lane.bits))
    }
    dut.io.storeReserve.valid.poke(false.B)
    dut.io.storeReserve.bits.poke(0.U.asTypeOf(dut.io.storeReserve.bits))
    dut.io.storeAddress.foreach { lane =>
      lane.valid.poke(false.B)
      lane.bits.poke(0.U.asTypeOf(lane.bits))
    }
    dut.io.storeData.foreach { lane =>
      lane.valid.poke(false.B)
      lane.bits.poke(0.U.asTypeOf(lane.bits))
    }
    dut.io.rebind.valid.poke(false.B)
    dut.io.rebind.bits.poke(0.U.asTypeOf(dut.io.rebind.bits))
    dut.io.launch.valid.poke(false.B)
    dut.io.launch.bits.poke(0.U)
    dut.io.pick.valid.poke(false.B)
    dut.io.pick.bits.poke(0.U)
    dut.io.scbReturn.valid.poke(false.B)
    dut.io.scbReturn.bits.poke(0.U)
    dut.io.replayWake.valid.poke(false.B)
    dut.io.replayWake.bits.poke(0.U.asTypeOf(dut.io.replayWake.bits))
    dut.io.refill.valid.poke(false.B)
    dut.io.refill.bits.poke(0.U.asTypeOf(dut.io.refill.bits))
    dut.io.missRequest.ready.poke(true.B)
    dut.io.missResponse.valid.poke(false.B)
    dut.io.missResponse.bits.poke(0.U.asTypeOf(dut.io.missResponse.bits))
    dut.io.resolveRetireValid.poke(false.B)
    dut.io.resolveRetireBid.poke(0.U.asTypeOf(dut.io.resolveRetireBid))
    dut.io.resolveRetireLsId.poke(0.U.asTypeOf(dut.io.resolveRetireLsId))
    dut.io.resolveRetireLsIdFullValid.poke(false.B)
    dut.io.resolveRetireLsIdFull.poke(0.U)
    dut.io.l1dEviction.ready.poke(true.B)
    dut.io.scbSource.returned.poke(true.B)
    dut.io.scbSource.validMask.poke("hffffffffffffffff".U)
    dut.io.scbSource.data.poke(BigInt("8877665544332211", 16).U)
    dut.io.scbCacheUpdate.poke(0.U.asTypeOf(dut.io.scbCacheUpdate))
    dut.io.scbLookupValid.poke(false.B)
    dut.io.scbLookupLineAddr.poke(0.U)
    dut.io.scbGrantWriteValid.poke(false.B)
    dut.io.scbGrantWriteLineAddr.poke(0.U)
    dut.io.robRowValid.poke(true.B)
    dut.io.robRowNeedFlush.poke(false.B)
    dut.io.result.ready.poke(true.B)
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.lsuRecoveryProjection.poke(
      0.U.asTypeOf(dut.io.lsuRecoveryProjection))
    dut.io.recoveryFire.poke(false.B)
    dut.io.hardFlush.poke(false.B)
    dut.io.markCommitValid.poke(false.B)
    dut.io.markCommitIndex.poke(0.U)
    dut.io.commitFreeMaskValid.poke(false.B)
    dut.io.commitFreeMask.poke(0.U)
    dut.io.mdbRecoveryReady.poke(true.B)
  }

  private def pokeUnknownOlderStore(row: OooIexIssueRow): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.schedule.valid.poke(true.B)
    row.schedule.peId.poke(1.U)
    row.schedule.stid.poke(1.U)
    row.schedule.childIndex.poke(0.U)
    row.schedule.member.group.valid.poke(true.B)
    row.schedule.member.group.peId.poke(1.U)
    row.schedule.member.group.stid.poke(1.U)
    row.schedule.member.group.ridSlot.poke(1.U)
    row.schedule.member.group.ridGeneration.poke(1.U)
    row.schedule.member.bid.valid.poke(true.B)
    row.schedule.member.bid.value.poke(5.U)
    row.schedule.member.brobGeneration.poke(2.U)
    row.schedule.member.memberIndex.poke(0.U)
    row.schedule.member.residentGeneration.poke(4.U)
    row.schedule.reservation.valid.poke(true.B)
    row.schedule.reservation.uopClass.poke(OooUopClass.Agu)
    row.payload.recipe.valid.poke(true.B)
    row.payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    row.payload.recipe.recipeKind.poke(OooOpcodeRecipeKind.ScalarStore)
    row.payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
    row.payload.recipe.lateSplitKind.poke(
      OooLateSplitKind.StoreAddressData)
    row.payload.memory.valid.poke(true.B)
    row.payload.memory.isStore.poke(true.B)
    row.payload.memory.addressMode.poke(OooMemoryAddressMode.BaseOffset)
    row.payload.memory.accessBytes.poke(8.U)
    row.payload.memory.addressSourceMask.poke(1.U)
    row.payload.memory.dataSourceMask.poke(4.U)
    row.payload.memoryOrder.valid.poke(true.B)
    row.payload.memoryOrder.memoryValid.poke(true.B)
    row.payload.memoryOrder.isStore.poke(true.B)
    row.payload.memoryOrder.requestCount.poke(1.U)
    row.payload.memoryOrder.firstLsid.poke(6.U)
    row.payload.memoryOrder.firstTypeId.poke(3.U)
  }

  private def pokeCrossLineStoreAddress(
      execute: OooIexExecuteTransaction): Unit = {
    execute.poke(0.U.asTypeOf(execute))
    execute.ownerClass.poke(OooUopClass.Agu)
    pokeUnknownOlderStore(execute.i2.row)
    execute.i2.sourceMask.poke(1.U)
    execute.i2.sourceData(0).poke(0x103c.U)
  }

  private def pokeLoad(
      request: OooIexAguLoadRequest,
      address: BigInt = 0x1000): Unit = {
    request.poke(0.U.asTypeOf(request))
    val execute = request.execute
    val schedule = execute.i2.row.schedule
    val payload = execute.i2.row.payload
    val opcode = FrontendOpcodeDecodeTable.OP_LDI
    val rule = OooOpcodeRecipeTable.Rules.find(_.opcode == opcode).get

    execute.ownerClass.poke(OooUopClass.Agu)
    execute.ownerLane.poke(0.U)
    execute.slotGeneration.poke(2.U)
    schedule.valid.poke(true.B)
    schedule.peId.poke(1.U)
    schedule.stid.poke(1.U)
    schedule.epoch.poke(3.U)
    schedule.transactionId.poke(9.U)
    schedule.member.group.valid.poke(true.B)
    schedule.member.group.peId.poke(1.U)
    schedule.member.group.stid.poke(1.U)
    schedule.member.group.ridSlot.poke(2.U)
    schedule.member.group.ridGeneration.poke(1.U)
    schedule.member.bid.valid.poke(true.B)
    schedule.member.bid.value.poke(5.U)
    schedule.member.brobGeneration.poke(2.U)
    schedule.member.memberIndex.poke(0.U)
    schedule.member.residentGeneration.poke(4.U)
    schedule.reservation.valid.poke(true.B)
    schedule.reservation.uopClass.poke(OooUopClass.Agu)
    schedule.sources(0).valid.poke(true.B)
    schedule.sources(0).ready.poke(true.B)
    schedule.sources(0).operandClass.poke(OperandClass.P)
    schedule.destinations(0).valid.poke(true.B)
    schedule.destinations(0).kind.poke(DestinationKind.Gpr)
    schedule.destinations(0).atag.poke(6.U)
    schedule.destinations(0).ptag.poke(17.U)
    schedule.destinations(0).ptagGeneration.poke(3.U)

    execute.i2.sourceMask.poke(1.U)
    execute.i2.sourceData(0).poke(address.U)
    execute.i2.pcValid.poke(true.B)
    execute.i2.pc.poke(0x4000.U)
    payload.opcode.poke(opcode.U)
    payload.recipe.opcode.poke(opcode.U)
    payload.recipe.valid.poke(true.B)
    payload.recipe.disposition.poke(rule.disposition.U)
    payload.recipe.recipeKind.poke(rule.recipeKind.U)
    payload.recipe.dispatchClass.poke(rule.dispatchClass.U)
    payload.recipe.sideEffectOwner.poke(rule.sideEffectOwner.U)
    payload.recipe.memoryRequestCount.poke(rule.memoryRequestCount.U)
    payload.recipe.pDestinationCount.poke(1.U)
    payload.memory.valid.poke(true.B)
    payload.memory.isLoad.poke(true.B)
    payload.memory.addressMode.poke(OooMemoryAddressMode.BaseOffset)
    payload.memory.accessBytes.poke(8.U)
    payload.memory.addressSourceMask.poke(1.U)
    payload.memory.offset.poke(0.U)
    payload.memoryOrder.valid.poke(true.B)
    payload.memoryOrder.memoryValid.poke(true.B)
    payload.memoryOrder.isLoad.poke(true.B)
    payload.memoryOrder.requestCount.poke(1.U)
    payload.memoryOrder.firstLsid.poke(7.U)
    payload.memoryOrder.firstTypeId.poke(4.U)
    payload.memoryOrder.before.lsid.poke(7.U)
    payload.memoryOrder.before.loadId.poke(4.U)
    payload.memoryOrder.after.lsid.poke(8.U)
    payload.memoryOrder.after.loadId.poke(5.U)
    payload.previousPDestinations(0).valid.poke(true.B)
    payload.previousPDestinations(0).ptag.poke(7.U)

    request.address.poke(address.U)
    request.accessBytes.poke(8.U)
    request.pcValid.poke(true.B)
    request.pc.poke(0x4000.U)
    request.destination.poke(schedule.destinations(0).peek())
  }

  private def pokeKillingRecovery(
      dut: OooIexScalarLoadStorePathHarness,
      projectionStid: Int = 1,
      projectionBid: Int = 5): Unit = {
    val plan = dut.io.recoveryPrepare.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.valid.poke(true.B)
    plan.oldHead.valid.poke(true.B)
    plan.oldHead.peId.poke(1.U)
    plan.oldHead.stid.poke(1.U)
    plan.oldHead.ridSlot.poke(2.U)
    plan.oldHead.ridGeneration.poke(1.U)
    plan.oldOccupied.poke(1.U)
    plan.newOccupied.poke(0.U)
    plan.pivotOffset.poke(0.U)
    plan.pivot.group.valid.poke(true.B)
    plan.pivot.group.peId.poke(1.U)
    plan.pivot.group.stid.poke(1.U)
    plan.pivot.group.ridSlot.poke(2.U)
    plan.pivot.group.ridGeneration.poke(1.U)
    plan.pivot.bid.valid.poke(true.B)
    plan.pivot.bid.value.poke(5.U)
    plan.pivot.brobGeneration.poke(2.U)
    plan.pivot.memberIndex.poke(0.U)
    plan.pivot.residentGeneration.poke(4.U)
    plan.pivotPhysicalMemberCount.poke(1.U)

    val flush = dut.io.lsuRecoveryProjection
    flush.poke(0.U.asTypeOf(flush))
    flush.req.valid.poke(true.B)
    flush.req.peId.poke(1.U)
    flush.req.stid.poke(projectionStid.U)
    flush.req.tid.poke(1.U)
    flush.req.bid.valid.poke(true.B)
    flush.req.bid.value.poke(projectionBid.U)
    flush.req.bid.wrap.poke(false.B)
    flush.req.lsId.valid.poke(true.B)
    flush.req.lsId.value.poke(7.U)
    flush.req.lsId.wrap.poke(false.B)
    flush.req.lsIdFullValid.poke(true.B)
    flush.req.lsIdFull.poke(7.U)
    flush.baseOnBid.poke(true.B)
    dut.io.recoveryPrepare.valid.poke(true.B)
  }

  test("carries one AGU load through canonical LIQ and live STQ forwarding to W2") {
    simulate(new OooIexScalarLoadStorePathHarness(p, coreParams, stqEntries = 4)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeLoad(dut.io.agu(0).bits)
      dut.io.agu(0).valid.poke(true.B)
      dut.io.agu(0).ready.expect(true.B)
      dut.io.allocAccepted.expect(true.B)
      dut.clock.step()
      dut.io.agu(0).valid.poke(false.B)

      dut.io.launch.bits.poke(0.U)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.ready.expect(true.B)
      dut.clock.step()
      dut.io.launch.valid.poke(false.B)

      dut.io.robRowValid.poke(false.B)
      var sawRobLookup = false
      for (_ <- 0 until 20 if !sawRobLookup) {
        if (dut.io.robLookupValid.peek().litToBoolean) {
          sawRobLookup = true
          dut.io.robLookupPeId.expect(1.U)
          dut.io.robLookupStid.expect(1.U)
          dut.io.robLookupBid.valid.expect(true.B)
          dut.io.robLookupBid.value.expect(5.U)
          dut.io.robLookupLoadLsIdFullValid.expect(true.B)
          dut.io.robLookupLoadLsIdFull.expect(7.U)
          dut.io.robLookupAttempt.producer.ridSlot.expect(2.U)
          dut.io.robLookupAttempt.producer.residentGeneration.expect(4.U)
          dut.io.robLookupAttempt.generation.expect(1.U)
        } else {
          dut.clock.step()
        }
      }
      assert(sawRobLookup,
        "the closed path must expose the exact ROB lookup identity")
      dut.io.robRowValid.poke(true.B)

      var sawResult = false
      var resultData = BigInt(0)
      for (_ <- 0 until 20) {
        if (dut.io.result.valid.peek().litToBoolean) {
          sawResult = true
          resultData = dut.io.result.bits.data.peek().litValue
        }
        dut.clock.step()
      }
      assert(sawResult, "the canonical load must reach the OOO terminal result")
      assert(resultData == BigInt("8877665544332211", 16))
      dut.io.protocolError.expect(false.B)
    }
  }

  test("unknown older store atomically cancels and rebinds the canonical load") {
    simulate(new OooIexScalarLoadStorePathHarness(
      p, coreParams, stqEntries = 4)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeUnknownOlderStore(dut.io.storeReserve.bits)
      dut.io.storeReserve.valid.poke(true.B)
      dut.io.storeReserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.storeReserve.valid.poke(false.B)

      pokeLoad(dut.io.agu(0).bits)
      dut.io.agu(0).valid.poke(true.B)
      dut.io.allocAccepted.expect(true.B)
      dut.clock.step()
      dut.io.agu(0).valid.poke(false.B)

      dut.io.launch.bits.poke(0.U)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.ready.expect(true.B)
      dut.clock.step()
      dut.io.launch.valid.poke(false.B)

      var sawPending = false
      var sawCancel = false
      var sawRetryAccepted = false
      var sawWait = false
      for (_ <- 0 until 30 if !sawWait) {
        if (dut.io.structuralBlockPending.peek().litToBoolean) {
          sawPending = true
          dut.io.structuralBlockUnsupported.expect(false.B)
          dut.io.structuralBlockDisposition.expect(
            LoadStructuralBlockDisposition.WaitStore)
          dut.io.structuralBlockLoadId.slot.expect(0.U)
          dut.io.structuralBlockAttempt.generation.expect(1.U)
        }
        if (dut.io.loadCancel(0).valid.peek().litToBoolean) {
          sawCancel = true
          dut.io.loadCancel(0).bits.load.valid.expect(true.B)
          dut.io.loadCancel(0).bits.load.generation.expect(1.U)
        }
        if (dut.io.structuralRetryAccepted.peek().litToBoolean) {
          sawRetryAccepted = true
        }
        if ((dut.io.liqWaitStoreMask.peek().litValue & 1) != 0) {
          sawWait = true
          dut.io.liqRepickMask.expect(0.U)
        }
        dut.clock.step()
      }
      assert(sawPending,
        "the structural result must enter retained policy ownership")
      assert(sawCancel,
        "the old speculative generation must be cancelled exactly once")
      assert(sawRetryAccepted,
        "OOO metadata and LIQ structural retry must share one fire")
      assert(sawWait,
        "the canonical LIQ row must install the exact wait-store key")
      dut.io.protocolError.expect(false.B)
    }
  }

  test("cross-line store uncertainty remains fail closed inside production") {
    simulate(new OooIexScalarLoadStorePathHarness(
      p, coreParams, stqEntries = 4)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeUnknownOlderStore(dut.io.storeReserve.bits)
      dut.io.storeReserve.valid.poke(true.B)
      dut.io.storeReserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.storeReserve.valid.poke(false.B)

      pokeCrossLineStoreAddress(dut.io.storeAddress(0).bits)
      dut.io.storeAddress(0).valid.poke(true.B)
      dut.io.storeAddress(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.storeAddress(0).valid.poke(false.B)
      dut.clock.step(4)

      pokeLoad(dut.io.agu(0).bits, address = 0x1038)
      dut.io.agu(0).valid.poke(true.B)
      dut.io.allocAccepted.expect(true.B)
      dut.clock.step()
      dut.io.agu(0).valid.poke(false.B)
      dut.io.launch.bits.poke(0.U)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.ready.expect(true.B)
      dut.clock.step()
      dut.io.launch.valid.poke(false.B)

      var sawUnsupported = false
      var sawCancel = false
      var sawRetry = false
      for (_ <- 0 until 30 if !sawUnsupported) {
        sawCancel ||= dut.io.loadCancel(0).valid.peek().litToBoolean
        sawRetry ||= dut.io.structuralRetryAccepted.peek().litToBoolean
        sawUnsupported =
          dut.io.structuralBlockUnsupported.peek().litToBoolean
        if (!sawUnsupported) dut.clock.step()
      }
      assert(sawUnsupported,
        "cross-line store overlap must become retained unsupported state")
      assert(!sawCancel,
        "unsupported structural state must not cancel as an ordinary retry")
      assert(!sawRetry,
        "unsupported structural state must not mutate the LIQ attempt")
      dut.io.structuralBlockDisposition.expect(
        LoadStructuralBlockDisposition.Unsupported)
      dut.io.structuralBlockPending.expect(true.B)
      dut.io.protocolError.expect(true.B)
      dut.io.liqRepickMask.expect(1.U)
      dut.io.liqWaitStoreMask.expect(0.U)

      pokeKillingRecovery(dut)
      dut.clock.step(4)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.expect(true.B)
      dut.io.structuralBlockPending.expect(true.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.lsuRecoveryProjection.req.valid.poke(false.B)

      dut.io.hardFlush.poke(true.B)
      dut.clock.step()
      dut.io.structuralBlockPending.expect(false.B)
      dut.io.liqOccupiedMask.expect(0.U)
    }
  }

  test("production boundary hides raw hard-block dequeue ownership") {
    val chirrtl = ChiselStage.emitCHIRRTL(
      new OooIexScalarLoadStorePath(p, coreParams, stqEntries = 4))
    assert(chirrtl.contains(
      "inst structuralPolicy of LoadStructuralBlockPolicy"))
    assert(!chirrtl.contains("external_hardBlock"))
    assert(chirrtl.contains("io.external.structuralBlockPending"))
    assert(chirrtl.contains("io.external.structuralRetryAccepted"))
  }

  test("holds all canonical load state during prepare and prunes only on common fire") {
    simulate(new OooIexScalarLoadStorePathHarness(p, coreParams, stqEntries = 4)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeLoad(dut.io.agu(0).bits)
      dut.io.agu(0).valid.poke(true.B)
      dut.io.allocAccepted.expect(true.B)
      dut.clock.step()
      dut.io.agu(0).valid.poke(false.B)
      dut.io.liqOccupiedMask.expect(1.U)

      pokeKillingRecovery(dut)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.expect(false.B)
      var prepared = false
      for (_ <- 0 until 20 if !prepared) {
        dut.clock.step()
        prepared = dut.io.recoveryPrepareReady.peek().litToBoolean
      }
      assert(prepared, "LIQ-only recovery must reach the prepared state")
      dut.io.liqOccupiedMask.expect(1.U)
      dut.clock.step()

      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.lsuRecoveryProjection.req.valid.poke(false.B)
      dut.io.liqOccupiedMask.expect(0.U)
      dut.io.protocolError.expect(false.B)
    }
  }

  test("rejects recovery while load state has advanced beyond LIQ-only residency") {
    simulate(new OooIexScalarLoadStorePathHarness(p, coreParams, stqEntries = 4)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeLoad(dut.io.agu(0).bits)
      dut.io.agu(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.agu(0).valid.poke(false.B)
      dut.io.robRowValid.poke(false.B)
      dut.io.launch.bits.poke(0.U)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.ready.expect(true.B)
      dut.clock.step()
      dut.io.launch.valid.poke(false.B)

      var reachedReturn = false
      for (_ <- 0 until 20 if !reachedReturn) {
        reachedReturn = dut.io.robLookupValid.peek().litToBoolean
        if (!reachedReturn) dut.clock.step()
      }
      assert(reachedReturn, "test requires retained post-LIQ return state")

      pokeKillingRecovery(dut)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.expect(false.B)
      dut.clock.step(4)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.expect(true.B)
    }
  }

  test("rejects a mismatched LSU recovery projection without mutation") {
    simulate(new OooIexScalarLoadStorePathHarness(p, coreParams, stqEntries = 4)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeLoad(dut.io.agu(0).bits)
      dut.io.agu(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.agu(0).valid.poke(false.B)

      pokeKillingRecovery(dut, projectionStid = 0)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.expect(true.B)
      dut.clock.step(2)
      dut.io.liqOccupiedMask.expect(1.U)
    }
  }

  test("rejects a same-scope LSU projection whose LIQ kill decision disagrees") {
    simulate(new OooIexScalarLoadStorePathHarness(p, coreParams, stqEntries = 4)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeLoad(dut.io.agu(0).bits)
      dut.io.agu(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.agu(0).valid.poke(false.B)

      pokeKillingRecovery(dut, projectionBid = 6)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.expect(true.B)
      dut.clock.step(2)
      dut.io.liqOccupiedMask.expect(1.U)
    }
  }
}
