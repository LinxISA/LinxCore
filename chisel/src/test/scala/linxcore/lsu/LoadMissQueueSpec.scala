package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadMissQueueReference {
  final case class MissId(slot: Int, generation: Boolean)
  final case class Dependent(
      loadIndex: Int,
      loadGeneration: Boolean = false,
      peId: Int = 0,
      stid: Int = 0,
      tid: Int = 0,
      bid: Int = 0,
      gid: Int = 0,
      rid: Int = 0,
      loadLsId: Int = 0,
      loadLsIdFullValid: Boolean = true,
      loadLsIdFull: BigInt = 0)
  final case class Entry(
      missId: MissId,
      lineAddr: BigInt,
      issued: Boolean = false,
      dependents: Map[Int, Dependent] = Map.empty)
  final case class Request(missId: MissId, lineAddr: BigInt)
  final case class ResponseResult(
      accepted: Boolean,
      matched: Boolean,
      stale: Boolean,
      refill: Boolean,
      dependents: Set[Int])

  final class QueueModel(val entries: Int) {
    private var rows = Vector.fill[Option[Entry]](entries)(None)
    private var generations = Vector.fill(entries)(false)
    private var issueOrder = Vector.empty[Int]

    def validCount: Int = rows.count(_.nonEmpty)
    def freeCount: Int = entries - validCount
    def pending: Boolean = rows.exists(_.nonEmpty) || issueOrder.nonEmpty
    def entry(slot: Int): Option[Entry] = rows(slot)
    def orphanMask: Int = rows.zipWithIndex.foldLeft(0) {
      case (mask, (Some(row), idx)) if row.issued && row.dependents.isEmpty => mask | (1 << idx)
      case (mask, _) => mask
    }

    def miss(lineAddr: BigInt, dependent: Dependent): String = {
      val lineMatches = rows.zipWithIndex.collect {
        case (Some(row), idx) if row.lineAddr == lineAddr => idx
      }
      require(lineMatches.size <= 1, "duplicate line owner")
      val occupiedElsewhere = rows.zipWithIndex.exists {
        case (Some(row), idx) => row.dependents.contains(dependent.loadIndex) && !lineMatches.contains(idx)
        case _ => false
      }
      if (occupiedElsewhere) {
        "index-collision"
      } else if (lineMatches.nonEmpty) {
        val idx = lineMatches.head
        val row = rows(idx).get
        row.dependents.get(dependent.loadIndex) match {
          case Some(old) if old != dependent => "index-collision"
          case _ =>
            rows = rows.updated(idx, Some(row.copy(
              dependents = row.dependents.updated(dependent.loadIndex, dependent))))
            "coalesced"
        }
      } else {
        rows.indexWhere(_.isEmpty) match {
          case -1 => "capacity"
          case idx =>
            val missId = MissId(idx, generations(idx))
            rows = rows.updated(idx, Some(Entry(
              missId = missId,
              lineAddr = lineAddr,
              dependents = Map(dependent.loadIndex -> dependent))))
            issueOrder :+= idx
            "allocated"
        }
      }
    }

    private def dropEmptyHeads(): Unit = {
      var keepDropping = true
      while (keepDropping && issueOrder.nonEmpty) {
        val idx = issueOrder.head
        rows(idx) match {
          case Some(row) if !row.issued && row.dependents.isEmpty =>
            issueOrder = issueOrder.tail
            rows = rows.updated(idx, None)
            generations = generations.updated(idx, !generations(idx))
          case None => issueOrder = issueOrder.tail
          case Some(row) if row.issued => issueOrder = issueOrder.tail
          case _ => keepDropping = false
        }
      }
    }

    def request: Option[Request] = {
      dropEmptyHeads()
      issueOrder.headOption.flatMap(idx => rows(idx).map(row => Request(row.missId, row.lineAddr)))
    }

    def acceptRequest(ready: Boolean): Option[Request] = {
      val current = request
      if (ready) {
        current.foreach { req =>
          val idx = issueOrder.head
          val row = rows(idx).get
          rows = rows.updated(idx, Some(row.copy(issued = true)))
          issueOrder = issueOrder.tail
        }
      }
      current.filter(_ => ready)
    }

    def prune(predicate: Dependent => Boolean): Int = {
      var count = 0
      rows = rows.map {
        case Some(row) =>
          val survivors = row.dependents.filterNot { case (_, dep) =>
            val kill = predicate(dep)
            if (kill) count += 1
            kill
          }
          Some(row.copy(dependents = survivors))
        case None => None
      }
      count
    }

    def hardFlush(): Int = prune(_ => true)

    def response(
        missId: MissId,
        lineAddr: BigInt,
        isRead: Boolean = true,
        missIdValid: Boolean = true): ResponseResult = {
      val matches = rows.zipWithIndex.collect {
        case (Some(row), idx)
            if missIdValid && row.issued && row.missId == missId && row.lineAddr == lineAddr => idx
      }
      if (matches.size != 1) {
        ResponseResult(accepted = true, matched = false, stale = true, refill = false, Set.empty)
      } else {
        val idx = matches.head
        val deps = rows(idx).get.dependents.keySet
        if (isRead) {
          rows = rows.updated(idx, None)
          generations = generations.updated(idx, !generations(idx))
        }
        ResponseResult(
          accepted = true,
          matched = true,
          stale = false,
          refill = isRead,
          dependents = if (isRead) deps else Set.empty)
      }
    }
  }
}

