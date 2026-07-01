package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedBfuStaticGeometryProducerReference {
  final case class Event(valid: Boolean, pc: BigInt = 0, lenBytes: Int = 0, boundary: Boolean = false, stop: Boolean = false)
  final case class ResolvedBodyEnd(valid: Boolean, headerPc: BigInt = 0, hsizeBytes: BigInt = 0, bodyEndPc: BigInt = 0)
  final case class Result(geometryValid: Boolean, headerPc: BigInt, hsizeBytes: BigInt, bsizeBytes: BigInt, activeAfter: Boolean)

  final class Model {
    private var active = false
    private var headerPc = BigInt(0)
    private var hsize = BigInt(0)

    def step(
        event: Event,
        resolved: ResolvedBodyEnd = ResolvedBodyEnd(valid = false),
        flush: Boolean = false): Result = {
      val resolvedFire = !flush && resolved.valid && active && resolved.headerPc == headerPc
      val eventFire = !flush && event.valid && active
      val geometryValid = resolvedFire || eventFire
      val bodyEnd = if (resolvedFire) {
        resolved.bodyEndPc
      } else if (event.stop) {
        event.pc + event.lenBytes
      } else {
        event.pc
      }
      val bodyBase = headerPc + 2
      val bsize = if (geometryValid && bodyEnd > bodyBase) bodyEnd - bodyBase else BigInt(0)
      val observedHsize = if (resolvedFire) resolved.hsizeBytes else hsize
      val observed = Result(geometryValid, headerPc, observedHsize, bsize, activeAfter = active)

      if (flush) {
        active = false
        headerPc = 0
        hsize = 0
      } else if (event.valid) {
        if (event.boundary) {
          active = true
          headerPc = event.pc
          hsize = 0
        } else if (event.stop) {
          active = false
          headerPc = 0
          hsize = 0
        }
      }

      observed.copy(activeAfter = active)
    }
  }
}

class ReducedBfuStaticGeometryProducerProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val flushValid = Input(Bool())
  val f4UpdateFire = Input(Bool())
  val f4Valid = Input(Bool())
  val f4Slots = Input(Vec(p.decodeWidth, new F4Slot(p)))
  val f4ValidMask = Input(UInt(p.decodeWidth.W))
  val resolvedBodyEndValid = Input(Bool())
  val resolvedHeaderPc = Input(UInt(p.pcWidth.W))
  val resolvedHSizeBytes = Input(UInt(p.pcWidth.W))
  val resolvedBodyEndPc = Input(UInt(p.pcWidth.W))

  val geometryValid = Output(Bool())
  val headerPc = Output(UInt(p.pcWidth.W))
  val hsizeBytes = Output(UInt(p.pcWidth.W))
  val bsizeBytes = Output(UInt(p.pcWidth.W))
  val learnedFire = Output(Bool())
  val resolvedLearnedFire = Output(Bool())
}

class ReducedBfuStaticGeometryProducerProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuStaticGeometryProducerProbeIO(p))
  val producer = Module(new ReducedBfuStaticGeometryProducer(p))

  producer.io.flushValid := io.flushValid
  producer.io.f4UpdateFire := io.f4UpdateFire
  producer.io.f4Valid := io.f4Valid
  producer.io.f4Slots := io.f4Slots
  producer.io.f4ValidMask := io.f4ValidMask
  producer.io.resolvedBodyEndValid := io.resolvedBodyEndValid
  producer.io.resolvedHeaderPc := io.resolvedHeaderPc
  producer.io.resolvedHSizeBytes := io.resolvedHSizeBytes
  producer.io.resolvedBodyEndPc := io.resolvedBodyEndPc

  io.geometryValid := producer.io.geometryValid
  io.headerPc := producer.io.headerPc
  io.hsizeBytes := producer.io.hsizeBytes
  io.bsizeBytes := producer.io.bsizeBytes
  io.learnedFire := producer.io.learnedFire
  io.resolvedLearnedFire := producer.io.resolvedLearnedFire
}

class ReducedBfuStaticGeometryProducerSpec extends AnyFunSuite {
  test("reference learns bsize when a later block boundary closes the active header body") {
    val model = new ReducedBfuStaticGeometryProducerReference.Model

    val open = model.step(ReducedBfuStaticGeometryProducerReference.Event(valid = true, pc = 0x1000, lenBytes = 2, boundary = true))
    assert(!open.geometryValid)
    assert(open.activeAfter)

    val body = model.step(ReducedBfuStaticGeometryProducerReference.Event(valid = false))
    assert(!body.geometryValid)
    assert(body.activeAfter)

    val learned = model.step(ReducedBfuStaticGeometryProducerReference.Event(valid = true, pc = 0x1010, lenBytes = 2, boundary = true))
    assert(learned.geometryValid)
    assert(learned.headerPc == 0x1000)
    assert(learned.hsizeBytes == 0)
    assert(learned.bsizeBytes == 0xe)
    assert(learned.activeAfter)
  }

