package linxcore.bctrl

import circt.stage.ChiselStage
import chisel3._
import chisel3.util.{Decoupled, Mux1H}
import linxcore.commit.CommitTraceParams
import linxcore.common._
import linxcore.execute.ScalarIssueFabric
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rename.ScalarDecodeRenameBridge

class TemplateHandoffProbeKey(val p: InterfaceParams) extends Bundle {
  val generation = UInt(16.W)
  val stid = UInt(p.threadIdWidth.W)
  val bidValid = Bool()
  val bidWrap = Bool()
  val bidValue = UInt(p.robIndexWidth.W)
  val gidValid = Bool()
  val gidWrap = Bool()
  val gidValue = UInt(p.robIndexWidth.W)
  val ridValid = Bool()
  val ridWrap = Bool()
  val ridValue = UInt(p.robIndexWidth.W)
  val robSlot = UInt(p.robIndexWidth.W)
}

class TemplateAcceptedSidecarHandoffProbeIO(
    val p: InterfaceParams,
    val archRegs: Int,
    val physTagWidth: Int,
    val stidCount: Int)
    extends Bundle {
  val acceptValid = Input(Bool())
  val acceptOpcode = Input(UInt(p.opcodeWidth.W))
  val acceptStid = Input(UInt(p.threadIdWidth.W))
  val acceptPeId = Input(UInt(p.peIdWidth.W))
  val acceptPc = Input(UInt(p.pcWidth.W))
  val acceptRaw = Input(UInt(p.insnWidth.W))
  val acceptImm = Input(UInt(p.immWidth.W))
  val acceptRangeM = Input(UInt(p.archRegWidth.W))
  val acceptRangeN = Input(UInt(p.archRegWidth.W))
  val acceptBidValid = Input(Bool())
  val acceptBidWrap = Input(Bool())
  val acceptBidValue = Input(UInt(p.robIndexWidth.W))
  val acceptGidValid = Input(Bool())
  val acceptGidWrap = Input(Bool())
  val acceptGidValue = Input(UInt(p.robIndexWidth.W))
  val acceptRidValid = Input(Bool())
  val acceptRidWrap = Input(Bool())
  val acceptRidValue = Input(UInt(p.robIndexWidth.W))
  val acceptUid = Input(UInt(p.uopUidWidth.W))
  val acceptBlockBidValid = Input(Bool())
  val acceptBlockBid = Input(UInt(p.blockBidWidth.W))
  val robAllocReady = Input(Bool())

  val readyMask = Input(UInt((1 << physTagWidth).W))
  val pWakeupValid = Input(Bool())
  val pWakeupTag = Input(UInt(physTagWidth.W))
  val localTReadyMask = Input(UInt(4.W))
  val localUReadyMask = Input(UInt(4.W))
  val readData = Input(Vec(3, UInt(p.immWidth.W)))
  val scalarSpHeadValid = Input(Vec(stidCount, Bool()))
  val scalarSpHeadBidValue = Input(Vec(stidCount, UInt(p.robIndexWidth.W)))
  val scalarSpHeadRidValue = Input(Vec(stidCount, UInt(p.robIndexWidth.W)))
  val scalarSpSnapshot = Input(Vec(stidCount, UInt(p.immWidth.W)))

  // Each bit flips one field after the request is built from the real issue
  // payload. This is stateless fault injection, not an identity matcher.
  val issueKeyFaultMask = Input(UInt(12.W))
  val parentRequestReadyOverride = Input(Bool())
  val cancelValid = Input(Bool())
  val cancelKey = Input(new TemplateHandoffProbeKey(p))
  val releaseDecodeValid = Input(Bool())
  val releaseDecodeKey = Input(new TemplateHandoffProbeKey(p))
  val releaseIssueValid = Input(Bool())
  val releaseIssueKey = Input(new TemplateHandoffProbeKey(p))
  val releaseMemoryValid = Input(Bool())
  val releaseMemoryKey = Input(new TemplateHandoffProbeKey(p))

  val selectedTemplate = Input(Bool())
  val parentCommitValid = Input(Bool())
  val parentCommitKey = Input(new TemplateHandoffProbeKey(p))
  val storeGrant = Input(Bool())
  val recoveryValid = Input(Bool())
  val recoveryKey = Input(new TemplateHandoffProbeKey(p))
  val recoveryKillsActive = Input(Bool())
  val globalClear = Input(Bool())
  val rfReadReady = Input(Bool())
  val rfReadResponseValid = Input(Bool())
  val rfReadResponseChildIndex = Input(UInt(5.W))
  val rfReadResponsePhysTag = Input(UInt(physTagWidth.W))
  val rfReadResponseData = Input(UInt(p.immWidth.W))
  val loadReady = Input(Bool())
  val loadResponseValid = Input(Bool())
  val loadResponseChildIndex = Input(UInt(5.W))
  val loadResponseAddr = Input(UInt(p.immWidth.W))
  val loadResponseData = Input(UInt(p.immWidth.W))
  val storeReady = Input(Bool())
  val rfWriteReady = Input(Bool())

  val bridgeAccepted = Output(Bool())
  val bridgeInReady = Output(Bool())
  val bridgeOutValid = Output(Bool())
  val bridgeTemplateSnapshotValid = Output(Bool())
  val bridgeTemplateSnapshotGeneration = Output(UInt(16.W))
  val bridgeTemplateSmap = Output(Vec(archRegs, UInt(physTagWidth.W)))
  val bridgeTemplateCmap = Output(Vec(archRegs, UInt(physTagWidth.W)))
  val sidecarValid = Output(Bool())
  val sidecarReady = Output(Bool())
  val sidecarFire = Output(Bool())
  val sidecarGeneration = Output(UInt(16.W))
  val sidecarStid = Output(UInt(p.threadIdWidth.W))
  val sidecarRaw = Output(UInt(p.insnWidth.W))
  val sidecarRangeM = Output(UInt(p.archRegWidth.W))
  val sidecarRangeN = Output(UInt(p.archRegWidth.W))
  val sidecarSrc0Imm = Output(UInt(p.immWidth.W))
  val sidecarSmap = Output(Vec(archRegs, UInt(physTagWidth.W)))
  val sidecarCmap = Output(Vec(archRegs, UInt(physTagWidth.W)))

  val issueInputReady = Output(Bool())
  val issueValid = Output(Bool())
  val issueReady = Output(Bool())
  val issueFire = Output(Bool())
  val issuedTemplateGenerationValid = Output(Bool())
  val issuedTemplateGeneration = Output(UInt(16.W))
  val issuedStid = Output(UInt(p.threadIdWidth.W))
  val issuedRaw = Output(UInt(p.insnWidth.W))
  val issuedBidValue = Output(UInt(p.robIndexWidth.W))
  val issuedGidValue = Output(UInt(p.robIndexWidth.W))
  val issuedRidValue = Output(UInt(p.robIndexWidth.W))
  val issuedSrcData0 = Output(UInt(p.immWidth.W))
  val parentRequestValid = Output(Bool())
  val parentRequestReady = Output(Bool())
  val parentTransfer = Output(Bool())
  val sidecarOccupiedMask = Output(UInt(stidCount.W))
  val sidecarDecodeFenceMask = Output(UInt(stidCount.W))
  val sidecarIssueFenceMask = Output(UInt(stidCount.W))
  val sidecarMemoryFenceMask = Output(UInt(stidCount.W))
  val enqueueCount = Output(UInt(32.W))
  val transferCount = Output(UInt(32.W))
  val mismatchDropCount = Output(UInt(32.W))
  val staleGenerationDropCount = Output(UInt(32.W))

  val sequencerActive = Output(Bool())
  val activeGeneration = Output(UInt(16.W))
  val activeStid = Output(UInt(p.threadIdWidth.W))
  val activePeId = Output(UInt(p.peIdWidth.W))
  val activePc = Output(UInt(p.pcWidth.W))
  val activeOpcode = Output(UInt(p.opcodeWidth.W))
  val activeRaw = Output(UInt(p.insnWidth.W))
  val activeBidValid = Output(Bool())
  val activeBidWrap = Output(Bool())
  val activeBidValue = Output(UInt(p.robIndexWidth.W))
  val activeGidValid = Output(Bool())
  val activeGidWrap = Output(Bool())
  val activeGidValue = Output(UInt(p.robIndexWidth.W))
  val activeRidValid = Output(Bool())
  val activeRidWrap = Output(Bool())
  val activeRidValue = Output(UInt(p.robIndexWidth.W))
  val activeRobSlot = Output(UInt(p.robIndexWidth.W))
  val activeBlockBidValid = Output(Bool())
  val activeBlockBid = Output(UInt(p.blockBidWidth.W))
  val activeCommitBid = Output(UInt(32.W))
  val activeCommitGid = Output(UInt(32.W))
  val activeCommitRid = Output(UInt(32.W))
  val activeSmap = Output(Vec(archRegs, UInt(physTagWidth.W)))
  val activeCmap = Output(Vec(archRegs, UInt(physTagWidth.W)))
  val activeOldSp = Output(UInt(p.immWidth.W))
  val activeSrcData = Output(Vec(3, UInt(p.immWidth.W)))
  val rfReadRequestValid = Output(Bool())
  val rfReadRequestChildIndex = Output(UInt(5.W))
  val rfReadRequestArchReg = Output(UInt(p.archRegWidth.W))
  val rfReadRequestPhysTag = Output(UInt(physTagWidth.W))
  val loadRequestValid = Output(Bool())
  val loadRequestChildIndex = Output(UInt(5.W))
  val loadRequestArchReg = Output(UInt(p.archRegWidth.W))
  val loadRequestAddr = Output(UInt(p.immWidth.W))
  val decodeFence = Output(Bool())
  val issueFence = Output(Bool())
  val memoryFence = Output(Bool())
  val completionValid = Output(Bool())
  val completionResult = Output(UInt(p.immWidth.W))
  val completionNewSp = Output(UInt(p.immWidth.W))
  val terminal = Output(Bool())
  val selfRestartObserved = Output(Bool())
  val cancelObserved = Output(Bool())
}

