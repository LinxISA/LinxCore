package linxcore.ooo

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
  val Count = 9

  def mask(values: Int*): BigInt =
    values.foldLeft(BigInt(0))((result, value) => result | (BigInt(1) << value))
  val ValidMask: BigInt = (BigInt(1) << Count) - 1
}

/** One independently selected physical issue pipe and its IQ residency. */
final case class OooIexPhysicalDomain(
    name: String,
    classBankEnables: Seq[BigInt],
    capabilities: BigInt,
    releasePort: Int) {
  def ownsClass(classIndex: Int): Boolean =
    classIndex >= 0 && classIndex < classBankEnables.length &&
      classBankEnables(classIndex) != 0
  def hasCapability(capability: Int): Boolean =
    (capabilities & (BigInt(1) << capability)) != 0
  def transferConfig: OooIexIssueDomainConfig =
    OooIexIssueDomainConfig(classBankEnables, releasePort, name)
}

/** Elaborated physical topology shared by dispatch, pick, RF arbitration, and
  * the retained I2/E1 transfer boundary.
  */
final case class OooIexPhysicalProfile(
    name: String,
    params: OooParams,
    domains: Seq[OooIexPhysicalDomain],
    dispatchableClasses: Set[Int],
    fastResolvedClasses: Set[Int]) {
  private val allBanks = (BigInt(1) << params.iqBankCount) - 1

  require(name.nonEmpty, "physical IEX profile needs a stable name")
  require(domains.map(_.name).forall(_.nonEmpty) &&
    domains.map(_.name).distinct.length == domains.length,
    "physical IEX domains need nonempty unique names")
  require(domains.forall(domain => domain.capabilities != 0 &&
    (domain.capabilities & ~OooIexDomainCapability.ValidMask) == 0),
    "physical IEX domains need only declared nonempty capabilities")
  require(dispatchableClasses.intersect(fastResolvedClasses).isEmpty,
    "a class cannot be both physically issued and fast-resolved")
  require((dispatchableClasses ++ fastResolvedClasses).forall(classIndex =>
    classIndex >= 0 && classIndex < params.iqClassCount),
    "physical IEX profile names an out-of-range dispatch class")

  OooIexIssueDomainConfig.validate(params, domains.map(_.transferConfig))

  dispatchableClasses.foreach { classIndex =>
    val coverage = domains.foldLeft(BigInt(0))(
      _ | _.classBankEnables(classIndex))
    require(coverage == allBanks,
      s"physical IEX class $classIndex must cover every IQ bank exactly once")
  }
  fastResolvedClasses.foreach { classIndex =>
    require(domains.forall(_.classBankEnables(classIndex) == 0),
      s"fast-resolved class $classIndex must not own physical IQ banks")
  }

  def transferConfigs: Seq[OooIexIssueDomainConfig] =
    domains.map(_.transferConfig)
  def domain(name: String): OooIexPhysicalDomain =
    domains.find(_.name == name).getOrElse(
      throw new NoSuchElementException(s"unknown IEX domain $name"))
  def ownersOf(classIndex: Int): Seq[OooIexPhysicalDomain] =
    domains.filter(_.ownsClass(classIndex))
}

/** Formal Linx scalar/control issue profile.
  *
  * Six ALU, three AGU, and two BRU domains are internal.  One external FSU
  * domain owns both floating/vector work and engine-command work.  Logical
  * class-bank residency is complete and disjoint; recipe-level capability
  * admission remains a separate runtime check.
  */
object OooIexLinxPhysicalProfile {
  import OooIexDomainCapability._

