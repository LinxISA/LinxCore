package linxcore.iex

import chisel3._
import chisel3.util.{Arbiter, Decoupled, DecoupledIO, Queue}
import linxcore.lsu.LSU
import linxcore.ooo.OOO
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface.{D1Packet, LoadReissueTxn, LoadResultTxn,
  MemoryIdentity, MemoryTransactionIdentity, RecoveryCause, TraceKind}

/** Capacity-bounded generated-RTL profile. Principal W4 pipe counts and all
  * public identity widths remain main-profile-identical; only retained local
  * storage is reduced to the already-proven Task-14 minimum geometry.
  */
object OOOIEXLSUActivationParams {
  private val main = ParamProfiles.W4
  val W4: CoreParams = main.copy(
    ooo = main.ooo.copy(
      stidCount = 2,
      robGroupsPerStid = 8,
      maxInstructionsPerRobGroup = 1,
      maxUopsPerInstruction = 12,
      robBankCount = 4,
      brobEntriesPerStid = 4,
      pcBufferEntries = 8,
      pcBankCount = 4,
      pcRecoveryScanGroupsPerCycle = 4,
      gprPhysRegs = 64,
      gprMapQDepthPerStid = 8,
      tPhysRegs = 8,
      uPhysRegs = 8,
      tuMapQDepthPerStid = 8),
    iex = main.iex.copy(scalarIssueEntries = 4),
    lsu = main.lsu.copy(
      loadQueueEntries = 2,
      storeQueueEntries = 2,
      loadReturnQueueEntries = 2,
      storeCommitQueueEntries = 2,
      scbEntries = 4,
      loadMissQueueEntries = 2,
      loadRefillQueueEntries = 2,
      resolveQueueEntries = 4,
      mdbSsitEntries = 4,
      mdbCommandQueueEntries = 4,
      mdbOutputQueueEntries = 4,
      mdbWaitPlanQueueEntries = 4,
      mdbRecoveryQueueEntries = 4,
      mdbFailedWaitTimeoutCycles = 8,
      l1dSets = 2,
      l1dWays = 2))
}

class OOOIEXLSUActivationProbeIO(val p: CoreParams) extends Bundle {
  val program = Flipped(Decoupled(new D1Packet(p)))
  val commitReady = Input(Bool())
  val trapReady = Input(Bool())
  val cmdReady = Input(Bool())
  val systemReady = Input(Bool())
  val oooTraceReady = Input(Bool())
  val iexTraceReady = Input(Bool())
  val recoveryReady = Input(Bool())
  val lsuTraceReady = Input(Bool())
  val memoryReady = Input(Vec(p.lsu.loadPipes + p.lsu.storePipes, Bool()))
  val memoryResponseValid = Input(Bool())
  val memoryResponseId = Input(UInt(p.memoryTransactionIdWidth.W))
  val memoryResponseGeneration =
    Input(UInt(p.memoryTransactionGenerationWidth.W))
  val memoryResponseAddress = Input(UInt(p.physicalAddressWidth.W))
  val memoryResponseData = Input(UInt(p.dataWidth.W))
  val memoryResponseReady = Output(Bool())
  val loadReissueRequest = Flipped(Decoupled(new LoadReissueTxn(p)))
  val loadResultInject = Flipped(Decoupled(new LoadResultTxn(p)))
  val bootstrapComplete = Output(Bool())
  val bootstrapInitCount = Output(UInt(32.W))