class TemplateAcceptedSidecarHandoffProbe extends Module {
  private val p = InterfaceParams()
  private val traceParams = CommitTraceParams()
  private val archRegs = 24
  private val physTagWidth = p.physRegWidth
  private val stidCount = 2

  val io = IO(new TemplateAcceptedSidecarHandoffProbeIO(p, archRegs, physTagWidth, stidCount))

  private def isTemplate(opcode: UInt): Bool =
    opcode === FrontendOpcodeDecodeTable.OP_FENTRY.U ||
      opcode === FrontendOpcodeDecodeTable.OP_FEXIT.U ||
      opcode === FrontendOpcodeDecodeTable.OP_FRET_RA.U ||
      opcode === FrontendOpcodeDecodeTable.OP_FRET_STK.U

  private def assignRobId(
      dst: linxcore.rob.ROBID,
      valid: Bool,
      wrap: Bool,
      value: UInt): Unit = {
    dst.valid := valid
    dst.wrap := wrap
    dst.value := value
  }

  private def assignKey(
      dst: TemplateParentIdentity,
      key: TemplateHandoffProbeKey,
      template: TemplateParentIdentity): Unit = {
    dst := template
    dst.generation := key.generation
    dst.stid := key.stid
    assignRobId(dst.bid, key.bidValid, key.bidWrap, key.bidValue)
    assignRobId(dst.gid, key.gidValid, key.gidWrap, key.gidValue)
    assignRobId(dst.rid, key.ridValid, key.ridWrap, key.ridValue)
    dst.robSlot := key.robSlot
  }

