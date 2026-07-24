package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.{DestinationKind, InterfaceParams}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.rename.{StoreSplitIssuePayload, StoreSplitStoreType}
import linxcore.rob.ROBID
import org.scalatest.funsuite.AnyFunSuite

object StoreDispatchSTQPathReference {
  import STQEntryBankReference._
  import STQInsertProbeReference._
  import StoreDispatchToSTQReference.Decision

  final case class PathDecision(staProbe: Result, stdProbe: Result, dispatch: Decision)

  def decide(
      rows: Seq[Option[Entry]],
      staReq: Request,
      stdReq: Request,
      staValid: Boolean,
      stdValid: Boolean,
      staExecValid: Boolean,
      stdExecValid: Boolean,
      addressInsertPermit: Boolean = true,
      flush: Boolean = false,
      flushApplied: Boolean = false): PathDecision = {
    val staCandidate = !flush && staValid && staExecValid
    val stdCandidate = !flush && stdValid && stdExecValid
    val staProbe = probe(rows, staReq, valid = staCandidate, flushApplied = flushApplied)
    val stdProbe = probe(rows, stdReq, valid = stdCandidate, flushApplied = flushApplied)
    val dispatch = StoreDispatchToSTQReference.decide(
      staValid = staValid,
      stdValid = stdValid,
      staExecValid = staExecValid,
      stdExecValid = stdExecValid,
      staInsertReady = staProbe.ready && (staReq.storeType == Data || addressInsertPermit),
      stdInsertReady = stdProbe.ready && (stdReq.storeType == Data || addressInsertPermit),
      flush = flush)

    PathDecision(staProbe, stdProbe, dispatch)
  }
}

class StoreDispatchSTQPathSpec extends AnyFunSuite with ChiselSim {
  import STQEntryBankReference._
  import STQFlushPruneReference.Id
  import StoreDispatchSTQPathReference._

  private def req(n: Int, storeType: StoreType = All, bid: Int = 0, lsId: Int = 0): Request =
    Request(
      storeType = storeType,
      bid = Id(value = bid),
      gid = Id(value = 0),
      rid = Id(value = n),
      lsId = Id(value = lsId),
      stid = 1,
      peId = 2,
      tid = 3,
      addr = 0x1000 + n * 8,
      data = 0x2000 + n,
      size = 8
    )

  private def waitEntry(request: Request): Entry =
    Entry(
      status = Wait,
      req = request,
      addrReady = request.storeType == All || request.storeType == Addr,
      dataReady = request.storeType == All || request.storeType == Data)

  private def pokeRobId(id: ROBID, value: Int, valid: Boolean = true, wrap: Boolean = false): Unit = {
    id.valid.poke(valid.B)
    id.wrap.poke(wrap.B)
    id.value.poke(value.U)
  }

  private def clearInputs(dut: StoreDispatchSTQPath): Unit = {
    dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
    dut.io.addressInsertPermit.poke(true.B)
    dut.io.queueFlushValid.poke(false.B)
    dut.io.staIn.poke(0.U.asTypeOf(dut.io.staIn))
    dut.io.stdIn.poke(0.U.asTypeOf(dut.io.stdIn))
    dut.io.unsplitIn.poke(0.U.asTypeOf(dut.io.unsplitIn))
    dut.io.staExec.poke(0.U.asTypeOf(dut.io.staExec))
    dut.io.stdExec.poke(0.U.asTypeOf(dut.io.stdExec))
    dut.io.scResultValid.poke(false.B)
    dut.io.scResultSuccess.poke(false.B)
    dut.io.scResultIdentity.poke(0.U.asTypeOf(dut.io.scResultIdentity))
    dut.io.scStoreData.poke(0.U)
    dut.io.markCommitValid.poke(false.B)
    dut.io.markCommitIndex.poke(0.U)
    dut.io.commitFreeValid.poke(false.B)
    dut.io.commitFreeIndex.poke(0.U)
    dut.io.commitFreeMaskValid.poke(false.B)
    dut.io.commitFreeMask.poke(0.U)
  }

