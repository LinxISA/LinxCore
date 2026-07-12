package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class ReducedLoadReplayRelaunchQueueIO(
    val idEntries: Int,
    val depth: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)

  val flush = Input(Bool())
  val enqueueValid = Input(Bool())
  val enqueue = Input(new ReducedLoadReplayCandidate(
    idEntries, addrWidth, pcWidth, sizeWidth, archRegWidth, physRegWidth, lsidWidth))
  val outReady = Input(Bool())

  val enqueueReady = Output(Bool())
  val enqueueAccepted = Output(Bool())
  val enqueueDropped = Output(Bool())
  val outValid = Output(Bool())
  val out = Output(new ReducedLoadReplayCandidate(
    idEntries, addrWidth, pcWidth, sizeWidth, archRegWidth, physRegWidth, lsidWidth))
  val outFire = Output(Bool())
  val pending = Output(Bool())
  val full = Output(Bool())
  val empty = Output(Bool())
  val count = Output(UInt(countWidth.W))
}

class ReducedLoadReplayRelaunchQueue(
    val idEntries: Int = 16,
    val depth: Int = 2,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6,
    val lsidWidth: Int = 32)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(depth > 0, "relaunch queue depth must be nonzero")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new ReducedLoadReplayRelaunchQueueIO(
    idEntries,
    depth,
    addrWidth,
    pcWidth,
    sizeWidth,
    archRegWidth,
    physRegWidth,
    lsidWidth
  ))

  private def zeroCandidate: ReducedLoadReplayCandidate = {
    val candidate = Wire(new ReducedLoadReplayCandidate(
      idEntries, addrWidth, pcWidth, sizeWidth, archRegWidth, physRegWidth, lsidWidth))
    candidate := 0.U.asTypeOf(candidate)
    candidate.dst := LoadReplayDestination.none(archRegWidth, physRegWidth)
    candidate.bid := ROBID.disabled(idEntries)
    candidate.gid := ROBID.disabled(idEntries)
    candidate.rid := ROBID.disabled(idEntries)
    candidate.loadLsId := ROBID.disabled(idEntries)
    candidate.youngestStoreId := ROBID.disabled(idEntries)
    candidate.youngestStoreLsId := ROBID.disabled(idEntries)
    candidate
  }

  private def inc(ptr: UInt): UInt =
    if (depth == 1) 0.U(ptrWidth.W) else Mux(ptr === (depth - 1).U, 0.U, ptr + 1.U)

  val entries = RegInit(VecInit(Seq.fill(depth)(zeroCandidate)))
  val headPtr = RegInit(0.U(ptrWidth.W))
  val tailPtr = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  val inputValid = io.enqueueValid && io.enqueue.valid
  val outValid = (count =/= 0.U) && !io.flush
  val outFire = outValid && io.outReady
  val enqueueReady = !io.flush && ((count =/= depth.U) || outFire)
  val enqueueAccepted = inputValid && enqueueReady
  val enqueueDropped = inputValid && !io.flush && !enqueueReady

  val enqueuePayload = Wire(new ReducedLoadReplayCandidate(
    idEntries, addrWidth, pcWidth, sizeWidth, archRegWidth, physRegWidth, lsidWidth))
  enqueuePayload := io.enqueue
  enqueuePayload.valid := true.B

  when(io.flush) {
    for (idx <- 0 until depth) {
      entries(idx) := zeroCandidate
    }
    headPtr := 0.U
    tailPtr := 0.U
    count := 0.U
  }.otherwise {
    when(outFire) {
      headPtr := inc(headPtr)
    }
    when(enqueueAccepted) {
      entries(tailPtr) := enqueuePayload
      tailPtr := inc(tailPtr)
    }

    when(enqueueAccepted && !outFire) {
      count := count + 1.U
    }.elsewhen(!enqueueAccepted && outFire) {
      count := count - 1.U
    }
  }

  io.enqueueReady := enqueueReady
  io.enqueueAccepted := enqueueAccepted
  io.enqueueDropped := enqueueDropped
  io.outValid := outValid
  io.out := Mux(outValid, entries(headPtr), zeroCandidate)
  io.outFire := outFire
  io.pending := count =/= 0.U
  io.full := count === depth.U
  io.empty := count === 0.U
  io.count := count
}
