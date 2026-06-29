package linxcore.rename

import circt.stage.ChiselStage
import linxcore.common.{DestinationKind, InterfaceParams}
import linxcore.rob.ROBIDValue
import org.scalatest.funsuite.AnyFunSuite

object TULinkRetireCommandPathReference {
  import TULinkRelationCmapReference._

  final case class Source(
      seq: Int,
      dst: Kind,
      valid: Boolean = true,
      last: Boolean = false,
      bid: Int = 1,
      gid: Int = 0,
      peId: Int = 0,
      stid: Int = 0)

  final class SourceQueue(sourceWidth: Int, queueDepth: Int) {
    private var rows = Vector.empty[Source]

    def preload(values: Seq[Source]): Unit = {
      require(values.length <= queueDepth)
      rows = values.toVector
    }

    def sourceWindowReady: Boolean =
      queueDepth - rows.length >= sourceWidth

    def enqueueWindow(sources: Seq[Source]): Boolean = {
      require(sources.length == sourceWidth)
      val ready = sourceWindowReady
      if (ready) {
        rows ++= sources.filter(_.valid)
      }
      ready
    }

    def drain(): Vector[Source] = {
      val out = rows
      rows = Vector.empty
      out
    }

    def cleanBlock(bid: Int): Unit = {
      rows = rows.filterNot(_.bid == bid)
    }

    def cleanGroup(bid: Int, gid: Int): Unit = {
      rows = rows.filterNot(source => source.bid == bid && source.gid == gid)
    }

    def flushRelative(flushBid: Int, baseOnBid: Boolean): Unit = {
      def needsFlush(source: Source): Boolean =
        if (baseOnBid) source.bid >= flushBid else source.bid > flushBid

      while (rows.nonEmpty && needsFlush(rows.last)) {
        rows = rows.dropRight(1)
      }
    }

    def count: Int = rows.length
    def seqs: Seq[Int] = rows.map(_.seq)
  }

  final case class BlockCleanEvent(bid: Int, stid: Int)
  final case class BlockCleanStep(autoClean: Option[BlockCleanEvent], localCommit: Option[BlockCleanEvent])

  final class BlockCleanScheduler {
    private var pendingClean: Option[BlockCleanEvent] = None
    private var pendingLocalCommit: Option[BlockCleanEvent] = None

    def sourceWindowReady(rawReady: Boolean, acceptingBlockLast: Boolean): Boolean =
      rawReady && pendingClean.isEmpty && pendingLocalCommit.isEmpty && !acceptingBlockLast

    def step(
        acceptingBlockLastBid: Option[Int],
        relationPending: Boolean,
        localCommitReady: Boolean = true,
        acceptingBlockLastStid: Int = 0): BlockCleanStep = {
      val localCommit = pendingLocalCommit.filter(_ => localCommitReady)
      if (localCommit.nonEmpty) {
        pendingLocalCommit = None
        BlockCleanStep(autoClean = None, localCommit = localCommit)
      } else {
        val clean = pendingClean.filter(_ => !relationPending)
        clean match {
          case Some(_) =>
            pendingClean = None
            pendingLocalCommit = clean
          case None =>
            acceptingBlockLastBid.foreach { bid =>
              pendingClean = Some(BlockCleanEvent(bid = bid, stid = acceptingBlockLastStid))
            }
        }
        BlockCleanStep(autoClean = clean, localCommit = None)
      }
    }

    def cleanPending: Boolean = pendingClean.nonEmpty
    def localCommitPending: Boolean = pendingLocalCommit.nonEmpty
    def pending: Boolean = cleanPending || localCommitPending
  }

  def toRelationRow(source: Source): Row =
    Row(
      bid = ROBIDValue(valid = true, wrap = false, value = 1),
      gid = ROBIDValue(valid = true, wrap = false, value = 0),
      isLast = source.last,
      dst = source.dst,
      tSeq = ROBIDValue(valid = true, wrap = false, value = source.seq),
      uSeq = ROBIDValue(valid = true, wrap = false, value = source.seq),
      peId = source.peId,
      stid = source.stid
    )
}

class TULinkRetireCommandPathSpec extends AnyFunSuite {
  import TULinkRelationCmapReference._
  import TULinkRetireCommandPathReference._