  val ingressCount = Output(UInt(32.W))
  val aluCount = Output(UInt(32.W))
  val bruCount = Output(UInt(32.W))
  val aguCount = Output(UInt(32.W))
  val stdCount = Output(UInt(32.W))
  val systemCount = Output(UInt(32.W))
  val cmdCount = Output(UInt(32.W))
  val systemIssueCount = Output(UInt(32.W))
  val cmdIssueCount = Output(UInt(32.W))
  val resolveCount = Output(UInt(32.W))
  val rfWriteCount = Output(UInt(32.W))
  val branchCount = Output(UInt(32.W))
  val recoveryEventCount = Output(UInt(32.W))
  val recoveryApplyCount = Output(UInt(32.W))
  val iexTerminalTraceCount = Output(UInt(32.W))
  val loadCount = Output(UInt(32.W))
  val loadLaunchCount = Output(UInt(32.W))
  val loadAttemptLaunchCount = Output(UInt(32.W))
  val loadAttemptCancelCount = Output(UInt(32.W))
  val loadResultCount = Output(UInt(32.W))
  val lastLoadIssueIdentity = Output(new MemoryIdentity(p))
  val lastLoadAllocationId = Output(new MemoryTransactionIdentity(p))
  val lastLoadDestination = Output(
    new linxcore.top.interface.RenamedDestination(p))
  val lastLoadDestinationRelativeIndex = Output(UInt(p.archRegWidth.W))
  val lastLoadLaunchIdentity = Output(new MemoryIdentity(p))
  val firstLoadAttemptLaunchIdentity = Output(new MemoryIdentity(p))
  val lastLoadAttemptLaunchIdentity = Output(new MemoryIdentity(p))
  val lastLoadRebindCurrent = Output(new MemoryIdentity(p))
  val lastLoadRebindNext = Output(new MemoryIdentity(p))
  val lastLoadResultIdentity = Output(new MemoryIdentity(p))
  val storeAddressCount = Output(UInt(32.W))
  val storeDataCount = Output(UInt(32.W))
  val memoryCount = Output(UInt(32.W))
  val commitCount = Output(UInt(32.W))
  val traceCount = Output(UInt(32.W))
  val stid0Progress = Output(UInt(32.W))
  val stid1Progress = Output(UInt(32.W))
  val stid0CommitProgress = Output(UInt(32.W))
  val stid1CommitProgress = Output(UInt(32.W))
  val dispatchStallCount = Output(UInt(32.W))
  val memoryStallCount = Output(UInt(32.W))
  val lastResolveValue = Output(UInt(p.dataWidth.W))
  val lastRfWriteValue = Output(UInt(p.dataWidth.W))
  val lastRfWritePtag = Output(UInt(
    chisel3.util.log2Ceil(p.ooo.gprPhysRegs).W))
  val lastRfWriteGeneration = Output(UInt(p.ooo.gprTagGenerationWidth.W))
  val lastBranchTarget = Output(UInt(p.pcWidth.W))
  val lastLoadAddress = Output(UInt(p.physicalAddressWidth.W))
  val lastStoreAddress = Output(UInt(p.physicalAddressWidth.W))
  val lastStoreData = Output(UInt(p.dataWidth.W))
  val lastMemoryId = Output(UInt(p.memoryTransactionIdWidth.W))
  val lastMemoryGeneration = Output(
    UInt(p.memoryTransactionGenerationWidth.W))
  val lastMemoryAddress = Output(UInt(p.physicalAddressWidth.W))
}

/** Retained subsystem composition used by the generated-RTL activation gate.
  * It contains no state beyond the three mainline owners.
  */
class OOOIEXLSUActivationProbe(val p: CoreParams) extends Module {
  val io = IO(new OOOIEXLSUActivationProbeIO(p))

  private val ooo = Module(new OOO(p))
  private val iex = Module(new IEX(p))
  private val lsu = Module(new LSU(p))

