package linxcore.execute

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.{InterfaceParams, RenamedUop}

class ReducedScalarIssueQueueIO(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 4)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)

  val inValid = Input(Bool())
  val inReady = Output(Bool())
  val in = Input(new RenamedUop(p))
  val flushValid = Input(Bool())

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
  val enqueueDstValid = Output(Bool())
  val enqueueDstTag = Output(UInt(p.physRegWidth.W))

  val empty = Output(Bool())
  val full = Output(Bool())
  val count = Output(UInt(countWidth.W))
  val headValid = Output(Bool())
  val allSourcesReady = Output(Bool())
  val blockedBySource = Output(Bool())
  val blockedByOutput = Output(Bool())
}

class ReducedScalarIssueQueue(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 4)
    extends Module {
  require(depth > 1, "reduced scalar issue queue needs at least two entries")
  require((depth & (depth - 1)) == 0, "reduced scalar issue queue depth must be a power of two")

  private val ptrWidth = log2Ceil(depth)
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new ReducedScalarIssueQueueIO(p, depth))

  private def inc(ptr: UInt): UInt =
    Mux(ptr === (depth - 1).U, 0.U(ptrWidth.W), ptr + 1.U)

  val entries = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new RenamedUop(p)))))
  val valid = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  val headValid = count =/= 0.U
  val headUop = entries(head)
  val sourceReady = Wire(Vec(3, Bool()))
  for (idx <- 0 until 3) {
    sourceReady(idx) := !headValid || !headUop.src(idx).valid || io.readReady(idx)
  }
  val allSourcesReady = sourceReady.reduce(_ && _)
  val issueValid = headValid && allSourcesReady
  val issueFire = issueValid && io.issueReady
  val enqueueFire = io.inValid && io.inReady

  io.inReady := (count =/= depth.U) || issueFire
  io.issueValid := issueValid
  io.issueUop := Mux(headValid, headUop, 0.U.asTypeOf(new RenamedUop(p)))
  for (idx <- 0 until 3) {
    io.readValid(idx) := headValid && headUop.src(idx).valid
    io.readTags(idx) := headUop.src(idx).physTag
    io.issueSrcData(idx) := io.readData(idx)
  }

  io.enqueueFire := enqueueFire
  io.issueFire := issueFire
  io.enqueueDstValid := enqueueFire && io.in.dst(0).valid
  io.enqueueDstTag := io.in.dst(0).physTag
  io.empty := count === 0.U
  io.full := count === depth.U
  io.count := count
  io.headValid := headValid
  io.allSourcesReady := allSourcesReady
  io.blockedBySource := headValid && !allSourcesReady
  io.blockedByOutput := issueValid && !io.issueReady

  when(io.flushValid) {
    for (idx <- 0 until depth) {
      valid(idx) := false.B
    }
    head := 0.U
    tail := 0.U
    count := 0.U
  }.otherwise {
    when(issueFire) {
      valid(head) := false.B
      head := inc(head)
    }
    when(enqueueFire) {
      entries(tail) := io.in
      valid(tail) := true.B
      tail := inc(tail)
    }
    count := count + enqueueFire.asUInt - issueFire.asUInt
  }
}
