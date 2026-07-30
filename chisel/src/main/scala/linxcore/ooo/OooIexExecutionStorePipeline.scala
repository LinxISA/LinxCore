package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid, log2Ceil}

import linxcore.lsu.STQEntryBankRow

/** Canonical external boundary for the scalar/control execution cluster with
  * its production store queue owner connected.
  *
  * Store reservation, STA, and STD are private connections.  Recovery is
  * published ready only when both IQ residency and the complete STQ/store
  * projection can participate in the same common fire.
  */
class OooIexExecutionStorePipelineIO(
    val p: OooParams,
    val stqEntries: Int) extends Bundle {
  val s1 = Flipped(Decoupled(new OooIexS1Transaction(p)))
  val dispatchReleases = Vec(p.iexReleaseWidth,
    Decoupled(new OooDispatchRelease(p)))
  val ptagRecycle = Flipped(Decoupled(new OooPTagReturnBatch(p)))

  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Output(new OooIexRecoveryPrepared(p))
  val recoveryRejected = Output(Valid(new OooIexRecoveryReject(p)))
  val recoveryFire = Input(Bool())

  val issuePolicy = Input(new OooIexIssuePolicy(p))
  val stageCancels = Flipped(Vec(p.iexIssueDomainCount,
    Vec(2, Decoupled(new OooIexStageCancel(p)))))

  val pcReadRequests = Output(Vec(p.pcReadPorts,
    Valid(new OooIexPcReadPortRequest(p))))
  val pcReadResponses = Input(Vec(p.pcReadPorts,
    Valid(UInt(p.pcWidth.W))))
  val pInit = Flipped(Valid(new OooIexPFileInit(p)))
  val pClear = Flipped(Vec(2, Valid(new OooIexPFileKey(p))))
  val tClear = Flipped(Vec(p.tuAllocationWidth,
    Valid(new OooIexLocalFileKey(p))))
  val uClear = Flipped(Vec(p.tuAllocationWidth,
    Valid(new OooIexLocalFileKey(p))))

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
  val completion = Vec(p.iexTerminalWidth,
    Decoupled(new OooRobMemberCompletion(p)))

  val markStoreCommitValid = Input(Bool())
  val markStoreCommitIndex = Input(UInt(log2Ceil(stqEntries).W))
  val markStoreCommitAccepted = Output(Bool())
  val storeCommitFreeMaskValid = Input(Bool())
  val storeCommitFreeMask = Input(UInt(stqEntries.W))
  val storeCommitFreeAcceptedMask = Output(UInt(stqEntries.W))

  val stqRows = Output(Vec(stqEntries, new STQEntryBankRow(
    p.robGroupsPerStid,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.stidWidth,
    mapQDepth = p.tuMapQDepthPerStid,
    lsidWidth = p.lsidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    leaseGenerationWidth = p.executeSlotGenerationWidth)))
  val stqOccupiedMask = Output(UInt(stqEntries.W))
  val stqAddressReadyMask = Output(UInt(stqEntries.W))
  val stqDataReadyMask = Output(UInt(stqEntries.W))
  val stqResidentCount = Output(UInt(log2Ceil(stqEntries + 1).W))
  val storePipelinesOccupied = Output(UInt(2.W))
  val storeReserveAccepted = Output(Bool())
  val storeReserveRejected = Output(Bool())
  val storeLeaseLookupRejected = Output(Vec(4, Bool()))
  val storeFillConflict = Output(Bool())
  val storeRecoveryFreeMask = Output(UInt(stqEntries.W))
  val storeRecoveryBlockedMask = Output(UInt(stqEntries.W))
  val storeRecoveryPartialCut = Output(Bool())

  val terminalFireMask = Output(UInt(p.iexTerminalWidth.W))
  val transferFireMask = Output(UInt(p.iexReleaseWidth.W))
  val pWriteFire = Output(Vec(p.iexPWritePorts, Bool()))
  val tWriteFire = Output(Vec(p.iexTWritePorts, Bool()))
  val uWriteFire = Output(Vec(p.iexUWritePorts, Bool()))
  val routeRejected = Output(Vec(
    OooIexLinxPhysicalProfile.ExecutionLaneCount,
    Valid(new OooIexExecutionRouteReject(p))))
  val terminalRejected = Output(Vec(p.iexTerminalWidth,
    Vec(3, Valid(new OooIexTerminalReject(p)))))
  val boundEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val residentEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val inFlightEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val issueEmpty = Output(Bool())
  val executionEmpty = Output(Bool())
  val storeEmpty = Output(Bool())
  val empty = Output(Bool())
  val pProtocolError = Output(Bool())
  val localProtocolError = Output(Bool())
}