  private def queued[T <: Data](source: DecoupledIO[T],
      sink: DecoupledIO[T]): Unit = {
    val boundary = Module(new Queue(chiselTypeOf(source.bits), 1,
      pipe = false, flow = false))
    boundary.io.enq <> source
    sink <> boundary.io.deq
  }
  for (lane <- ooo.io.iex.aluDispatch.indices) {
    queued(ooo.io.iex.aluDispatch(lane), iex.io.ooo.aluDispatch(lane))
  }
  for (lane <- ooo.io.iex.bruDispatch.indices) {
    queued(ooo.io.iex.bruDispatch(lane), iex.io.ooo.bruDispatch(lane))
  }
  for (lane <- ooo.io.iex.aguDispatch.indices) {
    queued(ooo.io.iex.aguDispatch(lane), iex.io.ooo.aguDispatch(lane))
  }
  for (lane <- ooo.io.iex.storeDispatch.indices) {
    queued(ooo.io.iex.storeDispatch(lane), iex.io.ooo.storeDispatch(lane))
  }
  for (lane <- ooo.io.iex.systemDispatch.indices) {
    queued(ooo.io.iex.systemDispatch(lane), iex.io.ooo.systemDispatch(lane))
  }
  for (lane <- ooo.io.iex.cmdDispatch.indices) {
    queued(ooo.io.iex.cmdDispatch(lane), iex.io.ooo.cmdDispatch(lane))
  }
  iex.io.ooo.allocationClear := ooo.io.iex.allocationClear
  queued(ooo.io.iex.fastWriteback, iex.io.ooo.fastWriteback)
  queued(ooo.io.iex.fastWakeup, iex.io.ooo.fastWakeup)
  ooo.io.iex.pcBufferReadAddress := iex.io.ooo.pcBufferReadAddress
  iex.io.ooo.pcBufferReadPcBase := ooo.io.iex.pcBufferReadPcBase
  ooo.io.iex.robNoflushReady <> iex.io.ooo.robNoflushReady
  queued(ooo.io.iex.robNoflush, iex.io.ooo.robNoflush)
  for (lane <- ooo.io.iex.robResolve.indices) {
    ooo.io.iex.robResolve(lane) <> iex.io.ooo.robResolve(lane)
  }
  for (lane <- ooo.io.iex.systemIssue.indices) {
    ooo.io.iex.systemIssue(lane) <> iex.io.ooo.systemIssue(lane)
  }
  ooo.io.iex.recoveryEvent.valid := iex.io.ooo.recoveryEvent.valid &&
    io.recoveryReady
  ooo.io.iex.recoveryEvent.bits := iex.io.ooo.recoveryEvent.bits
  iex.io.ooo.recoveryEvent.ready := ooo.io.iex.recoveryEvent.ready &&
    io.recoveryReady
  ooo.io.iex.recovery <> iex.io.ooo.recovery
  for (lane <- iex.io.lsu.storeReservation.indices) {
    iex.io.lsu.storeReservation(lane) <> lsu.io.iex.storeReservation(lane)
  }
  for (lane <- iex.io.lsu.loadAddress.indices) {
    iex.io.lsu.loadAddress(lane) <> lsu.io.iex.loadAddress(lane)
    iex.io.lsu.loadAllocation(lane) <> lsu.io.iex.loadAllocation(lane)
    iex.io.lsu.loadLaunch(lane) <> lsu.io.iex.loadLaunch(lane)
    iex.io.lsu.loadReissue(lane) <> lsu.io.iex.loadReissue(lane)
    iex.io.lsu.loadRebindApply(lane) <> lsu.io.iex.loadRebindApply(lane)
    iex.io.lsu.loadRepick(lane) <> lsu.io.iex.loadRepick(lane)
    iex.io.lsu.loadCancel(lane) <> lsu.io.iex.loadCancel(lane)
  }
  val loadResultArbiter = Module(new Arbiter(new LoadResultTxn(p), 2))
  loadResultArbiter.io.in(0) <> io.loadResultInject
  loadResultArbiter.io.in(1) <> lsu.io.iex.loadResult.head
  iex.io.lsu.loadResult.head <> loadResultArbiter.io.out
  for (lane <- 1 until iex.io.lsu.loadResult.length) {
    iex.io.lsu.loadResult(lane) <> lsu.io.iex.loadResult(lane)
  }
  for (lane <- iex.io.lsu.storeAddress.indices) {
    iex.io.lsu.storeAddress(lane) <> lsu.io.iex.storeAddress(lane)
    iex.io.lsu.storeData(lane) <> lsu.io.iex.storeData(lane)
  }
  iex.io.lsu.recoveryEvent <> lsu.io.iex.recoveryEvent
  ooo.io.recoveryToLsu <> lsu.io.recovery

