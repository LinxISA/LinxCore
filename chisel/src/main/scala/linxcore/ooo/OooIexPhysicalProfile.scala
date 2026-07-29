package linxcore.ooo

import chisel3._

/** Stable execution capabilities used to separate IQ residency from the
  * physical operation that a picker/pipe can accept.
  *
  * A dispatch class is intentionally broader than a capability.  In
  * particular, the AGU class contains load- and store-address recipes, while
  * only two of the three address domains accept store-address work.
  */
object OooIexDomainCapability {
  val SimpleAlu = 0
  val MultiCycleAlu = 1
  val StoreData = 2
  val System = 3
  val Branch = 4
  val LoadAddress = 5
  val StoreAddress = 6
  val FloatingVector = 7
  val EngineCommand = 8
  val PointerAuth = 9
  val Count = 10

  def mask(values: Int*): BigInt =
    values.foldLeft(BigInt(0))((result, value) => result | (BigInt(1) << value))
  val ValidMask: BigInt = (BigInt(1) << Count) - 1

  def covers(available: UInt, required: UInt): Bool =
    required.orR && (available & required) === required
}

/** Per-class/per-bank capability admission used by D3 reservation. */
final case class OooIexCapabilityTopology(
    classBankCapabilities: Seq[Seq[BigInt]]) {
  def validate(p: OooParams): Unit = {
    require(classBankCapabilities.length == p.iqClassCount,
      "IEX capability topology must define every IQ class")
    classBankCapabilities.zipWithIndex.foreach { case (banks, classIndex) =>
      require(banks.length == p.iqBankCount,
        s"IEX capability topology class $classIndex must define every bank")
      require(banks.forall(mask => mask >= 0 &&
        (mask & ~OooIexDomainCapability.ValidMask) == 0),
        s"IEX capability topology class $classIndex contains an invalid mask")
    }
  }
}

object OooIexCapabilityTopology {
  def permissive(p: OooParams): OooIexCapabilityTopology =
    OooIexCapabilityTopology(Seq.fill(p.iqClassCount)(
      Seq.fill(p.iqBankCount)(OooIexDomainCapability.ValidMask)))

  def fromDomains(
      p: OooParams,
      domains: Seq[OooIexIssueDomainConfig]): OooIexCapabilityTopology = {
    OooIexIssueDomainConfig.validate(p, domains)
    val matrix = Seq.tabulate(p.iqClassCount, p.iqBankCount) {
      case (classIndex, bank) =>
        domains.filter(domain =>
          (domain.classBankEnables(classIndex) & (BigInt(1) << bank)) != 0)
          .foldLeft(BigInt(0))(_ | _.capabilities)
    }
    val topology = OooIexCapabilityTopology(matrix)
    topology.validate(p)
    topology
  }
}

/** One canonical class/bank residency owner. */
final case class OooIexResidencyOwner(
    name: String,
    classBankEnables: Seq[BigInt],
    capabilities: BigInt) {
  def ownsClass(classIndex: Int): Boolean =
    classIndex >= 0 && classIndex < classBankEnables.length &&
      classBankEnables(classIndex) != 0
  def hasCapability(capability: Int): Boolean =
    (capabilities & (BigInt(1) << capability)) != 0
}

/** One execution destination independently named from selection topology. */
final case class OooIexExecutionLane(
    name: String,
    capabilities: BigInt)

/** One oldest-ready selection function over a canonical residency owner. */
final case class OooIexPickerFunction(
    name: String,
    residencyOwner: String,
    executionLane: String,
    classBankEnables: Seq[BigInt],
    capabilities: BigInt,
    releasePort: Int) {
  def transferConfig: OooIexIssueDomainConfig =
    OooIexIssueDomainConfig(classBankEnables, releasePort, name, capabilities)
}

/** Elaborated physical topology shared by dispatch, pick, RF arbitration,
  * execution-lane routing, and the retained I2/E1 transfer boundary.
  */
