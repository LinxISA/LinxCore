package linxcore.system

import chisel3._
import chisel3.util._

import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.common.InterfaceParams
import linxcore.rob.ROBID

object ReducedServiceRequestPath {
  val ArgCount: Int = 7
  val A0Index: Int = 0
  val A1Index: Int = 1
  val A2Index: Int = 2
  val A3Index: Int = 3
  val A4Index: Int = 4
  val A5Index: Int = 5
  val A7Index: Int = 6
}

class ReducedServiceRequestPathIssue(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val pc = UInt(p.pcWidth.W)
  val insnLen = UInt(p.lenWidth.W)
  val insnRaw = UInt(p.insnWidth.W)
  val nextInsnLen = UInt(p.lenWidth.W)
  val nextHalfword = UInt(16.W)
  val nextInsnRaw = UInt(p.insnWidth.W)
  val identity = new ReducedServiceRequestIdentity(p)
}

class ReducedServiceRequestPathIO(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  val enqueueValid = Input(Bool())
  val enqueueReady = Output(Bool())
  val enqueueIdentity = Input(new ReducedServiceRenameSnapshotIdentity(p))
  val enqueuePhysTags = Input(Vec(ReducedServiceRequestPath.ArgCount, UInt(p.physRegWidth.W)))

  val issueValid = Input(Bool())
  val issueReady = Output(Bool())
  val issue = Input(new ReducedServiceRequestPathIssue(p))
  val atCommitHead = Input(Bool())

  val rfReadValid = Output(Bool())
  val rfReadReady = Input(Bool())
  val rfReadTag = Output(UInt(p.physRegWidth.W))
  val rfReadIndex = Output(UInt(log2Ceil(ReducedServiceRequestPath.ArgCount).W))
  val rfReadData = Input(UInt(p.immWidth.W))

  val serviceRequest = Decoupled(new ReducedServiceRequestPayload(p))
  val serviceResponse = Flipped(Decoupled(new ReducedServiceRequestResponse(p)))
  val flush = Input(Bool())

  val completeReady = Input(Bool())
  val releaseReady = Input(Bool())
  val writebackReady = Input(Bool())
  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(p.robIndexWidth.W))
  val completeRow = Output(new CommitTraceRow(traceParams))
  val releaseValid = Output(Bool())
  val releaseBid = Output(new ROBID(p.robEntries))
  val releaseRid = Output(new ROBID(p.robEntries))
  val releaseStid = Output(UInt(p.threadIdWidth.W))
  val writebackValid = Output(Bool())
  val writeback = Output(new ReducedServiceWriteback(p))

  val renameFence = Output(Bool())
  val controlFence = Output(Bool())
  val gatherBusy = Output(Bool())
  val ownerBusy = Output(Bool())
  val snapshotLookupMatch = Output(Bool())
  val snapshotLookupMismatch = Output(Bool())
  val trappedInvalidRequestType = Output(Bool())
  val trappedIllegalSequence = Output(Bool())
  val responseIdentityMismatch = Output(Bool())
  val lateResponseSuppressed = Output(Bool())
}

