package linxcore.ctu

import chisel3._
import chisel3.util.{Cat, is, switch}
import linxcore.common.{TemplateD3Constants, TemplateForm, TemplateRowKind}
import linxcore.params.CoreParams
import linxcore.top.interface.{FetchedInstruction, FrontEndOp, FrontEndOpKind}

class TemplateExpandIO(val p: CoreParams) extends Bundle {
  val active = Input(Bool())
  val parent = Input(new FetchedInstruction(p))
  val decode = Input(new TemplateDecodeResult(p))
  val ordinal = Input(UInt(8.W))
  val out = Output(new FrontEndOp(p))
}

/** Pure child descriptor builder.
  *
  * The parent raw encoding remains attached so D1/D2 can validate every child
  * before D3 reserves backend resources atomically.
  */
class TemplateExpand(val p: CoreParams) extends Module {
  val io = IO(new TemplateExpandIO(p))

  val rowKind = TemplateD3Constants.rowKind(
    io.decode.form.asUInt,
    io.decode.rangeCount,
    io.ordinal)

  val registerOrdinal = WireDefault(0.U(8.W))
  switch(io.decode.form) {
    is(TemplateForm.FENTRY) {
      registerOrdinal := Mux(io.ordinal >= 2.U, io.ordinal - 2.U, 0.U)
    }
    is(TemplateForm.FEXIT) {
      registerOrdinal := Mux(io.ordinal >= 2.U, io.ordinal - 2.U, 0.U)
    }
    is(TemplateForm.FRET_RA) {
      registerOrdinal := Mux(io.ordinal >= 4.U, io.ordinal - 4.U, 0.U)
    }
    is(TemplateForm.FRET_STK) {
      registerOrdinal := Mux(io.ordinal >= 6.U, io.ordinal - 6.U, 0.U)
    }
  }
  val linearRegister =
    io.decode.rangeStart - 2.U + registerOrdinal
  val childRegister =
    2.U + Mux(linearRegister >= 22.U, linearRegister - 22.U, linearRegister)
  val isMemoryRow =
    rowKind === TemplateRowKind.STORE.asUInt ||
      rowKind === TemplateRowKind.LOAD.asUInt
  val isStackRow =
    rowKind === TemplateRowKind.SP_SUB.asUInt ||
      rowKind === TemplateRowKind.SP_ADD.asUInt

  io.out := 0.U.asTypeOf(io.out)
  io.out.kind := FrontEndOpKind.TemplateUop
  io.out.parent := io.parent
  io.out.templateOrdinal := io.ordinal
  io.out.templateCount := io.decode.rowCount
  io.out.templateOpcode := rowKind
  io.out.templateImmediate := Mux(
    isMemoryRow,
    childRegister,
    Mux(
      isStackRow,
      io.decode.frameImmediate,
      Cat(io.decode.rangeStart, io.decode.rangeEnd)))

  assert(!io.active || !io.decode.supported || io.ordinal < io.decode.rowCount)
}
