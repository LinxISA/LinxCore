package linxcore.lsu

import chisel3._

object SCBEntryState extends ChiselEnum {
  val Empty, Valid, Lookup, Miss = Value

  def canAcceptStore(state: Type): Bool = state === Empty || state === Valid

  def canIssueLookup(state: Type): Bool = state === Valid
}
