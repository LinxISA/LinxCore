package linxcore.ctu

import chisel3._
import chisel3.util.{is, switch}
import linxcore.common.{InterfaceParams, TemplateD3Constants, TemplateForm}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.params.CoreParams
import linxcore.top.interface.FetchedInstruction

class TemplateDecodeResult(val p: CoreParams) extends Bundle {
  val isTemplate = Bool()
  val supported = Bool()
  val form = TemplateForm()
  val opcode = UInt(p.opcodeWidth.W)
  val rangeStart = UInt(p.archRegWidth.W)
  val rangeEnd = UInt(p.archRegWidth.W)
  val rangeCount = UInt(5.W)
  val rowCount = UInt(6.W)
  val frameImmediate = UInt(p.dataWidth.W)
}

class TemplateDecodeIO(val p: CoreParams) extends Bundle {
  val in = Input(new FetchedInstruction(p))
  val out = Output(new TemplateDecodeResult(p))
}

/** Generated-catalog-backed recognition of the architectural template forms.
  *
  * This owner extracts only the information needed to describe canonical
  * children. It does not allocate ROB, rename, issue, or memory state.
  */
class TemplateDecode(val p: CoreParams) extends Module {
  val io = IO(new TemplateDecodeIO(p))

  private val catalogP = InterfaceParams()
  require(catalogP.opcodeWidth == p.opcodeWidth)
  private val templateOpcodes = Set(
    FrontendOpcodeDecodeTable.OP_FENTRY,
    FrontendOpcodeDecodeTable.OP_FEXIT,
    FrontendOpcodeDecodeTable.OP_FRET_RA,
    FrontendOpcodeDecodeTable.OP_FRET_STK)
  private val templateRules =
    FrontendOpcodeDecodeTable.Rules.filter(rule =>
      templateOpcodes.contains(rule.opcode))

  var matched: Bool = false.B
  var opcode: UInt = 0.U(p.opcodeWidth.W)
  for (rule <- templateRules) {
    val hit = !matched &&
      io.in.lengthBytes === rule.lenBytes.U &&
      ((io.in.instruction & rule.mask.U(p.instructionWidth.W)) ===
        rule.value.U(p.instructionWidth.W))
    opcode = Mux(hit, rule.opcode.U, opcode)
    matched = matched || hit
  }

  val form = WireDefault(TemplateForm.Invalid)
  switch(opcode) {
    is(FrontendOpcodeDecodeTable.OP_FENTRY.U) {
      form := TemplateForm.FENTRY
    }
    is(FrontendOpcodeDecodeTable.OP_FEXIT.U) {
      form := TemplateForm.FEXIT
    }
    is(FrontendOpcodeDecodeTable.OP_FRET_RA.U) {
      form := TemplateForm.FRET_RA
    }
    is(FrontendOpcodeDecodeTable.OP_FRET_STK.U) {
      form := TemplateForm.FRET_STK
    }
  }

  val rangeStart = io.in.instruction(19, 15)
  val rangeEnd = io.in.instruction(24, 20)
  val legalStart = rangeStart >= 2.U && rangeStart <= 23.U
  val legalEnd = rangeEnd >= 2.U && rangeEnd <= 23.U
  val normalCount = rangeEnd - rangeStart + 1.U
  val wrappedCount = (24.U - rangeStart) + (rangeEnd - 1.U)
  val rangeCount = Mux(rangeStart <= rangeEnd, normalCount, wrappedCount)
  val supported = matched && legalStart && legalEnd &&
    TemplateD3Constants.encodedNSupported(rangeCount)

  val frameImmediate =
    (io.in.instruction(11, 7).pad(p.dataWidth) << 10) |
      (io.in.instruction(31, 25).pad(p.dataWidth) << 3)

  io.out := 0.U.asTypeOf(io.out)
  io.out.isTemplate := matched
  io.out.supported := supported
  io.out.form := form
  io.out.opcode := opcode
  io.out.rangeStart := rangeStart
  io.out.rangeEnd := rangeEnd
  io.out.rangeCount := rangeCount
  io.out.rowCount := TemplateD3Constants.rowCount(form.asUInt, rangeCount)
  io.out.frameImmediate := frameImmediate

  assert(!io.out.supported || io.out.rowCount <= p.ctu.maxTemplateUops.U)
}