  private def pokeScPayload(
      payload: StoreSplitIssuePayload,
      storeType: StoreSplitStoreType.Type = StoreSplitStoreType.All,
      bid: Int = 1,
      gid: Int = 2,
      rid: Int = 3,
      lsid: BigInt = 9,
      stid: Int = 4,
      pc: BigInt = 0x8000,
      tSeq: Int = 5,
      uSeq: Int = 6): Unit = {
    payload.poke(0.U.asTypeOf(payload))
    payload.valid.poke(true.B)
    payload.storeType.poke(storeType)
    payload.dataSrcIndex.poke(0.U)
    payload.staSrc0Zeroed.poke(false.B)
    payload.uop.valid.poke(true.B)
    payload.uop.opcode.poke(FrontendOpcodeDecodeTable.OP_SC_W.U)
    payload.uop.isStore.poke(true.B)
    payload.uop.storeSplitIntent.poke(false.B)
    payload.uop.peId.poke(2.U)
    payload.uop.threadId.poke(stid.U)
    payload.uop.pc.poke(pc.U)
    payload.uop.lsid.poke(lsid.U)
    pokeRobId(payload.uop.bid, bid)
    pokeRobId(payload.uop.gid, gid)
    pokeRobId(payload.uop.rid, rid)
    pokeRobId(payload.tSeq, tSeq)
    pokeRobId(payload.uSeq, uSeq)
    payload.tuDstValid.poke(true.B)
    payload.tuDstKind.poke(DestinationKind.U)
  }

  private def pokeStorePayload(
      payload: StoreSplitIssuePayload,
      storeType: StoreSplitStoreType.Type,
      bid: Int = 1,
      gid: Int = 2,
      rid: Int = 7,
      lsid: BigInt = 11,
      stid: Int = 4,
      pc: BigInt = 0x9000,
      tSeq: Int = 8,
      uSeq: Int = 9): Unit = {
    payload.poke(0.U.asTypeOf(payload))
    payload.valid.poke(true.B)
    payload.storeType.poke(storeType)
    payload.dataSrcIndex.poke(0.U)
    payload.staSrc0Zeroed.poke(false.B)
    payload.uop.valid.poke(true.B)
    payload.uop.opcode.poke(FrontendOpcodeDecodeTable.OP_SW.U)
    payload.uop.isStore.poke(true.B)
    payload.uop.storeSplitIntent.poke(false.B)
    payload.uop.peId.poke(2.U)
    payload.uop.threadId.poke(stid.U)
    payload.uop.pc.poke(pc.U)
    payload.uop.lsid.poke(lsid.U)
    pokeRobId(payload.uop.bid, bid)
    pokeRobId(payload.uop.gid, gid)
    pokeRobId(payload.uop.rid, rid)
    pokeRobId(payload.tSeq, tSeq)
    pokeRobId(payload.uSeq, uSeq)
  }

  private def pokeExec(
      exec: StoreDispatchExecResult,
      valid: Boolean = true,
      addr: BigInt = 0x4400,
      data: BigInt = 0x1111,
      size: Int = 4,
      stid: Int = 4): Unit = {
    exec.poke(0.U.asTypeOf(exec))
    exec.valid.poke(valid.B)
    exec.addr.poke(addr.U)
    exec.data.poke(data.U)
    exec.size.poke(size.U)
    exec.peId.poke(2.U)
    exec.stid.poke(stid.U)
    exec.tid.poke(stid.U)
    exec.scalarIex.poke(true.B)
  }

  private def pokeScIdentity(
      identity: StoreDispatchScIdentity,
      bid: Int = 1,
      gid: Int = 2,
      rid: Int = 3,
      lsid: BigInt = 9,
      stid: Int = 4): Unit = {
    identity.stid.poke(stid.U)
    pokeRobId(identity.bid, bid)
    pokeRobId(identity.gid, gid)
    pokeRobId(identity.rid, rid)
    identity.lsIdFull.poke(lsid.U)
  }

  private def enqueueSc(dut: StoreDispatchSTQPath): Unit = {
    clearInputs(dut)
    pokeScPayload(dut.io.unsplitIn)
    dut.io.staEnqueueFire.expect(true.B)
    dut.clock.step()
    clearInputs(dut)
    dut.io.staQueueValid.expect(true.B)
  }

