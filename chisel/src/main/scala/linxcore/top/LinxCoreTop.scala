package linxcore.top

import chisel3._

class LinxCoreTopIO extends Bundle {
  val idle = Output(Bool())
}

class LinxCoreTop extends Module {
  val io = IO(new LinxCoreTopIO)

  io.idle := true.B
}

object Elaborate extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new LinxCoreTop,
    args = Array("--target-dir", "../generated/chisel-verilog"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
