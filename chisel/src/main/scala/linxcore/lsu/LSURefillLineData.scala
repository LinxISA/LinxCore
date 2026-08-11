package linxcore.lsu

import chisel3._
import linxcore.params.CoreParams
import linxcore.top.interface.MemoryResponseTxn

class LSURefillLineDataIO(val p: CoreParams) extends Bundle {
  val response = Input(new MemoryResponseTxn(p))
  val lineData = Output(UInt((p.lsu.lineBytes * 8).W))
}

/** Public lower-memory responses carry the authoritative complete line. */
class LSURefillLineData(val p: CoreParams) extends Module {
  val io = IO(new LSURefillLineDataIO(p))
  io.lineData := io.response.lineData
}
