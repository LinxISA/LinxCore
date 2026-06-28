package linxcore.common

import chisel3._

import linxcore.rob.ROBID

class TULinkFlushSequenceSource(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val stidWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val bid = new ROBID(p.robEntries)
  val rid = new ROBID(p.robEntries)
  val stid = UInt(stidWidth.W)
  val tSeq = new ROBID(mapQDepth)
  val uSeq = new ROBID(mapQDepth)
  val dstValid = Bool()
  val dstKind = DestinationKind()
}
