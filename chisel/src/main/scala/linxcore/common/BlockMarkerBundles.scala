package linxcore.common

import chisel3._

import linxcore.rob.ROBID

class BlockMarkerRetireSource(
    val entries: Int,
    val blockBidWidth: Int = 64,
    val pcWidth: Int = 64,
    val insnWidth: Int = 64,
    val lenWidth: Int = 4,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val isBoundary = Bool()
  val isStop = Bool()
  val isLast = Bool()
  val bid = new ROBID(entries)
  val gid = new ROBID(entries)
  val rid = new ROBID(entries)
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val blockBidValid = Bool()
  val blockBid = UInt(blockBidWidth.W)
  val pc = UInt(pcWidth.W)
  val insn = UInt(insnWidth.W)
  val len = UInt(lenWidth.W)
  val boundaryKind = BoundaryKind()
  val boundaryTarget = UInt(pcWidth.W)
}
