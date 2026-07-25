package linxcore.backend

import circt.stage.ChiselStage
import chisel3._
import linxcore.commit.CommitTraceParams
import linxcore.common._
import linxcore.frontend.F4Slot
import linxcore.rob.ROBID

class ScalarContinuationBlockIdentityProbeIO extends Bundle {
  val decodeValid = Input(Bool())
  val decodeInsn = Input(UInt(64.W))
  val decodePc = Input(UInt(64.W))
  val decodeLen = Input(UInt(4.W))
  val decodeLast = Input(Bool())
  val decodeReady = Output(Bool())
  val decRenPushFire = Output(Bool())
  val accepted = Output(Bool())
  val selectedValid = Output(Bool())
  val selectedIsLastInBlock = Output(Bool())
  val selectedBlockBid = Output(UInt(64.W))
  val selectedRobValue = Output(UInt(3.W))
  val scalarContinuationOwnershipCutFire = Output(Bool())
  val scalarContinuationTCutFire = Output(Bool())
  val renamedOutValid = Output(Bool())
  val renamedOutBlockBid = Output(UInt(64.W))
  val renamedOutRidValue = Output(UInt(3.W))
  val renamedOutTSeqValue = Output(UInt(3.W))
  val renamedOutUSeqValue = Output(UInt(3.W))
  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(3.W))
  val completeBlockBid = Input(UInt(64.W))
  val completePc = Input(UInt(64.W))
  val deallocReady = Input(Bool())
  val robDeallocBlockLastValid = Output(Bool())
  val robDeallocBlockLastBlockBid = Output(UInt(64.W))
  val blockRetireFire = Output(Bool())
  val blockRetireBid = Output(UInt(64.W))
  val gprCommitAccepted = Output(Bool())
  val gprCommitBlockBid = Output(UInt(64.W))
  val tuRetireLocalBlockCommitValid = Output(Bool())
  val tuRetireLocalBlockCommitAccepted = Output(Bool())
  val tuRetireLocalBlockCommitBid = Output(UInt(3.W))
  val tuRetireLocalBlockCommitStid = Output(UInt(8.W))
  val tuRenameAccepted = Output(Bool())
  val tuRenameTSeqValue = Output(UInt(3.W))
  val tuRenameUSeqValue = Output(UInt(3.W))
  val tuRenameTUsedEntries = Output(UInt(3.W))
  val tuRenameUUsedEntries = Output(UInt(3.W))
  val blockedByTURename = Output(Bool())
  val unsupported = Output(Bool())
  val commitContractError = Output(Bool())
  val observedTCutBid0 = Output(Bool())
  val observedFreshBid1 = Output(Bool())
  val observedBlockLastBid0 = Output(Bool())
  val observedLocalCommitBid0 = Output(Bool())
  val observedTRelease = Output(Bool())
  val observedPostReleaseTAccept = Output(Bool())
}

class ScalarContinuationBlockIdentityProbe extends Module {
  private val p = InterfaceParams(robEntries = 8, commitWidth = 2)
  private val trace = CommitTraceParams(
    commitWidth = p.commitWidth,
    robValueWidth = p.robIndexWidth,
    blockBidWidth = p.blockBidWidth,
    pcWidth = p.pcWidth,
    insnWidth = p.insnWidth,
    lenWidth = p.lenWidth)
  val io = IO(new ScalarContinuationBlockIdentityProbeIO)

  private def zeroRobId: ROBID = 0.U.asTypeOf(new ROBID(p.robEntries))

  val path = Module(new DecodeRenameROBPath(
    p = p,
    traceParams = trace,
    mapQDepth = 4,
    gprMapQDepth = 8,
    decRenQueueDepth = 4,
    tuRetireSourceQueueDepth = 4,
    tuRetireRelationCmapDepth = 8,
    markerRetireSourceQueueDepth = 4,
    blockRenameCommitQueueDepth = 4,
    scalarContinuationGprCutThreshold = 2,
    scalarContinuationTuCutThreshold = 2,
    useMarkerDecodeContext = false,
    skipBlockMarkers = true,
    reducedStoreDispatchBypass = true))

  path.io.d1 := 0.U.asTypeOf(path.io.d1)
  path.io.d1.valid := io.decodeValid
  path.io.d1.peId := 0.U
  path.io.d1.threadId := 0.U
  path.io.d1.pc := io.decodePc

