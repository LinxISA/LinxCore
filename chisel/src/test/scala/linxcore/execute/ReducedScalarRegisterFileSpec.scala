package linxcore.execute

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ReducedScalarRegisterFileSpec extends AnyFunSuite {
  test("interface follows the model-derived scalar physical GPR table shape") {
    val p = InterfaceParams()
    val io = new ReducedScalarRegisterFileIO(p, archRegs = 24, physRegs = 64)

    assert(io.readValid.length == 3)
    assert(io.readTags.head.getWidth == 6)
    assert(io.readData.head.getWidth == 64)
    assert(io.initArchTag.getWidth == 6)
    assert(io.clearTag.getWidth == 6)
    assert(io.writeTag.getWidth == 6)
    assert(io.readyMask.getWidth == 64)
  }

  test("ReducedScalarRegisterFile elaborates init, clear, write, and ready observability") {
    val sv = ChiselStage.emitSystemVerilog(
      new ReducedScalarRegisterFile(InterfaceParams(), archRegs = 24, physRegs = 64)
    )

    assert(sv.contains("module ReducedScalarRegisterFile"))
    assert(sv.contains("io_initValid"))
    assert(sv.contains("io_clearValid"))
    assert(sv.contains("io_writeValid"))
    assert(sv.contains("io_readReady"))
    assert(sv.contains("io_readyMask"))
    assert(sv.contains("io_stateError"))
  }
}
