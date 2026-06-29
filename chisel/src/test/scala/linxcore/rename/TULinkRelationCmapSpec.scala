package linxcore.rename

import circt.stage.ChiselStage
import linxcore.common.{DestinationKind, InterfaceParams}
import linxcore.rob.{ROBIDReference, ROBIDValue}
import org.scalatest.funsuite.AnyFunSuite

object TULinkRelationCmapReference {
  sealed trait Kind
  case object T extends Kind
  case object U extends Kind
  case object NoneKind extends Kind

  final case class Row(
      bid: ROBIDValue,
      gid: ROBIDValue,
      isLast: Boolean,
      dst: Kind,
      tSeq: ROBIDValue,
      uSeq: ROBIDValue,
      peId: Int = 0,
      stid: Int = 0)
  final case class Command(kind: Kind, seq: ROBIDValue, dealloc: Boolean, peId: Int = 0, stid: Int = 0)
  private final case class Entry(bid: ROBIDValue, gid: ROBIDValue, seq: ROBIDValue, peId: Int = 0, stid: Int = 0)

  final class Model(releaseThreshold: Int = 4) {
    private var t = Vector.empty[Entry]
    private var u = Vector.empty[Entry]

    private def drainBefore(row: Row): Vector[Command] = {
      var out = Vector.empty[Command]
      def shouldDrain(entries: Vector[Entry]): Boolean =
        entries.nonEmpty && (row.isLast || entries.last.bid != row.bid || entries.last.gid != row.gid)

      if (shouldDrain(t)) {
        out ++= t.map(e => Command(T, e.seq, dealloc = true))
        t = Vector.empty
      }
      if (shouldDrain(u)) {
        out ++= u.map(e => Command(U, e.seq, dealloc = true))
        u = Vector.empty
      }
      out
    }

    def accept(row: Row): Vector[Command] = {
      var out = drainBefore(row)
      row.dst match {
        case T =>
          val releaseAfterMark = row.isLast || t.size >= releaseThreshold
          t :+= Entry(row.bid, row.gid, row.tSeq, row.peId, row.stid)
          out :+= Command(T, row.tSeq, dealloc = false, peId = row.peId, stid = row.stid)
          if (releaseAfterMark) {
            out :+= Command(T, t.head.seq, dealloc = true, peId = t.head.peId, stid = t.head.stid)
            t = t.tail
          }
        case U =>
          val releaseAfterMark = row.isLast || u.size >= releaseThreshold
          u :+= Entry(row.bid, row.gid, row.uSeq, row.peId, row.stid)
          out :+= Command(U, row.uSeq, dealloc = false, peId = row.peId, stid = row.stid)
          if (releaseAfterMark) {
            out :+= Command(U, u.head.seq, dealloc = true, peId = u.head.peId, stid = u.head.stid)
            u = u.tail
          }
        case NoneKind =>
      }
      out
    }

    def cleanBlock(bid: ROBIDValue): Unit = {
      t = t.filterNot(_.bid == bid)
      u = u.filterNot(_.bid == bid)
    }

    def cleanGroup(bid: ROBIDValue, gid: ROBIDValue): Unit = {
      t = t.filterNot(e => e.bid == bid && e.gid == gid)
      u = u.filterNot(e => e.bid == bid && e.gid == gid)
    }

    def flushRelative(flushBid: ROBIDValue, baseOnBid: Boolean): Unit = {
      def needsFlush(entry: Entry): Boolean =
        if (baseOnBid) ROBIDReference.lessEqual(flushBid, entry.bid)
        else ROBIDReference.less(flushBid, entry.bid)

      def pruneSuffix(values: Vector[Entry]): Vector[Entry] = {
        var out = values
        while (out.nonEmpty && needsFlush(out.last)) {
          out = out.dropRight(1)
        }
        out
      }

      t = pruneSuffix(t)
      u = pruneSuffix(u)
    }

    def preloadT(entries: Seq[(ROBIDValue, ROBIDValue, ROBIDValue)]): Unit = {
      t = entries.map { case (bid, gid, seq) => Entry(bid, gid, seq) }.toVector
    }

    def preloadU(entries: Seq[(ROBIDValue, ROBIDValue, ROBIDValue)]): Unit = {
      u = entries.map { case (bid, gid, seq) => Entry(bid, gid, seq) }.toVector
    }

    def tCount: Int = t.size
    def uCount: Int = u.size
    def tSeqs: Seq[ROBIDValue] = t.map(_.seq)
    def uSeqs: Seq[ROBIDValue] = u.map(_.seq)
  }
}

class TULinkRelationCmapSpec extends AnyFunSuite {
  import TULinkRelationCmapReference._

  private def id(value: Int, wrap: Boolean = false): ROBIDValue =
    ROBIDValue(valid = true, wrap = wrap, value = value)

