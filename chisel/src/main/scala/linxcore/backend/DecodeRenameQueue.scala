package linxcore.backend

import chisel3._
import chisel3.util.log2Ceil
import linxcore.common.{DecodedUop, InterfaceParams}

class DecodeRenameQueueIO(val p: InterfaceParams = InterfaceParams(), val depth: Int = 4) extends Bundle {
  require(depth > 0 && (depth & (depth - 1)) == 0, "DecodeRenameQueue depth must be a power of two")

  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val push = Input(new DecodedUop(p))
  val pushReady = Output(Bool())
  val pushFire = Output(Bool())
  val popReady = Input(Bool())
  val out = Output(new DecodedUop(p))
  val popFire = Output(Bool())
  val flushValid = Input(Bool())

  val head = Output(UInt(ptrWidth.W))
  val tail = Output(UInt(ptrWidth.W))
  val count = Output(UInt(countWidth.W))
  val empty = Output(Bool())
  val full = Output(Bool())
}

class DecodeRenameQueue(val p: InterfaceParams = InterfaceParams(), val depth: Int = 4) extends Module {
  require(depth > 0 && (depth & (depth - 1)) == 0, "DecodeRenameQueue depth must be a power of two")

  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new DecodeRenameQueueIO(p, depth))

  val entries = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new DecodedUop(p)))))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  private def nextPtr(ptr: UInt): UInt =
    if (depth == 1) 0.U(ptrWidth.W) else (ptr + 1.U)(ptrWidth - 1, 0)

  val empty = count === 0.U
  val full = count === depth.U
  val outValid = !io.flushValid && !empty && entries(head).valid
  val popFire = outValid && io.popReady
  val pushReady = !io.flushValid && (!full || popFire)
  val pushFire = io.push.valid && pushReady

  io.pushReady := pushReady
  io.pushFire := pushFire
  io.out := entries(head)
  io.out.valid := outValid
  io.popFire := popFire
  io.head := head
  io.tail := tail
  io.count := count
  io.empty := empty
  io.full := full

  when(io.flushValid) {
    head := 0.U
    tail := 0.U
    count := 0.U
    for (idx <- 0 until depth) {
      entries(idx).valid := false.B
    }
  }.otherwise {
    when(popFire) {
      entries(head).valid := false.B
    }
    when(pushFire) {
      entries(tail) := io.push
    }

    when(popFire) {
      head := nextPtr(head)
    }
    when(pushFire) {
      tail := nextPtr(tail)
    }

    when(pushFire && !popFire) {
      count := count + 1.U
    }.elsewhen(!pushFire && popFire) {
      count := count - 1.U
    }
  }
}