final case class OooIexPhysicalProfile(
    name: String,
    params: OooParams,
    residencyOwners: Seq[OooIexResidencyOwner],
    pickerFunctions: Seq[OooIexPickerFunction],
    executionLanes: Seq[OooIexExecutionLane],
    dispatchableClasses: Set[Int],
    fastResolvedClasses: Set[Int]) {
  private val allBanks = (BigInt(1) << params.iqBankCount) - 1

  require(name.nonEmpty, "physical IEX profile needs a stable name")
  require(residencyOwners.map(_.name).forall(_.nonEmpty) &&
    residencyOwners.map(_.name).distinct.length == residencyOwners.length,
    "IEX residency owners need nonempty unique names")
  require(residencyOwners.forall(owner =>
    owner.classBankEnables.length == params.iqClassCount &&
      owner.classBankEnables.exists(_ != 0) &&
      owner.classBankEnables.forall(mask => mask >= 0 && mask <= allBanks) &&
      owner.capabilities != 0 &&
      (owner.capabilities & ~OooIexDomainCapability.ValidMask) == 0),
    "IEX residency owners need valid projections and capabilities")
  require(pickerFunctions.map(_.name).forall(_.nonEmpty) &&
    pickerFunctions.map(_.name).distinct.length == pickerFunctions.length,
    "IEX picker functions need nonempty unique names")
  require(executionLanes.map(_.name).forall(_.nonEmpty) &&
    executionLanes.map(_.name).distinct.length == executionLanes.length &&
    executionLanes.forall(lane => lane.capabilities != 0 &&
      (lane.capabilities & ~OooIexDomainCapability.ValidMask) == 0),
    "IEX execution lanes need nonempty unique names and valid capabilities")
  require(dispatchableClasses.intersect(fastResolvedClasses).isEmpty,
    "a class cannot be both physically issued and fast-resolved")
  require((dispatchableClasses ++ fastResolvedClasses).forall(classIndex =>
    classIndex >= 0 && classIndex < params.iqClassCount),
    "physical IEX profile names an out-of-range dispatch class")

  OooIexIssueDomainConfig.validate(params,
    pickerFunctions.map(_.transferConfig))

  pickerFunctions.foreach { picker =>
    val owner = residencyOwners.find(_.name == picker.residencyOwner).getOrElse(
      throw new IllegalArgumentException(
        s"IEX picker ${picker.name} names an unknown residency owner"))
    val lane = executionLanes.find(_.name == picker.executionLane).getOrElse(
      throw new IllegalArgumentException(
        s"IEX picker ${picker.name} names an unknown execution lane"))
    require((picker.capabilities & ~owner.capabilities) == 0,
      s"IEX picker ${picker.name} exceeds its residency-owner capability")
    require((picker.capabilities & ~lane.capabilities) == 0,
      s"IEX picker ${picker.name} exceeds its execution-lane capability")
    require(picker.classBankEnables.zip(owner.classBankEnables).forall {
      case (pickerMask, ownerMask) => (pickerMask & ~ownerMask) == 0
    }, s"IEX picker ${picker.name} exceeds its residency-owner projection")
  }
  require(executionLanes.forall(lane =>
    pickerFunctions.exists(_.executionLane == lane.name)),
    "every IEX execution lane needs at least one picker function")

  for (left <- residencyOwners.indices;
       right <- left + 1 until residencyOwners.length) {
    require(residencyOwners(left).classBankEnables
      .zip(residencyOwners(right).classBankEnables)
      .forall { case (leftMask, rightMask) => (leftMask & rightMask) == 0 },
      s"IEX residency owners $left and $right overlap one class/bank")
  }

  dispatchableClasses.foreach { classIndex =>
    val coverage = residencyOwners.foldLeft(BigInt(0))(
      _ | _.classBankEnables(classIndex))
    require(coverage == allBanks,
      s"physical IEX class $classIndex must cover every IQ bank exactly once")
  }
  fastResolvedClasses.foreach { classIndex =>
    require(residencyOwners.forall(_.classBankEnables(classIndex) == 0),
      s"fast-resolved class $classIndex must not own physical IQ banks")
  }
  residencyOwners.foreach { owner =>
    val pickerCapabilities = pickerFunctions
      .filter(_.residencyOwner == owner.name)
      .foldLeft(BigInt(0))(_ | _.capabilities)
    require(pickerCapabilities == owner.capabilities,
      s"IEX owner ${owner.name} capabilities are not fully projected")
    for (classIndex <- 0 until params.iqClassCount) {
      val pickerCoverage = pickerFunctions
        .filter(_.residencyOwner == owner.name)
        .foldLeft(BigInt(0))(_ | _.classBankEnables(classIndex))
      require(pickerCoverage == owner.classBankEnables(classIndex),
        s"IEX owner ${owner.name} is not fully projected by its pickers")
      for (bank <- 0 until params.iqBankCount
           if (owner.classBankEnables(classIndex) &
             (BigInt(1) << bank)) != 0) {
        val bankCapabilities = pickerFunctions.filter(picker =>
          picker.residencyOwner == owner.name &&
            (picker.classBankEnables(classIndex) &
              (BigInt(1) << bank)) != 0)
          .foldLeft(BigInt(0))(_ | _.capabilities)
        require(bankCapabilities == owner.capabilities,
          s"IEX owner ${owner.name} class $classIndex bank $bank " +
            "does not expose every declared capability")
      }
    }
  }

  def transferConfigs: Seq[OooIexIssueDomainConfig] =
    pickerFunctions.map(_.transferConfig)
  def capabilityTopology: OooIexCapabilityTopology = {
    val topology = OooIexCapabilityTopology(Seq.tabulate(
      params.iqClassCount, params.iqBankCount) { case (classIndex, bank) =>
      residencyOwners.filter(owner =>
        (owner.classBankEnables(classIndex) & (BigInt(1) << bank)) != 0)
        .foldLeft(BigInt(0))(_ | _.capabilities)
    })
    topology.validate(params)
    topology
  }
  def owner(name: String): OooIexResidencyOwner =
    residencyOwners.find(_.name == name).getOrElse(
      throw new NoSuchElementException(s"unknown IEX residency owner $name"))
  def picker(name: String): OooIexPickerFunction =
    pickerFunctions.find(_.name == name).getOrElse(
      throw new NoSuchElementException(s"unknown IEX picker function $name"))
  def lane(name: String): OooIexExecutionLane =
    executionLanes.find(_.name == name).getOrElse(
      throw new NoSuchElementException(s"unknown IEX execution lane $name"))
  def ownersOf(classIndex: Int): Seq[OooIexResidencyOwner] =
    residencyOwners.filter(_.ownsClass(classIndex))
  def pickersFor(ownerName: String): Seq[OooIexPickerFunction] =
    pickerFunctions.filter(_.residencyOwner == ownerName)
  def pickerIndex(name: String): Int = {
    val index = pickerFunctions.indexWhere(_.name == name)
    if (index >= 0) index
    else throw new NoSuchElementException(s"unknown IEX picker function $name")
  }
}