  private def row(
      seq: Int,
      dst: Kind,
      bid: Int = 1,
      gid: Int = 0,
      last: Boolean = false): Row =
    Row(
      bid = id(bid),
      gid = id(gid),
      isLast = last,
      dst = dst,
      tSeq = id(seq),
      uSeq = id(seq)
    )

  test("reference marks each T destination and pressure-releases the oldest fifth relation") {
    val model = new Model(releaseThreshold = 4)
    val commands = (0 to 4).flatMap(seq => model.accept(row(seq, T)))

    assert(commands == Seq(
      Command(T, id(0), dealloc = false),
      Command(T, id(1), dealloc = false),
      Command(T, id(2), dealloc = false),
      Command(T, id(3), dealloc = false),
      Command(T, id(4), dealloc = false),
      Command(T, id(0), dealloc = true)
    ))
    assert(model.tCount == 4)
  }

  test("reference drains older T entries before U entries on group change") {
    val model = new Model(releaseThreshold = 4)
    model.accept(row(0, T, bid = 1, gid = 0))
    model.accept(row(1, U, bid = 1, gid = 0))

    assert(model.accept(row(2, U, bid = 1, gid = 1)) == Seq(
      Command(T, id(0), dealloc = true),
      Command(U, id(1), dealloc = true),
      Command(U, id(2), dealloc = false)
    ))
    assert(model.tCount == 0)
    assert(model.uCount == 1)
  }

  test("reference block-last drains old relations before marking and releasing current destination") {
    val model = new Model(releaseThreshold = 4)
    model.accept(row(0, T, bid = 1, gid = 0))
    model.accept(row(1, U, bid = 1, gid = 0))

    assert(model.accept(row(2, T, bid = 1, gid = 0, last = true)) == Seq(
      Command(T, id(0), dealloc = true),
      Command(U, id(1), dealloc = true),
      Command(T, id(2), dealloc = false),
      Command(T, id(2), dealloc = true)
    ))
    assert(model.tCount == 0)
    assert(model.uCount == 0)
  }

  test("reference carries row PE/STID into mark and release commands") {
    val model = new Model(releaseThreshold = 1)

    assert(model.accept(row(0, T).copy(peId = 2, stid = 3)) == Seq(
      Command(T, id(0), dealloc = false, peId = 2, stid = 3)
    ))
    assert(model.accept(row(1, T).copy(peId = 4, stid = 5)) == Seq(
      Command(T, id(1), dealloc = false, peId = 4, stid = 5),
      Command(T, id(0), dealloc = true, peId = 2, stid = 3)
    ))
  }

  test("reference block clean removes matching BID entries while preserving other relation order") {
    val model = new Model(releaseThreshold = 4)
    model.preloadT(Seq((id(1), id(0), id(0)), (id(2), id(0), id(1))))
    model.preloadU(Seq((id(1), id(1), id(2)), (id(3), id(0), id(3))))

    model.cleanBlock(id(1))

    assert(model.tSeqs == Seq(id(1)))
    assert(model.uSeqs == Seq(id(3)))
  }

  test("reference group clean removes only matching BID/GID relation entries") {
    val model = new Model(releaseThreshold = 4)
    model.preloadT(Seq((id(1), id(0), id(0)), (id(1), id(1), id(1))))
    model.preloadU(Seq((id(1), id(0), id(2)), (id(2), id(0), id(3))))

    model.cleanGroup(id(1), id(0))

    assert(model.tSeqs == Seq(id(1)))
    assert(model.uSeqs == Seq(id(3)))
  }

  test("reference flush relative prunes only the newest suffix") {
    val model = new Model(releaseThreshold = 4)
    model.preloadT(Seq((id(1), id(0), id(0)), (id(2), id(0), id(1)), (id(3), id(0), id(2))))

    model.flushRelative(flushBid = id(2), baseOnBid = false)
    assert(model.tSeqs == Seq(id(0), id(1)))

    model.flushRelative(flushBid = id(2), baseOnBid = true)
    assert(model.tSeqs == Seq(id(0)))
  }

  test("TULinkRelationCmap elaborates as a serialized retire command owner") {
    val p = InterfaceParams(robEntries = 8)
    val sv = ChiselStage.emitSystemVerilog(
      new TULinkRelationCmap(p = p, mapQDepth = 8, cmapDepth = 8, releaseThreshold = 4, stidWidth = 4)
    )

    assert(sv.contains("module TULinkRelationCmap"))
    assert(sv.contains("io_inReady"))
    assert(sv.contains("io_command_valid"))
    assert(sv.contains("io_command_seq_value"))
    assert(sv.contains("io_command_dealloc"))
    assert(sv.contains("io_command_peId"))
    assert(sv.contains("io_command_stid"))
    assert(sv.contains("io_preReleaseT"))
    assert(sv.contains("io_pressureReleaseT"))
    assert(sv.contains("io_flush_req_valid"))
    assert(sv.contains("io_cleanupPruneTCount"))
    assert(DestinationKind.T.asUInt.litValue == 2)
    assert(DestinationKind.U.asUInt.litValue == 3)
  }
}