  val bootCount = p.ooo.stidCount * p.ooo.gprArchRegs
  val bootIndex = RegInit(0.U(chisel3.util.log2Ceil(bootCount + 1).W))
  val bootActive = bootIndex < bootCount.U
  iex.io.pInit.valid := bootActive
  iex.io.pInit.bits := 0.U.asTypeOf(iex.io.pInit.bits)
  iex.io.pInit.bits.stid := bootIndex / p.ooo.gprArchRegs.U
  iex.io.pInit.bits.atag := bootIndex % p.ooo.gprArchRegs.U
  iex.io.pInit.bits.epoch := 1.U
  iex.io.pInit.bits.ptag := bootIndex
  when(iex.io.pInit.fire) { bootIndex := bootIndex + 1.U }
  iex.io.bootstrapComplete := !bootActive
  val bootstrapDone = iex.io.bootstrapReady
  io.bootstrapComplete := bootstrapDone
  ooo.io.fromCtu.valid := io.program.valid && bootstrapDone
  ooo.io.fromCtu.bits := io.program.bits
  io.program.ready := ooo.io.fromCtu.ready && bootstrapDone
  ooo.io.commit.ready := io.commitReady
  ooo.io.trap.ready := io.trapReady
  ooo.io.interrupt.valid := false.B
  ooo.io.interrupt.bits := 0.U.asTypeOf(ooo.io.interrupt.bits)
  ooo.io.trace.ready := io.oooTraceReady
  ooo.io.systemIssue.foreach(_.ready := io.systemReady)
  for (target <- Seq(ooo.io.recoveryToIfu, ooo.io.recoveryToCtu)) {
    val pending = RegInit(false.B)
    val retained = Reg(chiselTypeOf(target.prepare.bits))
    target.prepare.ready := !pending
    target.prepared.valid := pending
    target.prepared.bits := retained
    when(target.prepare.fire) {
      pending := true.B
      retained := target.prepare.bits
    }.elsewhen(target.prepared.fire) {
      pending := false.B
    }
  }

  iex.io.cmdIssue.ready := io.cmdReady
  iex.io.trace.ready := io.iexTraceReady
  lsu.io.storeCommit.valid := false.B
  lsu.io.storeCommit.bits := 0.U.asTypeOf(lsu.io.storeCommit.bits)
  lsu.io.storeClassify.valid := false.B
  lsu.io.storeClassify.bits := 0.U.asTypeOf(lsu.io.storeClassify.bits)
  lsu.io.loadReissueRequest <> io.loadReissueRequest
  for (lane <- lsu.io.memoryRequest.indices) {
    lsu.io.memoryRequest(lane).ready := io.memoryReady(lane)
  }
  lsu.io.memoryResponse.foreach { response =>
    response.valid := false.B
    response.bits := 0.U.asTypeOf(response.bits)
  }
  lsu.io.memoryResponse.head.valid := io.memoryResponseValid
  lsu.io.memoryResponse.head.bits.identity.value := io.memoryResponseId
  lsu.io.memoryResponse.head.bits.identity.generation :=
    io.memoryResponseGeneration
  lsu.io.memoryResponse.head.bits.address := io.memoryResponseAddress
  lsu.io.memoryResponse.head.bits.data := io.memoryResponseData
  io.memoryResponseReady := lsu.io.memoryResponse.head.ready
  lsu.io.trace.ready := io.lsuTraceReady

  private def countWhen(event: Bool): UInt = {
    val count = RegInit(0.U(32.W))
    when(event) { count := count + 1.U }
    count
  }
  private def anyFire(ports: Seq[DecoupledIO[_ <: Data]]): Bool =
    ports.map(_.fire).reduce(_ || _)
  private def anyBlocked(ports: Seq[DecoupledIO[_ <: Data]]): Bool =
    ports.map(port => port.valid && !port.ready).reduce(_ || _)