  val bridge = Module(new ScalarDecodeRenameBridge(
    p = p,
    traceParams = traceParams,
    scalarArchRegs = archRegs,
    scalarStidCount = stidCount
  ))
  val issue = Module(new ScalarIssueFabric(p = p, depth = 8, bankCount = 2, stidCount = stidCount))
  val sidecar = Module(new TemplateRenameSidecarTable(p, archRegs, physTagWidth, stidCount))
  val sequencer = Module(new BlockControlTemplateSequencer(p, archRegs, physTagWidth))

  val decoded = Wire(new DecodedUop(p))
  decoded := 0.U.asTypeOf(decoded)
  decoded.valid := io.acceptValid
  decoded.peId := io.acceptPeId
  decoded.threadId := io.acceptStid
  decoded.pc := io.acceptPc
  decoded.opcode := io.acceptOpcode
  decoded.uopType := 0.U
  decoded.imm := io.acceptImm
  decoded.immValid := true.B
  decoded.src(0).valid := true.B
  decoded.src(0).operandClass := OperandClass.P
  decoded.src(0).archTag := io.acceptRangeM
  decoded.src(0).relTag := io.acceptRangeM
  decoded.src(1).valid := true.B
  decoded.src(1).operandClass := OperandClass.P
  decoded.src(1).archTag := io.acceptRangeN
  decoded.src(1).relTag := io.acceptRangeN
  decoded.src(2).valid := true.B
  decoded.src(2).operandClass := OperandClass.P
  decoded.src(2).archTag := 2.U
  decoded.src(2).relTag := 2.U
  decoded.dst(0).valid := false.B
  decoded.dst(0).kind := DestinationKind.Gpr
  assignRobId(decoded.bid, io.acceptBidValid, io.acceptBidWrap, io.acceptBidValue)
  assignRobId(decoded.gid, io.acceptGidValid, io.acceptGidWrap, io.acceptGidValue)
  assignRobId(decoded.rid, io.acceptRidValid, io.acceptRidWrap, io.acceptRidValue)
  decoded.insnLen := 4.U
  decoded.insnRaw := io.acceptRaw
  decoded.blockBidValid := io.acceptBlockBidValid
  decoded.blockBid := io.acceptBlockBid
  decoded.uid.uid := io.acceptUid

