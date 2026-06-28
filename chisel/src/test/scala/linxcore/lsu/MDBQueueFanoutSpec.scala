package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object MDBQueueFanoutReference {
  import STQFlushPruneReference.Id

  final case class Info(
      pc: BigInt = 0,
      bid: Id = Id(),
      lsId: Id = Id(),
      stid: Int = 0,
      addr: BigInt = 0,
      size: Int = 0,
      waitStorePc: BigInt = 0,
      isTile: Boolean = false)

  final case class Bus(
      ldInfo: Info,
      stInfo: Info = Info(),
      conf: Int = 1,
      hit: Boolean = false,
      valid: Boolean = true)

  final case class StoreRow(
      index: Int,
      pc: BigInt,
      bid: Id,
      lsId: Id = Id(),
      stid: Int = 0,
      addr: BigInt = 0,
      size: Int = 0,
      valid: Boolean = true,
      addrReady: Boolean = true,
      dataReady: Boolean = true,
      isTile: Boolean = false)

  final case class Wakeup(valid: Boolean = false, index: Int = 0, pc: BigInt = 0, bid: Id = Id(), addr: BigInt = 0, size: Int = 0)

  final case class StepResult(
      lookupAccepted: Boolean,
      deleteAccepted: Boolean,
      recordAccepted: Boolean,
      lookupProcessed: Boolean,
      deleteProcessed: Boolean,
      recordProcessed: Boolean,
      phaseStalledByFanout: Boolean,
      luOut: Option[Bus],
      suOut: Option[Bus],
      suMatchedStore: Boolean,
      suStorePending: Boolean,
      suWakeup: Wakeup,
      bmdbReport: Boolean)

  final class Model(commandDepth: Int = 4, outputDepth: Int = 4, ssitDepth: Int = 4) {
    private val ssit = new MDBSSITReference.Model(depth = ssitDepth)
    private var lookupQ = Vector.empty[Bus]
    private var deleteQ = Vector.empty[Bus]
    private var recordQ = Vector.empty[Bus]
    private var luOutQ = Vector.empty[Bus]
    private var suOutQ = Vector.empty[Bus]

    def luOutputDepth: Int = luOutQ.size
    def suOutputDepth: Int = suOutQ.size

    private def enqueue(queue: Vector[Bus], bus: Option[Bus], depth: Int): (Vector[Bus], Boolean) =
      bus match {
        case Some(value) if queue.size < depth => (queue :+ value, true)
        case Some(_) => (queue, false)
        case None => (queue, false)
      }

    private def storeWakeup(bus: Bus, rows: Seq[StoreRow]): (Boolean, Boolean, Wakeup) = {
      if (!bus.hit) {
        return (false, false, Wakeup())
      }
      val matched = rows.find(row => row.valid && !row.isTile && row.bid == bus.stInfo.bid && row.pc == bus.stInfo.pc)
      matched match {
        case Some(row) if row.addrReady && row.dataReady =>
          (true, false, Wakeup(valid = true, index = row.index, pc = row.pc, bid = row.bid, addr = row.addr, size = row.size))
        case Some(_) =>
          (true, true, Wakeup())
        case None =>
          (false, false, Wakeup())
      }
    }

    def step(
        lookup: Option[Bus] = None,
        delete: Option[Bus] = None,
        record: Option[Bus] = None,
        luReady: Boolean = false,
        suReady: Boolean = false,
        storeRows: Seq[StoreRow] = Seq.empty): StepResult = {
      val luOut = if (luReady && luOutQ.nonEmpty) Some(luOutQ.head) else None
      if (luOut.nonEmpty) {
        luOutQ = luOutQ.tail
      }

      val suOut = if (suReady && suOutQ.nonEmpty) Some(suOutQ.head) else None
      if (suOut.nonEmpty) {
        suOutQ = suOutQ.tail
      }
      val (suMatched, suPending, wakeup) = suOut.map(storeWakeup(_, storeRows)).getOrElse((false, false, Wakeup()))

      val lookupEnq = enqueue(lookupQ, lookup, commandDepth)
      lookupQ = lookupEnq._1
      val deleteEnq = enqueue(deleteQ, delete, commandDepth)
      deleteQ = deleteEnq._1
      val recordEnq = enqueue(recordQ, record, commandDepth)
      recordQ = recordEnq._1

      val lookupCanFanout = lookupQ.nonEmpty && luOutQ.size < outputDepth && suOutQ.size < outputDepth
      val phaseStalled = lookupQ.nonEmpty && !lookupCanFanout
      if (lookupCanFanout) {
        val bus = lookupQ.head
        lookupQ = lookupQ.tail
        val result = ssit.lookup(bus.ldInfo.pc, bus.ldInfo.bid)
        val out = bus.copy(
          hit = result.hit,
          stInfo = bus.stInfo.copy(pc = result.storePc, bid = result.storeBid, stid = bus.ldInfo.stid),
          valid = true
        )
        luOutQ :+= out
        suOutQ :+= out
      }

      var deleteProcessed = false
      if (!phaseStalled && deleteQ.nonEmpty) {
        val bus = deleteQ.head
        deleteQ = deleteQ.tail
        ssit.delete(bus.ldInfo.pc, bus.ldInfo.waitStorePc)
        deleteProcessed = true
      }

      var recordProcessed = false
      var bmdbReport = false
      if (!phaseStalled && recordQ.nonEmpty) {
        val bus = recordQ.head
        recordQ = recordQ.tail
        val result = ssit.record(
          loadPc = bus.ldInfo.pc,
          loadBid = bus.ldInfo.bid,
          loadLsId = bus.ldInfo.lsId,
          storePc = bus.stInfo.pc,
          storeBid = bus.stInfo.bid,
          storeLsId = bus.stInfo.lsId,
          conf = bus.conf
        )
        recordProcessed = true
        bmdbReport = result.accepted
      }

      StepResult(
        lookupAccepted = lookupEnq._2,
        deleteAccepted = deleteEnq._2,
        recordAccepted = recordEnq._2,
        lookupProcessed = lookupCanFanout,
        deleteProcessed = deleteProcessed,
        recordProcessed = recordProcessed,
        phaseStalledByFanout = phaseStalled,
        luOut = luOut,
        suOut = suOut,
        suMatchedStore = suMatched,
        suStorePending = suPending,
        suWakeup = wakeup,
        bmdbReport = bmdbReport
      )
    }
  }
}

