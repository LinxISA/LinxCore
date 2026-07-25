package linxcore.backend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.log2Ceil
import circt.stage.ChiselStage
import linxcore.common.{InterfaceParams, TemplateD3Constants, TemplateD3InterfaceParams, TemplateForm}
import org.scalatest.funsuite.AnyFunSuite

object TemplateD3ReservationAllocatorReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)
  final case class Reservation(first: Id, last: Id, slots: Seq[Int])

  final class Model(entries: Int) {
    require(entries > 1 && (entries & (entries - 1)) == 0)

    private val live = Array.fill(entries)(false)
    private val bid = Array.fill(entries)(Id(valid = false))
    private val rid = Array.fill(entries)(Id(valid = false))
    private var allocPtr = 0
    private var allocWrap = false
    private var count = 0

    private def advance(id: Id, amount: Int): Id = {
      val sum = id.value + amount
      Id(valid = id.valid, wrap = id.wrap ^ (sum >= entries), value = sum & (entries - 1))
    }

    private def less(lhs: Id, rhs: Id): Boolean =
      if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

    private def equal(lhs: Id, rhs: Id): Boolean =
      lhs.valid == rhs.valid && lhs.wrap == rhs.wrap && lhs.value == rhs.value

    private def lessEqual(lhs: Id, rhs: Id): Boolean =
      less(lhs, rhs) || equal(lhs, rhs)

    private def lessEqualBidRid(srcBid: Id, srcRid: Id, dstBid: Id, dstRid: Id): Boolean =
      less(srcBid, dstBid) || (equal(srcBid, dstBid) && lessEqual(srcRid, dstRid))

    def liveCount: Int = count
    def freeCount: Int = entries - count
    def allocValue: Int = allocPtr
    def allocWrapBit: Boolean = allocWrap
    def liveMask: BigInt =
      live.zipWithIndex.foldLeft(BigInt(0)) { case (mask, (valid, idx)) =>
        if (valid) mask | (BigInt(1) << idx) else mask
      }

    def reserve(rowCount: Int, rowBid: Id = Id()): Option[Reservation] = {
      if (rowCount <= 0 || rowCount > TemplateD3Constants.MaxRows || rowCount > freeCount) {
        return None
      }
      val first = Id(wrap = allocWrap, value = allocPtr)
      val slots = (0 until rowCount).map(offset => advance(first, offset))
      slots.foreach { rowRid =>
        live(rowRid.value) = true
        bid(rowRid.value) = rowBid
        rid(rowRid.value) = rowRid
      }
      val next = advance(first, rowCount)
      allocPtr = next.value
      allocWrap = next.wrap
      count += rowCount
      Some(Reservation(first, slots.last, slots.map(_.value)))
    }

    def flush(flushBid: Id, flushRid: Id, baseOnBid: Boolean): Seq[Int] = {
      val pruned = live.indices.filter { idx =>
        live(idx) && {
          if (baseOnBid) lessEqual(flushBid, bid(idx))
          else lessEqualBidRid(flushBid, flushRid, bid(idx), rid(idx))
        }
      }
      val oldestPruned = pruned.headOption.map(idx => rid(idx))
      pruned.foreach { idx =>
        live(idx) = false
        bid(idx) = Id(valid = false)
        rid(idx) = Id(valid = false)
      }
      count -= pruned.size
      oldestPruned.foreach { first =>
        allocPtr = first.value
        allocWrap = first.wrap
      }
      pruned
    }
  }
}

class TemplateD3ReservationAllocatorProbeIO(
    val p: InterfaceParams,
    val tp: TemplateD3InterfaceParams)
    extends Bundle {
  val reqValid = Input(Bool())
  val respReady = Input(Bool())
  val formId = Input(UInt(8.W))
  val encodedN = Input(UInt(5.W))
  val rowCount = Input(UInt(5.W))
  val bidValue = Input(UInt(p.robIndexWidth.W))
  val flushValid = Input(Bool())
  val flushBidValue = Input(UInt(p.robIndexWidth.W))

  val reqReady = Output(Bool())
  val respValid = Output(Bool())
  val accepted = Output(Bool())
  val rejected = Output(Bool())
  val firstRid = Output(UInt(p.robIndexWidth.W))
  val lastRid = Output(UInt(p.robIndexWidth.W))
  val firstRidWrap = Output(Bool())
  val lastRidWrap = Output(Bool())
  val liveCount = Output(UInt(log2Ceil(p.robEntries + 1).W))
  val allocValue = Output(UInt(p.robIndexWidth.W))
  val allocWrap = Output(Bool())
  val reservedMask = Output(UInt(p.robEntries.W))
  val respReservedMask = Output(UInt(p.robEntries.W))
  val flushApplied = Output(Bool())
  val flushPruneMask = Output(UInt(p.robEntries.W))
}

