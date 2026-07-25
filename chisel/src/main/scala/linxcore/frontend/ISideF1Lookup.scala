package linxcore.frontend

import chisel3._
import chisel3.util.Decoupled
import linxcore.common.InterfaceParams

class ISideF1LookupIO(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val in = Flipped(Decoupled(new ISideFetchRequest(p, lineBytes)))
  val itlbRequest = Decoupled(new ISideFetchRequest(p, lineBytes))
  val l1iRequest = Decoupled(new ISideFetchRequest(p, lineBytes))

  val parallelLaunch = Output(Bool())
  val itlbLaunch = Output(Bool())
  val l1iLaunch = Output(Bool())
}

class ISideF1Lookup(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Module {
  val io = IO(new ISideF1LookupIO(p, lineBytes))

  val bothReady = io.itlbRequest.ready && io.l1iRequest.ready
  val launch = io.in.valid && bothReady

  io.in.ready := bothReady
  io.itlbRequest.valid := launch
  io.itlbRequest.bits := io.in.bits
  io.l1iRequest.valid := launch
  io.l1iRequest.bits := io.in.bits

  io.parallelLaunch := launch
  io.itlbLaunch := io.itlbRequest.valid && io.itlbRequest.ready
  io.l1iLaunch := io.l1iRequest.valid && io.l1iRequest.ready

  when(io.itlbLaunch || io.l1iLaunch) {
    assert(io.itlbLaunch && io.l1iLaunch, "I-F1 must launch ITLB and L1I in the same cycle")
  }
}
