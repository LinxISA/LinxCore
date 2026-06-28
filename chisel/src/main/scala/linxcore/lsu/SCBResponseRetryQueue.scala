package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, UIntToOH}

class SCBResponseRetryQueueIO(
    val scbEntries: Int,
    val depth: Int)
    extends Bundle {
  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))
  private val countWidth = log2Ceil(depth + 1)

  val pushValid = Input(Bool())
  val pushEntryIndex = Input(UInt(entryIndexWidth.W))
  val popReady = Input(Bool())

  val pushReady = Output(Bool())
  val pushAccepted = Output(Bool())
  val headValid = Output(Bool())
  val headEntryIndex = Output(UInt(entryIndexWidth.W))
  val headMask = Output(UInt(scbEntries.W))
  val headConsumed = Output(Bool())
  val full = Output(Bool())
  val empty = Output(Bool())
  val count = Output(UInt(countWidth.W))
}

class SCBResponseRetryQueue(
    val scbEntries: Int = 16,
    val depth: Int = 16)
    extends Module {
  require(scbEntries > 0, "SCB response retry queue requires at least one SCB entry")
  require(depth > 0, "SCB response retry queue depth must be nonzero")

  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))
  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new SCBResponseRetryQueueIO(scbEntries, depth))

  val entries = RegInit(VecInit(Seq.fill(depth)(0.U(entryIndexWidth.W))))
  val headPtr = RegInit(0.U(ptrWidth.W))
  val tailPtr = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  private def inc(ptr: UInt): UInt =
    if (depth == 1) 0.U(ptrWidth.W) else Mux(ptr === (depth - 1).U, 0.U, ptr + 1.U)

  val headValid = count =/= 0.U
  val headConsumed = headValid && io.popReady
  val pushReady = count =/= depth.U || headConsumed
  val pushAccepted = io.pushValid && pushReady

  when(headConsumed) {
    headPtr := inc(headPtr)
  }
  when(pushAccepted) {
    entries(tailPtr) := io.pushEntryIndex
    tailPtr := inc(tailPtr)
  }

  when(pushAccepted && !headConsumed) {
    count := count + 1.U
  }.elsewhen(!pushAccepted && headConsumed) {
    count := count - 1.U
  }

  io.pushReady := pushReady
  io.pushAccepted := pushAccepted
  io.headValid := headValid
  io.headEntryIndex := entries(headPtr)
  io.headMask := Mux(headValid, UIntToOH(entries(headPtr), scbEntries), 0.U(scbEntries.W))
  io.headConsumed := headConsumed
  io.full := count === depth.U
  io.empty := count === 0.U
  io.count := count
}
