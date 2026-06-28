package linxcore.frontend

import chisel3._
import linxcore.common._

object FrontendRegAliasClassify {
  val ScalarGprCount = 24
  val SourceTLeft = 24
  val SourceTRight = 27
  val SourceULeft = 28
  val SourceURight = 31
  val DestinationUQueueTag = 30
  val DestinationTQueueTag = 31

  private def regInvalid(p: InterfaceParams): UInt =
    LinxCommonConstants.regInvalid(p.archRegWidth)

  def source(p: InterfaceParams, valid: Bool, tag: UInt): DecodedOperand = {
    val out = Wire(new DecodedOperand(p))
    out.valid := valid
    out.operandClass := OperandClass.Invalid
    out.archTag := tag
    out.relTag := regInvalid(p)

    when(valid) {
      when(tag < ScalarGprCount.U(p.archRegWidth.W)) {
        out.operandClass := OperandClass.P
        out.relTag := tag
      }.elsewhen(tag >= SourceTLeft.U(p.archRegWidth.W) && tag <= SourceTRight.U(p.archRegWidth.W)) {
        out.operandClass := OperandClass.T
        out.relTag := tag - SourceTLeft.U(p.archRegWidth.W)
      }.elsewhen(tag >= SourceULeft.U(p.archRegWidth.W) && tag <= SourceURight.U(p.archRegWidth.W)) {
        out.operandClass := OperandClass.U
        out.relTag := tag - SourceULeft.U(p.archRegWidth.W)
      }
    }

    out
  }

  def destination(p: InterfaceParams, valid: Bool, tag: UInt): DecodedDestination = {
    val out = Wire(new DecodedDestination(p))
    out.valid := valid
    out.kind := DestinationKind.None
    out.archTag := tag
    out.relTag := regInvalid(p)

    when(valid) {
      when(tag < ScalarGprCount.U(p.archRegWidth.W)) {
        out.kind := DestinationKind.Gpr
        out.relTag := tag
      }.elsewhen(tag === DestinationTQueueTag.U(p.archRegWidth.W)) {
        out.kind := DestinationKind.T
        out.relTag := 0.U(p.archRegWidth.W)
      }.elsewhen(tag === DestinationUQueueTag.U(p.archRegWidth.W)) {
        out.kind := DestinationKind.U
        out.relTag := 0.U(p.archRegWidth.W)
      }
    }

    out
  }
}
