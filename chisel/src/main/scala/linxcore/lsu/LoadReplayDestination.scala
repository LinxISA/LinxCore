package linxcore.lsu

import chisel3._

import linxcore.common.DestinationKind

class LoadReplayDestination(
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  val valid = Bool()
  val kind = DestinationKind()
  val archTag = UInt(archRegWidth.W)
  val relTag = UInt(archRegWidth.W)
  val physTag = UInt(physRegWidth.W)
  val oldPhysTag = UInt(physRegWidth.W)
}

object LoadReplayDestination {
  def none(archRegWidth: Int = 6, physRegWidth: Int = 6): LoadReplayDestination = {
    val dst = Wire(new LoadReplayDestination(archRegWidth, physRegWidth))
    dst.valid := false.B
    dst.kind := DestinationKind.None
    dst.archTag := 0.U
    dst.relTag := 0.U
    dst.physTag := 0.U
    dst.oldPhysTag := 0.U
    dst
  }
}
