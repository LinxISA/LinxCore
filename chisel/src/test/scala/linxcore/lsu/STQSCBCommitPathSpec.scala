package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object STQSCBCommitPathReference {
  import STQCommitDrainReference.{Request, Row}
  import STQCommitQueueReference.Entry
  import STQEntryBankReference.{Commit, CommitFreeMaskResult, InsertResult, Model, Request => StoreRequest}

  final case class StepResult(
      markCommitAccepted: Boolean,
      scbReadyForDrain: Boolean,
      issued: Seq[Entry],
      requests: Seq[Request],
      drainEarlyFreeMask: BigInt,
      finalFreeMask: BigInt,
      stqFree: CommitFreeMaskResult,
      queue: Seq[Entry],
      commitMask: Int,
      residentCount: Int)

  final class CommitPathModel(stqEntries: Int, scbEntries: Int, issueWidth: Int) {
    require(stqEntries > 1 && (stqEntries & (stqEntries - 1)) == 0)
    require(issueWidth > 0)

    private val requestCount = issueWidth * 2
    private val stq = new Model(stqEntries)
    private val drain = new STQCommitDrainReference.Model(stqEntries, issueWidth)
    private var scbFree = scbEntries

    def insert(req: StoreRequest): InsertResult = stq.insert(req)
    def setScbFree(count: Int): Unit = {
      require(count >= 0 && count <= scbEntries)
      scbFree = count
    }
    def residentCount: Int = stq.residentCount
    def commitMask: Int = stq.commitMask
    def queueIndices: Seq[Int] = drain.entries.map(_.stqIndex)

    private def committedRows: Seq[Row] =
      (0 until stqEntries).flatMap { index =>
        stq.entry(index).collect {
          case entry if entry.status == Commit =>
            Row(index = index, addr = entry.req.addr, data = entry.req.data, size = entry.req.size)
        }
      }

    def step(markCommit: Option[Int] = None, issueEnable: Boolean = true, flushApplied: Boolean = false): StepResult = {
      val rowsForDrain = committedRows
      val enqueue = markCommit.flatMap { index =>
        val before = stq.entry(index)
        if (stq.markCommit(index)) {
          before.map(entry => Entry(stqIndex = index, bid = entry.req.bid, lsId = entry.req.lsId))
        } else {
          None
        }
      }
      val scbReady = scbFree >= requestCount && !flushApplied
      val readyRows = if (scbReady) rowsForDrain.map(_.index).toSet else Set.empty[Int]
      val drainResult = drain.step(
        enqueue = enqueue,
        rows = rowsForDrain,
        primaryReady = readyRows,
        secondaryReady = readyRows,
        issueEnable = issueEnable && scbReady
      )
      val finalFreeMask = drainResult.requests.filter(_.last).foldLeft(BigInt(0)) { case (mask, req) =>
        mask | (BigInt(1) << req.index)
      }
      val stqFree =
        if (finalFreeMask == 0) CommitFreeMaskResult(acceptedMask = 0, ignoredMask = 0, count = 0)
        else stq.commitFreeMask(finalFreeMask.toInt)

      StepResult(
        markCommitAccepted = enqueue.nonEmpty,
        scbReadyForDrain = scbReady,
        issued = drainResult.issued,
        requests = drainResult.requests,
        drainEarlyFreeMask = drainResult.freeMask,
        finalFreeMask = finalFreeMask,
        stqFree = stqFree,
        queue = drain.entries,
        commitMask = stq.commitMask,
        residentCount = stq.residentCount
      )
    }
  }
}

class STQSCBCommitPathSpec extends AnyFunSuite with ChiselSim {
  import STQEntryBankReference._
  import STQFlushPruneReference.Id
  import STQSCBCommitPathReference._

