package linxcore.top

import linxcore.params.{ParamProfiles, SimulationParamProfiles}

object EmitTOP extends App {
  private val width = sys.env.get("LINX_TOP_WIDTH").map(_.toInt).getOrElse(4)
  private val resetVector = sys.env.get("LINX_TOP_RESET_PC")
    .map(value => BigInt(value.stripPrefix("0x"), 16))
  private val base = if (sys.env.get("LINX_TOP_SIMULATION_PROFILE").contains("1"))
    SimulationParamProfiles.forWidth(width)
  else ParamProfiles.forWidth(width)
  private val p = base.copy(ifu = base.ifu.copy(
    resetVector = resetVector.getOrElse(base.ifu.resetVector)))

  circt.stage.ChiselStage.emitSystemVerilogFile(
    new TOP(p),
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