class TemplateD3ReservationAllocatorProbe(
    val p: InterfaceParams = InterfaceParams(robEntries = 32),
    val tp: TemplateD3InterfaceParams = TemplateD3InterfaceParams())
    extends Module {
  private val dut = Module(new TemplateD3ReservationAllocator(p, tp))
  val io = IO(new TemplateD3ReservationAllocatorProbeIO(p, tp))

  dut.io.flush := 0.U.asTypeOf(dut.io.flush)
  dut.io.flush.req.valid := io.flushValid
  dut.io.flush.baseOnBid := true.B
  dut.io.flush.req.bid.valid := true.B
  dut.io.flush.req.bid.value := io.flushBidValue
  dut.io.flush.req.rid.valid := true.B
  dut.io.flush.req.rid.value := 0.U
  dut.io.reserveReq.valid := io.reqValid
  dut.io.reserveReq.bits := 0.U.asTypeOf(dut.io.reserveReq.bits)
  dut.io.reserveReq.bits.parent.pc := 0x1000.U
  dut.io.reserveReq.bits.parent.raw := 0x13.U
  dut.io.reserveReq.bits.parent.bid.valid := true.B
  dut.io.reserveReq.bits.parent.bid.value := io.bidValue
  dut.io.reserveReq.bits.parent.gid.valid := true.B
  dut.io.reserveReq.bits.demand.formId := io.formId
  dut.io.reserveReq.bits.demand.encodedN := io.encodedN
  dut.io.reserveReq.bits.demand.rowCount := io.rowCount
  dut.io.reserveResp.ready := io.respReady

  io.reqReady := dut.io.reserveReq.ready
  io.respValid := dut.io.reserveResp.valid
  io.accepted := dut.io.reserveResp.bits.accepted
  io.rejected := dut.io.reserveResp.bits.rejected
  io.firstRid := dut.io.reserveResp.bits.firstRid
  io.lastRid := dut.io.reserveResp.bits.lastRid
  io.firstRidWrap := dut.io.reserveResp.bits.firstRidRobid.wrap
  io.lastRidWrap := dut.io.reserveResp.bits.lastRidRobid.wrap
  io.liveCount := dut.io.liveCount
  io.allocValue := dut.io.allocValue
  io.allocWrap := dut.io.allocWrap
  io.reservedMask := dut.io.reservedMask
  io.respReservedMask := dut.io.reserveResp.bits.reservedMask
  io.flushApplied := dut.io.flushApplied
  io.flushPruneMask := dut.io.flushPruneMask
}

class TemplateD3ReservationAllocatorSpec extends AnyFunSuite with ChiselSim {
  import TemplateD3ReservationAllocatorReference._

  test("reference accepts one contiguous D3 N plus K interval atomically") {
    val model = new Model(entries = 32)
    val accepted = model.reserve(TemplateD3Constants.exactRowCount(1, 22)).get

    assert(accepted.first == Id(value = 0))
    assert(accepted.last == Id(value = 24))
    assert(accepted.slots == 0.until(25))
    assert(model.liveCount == 25)
    assert(model.freeCount == 7)
    assert(model.liveMask == ((BigInt(1) << 25) - 1))
  }

  test("reference rejects oversized demand without mutating live rows") {
    val model = new Model(entries = 32)
    assert(model.reserve(28).nonEmpty)
    assert(model.reserve(5).isEmpty)

    assert(model.liveCount == 28)
    assert(model.freeCount == 4)
    assert(model.allocValue == 28)
  }

  test("reference preserves wrapped contiguous RID interval") {
    val model = new Model(entries = 32)
    assert(model.reserve(28).nonEmpty)
    val wrapped = model.reserve(4).get

    assert(wrapped.first == Id(wrap = false, value = 28))
    assert(wrapped.last == Id(wrap = false, value = 31))
    assert(model.allocValue == 0)
    assert(model.allocWrapBit)
  }