/** Formal Linx scalar/control issue profile.
  *
  * Six ALU, three AGU, and two BRU residency owners are internal. One external
  * FSU owner retains both floating/vector work and engine-command work.
  * Logical class-bank residency is complete and disjoint. AGU0/1 expand to
  * capability-disjoint LDA/STA pickers without duplicating those rows.
  */
object OooIexLinxPhysicalProfile {
  import OooIexDomainCapability._

  val ResidencyOwnerCount = 12
  val PickerFunctionCount = 14
  val ExecutionLaneCount = 14
  val ReleaseWidth = 14

  private val aluClass = OooDispatchClass.Alu - 1
  private val bruClass = OooDispatchClass.Bru - 1
  private val aguClass = OooDispatchClass.Agu - 1
  private val stdClass = OooDispatchClass.Std - 1
  private val fsuClass = OooDispatchClass.Fsu - 1
  private val sysClass = OooDispatchClass.Sys - 1
  private val cmdClass = OooDispatchClass.Cmd - 1
  private val boundaryClass = OooDispatchClass.Boundary - 1

  def params(base: OooParams = OooParams()): OooParams = {
    require(base.iqBankCount >= 8 && base.iqBankCount % 2 == 0,
      "formal Linx IEX profile needs two clusters with at least four IQ banks each")
    base.copy(iexIssueDomainCount = PickerFunctionCount,
      iexReleaseWidth = ReleaseWidth)
  }

