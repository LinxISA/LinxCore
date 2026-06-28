package linxcore.rob

import chisel3._

object ROBEntryStatus extends ChiselEnum {
  val Free, Allocated, Renamed, Issued, Completed, Retired, Fault, NeedFlush = Value

  def occupiesRob(status: Type): Bool = status =/= Free

  def osdActive(status: Type): Bool =
    status === Allocated || status === Renamed || status === Issued ||
      status === Completed || status === NeedFlush

  def canCommit(status: Type): Bool = status === Completed

  def canDealloc(status: Type): Bool = status === Retired

  def flushClears(status: Type): Bool = osdActive(status)
}
