package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, PopCount, Valid, log2Ceil}
import linxcore.common.{CoreParams => LoadCoreParams}
import linxcore.params.CoreParams
import linxcore.top.interface.{CmdIssueTxn, RecoveryEvent, RecoveryPhase,
  RecoveryPlan, RecoveryPlanContract, RecoveryTargetIO, RobNoflushReadyTxn,
  RobNoflushTxn, RobResolveTxn, SystemIssueTxn}

class OooIexExecutionRouteReject(val p: OooParams = OooParams())
    extends Bundle {
  val member = new RobMemberKey(p)
  val ownerClass = OooUopClass()
  val ownerLane = UInt(p.iexIssueDomainWidth.W)
  val requiredCapabilities = UInt(OooIexDomainCapability.Count.W)
  val classInRange = Bool()
  val capabilityOneHot = Bool()
  val laneExact = Bool()
  val supportedRoute = Bool()
}

/** Typed E1-and-later execution cluster for the formal Linx profile.
  *
  * Simple scalar ALU, BRU, and load-address lanes are internal.  Store
  * address/data, multi-cycle, system, pointer-authentication, floating/vector,
  * and engine-command work leave through explicit retained boundaries.  The
  * upstream E1 slot remains the owner until one of those boundaries fires.
  */
class OooIexExecutionClusterIO(
    val core: CoreParams,
    val profile: OooIexPhysicalProfile,
    val loadParams: LoadCoreParams) extends Bundle {
  val p = profile.params
  private val terminalPorts = p.iexTerminalWidth * p.maxDestinationOperands
  private def capabilityCount(capability: Int): Int =
    profile.pickerFunctions.count(_.hasCapability(capability))
  private val aluCount = capabilityCount(OooIexDomainCapability.SimpleAlu)
  private val bruCount = capabilityCount(OooIexDomainCapability.Branch)
  private val loadCount = capabilityCount(OooIexDomainCapability.LoadAddress)
  private val storeAddressCount =
    capabilityCount(OooIexDomainCapability.StoreAddress)
  private val storeDataCount = capabilityCount(OooIexDomainCapability.StoreData)
  private val multiCycleCount =
    capabilityCount(OooIexDomainCapability.MultiCycleAlu)
  private val systemCount = capabilityCount(OooIexDomainCapability.System)
  private val pointerAuthCount =
    capabilityCount(OooIexDomainCapability.PointerAuth)

  val e1 = Flipped(Vec(profile.pickerFunctions.length,
    Decoupled(new OooIexExecuteTransaction(p))))
  val recovery = Flipped(new RecoveryTargetIO(core))

  val storeAddress = Vec(storeAddressCount,
    Decoupled(new OooIexExecuteTransaction(p)))
  val storeData = Vec(storeDataCount,
    Decoupled(new OooIexExecuteTransaction(p)))
  val multiCycleAlu = Vec(multiCycleCount,
    Decoupled(new OooIexExecuteTransaction(p)))
  val pointerAuth = Vec(pointerAuthCount,
    Decoupled(new OooIexExecuteTransaction(p)))
  val floatingVector = Decoupled(new OooIexExecuteTransaction(p))
  val robNoflushReady = Decoupled(new RobNoflushReadyTxn(core))
  val robNoflush = Flipped(Decoupled(new RobNoflushTxn(core)))
  val systemIssue = Vec(systemCount, Decoupled(new SystemIssueTxn(core)))
  val cmdIssue = Decoupled(new CmdIssueTxn(core))
  val systemCmdResolve = Decoupled(new RobResolveTxn(core))
  val systemCmdTrace = Decoupled(new OooIexTerminalTrace(p))

  val load = new OooIexCanonicalLoadPortIO(p, loadParams)

  val pWrite = Vec(terminalPorts, Decoupled(new OooIexPFileWrite(p)))
  val tWrite = Vec(terminalPorts, Decoupled(new OooIexLocalFileWrite(p)))
  val uWrite = Vec(terminalPorts, Decoupled(new OooIexLocalFileWrite(p)))
  val wakeup = Output(Vec(p.iexWakeupPorts, Valid(new OooIexWakeup(p))))
  val bypass = Output(Vec(p.iexBypassPorts,
    Valid(new OooIexBypassCandidate(p))))
  val loadCancel = Output(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))

  val bctrl = Vec(p.iexTerminalWidth,
    Decoupled(new OooIexTerminalBctrl(p)))
  val trace = Vec(p.iexTerminalWidth,
    Decoupled(new OooIexTerminalTrace(p)))
  val robResolve = Vec(p.iexTerminalWidth,
    Decoupled(new RobResolveTxn(core)))
  val recoveryEvent = Vec(p.iexTerminalWidth,
    Decoupled(new RecoveryEvent(core)))

  val routeRejected = Output(Vec(profile.pickerFunctions.length,
    Valid(new OooIexExecutionRouteReject(p))))
  val aluRejected = Output(Vec(aluCount, Valid(new OooIexAluReject(p))))
  val bruRejected = Output(Vec(bruCount, Valid(new OooIexBruReject(p))))
  val aguRejected = Output(Vec(loadCount, Valid(new OooIexAguReject(p))))
  val terminalRejected = Output(Vec(p.iexTerminalWidth,
    Vec(3, Valid(new OooIexTerminalReject(p)))))
  val terminalFireMask = Output(UInt(p.iexTerminalWidth.W))
  val systemCmdTerminalFire = Output(Bool())
  val loadMetadataOccupied = Output(UInt(
    log2Ceil(loadParams.scalarLsu.liqEntries + 1).W))
  val empty = Output(Bool())
}