  test("reference flush clears reserved-unfilled rows") {
    val model = new Model(entries = 32)
    assert(model.reserve(6, rowBid = Id(value = 3)).nonEmpty)

    assert(model.flush(flushBid = Id(value = 3), flushRid = Id(value = 0), baseOnBid = true) == 0.until(6))
    assert(model.liveCount == 0)
    assert(model.freeCount == 32)
    assert(model.allocValue == 0)
  }

  test("reference partial suffix flush rebases allocator to the oldest pruned RID") {
    val model = new Model(entries = 32)
    assert(model.reserve(8, rowBid = Id(value = 2)).nonEmpty)
    assert(model.reserve(4, rowBid = Id(value = 3)).nonEmpty)

    assert(model.flush(flushBid = Id(value = 3), flushRid = Id(value = 0), baseOnBid = true) == 8.until(12))
    assert(model.liveCount == 8)
    assert(model.freeCount == 24)
    assert(model.allocValue == 8)
    assert(!model.allocWrapBit)
  }

  test("reference reuses a flushed reservation interval") {
    val model = new Model(entries = 32)
    assert(model.reserve(6, rowBid = Id(value = 3)).nonEmpty)
    assert(model.flush(flushBid = Id(value = 3), flushRid = Id(value = 0), baseOnBid = true) == 0.until(6))

    val next = model.reserve(4).get
    assert(next.slots == 0.until(4))
    assert(model.liveCount == 4)
  }

  test("reference reuses a partial suffix flush interval before unpruned live rows") {
    val model = new Model(entries = 32)
    assert(model.reserve(8, rowBid = Id(value = 2)).nonEmpty)
    assert(model.reserve(4, rowBid = Id(value = 3)).nonEmpty)
    assert(model.flush(flushBid = Id(value = 3), flushRid = Id(value = 0), baseOnBid = true) == 8.until(12))

    val next = model.reserve(4, rowBid = Id(value = 4)).get
    assert(next.slots == 8.until(12))
    assert(model.liveCount == 12)
    assert(model.liveMask == ((BigInt(1) << 12) - 1))
  }

  test("reference wrapped-boundary suffix flush rebases allocator for reuse") {
    val model = new Model(entries = 32)
    assert(model.reserve(28, rowBid = Id(value = 2)).nonEmpty)
    assert(model.reserve(4, rowBid = Id(value = 3)).nonEmpty)

    assert(model.flush(flushBid = Id(value = 3), flushRid = Id(value = 0), baseOnBid = true) == 28.until(32))
    assert(model.liveCount == 28)
    assert(model.allocValue == 28)
    assert(!model.allocWrapBit)

    val next = model.reserve(4, rowBid = Id(value = 4)).get
    assert(next.slots == 28.until(32))
    assert(model.allocValue == 0)
    assert(model.allocWrapBit)
  }

  test("reference keeps single-row reservation behavior as the degenerate interval") {
    val model = new Model(entries = 32)
    val one = model.reserve(1).get

    assert(one.first == one.last)
    assert(one.slots == Seq(0))
    assert(model.liveCount == 1)
    assert(model.freeCount == 31)
  }

  test("Chisel TemplateD3ReservationAllocator elaborates reservation-only ABI") {
    val p = InterfaceParams(robEntries = 32)
    val tp = TemplateD3InterfaceParams()
    val sv = ChiselStage.emitSystemVerilog(new TemplateD3ReservationAllocator(p, tp))

    assert(sv.contains("module TemplateD3ReservationAllocator"))
    assert(sv.contains("io_reserveReq_ready"))
    assert(sv.contains("io_reserveResp_valid"))
    assert(sv.contains("io_reservedMask"))
    assert(sv.contains("io_flushApplied"))
    assert(sv.contains("io_headStatus"))
    assert(sv.contains("io_reserveResp_bits_reservedMask"))
  }

  private def clearInputs(dut: TemplateD3ReservationAllocatorProbe): Unit = {
    dut.io.reqValid.poke(false.B)
    dut.io.respReady.poke(false.B)
    dut.io.formId.poke(0.U)
    dut.io.encodedN.poke(0.U)
    dut.io.rowCount.poke(0.U)
    dut.io.bidValue.poke(0.U)
    dut.io.flushValid.poke(false.B)
    dut.io.flushBidValue.poke(0.U)
  }

