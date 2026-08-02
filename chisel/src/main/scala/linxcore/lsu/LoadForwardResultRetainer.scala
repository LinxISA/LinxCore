package linxcore.lsu

import chisel3._
import chisel3.util.{Decoupled, PopCount, Queue, log2Ceil}

class LoadForwardResultRetainerIO(
    val idEntries: Int,
    val storeEntries: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val lsidWidth: Int,
    val depth: Int)
    extends Bundle {
  val flush = Input(Bool())
  val in = Flipped(Decoupled(new LoadInflightForwardResult(
    idEntries, storeEntries, pcWidth, lineBytes, lsidWidth)))
  val outValid = Output(Bool())
  val out = Output(new LoadInflightForwardResult(
    idEntries, storeEntries, pcWidth, lineBytes, lsidWidth))
  val sinkAccepted = Input(Bool())
  val sinkRejectedPermanent = Input(Bool())
  val sinkRetryRequired = Input(Bool())
  val count = Output(UInt(log2Ceil(depth + 1).W))
  val pending = Output(Bool())
  val protocolError = Output(Bool())
}

/** Retains classified STQ E4 results until the canonical LIQ reaches a
  * terminal decision.
  *
  * Recovery and competing LIQ mutations are retryable owner conflicts.  They
  * hold the FIFO head unchanged.  Exact acceptance or a typed permanent stale
  * rejection is terminal and dequeues exactly one result.
  */
class LoadForwardResultRetainer(
    val idEntries: Int,
    val storeEntries: Int,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32,
    val depth: Int = 4)
    extends Module {
  require(depth >= 2,
    "forward-result retention must cover registered E3/E4 traffic")

  val io = IO(new LoadForwardResultRetainerIO(
    idEntries, storeEntries, pcWidth, lineBytes, lsidWidth, depth))

  val queue = withReset(reset.asBool || io.flush) {
    Module(new Queue(new LoadInflightForwardResult(
      idEntries, storeEntries, pcWidth, lineBytes, lsidWidth),
      depth, pipe = true, flow = false))
  }
  queue.io.enq <> io.in

  val terminal = io.sinkAccepted || io.sinkRejectedPermanent
  queue.io.deq.ready := terminal
  io.outValid := queue.io.deq.valid
  io.out := queue.io.deq.bits
  io.count := queue.io.count
  io.pending := queue.io.deq.valid

  val protocolError = RegInit(false.B)
  val decisions = VecInit(Seq(
    io.sinkAccepted,
    io.sinkRejectedPermanent,
    io.sinkRetryRequired))
  when(io.flush) {
    protocolError := false.B
  }.elsewhen(PopCount(decisions) > 1.U ||
      ((terminal || io.sinkRetryRequired) && !queue.io.deq.valid)) {
    protocolError := true.B
  }
  io.protocolError := protocolError
}
