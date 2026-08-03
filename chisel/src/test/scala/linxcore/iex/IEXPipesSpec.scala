package linxcore.iex

import circt.stage.ChiselStage
import linxcore.ooo.{OooIexDomainCapability, OooIexPhysicalProfile}
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class IEXPipesSpec extends AnyFunSuite {
  test("W4 public IEX exposes the frozen physical execution topology") {
    val p = SimulationParamProfiles.W4
    val profile = OooIexPhysicalProfile.fromCoreParams(p)
    def lanes(capability: Int): Int =
      profile.pickerFunctions.count(_.hasCapability(capability))
    assert(lanes(OooIexDomainCapability.SimpleAlu) == 2)
    assert(lanes(OooIexDomainCapability.Branch) == 1)
    assert(lanes(OooIexDomainCapability.LoadAddress) == 2)
    assert(lanes(OooIexDomainCapability.StoreAddress) == 2)
    assert(lanes(OooIexDomainCapability.StoreData) == 2)
    assert(p.iex.systemMulticycleQueues == 1)
    assert(p.iex.cmdIssueQueues == 1)
    assert(p.lsu.loadPipes == 2)
    assert(p.lsu.storePipes == 2)
    val sv = ChiselStage.emitSystemVerilog(new IEX(p))
    assert(sv.contains("module OooIexAluPipeline"))
    assert(sv.contains("module OooIexBruPipeline"))
    assert(sv.contains("module OooIexAguPipeline"))
    assert(sv.contains("OooIexSystemCmdTerminal"))
  }
}