  for (slot <- 0 until p.decodeWidth) {
    path.io.slots(slot) := 0.U.asTypeOf(new F4Slot(p))
    path.io.slots(slot).pc := io.decodePc
    path.io.slots(slot).uopUid := slot.U
  }
  path.io.slots(0).valid := io.decodeValid
  path.io.slots(0).insnRaw := io.decodeInsn
  path.io.slots(0).lenBytes := io.decodeLen
  path.io.slots(0).isLastInBlock := io.decodeLast
  path.io.validMask := Mux(io.decodeValid, 1.U, 0.U)
  path.io.samePacketNextSlotValid := false.B
  path.io.samePacketNextSlot := 0.U.asTypeOf(path.io.samePacketNextSlot)

  path.io.flushValid := false.B
  path.io.blockExplicitStoreCountValid := false.B
  path.io.blockExplicitStoreCountBid := 0.U
  path.io.blockExplicitStoreCountStid := 0.U
  path.io.blockExplicitStoreCountValue := 0.U
  path.io.renamedOutReady := true.B
  path.io.storeStaExec := 0.U.asTypeOf(path.io.storeStaExec)
  path.io.storeStdExec := 0.U.asTypeOf(path.io.storeStdExec)
  path.io.storeAddressInsertPermit := true.B
  path.io.storeMarkCommitValid := false.B
  path.io.storeMarkCommitIndex := 0.U
  path.io.storeCommitFreeValid := false.B
  path.io.storeCommitFreeIndex := 0.U
  path.io.storeCommitFreeMaskValid := false.B
  path.io.storeCommitFreeMask := 0.U
  DecodeRenameROBPath.tieOffStoreScResult(path)
  path.io.checkpointValid := false.B
  path.io.checkpointBid := zeroRobId
  path.io.checkpointStid := 0.U
  path.io.commitValid := false.B
  path.io.commitBid := zeroRobId
  path.io.commitBlockBid := 0.U
  path.io.commitStid := 0.U
  path.io.recoveryNonLsuSources.foreach(_ := 0.U.asTypeOf(path.io.recoveryNonLsuSources.head))
  path.io.directBccRecoveryMiss := 0.U.asTypeOf(path.io.directBccRecoveryMiss)
  path.io.directIexSlowRecovery := 0.U.asTypeOf(path.io.directIexSlowRecovery)
  path.io.directIexIqStalled := false.B
  path.io.directIexIqProgress := false.B
  path.io.directIexIqStid := 0.U
  path.io.directIexIqPeId := 0.U
  path.io.directIexIqTid := 0.U
  path.io.directPeMismatchRecovery := 0.U.asTypeOf(path.io.directPeMismatchRecovery)
  path.io.lsuRecoverySource := 0.U.asTypeOf(path.io.lsuRecoverySource)
  path.io.lsuFullBidLookupRequest := 0.U.asTypeOf(path.io.lsuFullBidLookupRequest)
  path.io.recoveryIntentReady := true.B
  path.io.scalarCleanupOrderValid := false.B
  path.io.scalarCleanupOrder := 0.U
  path.io.completeValid := io.completeValid
  path.io.completeRobValue := io.completeRobValue
  path.io.completeRowValid := io.completeValid
  path.io.completeRow := 0.U.asTypeOf(path.io.completeRow)
  path.io.completeRow.valid := io.completeValid
  path.io.completeRow.blockBidValid := io.completeValid
  path.io.completeRow.blockBid := io.completeBlockBid
  path.io.completeRow.pc := io.completePc
  path.io.blockBranchTakenValid := false.B
  path.io.blockBranchTaken := false.B
  path.io.scalarRedirectValid := false.B
  path.io.scalarRedirectStid := 0.U
  path.io.deallocReady := io.deallocReady
  path.io.deallocHoldMask := 0.U
  path.io.robStatusLookupValid := false.B
  path.io.robStatusLookupRid := zeroRobId
  path.io.robCommitTraceLookupValid := false.B
  path.io.robCommitTraceLookupRid := zeroRobId
  path.io.robCommitTraceLookupSourceTraceEnable := false.B

  path.io.recoveryNonLsuSources.foreach(_.valid := false.B)
  path.io.directBccRecoveryMiss.valid := false.B
  path.io.directIexSlowRecovery.valid := false.B
  path.io.directPeMismatchRecovery.valid := false.B
  path.io.lsuRecoverySource.valid := false.B