class OooIexExecutionCluster(
    val core: CoreParams,
    val loadParamsOverride: Option[LoadCoreParams] = None)
    extends Module {
  import OooIexDomainCapability._

  val profile = OooIexPhysicalProfile.fromCoreParams(core)
  val p = profile.params
  val loadParams = loadParamsOverride.getOrElse(
    OooIexCanonicalLoadOwnership.defaultCoreParams(p))
  private def pickerIndices(capability: Int): Seq[Int] =
    profile.pickerFunctions.zipWithIndex.collect {
      case (picker, index) if picker.hasCapability(capability) => index
    }
  private val aluLanes = pickerIndices(SimpleAlu)
  private val bruLanes = pickerIndices(Branch)
  private val loadLanes = pickerIndices(LoadAddress)
  private val storeAddressLanes = pickerIndices(StoreAddress)
  private val storeDataLanes = pickerIndices(StoreData)
  private val multiCycleLanes = pickerIndices(MultiCycleAlu)
  private val systemLanes = pickerIndices(System)
  private val pointerAuthLanes = pickerIndices(PointerAuth)
  private val floatingVectorLanes = pickerIndices(FloatingVector)
  private val engineCommandLanes = pickerIndices(EngineCommand)
  private val aluCount = aluLanes.length
  private val bruCount = bruLanes.length
  private val loadCount = loadLanes.length
  private val terminalPorts = p.iexTerminalWidth * p.maxDestinationOperands

  require(aluCount > 0 && bruCount > 0 && loadCount > 0,
    "execution cluster requires ALU, BRU, and load-address mechanisms")
  require(floatingVectorLanes.length == 1 && engineCommandLanes.length == 1,
    "execution cluster retains one floating/vector and one command boundary")
  require(p.iexWakeupPorts >= terminalPorts + loadCount,
    "committed and speculative load wakeups need independent ports")
  require(p.iexBypassPorts >= aluCount + loadCount,
    "every ALU W1 and load-return W1 owner needs a bypass port")
  require(p.iexLoadCancelPorts >= loadCount,
    "every scalar load pipe needs an independent cancel port")

  val io = IO(new OooIexExecutionClusterIO(core, profile, loadParams))

  val alus = Seq.fill(aluCount)(Module(new OooIexAluPipeline(p, core)))
  val brus = Seq.fill(bruCount)(Module(new OooIexBruPipeline(p, core)))
  val agus = Seq.fill(loadCount)(Module(new OooIexAguPipeline(p, core)))
  val load = Module(new OooIexCanonicalLoadOwnership(
    p, loadParams, laneCount = loadCount, recoveryParams = core))
  val terminal = Module(new OooIexTerminalFabric(
    core, aluCount, bruCount, loadCount))
  val systemCmd = Module(new OooIexSystemCmdTerminal(core))

  val recoveryPending = RegInit(false.B)
  val childrenPreparedAccepted = RegInit(false.B)
  val retainedRecovery = Reg(new RecoveryPlan(core))
  val prepareReady = !recoveryPending && terminal.io.recovery.prepare.ready &&
    load.io.recovery.prepare.ready && systemCmd.io.recovery.prepare.ready
  io.recovery.prepare.ready := prepareReady
  terminal.io.recovery.prepare.valid := io.recovery.prepare.valid && prepareReady
  terminal.io.recovery.prepare.bits := io.recovery.prepare.bits
  load.io.recovery.prepare.valid := io.recovery.prepare.valid && prepareReady
  load.io.recovery.prepare.bits := io.recovery.prepare.bits
  systemCmd.io.recovery.prepare.valid :=
    io.recovery.prepare.valid && prepareReady
  systemCmd.io.recovery.prepare.bits := io.recovery.prepare.bits
  when(io.recovery.prepare.fire) {
    retainedRecovery := io.recovery.prepare.bits
    recoveryPending := true.B
    childrenPreparedAccepted := false.B
  }

  val childrenPrepared = terminal.io.recovery.prepared.valid &&
    load.io.recovery.prepared.valid && systemCmd.io.recovery.prepared.valid
  io.recovery.prepared.valid := recoveryPending && childrenPrepared
  io.recovery.prepared.bits := retainedRecovery
  io.recovery.prepared.bits.phase := RecoveryPhase.Prepare
  terminal.io.recovery.prepared.ready := io.recovery.prepared.ready &&
    load.io.recovery.prepared.valid && systemCmd.io.recovery.prepared.valid
  load.io.recovery.prepared.ready := io.recovery.prepared.ready &&
    terminal.io.recovery.prepared.valid && systemCmd.io.recovery.prepared.valid
  systemCmd.io.recovery.prepared.ready := io.recovery.prepared.ready &&
    terminal.io.recovery.prepared.valid && load.io.recovery.prepared.valid

  val preparedPlansExact = childrenPrepared &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      terminal.io.recovery.prepared.bits, retainedRecovery) &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      load.io.recovery.prepared.bits, retainedRecovery) &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      systemCmd.io.recovery.prepared.bits, retainedRecovery)
  when(io.recovery.prepared.fire) {
    assert(preparedPlansExact,
      "execution children must prepare one exact recovery transaction")
    childrenPreparedAccepted := preparedPlansExact
  }
  val applyExact = io.recovery.apply.valid && recoveryPending &&
    childrenPreparedAccepted &&
    io.recovery.apply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.apply.bits, retainedRecovery)
  val abortExact = io.recovery.abort.valid && recoveryPending &&
    childrenPreparedAccepted &&
    io.recovery.abort.bits.phase === RecoveryPhase.Abort &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.abort.bits, retainedRecovery)
  val recoveryApply = Wire(Valid(new RecoveryPlan(core)))
  recoveryApply.valid := applyExact
  recoveryApply.bits := retainedRecovery
  recoveryApply.bits.phase := RecoveryPhase.Apply
  terminal.io.recovery.apply.valid := applyExact
  terminal.io.recovery.apply.bits := recoveryApply.bits
  load.io.recovery.apply.valid := applyExact
  load.io.recovery.apply.bits := recoveryApply.bits
  systemCmd.io.recovery.apply.valid := applyExact
  systemCmd.io.recovery.apply.bits := recoveryApply.bits
  terminal.io.recovery.abort.valid := abortExact
  terminal.io.recovery.abort.bits := retainedRecovery
  terminal.io.recovery.abort.bits.phase := RecoveryPhase.Abort
  load.io.recovery.abort.valid := abortExact
  load.io.recovery.abort.bits := retainedRecovery
  load.io.recovery.abort.bits.phase := RecoveryPhase.Abort
  systemCmd.io.recovery.abort.valid := abortExact
  systemCmd.io.recovery.abort.bits := retainedRecovery
  systemCmd.io.recovery.abort.bits.phase := RecoveryPhase.Abort
  when(applyExact || abortExact) {
    recoveryPending := false.B
    childrenPreparedAccepted := false.B
  }
  when(io.recovery.apply.valid) {
    assert(applyExact,
      "execution recovery apply requires the exact prepared transaction")
  }
  when(io.recovery.abort.valid) {
    assert(abortExact,
      "execution recovery abort requires the exact prepared transaction")
  }

  for (index <- 0 until aluCount) {
    alus(index).io.recoveryApply := recoveryApply
    alus(index).io.loadCancel := io.loadCancel
    terminal.io.alu(index) <> alus(index).io.w2
    io.aluRejected(index) := alus(index).io.rejected
  }
  for (index <- 0 until bruCount) {
    brus(index).io.recoveryApply := recoveryApply
    brus(index).io.loadCancel := io.loadCancel
    terminal.io.bru(index) <> brus(index).io.e2
    io.bruRejected(index) := brus(index).io.rejected
  }
  for (index <- 0 until loadCount) {
    agus(index).io.recoveryApply := recoveryApply
    agus(index).io.loadCancel := io.loadCancel
    load.io.agu(index) <> agus(index).io.request

    io.aguRejected(index) := agus(index).io.rejected
  }

  io.load.liqAlloc <> load.io.liqAlloc
  load.io.liqAllocLoadId := io.load.liqAllocLoadId
  load.io.rebind <> io.load.rebind
  io.load.liqRebind <> load.io.liqRebind
  load.io.attemptLaunch := io.load.attemptLaunch
  io.load.attemptLaunchAccepted := load.io.attemptLaunchAccepted
  load.io.completion <> io.load.completion
  for (index <- 0 until loadCount) {
    terminal.io.load(index).valid := load.io.result.valid &&
      load.io.resultLane === index.U
    terminal.io.load(index).bits := load.io.result.bits
  }
  load.io.result.ready := VecInit(terminal.io.load.map(_.ready))(
    load.io.resultLane)
  io.loadMetadataOccupied := load.io.metadataOccupied

  for (port <- 0 until terminalPorts) {
    io.pWrite(port).valid := terminal.io.pWrite(port).valid
    io.pWrite(port).bits := terminal.io.pWrite(port).bits
    terminal.io.pWrite(port).ready := io.pWrite(port).ready
    io.tWrite(port).valid := terminal.io.tWrite(port).valid
    io.tWrite(port).bits := terminal.io.tWrite(port).bits
    terminal.io.tWrite(port).ready := io.tWrite(port).ready
    io.uWrite(port).valid := terminal.io.uWrite(port).valid
    io.uWrite(port).bits := terminal.io.uWrite(port).bits
    terminal.io.uWrite(port).ready := io.uWrite(port).ready
  }
  for (lane <- 0 until p.iexTerminalWidth) {
    io.bctrl(lane).valid := terminal.io.bctrl(lane).valid
    io.bctrl(lane).bits := terminal.io.bctrl(lane).bits
    terminal.io.bctrl(lane).ready := io.bctrl(lane).ready
    io.trace(lane).valid := terminal.io.trace(lane).valid
    io.trace(lane).bits := terminal.io.trace(lane).bits
    terminal.io.trace(lane).ready := io.trace(lane).ready
    io.robResolve(lane) <> terminal.io.robResolve(lane)
    io.recoveryEvent(lane) <> terminal.io.recoveryEvent(lane)
  }
  io.terminalRejected := terminal.io.rejected
  io.terminalFireMask := terminal.io.terminalFireMask

  io.robNoflushReady <> systemCmd.io.robNoflushReady
  systemCmd.io.robNoflush <> io.robNoflush
  for (lane <- io.systemIssue.indices) {
    io.systemIssue(lane) <> systemCmd.io.systemIssue(lane)
  }
  io.cmdIssue <> systemCmd.io.cmdIssue
  io.systemCmdResolve <> systemCmd.io.robResolve
  io.systemCmdTrace <> systemCmd.io.trace
  io.systemCmdTerminalFire := systemCmd.io.terminalFire

  io.wakeup.foreach(_ := 0.U.asTypeOf(io.wakeup.head))
  for (port <- 0 until terminalPorts) {
    io.wakeup(port).valid := terminal.io.wakeup(port).valid
    io.wakeup(port).bits := terminal.io.wakeup(port).bits
    terminal.io.wakeup(port).ready := true.B
  }
  for (index <- 0 until loadCount) {
    io.wakeup(terminalPorts + index) := load.io.speculativeWakeup(index)
  }

  io.bypass.foreach(_ := 0.U.asTypeOf(io.bypass.head))
  for (index <- 0 until aluCount) {
    io.bypass(index).valid := alus(index).io.w1Bypass.valid
    io.bypass(index).bits.stid :=
      alus(index).io.w1Bypass.bits.execute.i2.row.stid
    io.bypass(index).bits.epoch :=
      alus(index).io.w1Bypass.bits.execute.i2.row.epoch
    io.bypass(index).bits.producer :=
      alus(index).io.w1Bypass.bits.execute.i2.row.member
    val writeback = alus(index).io.w1Bypass.bits.writebacks(0)
    io.bypass(index).bits.operandClass := Mux(
      writeback.destination.kind === linxcore.common.DestinationKind.Gpr,
      linxcore.common.OperandClass.P,
      Mux(writeback.destination.kind === linxcore.common.DestinationKind.T,
        linxcore.common.OperandClass.T, linxcore.common.OperandClass.U))
    io.bypass(index).bits.ptag := writeback.destination.ptag
    io.bypass(index).bits.ptagGeneration :=
      writeback.destination.ptagGeneration
    io.bypass(index).bits.localTag := writeback.destination.localTag
    io.bypass(index).bits.localSequence :=
      writeback.destination.localSequence
    io.bypass(index).bits.stage := OooIexBypassStage.W1
    io.bypass(index).bits.data := writeback.data
  }
  for (index <- 0 until loadCount) {
    io.bypass(aluCount + index) := load.io.loadBypass(index)
  }

  io.loadCancel.foreach(_ := 0.U.asTypeOf(io.loadCancel.head))
  for (index <- 0 until loadCount) {
    io.loadCancel(index) := load.io.loadCancel(index)
  }

  private def route(
      domain: Int,
      destinations: Seq[(Int,
        DecoupledIO[OooIexExecuteTransaction])]): Unit = {
    val input = io.e1(domain)
    val classIndex = input.bits.ownerClass.asUInt
    val classInRange = classIndex < p.iqClassCount.U
    val safeClass = Mux(classInRange, classIndex, 0.U)
    val required = input.bits.i2.row.recipe.dispatchCapabilities(safeClass)
    val capabilityOneHot = required.orR && PopCount(required) === 1.U
    val laneExact = input.bits.ownerLane === domain.U
    val routeBase = classInRange && capabilityOneHot && laneExact &&
      input.bits.i2.row.recipe.valid
    val selections = destinations.map { case (capability, _) =>
      routeBase && required(capability)
    }

    destinations.zip(selections).foreach {
      case ((_, destination), selected) =>
        destination.valid := input.valid && selected
        destination.bits := input.bits
    }
    input.ready := destinations.zip(selections).map {
      case ((_, destination), selected) => selected && destination.ready
    }.reduce(_ || _)

    val supported = selections.reduce(_ || _)
    io.routeRejected(domain).valid := input.valid && !supported
    io.routeRejected(domain).bits.member := input.bits.i2.row.member
    io.routeRejected(domain).bits.ownerClass := input.bits.ownerClass
    io.routeRejected(domain).bits.ownerLane := input.bits.ownerLane
    io.routeRejected(domain).bits.requiredCapabilities := required
    io.routeRejected(domain).bits.classInRange := classInRange
    io.routeRejected(domain).bits.capabilityOneHot := capabilityOneHot
    io.routeRejected(domain).bits.laneExact := laneExact
    io.routeRejected(domain).bits.supportedRoute := supported
    assert(PopCount(VecInit(selections)) <= 1.U,
      "one E1 owner must select at most one typed execution destination")
  }

  for (domain <- profile.pickerFunctions.indices) {
    val destinations = scala.collection.mutable.ArrayBuffer.empty[
      (Int, DecoupledIO[OooIexExecuteTransaction])]
    def append(capability: Int, lanes: Seq[Int],
        outputs: Seq[DecoupledIO[OooIexExecuteTransaction]]): Unit = {
      val ordinal = lanes.indexOf(domain)
      if (ordinal >= 0) destinations += capability -> outputs(ordinal)
    }
    append(SimpleAlu, aluLanes, alus.map(_.io.e1))
    append(StoreData, storeDataLanes, io.storeData.toSeq)
    append(LoadAddress, loadLanes, agus.map(_.io.e1))
    append(StoreAddress, storeAddressLanes, io.storeAddress.toSeq)
    append(Branch, bruLanes, brus.map(_.io.e1))
    append(MultiCycleAlu, multiCycleLanes, io.multiCycleAlu.toSeq)
    append(System, systemLanes, systemCmd.io.system.toSeq)
    append(PointerAuth, pointerAuthLanes, io.pointerAuth.toSeq)
    if (floatingVectorLanes.contains(domain))
      destinations += FloatingVector -> io.floatingVector
    if (engineCommandLanes.contains(domain)) {
      val ordinal = engineCommandLanes.indexOf(domain)
      destinations += EngineCommand -> systemCmd.io.cmd(ordinal)
    }
    require(destinations.nonEmpty,
      s"IEX picker ${profile.pickerFunctions(domain).name} has no execution destination")
    route(domain, destinations.toSeq)
  }

  val internalEmpty = alus.map(_.io.empty).reduce(_ && _) &&
    brus.map(!_.io.occupied).reduce(_ && _) &&
    agus.map(!_.io.occupied).reduce(_ && _) &&
    load.io.metadataEmpty && systemCmd.io.empty
  io.empty := !recoveryPending && internalEmpty &&
    !io.e1.map(_.valid).reduce(_ || _)
}