  private def enqueueSplitSc(dut: StoreDispatchSTQPath, staRid: Int = 3, stdRid: Int = 3): Unit = {
    clearInputs(dut)
    pokeScPayload(dut.io.staIn, storeType = StoreSplitStoreType.Addr, rid = staRid)
    pokeScPayload(dut.io.stdIn, storeType = StoreSplitStoreType.Data, rid = stdRid)
    dut.io.staEnqueueFire.expect(true.B)
    dut.io.stdEnqueueFire.expect(true.B)
    dut.clock.step()
    clearInputs(dut)
    dut.io.staQueueValid.expect(true.B)
    dut.io.stdQueueValid.expect(true.B)
  }

  private def enqueueSplitStore(dut: StoreDispatchSTQPath): Unit = {
    clearInputs(dut)
    pokeStorePayload(dut.io.staIn, StoreSplitStoreType.Addr)
    pokeStorePayload(dut.io.stdIn, StoreSplitStoreType.Data)
    dut.io.staEnqueueFire.expect(true.B)
    dut.io.stdEnqueueFire.expect(true.B)
    dut.clock.step()
    clearInputs(dut)
    dut.io.staQueueValid.expect(true.B)
    dut.io.stdQueueValid.expect(true.B)
  }

  test("reference path lets mergeable STD bypass a present STA when the STQ is allocation-full") {
    val rows = Seq(
      Some(waitEntry(req(0, storeType = Addr, bid = 3, lsId = 2))),
      Some(waitEntry(req(1, storeType = All, bid = 4, lsId = 0))))

    val result = decide(
      rows = rows,
      staReq = req(2, storeType = Addr, bid = 5, lsId = 0),
      stdReq = req(3, storeType = Data, bid = 3, lsId = 2),
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true)

    assert(!result.staProbe.ready)
    assert(result.stdProbe.ready)
    assert(result.stdProbe.canMerge)
    assert(!result.dispatch.selectedSta)
    assert(result.dispatch.selectedStd)
    assert(result.dispatch.blockedByStaInsert)
    assert(result.dispatch.stdBypassStaBlocked)
  }

  test("reference path keeps STA priority when both candidates can insert") {
    val rows = Seq(None, None)
    val result = decide(
      rows = rows,
      staReq = req(0, storeType = All, bid = 1, lsId = 0),
      stdReq = req(1, storeType = All, bid = 1, lsId = 1),
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true)

    assert(result.staProbe.ready)
    assert(result.stdProbe.ready)
    assert(result.dispatch.selectedSta)
    assert(!result.dispatch.selectedStd)
    assert(!result.dispatch.stdBypassStaBlocked)
  }

  test("reference path suppresses dispatch candidates during flush") {
    val rows = Seq(None, None)
    val result = decide(
      rows = rows,
      staReq = req(0),
      stdReq = req(1),
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true,
      flush = true)

    assert(!result.dispatch.staCandidate)
    assert(!result.dispatch.stdCandidate)
    assert(!result.dispatch.selectedSta)
    assert(!result.dispatch.selectedStd)
    assert(!result.dispatch.blockedByStaExec)
    assert(!result.dispatch.blockedByStdExec)
  }

  test("address permit backpressures STA while allowing a data-only STD fragment") {
    val rows = Seq(None, None)
    val result = decide(
      rows = rows,
      staReq = req(0, storeType = Addr),
      stdReq = req(1, storeType = Data),
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true,
      addressInsertPermit = false)

    assert(result.staProbe.ready)
    assert(result.stdProbe.ready)
    assert(!result.dispatch.selectedSta)
    assert(result.dispatch.selectedStd)
  }

  test("sim failed SC.W consumes the queued unsplit store without inserting an STQ row") {
    simulate(new StoreDispatchSTQPath(InterfaceParams(robEntries = 8), queueDepth = 2, entries = 2)) { dut =>
      enqueueSc(dut)

      pokeExec(dut.io.staExec)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(false.B)
      pokeScIdentity(dut.io.scResultIdentity)
      dut.io.scCandidate.expect(true.B)
      dut.io.scIdentityMatch.expect(true.B)
      dut.io.scSelectedMissDiscard.expect(true.B)
      dut.io.insertValid.expect(false.B)
      dut.io.staDequeueFire.expect(true.B)
      dut.io.stqResidentCount.expect(0.U)
      dut.clock.step()

      clearInputs(dut)
      dut.io.staQueueValid.expect(false.B)
      dut.io.stqResidentCount.expect(0.U)
      dut.io.stqOccupiedMask.expect(0.U)
    }
  }