  val aluFire = anyFire(iex.io.ooo.aluDispatch.toSeq)
  val bruFire = anyFire(iex.io.ooo.bruDispatch.toSeq)
  val aguFire = anyFire(iex.io.ooo.aguDispatch.toSeq)
  val stdFire = anyFire(iex.io.ooo.storeDispatch.toSeq)
  val systemFire = anyFire(iex.io.ooo.systemDispatch.toSeq)
  val cmdFire = anyFire(iex.io.ooo.cmdDispatch.toSeq)
  val systemIssueFire = anyFire(ooo.io.systemIssue.toSeq)
  val cmdIssueFire = iex.io.cmdIssue.fire
  val resolveFire = anyFire(ooo.io.iex.robResolve.toSeq)
  val loadFire = anyFire(lsu.io.iex.loadAddress.toSeq)
  val loadLaunchFire = lsu.io.iex.loadLaunch.map(_.valid).reduce(_ || _)
  val countedLoadAttempt = RegInit(0.U.asTypeOf(new MemoryIdentity(p)))
  val countedLoadAttemptValid = RegInit(false.B)
  val firstLoadAttemptLaunch = RegInit(0.U.asTypeOf(new MemoryIdentity(p)))
  val lastLoadAttemptLaunch = RegInit(0.U.asTypeOf(new MemoryIdentity(p)))
  val launchAttemptIdentity = lsu.io.iex.loadLaunch.head.bits.identity
  val sameCountedLoadAttempt = countedLoadAttemptValid &&
    launchAttemptIdentity.transaction.asUInt ===
      countedLoadAttempt.transaction.asUInt &&
    launchAttemptIdentity.attemptGeneration ===
      countedLoadAttempt.attemptGeneration
  val loadAttemptLaunchFire = loadLaunchFire &&
    !sameCountedLoadAttempt
  when(loadAttemptLaunchFire) {
    countedLoadAttempt := launchAttemptIdentity
    when(!countedLoadAttemptValid) {
      firstLoadAttemptLaunch := launchAttemptIdentity
    }
    lastLoadAttemptLaunch := launchAttemptIdentity
    countedLoadAttemptValid := true.B
  }
  val loadRebindFire = anyFire(iex.io.lsu.loadRebindApply.toSeq)
  val loadResultFire = anyFire(iex.io.lsu.loadResult.toSeq)
  val storeAddressFire = anyFire(lsu.io.iex.storeAddress.toSeq)
  val storeDataFire = anyFire(lsu.io.iex.storeData.toSeq)
  val memoryFire = anyFire(lsu.io.memoryRequest.toSeq)
  val traceFire = ooo.io.trace.fire || iex.io.trace.fire || lsu.io.trace.fire

