package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.{DestinationKind, InterfaceParams}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.rename.{StoreSplitIssuePayload, StoreSplitStoreType}
import linxcore.rob.ROBID
import org.scalatest.funsuite.AnyFunSuite

object StoreDispatchToSTQReference {
  final case class Decision(
      staCandidate: Boolean,
      stdCandidate: Boolean,
      selectedSta: Boolean,
      selectedStd: Boolean,
      blockedByStaExec: Boolean,
      blockedByStdExec: Boolean,
      blockedByStaInsert: Boolean,
      blockedByStdInsert: Boolean,
      stdBypassStaBlocked: Boolean)

  def decide(
      staValid: Boolean,
      stdValid: Boolean,
      staExecValid: Boolean,
      stdExecValid: Boolean,
      staInsertReady: Boolean,
      stdInsertReady: Boolean,
      flush: Boolean = false): Decision = {
    val staCandidate = !flush && staValid && staExecValid
    val stdCandidate = !flush && stdValid && stdExecValid
    val selectedSta = staCandidate && staInsertReady
    val selectedStd = !selectedSta && stdCandidate && stdInsertReady

    Decision(
      staCandidate = staCandidate,
      stdCandidate = stdCandidate,
      selectedSta = selectedSta,
      selectedStd = selectedStd,
      blockedByStaExec = !flush && staValid && !staExecValid,
      blockedByStdExec = !flush && stdValid && !stdExecValid,
      blockedByStaInsert = staCandidate && !staInsertReady,
      blockedByStdInsert = stdCandidate && !stdInsertReady && !selectedSta,
      stdBypassStaBlocked = selectedStd && staCandidate && !staInsertReady
    )
  }
}

class StoreDispatchToSTQSpec extends AnyFunSuite with ChiselSim {
  import StoreDispatchToSTQReference._

  private def pokeRobId(id: ROBID, value: Int, valid: Boolean = true): Unit = {
    id.valid.poke(valid.B)
    id.wrap.poke(false.B)
    id.value.poke(value.U)
  }

  private def clearBridge(dut: StoreDispatchToSTQ): Unit = {
    dut.io.flushValid.poke(false.B)
    dut.io.staValid.poke(false.B)
    dut.io.stdValid.poke(false.B)
    dut.io.sta.poke(0.U.asTypeOf(dut.io.sta))
    dut.io.std.poke(0.U.asTypeOf(dut.io.std))
    dut.io.staExec.poke(0.U.asTypeOf(dut.io.staExec))
    dut.io.stdExec.poke(0.U.asTypeOf(dut.io.stdExec))
    dut.io.staInsertReady.poke(true.B)
    dut.io.stdInsertReady.poke(true.B)
    dut.io.scResultValid.poke(false.B)
    dut.io.scResultSuccess.poke(false.B)
    dut.io.scResultIdentity.poke(0.U.asTypeOf(dut.io.scResultIdentity))
    dut.io.scStoreData.poke(0.U)
  }

  private def pokeScPayload(
      payload: StoreSplitIssuePayload,
      storeType: StoreSplitStoreType.Type,
      rid: Int = 3): Unit = {
    payload.poke(0.U.asTypeOf(payload))
    payload.valid.poke(true.B)
    payload.storeType.poke(storeType)
    payload.uop.valid.poke(true.B)
    payload.uop.opcode.poke(FrontendOpcodeDecodeTable.OP_SC_W.U)
    payload.uop.threadId.poke(4.U)
    payload.uop.peId.poke(2.U)
    payload.uop.pc.poke(0x8000.U)
    payload.uop.lsid.poke(9.U)
    pokeRobId(payload.uop.bid, 1)
    pokeRobId(payload.uop.gid, 2)
    pokeRobId(payload.uop.rid, rid)
    pokeRobId(payload.tSeq, 5)
    pokeRobId(payload.uSeq, 6)
  }

  private def pokeStorePayload(
      payload: StoreSplitIssuePayload,
      storeType: StoreSplitStoreType.Type,
      rid: Int = 7,
      lsid: BigInt = 11): Unit = {
    payload.poke(0.U.asTypeOf(payload))
    payload.valid.poke(true.B)
    payload.storeType.poke(storeType)
    payload.uop.valid.poke(true.B)
    payload.uop.opcode.poke(FrontendOpcodeDecodeTable.OP_SW.U)
    payload.uop.isStore.poke(true.B)
    payload.uop.threadId.poke(4.U)
    payload.uop.peId.poke(2.U)
    payload.uop.pc.poke(0x9000.U)
    payload.uop.lsid.poke(lsid.U)
    pokeRobId(payload.uop.bid, 1)
    pokeRobId(payload.uop.gid, 2)
    pokeRobId(payload.uop.rid, rid)
    pokeRobId(payload.tSeq, 8)
    pokeRobId(payload.uSeq, 9)
  }