  private def classMasks(p: OooParams, entries: (Int, BigInt)*): Seq[BigInt] = {
    val result = Array.fill[BigInt](p.iqClassCount)(BigInt(0))
    entries.foreach { case (classIndex, bankMask) =>
      require(classIndex >= 0 && classIndex < p.iqClassCount)
      result(classIndex) = bankMask
    }
    result.toSeq
  }

  private def moduloMask(bankCount: Int, residue: Int, modulus: Int): BigInt =
    (0 until bankCount).filter(_ % modulus == residue).foldLeft(BigInt(0))(
      (mask, bank) => mask | (BigInt(1) << bank))

  private def clusterModuloMask(
      bankCount: Int,
      cluster: Int,
      residue: Int): BigInt = {
    val clusterWidth = bankCount / 2
    val base = cluster * clusterWidth
    (0 until clusterWidth).filter(_ % 3 == residue).foldLeft(BigInt(0))(
      (mask, localBank) => mask | (BigInt(1) << (base + localBank)))
  }

  def sharedReadResources(
      profile: OooIexPhysicalProfile): Seq[OooIexSharedResourceConfig] = {
    val symmetricAluPickers = Seq(
      profile.pickerIndex("alu2"), profile.pickerIndex("alu5"))
    Seq(
      OooIexSharedResourceConfig("divide", MultiCycleAlu,
        symmetricAluPickers),
      OooIexSharedResourceConfig("pointer-auth", PointerAuth,
        symmetricAluPickers),
      OooIexSharedResourceConfig("system", System,
        symmetricAluPickers))
  }