  bridge.io.in := decoded
  bridge.io.activeStid := io.acceptStid
  val acceptedTemplateSidecar = Wire(Decoupled(new TemplateRenameSidecar(p, archRegs, physTagWidth)))
  bridge.io.outReady := issue.io.inReady && Mux(isTemplate(io.acceptOpcode), acceptedTemplateSidecar.ready, true.B)
  bridge.io.robAllocReady := io.robAllocReady
  bridge.io.checkpointValid := false.B
  bridge.io.checkpointBid := 0.U.asTypeOf(bridge.io.checkpointBid)
  bridge.io.checkpointStid := 0.U
  bridge.io.commitValid := false.B
  bridge.io.commitBid := 0.U.asTypeOf(bridge.io.commitBid)
  bridge.io.commitBlockBid := 0.U
  bridge.io.commitStid := 0.U
  bridge.io.cleanup := 0.U.asTypeOf(new RecoveryCleanupIntent(p.robEntries))
  bridge.io.cleanupOrderValid := false.B
  bridge.io.cleanupOrder := 0.U

  acceptedTemplateSidecar.valid := bridge.io.templateSnapshotValid
  acceptedTemplateSidecar.bits := 0.U.asTypeOf(acceptedTemplateSidecar.bits)
  acceptedTemplateSidecar.bits.identity.generation := bridge.io.templateSnapshotGeneration
  acceptedTemplateSidecar.bits.identity.stid := io.acceptStid
  acceptedTemplateSidecar.bits.identity.peId := io.acceptPeId
  acceptedTemplateSidecar.bits.identity.pc := io.acceptPc
  acceptedTemplateSidecar.bits.identity.raw := io.acceptRaw
  acceptedTemplateSidecar.bits.identity.opcode := io.acceptOpcode
  acceptedTemplateSidecar.bits.identity.bid := decoded.bid
  acceptedTemplateSidecar.bits.identity.gid := decoded.gid
  acceptedTemplateSidecar.bits.identity.rid := decoded.rid
  acceptedTemplateSidecar.bits.identity.robSlot := decoded.rid.value
  acceptedTemplateSidecar.bits.identity.blockBidValid := io.acceptBlockBidValid
  acceptedTemplateSidecar.bits.identity.blockBid := io.acceptBlockBid
  acceptedTemplateSidecar.bits.identity.commitIdentityBid := io.acceptBidValue
  acceptedTemplateSidecar.bits.identity.commitIdentityGid := io.acceptGidValue
  acceptedTemplateSidecar.bits.identity.commitIdentityRid := io.acceptRidValue
  acceptedTemplateSidecar.bits.src0Imm := io.acceptImm
  acceptedTemplateSidecar.bits.rangeM := io.acceptRangeM
  acceptedTemplateSidecar.bits.rangeN := io.acceptRangeN
  acceptedTemplateSidecar.bits.map.smap := bridge.io.templateSmapSnapshot
  acceptedTemplateSidecar.bits.map.cmap := bridge.io.templateCmapSnapshot
  sidecar.io.enqueue <> acceptedTemplateSidecar

