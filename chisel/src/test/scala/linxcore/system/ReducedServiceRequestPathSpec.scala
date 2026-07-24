package linxcore.system

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ReducedServiceRequestPathSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams(robEntries = 8, physRegWidth = 7, blockBidWidth = 64)
  private val trace = CommitTraceParams(robValueWidth = 3)

  private def clear(dut: ReducedServiceRequestPath): Unit = {
    dut.io.enqueueValid.poke(false.B)
    dut.io.enqueueIdentity.poke(0.U.asTypeOf(dut.io.enqueueIdentity))
    dut.io.enqueuePhysTags.foreach(_.poke(0.U))
    dut.io.issueValid.poke(false.B)
    dut.io.issue.poke(0.U.asTypeOf(dut.io.issue))
    dut.io.atCommitHead.poke(false.B)
    dut.io.rfReadReady.poke(false.B)
    dut.io.rfReadData.poke(0.U)
    dut.io.serviceRequest.ready.poke(false.B)
    dut.io.serviceResponse.valid.poke(false.B)
    dut.io.serviceResponse.bits.poke(0.U.asTypeOf(dut.io.serviceResponse.bits))
    dut.io.flush.poke(false.B)
    dut.io.completeReady.poke(false.B)
    dut.io.releaseReady.poke(false.B)
    dut.io.writebackReady.poke(false.B)
  }

  private def pokeSnapshotIdentity(
      id: ReducedServiceRenameSnapshotIdentity,
      stid: Int = 1,
      bid: Int = 2,
      gid: Int = 3,
      rid: Int = 4,
      ridWrap: Boolean = true,
      valid: Boolean = true): Unit = {
    id.stid.poke(stid.U)
    id.bid.valid.poke(valid.B)
    id.bid.wrap.poke(false.B)
    id.bid.value.poke(bid.U)
    id.gid.valid.poke(valid.B)
    id.gid.wrap.poke(false.B)
    id.gid.value.poke(gid.U)
    id.rid.valid.poke(valid.B)
    id.rid.wrap.poke(ridWrap.B)
    id.rid.value.poke(rid.U)
  }

  private def pokeRequestIdentity(
      id: ReducedServiceRequestIdentity,
      stid: Int = 1,
      bid: Int = 2,
      gid: Int = 3,
      rid: Int = 4,
      ridWrap: Boolean = true,
      valid: Boolean = true): Unit = {
    id.stid.poke(stid.U)
    id.bid.valid.poke(valid.B)
    id.bid.wrap.poke(false.B)
    id.bid.value.poke(bid.U)
    id.gid.valid.poke(valid.B)
    id.gid.wrap.poke(false.B)
    id.gid.value.poke(gid.U)
    id.rid.valid.poke(valid.B)
    id.rid.wrap.poke(ridWrap.B)
    id.rid.value.poke(rid.U)
  }

  private def insnRawForType(requestType: Int): BigInt = (BigInt(requestType) << 20) | BigInt(0x302b)

  private def capture(
      dut: ReducedServiceRequestPath,
      tagBase: Int = 70,
      stid: Int = 1,
      bid: Int = 2,
      gid: Int = 3,
      rid: Int = 4): Unit = {
    pokeSnapshotIdentity(dut.io.enqueueIdentity, stid = stid, bid = bid, gid = gid, rid = rid)
    for (idx <- 0 until ReducedServiceRequestPath.ArgCount) {
      dut.io.enqueuePhysTags(idx).poke((tagBase + idx).U)
    }
    dut.io.enqueueValid.poke(true.B)
    dut.io.enqueueReady.expect(true.B)
    dut.clock.step()
    dut.io.enqueueValid.poke(false.B)
    dut.io.renameFence.expect(true.B)
    dut.io.controlFence.expect(true.B)
  }

  private def driveIssue(
      dut: ReducedServiceRequestPath,
      requestType: Int = ReducedServiceRequestOwner.RawAcrcRequestType,
      pc: BigInt = 0x1000,
      nextInsnLen: Int = 2,
      nextHalfword: Int = ReducedServiceRequestOwner.RawCompressedBstop.toInt,
      nextInsnRaw: BigInt = 0,
      stid: Int = 1,
      bid: Int = 2,
      gid: Int = 3,
      rid: Int = 4,
      ridWrap: Boolean = true): Unit = {
    dut.io.issueValid.poke(true.B)
    dut.io.issue.pc.poke(pc.U)
    dut.io.issue.insnLen.poke(4.U)
    dut.io.issue.insnRaw.poke(insnRawForType(requestType).U)
    dut.io.issue.nextInsnLen.poke(nextInsnLen.U)
    dut.io.issue.nextHalfword.poke(nextHalfword.U)
    dut.io.issue.nextInsnRaw.poke(nextInsnRaw.U)
    pokeRequestIdentity(
      dut.io.issue.identity,
      stid = stid,
      bid = bid,
      gid = gid,
      rid = rid,
      ridWrap = ridWrap)
  }

  private def driveResponse(
      dut: ReducedServiceRequestPath,
      requestType: Int = ReducedServiceRequestOwner.RawAcrcRequestType,
      stid: Int = 1,
      bid: Int = 2,
      gid: Int = 3,
      rid: Int = 4,
      a0: BigInt = BigInt("8877665544332211", 16)): Unit = {
    dut.io.serviceResponse.valid.poke(true.B)
    dut.io.serviceResponse.bits.requestType.poke(requestType.U)
    pokeRequestIdentity(dut.io.serviceResponse.bits.identity, stid = stid, bid = bid, gid = gid, rid = rid)
    dut.io.serviceResponse.bits.a0.poke(a0.U)
  }

  private def startGather(dut: ReducedServiceRequestPath): Unit = {
    dut.io.atCommitHead.poke(true.B)
    dut.io.snapshotLookupMatch.expect(true.B)
    dut.io.issueReady.expect(false.B)
    dut.io.rfReadValid.expect(false.B)
    dut.clock.step()
    dut.io.gatherBusy.expect(true.B)
  }

  private def gatherArgs(dut: ReducedServiceRequestPath, tagBase: Int = 70, dataBase: BigInt = 0x100): Unit = {
    for (idx <- 0 until ReducedServiceRequestPath.ArgCount) {
      dut.io.rfReadValid.expect(true.B)
      dut.io.rfReadIndex.expect(idx.U)
      dut.io.rfReadTag.expect((tagBase + idx).U)
      dut.io.rfReadReady.poke(true.B)
      dut.io.rfReadData.poke((dataBase + idx).U)
      dut.clock.step()
    }
    dut.io.rfReadReady.poke(false.B)
    dut.io.gatherBusy.expect(false.B)
  }

  test("captures full identity and seven tags, then issues gathered args in a0..a5/a7 order") {
    simulate(new ReducedServiceRequestPath(p, trace)) { dut =>
      clear(dut)
      capture(dut, tagBase = 70)
      driveIssue(dut)
      dut.io.serviceRequest.ready.poke(true.B)
      startGather(dut)
      gatherArgs(dut, tagBase = 70, dataBase = 0x100)

      dut.io.issueReady.expect(true.B)
      dut.io.serviceRequest.valid.expect(true.B)
      dut.io.serviceRequest.bits.requestType.expect(ReducedServiceRequestOwner.RawAcrcRequestType.U)
      dut.io.serviceRequest.bits.a0.expect(0x100.U)
      dut.io.serviceRequest.bits.a1.expect(0x101.U)
      dut.io.serviceRequest.bits.a2.expect(0x102.U)
      dut.io.serviceRequest.bits.a3.expect(0x103.U)
      dut.io.serviceRequest.bits.a4.expect(0x104.U)
      dut.io.serviceRequest.bits.a5.expect(0x105.U)
      dut.io.serviceRequest.bits.a7.expect(0x106.U)
      dut.io.serviceRequest.bits.a0PhysTag.expect(70.U)
      dut.clock.step()
      dut.io.issueValid.poke(false.B)
      dut.io.ownerBusy.expect(true.B)
    }
  }

  test("requires commit-head before starting RF gather") {
    simulate(new ReducedServiceRequestPath(p, trace)) { dut =>
      clear(dut)
      capture(dut)
      driveIssue(dut)
      dut.io.atCommitHead.poke(false.B)
      dut.clock.step()
      dut.io.rfReadValid.expect(false.B)
      dut.io.serviceRequest.valid.expect(false.B)
      dut.io.issueReady.expect(false.B)

      dut.io.atCommitHead.poke(true.B)
      dut.io.snapshotLookupMatch.expect(true.B)
      dut.clock.step()
      dut.io.gatherBusy.expect(true.B)
    }
  }

  test("snapshot identity mismatch does not read RF or issue service") {
    simulate(new ReducedServiceRequestPath(p, trace)) { dut =>
      clear(dut)
      capture(dut, rid = 4)
      driveIssue(dut, rid = 5)
      dut.io.atCommitHead.poke(true.B)
      dut.io.snapshotLookupMatch.expect(false.B)
      dut.io.snapshotLookupMismatch.expect(true.B)
      dut.clock.step()
      dut.io.rfReadValid.expect(false.B)
      dut.io.serviceRequest.valid.expect(false.B)
      dut.io.issueReady.expect(false.B)
      dut.io.renameFence.expect(true.B)
    }
  }

  test("RF backpressure holds read tag and index stable") {
    simulate(new ReducedServiceRequestPath(p, trace)) { dut =>
      clear(dut)
      capture(dut, tagBase = 90)
      driveIssue(dut)
      startGather(dut)

      dut.io.rfReadReady.poke(false.B)
      dut.io.rfReadValid.expect(true.B)
      dut.io.rfReadIndex.expect(0.U)
      dut.io.rfReadTag.expect(90.U)
      dut.clock.step(2)
      dut.io.rfReadIndex.expect(0.U)
      dut.io.rfReadTag.expect(90.U)

      dut.io.rfReadReady.poke(true.B)
      dut.io.rfReadData.poke(0xabc.U)
      dut.clock.step()
      dut.io.rfReadIndex.expect(1.U)
      dut.io.rfReadTag.expect(91.U)
    }
  }

  test("service backpressure holds final issue accept until owner request fires, then response side effects pass through and clear") {
    simulate(new ReducedServiceRequestPath(p, trace)) { dut =>
      clear(dut)
      capture(dut)
      driveIssue(dut)
      startGather(dut)
      gatherArgs(dut)

      dut.io.serviceRequest.ready.poke(false.B)
      dut.io.issueReady.expect(false.B)
      dut.io.serviceRequest.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.issueReady.expect(false.B)
      dut.io.serviceRequest.valid.expect(true.B)

      dut.io.serviceRequest.ready.poke(true.B)
      dut.io.issueReady.expect(true.B)
      dut.clock.step()
      dut.io.ownerBusy.expect(true.B)
      dut.io.snapshotLookupMatch.expect(false.B)
      dut.io.rfReadValid.expect(false.B)
      dut.io.issueReady.expect(false.B)
      dut.io.issueValid.poke(false.B)

      driveResponse(dut)
      dut.io.completeReady.poke(true.B)
      dut.io.releaseReady.poke(true.B)
      dut.io.writebackReady.poke(true.B)
      dut.io.completeValid.expect(true.B)
      dut.io.releaseValid.expect(true.B)
      dut.io.writebackValid.expect(true.B)
      dut.io.writeback.physTag.expect(70.U)
      dut.io.writeback.data.expect(BigInt("8877665544332211", 16).U)
      dut.clock.step()
      dut.io.serviceResponse.valid.poke(false.B)
      dut.io.renameFence.expect(false.B)
      dut.io.controlFence.expect(false.B)
    }
  }

  test("illegal request sequence traps through owner and clears the snapshot without service issue") {
    simulate(new ReducedServiceRequestPath(p, trace)) { dut =>
      clear(dut)
      capture(dut)
      driveIssue(dut, nextHalfword = 0x0004)
      startGather(dut)
      gatherArgs(dut)

      dut.io.completeReady.poke(true.B)
      dut.io.serviceRequest.valid.expect(false.B)
      dut.io.issueReady.expect(true.B)
      dut.io.completeValid.expect(true.B)
      dut.io.completeRow.trap.valid.expect(true.B)
      dut.io.completeRow.trap.cause.expect(ReducedServiceRequestOwner.TrapIllegalServiceSequence.U)
      dut.io.trappedIllegalSequence.expect(true.B)
      dut.clock.step()
      dut.io.issueValid.poke(false.B)
      dut.io.renameFence.expect(false.B)
    }
  }

  test("flush during gather clears state and snapshot before any RF or service side effect continues") {
    simulate(new ReducedServiceRequestPath(p, trace)) { dut =>
      clear(dut)
      capture(dut)
      driveIssue(dut)
      startGather(dut)
      dut.io.rfReadValid.expect(true.B)

      dut.io.flush.poke(true.B)
      dut.io.rfReadValid.expect(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.gatherBusy.expect(false.B)
      dut.io.renameFence.expect(false.B)
      dut.io.controlFence.expect(false.B)
      dut.io.serviceRequest.valid.expect(false.B)
    }
  }

  test("flush during owner request keeps control fence until canceled response drains") {
    simulate(new ReducedServiceRequestPath(p, trace)) { dut =>
      clear(dut)
      capture(dut)
      driveIssue(dut)
      dut.io.serviceRequest.ready.poke(true.B)
      startGather(dut)
      gatherArgs(dut)
      dut.io.issueReady.expect(true.B)
      dut.clock.step()
      dut.io.issueValid.poke(false.B)
      dut.io.ownerBusy.expect(true.B)

      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.renameFence.expect(false.B)
      dut.io.controlFence.expect(true.B)

      driveIssue(dut, rid = 5)
      dut.io.atCommitHead.poke(true.B)
      dut.io.issueReady.expect(false.B)
      dut.io.serviceRequest.valid.expect(false.B)
      driveResponse(dut)
      dut.io.serviceResponse.ready.expect(true.B)
      dut.io.lateResponseSuppressed.expect(true.B)
      dut.clock.step()
      dut.io.serviceResponse.valid.poke(false.B)
      dut.io.controlFence.expect(false.B)
    }
  }

  test("Chisel ReducedServiceRequestPath elaborates the standalone request path surface") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedServiceRequestPath(p, trace))

    assert(sv.contains("module ReducedServiceRequestPath"))
    assert(sv.contains("io_enqueueReady"))
    assert(sv.contains("io_issueReady"))
    assert(sv.contains("io_rfReadValid"))
    assert(sv.contains("io_serviceRequest_valid"))
    assert(sv.contains("io_completeValid"))
    assert(sv.contains("io_renameFence"))
    assert(sv.contains("io_controlFence"))
  }
}