  private def clearMemoryClassIO(dut: STQSCBCommitPath): Unit = {
    dut.io.memoryClassify.valid.poke(false.B)
    dut.io.memoryClassify.bits.poke(
      0.U.asTypeOf(dut.io.memoryClassify.bits))
    dut.io.serializedRequest.ready.poke(false.B)
    dut.io.serializedResponse.valid.poke(false.B)
    dut.io.serializedResponse.bits.poke(
      0.U.asTypeOf(dut.io.serializedResponse.bits))
  }

  private def classifyRow(
      dut: STQSCBCommitPath,
      index: BigInt,
      memoryClass: STQMemoryClass.Type =
        STQMemoryClass.NormalCacheable): Unit = {
    val token = dut.io.memoryClassify.bits
    token.poke(0.U.asTypeOf(token))
    token.lease.valid.poke(true.B)
    token.lease.index.poke(index.U)
    token.lease.generation.poke(
      dut.io.stqRows(index.toInt).leaseGeneration.peek())
    token.exactOwner.valid.poke(true.B)
    token.exactOwner.peId.poke(1.U)
    token.exactOwner.stid.poke(0.U)
    token.exactOwner.nativeBidValid.poke(true.B)
    token.exactOwner.nativeBid.poke(6.U)
    token.exactOwner.brobGeneration.poke(2.U)
    token.exactOwner.ridSlot.poke(1.U)
    token.exactOwner.ridGeneration.poke(3.U)
    token.exactOwner.memberIndex.poke(0.U)
    token.exactOwner.residentGeneration.poke(4.U)
    token.logicalBeat.poke(dut.io.stqRows(index.toInt).logicalBeat.peek())
    token.memoryClass.poke(memoryClass)
    dut.io.memoryClassify.valid.poke(true.B)
    dut.io.memoryClassify.ready.expect(true.B)
    dut.io.memoryClassifyAccepted.expect(true.B)
    dut.clock.step()
    dut.io.memoryClassify.valid.poke(false.B)
  }

  private def req(n: Int, addr: BigInt = 0x1000, size: Int = 8, data: BigInt = 0x1122334455667788L, bid: Int = 1, lsId: Int = 0): Request =
    Request(
      storeType = All,
      bid = Id(value = bid),
      gid = Id(value = 0),
      rid = Id(value = n),
      lsId = Id(value = lsId),
      stid = 1,
      peId = 2,
      tid = 3,
      addr = addr,
      data = data,
      size = size
    )

  test("SCB accepted last fragment is the source of STQ committed-row free") {
    val path = new CommitPathModel(stqEntries = 8, scbEntries = 8, issueWidth = 2)
    val index = path.insert(req(0, bid = 1, lsId = 0)).index.get

    val marked = path.step(markCommit = Some(index))
    assert(marked.markCommitAccepted)
    assert(marked.issued.isEmpty)
    assert(marked.finalFreeMask == BigInt(0))
    assert(marked.commitMask == (1 << index))

    val drained = path.step()
    assert(drained.scbReadyForDrain)
    assert(drained.issued.map(_.stqIndex) == Seq(index))
    assert(drained.requests.map(_.last) == Seq(true))
    assert(drained.finalFreeMask == (BigInt(1) << index))
    assert(drained.stqFree == CommitFreeMaskResult(acceptedMask = 1 << index, ignoredMask = 0, count = 1))
    assert(drained.residentCount == 0)
  }

  test("closed SCB model batch stalls drain issue and preserves committed STQ row") {
    val path = new CommitPathModel(stqEntries = 8, scbEntries = 8, issueWidth = 2)
    val index = path.insert(req(0, bid = 1, lsId = 0)).index.get
    path.step(markCommit = Some(index))
    path.setScbFree(3)

    val stalled = path.step()
    assert(!stalled.scbReadyForDrain)
    assert(stalled.issued.isEmpty)
    assert(stalled.requests.isEmpty)
    assert(stalled.finalFreeMask == BigInt(0))
    assert(stalled.queue.map(_.stqIndex) == Seq(index))
    assert(stalled.commitMask == (1 << index))

    path.setScbFree(4)
    val released = path.step()
    assert(released.issued.map(_.stqIndex) == Seq(index))
    assert(released.stqFree.acceptedMask == (1 << index))
  }