  def apply(base: OooParams = OooParams()): OooIexPhysicalProfile = {
    val p = params(base)
    val half = p.iqBankCount / 2
    val lowerBanks = (BigInt(1) << half) - 1
    val allBanks = (BigInt(1) << p.iqBankCount) - 1
    val upperBanks = allBanks ^ lowerBanks

    val owners = Seq(
      OooIexResidencyOwner("alu0",
        classMasks(p,
          aluClass -> clusterModuloMask(p.iqBankCount, 0, 0),
          stdClass -> lowerBanks),
        mask(SimpleAlu, StoreData)),
      OooIexResidencyOwner("alu1",
        classMasks(p,
          aluClass -> clusterModuloMask(p.iqBankCount, 0, 1)),
        mask(SimpleAlu)),
      OooIexResidencyOwner("alu2",
        classMasks(p,
          aluClass -> clusterModuloMask(p.iqBankCount, 0, 2),
          sysClass -> lowerBanks),
        mask(SimpleAlu, MultiCycleAlu, System, PointerAuth)),
      OooIexResidencyOwner("alu3",
        classMasks(p,
          aluClass -> clusterModuloMask(p.iqBankCount, 1, 0),
          stdClass -> upperBanks),
        mask(SimpleAlu, StoreData)),
      OooIexResidencyOwner("alu4",
        classMasks(p,
          aluClass -> clusterModuloMask(p.iqBankCount, 1, 1)),
        mask(SimpleAlu)),
      OooIexResidencyOwner("alu5",
        classMasks(p,
          aluClass -> clusterModuloMask(p.iqBankCount, 1, 2),
          sysClass -> upperBanks),
        mask(SimpleAlu, MultiCycleAlu, System, PointerAuth)),
      OooIexResidencyOwner("agu0",
        classMasks(p, aguClass -> moduloMask(p.iqBankCount, 0, 3)),
        mask(LoadAddress, StoreAddress)),
      OooIexResidencyOwner("agu1",
        classMasks(p, aguClass -> moduloMask(p.iqBankCount, 1, 3)),
        mask(LoadAddress, StoreAddress)),
      OooIexResidencyOwner("agu2",
        classMasks(p, aguClass -> moduloMask(p.iqBankCount, 2, 3)),
        mask(LoadAddress)),
      OooIexResidencyOwner("bru0",
        classMasks(p, bruClass -> moduloMask(p.iqBankCount, 0, 2)),
        mask(Branch)),
      OooIexResidencyOwner("bru1",
        classMasks(p, bruClass -> moduloMask(p.iqBankCount, 1, 2)),
        mask(Branch)),
      OooIexResidencyOwner("fsu0",
        classMasks(p, fsuClass -> allBanks, cmdClass -> allBanks),
        mask(FloatingVector, EngineCommand)))

    def picker(
        name: String,
        ownerName: String,
        capabilities: BigInt,
        releasePort: Int): OooIexPickerFunction =
      OooIexPickerFunction(name, ownerName, name,
        owners.find(_.name == ownerName).get.classBankEnables,
        capabilities, releasePort)

    val pickers = Seq(
      picker("alu0", "alu0", mask(SimpleAlu, StoreData), 0),
      picker("alu1", "alu1", mask(SimpleAlu), 1),
      picker("alu2", "alu2",
        mask(SimpleAlu, MultiCycleAlu, System, PointerAuth), 2),
      picker("alu3", "alu3", mask(SimpleAlu, StoreData), 3),
      picker("alu4", "alu4", mask(SimpleAlu), 4),
      picker("alu5", "alu5",
        mask(SimpleAlu, MultiCycleAlu, System, PointerAuth), 5),
      picker("agu0-lda", "agu0", mask(LoadAddress), 6),
      picker("agu0-sta", "agu0", mask(StoreAddress), 7),
      picker("agu1-lda", "agu1", mask(LoadAddress), 8),
      picker("agu1-sta", "agu1", mask(StoreAddress), 9),
      picker("agu2-lda", "agu2", mask(LoadAddress), 10),
      picker("bru0", "bru0", mask(Branch), 11),
      picker("bru1", "bru1", mask(Branch), 12),
      picker("fsu0", "fsu0", mask(FloatingVector, EngineCommand), 13))
    val lanes = pickers.map(picker =>
      OooIexExecutionLane(picker.executionLane, picker.capabilities))
    require(owners.length == ResidencyOwnerCount &&
      pickers.length == PickerFunctionCount &&
      lanes.length == ExecutionLaneCount,
      "formal Linx IEX topology counts must remain explicit")

    val profile = OooIexPhysicalProfile(
      name = "linx-scalar-control-v2",
      params = p,
      residencyOwners = owners,
      pickerFunctions = pickers,
      executionLanes = lanes,
      dispatchableClasses = Set(aluClass, bruClass, aguClass, stdClass,
        fsuClass, sysClass, cmdClass),
      fastResolvedClasses = Set(boundaryClass))

    require(profile.ownersOf(stdClass).map(_.name).toSet == Set("alu0", "alu3") &&
      profile.ownersOf(stdClass).forall(_.hasCapability(StoreData)),
      "STD residency must be restricted to ALU0/ALU3")
    require(profile.ownersOf(sysClass).map(_.name).toSet == Set("alu2", "alu5") &&
      profile.ownersOf(sysClass).forall(_.hasCapability(System)),
      "system residency must be restricted to ALU2/ALU5")
    require(profile.residencyOwners.filter(_.hasCapability(MultiCycleAlu))
      .map(_.name).toSet == Set("alu2", "alu5"),
      "multi-cycle ALU capability must be restricted to ALU2/ALU5")
    require(profile.residencyOwners.filter(_.hasCapability(PointerAuth))
      .map(_.name).toSet == Set("alu2", "alu5"),
      "pointer-authentication capability must be restricted to ALU2/ALU5")
    require(profile.ownersOf(aguClass).forall(_.hasCapability(LoadAddress)) &&
      profile.residencyOwners.filter(_.hasCapability(StoreAddress))
        .map(_.name).toSet ==
        Set("agu0", "agu1"),
      "all AGUs accept loads while only AGU0/AGU1 accept stores")
    require(profile.pickersFor("agu0").map(_.name) ==
      Seq("agu0-lda", "agu0-sta") &&
      profile.pickersFor("agu1").map(_.name) ==
        Seq("agu1-lda", "agu1-sta") &&
      profile.pickersFor("agu2").map(_.name) == Seq("agu2-lda"),
      "AGU0/1 need LDA+STA pickers while AGU2 is LDA-only")
    OooIexSharedResourceConfig.validate(p, sharedReadResources(profile))
    require(profile.ownersOf(fsuClass).map(_.name) == Seq("fsu0") &&
      profile.ownersOf(cmdClass).map(_.name) == Seq("fsu0"),
      "the external FSU domain owns FSU and engine-command residency")
    profile
  }
}