  private def pokeExec(
      exec: StoreDispatchExecResult,
      valid: Boolean = true,
      addr: BigInt = 0x4400,
      data: BigInt = 0x1111): Unit = {
    exec.poke(0.U.asTypeOf(exec))
    exec.valid.poke(valid.B)
    exec.addr.poke(addr.U)
    exec.data.poke(data.U)
    exec.size.poke(4.U)
    exec.peId.poke(2.U)
    exec.stid.poke(4.U)
    exec.tid.poke(4.U)
    exec.scalarIex.poke(true.B)
  }

  private def pokeScIdentity(identity: StoreDispatchScIdentity, rid: Int = 3): Unit = {
    identity.stid.poke(4.U)
    pokeRobId(identity.bid, 1)
    pokeRobId(identity.gid, 2)
    pokeRobId(identity.rid, rid)
    identity.lsIdFull.poke(9.U)
  }

  test("reference gives executed STA priority when both halves can insert") {
    val decision = decide(
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true,
      staInsertReady = true,
      stdInsertReady = true)

    assert(decision.staCandidate)
    assert(decision.stdCandidate)
    assert(decision.selectedSta)
    assert(!decision.selectedStd)
    assert(!decision.stdBypassStaBlocked)
  }

  test("reference allows STD to bypass a present STA only when STA cannot insert") {
    val decision = decide(
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true,
      staInsertReady = false,
      stdInsertReady = true)

    assert(decision.staCandidate)
    assert(decision.stdCandidate)
    assert(!decision.selectedSta)
    assert(decision.selectedStd)
    assert(decision.blockedByStaInsert)
    assert(decision.stdBypassStaBlocked)
  }

  test("reference reports execution-result backpressure separately from insert backpressure") {
    val decision = decide(
      staValid = true,
      stdValid = true,
      staExecValid = false,
      stdExecValid = false,
      staInsertReady = true,
      stdInsertReady = true)

    assert(!decision.staCandidate)
    assert(!decision.stdCandidate)
    assert(!decision.selectedSta)
    assert(!decision.selectedStd)
    assert(decision.blockedByStaExec)
    assert(decision.blockedByStdExec)
    assert(!decision.blockedByStaInsert)
    assert(!decision.blockedByStdInsert)
  }

  test("reference suppresses candidates and dequeue on flush") {
    val decision = decide(
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true,
      staInsertReady = true,
      stdInsertReady = true,
      flush = true)

    assert(!decision.staCandidate)
    assert(!decision.stdCandidate)
    assert(!decision.selectedSta)
    assert(!decision.selectedStd)
    assert(!decision.blockedByStaExec)
    assert(!decision.blockedByStdExec)
  }

  test("sim split SC.W with missing STD half consumes neither side and emits no insert") {
    simulate(new StoreDispatchToSTQ(InterfaceParams(robEntries = 8), entries = 8)) { dut =>
      clearBridge(dut)
      dut.io.staValid.poke(true.B)
      pokeScPayload(dut.io.sta, StoreSplitStoreType.Addr)
      pokeExec(dut.io.staExec)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(false.B)
      pokeScIdentity(dut.io.scResultIdentity)

      dut.io.scCandidate.expect(true.B)
      dut.io.scIdentityMatch.expect(false.B)
      dut.io.scBlockedByResult.expect(true.B)
      dut.io.staCandidate.expect(false.B)
      dut.io.stdCandidate.expect(false.B)
      dut.io.selectedSta.expect(false.B)
      dut.io.selectedStd.expect(false.B)
      dut.io.staDequeueReady.expect(false.B)
      dut.io.stdDequeueReady.expect(false.B)
      dut.io.insertValid.expect(false.B)
    }
  }