  io.decodeReady := path.io.decodeReady
  io.decRenPushFire := path.io.decRenPushFire
  io.accepted := path.io.accepted
  io.selectedValid := path.io.selectedValid
  io.selectedIsLastInBlock := path.io.selectedIsLastInBlock
  io.selectedBlockBid := path.io.selectedBlockBid
  io.selectedRobValue := path.io.selectedRobValue
  io.scalarContinuationOwnershipCutFire := path.io.scalarContinuationOwnershipCutFire
  io.scalarContinuationTCutFire := path.io.scalarContinuationTCutFire
  io.renamedOutValid := path.io.renamedOutValid
  io.renamedOutBlockBid := path.io.renamedOut.blockBid
  io.renamedOutRidValue := path.io.renamedOut.rid.value
  io.renamedOutTSeqValue := path.io.renamedOut.src(0).relTag(2, 0)
  io.renamedOutUSeqValue := path.io.renamedOut.src(1).relTag(2, 0)
  io.robDeallocBlockLastValid := path.io.robDeallocBlockLastValid
  io.robDeallocBlockLastBlockBid := path.io.robDeallocBlockLastBlockBid
  io.blockRetireFire := path.io.blockRetireFire
  io.blockRetireBid := path.io.blockRetireBid
  io.gprCommitAccepted := path.io.gprCommitAccepted
  io.gprCommitBlockBid := path.io.gprCommitBlockBid
  io.tuRetireLocalBlockCommitValid := path.io.tuRetireLocalBlockCommitValid
  io.tuRetireLocalBlockCommitAccepted := path.io.tuRetireLocalBlockCommitAccepted
  io.tuRetireLocalBlockCommitBid := path.io.tuRetireLocalBlockCommitBid.value
  io.tuRetireLocalBlockCommitStid := path.io.tuRetireLocalBlockCommitStid
  io.tuRenameAccepted := path.io.tuRenameAccepted
  io.tuRenameTSeqValue := path.io.tuRenameTSeq.value
  io.tuRenameUSeqValue := path.io.tuRenameUSeq.value
  io.tuRenameTUsedEntries := path.io.tuRenameTUsedEntries
  io.tuRenameUUsedEntries := path.io.tuRenameUUsedEntries
  io.blockedByTURename := path.io.blockedByTURename
  io.unsupported := path.io.unsupported
  io.commitContractError := path.io.commitContractError

  val observedTCutBid0 = RegInit(false.B)
  val observedFreshBid1 = RegInit(false.B)
  val observedBlockLastBid0 = RegInit(false.B)
  val observedLocalCommitBid0 = RegInit(false.B)
  val observedTRelease = RegInit(false.B)
  val observedPostReleaseTAccept = RegInit(false.B)
  when(path.io.scalarContinuationTCutFire && path.io.selectedBlockBid === 0.U && path.io.selectedIsLastInBlock) {
    observedTCutBid0 := true.B
  }
  when(path.io.selectedValid && path.io.selectedBlockBid === 1.U) {
    observedFreshBid1 := true.B
  }
  when(path.io.robDeallocBlockLastValid && path.io.robDeallocBlockLastBlockBid === 0.U) {
    observedBlockLastBid0 := true.B
  }
  when(path.io.tuRetireLocalBlockCommitAccepted && path.io.tuRetireLocalBlockCommitBid.value === 0.U) {
    observedLocalCommitBid0 := true.B
  }
  // BID 1 already owns one younger T mapping, so retiring BID 0 must
  // reduce occupancy from three entries to that single live entry.
  when(observedLocalCommitBid0 && path.io.tuRenameTUsedEntries === 1.U) {
    observedTRelease := true.B
  }
  when(observedTRelease && path.io.tuRenameAccepted && path.io.tuRenameDstValid &&
      path.io.tuRenameDstKind === DestinationKind.T) {
    observedPostReleaseTAccept := true.B
  }
  io.observedTCutBid0 := observedTCutBid0
  io.observedFreshBid1 := observedFreshBid1
  io.observedBlockLastBid0 := observedBlockLastBid0
  io.observedLocalCommitBid0 := observedLocalCommitBid0
  io.observedTRelease := observedTRelease
  io.observedPostReleaseTAccept := observedPostReleaseTAccept
}

object EmitScalarContinuationBlockIdentityProbe extends App {
  val targetDir = args.sliding(2, 1).collectFirst {
    case Array("--target-dir", dir) => dir
  }.getOrElse("generated/chisel-verilog/backend-scalar-continuation-block-identity-probe")

  ChiselStage.emitSystemVerilogFile(
    new ScalarContinuationBlockIdentityProbe,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    args = Array("--target-dir", targetDir))
}