class LoadMissQueueSpec extends AnyFunSuite {
  import LoadMissQueueReference._

  test("unique lines issue in FIFO order while same-line misses coalesce") {
    val q = new QueueModel(entries = 4)
    assert(q.miss(0x1000, Dependent(loadIndex = 0, bid = 1)) == "allocated")
    assert(q.miss(0x2000, Dependent(loadIndex = 1, bid = 2)) == "allocated")
    assert(q.miss(0x1000, Dependent(loadIndex = 2, bid = 3)) == "coalesced")
    assert(q.validCount == 2)

    val held = q.request.get
    assert(held.lineAddr == 0x1000)
    assert(q.acceptRequest(ready = false).isEmpty)
    assert(q.request.contains(held))
    assert(q.acceptRequest(ready = true).contains(held))
    assert(q.request.exists(_.lineAddr == 0x2000))
  }

  test("exact response refills every coalesced dependent and advances generation") {
    val q = new QueueModel(entries = 2)
    q.miss(0x1000, Dependent(loadIndex = 0))
    q.miss(0x1000, Dependent(loadIndex = 1))
    val first = q.acceptRequest(ready = true).get

    val response = q.response(first.missId, first.lineAddr)
    assert(response.matched && response.refill)
    assert(response.dependents == Set(0, 1))
    assert(q.validCount == 0)

    q.miss(0x3000, Dependent(loadIndex = 0, loadGeneration = true))
    val second = q.request.get
    assert(second.missId.slot == first.missId.slot)
    assert(second.missId.generation != first.missId.generation)
  }

  test("precise recovery prunes dependents and preserves an issued orphan") {
    val q = new QueueModel(entries = 4)
    q.miss(0x1000, Dependent(loadIndex = 0, stid = 0, bid = 4, loadLsIdFull = 8))
    q.miss(0x1000, Dependent(loadIndex = 1, stid = 1, bid = 4, loadLsIdFull = 8))
    val request = q.acceptRequest(ready = true).get

    assert(q.prune(_.stid == 0) == 1)
    assert(q.entry(request.missId.slot).exists(_.dependents.keySet == Set(1)))
    assert(q.prune(_.stid == 1) == 1)
    assert(q.orphanMask == (1 << request.missId.slot))
    assert(q.pending)

    assert(q.miss(0x1000, Dependent(loadIndex = 2, stid = 0)) == "coalesced")
    val response = q.response(request.missId, request.lineAddr)
    assert(response.refill && response.dependents == Set(2))
    assert(!q.pending)
  }