  test("sim successful SC.W stalls under STQ backpressure then inserts one exact canonical row") {
    simulate(new StoreDispatchSTQPath(InterfaceParams(robEntries = 8), queueDepth = 2, entries = 2)) { dut =>
      enqueueSc(dut)

      dut.io.addressInsertPermit.poke(false.B)
      pokeExec(dut.io.staExec, addr = 0x4400, data = 0x1111)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(true.B)
      pokeScIdentity(dut.io.scResultIdentity)
      dut.io.scStoreData.poke(BigInt("aabbccdd", 16).U)
      dut.io.scCandidate.expect(true.B)
      dut.io.scIdentityMatch.expect(true.B)
      dut.io.scBlockedByInsert.expect(true.B)
      dut.io.scSelectedSuccess.expect(false.B)
      dut.io.staDequeueFire.expect(false.B)
      dut.clock.step()

      dut.io.addressInsertPermit.poke(true.B)
      pokeExec(dut.io.staExec, addr = 0x4400, data = 0x1111)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(true.B)
      pokeScIdentity(dut.io.scResultIdentity)
      dut.io.scStoreData.poke(BigInt("aabbccdd", 16).U)
      dut.io.scSelectedSuccess.expect(true.B)
      dut.io.insertValid.expect(true.B)
      dut.io.insert.storeType.expect(STQStoreType.All)
      dut.io.insert.addr.expect(0x4400.U)
      dut.io.insert.data.expect(BigInt("aabbccdd", 16).U)
      dut.io.insert.size.expect(4.U)
      dut.io.staDequeueFire.expect(true.B)
      dut.clock.step()

      clearInputs(dut)
      dut.io.staQueueValid.expect(false.B)
      dut.io.stqResidentCount.expect(1.U)
      dut.io.stqRows(0).valid.expect(true.B)
      dut.io.stqRows(0).storeType.expect(STQStoreType.All)
      dut.io.stqRows(0).addrReady.expect(true.B)
      dut.io.stqRows(0).dataReady.expect(true.B)
      dut.io.stqRows(0).stid.expect(4.U)
      dut.io.stqRows(0).bid.value.expect(1.U)
      dut.io.stqRows(0).gid.value.expect(2.U)
      dut.io.stqRows(0).rid.value.expect(3.U)
      dut.io.stqRows(0).lsIdFull.expect(9.U)
      dut.io.stqRows(0).tSeq.value.expect(5.U)
      dut.io.stqRows(0).uSeq.value.expect(6.U)
      dut.io.stqRows(0).tuDstValid.expect(true.B)
      dut.io.stqRows(0).tuDstKind.expect(DestinationKind.U)
      dut.io.stqRows(0).pc.expect(0x8000.U)
      dut.io.stqRows(0).addr.expect(0x4400.U)
      dut.io.stqRows(0).data.expect(BigInt("aabbccdd", 16).U)
      dut.io.stqRows(0).size.expect(4.U)
    }
  }

