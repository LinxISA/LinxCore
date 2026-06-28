package linxcore.frontend

import chisel3._
import chisel3.util.log2Ceil
import linxcore.common.{FrontendDecodePacket, InterfaceParams}

class FrontendInstructionBufferIO(val p: InterfaceParams = InterfaceParams(), val depth: Int = 8) extends Bundle {
  require(depth > 0 && (depth & (depth - 1)) == 0, "FrontendInstructionBuffer depth must be a power of two")

  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val push = Input(new FrontendDecodePacket(p))
  val pushReady = Output(Bool())
  val popReady = Input(Bool())
  val out = Output(new FrontendDecodePacket(p))
  val popFire = Output(Bool())
  val flushValid = Input(Bool())

  val head = Output(UInt(ptrWidth.W))
  val tail = Output(UInt(ptrWidth.W))
  val count = Output(UInt(countWidth.W))
}

class FrontendInstructionBuffer(val p: InterfaceParams = InterfaceParams(), val depth: Int = 8) extends Module {
  require(depth > 0 && (depth & (depth - 1)) == 0, "FrontendInstructionBuffer depth must be a power of two")

  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new FrontendInstructionBufferIO(p, depth))

  val entries = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new FrontendDecodePacket(p)))))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))
  def nextPtr(ptr: UInt): UInt = if (depth == 1) 0.U(ptrWidth.W) else (ptr + 1.U)(ptrWidth - 1, 0)

  val empty = count === 0.U
  val full = count === depth.U
  val outValid = !empty && entries(head).valid
  val canPush = !io.flushValid && !full
  val pushFire = io.push.valid && canPush

  io.pushReady := canPush
  io.out := entries(head)
  io.out.valid := !io.flushValid && outValid
  io.popFire := io.out.valid && io.popReady
  io.head := head
  io.tail := tail
  io.count := count

  when(io.flushValid) {
    head := 0.U
    tail := 0.U
    count := 0.U
    for (idx <- 0 until depth) {
      entries(idx).valid := false.B
    }
  }.otherwise {
    when(pushFire) {
      entries(tail) := io.push
    }

    when(io.popFire) {
      entries(head).valid := false.B
    }

    when(pushFire) {
      tail := nextPtr(tail)
    }
    when(io.popFire) {
      head := nextPtr(head)
    }

    when(pushFire && !io.popFire) {
      count := count + 1.U
    }.elsewhen(!pushFire && io.popFire) {
      count := count - 1.U
    }
  }
}