  test("split stores free the STQ row only from the accepted last fragment") {
    val path = new CommitPathModel(stqEntries = 8, scbEntries = 8, issueWidth = 2)
    val index = path.insert(req(0, addr = 0x103e, size = 8, data = BigInt("1122334455667788", 16))).index.get
    path.step(markCommit = Some(index))

    val drained = path.step()
    assert(drained.requests.map(req => (req.segment, req.last, req.addr, req.size)) == Seq(
      (0, false, BigInt(0x103e), 2),
      (1, true, BigInt(0x1040), 6)
    ))
    assert(drained.finalFreeMask == (BigInt(1) << index))
    assert(drained.stqFree.acceptedMask == (1 << index))
    assert(drained.residentCount == 0)
  }

  test("older committed row can drain while a younger row is enqueued for a later cycle") {
    val path = new CommitPathModel(stqEntries = 8, scbEntries = 8, issueWidth = 1)
    val old = path.insert(req(0, bid = 1, lsId = 0)).index.get
    val young = path.insert(req(1, addr = 0x2000, bid = 1, lsId = 1)).index.get

    path.step(markCommit = Some(old))
    val overlap = path.step(markCommit = Some(young))

    assert(overlap.issued.map(_.stqIndex) == Seq(old))
    assert(overlap.stqFree.acceptedMask == (1 << old))
    assert(overlap.queue.map(_.stqIndex) == Seq(young))
    assert(overlap.commitMask == (1 << young))
    assert(overlap.residentCount == 1)
  }

  test("Chisel STQSCBCommitPath elaborates with STQ bank, drain, and SCB row-bank children") {
    val sv = ChiselStage.emitSystemVerilog(new STQSCBCommitPath(entries = 8, queueEntries = 8, issueWidth = 2, scbEntries = 8))

    assert(sv.contains("module STQSCBCommitPath"))
    assert(sv.contains("module STQEntryBank"))
    assert(sv.contains("module STQCommitDrain"))
    assert(sv.contains("module STQRobCommitIngress"))
    assert(sv.contains("module STQMemoryAttributeOwner"))
    assert(sv.contains("module STQCommittedStoreSerializer"))
    assert(sv.contains("module SCBRowBank"))
    assert(sv.contains("io_scbCommitFreeMask"))
    assert(sv.contains("io_scbAcceptedReqs_0_valid"))
    assert(sv.contains("io_scbAcceptedReqs_0_gid_value"))
    assert(sv.contains("io_scbAcceptedReqs_0_rid_value"))
    assert(sv.contains("io_rawRespTxnId"))
    assert(sv.contains("io_rawRespReady"))
    assert(sv.contains("io_lsuTULinkSource_valid"))
    assert(sv.contains("io_lsuTULinkSourceMatched"))
    assert(sv.contains("io_scbRespBufferHeadTxnId"))
    assert(sv.contains("io_scbRespDecodeError"))
    assert(sv.contains("io_stqCommitFreeAcceptedMask"))
    assert(sv.contains("io_drainEarlyFreeMask"))
    assert(sv.contains("io_serializedRequest_valid"))
  }

