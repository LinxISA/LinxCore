package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid, log2Ceil}
import linxcore.lsu.{STQMemoryClassifyToken, STQSerializedStoreRequest,
  STQSerializedStoreResponse}

/** External production boundary around the retained O3, issue/execute, and
  * canonical store-retirement owners.  Internal ownership handshakes are not
  * re-exported and therefore cannot be acknowledged by placeholder logic.
  */
class OooO3IexStorePipelineIO(
    val p: OooParams,
    val stqEntries: Int,
    val storeCommitQueueEntries: Int,
    val scbEntries: Int) extends Bundle {
  private val scbResponseTxnIdWidth = math.max(1, log2Ceil(scbEntries)) + 2

  val reserve = Flipped(Decoupled(new OooD2GroupedTransaction(p)))
  val cancel = Input(Vec(p.stidCount, Bool()))
  val nonFlushEvidence = Flipped(Decoupled(new OooRobNonFlushEvidence(p)))
  val interruptPending = Input(Vec(p.stidCount, Bool()))
  val commit = Decoupled(new OooRobCommitBatch(p))
  val recoveryRequest = Flipped(Decoupled(new OooGlobalRecoveryRequest(p)))

  val queryStid = Input(UInt(p.stidWidth.W))
  val queryAtag = Input(UInt(p.archRegWidth.W))
  val speculativeMapping = Output(new PMapPayload(p))
  val committedMapping = Output(new PMapPayload(p))

  val issuePolicy = Input(new OooIexIssuePolicy(p))
  val stageCancels = Flipped(Vec(p.iexIssueDomainCount,
    Vec(2, Decoupled(new OooIexStageCancel(p)))))
  val pInit = Flipped(Valid(new OooIexPFileInit(p)))
  val pClear = Flipped(Vec(2, Valid(new OooIexPFileKey(p))))
  val tClear = Flipped(Vec(p.tuAllocationWidth,
    Valid(new OooIexLocalFileKey(p))))
  val uClear = Flipped(Vec(p.tuAllocationWidth,
    Valid(new OooIexLocalFileKey(p))))

  val fastBoundary = Decoupled(new OooFastResolveBoundaryRequest(p))
  val fastTrace = Decoupled(new OooFastResolveTrace(p))
  val multiCycleAlu = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val system = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val pointerAuth = Vec(2, Decoupled(new OooIexExecuteTransaction(p)))
  val floatingVector = Decoupled(new OooIexExecuteTransaction(p))
  val engineCommand = Decoupled(new OooIexExecuteTransaction(p))
  val memoryRequest = Vec(3, Decoupled(new OooIexLoadMemoryRequest(p)))
  val memoryResponse = Flipped(Vec(3,
    Decoupled(new OooIexLoadMemoryResponse(p))))
  val loadCancel = Output(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))
  val bctrl = Vec(p.iexTerminalWidth,
    Decoupled(new OooIexTerminalBctrl(p)))
  val trace = Vec(p.iexTerminalWidth,
    Decoupled(new OooIexTerminalTrace(p)))

  val memoryClassify = Flipped(Decoupled(new STQMemoryClassifyToken(
    stqEntries, p.robGroupsPerStid, p.peIdWidth, p.stidWidth,
    p.nativeBidWidth, p.ridGenerationWidth, p.brobGenerationWidth,
    p.robMemberIndexWidth, p.residentGenerationWidth,
    p.executeSlotGenerationWidth)))
  val storeCommitIssueEnable = Input(Bool())
  val storeEvictEnable = Input(Bool())
  val storeDcacheReady = Input(Bool())
  val storeDcacheWriteHit = Input(Bool())
  val storeDcacheTagHit = Input(Bool())
  val storeL2RequestReady = Input(Bool())
  val storeRawRespValid = Input(Bool())
  val storeRawRespTxnId = Input(UInt(scbResponseTxnIdWidth.W))
  val storeRawRespWrite = Input(Bool())
  val storeRawRespUpgrade = Input(Bool())
  val storeRawRespReady = Output(Bool())
  val serializedStoreRequest = Decoupled(new STQSerializedStoreRequest(
    stqEntries, p.robGroupsPerStid, lsidWidth = p.lsidWidth,
    peIdWidth = p.peIdWidth, stidWidth = p.stidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    leaseGenerationWidth = p.executeSlotGenerationWidth))
  val serializedStoreResponse = Flipped(Decoupled(
    new STQSerializedStoreResponse()))

  val publishFire = Output(Bool())
  val fastTerminalFire = Output(Bool())
  val completionBufferUsed = Output(UInt(p.robCompletionBufferCountWidth.W))
  val storeCommitUsed = Output(UInt(p.storeCommitBufferCountWidth.W))
  val recoveryBusy = Output(Bool())
  val recoveryComplete = Output(Bool())
  val terminalFireMask = Output(UInt(p.iexTerminalWidth.W))
  val transferFireMask = Output(UInt(p.iexReleaseWidth.W))
  val stqOccupiedMask = Output(UInt(stqEntries.W))
  val storeCommitQueueCount = Output(UInt(
    log2Ceil(storeCommitQueueEntries + 1).W))
  val issueEmpty = Output(Bool())
  val executionEmpty = Output(Bool())
  val storeEmpty = Output(Bool())
  val empty = Output(Bool())
  val pProtocolError = Output(Bool())
  val localProtocolError = Output(Bool())
  val storeCommitProtocolError = Output(Bool())
}

