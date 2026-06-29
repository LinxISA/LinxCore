package linxcore.execute

import chisel3._
import chisel3.util.{log2Ceil, PopCount}

import linxcore.common.{InterfaceParams, RenamedUop}
import linxcore.rob.{ROBID}

class ReducedScalarIssueQueueIO(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 4)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)

  val inValid = Input(Bool())
  val inReady = Output(Bool())
  val in = Input(new RenamedUop(p))
  val flushValid = Input(Bool())

  val releaseValid = Input(Bool())
  val releaseBid = Input(new ROBID(p.robEntries))
  val releaseRid = Input(new ROBID(p.robEntries))
  val releaseStid = Input(UInt(p.threadIdWidth.W))

  val readValid = Output(Vec(3, Bool()))
  val readTags = Output(Vec(3, UInt(p.physRegWidth.W)))
  val readReady = Input(Vec(3, Bool()))
  val readData = Input(Vec(3, UInt(p.immWidth.W)))

  val issueValid = Output(Bool())
  val issueReady = Input(Bool())
  val issueUop = Output(new RenamedUop(p))
  val issueSrcData = Output(Vec(3, UInt(p.immWidth.W)))

  val enqueueFire = Output(Bool())
  val issueFire = Output(Bool())
  val releaseFire = Output(Bool())
  val enqueueDstValid = Output(Bool())
  val enqueueDstTag = Output(UInt(p.physRegWidth.W))

  val empty = Output(Bool())
  val full = Output(Bool())
  val count = Output(UInt(countWidth.W))
  val issuedCount = Output(UInt(countWidth.W))
  val notIssuedCount = Output(UInt(countWidth.W))
  val headValid = Output(Bool())
  val headIssued = Output(Bool())
  val allSourcesReady = Output(Bool())
  val blockedBySource = Output(Bool())
  val blockedByOutput = Output(Bool())
  val blockedByIssued = Output(Bool())
}

class ReducedScalarIssueQueue(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 4)
    extends Module {
  require(depth > 1, "reduced scalar issue queue needs at least two entries")
  require((depth & (depth - 1)) == 0, "reduced scalar issue queue depth must be a power of two")

  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new ReducedScalarIssueQueueIO(p, depth))

  private def sameRobId(lhs: ROBID, rhs: ROBID): Bool =
    ROBID.equal(lhs, rhs)

  val entries = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new RenamedUop(p)))))
  val valid = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val issued = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val count = RegInit(0.U(countWidth.W))

  val headValid = count =/= 0.U
  val headIssued = headValid && issued(0)
  val headUop = entries(0)

  val rawReleaseMatches = Wire(Vec(depth, Bool()))
  val releaseMatches = Wire(Vec(depth, Bool()))
  for (idx <- 0 until depth) {
    rawReleaseMatches(idx) := valid(idx) && issued(idx) &&
      sameRobId(entries(idx).bid, io.releaseBid) &&
      sameRobId(entries(idx).rid, io.releaseRid) &&
      (entries(idx).threadId === io.releaseStid)
    val earlierMatch =
      if (idx == 0) false.B else VecInit((0 until idx).map(rawReleaseMatches(_))).asUInt.orR
    releaseMatches(idx) := io.releaseValid && rawReleaseMatches(idx) && !earlierMatch
  }
  val releaseFire = io.releaseValid && rawReleaseMatches.asUInt.orR

  val sourceReady = Wire(Vec(3, Bool()))
  for (idx <- 0 until 3) {
    sourceReady(idx) := !headValid || headIssued || !headUop.src(idx).valid || io.readReady(idx)
  }
  val allSourcesReady = sourceReady.reduce(_ && _)
  val issueValid = headValid && !headIssued && allSourcesReady
  val issueFire = issueValid && io.issueReady
  val enqueueFire = io.inValid && io.inReady
  val issuedVec = VecInit((0 until depth).map(idx => valid(idx) && issued(idx)))
  val issuedCount = PopCount(issuedVec.asUInt)
  val notIssuedCount = count - issuedCount

  io.inReady := (count =/= depth.U) || releaseFire
  io.issueValid := issueValid
  io.issueUop := Mux(headValid, headUop, 0.U.asTypeOf(new RenamedUop(p)))
  for (idx <- 0 until 3) {
    io.readValid(idx) := headValid && !headIssued && headUop.src(idx).valid
    io.readTags(idx) := headUop.src(idx).physTag
    io.issueSrcData(idx) := io.readData(idx)
  }

  io.enqueueFire := enqueueFire
  io.issueFire := issueFire
  io.releaseFire := releaseFire
  io.enqueueDstValid := enqueueFire && io.in.dst(0).valid
  io.enqueueDstTag := io.in.dst(0).physTag
  io.empty := count === 0.U
  io.full := count === depth.U
  io.count := count
  io.issuedCount := issuedCount
  io.notIssuedCount := notIssuedCount
  io.headValid := headValid
  io.headIssued := headIssued
  io.allSourcesReady := allSourcesReady
  io.blockedBySource := headValid && !headIssued && !allSourcesReady
  io.blockedByOutput := issueValid && !io.issueReady
  io.blockedByIssued := headIssued

  val preIssued = Wire(Vec(depth, Bool()))
  for (idx <- 0 until depth) {
    preIssued(idx) := issued(idx) || (if (idx == 0) issueFire else false.B)
  }

  val keep = Wire(Vec(depth, Bool()))
  for (idx <- 0 until depth) {
    keep(idx) := valid(idx) && !releaseMatches(idx)
  }

  val baseCount = count - releaseFire.asUInt
  val nextEntries = Wire(Vec(depth, new RenamedUop(p)))
  val nextValid = Wire(Vec(depth, Bool()))
  val nextIssued = Wire(Vec(depth, Bool()))
  for (dst <- 0 until depth) {
    nextEntries(dst) := 0.U.asTypeOf(new RenamedUop(p))
    nextValid(dst) := false.B
    nextIssued(dst) := false.B

    for (src <- 0 until depth) {
      val keptBefore =
        if (src == 0) 0.U(countWidth.W) else PopCount(VecInit((0 until src).map(keep(_))).asUInt)
      when(keep(src) && keptBefore === dst.U) {
        nextEntries(dst) := entries(src)
        nextValid(dst) := true.B
        nextIssued(dst) := preIssued(src)
      }
    }

    when(enqueueFire && baseCount === dst.U) {
      nextEntries(dst) := io.in
      nextValid(dst) := true.B
      nextIssued(dst) := false.B
    }
  }

  when(io.flushValid) {
    for (idx <- 0 until depth) {
      valid(idx) := false.B
      issued(idx) := false.B
    }
    count := 0.U
  }.otherwise {
    entries := nextEntries
    valid := nextValid
    issued := nextIssued
    count := baseCount + enqueueFire.asUInt
  }
}