  test("sim split SC.W success holds both halves under STQ backpressure then inserts one canonical row") {
    simulate(new StoreDispatchSTQPath(InterfaceParams(robEntries = 8), queueDepth = 2, entries = 2)) { dut =>
      enqueueSplitSc(dut)

      dut.io.addressInsertPermit.poke(false.B)
      pokeExec(dut.io.staExec, addr = 0x5500, data = 0x2222)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(true.B)
      pokeScIdentity(dut.io.scResultIdentity)
      dut.io.scStoreData.poke(BigInt("11223344", 16).U)
      dut.io.scCandidate.expect(true.B)
      dut.io.scIdentityMatch.expect(true.B)
      dut.io.scBlockedByInsert.expect(true.B)
      dut.io.staDequeueFire.expect(false.B)
      dut.io.stdDequeueFire.expect(false.B)
      dut.clock.step()

      dut.io.addressInsertPermit.poke(true.B)
      pokeExec(dut.io.staExec, addr = 0x5500, data = 0x2222)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(true.B)
      pokeScIdentity(dut.io.scResultIdentity)
      dut.io.scStoreData.poke(BigInt("11223344", 16).U)
      dut.io.scSelectedSuccess.expect(true.B)
      dut.io.insertValid.expect(true.B)
      dut.io.insert.storeType.expect(STQStoreType.All)
      dut.io.insert.addr.expect(0x5500.U)
      dut.io.insert.data.expect(BigInt("11223344", 16).U)
      dut.io.staDequeueFire.expect(true.B)
      dut.io.stdDequeueFire.expect(true.B)
      dut.clock.step()

      clearInputs(dut)
      dut.io.staQueueValid.expect(false.B)
      dut.io.stdQueueValid.expect(false.B)
      dut.io.stqResidentCount.expect(1.U)
      dut.io.stqRows(0).valid.expect(true.B)
      dut.io.stqRows(0).storeType.expect(STQStoreType.All)
      dut.io.stqRows(0).addrReady.expect(true.B)
      dut.io.stqRows(0).dataReady.expect(true.B)
      dut.io.stqRows(0).addr.expect(0x5500.U)
      dut.io.stqRows(0).data.expect(BigInt("11223344", 16).U)
      dut.io.stqRows(0).rid.value.expect(3.U)
      dut.io.stqRows(0).lsIdFull.expect(9.U)
    }
  }

  test("sim split SC.W failure dequeues both halves atomically without inserting") {
    simulate(new StoreDispatchSTQPath(InterfaceParams(robEntries = 8), queueDepth = 2, entries = 2)) { dut =>
      enqueueSplitSc(dut)

      pokeExec(dut.io.staExec, addr = 0x6600)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(false.B)
      pokeScIdentity(dut.io.scResultIdentity)
      dut.io.scCandidate.expect(true.B)
      dut.io.scIdentityMatch.expect(true.B)
      dut.io.scSelectedMissDiscard.expect(true.B)
      dut.io.insertValid.expect(false.B)
      dut.io.staDequeueFire.expect(true.B)
      dut.io.stdDequeueFire.expect(true.B)
      dut.clock.step()

      clearInputs(dut)
      dut.io.staQueueValid.expect(false.B)
      dut.io.stdQueueValid.expect(false.B)
      dut.io.stqResidentCount.expect(0.U)
      dut.io.stqOccupiedMask.expect(0.U)
    }
  }

  test("sim ordinary STD drains before split SC.W miss discard when SC STA reaches the head first") {
    simulate(new StoreDispatchSTQPath(InterfaceParams(robEntries = 8), queueDepth = 4, entries = 4)) { dut =>
      enqueueSplitSc(dut)
      enqueueSplitStore(dut)
      enqueueSplitSc(dut, staRid = 5, stdRid = 5)
      clearInputs(dut)
      dut.io.staQueueCount.expect(3.U)
      dut.io.stdQueueCount.expect(3.U)

      pokeExec(dut.io.staExec, addr = 0x4400)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(true.B)
      pokeScIdentity(dut.io.scResultIdentity)
      dut.io.scStoreData.poke(BigInt("11223344", 16).U)
      dut.io.scSelectedSuccess.expect(true.B)
      dut.io.staDequeueFire.expect(true.B)
      dut.io.stdDequeueFire.expect(true.B)
      dut.io.insertValid.expect(true.B)
      dut.clock.step()

      clearInputs(dut)
      pokeExec(dut.io.staExec, addr = 0x5500)
      pokeExec(dut.io.stdExec, addr = 0x5500, data = 0x123)
      dut.io.selectedSta.expect(true.B)
      dut.io.selectedStd.expect(false.B)
      dut.io.staDequeueFire.expect(true.B)
      dut.io.stdDequeueFire.expect(false.B)
      dut.io.insertValid.expect(true.B)
      dut.io.insert.storeType.expect(STQStoreType.Addr)
      dut.clock.step()

      clearInputs(dut)
      pokeExec(dut.io.staExec, addr = 0x6600)
      pokeExec(dut.io.stdExec, addr = 0x5500, data = 0x123)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(false.B)
      pokeScIdentity(dut.io.scResultIdentity, rid = 5)
      dut.io.scCandidate.expect(true.B)
      dut.io.scIdentityMatch.expect(false.B)
      dut.io.scSelectedMissDiscard.expect(false.B)
      dut.io.stdCandidate.expect(true.B)
      dut.io.selectedStd.expect(true.B)
      dut.io.staDequeueFire.expect(false.B)
      dut.io.stdDequeueFire.expect(true.B)
      dut.io.insertValid.expect(true.B)
      dut.io.insert.storeType.expect(STQStoreType.Data)
      dut.io.insert.data.expect(0x123.U)
      dut.clock.step()

      clearInputs(dut)
      pokeExec(dut.io.staExec, addr = 0x6600)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(false.B)
      pokeScIdentity(dut.io.scResultIdentity, rid = 5)
      dut.io.scCandidate.expect(true.B)
      dut.io.scIdentityMatch.expect(true.B)
      dut.io.scSelectedMissDiscard.expect(true.B)
      dut.io.insertValid.expect(false.B)
      dut.io.staDequeueFire.expect(true.B)
      dut.io.stdDequeueFire.expect(true.B)
      dut.io.stqResidentCount.expect(2.U)
      dut.clock.step()

      clearInputs(dut)
      dut.io.staQueueValid.expect(false.B)
      dut.io.stdQueueValid.expect(false.B)
      dut.io.stqResidentCount.expect(2.U)
      dut.io.stqRows(1).valid.expect(true.B)
      dut.io.stqRows(1).storeType.expect(STQStoreType.All)
      dut.io.stqRows(1).addrReady.expect(true.B)
      dut.io.stqRows(1).dataReady.expect(true.B)
      dut.io.stqRows(1).addr.expect(0x5500.U)
      dut.io.stqRows(1).data.expect(0x123.U)
    }
  }

