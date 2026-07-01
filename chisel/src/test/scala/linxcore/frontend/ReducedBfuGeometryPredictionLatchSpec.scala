package linxcore.frontend

import chisel3._
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ReducedBfuGeometryPredictionLatchProbeIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val flushValid = Input(Bool())
  val learnValid = Input(Bool())
  val learnHeaderPc = Input(UInt(p.pcWidth.W))
  val learnHSizeBytes = Input(UInt(p.pcWidth.W))
  val learnBSizeBytes = Input(UInt(p.pcWidth.W))

  val geometryValid = Output(Bool())
  val headerPc = Output(UInt(p.pcWidth.W))
  val hsizeBytes = Output(UInt(p.pcWidth.W))
  val bsizeBytes = Output(UInt(p.pcWidth.W))
}

class ReducedBfuGeometryPredictionLatchProbe(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuGeometryPredictionLatchProbeIO(p))
  val latch = Module(new ReducedBfuGeometryPredictionLatch(p))

  latch.io.flushValid := io.flushValid
  latch.io.learnValid := io.learnValid
  latch.io.learnHeaderPc := io.learnHeaderPc
  latch.io.learnHSizeBytes := io.learnHSizeBytes
  latch.io.learnBSizeBytes := io.learnBSizeBytes

  io.geometryValid := latch.io.geometryValid
  io.headerPc := latch.io.headerPc
  io.hsizeBytes := latch.io.hsizeBytes
  io.bsizeBytes := latch.io.bsizeBytes
}

class ReducedBfuGeometryPredictionLatchSpec extends AnyFunSuite {
  test("reference stores learned geometry for later prediction without same-cycle feedthrough") {
    var valid = false
    var headerPc = BigInt(0)
    var hsize = BigInt(0)
    var bsize = BigInt(0)

    def step(
        flush: Boolean = false,
        learn: Boolean = false,
        learnHeader: BigInt = 0,
        learnHSize: BigInt = 0,
        learnBSize: BigInt = 0): (Boolean, BigInt, BigInt, BigInt) = {
      val observed = (valid, headerPc, hsize, bsize)
      if (flush) {
        valid = false
        headerPc = 0
        hsize = 0
        bsize = 0
      } else if (learn) {
        valid = true
        headerPc = learnHeader
        hsize = learnHSize
        bsize = learnBSize
      }
      observed
    }

    assert(step(learn = true, learnHeader = 0x4000554e, learnBSize = 0xa)._1 == false)
    val predicted = step()
    assert(predicted == ((true, BigInt("4000554e", 16), BigInt(0), BigInt(0xa))))
    assert(step(flush = true)._1 == true)
    assert(step()._1 == false)
  }

  test("ReducedBfuGeometryPredictionLatch elaborates through Chisel") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedBfuGeometryPredictionLatchProbe(InterfaceParams()))

    assert(sv.contains("module ReducedBfuGeometryPredictionLatchProbe"))
    assert(sv.contains("module ReducedBfuGeometryPredictionLatch"))
    assert(sv.contains("io_geometryValid"))
    assert(sv.contains("io_learnValid"))
    assert(sv.contains("io_bsizeBytes"))
  }
}
