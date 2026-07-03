package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplayReturnLretEntry(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val valid = Bool()
  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val loadLsId = new ROBID(idEntries)
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
  val dst = new LoadReplayDestination(archRegWidth, physRegWidth)
  val data = UInt(dataWidth.W)
  val pipeIndex = UInt(returnPipeIndexWidth.W)
  val specWakeup = Bool()
  val stackValid = Bool()
}

class LoadReplayReturnLretSinkIO(
    val idEntries: Int,
    val depth: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)

  val flush = Input(Bool())
  val enqueueValid = Input(Bool())
  val enqueue = Input(new LoadReplayReturnLretEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))
  val drainReady = Input(Bool())

  val enqueueReady = Output(Bool())
  val enqueueAccepted = Output(Bool())
  val enqueueDropped = Output(Bool())
  val drainValid = Output(Bool())
  val drain = Output(new LoadReplayReturnLretEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))
  val drainFire = Output(Bool())
  val pending = Output(Bool())
  val full = Output(Bool())
  val empty = Output(Bool())
  val count = Output(UInt(countWidth.W))
  val blockedByFlush = Output(Bool())
  val blockedByNoPayload = Output(Bool())
  val blockedByFull = Output(Bool())
  val blockedByDrain = Output(Bool())
}

class LoadReplayReturnLretSink(
    val idEntries: Int = 16,
    val depth: Int = 2,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(depth > 0, "LRET sink depth must be nonzero")
  require(addrWidth > 0, "addrWidth must be positive")
  require(pcWidth > 0, "pcWidth must be positive")
  require(dataWidth > 0, "dataWidth must be positive")
  require(sizeWidth > 0, "sizeWidth must be positive")
  require(returnPipeCount > 0, "returnPipeCount must be positive")

  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new LoadReplayReturnLretSinkIO(
    idEntries,
    depth,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  private def zeroEntry: LoadReplayReturnLretEntry = {
    val entry = Wire(new LoadReplayReturnLretEntry(
      idEntries,
      addrWidth,
      pcWidth,
      dataWidth,
      sizeWidth,
      returnPipeCount,
      archRegWidth,
      physRegWidth
    ))
    entry := 0.U.asTypeOf(entry)
    entry.bid := ROBID.disabled(idEntries)
    entry.gid := ROBID.disabled(idEntries)
    entry.rid := ROBID.disabled(idEntries)
    entry.loadLsId := ROBID.disabled(idEntries)
    entry.dst := LoadReplayDestination.none(archRegWidth, physRegWidth)
    entry
  }

  private def inc(ptr: UInt): UInt =
    if (depth == 1) 0.U(ptrWidth.W) else Mux(ptr === (depth - 1).U, 0.U, ptr + 1.U)

  val entries = RegInit(VecInit(Seq.fill(depth)(zeroEntry)))
  val headPtr = RegInit(0.U(ptrWidth.W))
  val tailPtr = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  val inputValid = io.enqueueValid && io.enqueue.valid
  val drainValid = (count =/= 0.U) && !io.flush
  val drainFire = drainValid && io.drainReady
  val enqueueReady = !io.flush && ((count =/= depth.U) || drainFire)
  val enqueueAccepted = inputValid && enqueueReady
  val enqueueDropped = inputValid && !io.flush && !enqueueReady

  val enqueuePayload = Wire(new LoadReplayReturnLretEntry(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))
  enqueuePayload := io.enqueue
  enqueuePayload.valid := true.B

  when(io.flush) {
    for (idx <- 0 until depth) {
      entries(idx) := zeroEntry
    }
    headPtr := 0.U
    tailPtr := 0.U
    count := 0.U
  }.otherwise {
    when(drainFire) {
      headPtr := inc(headPtr)
    }
    when(enqueueAccepted) {
      entries(tailPtr) := enqueuePayload
      tailPtr := inc(tailPtr)
    }

    when(enqueueAccepted && !drainFire) {
      count := count + 1.U
    }.elsewhen(!enqueueAccepted && drainFire) {
      count := count - 1.U
    }
  }

  io.enqueueReady := enqueueReady
  io.enqueueAccepted := enqueueAccepted
  io.enqueueDropped := enqueueDropped
  io.drainValid := drainValid
  io.drain := Mux(drainValid, entries(headPtr), zeroEntry)
  io.drainFire := drainFire
  io.pending := count =/= 0.U
  io.full := count === depth.U
  io.empty := count === 0.U
  io.count := count
  io.blockedByFlush := io.flush && io.enqueueValid
  io.blockedByNoPayload := io.enqueueValid && !io.enqueue.valid
  io.blockedByFull := inputValid && !io.flush && !enqueueReady
  io.blockedByDrain := drainValid && !io.drainReady
}
