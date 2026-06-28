package linxcore.rob

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedCommitROBReference {
  final case class Row(
      pc: BigInt,
      insn: BigInt,
      length: Int,
      bid: BigInt,
      gid: BigInt,
      rid: BigInt,
      blockBid: BigInt,
      robValue: Int = -1,
      commitSlot: Int = -1)

  private final case class Entry(row: Row, complete: Boolean = false)

  final class Model(entries: Int, commitWidth: Int) {
    require(entries > 1 && (entries & (entries - 1)) == 0)
    require(commitWidth > 0 && commitWidth <= entries)

    private val table = Array.fill[Option[Entry]](entries)(None)
    private var head = 0
    private var tail = 0
    private var count = 0

    private def inc(value: Int): Int =
      (value + 1) & (entries - 1)

    private def duplicateIdentity(row: Row): Boolean =
      table.flatten.exists(e => (e.row.bid, e.row.gid, e.row.rid) == ((row.bid, row.gid, row.rid)))

    def size: Int = count

    def alloc(row: Row): Option[Int] = {
      if (count == entries || duplicateIdentity(row)) {
        return None
      }
      val rob = tail
      table(tail) = Some(Entry(row.copy(robValue = rob)))
      tail = inc(tail)
      count += 1
      Some(rob)
    }

    def complete(robValue: Int): Unit =
      table(robValue) = table(robValue).map(e => e.copy(complete = true))

    def commit(): Seq[Row] = {
      val out = collection.mutable.ArrayBuffer.empty[Row]
      while (out.size < commitWidth) {
        table(head) match {
          case Some(entry) if entry.complete =>
            out += entry.row.copy(commitSlot = out.size)
            table(head) = None
            head = inc(head)
            count -= 1
          case _ =>
            return out.toSeq
        }
      }
      out.toSeq
    }
  }
}

class ReducedCommitROBSpec extends AnyFunSuite {
  import ReducedCommitROBReference._

  private def row(n: Int): Row =
    Row(
      pc = 0x1000 + (n * 4),
      insn = 0x13,
      length = 4,
      bid = 1,
      gid = 0,
      rid = n,
      blockBid = 0x100 + n
    )

  test("reference retires contiguous completed head rows up to commit width") {
    val rob = new Model(entries = 8, commitWidth = 2)
    val r0 = rob.alloc(row(0)).get
    val r1 = rob.alloc(row(1)).get
    val r2 = rob.alloc(row(2)).get

    rob.complete(r0)
    rob.complete(r1)
    rob.complete(r2)

    val first = rob.commit()
    assert(first.map(_.rid) == Seq(0, 1))
    assert(first.map(_.commitSlot) == Seq(0, 1))
    assert(rob.size == 1)

    val second = rob.commit()
    assert(second.map(_.rid) == Seq(2))
    assert(second.map(_.commitSlot) == Seq(0))
    assert(rob.size == 0)
  }

  test("reference blocks on the first incomplete head even when younger rows complete") {
    val rob = new Model(entries = 8, commitWidth = 4)
    val r0 = rob.alloc(row(0)).get
    val r1 = rob.alloc(row(1)).get
    val r2 = rob.alloc(row(2)).get
    val r3 = rob.alloc(row(3)).get

    rob.complete(r0)
    rob.complete(r1)
    rob.complete(r3)

    assert(rob.commit().map(_.rid) == Seq(0, 1))
    assert(rob.commit().isEmpty)

    rob.complete(r2)
    assert(rob.commit().map(_.rid) == Seq(2, 3))
  }

  test("reference rejects duplicate CommitInfo identity") {
    val rob = new Model(entries = 8, commitWidth = 2)
    assert(rob.alloc(row(4)).nonEmpty)
    assert(rob.alloc(row(4)).isEmpty)
  }

  test("reference preserves block BID sideband independently of CommitInfo bid") {
    val rob = new Model(entries = 8, commitWidth = 2)
    val entry = row(5).copy(bid = 7, blockBid = BigInt("20000007f", 16))
    val rid = rob.alloc(entry).get
    rob.complete(rid)

    val committed = rob.commit().head
    assert(committed.bid == 7)
    assert(committed.blockBid == BigInt("20000007f", 16))
  }

  test("Chisel ReducedCommitROB elaborates with the commit trace port") {
    val sv = ChiselStage.emitSystemVerilog(
      new ReducedCommitROB(
        entries = 8,
        traceParams = CommitTraceParams(commitWidth = 2, robValueWidth = 3)
      )
    )
    assert(sv.contains("module ReducedCommitROB"))
    assert(sv.contains("commit"))
  }
}