  private def id(value: Int): ROBIDValue =
    ROBIDValue(valid = true, wrap = false, value = value)

  test("reference serializes valid ROB dealloc sources in slot order") {
    val queue = new SourceQueue(sourceWidth = 3, queueDepth = 8)

    assert(queue.enqueueWindow(Seq(
      Source(seq = 0, dst = T),
      Source(seq = 1, dst = NoneKind, valid = false),
      Source(seq = 2, dst = U)
    )))
    assert(queue.enqueueWindow(Seq(
      Source(seq = 3, dst = T),
      Source(seq = 4, dst = NoneKind, valid = false),
      Source(seq = 5, dst = NoneKind, valid = false)
    )))

    assert(queue.drain().map(_.seq) == Vector(0, 2, 3))
  }

  test("reference requires enough capacity for a full source window") {
    val queue = new SourceQueue(sourceWidth = 2, queueDepth = 4)
    queue.preload(Seq(Source(0, T), Source(1, U), Source(2, T)))

    assert(!queue.sourceWindowReady)
    assert(!queue.enqueueWindow(Seq(Source(3, U), Source(4, T))))
    assert(queue.count == 3)
  }

  test("reference preserves no-destination block-last rows so relation cmap can drain") {
    val relation = new TULinkRelationCmapReference.Model(releaseThreshold = 4)
    relation.accept(toRelationRow(Source(seq = 0, dst = T)))
    relation.accept(toRelationRow(Source(seq = 1, dst = U)))

    assert(relation.accept(toRelationRow(Source(seq = 2, dst = NoneKind, last = true))) == Seq(
      Command(T, id(0), dealloc = true),
      Command(U, id(1), dealloc = true)
    ))
  }

  test("reference cleanup prunes queued sources before relation cmap consumption") {
    val queue = new SourceQueue(sourceWidth = 2, queueDepth = 8)
    queue.preload(Seq(
      Source(seq = 0, dst = T, bid = 1, gid = 0),
      Source(seq = 1, dst = U, bid = 2, gid = 0),
      Source(seq = 2, dst = T, bid = 3, gid = 0)
    ))

    queue.flushRelative(flushBid = 2, baseOnBid = false)
    assert(queue.seqs == Seq(0, 1))

    queue.cleanBlock(bid = 1)
    assert(queue.seqs == Seq(1))

    queue.preload(Seq(
      Source(seq = 3, dst = T, bid = 4, gid = 0),
      Source(seq = 4, dst = U, bid = 4, gid = 1)
    ))
    queue.cleanGroup(bid = 4, gid = 1)
    assert(queue.seqs == Seq(3))
  }

  test("reference auto block clean waits until block-last relation commands drain") {
    val scheduler = new BlockCleanScheduler

    assert(!scheduler.sourceWindowReady(rawReady = true, acceptingBlockLast = true))
    assert(scheduler.step(acceptingBlockLastBid = Some(7), relationPending = false) == BlockCleanStep(None, None))
    assert(scheduler.cleanPending)

    assert(!scheduler.sourceWindowReady(rawReady = true, acceptingBlockLast = false))
    assert(scheduler.step(acceptingBlockLastBid = None, relationPending = true) == BlockCleanStep(None, None))
    assert(scheduler.cleanPending)

    assert(scheduler.step(acceptingBlockLastBid = None, relationPending = false) ==
      BlockCleanStep(Some(BlockCleanEvent(7, 0)), None))
    assert(scheduler.localCommitPending)
    assert(!scheduler.sourceWindowReady(rawReady = true, acceptingBlockLast = false))

    assert(scheduler.step(acceptingBlockLastBid = None, relationPending = false) ==
      BlockCleanStep(None, Some(BlockCleanEvent(7, 0))))
    assert(!scheduler.pending)
    assert(scheduler.sourceWindowReady(rawReady = true, acceptingBlockLast = false))
  }