  test("unissued empty entries cancel without traffic") {
    val q = new QueueModel(entries = 2)
    q.miss(0x1000, Dependent(loadIndex = 0))
    assert(q.hardFlush() == 1)
    assert(q.request.isEmpty)
    assert(q.validCount == 0)
    assert(!q.pending)
  }

  test("stale generation and wrong-line responses cannot free a reused entry") {
    val q = new QueueModel(entries = 2)
    q.miss(0x1000, Dependent(loadIndex = 0))
    val old = q.acceptRequest(ready = true).get
    assert(q.response(old.missId, old.lineAddr).matched)

    q.miss(0x2000, Dependent(loadIndex = 1))
    val current = q.acceptRequest(ready = true).get
    assert(current.missId.slot == old.missId.slot)
    assert(current.missId.generation != old.missId.generation)

    val stale = q.response(old.missId, old.lineAddr)
    assert(stale.stale && !stale.refill)
    assert(q.entry(current.missId.slot).nonEmpty)
    val wrongLine = q.response(current.missId, 0x3000)
    assert(wrongLine.stale && q.entry(current.missId.slot).nonEmpty)
    assert(q.response(current.missId, current.lineAddr).matched)
  }

  test("malformed exact responses are diagnosed without freeing the live miss") {
    val q = new QueueModel(entries = 2)
    q.miss(0x1000, Dependent(loadIndex = 0))
    val current = q.acceptRequest(ready = true).get

    val nonRead = q.response(current.missId, current.lineAddr, isRead = false)
    assert(nonRead.matched && !nonRead.refill)
    assert(q.entry(current.missId.slot).nonEmpty)

    val invalidId = q.response(current.missId, current.lineAddr, missIdValid = false)
    assert(invalidId.stale && !invalidId.refill)
    assert(q.entry(current.missId.slot).nonEmpty)
    assert(q.response(current.missId, current.lineAddr).refill)
  }

  test("physical capacity and LIQ index ownership are independent") {
    val q = new QueueModel(entries = 2)
    assert(q.miss(0x1000, Dependent(loadIndex = 7)) == "allocated")
    assert(q.miss(0x2000, Dependent(loadIndex = 3)) == "allocated")
    assert(q.miss(0x3000, Dependent(loadIndex = 5)) == "capacity")
    assert(q.miss(0x1000, Dependent(loadIndex = 5)) == "coalesced")
  }

  test("Chisel LoadMissQueue elaborates exact transactions and 40-bit dependents") {
    val io = new LoadMissQueueIO(
      missEntries = 4,
      liqEntries = 8,
      idEntries = 16,
      storeEntries = 8,
      addrWidth = 64,
      pcWidth = 64,
      lineBytes = 64,
      sizeWidth = 7,
      archRegWidth = 6,
      physRegWidth = 7,
      peIdWidth = 4,
      stidWidth = 3,
      tidWidth = 2,
      returnPipeCount = 2,
      lsidWidth = 40)
    assert(io.entries.length == 4)
    assert(io.entries.head.dependents.length == 8)
    assert(io.entries.head.missId.value.getWidth == 2)
    assert(io.entries.head.dependents.head.loadId.value.getWidth == 3)
    assert(io.entries.head.dependents.head.bid.value.getWidth == 4)
    assert(io.entries.head.dependents.head.loadLsIdFull.getWidth == 40)

    val sv = ChiselStage.emitSystemVerilog(new LoadMissQueue(
      missEntries = 4,
      liqEntries = 8,
      idEntries = 16,
      storeEntries = 8,
      lsidWidth = 40))
    assert(sv.contains("module LoadMissQueue"))
    assert(sv.contains("io_request_missId_wrap"))
    assert(sv.contains("io_response_missId_wrap"))
    assert(sv.contains("io_refillReady"))
    assert(sv.contains("io_responseBlockedByRefill"))
    assert(sv.contains("io_missCoalesced"))
    assert(sv.contains("io_orphanMask"))
    assert(sv.contains("io_precisePruneCount"))
    assert(sv.contains("io_protocolError"))
  }
}
