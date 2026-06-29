package linxcore.execute

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.{InterfaceParams, RenamedUop}

class ReducedScalarIssuePickIO(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 4)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)
  private val indexWidth = log2Ceil(depth)

  val selectableMask = Input(UInt(depth.W))
  val entries = Input(Vec(depth, new RenamedUop(p)))
  val headValid = Input(Bool())
  val headIssued = Input(Bool())
  val notIssuedCount = Input(UInt(countWidth.W))

  val readValid = Output(Vec(3, Bool()))
  val readTags = Output(Vec(3, UInt(p.physRegWidth.W)))
  val readReady = Input(Vec(3, Bool()))
  val readData = Input(Vec(3, UInt(p.immWidth.W)))

  val issueValid = Output(Bool())
  val issueReady = Input(Bool())
  val issueFire = Output(Bool())
  val issueUop = Output(new RenamedUop(p))
  val issueSrcData = Output(Vec(3, UInt(p.immWidth.W)))

  val selectedValid = Output(Bool())
  val selectedIndex = Output(UInt(indexWidth.W))
  val selectedReadReady = Output(Bool())
  val blockedBySource = Output(Bool())
  val blockedByRead = Output(Bool())
  val blockedByOutput = Output(Bool())
  val blockedByIssued = Output(Bool())
}

class ReducedScalarIssuePick(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 4)
    extends Module {
  require(depth > 1, "reduced scalar issue pick needs at least two entries")
  require((depth & (depth - 1)) == 0, "reduced scalar issue pick depth must be a power of two")

  val io = IO(new ReducedScalarIssuePickIO(p, depth))

  val selectedValid = io.selectableMask.orR
  val selectedIndex = Wire(UInt(log2Ceil(depth).W))
  selectedIndex := 0.U
  for (idx <- (0 until depth).reverse) {
    when(io.selectableMask(idx)) {
      selectedIndex := idx.U
    }
  }

  val selectedUop = Mux(selectedValid, io.entries(selectedIndex), 0.U.asTypeOf(new RenamedUop(p)))

  for (idx <- 0 until 3) {
    io.readValid(idx) := selectedValid && selectedUop.src(idx).valid
    io.readTags(idx) := selectedUop.src(idx).physTag
    io.issueSrcData(idx) := io.readData(idx)
  }

  val selectedReadReady = VecInit((0 until 3).map(idx => !io.readValid(idx) || io.readReady(idx))).asUInt.andR
  val issueValid = selectedValid && selectedReadReady
  val issueFire = issueValid && io.issueReady

  io.issueValid := issueValid
  io.issueFire := issueFire
  io.issueUop := selectedUop
  io.selectedValid := selectedValid
  io.selectedIndex := selectedIndex
  io.selectedReadReady := selectedReadReady
  io.blockedBySource := io.headValid && (io.notIssuedCount =/= 0.U) && !selectedValid
  io.blockedByRead := selectedValid && !selectedReadReady
  io.blockedByOutput := issueValid && !io.issueReady
  io.blockedByIssued := io.headIssued && !selectedValid
}