  test("sim split SC.W with wrong STD identity consumes neither half") {
    simulate(new StoreDispatchSTQPath(InterfaceParams(robEntries = 8), queueDepth = 2, entries = 2)) { dut =>
      enqueueSplitSc(dut, stdRid = 4)

      pokeExec(dut.io.staExec, addr = 0x7700)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(false.B)
      pokeScIdentity(dut.io.scResultIdentity, rid = 3)
      dut.io.scCandidate.expect(true.B)
      dut.io.scIdentityMatch.expect(false.B)
      dut.io.scBlockedByResult.expect(true.B)
      dut.io.scSelectedMissDiscard.expect(false.B)
      dut.io.insertValid.expect(false.B)
      dut.io.staDequeueFire.expect(false.B)
      dut.io.stdDequeueFire.expect(false.B)
      dut.clock.step()

      clearInputs(dut)
      dut.io.staQueueValid.expect(true.B)
      dut.io.stdQueueValid.expect(true.B)
      dut.io.staQueueCount.expect(1.U)
      dut.io.stdQueueCount.expect(1.U)
      dut.io.stqResidentCount.expect(0.U)
    }
  }

  test("sim SC.W result with wrong full identity neither consumes queues nor inserts") {
    simulate(new StoreDispatchSTQPath(InterfaceParams(robEntries = 8), queueDepth = 2, entries = 2)) { dut =>
      enqueueSc(dut)

      pokeExec(dut.io.staExec)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(false.B)
      pokeScIdentity(dut.io.scResultIdentity, rid = 4)
      dut.io.scCandidate.expect(true.B)
      dut.io.scIdentityMatch.expect(false.B)
      dut.io.scBlockedByResult.expect(true.B)
      dut.io.scSelectedMissDiscard.expect(false.B)
      dut.io.staDequeueFire.expect(false.B)
      dut.io.insertValid.expect(false.B)
      dut.clock.step()

      clearInputs(dut)
      dut.io.staQueueValid.expect(true.B)
      dut.io.staQueueCount.expect(1.U)
      dut.io.stqResidentCount.expect(0.U)
    }
  }