class OooIexExecutionStoreRecoveryJoinIO(val p: OooParams) extends Bundle {
  val requested = Input(Valid(new OooResidencyRecoveryPlan(p)))
  val executionReady = Input(Bool())
  val executionPrepared = Input(new OooIexRecoveryPrepared(p))
  val executionRejected = Input(Valid(new OooIexRecoveryReject(p)))
  val storeReady = Input(Bool())
  val storeRejected = Input(Bool())
  val fire = Input(Bool())

  val prepareReady = Output(Bool())
  val prepared = Output(new OooIexRecoveryPrepared(p))
  val rejected = Output(Valid(new OooIexRecoveryReject(p)))
  val commonFire = Output(Bool())
}

/** Stateless rendezvous for the issue/execution and STQ/store recovery
  * projections. Neither owner sees an apply edge until both have prepared the
  * same held request.
  */
class OooIexExecutionStoreRecoveryJoin(val p: OooParams) extends Module {
  val io = IO(new OooIexExecutionStoreRecoveryJoinIO(p))

  io.prepareReady := io.requested.valid && io.executionReady &&
    io.executionPrepared.valid && io.storeReady
  io.prepared := io.executionPrepared
  io.prepared.valid := io.prepareReady
  io.commonFire := io.fire && io.prepareReady

  io.rejected := io.executionRejected
  when(!io.executionRejected.valid && io.requested.valid && io.storeRejected) {
    io.rejected.valid := true.B
    io.rejected.bits.requested := io.requested.bits
    io.rejected.bits.stidInRange :=
      io.requested.bits.oldHead.stid < p.stidCount.U
    io.rejected.bits.residentRowsExact := false.B
    io.rejected.bits.s1RowsExact := true.B
  }
}

