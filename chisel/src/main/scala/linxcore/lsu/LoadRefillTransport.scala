package linxcore.lsu

import chisel3._
import chisel3.util.{PopCount, log2Ceil}

class LoadRefillTransportEntry(val addrWidth: Int, val lineBytes: Int) extends Bundle {
  val valid = Bool()
  val fromMissQueue = Bool()
  val refill = new LoadRefillWakeupRequest(addrWidth, lineBytes)
}

class LoadRefillTransportIO(val entries: Int, val addrWidth: Int, val lineBytes: Int)
    extends Bundle {
  private val countWidth = log2Ceil(entries + 1)

  val hardFlush = Input(Bool())
  val hold = Input(Bool())

  val missValid = Input(Bool())
  val missReady = Output(Bool())
  val miss = Input(new LoadRefillWakeupRequest(addrWidth, lineBytes))
  val missAccepted = Output(Bool())

  val externalValid = Input(Bool())
  val externalReady = Output(Bool())
  val external = Input(new LoadRefillWakeupRequest(addrWidth, lineBytes))
  val externalAccepted = Output(Bool())
  val dualIngressAccepted = Output(Bool())

  val outValid = Output(Bool())
  val outReady = Input(Bool())
  val out = Output(new LoadRefillWakeupRequest(addrWidth, lineBytes))
  val outFromMissQueue = Output(Bool())
  val outAccepted = Output(Bool())

  val resident = Output(Vec(entries, new LoadRefillTransportEntry(addrWidth, lineBytes)))
  val validMask = Output(UInt(entries.W))
  val count = Output(UInt(countWidth.W))
  val empty = Output(Bool())
  val full = Output(Bool())
  val pending = Output(Bool())
  val missBlocked = Output(Bool())
  val externalBlocked = Output(Bool())
  val protocolError = Output(Bool())
}

class LoadRefillTransport(
    val entries: Int = 4,
    val addrWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0,
    "entries must be a power of two greater than one")
  require(lineBytes > 1 && (lineBytes & (lineBytes - 1)) == 0,
    "lineBytes must be a power of two greater than one")

  private val ptrWidth = log2Ceil(entries)
  private val countWidth = log2Ceil(entries + 1)

  val io = IO(new LoadRefillTransportIO(entries, addrWidth, lineBytes))

  private def zeroEntry: LoadRefillTransportEntry =
    0.U.asTypeOf(new LoadRefillTransportEntry(addrWidth, lineBytes))

  val rows = RegInit(VecInit(Seq.fill(entries)(zeroEntry)))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  val blocked = io.hardFlush || io.hold
  val outValid = count =/= 0.U && !blocked
  val outAccepted = outValid && io.outReady
  val postDequeueCapacity = entries.U((countWidth + 1).W) - count + outAccepted

  val missReady = !blocked && postDequeueCapacity =/= 0.U
  val missAccepted = io.missValid && missReady
  val externalReady = !blocked && postDequeueCapacity > missAccepted.asUInt
  val externalAccepted = io.externalValid && externalReady
  val enqueueCount = missAccepted.asUInt +& externalAccepted.asUInt
  val externalIndex = Mux(missAccepted, tail + 1.U, tail)(ptrWidth - 1, 0)

  io.missReady := missReady
  io.externalReady := externalReady
  io.missAccepted := missAccepted
  io.externalAccepted := externalAccepted
  io.dualIngressAccepted := missAccepted && externalAccepted
  io.outValid := outValid
  io.out := rows(head).refill
  io.outFromMissQueue := rows(head).fromMissQueue
  io.outAccepted := outAccepted

  when(io.hardFlush) {
    for (idx <- 0 until entries) {
      rows(idx) := zeroEntry
    }
    head := 0.U
    tail := 0.U
    count := 0.U
  }.otherwise {
    when(outAccepted) {
      rows(head) := zeroEntry
      head := head + 1.U
    }
    when(missAccepted) {
      rows(tail) := zeroEntry
      rows(tail).valid := true.B
      rows(tail).fromMissQueue := true.B
      rows(tail).refill := io.miss
    }
    when(externalAccepted) {
      rows(externalIndex) := zeroEntry
      rows(externalIndex).valid := true.B
      rows(externalIndex).fromMissQueue := false.B
      rows(externalIndex).refill := io.external
    }
    when(enqueueCount =/= 0.U) {
      tail := tail + enqueueCount
    }
    count := count + enqueueCount - outAccepted.asUInt
  }

  val validVec = VecInit(rows.map(_.valid))
  io.resident := rows
  io.validMask := validVec.asUInt
  io.count := count
  io.empty := count === 0.U
  io.full := count === entries.U
  io.pending := count =/= 0.U
  io.missBlocked := io.missValid && !missReady
  io.externalBlocked := io.externalValid && !externalReady
  io.protocolError := count > entries.U || PopCount(validVec) =/= count
}