  private def driveReserve(
      dut: TemplateD3ReservationAllocatorProbe,
      form: BigInt,
      encodedN: BigInt,
      rowCount: BigInt,
      bidValue: BigInt = 1): Unit = {
    dut.io.reqValid.poke(true.B)
    dut.io.formId.poke(form.U)
    dut.io.encodedN.poke(encodedN.U)
    dut.io.rowCount.poke(rowCount.U)
    dut.io.bidValue.poke(bidValue.U)
  }

  private def driveBaseBidFlush(dut: TemplateD3ReservationAllocatorProbe, bidValue: BigInt): Unit = {
    dut.io.flushValid.poke(true.B)
    dut.io.flushBidValue.poke(bidValue.U)
  }

  private def acceptAndDrain(
      dut: TemplateD3ReservationAllocatorProbe,
      form: BigInt,
      encodedN: BigInt,
      rowCount: BigInt,
      bidValue: BigInt): Unit = {
    clearInputs(dut)
    driveReserve(dut, form, encodedN, rowCount, bidValue)
    dut.io.reqReady.expect(true.B)
    dut.clock.step()
    dut.io.respValid.expect(true.B)
    dut.io.accepted.expect(true.B)
    dut.io.respReady.poke(true.B)
    dut.clock.step()
  }

  test("sim accepts one reservation and reports the exact response interval") {
    simulate(new TemplateD3ReservationAllocatorProbe()) { dut =>
      clearInputs(dut)

      driveReserve(dut, TemplateForm.FENTRY.asUInt.litValue, 1, 4)
      dut.io.reqReady.expect(true.B)
      dut.clock.step()
      dut.io.liveCount.expect(4.U)
      dut.io.reservedMask.expect(0xf.U)
      dut.io.respValid.expect(true.B)
      dut.io.accepted.expect(true.B)
      dut.io.firstRid.expect(0.U)
      dut.io.lastRid.expect(3.U)
      dut.io.respReservedMask.expect(0xf.U)
      dut.io.respReady.poke(true.B)
      dut.clock.step()
    }
  }

  test("sim flushes an empty reservation interval and reuses it") {
    simulate(new TemplateD3ReservationAllocatorProbe()) { dut =>
      acceptAndDrain(dut, TemplateForm.FENTRY.asUInt.litValue, 1, 4, bidValue = 1)

      clearInputs(dut)
      driveBaseBidFlush(dut, 1)
      dut.io.flushApplied.expect(true.B)
      dut.io.flushPruneMask.expect(0xf.U)
      dut.clock.step()
      dut.io.liveCount.expect(0.U)
      dut.io.allocValue.expect(0.U)
      dut.io.allocWrap.expect(false.B)

      clearInputs(dut)
      driveReserve(dut, TemplateForm.FENTRY.asUInt.litValue, 1, 4, bidValue = 2)
      dut.clock.step()
      dut.io.accepted.expect(true.B)
      dut.io.firstRid.expect(0.U)
      dut.io.lastRid.expect(3.U)
    }
  }

  test("sim preserves wrapped-boundary reservation and flushes its suffix for reuse") {
    simulate(new TemplateD3ReservationAllocatorProbe()) { dut =>
      clearInputs(dut)
      driveReserve(dut, TemplateForm.FRET_STK.asUInt.litValue, 22, 28, bidValue = 2)
      dut.clock.step()
      dut.io.respReady.poke(true.B)
      dut.clock.step()

      driveReserve(dut, TemplateForm.FENTRY.asUInt.litValue, 1, 4, bidValue = 3)
      dut.clock.step()
      dut.io.allocValue.expect(0.U)
      dut.io.allocWrap.expect(true.B)
      dut.io.firstRid.expect(28.U)
      dut.io.firstRidWrap.expect(false.B)
      dut.io.lastRid.expect(31.U)
      dut.io.lastRidWrap.expect(false.B)
      dut.io.respReady.poke(true.B)
      dut.clock.step()

      driveBaseBidFlush(dut, 3)
      dut.io.flushApplied.expect(true.B)
      dut.io.flushPruneMask.expect(BigInt("f0000000", 16).U)
      dut.clock.step()
      dut.io.liveCount.expect(28.U)
      dut.io.allocValue.expect(28.U)
      dut.io.allocWrap.expect(false.B)

      clearInputs(dut)
      driveReserve(dut, TemplateForm.FENTRY.asUInt.litValue, 1, 4, bidValue = 4)
      dut.clock.step()
      dut.io.accepted.expect(true.B)
      dut.io.firstRid.expect(28.U)
      dut.io.lastRid.expect(31.U)
    }
  }