  test("accepted WAIT-to-Commit transition snapshots one exact drain token") {
    simulate(new STQSCBCommitPath(
      entries = 4,
      queueEntries = 4,
      issueWidth = 1,
      scbEntries = 4,
      scbResponseBufferDepth = 2,
      robEntries = 8,
      stidWidth = 2,
      lsidWidth = 40)) { dut =>
      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
      dut.io.insertValid.poke(false.B)
      dut.io.insert.poke(0.U.asTypeOf(dut.io.insert))
      dut.io.markCommitValid.poke(false.B)
      dut.io.markCommitIndex.poke(0.U)
      dut.io.robStoreCommit.valid.poke(false.B)
      dut.io.robStoreCommit.bits.poke(
        0.U.asTypeOf(dut.io.robStoreCommit.bits))
      dut.io.issueEnable.poke(false.B)
      dut.io.evictEnable.poke(false.B)
      dut.io.dcacheReady.poke(true.B)
      dut.io.dcacheWriteHit.poke(false.B)
      dut.io.dcacheTagHit.poke(false.B)
      dut.io.l2RequestReady.poke(true.B)
      dut.io.rawRespValid.poke(false.B)
      dut.io.rawRespTxnId.poke(0.U)
      dut.io.rawRespWrite.poke(false.B)
      dut.io.rawRespUpgrade.poke(false.B)
      clearMemoryClassIO(dut)
      dut.clock.step()

      val request = dut.io.insert
      request.storeType.poke(STQStoreType.All)
      request.peId.poke(1.U)
      request.stid.poke(0.U)
      request.tid.poke(0.U)
      request.bid.valid.poke(true.B)
      request.bid.value.poke(2.U)
      request.gid.valid.poke(true.B)
      request.gid.value.poke(1.U)
      request.rid.valid.poke(true.B)
      request.rid.value.poke(3.U)
      request.lsId.valid.poke(true.B)
      request.lsId.value.poke(1.U)
      request.lsIdFull.poke(9.U)
      request.storeIdFullValid.poke(true.B)
      request.storeIdFull.poke(4.U)
      request.logicalStoreValid.poke(true.B)
      request.logicalFirstLsid.poke(9.U)
      request.logicalFirstStoreId.poke(4.U)
      request.logicalRequestCount.poke(1.U)
      request.logicalBeat.poke(0.U)
      request.exactOwner.valid.poke(true.B)
      request.exactOwner.peId.poke(1.U)
      request.exactOwner.stid.poke(0.U)
      request.exactOwner.nativeBidValid.poke(true.B)
      request.exactOwner.nativeBid.poke(6.U)
      request.exactOwner.brobGeneration.poke(2.U)
      request.exactOwner.ridSlot.poke(1.U)
      request.exactOwner.ridGeneration.poke(3.U)
      request.exactOwner.memberIndex.poke(0.U)
      request.exactOwner.residentGeneration.poke(4.U)
      request.addr.poke(0x1000.U)
      request.data.poke(BigInt("1122334455667788", 16).U)
      request.size.poke(8.U)
      dut.io.insertValid.poke(true.B)
      dut.io.insertReady.expect(true.B)
      dut.io.insertAccepted.expect(true.B)
      val index = dut.io.insertIndex.peek().litValue
      dut.clock.step()
      dut.io.insertValid.poke(false.B)
      classifyRow(dut, index)

      dut.io.markCommitValid.poke(true.B)
      dut.io.markCommitIndex.poke(index.U)
      dut.io.markCommitAccepted.expect(true.B)
      dut.io.drainEnqueueAccepted.expect(true.B)
      dut.io.drainEnqueueMalformed.expect(false.B)
      dut.clock.step()
      dut.io.markCommitValid.poke(false.B)

      dut.io.drainQueueCount.expect(1.U)
      dut.io.drainQueuedIdentityError.expect(false.B)
      dut.io.issueEnable.poke(true.B)
      dut.io.drainIssueValidMask.expect(1.U)
      dut.clock.step()
      dut.io.drainRetainedBatchValid.expect(true.B)
      dut.io.drainRetainedBatchAccepted.expect(true.B)
      dut.io.drainMemReqs(0).valid.expect(true.B)
      dut.io.drainMemReqs(0).last.expect(true.B)
      dut.io.drainMemReqs(0).ownsStqRow.expect(true.B)
      dut.io.scbAcceptedMask.expect(1.U)
      dut.io.scbCommitFreeMask.expect((BigInt(1) << index.toInt).U)
      dut.io.drainLogicalCompletionCount.expect(1.U)
      dut.io.drainLogicalCompletions(0).valid.expect(true.B)
    }
  }

