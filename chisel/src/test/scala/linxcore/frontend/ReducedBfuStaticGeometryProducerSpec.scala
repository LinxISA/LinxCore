package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedBfuStaticGeometryProducerReference {
  final case class Event(valid: Boolean, pc: BigInt = 0, lenBytes: Int = 0, boundary: Boolean = false, stop: Boolean = false)
  final case class Result(geometryValid: Boolean, headerPc: BigInt, hsizeBytes: BigInt, bsizeBytes: BigInt, activeAfter: Boolean)

  final class Model {
    private var active = false
    private var headerPc = BigInt(0)
    private var hsize = BigInt(0)

    def step(event: Event, flush: Boolean = false): Result = {
      val geometryValid = !flush && event.valid && active
      val bodyEnd = if (event.stop) event.pc + event.lenBytes else event.pc
      val bodyBase = headerPc + 2
      val bsize = if (geometryValid && bodyEnd > bodyBase) bodyEnd - bodyBase else BigInt(0)
      val observed = Result(geometryValid, headerPc, hsize, bsize, activeAfter = active)

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

  val geometryValid = Output(Bool())
  val headerPc = Output(UInt(p.pcWidth.W))
  val hsizeBytes = Output(UInt(p.pcWidth.W))
  val bsizeBytes = Output(UInt(p.pcWidth.W))
  val learnedFire = Output(Bool())
}

class ReducedBfuStaticGeometryProducerProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuStaticGeometryProducerProbeIO(p))
  val producer = Module(new ReducedBfuStaticGeometryProducer(p))

  producer.io.flushValid := io.flushValid
  producer.io.f4UpdateFire := io.f4UpdateFire
  producer.io.f4Valid := io.f4Valid
  producer.io.f4Slots := io.f4Slots
  producer.io.f4ValidMask := io.f4ValidMask

  io.geometryValid := producer.io.geometryValid
  io.headerPc := producer.io.headerPc
  io.hsizeBytes := producer.io.hsizeBytes
  io.bsizeBytes := producer.io.bsizeBytes
  io.learnedFire := producer.io.learnedFire
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

  test("ReducedBfuStaticGeometryProducer elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedBfuStaticGeometryProducerProbe(InterfaceParams()))

    assert(sv.contains("module ReducedBfuStaticGeometryProducerProbe"))
    assert(sv.contains("module ReducedBfuStaticGeometryProducer"))
    assert(sv.contains("io_geometryValid"))
    assert(sv.contains("io_bsizeBytes"))
  }
}
