package linxcore.rob

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams

object EmitReducedCommitROB extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new ReducedCommitROB(
      entries = 8,
      traceParams = CommitTraceParams(commitWidth = 2, robValueWidth = 3)
    ),
    args = Array("--target-dir", "../generated/chisel-verilog/reduced-rob"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