  test("pair store reaches SCB as four fragments and reports one logical drain completion") {
    simulate(new STQSCBCommitPath(
      entries = 4,
      queueEntries = 4,
      issueWidth = 2,
      scbEntries = 4,
      scbResponseBufferDepth = 2,
      robEntries = 8,
      stidWidth = 2,
      lsidWidth = 40)) { dut =>
      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
      dut.io.insertValid.poke(false.B)
      dut.io.insert.poke(0.U.asTypeOf(dut.io.insert))
      dut.io.markCommitValid.poke(false.B)
      dut.io.markCommitIndex.poke(0.U)
      dut.io.robStoreCommit.valid.poke(false.B)
      dut.io.robStoreCommit.bits.poke(
        0.U.asTypeOf(dut.io.robStoreCommit.bits))
      dut.io.issueEnable.poke(false.B)
      dut.io.evictEnable.poke(false.B)
      dut.io.dcacheReady.poke(true.B)
      dut.io.dcacheWriteHit.poke(false.B)
      dut.io.dcacheTagHit.poke(false.B)
      dut.io.l2RequestReady.poke(true.B)
      dut.io.rawRespValid.poke(false.B)
      dut.io.rawRespTxnId.poke(0.U)
      dut.io.rawRespWrite.poke(false.B)
      dut.io.rawRespUpgrade.poke(false.B)
      clearMemoryClassIO(dut)
      dut.clock.step()

      def insertBeat(
          beat: Int,
          lsid: Int,
          storeId: Int,
          addr: BigInt,
          data: BigInt): BigInt = {
        val request = dut.io.insert
        request.poke(0.U.asTypeOf(request))
        request.storeType.poke(STQStoreType.All)
        request.peId.poke(1.U)
        request.stid.poke(0.U)
        request.bid.valid.poke(true.B)
        request.bid.value.poke(2.U)
        request.gid.valid.poke(true.B)
        request.gid.value.poke(1.U)
        request.rid.valid.poke(true.B)
        request.rid.value.poke(3.U)
        request.lsId.valid.poke(true.B)
        request.lsId.value.poke((lsid & 7).U)
        request.lsIdFull.poke(lsid.U)
        request.storeIdFullValid.poke(true.B)
        request.storeIdFull.poke(storeId.U)
        request.logicalStoreValid.poke(true.B)
        request.logicalFirstLsid.poke(10.U)
        request.logicalFirstStoreId.poke(20.U)
        request.logicalRequestCount.poke(2.U)
        request.logicalBeat.poke(beat.U)
        request.exactOwner.valid.poke(true.B)
        request.exactOwner.peId.poke(1.U)
        request.exactOwner.stid.poke(0.U)
        request.exactOwner.nativeBidValid.poke(true.B)
        request.exactOwner.nativeBid.poke(6.U)
        request.exactOwner.brobGeneration.poke(2.U)
        request.exactOwner.ridSlot.poke(1.U)
        request.exactOwner.ridGeneration.poke(3.U)
        request.exactOwner.memberIndex.poke(0.U)
        request.exactOwner.residentGeneration.poke(4.U)
        request.addr.poke(addr.U)
        request.data.poke(data.U)
        request.size.poke(8.U)
        dut.io.insertValid.poke(true.B)
        dut.io.insertReady.expect(true.B)
        val index = dut.io.insertIndex.peek().litValue
        dut.clock.step()
        dut.io.insertValid.poke(false.B)
        index
      }

      val firstIndex = insertBeat(
        beat = 0, lsid = 10, storeId = 20, addr = 0x103e,
        data = BigInt("1122334455667788", 16))
      val secondIndex = insertBeat(
        beat = 1, lsid = 11, storeId = 21, addr = 0x107e,
        data = BigInt("99aabbccddeeff00", 16))
      classifyRow(dut, firstIndex)
      classifyRow(dut, secondIndex)

      def robCommitBeat(beat: Int): Unit = {
        val token = dut.io.robStoreCommit.bits
        token.poke(0.U.asTypeOf(token))
        token.logicalFirstLsid.poke(10.U)
        token.logicalFirstStoreId.poke(20.U)
        token.logicalRequestCount.poke(2.U)
        token.logicalBeat.poke(beat.U)
        token.exactOwner.valid.poke(true.B)
        token.exactOwner.peId.poke(1.U)
        token.exactOwner.stid.poke(0.U)
        token.exactOwner.nativeBidValid.poke(true.B)
        token.exactOwner.nativeBid.poke(6.U)
        token.exactOwner.brobGeneration.poke(2.U)
        token.exactOwner.ridSlot.poke(1.U)
        token.exactOwner.ridGeneration.poke(3.U)
        token.exactOwner.memberIndex.poke(0.U)
        token.exactOwner.residentGeneration.poke(4.U)
        dut.io.robStoreCommit.valid.poke(true.B)
        dut.io.robStoreCommit.ready.expect(true.B)
        dut.io.robStoreCommitAccepted.expect(true.B)
        dut.io.drainEnqueueAccepted.expect(true.B)
        dut.clock.step()
        dut.io.robStoreCommit.valid.poke(false.B)
      }

      robCommitBeat(beat = 0)
      dut.io.drainIssueValidMask.expect(0.U)
      robCommitBeat(beat = 1)
      dut.io.issueEnable.poke(true.B)
      dut.io.drainIssueCount.expect(2.U)
      dut.clock.step()

      dut.io.drainRetainedBatchValid.expect(true.B)
      dut.io.drainRetainedBatchAccepted.expect(true.B)
      dut.io.drainMemReqs.foreach(_.valid.expect(true.B))
      dut.io.scbAcceptedMask.expect("b1111".U)
      dut.io.scbCommitFreeMask.expect(
        ((BigInt(1) << firstIndex.toInt) |
          (BigInt(1) << secondIndex.toInt)).U)
      dut.io.drainLogicalCompletionCount.expect(1.U)
      dut.io.drainLogicalCompletions(0).valid.expect(true.B)
      dut.io.drainLogicalCompletions(0).logicalRequestCount.expect(2.U)
      dut.io.drainLogicalCompletions(1).valid.expect(false.B)
    }
  }