class ReducedServiceRequestPath(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(traceParams.pcWidth == p.pcWidth, "trace PC width must match interface PC width")
  require(traceParams.insnWidth == p.insnWidth, "trace instruction width must match interface instruction width")
  require(traceParams.lenWidth == p.lenWidth, "trace length width must match interface length width")
  require(traceParams.dataWidth == p.immWidth, "trace data width must match interface data width")
  require(traceParams.robValueWidth == p.robIndexWidth, "trace ROB value width must match interface ROB index width")

  import ReducedServiceRequestPath._

  val io = IO(new ReducedServiceRequestPathIO(p, traceParams))

  val snapshot = Module(new ReducedServiceRenameSnapshot(p))
  val owner = Module(new ReducedServiceRequestOwner(p, traceParams))

  val sIdle :: sGather :: sSubmit :: Nil = Enum(3)
  val state = RegInit(sIdle)
  val gatherIndex = RegInit(0.U(log2Ceil(ArgCount).W))
  val gatheredArgs = Reg(Vec(ArgCount, UInt(p.immWidth.W)))
  val pendingIssue = Reg(new ReducedServiceRequestPathIssue(p))
  val pendingTags = Reg(Vec(ArgCount, UInt(p.physRegWidth.W)))

  private def requestToSnapshotIdentity(in: ReducedServiceRequestIdentity): ReducedServiceRenameSnapshotIdentity = {
    val out = Wire(new ReducedServiceRenameSnapshotIdentity(p))
    out.stid := in.stid
    out.bid := in.bid
    out.gid := in.gid
    out.rid := in.rid
    out
  }

  private def identitiesMatch(
      lhs: ReducedServiceRequestIdentity,
      rhs: ReducedServiceRequestIdentity): Bool = {
    (lhs.stid === rhs.stid) &&
      lhs.bid.valid && rhs.bid.valid && ROBID.equal(lhs.bid, rhs.bid) &&
      lhs.gid.valid && rhs.gid.valid && ROBID.equal(lhs.gid, rhs.gid) &&
      lhs.rid.valid && rhs.rid.valid && ROBID.equal(lhs.rid, rhs.rid)
  }

  snapshot.io.captureValid := io.enqueueValid && io.enqueueReady
  snapshot.io.captureIdentity := io.enqueueIdentity
  snapshot.io.capturePhysTags := io.enqueuePhysTags
  val lookupAllowed = io.issueValid && (state === sIdle) && io.atCommitHead && !owner.io.controlFence && !io.flush
  snapshot.io.lookupValid := lookupAllowed
  snapshot.io.lookupIdentity := requestToSnapshotIdentity(io.issue.identity)
  snapshot.io.flush := io.flush

  val ownerCompleted = owner.io.completeValid
  snapshot.io.clearValid := ownerCompleted
  snapshot.io.clearIdentity := requestToSnapshotIdentity(pendingIssue.identity)

  val canCapture = (state === sIdle) && !snapshot.io.occupied && !owner.io.controlFence && !io.flush
  io.enqueueReady := canCapture

  val startGather = snapshot.io.lookupMatch && lookupAllowed
  val rfReadFire = io.rfReadValid && io.rfReadReady
  val lastArg = gatherIndex === (ArgCount - 1).U

  io.rfReadValid := (state === sGather) && !io.flush
  io.rfReadTag := pendingTags(gatherIndex)
  io.rfReadIndex := gatherIndex

  val ownerRequest = WireDefault(0.U.asTypeOf(new ReducedServiceRequestPayload(p)))
  ownerRequest.requestType := pendingIssue.insnRaw(23, 20)
  ownerRequest.pc := pendingIssue.pc
  ownerRequest.insnLen := pendingIssue.insnLen
  ownerRequest.insnRaw := pendingIssue.insnRaw
  ownerRequest.nextInsnLen := pendingIssue.nextInsnLen
  ownerRequest.nextHalfword := pendingIssue.nextHalfword
  ownerRequest.nextInsnRaw := pendingIssue.nextInsnRaw
  ownerRequest.identity := pendingIssue.identity
  ownerRequest.a0 := gatheredArgs(A0Index)
  ownerRequest.a1 := gatheredArgs(A1Index)
  ownerRequest.a2 := gatheredArgs(A2Index)
  ownerRequest.a3 := gatheredArgs(A3Index)
  ownerRequest.a4 := gatheredArgs(A4Index)
  ownerRequest.a5 := gatheredArgs(A5Index)
  ownerRequest.a7 := gatheredArgs(A7Index)
  ownerRequest.a0PhysTag := pendingTags(A0Index)

  val submitIdentityStillMatches = identitiesMatch(io.issue.identity, pendingIssue.identity)
  owner.io.request.valid := (state === sSubmit) && io.issueValid && submitIdentityStillMatches && !io.flush
  owner.io.request.bits := ownerRequest
  io.issueReady := owner.io.request.ready && owner.io.request.valid

  owner.io.serviceRequest <> io.serviceRequest
  owner.io.serviceResponse <> io.serviceResponse
  owner.io.flush := io.flush
  owner.io.completeReady := io.completeReady
  owner.io.releaseReady := io.releaseReady
  owner.io.writebackReady := io.writebackReady

  io.completeValid := owner.io.completeValid
  io.completeRobValue := owner.io.completeRobValue
  io.completeRow := owner.io.completeRow
  io.releaseValid := owner.io.releaseValid
  io.releaseBid := owner.io.releaseBid
  io.releaseRid := owner.io.releaseRid
  io.releaseStid := owner.io.releaseStid
  io.writebackValid := owner.io.writebackValid
  io.writeback := owner.io.writeback

  io.renameFence := snapshot.io.occupied
  io.controlFence := snapshot.io.occupied || (state =/= sIdle) || owner.io.controlFence
  io.gatherBusy := state === sGather
  io.ownerBusy := owner.io.busy
  io.snapshotLookupMatch := snapshot.io.lookupMatch
  io.snapshotLookupMismatch := snapshot.io.lookupMismatch
  io.trappedInvalidRequestType := owner.io.trappedInvalidRequestType
  io.trappedIllegalSequence := owner.io.trappedIllegalSequence
  io.responseIdentityMismatch := owner.io.responseIdentityMismatch
  io.lateResponseSuppressed := owner.io.lateResponseSuppressed

  when(io.flush) {
    state := sIdle
    gatherIndex := 0.U
  }.elsewhen(startGather) {
    pendingIssue := io.issue
    pendingTags := snapshot.io.lookupPhysTags
    gatherIndex := 0.U
    state := sGather
  }.elsewhen(state === sGather && rfReadFire) {
    gatheredArgs(gatherIndex) := io.rfReadData
    when(lastArg) {
      state := sSubmit
    }.otherwise {
      gatherIndex := gatherIndex + 1.U
    }
  }.elsewhen(owner.io.request.fire) {
    state := sIdle
    gatherIndex := 0.U
  }
}