  test("reference uses the byte after BSTOP as the body end and clears the active header") {
    val model = new ReducedBfuStaticGeometryProducerReference.Model

    model.step(ReducedBfuStaticGeometryProducerReference.Event(valid = true, pc = 0x2000, lenBytes = 2, boundary = true))
    val learned = model.step(ReducedBfuStaticGeometryProducerReference.Event(valid = true, pc = 0x2008, lenBytes = 4, stop = true))

    assert(learned.geometryValid)
    assert(learned.headerPc == 0x2000)
    assert(learned.bsizeBytes == 0xa)
    assert(!learned.activeAfter)
  }

  test("reference documents that CoreMark ACRC continuation is not yet a boundary event") {
    val model = new ReducedBfuStaticGeometryProducerReference.Model

    model.step(ReducedBfuStaticGeometryProducerReference.Event(valid = true, pc = BigInt("4000630c", 16), lenBytes = 2, boundary = true))
    val acrc = model.step(ReducedBfuStaticGeometryProducerReference.Event(valid = false, pc = BigInt("4000632e", 16), lenBytes = 4))

    assert(!acrc.geometryValid)
    assert(acrc.activeAfter)
  }

  test("reference learns CoreMark FALL body size from a resolved body end") {
    val model = new ReducedBfuStaticGeometryProducerReference.Model

    model.step(ReducedBfuStaticGeometryProducerReference.Event(valid = true, pc = BigInt("4000630c", 16), lenBytes = 2, boundary = true))
    val learned = model.step(
      ReducedBfuStaticGeometryProducerReference.Event(valid = false),
      resolved = ReducedBfuStaticGeometryProducerReference.ResolvedBodyEnd(
        valid = true,
        headerPc = BigInt("4000630c", 16),
        bodyEndPc = BigInt("4000632e", 16)))

    assert(learned.geometryValid)
    assert(learned.headerPc == BigInt("4000630c", 16))
    assert(learned.hsizeBytes == 0)
    assert(learned.bsizeBytes == 0x20)
    assert(learned.activeAfter)
  }

  test("reference carries resolved hsize only on the resolved body-end geometry row") {
    val model = new ReducedBfuStaticGeometryProducerReference.Model

    model.step(ReducedBfuStaticGeometryProducerReference.Event(valid = true, pc = 0x4000, lenBytes = 2, boundary = true))
    val learned = model.step(
      ReducedBfuStaticGeometryProducerReference.Event(valid = false),
      resolved = ReducedBfuStaticGeometryProducerReference.ResolvedBodyEnd(
        valid = true,
        headerPc = 0x4000,
        hsizeBytes = 6,
        bodyEndPc = 0x4012))
    val idleAfter = model.step(ReducedBfuStaticGeometryProducerReference.Event(valid = false))

    assert(learned.geometryValid)
    assert(learned.headerPc == 0x4000)
    assert(learned.hsizeBytes == 6)
    assert(learned.bsizeBytes == 0x10)
    assert(learned.activeAfter)
    assert(!idleAfter.geometryValid)
    assert(idleAfter.hsizeBytes == 0)
    assert(idleAfter.activeAfter)
  }

  test("reference suppresses resolved body-end learning during flush") {
    val model = new ReducedBfuStaticGeometryProducerReference.Model

    model.step(ReducedBfuStaticGeometryProducerReference.Event(valid = true, pc = 0x3000, lenBytes = 2, boundary = true))
    val flushed = model.step(
      ReducedBfuStaticGeometryProducerReference.Event(valid = false),
      resolved = ReducedBfuStaticGeometryProducerReference.ResolvedBodyEnd(valid = true, headerPc = 0x3000, bodyEndPc = 0x3010),
      flush = true)

    assert(!flushed.geometryValid)
    assert(!flushed.activeAfter)
  }

  test("ReducedBfuStaticGeometryProducer elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedBfuStaticGeometryProducerProbe(InterfaceParams()))

    assert(sv.contains("module ReducedBfuStaticGeometryProducerProbe"))
    assert(sv.contains("module ReducedBfuStaticGeometryProducer"))
    assert(sv.contains("io_geometryValid"))
    assert(sv.contains("io_bsizeBytes"))
    assert(sv.contains("io_resolvedBodyEndValid"))
    assert(sv.contains("io_resolvedHSizeBytes"))
    assert(sv.contains("io_resolvedLearnedFire"))
  }
}
