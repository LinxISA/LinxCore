package linxcore.execute

import chisel3._
import chisel3.util.{log2Ceil, PopCount}

import linxcore.common.{DestinationKind, InterfaceParams, OperandClass, RenamedOperand, RenamedUop}
import linxcore.rob.{ROBID}

class ReducedScalarIssueQueueIO(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 4)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)
  private val indexWidth = log2Ceil(depth)

  val inValid = Input(Bool())
  val inReady = Output(Bool())
  val in = Input(new RenamedUop(p))
  val flushValid = Input(Bool())

  val releaseValid = Input(Bool())
  val releaseBid = Input(new ROBID(p.robEntries))
  val releaseRid = Input(new ROBID(p.robEntries))
  val releaseStid = Input(UInt(p.threadIdWidth.W))

  val readyMask = Input(UInt((1 << p.physRegWidth).W))
  val localTReadyMask = Input(UInt(4.W))
  val localUReadyMask = Input(UInt(4.W))
  val readValid = Output(Vec(3, Bool()))
  val readTags = Output(Vec(3, UInt(p.physRegWidth.W)))
  val readOperandClass = Output(Vec(3, OperandClass()))
  val readRelTag = Output(Vec(3, UInt(p.archRegWidth.W)))
  val readReady = Input(Vec(3, Bool()))
  val readData = Input(Vec(3, UInt(p.immWidth.W)))

  val issueValid = Output(Bool())
  val issueReady = Input(Bool())
  val issueUop = Output(new RenamedUop(p))
  val issueSrcData = Output(Vec(3, UInt(p.immWidth.W)))

  val enqueueFire = Output(Bool())
  val pickFire = Output(Bool())
  val issueFire = Output(Bool())
  val cancelFire = Output(Bool())
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
  val headPc = Output(UInt(p.pcWidth.W))
  val headOpcode = Output(UInt(p.opcodeWidth.W))
  val headSrcValidMask = Output(UInt(3.W))
  val headSrcOperandClass = Output(Vec(3, OperandClass()))
  val headSrcPhysTag = Output(Vec(3, UInt(p.physRegWidth.W)))
  val headSrcRelTag = Output(Vec(3, UInt(p.archRegWidth.W)))
  val sourceReadyMask = Output(UInt(3.W))
  val allSourcesReady = Output(Bool())
  val selectedValid = Output(Bool())
  val selectedIndex = Output(UInt(indexWidth.W))
  val selectedReadReady = Output(Bool())
  val i1Valid = Output(Bool())
  val i2Valid = Output(Bool())
  val stageBusy = Output(Bool())
  val blockedBySource = Output(Bool())
  val blockedByRead = Output(Bool())
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
  val srcReady = RegInit(VecInit(Seq.fill(depth)(VecInit(Seq.fill(3)(false.B)))))
  val count = RegInit(0.U(countWidth.W))

  val headValid = count =/= 0.U
  val headIssued = headValid && issued(0)
  val headUop = entries(0)

  private def localSourceReady(src: RenamedOperand): Bool = {
    val rel = src.relTag(1, 0)
    Mux(
      src.operandClass === OperandClass.T,
      io.localTReadyMask(rel),
      Mux(src.operandClass === OperandClass.U, io.localUReadyMask(rel), false.B)
    )
  }

  private def laneReadyFromMask(uop: RenamedUop, lane: Int): Bool = {
    val src = uop.src(lane)
    val isLocal = src.operandClass === OperandClass.T || src.operandClass === OperandClass.U
    val isScalarSp = src.operandClass === OperandClass.P && src.relTag === 1.U
    !src.valid || Mux(isLocal, localSourceReady(src), Mux(isScalarSp, true.B, io.readyMask(src.physTag)))
  }

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

  val entrySourceReady = Wire(Vec(depth, Vec(3, Bool())))
  val entryAllSourcesReady = Wire(Vec(depth, Bool()))
  val entrySelectable = Wire(Vec(depth, Bool()))
  for (entryIdx <- 0 until depth) {
    for (lane <- 0 until 3) {
      entrySourceReady(entryIdx)(lane) :=
        !valid(entryIdx) || issued(entryIdx) || srcReady(entryIdx)(lane)
    }
    entryAllSourcesReady(entryIdx) := entrySourceReady(entryIdx).reduce(_ && _)
    entrySelectable(entryIdx) := valid(entryIdx) && !issued(entryIdx) && entryAllSourcesReady(entryIdx)
  }

  val headSourceReady = entrySourceReady(0)
  val headAllSourcesReady = headSourceReady.reduce(_ && _)
  val enqueueFire = io.inValid && io.inReady
  val issuedVec = VecInit((0 until depth).map(idx => valid(idx) && issued(idx)))
  val issuedCount = PopCount(issuedVec.asUInt)
  val notIssuedCount = count - issuedCount

  val pick = Module(new ReducedScalarIssuePick(p, depth))
  pick.io.selectableMask := entrySelectable.asUInt
  pick.io.entries := entries
  pick.io.headValid := headValid
  pick.io.headIssued := headIssued
  pick.io.notIssuedCount := notIssuedCount
  pick.io.flushValid := io.flushValid
  pick.io.readReady := io.readReady
  pick.io.readData := io.readData
  pick.io.issueReady := io.issueReady
  val pickFire = pick.io.pickFire
  val issueFire = pick.io.issueFire
  val cancelFire = pick.io.cancelFire

  io.inReady := (count =/= depth.U) || releaseFire
  io.issueValid := pick.io.issueValid
  io.issueUop := pick.io.issueUop
  for (idx <- 0 until 3) {
    io.readValid(idx) := pick.io.readValid(idx)
    io.readTags(idx) := pick.io.readTags(idx)
    io.readOperandClass(idx) := pick.io.readOperandClass(idx)
    io.readRelTag(idx) := pick.io.readRelTag(idx)
    io.issueSrcData(idx) := pick.io.issueSrcData(idx)
  }

  io.enqueueFire := enqueueFire
  io.pickFire := pickFire
  io.issueFire := issueFire
  io.cancelFire := cancelFire
  io.releaseFire := releaseFire
  io.enqueueDstValid := enqueueFire && io.in.dst(0).valid && (io.in.dst(0).kind === DestinationKind.Gpr)
  io.enqueueDstTag := io.in.dst(0).physTag
  io.empty := count === 0.U
  io.full := count === depth.U
  io.count := count
  io.issuedCount := issuedCount
  io.notIssuedCount := notIssuedCount
  io.headValid := headValid
  io.headIssued := headIssued
  io.headPc := Mux(headValid, headUop.pc, 0.U)
  io.headOpcode := Mux(headValid, headUop.opcode, 0.U)
  io.headSrcValidMask := VecInit((0 until 3).map(lane => headValid && headUop.src(lane).valid)).asUInt
  for (idx <- 0 until 3) {
    io.headSrcOperandClass(idx) := headUop.src(idx).operandClass
    io.headSrcPhysTag(idx) := Mux(headValid, headUop.src(idx).physTag, 0.U)
    io.headSrcRelTag(idx) := Mux(headValid, headUop.src(idx).relTag, 0.U)
  }
  io.sourceReadyMask := headSourceReady.asUInt
  io.allSourcesReady := headAllSourcesReady
  io.selectedValid := pick.io.selectedValid
  io.selectedIndex := pick.io.selectedIndex
  io.selectedReadReady := pick.io.selectedReadReady
  io.i1Valid := pick.io.i1Valid
  io.i2Valid := pick.io.i2Valid
  io.stageBusy := pick.io.stageBusy
  io.blockedBySource := pick.io.blockedBySource
  io.blockedByRead := pick.io.blockedByRead
  io.blockedByOutput := pick.io.blockedByOutput
  io.blockedByIssued := pick.io.blockedByIssued

  val preIssued = Wire(Vec(depth, Bool()))
  for (idx <- 0 until depth) {
    preIssued(idx) :=
      (issued(idx) || (pickFire && pick.io.selectedIndex === idx.U)) &&
        !(cancelFire && pick.io.cancelIndex === idx.U)
  }

  val preSrcReady = Wire(Vec(depth, Vec(3, Bool())))
  for (entryIdx <- 0 until depth) {
    for (lane <- 0 until 3) {
      preSrcReady(entryIdx)(lane) :=
        srcReady(entryIdx)(lane) || (valid(entryIdx) && laneReadyFromMask(entries(entryIdx), lane))
    }
  }

  val keep = Wire(Vec(depth, Bool()))
  for (idx <- 0 until depth) {
    keep(idx) := valid(idx) && !releaseMatches(idx)
  }

  val baseCount = count - releaseFire.asUInt
  val nextEntries = Wire(Vec(depth, new RenamedUop(p)))
  val nextValid = Wire(Vec(depth, Bool()))
  val nextIssued = Wire(Vec(depth, Bool()))
  val nextSrcReady = Wire(Vec(depth, Vec(3, Bool())))
  for (dst <- 0 until depth) {
    nextEntries(dst) := 0.U.asTypeOf(new RenamedUop(p))
    nextValid(dst) := false.B
    nextIssued(dst) := false.B
    nextSrcReady(dst) := VecInit(Seq.fill(3)(false.B))

    for (src <- 0 until depth) {
      val keptBefore =
        if (src == 0) 0.U(countWidth.W) else PopCount(VecInit((0 until src).map(keep(_))).asUInt)
      when(keep(src) && keptBefore === dst.U) {
        nextEntries(dst) := entries(src)
        nextValid(dst) := true.B
        nextIssued(dst) := preIssued(src)
        nextSrcReady(dst) := preSrcReady(src)
      }
    }

    when(enqueueFire && baseCount === dst.U) {
      nextEntries(dst) := io.in
      nextValid(dst) := true.B
      nextIssued(dst) := false.B
      nextSrcReady(dst) := VecInit((0 until 3).map(lane => laneReadyFromMask(io.in, lane)))
    }
  }

  when(io.flushValid) {
    for (idx <- 0 until depth) {
      valid(idx) := false.B
      issued(idx) := false.B
      srcReady(idx) := VecInit(Seq.fill(3)(false.B))
    }
    count := 0.U
  }.otherwise {
    entries := nextEntries
    valid := nextValid
    issued := nextIssued
    srcReady := nextSrcReady
    count := baseCount + enqueueFire.asUInt
  }
}
