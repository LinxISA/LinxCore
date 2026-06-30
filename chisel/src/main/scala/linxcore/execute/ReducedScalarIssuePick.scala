package linxcore.execute

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.{InterfaceParams, OperandClass, RenamedUop}

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
  val flushValid = Input(Bool())

  val readValid = Output(Vec(3, Bool()))
  val readTags = Output(Vec(3, UInt(p.physRegWidth.W)))
  val readOperandClass = Output(Vec(3, OperandClass()))
  val readRelTag = Output(Vec(3, UInt(p.archRegWidth.W)))
  val readReady = Input(Vec(3, Bool()))
  val readData = Input(Vec(3, UInt(p.immWidth.W)))

  val issueValid = Output(Bool())
  val issueReady = Input(Bool())
  val issueFire = Output(Bool())
  val issueUop = Output(new RenamedUop(p))
  val issueSrcData = Output(Vec(3, UInt(p.immWidth.W)))

  val pickFire = Output(Bool())
  val cancelFire = Output(Bool())
  val cancelIndex = Output(UInt(indexWidth.W))
  val i1Valid = Output(Bool())
  val i2Valid = Output(Bool())
  val stageBusy = Output(Bool())

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

  val i1Valid = RegInit(false.B)
  val i1Index = RegInit(0.U(log2Ceil(depth).W))
  val i1Uop = RegInit(0.U.asTypeOf(new RenamedUop(p)))
  val i2Valid = RegInit(false.B)
  val i2Uop = RegInit(0.U.asTypeOf(new RenamedUop(p)))
  val i2SrcData = RegInit(VecInit(Seq.fill(3)(0.U(p.immWidth.W))))
  val active = !io.flushValid

  for (idx <- 0 until 3) {
    io.readValid(idx) := active && i1Valid && i1Uop.src(idx).valid
    io.readTags(idx) := i1Uop.src(idx).physTag
    io.readOperandClass(idx) := i1Uop.src(idx).operandClass
    io.readRelTag(idx) := i1Uop.src(idx).relTag
    io.issueSrcData(idx) := i2SrcData(idx)
  }

  val p1ReadReady = VecInit((0 until 3).map(idx => !selectedUop.src(idx).valid || io.readReady(idx))).asUInt.andR
  // The reduced RF read data is combinational; once the queue selected a ready
  // uop, later ready-bit drops must not cancel the already-picked read.
  val i1ReadReady = true.B
  val issueValid = active && i2Valid
  val issueFire = issueValid && io.issueReady
  val i2CanAccept = !i2Valid || issueFire
  val i1Advance = i1Valid && i1ReadReady && i2CanAccept
  val i1Cancel = i1Valid && !i1ReadReady
  val i1WillClear = i1Advance || i1Cancel
  val i1CanAccept = !i1Valid || i1WillClear
  val pickFire = active && selectedValid && i1CanAccept
  val stageBusy = i1Valid || i2Valid

  io.issueValid := issueValid
  io.issueFire := issueFire
  io.issueUop := i2Uop
  io.pickFire := pickFire
  io.cancelFire := active && i1Cancel
  io.cancelIndex := i1Index
  io.i1Valid := i1Valid
  io.i2Valid := i2Valid
  io.stageBusy := stageBusy
  io.selectedValid := selectedValid
  io.selectedIndex := selectedIndex
  io.selectedReadReady := Mux(i1Valid, i1ReadReady, p1ReadReady)
  io.blockedBySource := !stageBusy && io.headValid && (io.notIssuedCount =/= 0.U) && !selectedValid
  io.blockedByRead := i1Valid && !i1ReadReady
  io.blockedByOutput := issueValid && !io.issueReady
  io.blockedByIssued := io.headIssued && !selectedValid

  when(io.flushValid) {
    i1Valid := false.B
    i2Valid := false.B
  }.otherwise {
    when(i1Advance) {
      i2Valid := true.B
      i2Uop := i1Uop
      i2SrcData := io.readData
    }.elsewhen(issueFire) {
      i2Valid := false.B
    }

    when(pickFire) {
      i1Valid := true.B
      i1Index := selectedIndex
      i1Uop := selectedUop
    }.elsewhen(i1WillClear) {
      i1Valid := false.B
    }
  }
}