  io.ingressCount := countWhen(io.program.fire)
  io.bootstrapInitCount := countWhen(iex.io.pInit.fire)
  io.aluCount := countWhen(aluFire)
  io.bruCount := countWhen(bruFire)
  io.aguCount := countWhen(aguFire)
  io.stdCount := countWhen(stdFire)
  io.systemCount := countWhen(systemFire)
  io.cmdCount := countWhen(cmdFire)
  io.systemIssueCount := countWhen(systemIssueFire)
  io.cmdIssueCount := countWhen(cmdIssueFire)
  io.resolveCount := countWhen(resolveFire)
  val rfWriteFire = iex.io.terminalPWrite.valid
  io.rfWriteCount := countWhen(rfWriteFire)
  io.branchCount := countWhen(iex.io.ooo.recoveryEvent.fire &&
    iex.io.ooo.recoveryEvent.bits.cause === RecoveryCause.Branch)
  io.recoveryEventCount := countWhen(iex.io.ooo.recoveryEvent.fire)
  io.recoveryApplyCount := countWhen(iex.io.ooo.recovery.apply.valid)
  io.iexTerminalTraceCount := countWhen(iex.io.trace.fire)
  io.loadCount := countWhen(loadFire)
  io.loadLaunchCount := countWhen(loadLaunchFire)
  io.loadAttemptLaunchCount := countWhen(loadAttemptLaunchFire)
  io.loadAttemptCancelCount := countWhen(loadRebindFire)
  io.loadResultCount := countWhen(loadResultFire)
  val lastLoadIssueIdentity = RegInit(0.U.asTypeOf(new MemoryIdentity(p)))
  val lastLoadAllocationId = RegInit(
    0.U.asTypeOf(new MemoryTransactionIdentity(p)))
  val lastLoadDestination = RegInit(0.U.asTypeOf(
    new linxcore.top.interface.RenamedDestination(p)))
  val lastLoadDestinationRelativeIndex = RegInit(0.U(p.archRegWidth.W))
  val lastLoadLaunchIdentity = RegInit(0.U.asTypeOf(new MemoryIdentity(p)))
  val lastLoadRebindCurrent = RegInit(0.U.asTypeOf(new MemoryIdentity(p)))
  val lastLoadRebindNext = RegInit(0.U.asTypeOf(new MemoryIdentity(p)))
  val lastLoadResultIdentity = RegInit(0.U.asTypeOf(new MemoryIdentity(p)))
  when(lsu.io.iex.loadAddress.head.fire) {
    lastLoadIssueIdentity := lsu.io.iex.loadAddress.head.bits.identity
    lastLoadAllocationId := lsu.io.iex.loadAllocation.head.bits.allocationId
    lastLoadDestination := lsu.io.iex.loadAddress.head.bits.destination
    lastLoadDestinationRelativeIndex :=
      lsu.io.iex.loadAddress.head.bits.destinationRelativeIndex
  }
  when(lsu.io.iex.loadLaunch.head.valid) {
    lastLoadLaunchIdentity := lsu.io.iex.loadLaunch.head.bits.identity
  }
  when(iex.io.lsu.loadRebindApply.head.fire) {
    lastLoadRebindCurrent := iex.io.lsu.loadRebindApply.head.bits.currentIdentity
    lastLoadRebindNext := iex.io.lsu.loadRebindApply.head.bits.nextIdentity
  }
  when(iex.io.lsu.loadResult.head.fire) {
    lastLoadResultIdentity := iex.io.lsu.loadResult.head.bits.identity
  }
  io.lastLoadIssueIdentity := lastLoadIssueIdentity
  io.lastLoadAllocationId := lastLoadAllocationId
  io.lastLoadDestination := lastLoadDestination
  io.lastLoadDestinationRelativeIndex := lastLoadDestinationRelativeIndex
  io.lastLoadLaunchIdentity := lastLoadLaunchIdentity
  io.firstLoadAttemptLaunchIdentity := firstLoadAttemptLaunch
  io.lastLoadAttemptLaunchIdentity := lastLoadAttemptLaunch
  io.lastLoadRebindCurrent := lastLoadRebindCurrent
  io.lastLoadRebindNext := lastLoadRebindNext
  io.lastLoadResultIdentity := lastLoadResultIdentity
  io.storeAddressCount := countWhen(storeAddressFire)
  io.storeDataCount := countWhen(storeDataFire)
  io.memoryCount := countWhen(memoryFire)
  io.commitCount := countWhen(ooo.io.commit.fire)
  io.traceCount := countWhen(traceFire)
  io.dispatchStallCount := countWhen(anyBlocked(
    iex.io.ooo.aluDispatch.toSeq ++ iex.io.ooo.bruDispatch.toSeq ++
      iex.io.ooo.aguDispatch.toSeq ++ iex.io.ooo.storeDispatch.toSeq ++
      iex.io.ooo.systemDispatch.toSeq ++ iex.io.ooo.cmdDispatch.toSeq) ||
    anyBlocked(ooo.io.systemIssue.toSeq) ||
    (iex.io.cmdIssue.valid && !iex.io.cmdIssue.ready))
  io.memoryStallCount := countWhen(lsu.io.memoryRequest.map(
    port => port.valid && !port.ready).reduce(_ || _))

