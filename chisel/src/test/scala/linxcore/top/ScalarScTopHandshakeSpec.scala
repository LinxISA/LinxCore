package linxcore.top

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.{DestinationKind, InterfaceParams, OperandClass}
import linxcore.commit.CommitTraceParams
import linxcore.lsu.STQCommitDrainRequest
import org.scalatest.funsuite.AnyFunSuite

class ScalarScTopHandshakeSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams(robEntries = 8, commitWidth = 2)
  private val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)

  private def clear(dut: ScalarScTopHandshake): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.issueFire.poke(false.B)
    dut.io.issueUop.poke(0.U.asTypeOf(dut.io.issueUop))
    dut.io.issueAddr.poke(0.U)
    dut.io.issueData.poke(0.U)
    dut.io.ownerScReqReady.poke(true.B)
    dut.io.ownerScReqAccepted.poke(false.B)
    dut.io.ownerScSuccess.poke(false.B)
    dut.io.ownerScStatus.poke(1.U)
    dut.io.ownerScStoreData.poke(0.U)
    dut.io.ownerScCompleteFire.poke(false.B)
    dut.io.storeScSelectedSuccess.poke(false.B)
    dut.io.storeScSelectedMissDiscard.poke(false.B)
    dut.io.storeStqInsertAccepted.poke(false.B)
    dut.io.scbAcceptedMask.poke(0.U)
    for (idx <- 0 until 2) {
      dut.io.scbAcceptedReqs(idx).poke(0.U.asTypeOf(dut.io.scbAcceptedReqs(idx)))
    }
    dut.io.serviceCompleteValid.poke(false.B)
  }

  private def pokeRobId(id: linxcore.rob.ROBID, value: Int, wrap: Boolean = false): Unit = {
    id.valid.poke(true.B)
    id.wrap.poke(wrap.B)
    id.value.poke(value.U)
  }

  private def pokeIssue(dut: ScalarScTopHandshake, rid: Int = 3, lsid: Int = 9): Unit = {
    dut.io.issueUop.valid.poke(true.B)
    dut.io.issueUop.threadId.poke(0.U)
    dut.io.issueUop.pc.poke(0x1000.U)
    dut.io.issueUop.insnRaw.poke("h2000000b".U)
    dut.io.issueUop.insnLen.poke(4.U)
    dut.io.issueUop.lsid.poke(lsid.U)
    dut.io.issueUop.blockBidValid.poke(true.B)
    dut.io.issueUop.blockBid.poke(0x55.U)
    pokeRobId(dut.io.issueUop.bid, 1)
    pokeRobId(dut.io.issueUop.gid, 2)
    pokeRobId(dut.io.issueUop.rid, rid)
    dut.io.issueUop.src(0).valid.poke(true.B)
    dut.io.issueUop.src(0).operandClass.poke(OperandClass.P)
    dut.io.issueUop.src(0).archTag.poke(5.U)
    dut.io.issueUop.src(1).valid.poke(true.B)
    dut.io.issueUop.src(1).operandClass.poke(OperandClass.P)
    dut.io.issueUop.src(1).archTag.poke(6.U)
    dut.io.issueUop.dst(0).valid.poke(true.B)
    dut.io.issueUop.dst(0).kind.poke(DestinationKind.Gpr)
    dut.io.issueUop.dst(0).archTag.poke(7.U)
    dut.io.issueUop.dst(0).physTag.poke(11.U)
    dut.io.issueAddr.poke(0x2044.U)
    dut.io.issueData.poke(0xdeadbeefL.U)
  }

  private def acceptIssue(dut: ScalarScTopHandshake, rid: Int = 3, lsid: Int = 9): Unit = {
    clear(dut)
    pokeIssue(dut, rid = rid, lsid = lsid)
    dut.io.issueFire.poke(true.B)
    dut.io.ownerScReqAccepted.poke(true.B)
    dut.io.ownerScReqValid.expect(true.B)
    dut.clock.step()
  }

  private def pokeAcceptedScb(req: STQCommitDrainRequest, rid: Int = 3, lsid: Int = 9): Unit = {
    req.valid.poke(true.B)
    req.stid.poke(0.U)
    pokeRobId(req.bid, 1)
    pokeRobId(req.gid, 2)
    pokeRobId(req.rid, rid)
    req.lsId.poke(lsid.U)
  }

  private def expectNoTerminalSameCycle(dut: ScalarScTopHandshake): Unit = {
    dut.io.completeValid.expect(false.B)
    dut.io.releaseValid.expect(false.B)
    dut.io.writebackValid.expect(false.B)
  }

  test("miss discard acceptance registers terminal outcome before ROB, issue release, and writeback") {
    simulate(new ScalarScTopHandshake(p, trace, scbRequestCount = 2, stqEntries = 8)) { dut =>
      clear(dut)
      acceptIssue(dut)

      clear(dut)
      dut.io.storeScSelectedMissDiscard.poke(true.B)
      dut.io.ownerScCompleteFire.poke(true.B)
      dut.io.ownerScSuccess.poke(false.B)
      dut.io.ownerScStatus.poke(1.U)
      dut.io.ownerScCommitReady.expect(true.B)
      expectNoTerminalSameCycle(dut)
      dut.clock.step()

      clear(dut)
      dut.io.completeValid.expect(true.B)
      dut.io.releaseValid.expect(true.B)
      dut.io.writebackValid.expect(true.B)
      dut.io.completeRobValue.expect(3.U)
      dut.io.writebackTag.expect(11.U)
      dut.io.writebackData.expect(1.U)
      dut.io.completeRow.dst.data.expect(1.U)
      dut.io.completeRow.mem.valid.expect(false.B)
      dut.clock.step()
      clear(dut)
      dut.io.completeValid.expect(false.B)
    }
  }

  test("STQ insert acceptance registers successful terminal outcome without waiting for SCB") {
    simulate(new ScalarScTopHandshake(p, trace, scbRequestCount = 2, stqEntries = 8)) { dut =>
      clear(dut)
      acceptIssue(dut, rid = 4, lsid = 12)

      clear(dut)
      dut.io.storeScSelectedSuccess.poke(true.B)
      dut.io.storeStqInsertAccepted.poke(true.B)
      dut.io.ownerScCompleteFire.poke(true.B)
      dut.io.ownerScSuccess.poke(true.B)
      dut.io.ownerScStatus.poke(1.U)
      dut.io.ownerScStoreData.poke(0xcafebabeL.U)
      dut.io.ownerScCommitReady.expect(true.B)
      dut.io.scbAcceptedDiagnostic.expect(false.B)
      expectNoTerminalSameCycle(dut)
      dut.clock.step()

      clear(dut)
      dut.io.serviceCompleteValid.poke(true.B)
      dut.io.completeValid.expect(false.B)
      dut.io.releaseValid.expect(false.B)
      dut.io.writebackValid.expect(false.B)
      dut.clock.step()

      clear(dut)
      dut.io.completeValid.expect(true.B)
      dut.io.releaseBid.value.expect(1.U)
      dut.io.releaseRid.value.expect(4.U)
      dut.io.releaseStid.expect(0.U)
      dut.io.writebackValid.expect(true.B)
      dut.io.writebackData.expect(0.U)
      dut.io.completeRow.dst.data.expect(0.U)
      dut.io.completeRow.mem.valid.expect(true.B)
      dut.io.completeRow.mem.addr.expect(0x2044.U)
      dut.io.completeRow.mem.wdata.expect(0xdeadbeefL.U)
      dut.clock.step()
    }
  }

  test("successful SC retains commit-ready after STQ insert when owner completion is backpressured") {
    simulate(new ScalarScTopHandshake(p, trace, scbRequestCount = 2, stqEntries = 8)) { dut =>
      clear(dut)
      acceptIssue(dut, rid = 5, lsid = 13)

      clear(dut)
      dut.io.storeScSelectedSuccess.poke(true.B)
      dut.io.storeStqInsertAccepted.poke(true.B)
      dut.io.ownerScCompleteFire.poke(false.B)
      dut.io.ownerScCommitReady.expect(true.B)
      expectNoTerminalSameCycle(dut)
      dut.clock.step()

      clear(dut)
      dut.io.ownerScCommitReady.expect(true.B)
      dut.io.inserted.expect(true.B)
      dut.io.scbAcceptedMask.poke(1.U)
      pokeAcceptedScb(dut.io.scbAcceptedReqs(0), rid = 5, lsid = 13)
      dut.io.scbAcceptedDiagnostic.expect(true.B)
      dut.io.ownerScCompleteFire.poke(true.B)
      dut.io.ownerScSuccess.poke(true.B)
      dut.io.ownerScStatus.poke(0.U)
      expectNoTerminalSameCycle(dut)
      dut.clock.step()

      clear(dut)
      dut.io.completeValid.expect(true.B)
      dut.io.completeRobValue.expect(5.U)
      dut.io.writebackData.expect(0.U)
      dut.clock.step()
    }
  }

  test("flush cancels a registered terminal without same-cycle release or writeback") {
    simulate(new ScalarScTopHandshake(p, trace, scbRequestCount = 2, stqEntries = 8)) { dut =>
      clear(dut)
      acceptIssue(dut)

      clear(dut)
      dut.io.storeScSelectedMissDiscard.poke(true.B)
      dut.io.ownerScCompleteFire.poke(true.B)
      dut.io.ownerScStatus.poke(1.U)
      dut.clock.step()

      clear(dut)
      dut.io.flush.poke(true.B)
      dut.io.completeValid.expect(false.B)
      dut.io.releaseValid.expect(false.B)
      dut.io.writebackValid.expect(false.B)
      dut.clock.step()

      clear(dut)
      dut.io.completeValid.expect(false.B)
      dut.io.releaseValid.expect(false.B)
      dut.io.active.expect(false.B)
    }
  }

  test("Chisel ScalarScTopHandshake elaborates a registered terminal boundary") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarScTopHandshake(p, trace, scbRequestCount = 2, stqEntries = 8))

    assert(sv.contains("module ScalarScTopHandshake"))
    assert(sv.contains("terminalPending"))
    assert(sv.contains("terminalStatus"))
    assert(sv.contains("io_completeValid"))
  }
}