  test("sim flush cancels a pending SC.W without discard or insert") {
    simulate(new StoreDispatchSTQPath(InterfaceParams(robEntries = 8), queueDepth = 2, entries = 2)) { dut =>
      enqueueSc(dut)

      pokeExec(dut.io.staExec)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(false.B)
      pokeScIdentity(dut.io.scResultIdentity)
      dut.io.flush.req.valid.poke(true.B)
      dut.io.scCandidate.expect(false.B)
      dut.io.scSelectedMissDiscard.expect(false.B)
      dut.io.insertValid.expect(false.B)
      dut.io.staDequeueFire.expect(false.B)
      dut.clock.step()

      clearInputs(dut)
      dut.io.staQueueValid.expect(false.B)
      dut.io.stqResidentCount.expect(0.U)
    }
  }

  test("StoreDispatchSTQPath IO preserves queue, request, and STQ counter widths") {
    val p = InterfaceParams(robEntries = 8)
    val io = new StoreDispatchSTQPathIO(p, queueDepth = 4, entries = 8)

    assert(io.queueFlushValid.getWidth == 1)
    assert(io.addressInsertPermit.getWidth == 1)
    assert(io.staReady.getWidth == 1)
    assert(io.stdReady.getWidth == 1)
    assert(io.staQueueCount.getWidth == 3)
    assert(io.stdQueueCount.getWidth == 3)
    assert(io.markCommitIndex.getWidth == 3)
    assert(io.staRequest.bid.value.getWidth == 3)
    assert(io.stdRequest.lsId.value.getWidth == 3)
    assert(io.staRequest.tSeq.value.getWidth == 5)
    assert(io.staRequest.pc.getWidth == 64)
    assert(io.insertIntentValid.getWidth == 1)
    assert(io.insertIntent.pc.getWidth == 64)
    assert(io.staIn.tSeq.value.getWidth == 5)
    assert(io.stdQueue.uSeq.value.getWidth == 5)
    assert(io.lsuTULinkSource.tSeq.value.getWidth == 5)
    assert(io.lsuTULinkSourceMatched.getWidth == 1)
    assert(io.stqResidentCount.getWidth == 4)
    assert(io.stqOutstandingWaitCount.getWidth == 4)
    assert(io.stqRows.length == 8)
    assert(io.stqRows.head.pc.getWidth == 64)
    assert(io.staExec.addr.getWidth == 64)
  }

  test("StoreDispatchSTQPath keeps physical row sizing independent of ROB identity sizing") {
    val p = InterfaceParams(robEntries = 8)
    val io = new StoreDispatchSTQPathIO(p, queueDepth = 4, entries = 16)

    assert(io.markCommitIndex.getWidth == 4)
    assert(io.commitFreeMask.getWidth == 16)
    assert(io.commitFreeCount.getWidth == 5)
    assert(io.stqRows.length == 16)
    assert(io.stqOccupiedMask.getWidth == 16)
    assert(io.staRequest.bid.value.getWidth == 3)
    assert(io.stdRequest.lsId.value.getWidth == 3)
    assert(io.stqRows.head.bid.value.getWidth == 3)

    val sv = ChiselStage.emitSystemVerilog(
      new StoreDispatchSTQPath(p, queueDepth = 4, entries = 16)
    )
    assert(sv.contains("io_stqRows_15_status"))
    assert(sv.contains("io_markCommitIndex"))
    assert(sv.contains("io_commitFreeMask"))
  }

  test("StoreDispatchSTQPath elaborates with queues, probes, bridge, and STQ bank") {
    val sv = ChiselStage.emitSystemVerilog(new StoreDispatchSTQPath(InterfaceParams(robEntries = 8), queueDepth = 4, entries = 8))

    assert(sv.contains("module StoreDispatchSTQPath"))
    assert(sv.contains("StoreDispatchQueues"))
    assert(sv.contains("io_queueFlushValid"))
    assert(sv.contains("io_addressInsertPermit"))
    assert(sv.contains("StoreDispatchToSTQ"))
    assert(sv.contains("STQInsertProbe"))
    assert(sv.contains("STQEntryBank"))
    assert(sv.contains("io_staIn_tSeq_value"))
    assert(sv.contains("io_stdQueue_uSeq_value"))
    assert(sv.contains("io_stqRows_0_pc"))
    assert(sv.contains("io_lsuTULinkSource_valid"))
    assert(sv.contains("io_lsuTULinkSourceMatched"))
    assert(sv.contains("io_stdBypassStaBlocked"))
    assert(sv.contains("io_insertIntentValid"))
  }
}