  test("sim rejects target overlap without mutating live rows") {
    simulate(new TemplateD3ReservationAllocatorProbe()) { dut =>
      acceptAndDrain(dut, TemplateForm.FRET_STK.asUInt.litValue, 22, 28, bidValue = 2)
      acceptAndDrain(dut, TemplateForm.FENTRY.asUInt.litValue, 1, 4, bidValue = 3)

      driveReserve(dut, TemplateForm.FENTRY.asUInt.litValue, 1, 4, bidValue = 4)
      dut.clock.step()
      dut.io.rejected.expect(true.B)
      dut.io.liveCount.expect(32.U)
      dut.io.reservedMask.expect(BigInt("ffffffff", 16).U)
      dut.io.respReady.poke(true.B)
      dut.clock.step()
    }
  }

  test("sim partial suffix flush rebases allocator and reuses the freed rows") {
    simulate(new TemplateD3ReservationAllocatorProbe()) { dut =>
      acceptAndDrain(dut, TemplateForm.FENTRY.asUInt.litValue, 5, 8, bidValue = 2)
      acceptAndDrain(dut, TemplateForm.FENTRY.asUInt.litValue, 1, 4, bidValue = 3)

      clearInputs(dut)
      driveBaseBidFlush(dut, 3)
      dut.io.flushApplied.expect(true.B)
      dut.io.flushPruneMask.expect(0xf00.U)
      dut.clock.step()
      dut.io.liveCount.expect(8.U)
      dut.io.allocValue.expect(8.U)
      dut.io.allocWrap.expect(false.B)

      clearInputs(dut)
      driveReserve(dut, TemplateForm.FENTRY.asUInt.litValue, 1, 4, bidValue = 4)
      dut.clock.step()
      dut.io.accepted.expect(true.B)
      dut.io.firstRid.expect(8.U)
      dut.io.lastRid.expect(11.U)
      dut.io.reservedMask.expect(0xfff.U)
    }
  }

  test("sim rejects oversize demand without mutating live rows") {
    simulate(new TemplateD3ReservationAllocatorProbe()) { dut =>
      acceptAndDrain(dut, TemplateForm.FRET_STK.asUInt.litValue, 22, 28, bidValue = 2)

      clearInputs(dut)
      driveReserve(dut, TemplateForm.FRET_STK.asUInt.litValue, 22, 28, bidValue = 3)
      dut.clock.step()
      dut.io.rejected.expect(true.B)
      dut.io.liveCount.expect(28.U)
      dut.io.reservedMask.expect(((BigInt(1) << 28) - 1).U)
      dut.io.respReady.poke(true.B)
      dut.clock.step()
    }
  }

  test("sim cancels a held response when a matching flush arrives") {
    simulate(new TemplateD3ReservationAllocatorProbe()) { dut =>
      acceptAndDrain(dut, TemplateForm.FRET_STK.asUInt.litValue, 22, 28, bidValue = 2)

      clearInputs(dut)
      driveReserve(dut, TemplateForm.FENTRY.asUInt.litValue, 1, 4, bidValue = 6)
      dut.clock.step()
      dut.io.respValid.expect(true.B)
      driveBaseBidFlush(dut, 6)
      dut.clock.step()
      dut.io.respValid.expect(false.B)
      dut.io.liveCount.expect(28.U)
      dut.io.allocValue.expect(28.U)
    }
  }

  test("sim gives flush priority over a simultaneous reserve request") {
    simulate(new TemplateD3ReservationAllocatorProbe()) { dut =>
      clearInputs(dut)
      driveReserve(dut, TemplateForm.FENTRY.asUInt.litValue, 1, 4, bidValue = 7)
      driveBaseBidFlush(dut, 31)
      dut.io.reqReady.expect(false.B)
      dut.clock.step()
      dut.io.respValid.expect(false.B)
    }
  }
}
