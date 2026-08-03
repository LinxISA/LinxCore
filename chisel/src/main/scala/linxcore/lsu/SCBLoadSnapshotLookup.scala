package linxcore.lsu

import chisel3._
import chisel3.util.{Mux1H, PopCount}

class SCBLoadSnapshotLookupIO(
    val scbEntries: Int,
    val addrWidth: Int,
    val lineBytes: Int) extends Bundle {
  val rows = Input(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  val lineAddress = Input(UInt(addrWidth.W))
  val returned = Output(Bool())
  val validMask = Output(UInt(lineBytes.W))
  val data = Output(UInt((lineBytes * 8).W))
  val ambiguous = Output(Bool())
}

/** Combinational view of the retained SCB owner for one aligned load line.
  * Stale rows are ignored. Multiple live rows are rejected instead of
  * selecting an arbitrary snapshot.
  */
class SCBLoadSnapshotLookup(
    val scbEntries: Int,
    val addrWidth: Int,
    val lineBytes: Int) extends Module {
  require(scbEntries > 0)
  val io = IO(new SCBLoadSnapshotLookupIO(scbEntries, addrWidth, lineBytes))

  val matches = VecInit(io.rows.map(row => row.valid &&
    row.state === SCBEntryState.Valid && row.lineAddr === io.lineAddress))
  val unique = PopCount(matches) === 1.U
  io.ambiguous := PopCount(matches) > 1.U
  io.returned := !io.ambiguous
  io.validMask := Mux(unique, Mux1H(matches, io.rows.map(_.byteMask)), 0.U)
  io.data := Mux(unique, Mux1H(matches, io.rows.map(_.data)), 0.U)
}
