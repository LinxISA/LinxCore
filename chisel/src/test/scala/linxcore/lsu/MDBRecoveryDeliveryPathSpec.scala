package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object MDBRecoveryDeliveryPathReference {
  final case class Report(stid: Int, bid: Int, rid: Int)

  final class QueueModel(depth: Int) {
    require(depth > 0)
    private var reports = Vector.empty[Report]

    def enqueue(report: Report, recordReady: Boolean): Boolean = {
      val accepted = recordReady && reports.size < depth
      if (accepted) reports :+= report
      accepted
    }

    def head: Option[Report] = reports.headOption
    def acceptSource(): Unit = if (reports.nonEmpty) reports = reports.tail
    def size: Int = reports.size
  }

  def selectOldest[T](stid: Int, lanes: Vector[T]): Option[T] =
    if (stid >= 0 && stid < lanes.size) Some(lanes(stid)) else None
}

class MDBRecoveryDeliveryPathSpec extends AnyFunSuite {
  import MDBRecoveryDeliveryPathReference._

  test("record acceptance and recovery retention are atomic") {
    val queue = new QueueModel(depth = 2)
    val report = Report(stid = 1, bid = 4, rid = 7)

    assert(!queue.enqueue(report, recordReady = false))
    assert(queue.size == 0)
    assert(queue.enqueue(report, recordReady = true))
    assert(queue.head.contains(report))
    assert(queue.size == 1)

    assert(queue.head.contains(report))
    queue.acceptSource()
    assert(queue.head.isEmpty)
  }

  test("oldest recovery ownership is selected by report STID") {
    val lanes = Vector("stid0", "stid1")
    assert(selectOldest(stid = 1, lanes).contains("stid1"))
    assert(selectOldest(stid = 2, lanes).isEmpty)
  }

  test("Chisel delivery path elaborates queue, STID selection, and exact promotion") {
    val sv = ChiselStage.emitSystemVerilog(new MDBRecoveryDeliveryPath(
      entries = 8,
      recoveryQueueEntries = 4,
      stidCount = 2,
      bidWidth = 16
    ))
    assert(sv.contains("module MDBRecoveryDeliveryPath"))
    assert(sv.contains("module MDBConflictTransactionControl"))
    assert(sv.contains("module ScalarLSURecoverySource"))
    assert(sv.contains("io_recoveryCount"))
    assert(sv.contains("io_recoveryStidInRange"))
    assert(sv.contains("io_fullBidLookupRequest_rid_value"))
  }
}
