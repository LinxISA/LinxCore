package linxcore.top

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import linxcore.params.{NaturalPlatformParams, ParamProfiles,
  SimulationParamProfiles}

object EmitTOP extends App {
  private val width = sys.env.get("LINX_TOP_WIDTH").map(_.toInt).getOrElse(4)
  private val resetVector = sys.env.get("LINX_TOP_RESET_PC")
    .map(value => BigInt(value.stripPrefix("0x"), 16))
  private val base = if (sys.env.get("LINX_TOP_SIMULATION_PROFILE").contains("1"))
    NaturalPlatformParams(SimulationParamProfiles.forWidth(width))
  else ParamProfiles.forWidth(width)
  private val p = base.copy(ifu = base.ifu.copy(
    resetVector = resetVector.getOrElse(base.ifu.resetVector)))

  sys.env.get("LINX_TOP_PROFILE_METADATA").foreach { output =>
    val metadata = Seq(
      s"stidCount=${p.ooo.stidCount}",
      s"gprArchRegs=${p.ooo.gprArchRegs}",
      "spAtag=1",
      s"traceWidth=${p.dtu.traceWidth}",
      s"retireWidth=${p.ooo.retireWidth}",
      s"systemIssueLanes=${p.iex.systemMulticycleQueues}",
      s"loadLanes=${p.lsu.loadPipes}",
      s"storeLanes=${p.lsu.storePipes}").mkString("\n") + "\n"
    Files.write(Paths.get(output), metadata.getBytes(StandardCharsets.UTF_8))
  }

  circt.stage.ChiselStage.emitSystemVerilogFile(
    new TOP(p),
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