  test("committed MMIO bypasses SCB and frees STQ only after exact response") {
    simulate(new STQSCBCommitPath(
      entries = 4,
      queueEntries = 4,
      issueWidth = 1,
      scbEntries = 4,
      scbResponseBufferDepth = 2,
      robEntries = 8,
      stidWidth = 2,
      lsidWidth = 40)) { dut =>
      dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
      dut.io.insertValid.poke(false.B)
      dut.io.insert.poke(0.U.asTypeOf(dut.io.insert))
      dut.io.markCommitValid.poke(false.B)
      dut.io.markCommitIndex.poke(0.U)
      dut.io.robStoreCommit.valid.poke(false.B)
      dut.io.robStoreCommit.bits.poke(
        0.U.asTypeOf(dut.io.robStoreCommit.bits))
      dut.io.issueEnable.poke(false.B)
      dut.io.evictEnable.poke(false.B)
      dut.io.dcacheReady.poke(true.B)
      dut.io.dcacheWriteHit.poke(false.B)
      dut.io.dcacheTagHit.poke(false.B)
      dut.io.l2RequestReady.poke(true.B)
      dut.io.rawRespValid.poke(false.B)
      dut.io.rawRespTxnId.poke(0.U)
      dut.io.rawRespWrite.poke(false.B)
      dut.io.rawRespUpgrade.poke(false.B)
      clearMemoryClassIO(dut)
      dut.clock.step()

      val request = dut.io.insert
      request.storeType.poke(STQStoreType.All)
      request.peId.poke(1.U)
      request.stid.poke(0.U)
      request.bid.valid.poke(true.B)
      request.bid.value.poke(2.U)
      request.gid.valid.poke(true.B)
      request.gid.value.poke(1.U)
      request.rid.valid.poke(true.B)
      request.rid.value.poke(3.U)
      request.lsId.valid.poke(true.B)
      request.lsId.value.poke(1.U)
      request.lsIdFull.poke(9.U)
      request.storeIdFullValid.poke(true.B)
      request.storeIdFull.poke(4.U)
      request.logicalStoreValid.poke(true.B)
      request.logicalFirstLsid.poke(9.U)
      request.logicalFirstStoreId.poke(4.U)
      request.logicalRequestCount.poke(1.U)
      request.logicalBeat.poke(0.U)
      request.exactOwner.valid.poke(true.B)
      request.exactOwner.peId.poke(1.U)
      request.exactOwner.stid.poke(0.U)
      request.exactOwner.nativeBidValid.poke(true.B)
      request.exactOwner.nativeBid.poke(6.U)
      request.exactOwner.brobGeneration.poke(2.U)
      request.exactOwner.ridSlot.poke(1.U)
      request.exactOwner.ridGeneration.poke(3.U)
      request.exactOwner.memberIndex.poke(0.U)
      request.exactOwner.residentGeneration.poke(4.U)
      request.addr.poke(0x40001000L.U)
      request.data.poke(BigInt("1122334455667788", 16).U)
      request.size.poke(8.U)
      dut.io.insertValid.poke(true.B)
      val index = dut.io.insertIndex.peek().litValue
      dut.io.insertReady.expect(true.B)
      dut.clock.step()
      dut.io.insertValid.poke(false.B)

      val token = dut.io.robStoreCommit.bits
      token.logicalFirstLsid.poke(9.U)
      token.logicalFirstStoreId.poke(4.U)
      token.logicalRequestCount.poke(1.U)
      token.logicalBeat.poke(0.U)
      token.exactOwner.valid.poke(true.B)
      token.exactOwner.peId.poke(1.U)
      token.exactOwner.stid.poke(0.U)
      token.exactOwner.nativeBidValid.poke(true.B)
      token.exactOwner.nativeBid.poke(6.U)
      token.exactOwner.brobGeneration.poke(2.U)
      token.exactOwner.ridSlot.poke(1.U)
      token.exactOwner.ridGeneration.poke(3.U)
      token.exactOwner.memberIndex.poke(0.U)
      token.exactOwner.residentGeneration.poke(4.U)
      dut.io.robStoreCommit.valid.poke(true.B)
      dut.io.robStoreCommitClassificationMissing.expect(true.B)
      dut.io.robStoreCommit.ready.expect(false.B)

      classifyRow(dut, index, STQMemoryClass.DeviceMmio)
      dut.io.robStoreCommit.ready.expect(true.B)
      dut.clock.step()
      dut.io.robStoreCommit.valid.poke(false.B)

      dut.io.issueEnable.poke(true.B)
      dut.clock.step()
      dut.io.drainRetainedBatchValid.expect(true.B)
      dut.io.drainRetainedBatchSerialized.expect(true.B)
      dut.io.drainRetainedBatchMemoryClass.expect(STQMemoryClass.DeviceMmio)
      dut.io.scbAcceptedMask.expect(0.U)
      dut.io.scbEntryCount.expect(0.U)
      dut.clock.step()

      dut.io.serializedRequest.valid.expect(true.B)
      dut.io.serializedRequest.bits.memoryClass.expect(
        STQMemoryClass.DeviceMmio)
      val transactionId =
        dut.io.serializedRequest.bits.transactionId.peek().litValue
      val retainedAddr =
        dut.io.serializedRequest.bits.fragment.addr.peek().litValue
      dut.io.flush.req.valid.poke(true.B)
      dut.clock.step(2)
      dut.io.serializedRequest.valid.expect(true.B)
      dut.io.serializedRequest.bits.transactionId.expect(transactionId.U)
      dut.io.serializedRequest.bits.fragment.addr.expect(retainedAddr.U)
      dut.io.stqOccupiedMask.expect((BigInt(1) << index.toInt).U)

      dut.io.serializedRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.serializedRequest.ready.poke(false.B)
      dut.io.serializedWaitingResponse.expect(true.B)
      dut.io.serializedRequest.valid.expect(false.B)

      dut.io.serializedResponse.valid.poke(true.B)
      dut.io.serializedResponse.bits.transactionId.poke(
        (transactionId + 1).U)
      dut.io.serializedStaleResponse.expect(true.B)
      dut.io.serializedResponse.ready.expect(false.B)
      dut.io.serializedFreeMaskValid.expect(false.B)
      dut.clock.step()

      dut.io.serializedResponse.bits.transactionId.poke(transactionId.U)
      dut.io.serializedResponse.bits.error.poke(false.B)
      dut.io.serializedResponse.ready.expect(true.B)
      dut.io.serializedFreeMaskValid.expect(true.B)
      dut.io.serializedFreeMask.expect((BigInt(1) << index.toInt).U)
      dut.io.serializedLogicalCompletion.valid.expect(true.B)
      dut.io.drainLogicalCompletionCount.expect(1.U)
      dut.io.scbEntryCount.expect(0.U)
      dut.clock.step()
      dut.io.serializedResponse.valid.poke(false.B)
      dut.io.flush.req.valid.poke(false.B)
      dut.io.stqOccupiedMask.expect(0.U)
      dut.io.serializedBusy.expect(false.B)
      dut.io.serializedRequest.valid.expect(false.B)

      // Keep the completed serializer's old physical slot occupied by an
      // unrelated speculative row, then complete a cacheable store in a
      // different slot.  Invalid serializer free bits must not leak into the
      // active SCB free command.
      request.rid.value.poke(4.U)
      request.lsId.value.poke(2.U)
      request.lsIdFull.poke(10.U)
      request.storeIdFull.poke(5.U)
      request.logicalFirstLsid.poke(10.U)
      request.logicalFirstStoreId.poke(5.U)
      request.exactOwner.memberIndex.poke(1.U)
      dut.io.insertValid.poke(true.B)
      val speculativeIndex = dut.io.insertIndex.peek().litValue
      dut.clock.step()
      dut.io.insertValid.poke(false.B)

      request.rid.value.poke(3.U)
      request.lsId.value.poke(1.U)
      request.lsIdFull.poke(9.U)
      request.storeIdFull.poke(4.U)
      request.logicalFirstLsid.poke(9.U)
      request.logicalFirstStoreId.poke(4.U)
      request.exactOwner.memberIndex.poke(0.U)
      request.addr.poke(0x50001000L.U)
      dut.io.insertValid.poke(true.B)
      val cacheableIndex = dut.io.insertIndex.peek().litValue
      assert(cacheableIndex != speculativeIndex)
      dut.clock.step()
      dut.io.insertValid.poke(false.B)
      classifyRow(dut, cacheableIndex, STQMemoryClass.NormalCacheable)

      dut.io.robStoreCommit.valid.poke(true.B)
      dut.io.robStoreCommit.ready.expect(true.B)
      dut.clock.step()
      dut.io.robStoreCommit.valid.poke(false.B)
      dut.clock.step()

      dut.io.drainRetainedBatchValid.expect(true.B)
      dut.io.drainRetainedBatchSerialized.expect(false.B)
      dut.io.scbCommitFreeMask.expect(
        (BigInt(1) << cacheableIndex.toInt).U)
      dut.io.stqCommitFreeAcceptedMask.expect(
        (BigInt(1) << cacheableIndex.toInt).U)
      dut.io.stqCommitFreeIgnoredMask.expect(0.U)
    }
  }
}