  test("sim split SC.W head lets an older ordinary STD half drain before SC pair is ready") {
    simulate(new StoreDispatchToSTQ(InterfaceParams(robEntries = 8), entries = 8)) { dut =>
      clearBridge(dut)
      dut.io.staValid.poke(true.B)
      dut.io.stdValid.poke(true.B)
      pokeScPayload(dut.io.sta, StoreSplitStoreType.Addr)
      pokeStorePayload(dut.io.std, StoreSplitStoreType.Data)
      pokeExec(dut.io.staExec, addr = 0x4400)
      pokeExec(dut.io.stdExec, addr = 0x5500, data = 0x123)
      dut.io.scResultValid.poke(true.B)
      dut.io.scResultSuccess.poke(false.B)
      pokeScIdentity(dut.io.scResultIdentity)

      dut.io.scCandidate.expect(true.B)
      dut.io.scIdentityMatch.expect(false.B)
      dut.io.scBlockedByResult.expect(true.B)
      dut.io.staCandidate.expect(false.B)
      dut.io.stdCandidate.expect(true.B)
      dut.io.selectedSta.expect(false.B)
      dut.io.selectedStd.expect(true.B)
      dut.io.staDequeueReady.expect(false.B)
      dut.io.stdDequeueReady.expect(true.B)
      dut.io.insertValid.expect(true.B)
      dut.io.insert.storeType.expect(STQStoreType.Data)
      dut.io.insert.addr.expect(0x5500.U)
      dut.io.insert.data.expect(0x123.U)
    }
  }

  test("StoreDispatchToSTQ IO preserves STQ request widths and store type order") {
    val p = InterfaceParams(robEntries = 8)
    val io = new StoreDispatchToSTQIO(p, entries = 8)

    assert(StoreSplitStoreType.All.asUInt.litValue == 0)
    assert(StoreSplitStoreType.Addr.asUInt.litValue == 1)
    assert(StoreSplitStoreType.Data.asUInt.litValue == 2)
    assert(STQStoreType.All.asUInt.litValue == 0)
    assert(STQStoreType.Addr.asUInt.litValue == 1)
    assert(STQStoreType.Data.asUInt.litValue == 2)
    assert(io.staDequeueReady.getWidth == 1)
    assert(io.stdDequeueReady.getWidth == 1)
    assert(io.insertValid.getWidth == 1)
    assert(io.insert.bid.value.getWidth == 3)
    assert(io.insert.lsId.value.getWidth == 3)
    assert(io.insert.lsIdFull.getWidth == 32)
    assert(io.insert.tSeq.value.getWidth == 5)
    assert(io.insert.uSeq.value.getWidth == 5)
    assert(io.insert.tuDstValid.getWidth == 1)
    assert(io.insert.tuDstKind.getWidth == DestinationKind.getWidth)
    assert(io.sta.tSeq.value.getWidth == 5)
    assert(io.std.uSeq.value.getWidth == 5)
    assert(io.sta.tuDstKind.getWidth == DestinationKind.getWidth)
    assert(io.insert.pc.getWidth == 64)
    assert(io.staRequest.pc.getWidth == 64)
    assert(io.insert.addr.getWidth == 64)
    assert(io.insert.data.getWidth == 64)
    assert(io.insert.size.getWidth == 4)
    assert(io.staExec.peId.getWidth == 8)
    assert(io.stdExec.simtLane.getWidth == 8)
  }

  test("StoreDispatchToSTQ elaborates as a separate STQ insert bridge") {
    val sv = ChiselStage.emitSystemVerilog(new StoreDispatchToSTQ(InterfaceParams(robEntries = 8), entries = 8))

    assert(sv.contains("module StoreDispatchToSTQ"))
    assert(sv.contains("io_selectedSta"))
    assert(sv.contains("io_selectedStd"))
    assert(sv.contains("io_insertValid"))
    assert(sv.contains("io_sta_tSeq_value"))
    assert(sv.contains("io_std_uSeq_value"))
    assert(sv.contains("io_insert_tSeq_value"))
    assert(sv.contains("io_insert_tuDstValid"))
    assert(sv.contains("io_insert_pc"))
    assert(sv.contains("io_stdBypassStaBlocked"))
  }

  test("full LSID width is independent of the ROB projection") {
    val io = new StoreDispatchToSTQIO(InterfaceParams(robEntries = 8, lsidWidth = 40), entries = 8)

    assert(io.insert.lsId.value.getWidth == 3)
    assert(io.insert.lsIdFull.getWidth == 40)
  }
}