  val stid0Resolve = ooo.io.iex.robResolve.map(port =>
    port.fire && port.bits.rob.stid === 0.U).reduce(_ || _)
  val stid1Resolve = ooo.io.iex.robResolve.map(port =>
    port.fire && port.bits.rob.stid === 1.U).reduce(_ || _)
  io.stid0Progress := countWhen(stid0Resolve)
  io.stid1Progress := countWhen(stid1Resolve)
  val stid0Commit = ooo.io.commit.fire &&
    ooo.io.commit.bits.entries.zipWithIndex.map { case (entry, index) =>
      index.U < ooo.io.commit.bits.count && entry.instruction.stid === 0.U
    }.reduce(_ || _)
  val stid1Commit = ooo.io.commit.fire &&
    ooo.io.commit.bits.entries.zipWithIndex.map { case (entry, index) =>
      index.U < ooo.io.commit.bits.count && entry.instruction.stid === 1.U
    }.reduce(_ || _)
  io.stid0CommitProgress := countWhen(stid0Commit)
  io.stid1CommitProgress := countWhen(stid1Commit)

  val lastResolveValue = RegInit(0.U(p.dataWidth.W))
  for (port <- ooo.io.iex.robResolve) {
    when(port.fire) { lastResolveValue := port.bits.value }
  }
  io.lastResolveValue := lastResolveValue
  val lastRfWriteValue = RegInit(0.U(p.dataWidth.W))
  when(rfWriteFire) { lastRfWriteValue := iex.io.terminalPWrite.bits.value }
  io.lastRfWriteValue := lastRfWriteValue
  val lastRfWritePtag = RegInit(0.U(
    chisel3.util.log2Ceil(p.ooo.gprPhysRegs).W))
  val lastRfWriteGeneration = RegInit(0.U(
    p.ooo.gprTagGenerationWidth.W))
  when(rfWriteFire) {
    lastRfWritePtag := iex.io.terminalPWrite.bits.ptag
    lastRfWriteGeneration := iex.io.terminalPWrite.bits.generation
  }
  io.lastRfWritePtag := lastRfWritePtag
  io.lastRfWriteGeneration := lastRfWriteGeneration
  val lastBranchTarget = RegInit(0.U(p.pcWidth.W))
  when(iex.io.ooo.recoveryEvent.fire) {
    lastBranchTarget := iex.io.ooo.recoveryEvent.bits.redirectPc
  }
  io.lastBranchTarget := lastBranchTarget
  val lastLoadAddress = RegInit(0.U(p.physicalAddressWidth.W))
  for (port <- lsu.io.iex.loadAddress) {
    when(port.fire) { lastLoadAddress := port.bits.address }
  }
  io.lastLoadAddress := lastLoadAddress
  val lastStoreAddress = RegInit(0.U(p.physicalAddressWidth.W))
  for (port <- lsu.io.iex.storeAddress) {
    when(port.fire) { lastStoreAddress := port.bits.address }
  }
  io.lastStoreAddress := lastStoreAddress
  val lastStoreData = RegInit(0.U(p.dataWidth.W))
  for (port <- lsu.io.iex.storeData) {
    when(port.fire) { lastStoreData := port.bits.data.head }
  }
  io.lastStoreData := lastStoreData
  val lastMemoryId = RegInit(0.U(p.memoryTransactionIdWidth.W))
  val lastMemoryGeneration = RegInit(
    0.U(p.memoryTransactionGenerationWidth.W))
  val lastMemoryAddress = RegInit(0.U(p.physicalAddressWidth.W))
  for (port <- lsu.io.memoryRequest) {
    when(port.fire) {
      lastMemoryId := port.bits.identity.value
      lastMemoryGeneration := port.bits.identity.generation
      lastMemoryAddress := port.bits.address
    }
  }
  io.lastMemoryId := lastMemoryId
  io.lastMemoryGeneration := lastMemoryGeneration
  io.lastMemoryAddress := lastMemoryAddress
}

object EmitOOOIEXLSUActivationProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new OOOIEXLSUActivationProbe(OOOIEXLSUActivationParams.W4),
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info"))
}