  issue.io.inValid := bridge.io.outValid
  issue.io.in := bridge.io.out
  issue.io.flushValid := false.B
  issue.io.releaseValid := false.B
  issue.io.releaseBid := 0.U.asTypeOf(issue.io.releaseBid)
  issue.io.releaseRid := 0.U.asTypeOf(issue.io.releaseRid)
  issue.io.releaseStid := 0.U
  issue.io.secondaryReleaseValid := false.B
  issue.io.secondaryReleaseBid := 0.U.asTypeOf(issue.io.secondaryReleaseBid)
  issue.io.secondaryReleaseRid := 0.U.asTypeOf(issue.io.secondaryReleaseRid)
  issue.io.secondaryReleaseStid := 0.U
  issue.io.tertiaryReleaseValid := false.B
  issue.io.tertiaryReleaseBid := 0.U.asTypeOf(issue.io.tertiaryReleaseBid)
  issue.io.tertiaryReleaseRid := 0.U.asTypeOf(issue.io.tertiaryReleaseRid)
  issue.io.tertiaryReleaseStid := 0.U
  issue.io.externalControlFenceValid := false.B
  issue.io.externalControlFenceBid := 0.U.asTypeOf(issue.io.externalControlFenceBid)
  issue.io.externalControlFenceRid := 0.U.asTypeOf(issue.io.externalControlFenceRid)
  issue.io.externalControlFenceStid := 0.U
  for (stid <- 0 until stidCount) {
    issue.io.scalarSpHeadValidByStid(stid) := io.scalarSpHeadValid(stid)
    assignRobId(
      issue.io.scalarSpHeadBidByStid(stid),
      true.B,
      io.acceptBidWrap,
      io.scalarSpHeadBidValue(stid)
    )
    assignRobId(
      issue.io.scalarSpHeadRidByStid(stid),
      true.B,
      io.acceptRidWrap,
      io.scalarSpHeadRidValue(stid)
    )
    issue.io.scalarSpSnapshotByStid(stid) := io.scalarSpSnapshot(stid)
  }
  issue.io.readyMask := io.readyMask
  issue.io.pWakeupValid := io.pWakeupValid
  issue.io.pWakeupTag := io.pWakeupTag
  issue.io.localTReadyMask := io.localTReadyMask
  issue.io.localUReadyMask := io.localUReadyMask
  issue.io.readData := io.readData

  val issued = issue.io.issueUop
  val issuedTemplate = isTemplate(issued.opcode)
  val acceptedGenerationValidByStid = RegInit(VecInit(Seq.fill(stidCount)(false.B)))
  val acceptedGenerationByStid = Reg(Vec(stidCount, UInt(16.W)))
  val issuedStidMatches = VecInit((0 until stidCount).map(idx => issued.threadId === idx.U(p.threadIdWidth.W)))
  val issuedStidInRange = issuedStidMatches.asUInt.orR
  val issueTemplateGenerationValid =
    issuedTemplate && issuedStidInRange && Mux1H(issuedStidMatches, acceptedGenerationValidByStid)
  val issueTemplateGeneration = Mux1H(issuedStidMatches, acceptedGenerationByStid)

  when(acceptedTemplateSidecar.fire) {
    for (idx <- 0 until stidCount) {
      when(acceptedTemplateSidecar.bits.identity.stid === idx.U(p.threadIdWidth.W)) {
        acceptedGenerationValidByStid(idx) := true.B
        acceptedGenerationByStid(idx) := acceptedTemplateSidecar.bits.identity.generation
      }
    }
  }
  when(io.cancelValid) {
    for (idx <- 0 until stidCount) {
      when(io.cancelKey.stid === idx.U(p.threadIdWidth.W)) {
        acceptedGenerationValidByStid(idx) := false.B
      }
    }
  }
  when(io.globalClear) {
    acceptedGenerationValidByStid := VecInit(Seq.fill(stidCount)(false.B))
  }