class MDBQueueFanoutSpec extends AnyFunSuite {
  import MDBQueueFanoutReference._
  import STQFlushPruneReference.Id

  private def id(value: Int, wrap: Boolean = false): Id =
    Id(wrap = wrap, value = value)

  private def lookup(pc: BigInt, bid: Int, lsId: Int = 0): Bus =
    Bus(ldInfo = Info(pc = pc, bid = id(bid), lsId = id(lsId), stid = 1))

  private def record(loadPc: BigInt, loadBid: Int, loadLsId: Int, storePc: BigInt, storeBid: Int, storeLsId: Int): Bus =
    Bus(
      ldInfo = Info(pc = loadPc, bid = id(loadBid), lsId = id(loadLsId), stid = 1),
      stInfo = Info(pc = storePc, bid = id(storeBid), lsId = id(storeLsId), stid = 1),
      conf = 1
    )

  private def delete(loadPc: BigInt, loadBid: Int, waitPc: BigInt): Bus =
    Bus(ldInfo = Info(pc = loadPc, bid = id(loadBid), waitStorePc = waitPc, stid = 1))

  test("lookup result fans out to LU and SU, and SU wakeup uses the predicted store row") {
    val model = new Model()
    model.step(record = Some(record(0x1000, 4, 7, 0x2000, 2, 5)))
    model.step(record = Some(record(0x1000, 5, 8, 0x2000, 3, 6)))
    model.step(lookup = Some(lookup(0x1000, 5, 8)))
    model.step(luReady = true, suReady = true, storeRows = Seq(StoreRow(index = 0, pc = 0x2000, bid = id(3))))

    model.step(lookup = Some(lookup(0x1000, 5, 8)))
    val hit = model.step(
      luReady = true,
      suReady = true,
      storeRows = Seq(StoreRow(index = 2, pc = 0x2000, bid = id(3), addr = 0x8040, size = 8))
    )

    assert(hit.luOut.exists(_.hit))
    assert(hit.suOut.exists(_.hit))
    assert(hit.suMatchedStore)
    assert(!hit.suStorePending)
    assert(hit.suWakeup.valid)
    assert(hit.suWakeup.index == 2)
    assert(hit.suWakeup.addr == 0x8040)
  }