  val DomainCount = 12
  val ReleaseWidth = 12

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
    base.copy(iexIssueDomainCount = DomainCount,
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

  def apply(base: OooParams = OooParams()): OooIexPhysicalProfile = {
    val p = params(base)
    val half = p.iqBankCount / 2
    val lowerBanks = (BigInt(1) << half) - 1
    val allBanks = (BigInt(1) << p.iqBankCount) - 1
    val upperBanks = allBanks ^ lowerBanks

    val domains = Seq(
      OooIexPhysicalDomain("alu0",
        classMasks(p,
          aluClass -> clusterModuloMask(p.iqBankCount, 0, 0),
          stdClass -> lowerBanks),
        mask(SimpleAlu, StoreData), 0),
      OooIexPhysicalDomain("alu1",
        classMasks(p,
          aluClass -> clusterModuloMask(p.iqBankCount, 0, 1)),
        mask(SimpleAlu), 1),
      OooIexPhysicalDomain("alu2",
        classMasks(p,
          aluClass -> clusterModuloMask(p.iqBankCount, 0, 2),
          sysClass -> lowerBanks),
        mask(SimpleAlu, MultiCycleAlu, System), 2),
      OooIexPhysicalDomain("alu3",
        classMasks(p,
          aluClass -> clusterModuloMask(p.iqBankCount, 1, 0),
          stdClass -> upperBanks),
        mask(SimpleAlu, StoreData), 3),
      OooIexPhysicalDomain("alu4",
        classMasks(p,
          aluClass -> clusterModuloMask(p.iqBankCount, 1, 1)),
        mask(SimpleAlu), 4),
      OooIexPhysicalDomain("alu5",
        classMasks(p,
          aluClass -> clusterModuloMask(p.iqBankCount, 1, 2),
          sysClass -> upperBanks),
        mask(SimpleAlu, MultiCycleAlu, System), 5),
      OooIexPhysicalDomain("agu0",
        classMasks(p, aguClass -> moduloMask(p.iqBankCount, 0, 3)),
        mask(LoadAddress, StoreAddress), 6),
      OooIexPhysicalDomain("agu1",
        classMasks(p, aguClass -> moduloMask(p.iqBankCount, 1, 3)),
        mask(LoadAddress, StoreAddress), 7),
      OooIexPhysicalDomain("agu2",
        classMasks(p, aguClass -> moduloMask(p.iqBankCount, 2, 3)),
        mask(LoadAddress), 8),
      OooIexPhysicalDomain("bru0",
        classMasks(p, bruClass -> moduloMask(p.iqBankCount, 0, 2)),
        mask(Branch), 9),
      OooIexPhysicalDomain("bru1",
        classMasks(p, bruClass -> moduloMask(p.iqBankCount, 1, 2)),
        mask(Branch), 10),
      OooIexPhysicalDomain("fsu0",
        classMasks(p, fsuClass -> allBanks, cmdClass -> allBanks),
        mask(FloatingVector, EngineCommand), 11))

    val profile = OooIexPhysicalProfile(
      name = "linx-scalar-control-v1",
      params = p,
      domains = domains,
      dispatchableClasses = Set(aluClass, bruClass, aguClass, stdClass,
        fsuClass, sysClass, cmdClass),
      fastResolvedClasses = Set(boundaryClass))

    require(profile.ownersOf(stdClass).map(_.name).toSet == Set("alu0", "alu3") &&
      profile.ownersOf(stdClass).forall(_.hasCapability(StoreData)),
      "STD residency must be restricted to ALU0/ALU3")
    require(profile.ownersOf(sysClass).map(_.name).toSet == Set("alu2", "alu5") &&
      profile.ownersOf(sysClass).forall(_.hasCapability(System)),
      "system residency must be restricted to ALU2/ALU5")
    require(profile.domains.filter(_.hasCapability(MultiCycleAlu))
      .map(_.name).toSet == Set("alu2", "alu5"),
      "multi-cycle ALU capability must be restricted to ALU2/ALU5")
    require(profile.ownersOf(aguClass).forall(_.hasCapability(LoadAddress)) &&
      profile.domains.filter(_.hasCapability(StoreAddress)).map(_.name).toSet ==
        Set("agu0", "agu1"),
      "all AGUs accept loads while only AGU0/AGU1 accept stores")
    require(profile.ownersOf(fsuClass).map(_.name) == Seq("fsu0") &&
      profile.ownersOf(cmdClass).map(_.name) == Seq("fsu0"),
      "the external FSU domain owns FSU and engine-command residency")
    profile
  }
}