  val issueRequest = Wire(new TemplateIssueRequest(p))
  issueRequest := 0.U.asTypeOf(issueRequest)
  issueRequest.identity.generation := issueTemplateGeneration
  issueRequest.identity.stid := issued.threadId
  issueRequest.identity.peId := issued.peId
  issueRequest.identity.pc := issued.pc
  issueRequest.identity.raw := issued.insnRaw
  issueRequest.identity.opcode := issued.opcode
  issueRequest.identity.bid := issued.bid
  issueRequest.identity.gid := issued.gid
  issueRequest.identity.rid := issued.rid
  issueRequest.identity.robSlot := issued.rid.value
  issueRequest.identity.blockBidValid := issued.blockBidValid
  issueRequest.identity.blockBid := issued.blockBid
  issueRequest.identity.commitIdentityBid := issued.bid.value
  issueRequest.identity.commitIdentityGid := issued.gid.value
  issueRequest.identity.commitIdentityRid := issued.rid.value
  issueRequest.oldSp := issue.io.scalarSpIssueSnapshot
  issueRequest.srcData0 := issue.io.issueSrcData(0)
  issueRequest.srcData1 := issue.io.issueSrcData(1)
  issueRequest.srcData2 := issue.io.issueSrcData(2)

  when(io.issueKeyFaultMask(0)) {
    issueRequest.identity.stid := issued.threadId ^ 1.U
  }
  when(io.issueKeyFaultMask(1)) {
    issueRequest.identity.bid.valid := !issued.bid.valid
  }
  when(io.issueKeyFaultMask(2)) {
    issueRequest.identity.bid.wrap := !issued.bid.wrap
  }
  when(io.issueKeyFaultMask(3)) {
    issueRequest.identity.bid.value := issued.bid.value ^ 1.U
  }
  when(io.issueKeyFaultMask(4)) {
    issueRequest.identity.gid.valid := !issued.gid.valid
  }
  when(io.issueKeyFaultMask(5)) {
    issueRequest.identity.gid.wrap := !issued.gid.wrap
  }
  when(io.issueKeyFaultMask(6)) {
    issueRequest.identity.gid.value := issued.gid.value ^ 1.U
  }
  when(io.issueKeyFaultMask(7)) {
    issueRequest.identity.rid.valid := !issued.rid.valid
  }
  when(io.issueKeyFaultMask(8)) {
    issueRequest.identity.rid.wrap := !issued.rid.wrap
  }
  when(io.issueKeyFaultMask(9)) {
    issueRequest.identity.rid.value := issued.rid.value ^ 1.U
  }
  when(io.issueKeyFaultMask(10)) {
    issueRequest.identity.robSlot := issued.rid.value ^ 1.U
  }
  when(io.issueKeyFaultMask(11)) {
    issueRequest.identity.generation := issueTemplateGeneration ^ 1.U
  }

  sidecar.io.issue.valid := issue.io.issueValid && issuedTemplate && issueTemplateGenerationValid
  sidecar.io.issue.bits := issueRequest
  issue.io.issueReady :=
    Mux(issuedTemplate, sidecar.io.issue.ready && issueTemplateGenerationValid, true.B)

  sidecar.io.cancel.valid := io.cancelValid
  assignKey(sidecar.io.cancel.bits, io.cancelKey, issueRequest.identity)
  sidecar.io.releaseDecodeFence.valid := io.releaseDecodeValid
  assignKey(sidecar.io.releaseDecodeFence.bits, io.releaseDecodeKey, issueRequest.identity)
  sidecar.io.releaseIssueFence.valid := io.releaseIssueValid
  assignKey(sidecar.io.releaseIssueFence.bits, io.releaseIssueKey, issueRequest.identity)
  sidecar.io.releaseMemoryFence.valid := io.releaseMemoryValid
  assignKey(sidecar.io.releaseMemoryFence.bits, io.releaseMemoryKey, issueRequest.identity)
  sidecar.io.globalClear := io.globalClear

  // Preserve a point-to-point Decoupled transfer while allowing the testbench
  // to apply downstream backpressure. Neither endpoint can fire alone.
  sequencer.io.parentRequest.valid :=
    sidecar.io.parentRequest.valid && io.parentRequestReadyOverride
  sequencer.io.parentRequest.bits := sidecar.io.parentRequest.bits
  sidecar.io.parentRequest.ready :=
    sequencer.io.parentRequest.ready && io.parentRequestReadyOverride

