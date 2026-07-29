package linxcore.ooo

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class OooIexPhysicalProfileSpec extends AnyFunSuite {
  import OooIexDomainCapability._

  test("defines complete disjoint residency and the required capabilities") {
    val profile = OooIexLinxPhysicalProfile()
    val p = profile.params
    val allBanks = (BigInt(1) << p.iqBankCount) - 1
    val names = Seq("alu0", "alu1", "alu2", "alu3", "alu4", "alu5",
      "agu0", "agu1", "agu2", "bru0", "bru1", "fsu0")

    assert(profile.domains.map(_.name) == names)
    assert(profile.domains.map(_.releasePort) == names.indices)
    assert(p.iexIssueDomainCount == 12)
    assert(p.iexReleaseWidth == 12)

    profile.dispatchableClasses.foreach { classIndex =>
      val masks = profile.domains.map(_.classBankEnables(classIndex))
      assert(masks.reduce(_ | _) == allBanks)
      for (left <- masks.indices; right <- left + 1 until masks.length) {
        assert((masks(left) & masks(right)) == 0)
      }
    }
    profile.fastResolvedClasses.foreach { classIndex =>
      assert(profile.domains.forall(_.classBankEnables(classIndex) == 0))
    }

    assert(profile.domain("alu0").hasCapability(StoreData))
    assert(profile.domain("alu3").hasCapability(StoreData))
    assert(profile.domain("alu2").hasCapability(MultiCycleAlu))
    assert(profile.domain("alu5").hasCapability(System))
    assert(!profile.domain("alu0").hasCapability(MultiCycleAlu))
    assert(profile.domain("agu0").hasCapability(StoreAddress))
    assert(profile.domain("agu1").hasCapability(StoreAddress))
    assert(!profile.domain("agu2").hasCapability(StoreAddress))
    assert(profile.domain("fsu0").hasCapability(FloatingVector))
    assert(profile.domain("fsu0").hasCapability(EngineCommand))

    assert(profile.domain("alu0").classBankEnables(
      OooDispatchClass.Alu - 1) == BigInt("09", 16))
    assert(profile.domain("alu0").classBankEnables(
      OooDispatchClass.Std - 1) == BigInt("0f", 16))
    assert(profile.domain("alu3").classBankEnables(
      OooDispatchClass.Alu - 1) == BigInt("90", 16))
    assert(profile.domain("alu3").classBankEnables(
      OooDispatchClass.Std - 1) == BigInt("f0", 16))
  }

  test("rejects incomplete class coverage and undeclared capability bits") {
    val profile = OooIexLinxPhysicalProfile()
    val aluClass = OooDispatchClass.Alu - 1
    val brokenDomains = profile.domains.updated(0,
      profile.domain("alu0").copy(
        classBankEnables = profile.domain("alu0").classBankEnables.updated(
          aluClass, BigInt(0))))

    assertThrows[IllegalArgumentException] {
      OooIexPhysicalProfile("broken-coverage", profile.params, brokenDomains,
        profile.dispatchableClasses, profile.fastResolvedClasses)
    }
    assertThrows[IllegalArgumentException] {
      OooIexPhysicalProfile("broken-capability", profile.params,
        profile.domains.updated(0, profile.domain("alu0").copy(
          capabilities = BigInt(1) << Count)),
        profile.dispatchableClasses, profile.fastResolvedClasses)
    }
  }

  test("elaborates the twelve-domain retained transfer topology") {
    val profile = OooIexLinxPhysicalProfile()
    val systemVerilog = ChiselStage.emitSystemVerilog(
      new OooIexE1TransferFabric(profile.params, profile.transferConfigs),
      firtoolOpts = Array("--disable-all-randomization"))
    assert(systemVerilog.contains("module OooIexE1TransferFabric"))
    assert(systemVerilog.contains("pickBankEnables_11_7"))
  }
}
