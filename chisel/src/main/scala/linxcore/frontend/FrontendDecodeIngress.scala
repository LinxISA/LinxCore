package linxcore.frontend

import chisel3._
import chisel3.util.log2Ceil
import linxcore.common.{FrontendDecodePacket, InterfaceParams}

class FrontendDecodeIngressIO(
    val p: InterfaceParams = InterfaceParams(),
    val ibufDepth: Int = 8)
    extends Bundle {
  require(ibufDepth > 0 && (ibufDepth & (ibufDepth - 1)) == 0, "FrontendDecodeIngress depth must be a power of two")

  private val ptrWidth = math.max(1, log2Ceil(ibufDepth))
  private val countWidth = log2Ceil(ibufDepth + 1)

  val push = Input(new FrontendDecodePacket(p))
  val pushReady = Output(Bool())
  val decodeReady = Input(Bool())
  val flushValid = Input(Bool())

  val d1 = Output(new FrontendDecodePacket(p))
  val slots = Output(Vec(p.decodeWidth, new F4Slot(p)))
  val validMask = Output(UInt(p.decodeWidth.W))
  val slotCount = Output(UInt(log2Ceil(p.decodeWidth + 1).W))
  val totalLenBytes = Output(UInt(4.W))

  val popFire = Output(Bool())
  val ibHead = Output(UInt(ptrWidth.W))
  val ibTail = Output(UInt(ptrWidth.W))
  val ibCount = Output(UInt(countWidth.W))
}

class FrontendDecodeIngress(
    val p: InterfaceParams = InterfaceParams(),
    val ibufDepth: Int = 8)
    extends Module {
  require(ibufDepth > 0 && (ibufDepth & (ibufDepth - 1)) == 0, "FrontendDecodeIngress depth must be a power of two")

  val io = IO(new FrontendDecodeIngressIO(p, ibufDepth))

  val ib = Module(new FrontendInstructionBuffer(p, depth = ibufDepth))
  val f4 = Module(new F4DecodeWindow(p))

  ib.io.push := io.push
  ib.io.flushValid := io.flushValid

  f4.io.in := ib.io.out
  f4.io.flushValid := io.flushValid

  ib.io.popReady := io.decodeReady && f4.io.d1.valid

  io.pushReady := ib.io.pushReady
  io.d1 := f4.io.d1
  io.slots := f4.io.slots
  io.validMask := f4.io.validMask
  io.slotCount := f4.io.slotCount
  io.totalLenBytes := f4.io.totalLenBytes
  io.popFire := ib.io.popFire
  io.ibHead := ib.io.head
  io.ibTail := ib.io.tail
  io.ibCount := ib.io.count
}