  sequencer.io.rfReadRequest.ready := io.rfReadReady
  sequencer.io.rfReadResponse.valid := io.rfReadResponseValid
  sequencer.io.rfReadResponse.bits := 0.U.asTypeOf(sequencer.io.rfReadResponse.bits)
  sequencer.io.rfReadResponse.bits.parent := sequencer.io.activeIdentity
  sequencer.io.rfReadResponse.bits.childIndex := io.rfReadResponseChildIndex
  sequencer.io.rfReadResponse.bits.physTag := io.rfReadResponsePhysTag
  sequencer.io.rfReadResponse.bits.data := io.rfReadResponseData
  sequencer.io.loadRequest.ready := io.loadReady
  sequencer.io.loadResponse.valid := io.loadResponseValid
  sequencer.io.loadResponse.bits := 0.U.asTypeOf(sequencer.io.loadResponse.bits)
  sequencer.io.loadResponse.bits.parent := sequencer.io.activeIdentity
  sequencer.io.loadResponse.bits.childIndex := io.loadResponseChildIndex
  sequencer.io.loadResponse.bits.addr := io.loadResponseAddr
  sequencer.io.loadResponse.bits.data := io.loadResponseData
  sequencer.io.selectedTemplate := io.selectedTemplate
  sequencer.io.parentCommit.valid := io.parentCommitValid
  assignKey(sequencer.io.parentCommit.bits, io.parentCommitKey, sequencer.io.activeIdentity)
  sequencer.io.storeGrant := io.storeGrant
  sequencer.io.recovery.valid := io.recoveryValid
  assignKey(sequencer.io.recovery.bits, io.recoveryKey, sequencer.io.activeIdentity)
  sequencer.io.recoveryKillsActive := io.recoveryKillsActive
  sequencer.io.globalClear := io.globalClear
  sequencer.io.storeRequest.ready := io.storeReady
  sequencer.io.rfWriteRequest.ready := io.rfWriteReady

  when(bridge.io.accepted && isTemplate(io.acceptOpcode)) {
    assert(acceptedTemplateSidecar.fire)
  }
  when(io.acceptValid && isTemplate(io.acceptOpcode) && !acceptedTemplateSidecar.ready) {
    assert(!bridge.io.accepted)
  }
  assert(sidecar.io.parentRequest.fire === sequencer.io.parentRequest.fire)

  io.bridgeAccepted := bridge.io.accepted
  io.bridgeInReady := bridge.io.inReady
  io.bridgeOutValid := bridge.io.outValid
  io.bridgeTemplateSnapshotValid := bridge.io.templateSnapshotValid
  io.bridgeTemplateSnapshotGeneration := bridge.io.templateSnapshotGeneration
  io.bridgeTemplateSmap := bridge.io.templateSmapSnapshot
  io.bridgeTemplateCmap := bridge.io.templateCmapSnapshot
  io.sidecarValid := acceptedTemplateSidecar.valid
  io.sidecarReady := acceptedTemplateSidecar.ready
  io.sidecarFire := acceptedTemplateSidecar.fire
  io.sidecarGeneration := acceptedTemplateSidecar.bits.identity.generation
  io.sidecarStid := acceptedTemplateSidecar.bits.identity.stid
  io.sidecarRaw := acceptedTemplateSidecar.bits.identity.raw
  io.sidecarRangeM := acceptedTemplateSidecar.bits.rangeM
  io.sidecarRangeN := acceptedTemplateSidecar.bits.rangeN
  io.sidecarSrc0Imm := acceptedTemplateSidecar.bits.src0Imm
  io.sidecarSmap := acceptedTemplateSidecar.bits.map.smap
  io.sidecarCmap := acceptedTemplateSidecar.bits.map.cmap

