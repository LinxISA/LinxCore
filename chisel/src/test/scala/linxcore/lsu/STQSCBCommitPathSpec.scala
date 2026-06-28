package linxcore.lsu

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

class STQSCBCommitPathSpec extends AnyFunSuite {
  import STQEntryBankReference._
  import STQFlushPruneReference.Id
  import STQSCBCommitPathReference._

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
    assert(sv.contains("module SCBRowBank"))
    assert(sv.contains("io_scbCommitFreeMask"))
    assert(sv.contains("io_rawRespTxnId"))
    assert(sv.contains("io_rawRespReady"))
    assert(sv.contains("io_lsuTULinkSource_valid"))
    assert(sv.contains("io_lsuTULinkSourceMatched"))
    assert(sv.contains("io_scbRespBufferHeadTxnId"))
    assert(sv.contains("io_scbRespDecodeError"))
    assert(sv.contains("io_stqCommitFreeAcceptedMask"))
    assert(sv.contains("io_drainEarlyFreeMask"))
  }
}
