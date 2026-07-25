package linxcore.rob

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ROBEntryStatusReference {
  sealed abstract class Status(val value: Int)
  case object Free extends Status(0)
  case object Allocated extends Status(1)
  case object Renamed extends Status(2)
  case object Issued extends Status(3)
  case object Completed extends Status(4)
  case object Retired extends Status(5)
  case object Fault extends Status(6)
  case object NeedFlush extends Status(7)
  case object ReservedUnfilled extends Status(8)

  val all: Seq[Status] =
    Seq(Free, Allocated, Renamed, Issued, Completed, Retired, Fault, NeedFlush, ReservedUnfilled)

  def occupiesRob(status: Status): Boolean = status != Free

  def osdActive(status: Status): Boolean =
    Set[Status](Allocated, Renamed, Issued, Completed, NeedFlush, ReservedUnfilled).contains(status)

  def canCommit(status: Status): Boolean = status == Completed

  def canDealloc(status: Status): Boolean = status == Retired

  def flushClears(status: Status): Boolean = osdActive(status)
}

class ROBEntryStatusProbeIO extends Bundle {
  val status = Input(ROBEntryStatus())
  val values = Output(Vec(9, UInt(4.W)))
  val occupiesRob = Output(Bool())
  val osdActive = Output(Bool())
  val canCommit = Output(Bool())
  val canDealloc = Output(Bool())
  val flushClears = Output(Bool())
}

class ROBEntryStatusProbe extends Module {
  val io = IO(new ROBEntryStatusProbeIO)

  io.values(0) := ROBEntryStatus.Free.asUInt
  io.values(1) := ROBEntryStatus.Allocated.asUInt
  io.values(2) := ROBEntryStatus.Renamed.asUInt
  io.values(3) := ROBEntryStatus.Issued.asUInt
  io.values(4) := ROBEntryStatus.Completed.asUInt
  io.values(5) := ROBEntryStatus.Retired.asUInt
  io.values(6) := ROBEntryStatus.Fault.asUInt
  io.values(7) := ROBEntryStatus.NeedFlush.asUInt
  io.values(8) := ROBEntryStatus.ReservedUnfilled.asUInt

  io.occupiesRob := ROBEntryStatus.occupiesRob(io.status)
  io.osdActive := ROBEntryStatus.osdActive(io.status)
  io.canCommit := ROBEntryStatus.canCommit(io.status)
  io.canDealloc := ROBEntryStatus.canDealloc(io.status)
  io.flushClears := ROBEntryStatus.flushClears(io.status)
}

class ROBEntryStatusSpec extends AnyFunSuite {
  import ROBEntryStatusReference._

  test("reference preserves LinxCoreModel PROBStatus numeric order") {
    assert(all.map(_.value) == Seq(0, 1, 2, 3, 4, 5, 6, 7, 8))
  }

  test("reference separates ROB residency from outstanding commit work") {
    assert(all.filter(occupiesRob).map(_.value) == Seq(1, 2, 3, 4, 5, 6, 7, 8))
    assert(all.filter(osdActive).map(_.value) == Seq(1, 2, 3, 4, 7, 8))
  }

  test("reference keeps commit and dealloc as separate status predicates") {
    assert(all.filter(canCommit) == Seq(Completed))
    assert(all.filter(canDealloc) == Seq(Retired))
  }

  test("reference flush-clears only outstanding non-retired work") {
    assert(all.filter(flushClears).map(_.value) == Seq(1, 2, 3, 4, 7, 8))
  }

  test("ROBEntryStatus probe IO widths cover all model statuses") {
    val io = new ROBEntryStatusProbeIO

    assert(io.status.getWidth == 4)
    assert(io.values.head.getWidth == 4)
  }

  test("ROBEntryStatus elaborates helper predicates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new ROBEntryStatusProbe)

    assert(sv.contains("module ROBEntryStatusProbe"))
    assert(sv.contains("io_values_0"))
    assert(sv.contains("io_values_8"))
    assert(sv.contains("io_osdActive"))
    assert(sv.contains("io_canCommit"))
    assert(sv.contains("io_canDealloc"))
    assert(sv.contains("io_flushClears"))
  }
}
