package linxcore.rob

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import org.scalatest.funsuite.AnyFunSuite

object ROBEntryBankReference {
  sealed trait Status
  case object Free extends Status
  case object Allocated extends Status
  case object Completed extends Status
  case object Retired extends Status

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

  private final case class Entry(row: Row, status: Status)

  final class Model(entries: Int, commitWidth: Int) {
    require(entries > 1 && (entries & (entries - 1)) == 0)
    require(commitWidth > 0 && commitWidth <= entries)

    private val table = Array.fill[Option[Entry]](entries)(None)
    private var allocPtr = 0
    private var commitPtr = 0
    private var deallocPtr = 0
    private var count = 0
    private var outstanding = 0

    private def inc(value: Int): Int =
      (value + 1) & (entries - 1)

    private def duplicateIdentity(row: Row): Boolean =
      table.flatten.exists(e => (e.row.bid, e.row.gid, e.row.rid) == ((row.bid, row.gid, row.rid)))

    def size: Int = count

    def outstandingCount: Int = outstanding

    def statusAt(value: Int): Status =
      table(value).map(_.status).getOrElse(Free)

    def alloc(row: Row): Option[Int] = {
      if (count == entries || duplicateIdentity(row)) {
        return None
      }
      val rob = allocPtr
      table(allocPtr) = Some(Entry(row.copy(robValue = rob), Allocated))
      allocPtr = inc(allocPtr)
      count += 1
      outstanding += 1
      Some(rob)
    }

    def complete(robValue: Int): Boolean =
      table(robValue) match {
        case Some(entry) if entry.status == Allocated || entry.status == Completed =>
          table(robValue) = Some(entry.copy(status = Completed))
          true
        case _ =>
          false
      }

    def commit(): Seq[Row] = {
      val out = collection.mutable.ArrayBuffer.empty[Row]
      while (out.size < commitWidth) {
        table(commitPtr) match {
          case Some(entry) if entry.status == Completed =>
            out += entry.row.copy(commitSlot = out.size)
            table(commitPtr) = Some(entry.copy(status = Retired))
            commitPtr = inc(commitPtr)
            outstanding -= 1
          case _ =>
            return out.toSeq
        }
      }
      out.toSeq
    }

    def dealloc(ready: Boolean = true): Seq[Int] = {
      if (!ready) {
        return Seq.empty
      }
      val out = collection.mutable.ArrayBuffer.empty[Int]
      while (out.size < commitWidth) {
        table(deallocPtr) match {
          case Some(entry) if entry.status == Retired =>
            out += deallocPtr
            table(deallocPtr) = None
            deallocPtr = inc(deallocPtr)
            count -= 1
          case _ =>
            return out.toSeq
        }
      }
      out.toSeq
    }
  }
}

class ROBEntryBankSpec extends AnyFunSuite {
  import ROBEntryBankReference._

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

  test("reference keeps commit and dealloc as separate pointer walks") {
    val rob = new Model(entries = 8, commitWidth = 2)
    val r0 = rob.alloc(row(0)).get

    assert(rob.statusAt(r0) == Allocated)
    assert(rob.size == 1)
    assert(rob.outstandingCount == 1)

    assert(rob.complete(r0))
    val committed = rob.commit()
    assert(committed.map(_.rid) == Seq(0))
    assert(committed.map(_.commitSlot) == Seq(0))
    assert(rob.statusAt(r0) == Retired)
    assert(rob.size == 1)
    assert(rob.outstandingCount == 0)

    assert(rob.dealloc() == Seq(r0))
    assert(rob.statusAt(r0) == Free)
    assert(rob.size == 0)
  }

  test("reference blocks commit on incomplete head even when younger rows complete") {
    val rob = new Model(entries = 8, commitWidth = 2)
    val r0 = rob.alloc(row(0)).get
    val r1 = rob.alloc(row(1)).get
    val r2 = rob.alloc(row(2)).get

    assert(rob.complete(r1))
    assert(rob.complete(r2))
    assert(rob.commit().isEmpty)

    assert(rob.complete(r0))
    assert(rob.commit().map(_.rid) == Seq(0, 1))
    assert(rob.commit().map(_.rid) == Seq(2))
  }

  test("reference rejects duplicate identity until deallocation frees the row") {
    val rob = new Model(entries = 8, commitWidth = 2)
    val first = rob.alloc(row(4)).get
    assert(rob.alloc(row(4)).isEmpty)

    assert(rob.complete(first))
    assert(rob.commit().map(_.rid) == Seq(4))
    assert(rob.alloc(row(4)).isEmpty)

    assert(rob.dealloc() == Seq(first))
    assert(rob.alloc(row(4)).nonEmpty)
  }

  test("reference deallocReady can hold retired rows and keep the bank full") {
    val rob = new Model(entries = 2, commitWidth = 2)
    val r0 = rob.alloc(row(0)).get
    val r1 = rob.alloc(row(1)).get
    assert(rob.complete(r0))
    assert(rob.complete(r1))

    assert(rob.commit().map(_.rid) == Seq(0, 1))
    assert(rob.size == 2)
    assert(rob.outstandingCount == 0)
    assert(rob.alloc(row(2)).isEmpty)
    assert(rob.dealloc(ready = false).isEmpty)
    assert(rob.size == 2)

    assert(rob.dealloc() == Seq(r0, r1))
    assert(rob.alloc(row(2)).nonEmpty)
  }

  test("reference ignores completion for free and retired rows") {
    val rob = new Model(entries = 8, commitWidth = 2)
    assert(!rob.complete(0))

    val r0 = rob.alloc(row(0)).get
    assert(rob.complete(r0))
    assert(rob.commit().map(_.rid) == Seq(0))
    assert(!rob.complete(r0))
  }

  test("Chisel ROBEntryBank elaborates with status masks and commit monitor outputs") {
    val sv = ChiselStage.emitSystemVerilog(
      new ROBEntryBank(
        entries = 8,
        traceParams = CommitTraceParams(commitWidth = 2, robValueWidth = 3)
      )
    )

    assert(sv.contains("module ROBEntryBank"))
    assert(sv.contains("io_deallocReady"))
    assert(sv.contains("io_completedMask"))
    assert(sv.contains("io_retiredMask"))
    assert(sv.contains("io_commitContractError"))
    assert(sv.contains("CommitTraceMonitor"))
  }
}
