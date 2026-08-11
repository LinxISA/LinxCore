package linxcore.top

import _root_.circt.stage.ChiselStage
import java.nio.file.{Files, Paths}
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class TOPSpec extends AnyFunSuite {
  private val repoRoot = Paths.get("..").toAbsolutePath.normalize

  test("W4 TOP instantiates each public box exactly once and owns no state") {
    val sv = ChiselStage.emitSystemVerilog(new TOP(SimulationParamProfiles.W4))

    Seq("IFU", "CTU", "OOO", "IEX", "LSU", "DTU").foreach { box =>
      assert((s"(?m)^  ${box} ${box.toLowerCase} \\(".r findAllIn sv).size == 1, box)
    }
    assert(!sv.contains("iex_to_ifu"))
    assert(!sv.contains("lsu_to_ifu"))

    val source = Files.readString(
      repoRoot.resolve("chisel/src/main/scala/linxcore/top/TOP.scala"))
    val stateTokens = Seq("Reg(", "RegInit(", "RegNext(", "Mem(",
      "SyncReadMem(", "new Queue(")
    stateTokens.foreach(token => assert(!source.contains(token), token))
  }

  test("CMD remains externally backpressured and trace observation is lossy") {
    val source = Files.readString(
      repoRoot.resolve("chisel/src/main/scala/linxcore/top/TOP.scala"))

    assert(source.contains("io.cmdIssue <> iex.io.cmdIssue"))
    assert(!source.contains("iex.io.cmdIssue.ready := true.B"))
    assert(source.contains("new TracePrefixPacker"))
    assert(!source.contains("dtu.io.traceIn.ready"))
  }
}