  io.issueInputReady := issue.io.inReady
  io.issueValid := issue.io.issueValid
  io.issueReady := issue.io.issueReady
  io.issueFire := issue.io.issueFire
  io.issuedTemplateGenerationValid := issueTemplateGenerationValid
  io.issuedTemplateGeneration := issueTemplateGeneration
  io.issuedStid := issued.threadId
  io.issuedRaw := issued.insnRaw
  io.issuedBidValue := issued.bid.value
  io.issuedGidValue := issued.gid.value
  io.issuedRidValue := issued.rid.value
  io.issuedSrcData0 := issue.io.issueSrcData(0)
  io.parentRequestValid := sidecar.io.parentRequest.valid
  io.parentRequestReady := sidecar.io.parentRequest.ready
  io.parentTransfer := sidecar.io.parentRequest.fire
  io.sidecarOccupiedMask := sidecar.io.occupiedMask
  io.sidecarDecodeFenceMask := sidecar.io.decodeFenceMask
  io.sidecarIssueFenceMask := sidecar.io.issueFenceMask
  io.sidecarMemoryFenceMask := sidecar.io.memoryFenceMask
  io.enqueueCount := sidecar.io.enqueueCount
  io.transferCount := sidecar.io.transferCount
  io.mismatchDropCount := sidecar.io.mismatchDropCount
  io.staleGenerationDropCount := sidecar.io.staleGenerationDropCount

  val activeIdentity = sequencer.io.activeIdentity
  io.sequencerActive := sequencer.io.activeValid
  io.activeGeneration := activeIdentity.generation
  io.activeStid := activeIdentity.stid
  io.activePeId := activeIdentity.peId
  io.activePc := activeIdentity.pc
  io.activeOpcode := activeIdentity.opcode
  io.activeRaw := activeIdentity.raw
  io.activeBidValid := activeIdentity.bid.valid
  io.activeBidWrap := activeIdentity.bid.wrap
  io.activeBidValue := activeIdentity.bid.value
  io.activeGidValid := activeIdentity.gid.valid
  io.activeGidWrap := activeIdentity.gid.wrap
  io.activeGidValue := activeIdentity.gid.value
  io.activeRidValid := activeIdentity.rid.valid
  io.activeRidWrap := activeIdentity.rid.wrap
  io.activeRidValue := activeIdentity.rid.value
  io.activeRobSlot := activeIdentity.robSlot
  io.activeBlockBidValid := activeIdentity.blockBidValid
  io.activeBlockBid := activeIdentity.blockBid
  io.activeCommitBid := activeIdentity.commitIdentityBid
  io.activeCommitGid := activeIdentity.commitIdentityGid
  io.activeCommitRid := activeIdentity.commitIdentityRid
  io.activeSmap := sequencer.io.activeMap.smap
  io.activeCmap := sequencer.io.activeMap.cmap
  io.activeOldSp := sequencer.io.activeOldSp
  io.activeSrcData := sequencer.io.activeSrcData
  io.rfReadRequestValid := sequencer.io.rfReadRequest.valid
  io.rfReadRequestChildIndex := sequencer.io.rfReadRequest.bits.childIndex
  io.rfReadRequestArchReg := sequencer.io.rfReadRequest.bits.archReg
  io.rfReadRequestPhysTag := sequencer.io.rfReadRequest.bits.physTag
  io.loadRequestValid := sequencer.io.loadRequest.valid
  io.loadRequestChildIndex := sequencer.io.loadRequest.bits.childIndex
  io.loadRequestArchReg := sequencer.io.loadRequest.bits.archReg
  io.loadRequestAddr := sequencer.io.loadRequest.bits.addr
  io.decodeFence := sequencer.io.decodeFence
  io.issueFence := sequencer.io.issueFence
  io.memoryFence := sequencer.io.memoryFence
  io.completionValid := sequencer.io.completion.valid
  io.completionResult := sequencer.io.completion.bits.result
  io.completionNewSp := sequencer.io.completion.bits.newSp
  io.terminal := sequencer.io.terminal
  io.selfRestartObserved := sequencer.io.selfRestartObserved
  io.cancelObserved := sequencer.io.cancelObserved
}

object EmitTemplateAcceptedSidecarHandoffProbe extends App {
  val targetDir = args.sliding(2, 1).collectFirst {
    case Array("--target-dir", dir) => dir
  }.getOrElse("generated/chisel-verilog/bctrl-template-accepted-sidecar-handoff-probe")

  ChiselStage.emitSystemVerilogFile(
    new TemplateAcceptedSidecarHandoffProbe,
    Array("--target-dir", targetDir),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