  test("full fanout output queue stalls lookup and freezes later MDB phases") {
    val model = new Model(commandDepth = 4, outputDepth = 1)
    model.step(record = Some(record(0x1000, 4, 7, 0x2000, 2, 5)))
    model.step(record = Some(record(0x1000, 5, 8, 0x2000, 3, 6)))
    model.step(lookup = Some(lookup(0x1000, 5, 8)))
    assert(model.suOutputDepth == 1)

    val stalled = model.step(
      lookup = Some(lookup(0x1000, 5, 8)),
      delete = Some(delete(0x1000, 5, 0x2000)),
      record = Some(record(0x1100, 7, 7, 0x2100, 6, 6)),
      luReady = true,
      suReady = false
    )

    assert(stalled.phaseStalledByFanout)
    assert(!stalled.lookupProcessed)
    assert(!stalled.deleteProcessed)
    assert(!stalled.recordProcessed)
  }

  test("SU mdbCheck reports pending for not-ready stores and ignores tile rows") {
    val model = new Model()
    model.step(record = Some(record(0x1000, 4, 7, 0x2000, 2, 5)))
    model.step(record = Some(record(0x1000, 5, 8, 0x2000, 3, 6)))
    model.step(lookup = Some(lookup(0x1000, 5, 8)))
    model.step(luReady = true, suReady = true, storeRows = Seq(StoreRow(index = 0, pc = 0x2000, bid = id(3))))

    model.step(lookup = Some(lookup(0x1000, 5, 8)))
    val pending = model.step(
      luReady = true,
      suReady = true,
      storeRows = Seq(StoreRow(index = 0, pc = 0x2000, bid = id(3), dataReady = false))
    )

    assert(pending.suMatchedStore)
    assert(pending.suStorePending)
    assert(!pending.suWakeup.valid)

    model.step(lookup = Some(lookup(0x1000, 5, 8)))
    val tileIgnored = model.step(
      luReady = true,
      suReady = true,
      storeRows = Seq(StoreRow(index = 0, pc = 0x2000, bid = id(3), isTile = true))
    )

    assert(!tileIgnored.suMatchedStore)
    assert(!tileIgnored.suWakeup.valid)
  }

  test("records publish BMDB report intent only when SSIT accepts the record") {
    val model = new Model(ssitDepth = 1)
    val accepted = model.step(record = Some(record(0x1000, 4, 7, 0x2000, 2, 5)))
    val overflow = model.step(record = Some(record(0x1100, 5, 8, 0x2100, 3, 6)))

    assert(accepted.bmdbReport)
    assert(!overflow.bmdbReport)
  }

  test("Chisel MDBQueueFanout elaborates with command queues, fanout queues, and SU wakeup outputs") {
    val sv = ChiselStage.emitSystemVerilog(new MDBQueueFanout(entries = 8, ssitEntries = 4, commandQueueEntries = 4, outputQueueEntries = 4, storeEntries = 4))

    assert(sv.contains("module MDBQueueFanout"))
    assert(sv.contains("Queue"))
    assert(sv.contains("MDBSSIT"))
    assert(sv.contains("io_luOutValid"))
    assert(sv.contains("io_suWakeup_valid"))
    assert(sv.contains("io_bmdbReportValid"))
  }
}
