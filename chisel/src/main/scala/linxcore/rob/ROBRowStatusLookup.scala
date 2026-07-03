package linxcore.rob

import chisel3._
import chisel3.util.log2Ceil

class ROBRowStatusLookupResult(val entries: Int) extends Bundle {
  require(entries > 1, "entries must be greater than one")
  require((entries & (entries - 1)) == 0, "entries must be a power of two")

  val queryValid = Bool()
  val rowValid = Bool()
  val ridMatch = Bool()
  val status = ROBEntryStatus()
  val needFlush = Bool()
  val blockedByInvalidRid = Bool()
  val blockedByFree = Bool()
  val blockedByStaleRid = Bool()
}

class ROBRowStatusLookupIO(val entries: Int) extends Bundle {
  require(entries > 1, "entries must be greater than one")
  require((entries & (entries - 1)) == 0, "entries must be a power of two")

  val queryValid = Input(Bool())
  val queryRid = Input(new ROBID(entries))
  val rowValidMask = Input(UInt(entries.W))
  val rowRid = Input(Vec(entries, new ROBID(entries)))
  val rowStatus = Input(Vec(entries, ROBEntryStatus()))

  val result = Output(new ROBRowStatusLookupResult(entries))
}

class ROBRowStatusLookup(val entries: Int = 16) extends Module {
  require(entries > 1, "entries must be greater than one")
  require((entries & (entries - 1)) == 0, "entries must be a power of two")

  private val ptrWidth = log2Ceil(entries)

  val io = IO(new ROBRowStatusLookupIO(entries))

  val index = io.queryRid.value(ptrWidth - 1, 0)
  val slotOccupied = io.rowValidMask(index)
  val slotRidMatch = slotOccupied && ROBID.equal(io.rowRid(index), io.queryRid)
  val rowValid = io.queryValid && io.queryRid.valid && slotRidMatch
  val selectedStatus = Mux(rowValid, io.rowStatus(index), ROBEntryStatus.Free)

  io.result.queryValid := io.queryValid
  io.result.rowValid := rowValid
  io.result.ridMatch := io.queryValid && io.queryRid.valid && slotRidMatch
  io.result.status := selectedStatus
  io.result.needFlush := rowValid && selectedStatus === ROBEntryStatus.NeedFlush
  io.result.blockedByInvalidRid := io.queryValid && !io.queryRid.valid
  io.result.blockedByFree := io.queryValid && io.queryRid.valid && !slotOccupied
  io.result.blockedByStaleRid := io.queryValid && io.queryRid.valid && slotOccupied && !slotRidMatch
}
