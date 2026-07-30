package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, PopCount, Valid, log2Ceil}
import linxcore.common.CoreParams

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
    val p: OooParams,
    val coreParams: CoreParams) extends Bundle {
  private val terminalPorts = p.iexTerminalWidth * p.maxDestinationOperands

  val e1 = Flipped(Vec(OooIexLinxPhysicalProfile.ExecutionLaneCount,
    Decoupled(new OooIexExecuteTransaction(p))))
  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryRejected = Output(Bool())
  val recoveryFire = Input(Bool())

  val storeAddress = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val storeData = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val multiCycleAlu = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val system = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val pointerAuth = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val floatingVector = Decoupled(new OooIexExecuteTransaction(p))
  val engineCommand = Decoupled(new OooIexExecuteTransaction(p))

  val load = new OooIexCanonicalLoadPortIO(p, coreParams)

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
  val completion = Vec(p.iexTerminalWidth,
    Decoupled(new OooRobMemberCompletion(p)))

  val routeRejected = Output(Vec(
    OooIexLinxPhysicalProfile.ExecutionLaneCount,
    Valid(new OooIexExecutionRouteReject(p))))
  val aluRejected = Output(Vec(6, Valid(new OooIexAluReject(p))))
  val bruRejected = Output(Vec(2, Valid(new OooIexBruReject(p))))
  val aguRejected = Output(Vec(3, Valid(new OooIexAguReject(p))))
  val terminalRejected = Output(Vec(p.iexTerminalWidth,
    Vec(3, Valid(new OooIexTerminalReject(p)))))
  val terminalFireMask = Output(UInt(p.iexTerminalWidth.W))
  val loadMetadataOccupied = Output(UInt(
    log2Ceil(coreParams.scalarLsu.liqEntries + 1).W))
  val empty = Output(Bool())
}

class OooIexExecutionCluster(
    val profile: OooIexPhysicalProfile = OooIexLinxPhysicalProfile(),
    val coreParamsOverride: Option[CoreParams] = None)
    extends Module {
  import OooIexDomainCapability._

  val p = profile.params
  val coreParams = coreParamsOverride.getOrElse(
    OooIexCanonicalLoadOwnership.defaultCoreParams(p))
  private val aluCount = 6
  private val bruCount = 2
  private val loadCount = 3
  private val terminalPorts = p.iexTerminalWidth * p.maxDestinationOperands

  require(profile.name == "linx-scalar-control-v2" &&
    profile.pickerFunctions.length ==
      OooIexLinxPhysicalProfile.PickerFunctionCount,
    "execution cluster requires the formal Linx scalar/control profile")
  require(p.iexWakeupPorts >= terminalPorts + loadCount,
    "committed and speculative load wakeups need independent ports")
  require(p.iexBypassPorts >= aluCount + loadCount,
    "every ALU W1 and load-return W1 owner needs a bypass port")
  require(p.iexLoadCancelPorts >= loadCount,
    "every scalar load pipe needs an independent cancel port")

  val io = IO(new OooIexExecutionClusterIO(p, coreParams))

  val alus = Seq.fill(aluCount)(Module(new OooIexAluPipeline(p)))
  val brus = Seq.fill(bruCount)(Module(new OooIexBruPipeline(p)))
  val agus = Seq.fill(loadCount)(Module(new OooIexAguPipeline(p)))
  val load = Module(new OooIexCanonicalLoadOwnership(p, coreParams))
  val terminal = Module(new OooIexTerminalFabric(
    p, aluCount, bruCount, loadCount))

  val recoveryApply = Wire(Valid(new OooResidencyRecoveryPlan(p)))
  recoveryApply.valid := io.recoveryFire && io.recoveryPrepareReady
  recoveryApply.bits := io.recoveryPrepare.bits
  load.io.recoveryPrepare := io.recoveryPrepare
  io.recoveryPrepareReady := load.io.recoveryPrepareReady
  io.recoveryRejected := load.io.recoveryRejected
  load.io.recoveryFire := io.recoveryFire && io.recoveryPrepareReady
  load.io.flush := false.B

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
    io.completion(lane).valid := terminal.io.completion(lane).valid
    io.completion(lane).bits := terminal.io.completion(lane).bits
    terminal.io.completion(lane).ready := io.completion(lane).ready
  }
  io.terminalRejected := terminal.io.rejected
  io.terminalFireMask := terminal.io.terminalFireMask

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

  val aluLanes = (0 until aluCount).map(index =>
    profile.pickerIndex(s"alu$index"))
  route(aluLanes(0), Seq(SimpleAlu -> alus(0).io.e1,
    StoreData -> io.storeData(0)))
  route(aluLanes(1), Seq(SimpleAlu -> alus(1).io.e1))
  route(aluLanes(2), Seq(SimpleAlu -> alus(2).io.e1,
    MultiCycleAlu -> io.multiCycleAlu(0), System -> io.system(0),
    PointerAuth -> io.pointerAuth(0)))
  route(aluLanes(3), Seq(SimpleAlu -> alus(3).io.e1,
    StoreData -> io.storeData(1)))
  route(aluLanes(4), Seq(SimpleAlu -> alus(4).io.e1))
  route(aluLanes(5), Seq(SimpleAlu -> alus(5).io.e1,
    MultiCycleAlu -> io.multiCycleAlu(1), System -> io.system(1),
    PointerAuth -> io.pointerAuth(1)))

  route(profile.pickerIndex("agu0-lda"),
    Seq(LoadAddress -> agus(0).io.e1))
  route(profile.pickerIndex("agu0-sta"),
    Seq(StoreAddress -> io.storeAddress(0)))
  route(profile.pickerIndex("agu1-lda"),
    Seq(LoadAddress -> agus(1).io.e1))
  route(profile.pickerIndex("agu1-sta"),
    Seq(StoreAddress -> io.storeAddress(1)))
  route(profile.pickerIndex("agu2-lda"),
    Seq(LoadAddress -> agus(2).io.e1))
  route(profile.pickerIndex("bru0"), Seq(Branch -> brus(0).io.e1))
  route(profile.pickerIndex("bru1"), Seq(Branch -> brus(1).io.e1))
  route(profile.pickerIndex("fsu0"), Seq(
    FloatingVector -> io.floatingVector,
    EngineCommand -> io.engineCommand))

  val internalEmpty = alus.map(_.io.empty).reduce(_ && _) &&
    brus.map(!_.io.occupied).reduce(_ && _) &&
    agus.map(!_.io.occupied).reduce(_ && _) &&
    load.io.metadataEmpty
  io.empty := internalEmpty && !io.e1.map(_.valid).reduce(_ || _)
}