class OooIexExecutionStorePipeline(
    val profile: OooIexPhysicalProfile = OooIexLinxPhysicalProfile(),
    val stqEntries: Int = 16) extends Module {
  val p = profile.params
  val io = IO(new OooIexExecutionStorePipelineIO(p, stqEntries))

  val execution = Module(new OooIexExecutionPipeline(
    profile, requireStoreReservation = true))
  val store = Module(new OooIexStoreStqFabric(p, stqEntries))

  execution.io.s1 <> io.s1
  io.dispatchReleases <> execution.io.dispatchReleases
  execution.io.ptagRecycle <> io.ptagRecycle
  execution.io.issuePolicy := io.issuePolicy
  execution.io.stageCancels <> io.stageCancels
  io.pcReadRequests := execution.io.pcReadRequests
  execution.io.pcReadResponses := io.pcReadResponses
  execution.io.pInit := io.pInit
  execution.io.pClear := io.pClear
  execution.io.tClear := io.tClear
  execution.io.uClear := io.uClear

  execution.io.storeReserve.get <> store.io.reserve
  store.io.storeAddress <> execution.io.storeAddress
  store.io.storeData <> execution.io.storeData
  store.io.loadCancel := execution.io.loadCancel

  execution.io.recoveryPrepare := io.recoveryPrepare
  store.io.recoveryPrepare := io.recoveryPrepare
  val recoveryJoin = Module(new OooIexExecutionStoreRecoveryJoin(p))
  recoveryJoin.io.requested := io.recoveryPrepare
  recoveryJoin.io.executionReady := execution.io.recoveryPrepareReady
  recoveryJoin.io.executionPrepared := execution.io.recoveryPrepared
  recoveryJoin.io.executionRejected := execution.io.recoveryRejected
  recoveryJoin.io.storeReady := store.io.recoveryPrepareReady
  recoveryJoin.io.storeRejected := store.io.recoveryRejected
  recoveryJoin.io.fire := io.recoveryFire
  io.recoveryPrepareReady := recoveryJoin.io.prepareReady
  io.recoveryPrepared := recoveryJoin.io.prepared
  io.recoveryRejected := recoveryJoin.io.rejected
  execution.io.recoveryFire := recoveryJoin.io.commonFire
  store.io.recoveryFire := recoveryJoin.io.commonFire

  for (index <- 0 until 2) {
    io.multiCycleAlu(index) <> execution.io.multiCycleAlu(index)
    io.system(index) <> execution.io.system(index)
    io.pointerAuth(index) <> execution.io.pointerAuth(index)
  }
  io.floatingVector <> execution.io.floatingVector
  io.engineCommand <> execution.io.engineCommand
  for (index <- 0 until 3) {
    io.memoryRequest(index) <> execution.io.memoryRequest(index)
    execution.io.memoryResponse(index) <> io.memoryResponse(index)
  }
  io.loadCancel := execution.io.loadCancel
  for (lane <- 0 until p.iexTerminalWidth) {
    io.bctrl(lane) <> execution.io.bctrl(lane)
    io.trace(lane) <> execution.io.trace(lane)
    io.completion(lane) <> execution.io.completion(lane)
  }

  store.io.markCommitValid := io.markStoreCommitValid
  store.io.markCommitIndex := io.markStoreCommitIndex
  io.markStoreCommitAccepted := store.io.markCommitAccepted
  store.io.commitFreeMaskValid := io.storeCommitFreeMaskValid
  store.io.commitFreeMask := io.storeCommitFreeMask
  io.storeCommitFreeAcceptedMask := store.io.commitFreeAcceptedMask

  io.stqRows := store.io.rows
  io.stqOccupiedMask := store.io.occupiedMask
  io.stqAddressReadyMask := store.io.addrReadyMask
  io.stqDataReadyMask := store.io.dataReadyMask
  io.stqResidentCount := store.io.residentCount
  io.storePipelinesOccupied := store.io.storePipelinesOccupied
  io.storeReserveAccepted := store.io.reserveAccepted
  io.storeReserveRejected := store.io.reserveRejected
  io.storeLeaseLookupRejected := store.io.leaseLookupRejected
  io.storeFillConflict := store.io.fillConflict
  io.storeRecoveryFreeMask := store.io.recoveryFreeMask
  io.storeRecoveryBlockedMask := store.io.recoveryBlockedMask
  io.storeRecoveryPartialCut := store.io.recoveryPartialStoreCut

  io.terminalFireMask := execution.io.terminalFireMask
  io.transferFireMask := execution.io.transferFireMask
  io.pWriteFire := execution.io.pWriteFire
  io.tWriteFire := execution.io.tWriteFire
  io.uWriteFire := execution.io.uWriteFire
  io.routeRejected := execution.io.routeRejected
  io.terminalRejected := execution.io.terminalRejected
  io.boundEntries := execution.io.boundEntries
  io.residentEntries := execution.io.residentEntries
  io.inFlightEntries := execution.io.inFlightEntries
  io.issueEmpty := execution.io.issueEmpty
  io.executionEmpty := execution.io.executionEmpty
  io.storeEmpty := store.io.empty
  io.empty := execution.io.empty && store.io.empty
  io.pProtocolError := execution.io.pProtocolError
  io.localProtocolError := execution.io.localProtocolError

  when(io.recoveryFire) {
    assert(io.recoveryPrepare.valid && recoveryJoin.io.prepareReady,
      "execution/store recovery needs one common prepared owner set")
  }
}