/** Production retained path from grouped D2 transactions through O3, typed
  * issue/execute, exact ROB completion, and canonical STQ/CommitQ/SCB store
  * retirement.
  *
  * The composition closes five formerly external ownership seams: S1 publish,
  * IQ release, PTag recycle, all execution completion lanes, and exact ROB
  * store-commit tokens.  PC lookup and global recovery are also private common
  * transactions.  Fast results use their dedicated PRF+wakeup ingress while
  * boundary and trace publication remain explicit architectural sinks.
  */
class OooO3IexStorePipeline(
    val profile: OooIexPhysicalProfile = OooIexLinxPhysicalProfile(),
    val stqEntries: Int = 16,
    val storeCommitQueueEntries: Int = 16,
    val storeCommitIssueWidth: Int = 2,
    val scbEntries: Int = 16,
    val scbResponseBufferDepth: Int = 4,
    val storeLineBytes: Int = 64) extends Module {
  val p = profile.params
  val io = IO(new OooO3IexStorePipelineIO(
    p, stqEntries, storeCommitQueueEntries, scbEntries))

  val o3 = Module(new OooO3RenameCoordinator(
    p, profile.capabilityTopology))
  val iex = Module(new OooIexExecutionStorePipeline(
    profile, stqEntries, storeCommitQueueEntries, storeCommitIssueWidth,
    scbEntries, scbResponseBufferDepth, storeLineBytes))

  o3.io.reserve <> io.reserve
  o3.io.cancel := io.cancel
  o3.io.nonFlushEvidence <> io.nonFlushEvidence
  o3.io.interruptPending := io.interruptPending
  io.commit <> o3.io.commit
  o3.io.recoveryRequest <> io.recoveryRequest
  o3.io.queryStid := io.queryStid
  o3.io.queryAtag := io.queryAtag
  io.speculativeMapping := o3.io.speculativeMapping
  io.committedMapping := o3.io.committedMapping

  iex.io.s1 <> o3.io.iexS1
  o3.io.dispatchReleases <> iex.io.dispatchReleases
  iex.io.ptagRecycle <> o3.io.ptagRecycle
  for (lane <- 0 until p.iexTerminalWidth) {
    o3.io.completions(lane) <> iex.io.completion(lane)
  }
  iex.io.robStoreCommit <> o3.io.storeCommit

  iex.io.recoveryPrepare := o3.io.iexRecoveryPrepare
  o3.io.iexRecoveryPrepareReady := iex.io.recoveryPrepareReady
  o3.io.iexRecoveryPrepared := iex.io.recoveryPrepared
  o3.io.iexRecoveryRejected := iex.io.recoveryRejected
  iex.io.recoveryFire := o3.io.iexRecoveryFire

  for (port <- 0 until p.pcReadPorts) {
    o3.io.pcReadTokens(port) := iex.io.pcReadRequests(port).bits.token
    iex.io.pcReadResponses(port).valid :=
      iex.io.pcReadRequests(port).valid && o3.io.pcReadValid(port)
    iex.io.pcReadResponses(port).bits := o3.io.pcRead(port)
  }

  iex.io.fastWriteback <> o3.io.fastWriteback
  iex.io.fastWakeup <> o3.io.fastWakeup
  io.fastBoundary <> o3.io.fastBoundary
  io.fastTrace <> o3.io.fastTrace

  iex.io.issuePolicy := io.issuePolicy
  iex.io.stageCancels <> io.stageCancels
  iex.io.pInit := io.pInit
  iex.io.pClear := io.pClear
  iex.io.tClear := io.tClear
  iex.io.uClear := io.uClear
  for (index <- 0 until 2) {
    io.multiCycleAlu(index) <> iex.io.multiCycleAlu(index)
    io.system(index) <> iex.io.system(index)
    io.pointerAuth(index) <> iex.io.pointerAuth(index)
  }
  io.floatingVector <> iex.io.floatingVector
  io.engineCommand <> iex.io.engineCommand
  for (index <- 0 until 3) {
    io.memoryRequest(index) <> iex.io.memoryRequest(index)
    iex.io.memoryResponse(index) <> io.memoryResponse(index)
  }
  io.loadCancel := iex.io.loadCancel
  for (lane <- 0 until p.iexTerminalWidth) {
    io.bctrl(lane) <> iex.io.bctrl(lane)
    io.trace(lane) <> iex.io.trace(lane)
  }

  iex.io.memoryClassify <> io.memoryClassify
  iex.io.storeCommitIssueEnable := io.storeCommitIssueEnable
  iex.io.storeEvictEnable := io.storeEvictEnable
  iex.io.storeDcacheReady := io.storeDcacheReady
  iex.io.storeDcacheWriteHit := io.storeDcacheWriteHit
  iex.io.storeDcacheTagHit := io.storeDcacheTagHit
  iex.io.storeL2RequestReady := io.storeL2RequestReady
  iex.io.storeRawRespValid := io.storeRawRespValid
  iex.io.storeRawRespTxnId := io.storeRawRespTxnId
  iex.io.storeRawRespWrite := io.storeRawRespWrite
  iex.io.storeRawRespUpgrade := io.storeRawRespUpgrade
  io.storeRawRespReady := iex.io.storeRawRespReady
  io.serializedStoreRequest <> iex.io.serializedStoreRequest
  iex.io.serializedStoreResponse <> io.serializedStoreResponse

  io.publishFire := o3.io.publishFire
  io.fastTerminalFire := o3.io.fastTerminalFire
  io.completionBufferUsed := o3.io.completionBufferUsed
  io.storeCommitUsed := o3.io.storeCommitUsed
  io.recoveryBusy := o3.io.recoveryBusy
  io.recoveryComplete := o3.io.recoveryComplete
  io.terminalFireMask := iex.io.terminalFireMask
  io.transferFireMask := iex.io.transferFireMask
  io.stqOccupiedMask := iex.io.stqOccupiedMask
  io.storeCommitQueueCount := iex.io.storeCommitQueueCount
  io.issueEmpty := iex.io.issueEmpty
  io.executionEmpty := iex.io.executionEmpty
  io.storeEmpty := iex.io.storeEmpty
  val o3ResidencyEmpty = !VecInit(o3.io.robOccupiedGroups.map(_ =/= 0.U)).asUInt.orR &&
    !VecInit(o3.io.d3UsedGroups.map(_ =/= 0.U)).asUInt.orR &&
    !VecInit(o3.io.fastPendingByStid.map(_ =/= 0.U)).asUInt.orR &&
    o3.io.ptagProvisionalCount === 0.U
  io.empty := iex.io.empty && o3ResidencyEmpty &&
    o3.io.completionBufferUsed === 0.U && o3.io.storeCommitUsed === 0.U &&
    !o3.io.recoveryBusy
  io.pProtocolError := iex.io.pProtocolError
  io.localProtocolError := iex.io.localProtocolError
  io.storeCommitProtocolError := iex.io.storeCommitProtocolError
}