  test("reference local block commit follows auto clean and backpressures source admission") {
    val scheduler = new BlockCleanScheduler

    assert(scheduler.step(acceptingBlockLastBid = Some(3), relationPending = false) == BlockCleanStep(None, None))
    assert(scheduler.cleanPending)

    assert(scheduler.step(
      acceptingBlockLastBid = None,
      relationPending = false,
      localCommitReady = false) == BlockCleanStep(Some(BlockCleanEvent(3, 0)), None))
    assert(!scheduler.cleanPending)
    assert(scheduler.localCommitPending)
    assert(!scheduler.sourceWindowReady(rawReady = true, acceptingBlockLast = false))

    assert(scheduler.step(
      acceptingBlockLastBid = None,
      relationPending = false,
      localCommitReady = false) == BlockCleanStep(None, None))
    assert(scheduler.localCommitPending)
    assert(!scheduler.sourceWindowReady(rawReady = true, acceptingBlockLast = false))

    assert(scheduler.step(
      acceptingBlockLastBid = None,
      relationPending = false,
      localCommitReady = true) == BlockCleanStep(None, Some(BlockCleanEvent(3, 0))))
    assert(!scheduler.pending)
    assert(scheduler.sourceWindowReady(rawReady = true, acceptingBlockLast = false))
  }

  test("reference carries block-last STID through auto clean into local block commit") {
    val scheduler = new BlockCleanScheduler

    assert(scheduler.step(
      acceptingBlockLastBid = Some(5),
      relationPending = false,
      acceptingBlockLastStid = 3) == BlockCleanStep(None, None))
    assert(scheduler.step(acceptingBlockLastBid = None, relationPending = false) ==
      BlockCleanStep(Some(BlockCleanEvent(5, 3)), None))
    assert(scheduler.step(acceptingBlockLastBid = None, relationPending = false) ==
      BlockCleanStep(None, Some(BlockCleanEvent(5, 3))))
  }

  test("reference preserves source PE/STID through relation commands") {
    val relation = new TULinkRelationCmapReference.Model(releaseThreshold = 1)

    assert(relation.accept(toRelationRow(Source(seq = 0, dst = T, peId = 2, stid = 3))) == Seq(
      Command(T, id(0), dealloc = false, peId = 2, stid = 3)
    ))
    assert(relation.accept(toRelationRow(Source(seq = 1, dst = T, peId = 4, stid = 5))) == Seq(
      Command(T, id(1), dealloc = false, peId = 4, stid = 5),
      Command(T, id(0), dealloc = true, peId = 2, stid = 3)
    ))
  }

  test("TULinkRetireCommandPath elaborates as ROB-source serializer plus relation cmap") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val sv = ChiselStage.emitSystemVerilog(
      new TULinkRetireCommandPath(
        p = p,
        sourceWidth = 2,
        mapQDepth = 8,
        sourceQueueDepth = 8,
        cmapDepth = 8,
        releaseThreshold = 4,
        peIdWidth = 5,
        stidWidth = 4
      )
    )

    assert(sv.contains("module TULinkRetireCommandPath"))
    assert(sv.contains("module TULinkRelationCmap"))
    assert(sv.contains("io_sourceWindowReady"))
    assert(sv.contains("io_sourceValidMask"))
    assert(sv.contains("io_sourceQueueCount"))
    assert(sv.contains("io_cleanupActive"))
    assert(sv.contains("io_localBlockCommitReady"))
    assert(sv.contains("io_sourcePruneCount"))
    assert(sv.contains("io_relationPruneTCount"))
    assert(sv.contains("io_autoCleanBlockPending"))
    assert(sv.contains("io_autoCleanBlockValid"))
    assert(sv.contains("io_autoCleanBlockBid_value"))
    assert(sv.contains("io_localBlockCommitPending"))
    assert(sv.contains("io_localBlockCommitValid"))
    assert(sv.contains("io_localBlockCommitBid_value"))
    assert(sv.contains("io_localBlockCommitStid"))
    assert(sv.contains("io_localBlockCommitFire"))
    assert(sv.contains("io_command_valid"))
    assert(sv.contains("io_command_seq_value"))
    assert(sv.contains("io_command_peId"))
    assert(sv.contains("io_command_stid"))
    assert(sv.contains("io_commandFire"))
    assert(DestinationKind.T.asUInt.litValue == 2)
    assert(DestinationKind.U.asUInt.litValue == 3)
  }
}
