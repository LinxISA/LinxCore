package linxcore.ooo

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class OooIexPhysicalProfileSpec extends AnyFunSuite {
  import OooIexDomainCapability._

  test("defines complete disjoint residency and the required capabilities") {
    val profile = OooIexLinxPhysicalProfile()
    val p = profile.params
    val allBanks = (BigInt(1) << p.iqBankCount) - 1
    val ownerNames = Seq("alu0", "alu1", "alu2", "alu3", "alu4", "alu5",
      "agu0", "agu1", "agu2", "bru0", "bru1", "fsu0")
    val pickerNames = Seq("alu0", "alu1", "alu2", "alu3", "alu4", "alu5",
      "agu0-lda", "agu0-sta", "agu1-lda", "agu1-sta", "agu2-lda",
      "bru0", "bru1", "fsu0")

    assert(profile.residencyOwners.map(_.name) == ownerNames)
    assert(profile.pickerFunctions.map(_.name) == pickerNames)
    assert(profile.executionLanes.map(_.name) == pickerNames)
    assert(profile.pickerFunctions.map(_.releasePort) == pickerNames.indices)
    assert(profile.residencyOwners.length == 12)
    assert(p.iexIssueDomainCount == 14)
    assert(p.iexReleaseWidth == 14)

    profile.dispatchableClasses.foreach { classIndex =>
      val masks = profile.residencyOwners.map(
        _.classBankEnables(classIndex))
      assert(masks.reduce(_ | _) == allBanks)
      for (left <- masks.indices; right <- left + 1 until masks.length) {
        assert((masks(left) & masks(right)) == 0)
      }
    }
    profile.fastResolvedClasses.foreach { classIndex =>
      assert(profile.residencyOwners.forall(
        _.classBankEnables(classIndex) == 0))
    }

    assert(profile.owner("alu0").hasCapability(StoreData))
    assert(profile.owner("alu3").hasCapability(StoreData))
    assert(profile.owner("alu2").hasCapability(MultiCycleAlu))
    assert(profile.owner("alu2").hasCapability(PointerAuth))
    assert(profile.owner("alu5").hasCapability(System))
    assert(!profile.owner("alu0").hasCapability(MultiCycleAlu))
    assert(profile.owner("agu0").hasCapability(StoreAddress))
    assert(profile.owner("agu1").hasCapability(StoreAddress))
    assert(!profile.owner("agu2").hasCapability(StoreAddress))
    assert(profile.owner("fsu0").hasCapability(FloatingVector))
    assert(profile.owner("fsu0").hasCapability(EngineCommand))

    assert(profile.pickersFor("agu0").map(_.name) ==
      Seq("agu0-lda", "agu0-sta"))
    assert(profile.pickersFor("agu1").map(_.name) ==
      Seq("agu1-lda", "agu1-sta"))
    assert(profile.pickersFor("agu2").map(_.name) == Seq("agu2-lda"))
    assert(profile.picker("agu0-lda").capabilities == mask(LoadAddress))
    assert(profile.picker("agu0-sta").capabilities == mask(StoreAddress))
    assert((profile.picker("agu0-lda").classBankEnables(
      OooDispatchClass.Agu - 1) & profile.picker("agu0-sta")
      .classBankEnables(OooDispatchClass.Agu - 1)) != 0)
    assert((profile.picker("agu0-lda").capabilities &
      profile.picker("agu0-sta").capabilities) == 0)
    assert(profile.pickerFunctions.forall(picker =>
      profile.lane(picker.executionLane).capabilities == picker.capabilities))
    val sharedResources = OooIexLinxPhysicalProfile.sharedReadResources(profile)
    assert(sharedResources.map(_.name) ==
      Seq("divide", "pointer-auth", "system"))
    assert(sharedResources.map(_.capability) ==
      Seq(MultiCycleAlu, PointerAuth, System))
    assert(sharedResources.forall(_.pickerFunctions == Seq(2, 5)))

    val topology = profile.capabilityTopology.classBankCapabilities
    val aguClass = OooDispatchClass.Agu - 1
    assert(topology(aguClass)(2) == mask(LoadAddress))
    assert((topology(aguClass)(0) & mask(StoreAddress)) != 0)
    val aluClass = OooDispatchClass.Alu - 1
    assert(topology(aluClass)(2) ==
      mask(SimpleAlu, MultiCycleAlu, System, PointerAuth))
    assert(topology(aluClass)(0) == mask(SimpleAlu, StoreData))

    assert(profile.owner("alu0").classBankEnables(
      OooDispatchClass.Alu - 1) == BigInt("09", 16))
    assert(profile.owner("alu0").classBankEnables(
      OooDispatchClass.Std - 1) == BigInt("0f", 16))
    assert(profile.owner("alu3").classBankEnables(
      OooDispatchClass.Alu - 1) == BigInt("90", 16))
    assert(profile.owner("alu3").classBankEnables(
      OooDispatchClass.Std - 1) == BigInt("f0", 16))
  }

  test("rejects incomplete class coverage and undeclared capability bits") {
    val profile = OooIexLinxPhysicalProfile()
    val aluClass = OooDispatchClass.Alu - 1
    val brokenOwners = profile.residencyOwners.updated(0,
      profile.owner("alu0").copy(
        classBankEnables = profile.owner("alu0").classBankEnables.updated(
          aluClass, BigInt(0))))

    assertThrows[IllegalArgumentException] {
      profile.copy(name = "broken-coverage", residencyOwners = brokenOwners)
    }
    assertThrows[IllegalArgumentException] {
      profile.copy(name = "broken-capability",
        residencyOwners = profile.residencyOwners.updated(0,
          profile.owner("alu0").copy(capabilities = BigInt(1) << Count)))
    }
    assertThrows[IllegalArgumentException] {
      profile.copy(name = "unknown-owner",
        pickerFunctions = profile.pickerFunctions.updated(0,
          profile.picker("alu0").copy(residencyOwner = "missing")))
    }
    val aguClass = OooDispatchClass.Agu - 1
    val partialSta = profile.picker("agu0-sta")
    assertThrows[IllegalArgumentException] {
      profile.copy(name = "partial-capability-projection",
        pickerFunctions = profile.pickerFunctions.updated(7,
          partialSta.copy(classBankEnables = partialSta.classBankEnables
            .updated(aguClass,
              partialSta.classBankEnables(aguClass) & ~BigInt(1)))))
    }
  }

  test("elaborates the fourteen-picker transfer and shared-resource topology") {
    val profile = OooIexLinxPhysicalProfile()
    val systemVerilog = ChiselStage.emitSystemVerilog(
      new OooIexLinxE1TransferFabric(profile = profile),
      firtoolOpts = Array("--disable-all-randomization"))
    assert(systemVerilog.contains("module OooIexLinxE1TransferFabric"))
    assert(systemVerilog.contains("pickBankEnables_13_7"))

    val sharedSystemVerilog = ChiselStage.emitSystemVerilog(
      new OooIexSharedResourceArbiter(profile.params,
        OooIexLinxPhysicalProfile.sharedReadResources(profile)),
      firtoolOpts = Array("--disable-all-randomization"))
    assert(sharedSystemVerilog.contains("module OooIexSharedResourceArbiter"))
    assert(sharedSystemVerilog.contains("io_conflicted"))
  }
}
