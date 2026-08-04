package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, MuxLookup, PopCount, PriorityEncoder,
  PriorityEncoderOH, UIntToOH, Valid, log2Ceil}
import linxcore.common.{BoundaryKind, DestinationKind, OperandClass}
import linxcore.params.CoreParams
import linxcore.top.interface.{DispatchTxn, MemoryAddressMode, MemoryIndexMode,
  OperandKind, PredictionKind, RecoveryPhase, RecoveryPlan,
  RecoveryPlanContract, RecoveryTargetIO, RobIdentity, StoreDispatchTxn,
  UopClass}

object OooIexIssueSlotState extends ChiselEnum {
  val Free, BoundS2, ResidentS3 = Value
}

object OooIexRecoveryScanState extends ChiselEnum {
  val Idle, Scan, Prepared = Value
}

class OooIexIssueIO(
    val core: CoreParams,
    val p: OooParams)
    extends Bundle {
  def this(core: CoreParams) =
    this(core, OooIexPhysicalProfile.fromCoreParams(core).params)
  val dispatch = Flipped(new OOODispatchChannels(core))
  val storeReserve = Decoupled(new OooIexIssueRow(p))
  val operandReadyBits = Input(new OooIexOperandReadyBits(p))
  val wakeup = Input(Vec(p.iexWakeupPorts, Valid(new OooIexWakeup(p))))
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))
  val releases = Flipped(Vec(p.iexReleaseWidth,
    Decoupled(new OooIexIssueRelease(p))))
  def release = releases(0)
  val recovery = Flipped(new RecoveryTargetIO(core))
  val acceptedRecoveryApply = Valid(new RecoveryPlan(core))

  val queries = Input(Vec(p.iexIssueDomainCount,
    new OooIexSlotQuery(p)))
  val queryStates = Output(Vec(p.iexIssueDomainCount,
    OooIexIssueSlotState()))
  val queryRows = Output(Vec(p.iexIssueDomainCount,
    new OooIexIssueRow(p)))
  val queryPickables = Output(Vec(p.iexIssueDomainCount, Bool()))
  val pickBankEnables = Input(Vec(p.iexIssueDomainCount,
    Vec(p.iqClassCount, UInt(p.iqBankCount.W))))
  val issuePolicy = Input(new OooIexIssuePolicy(p))
  val picks = Vec(p.iexIssueDomainCount,
    Decoupled(new OooIexPickToken(p)))
  val pickRetries = Flipped(Vec(p.iexIssueDomainCount,
    Valid(new OooIexReadRepick(p))))
  val pickMalformedByDomain = Vec(p.iexIssueDomainCount,
    Valid(new OooIexPickReject(p)))
  val pickRejectedByDomain = Vec(p.iexIssueDomainCount,
    Valid(new OooIexPickClaimReject(p)))
  val pickRetryRejectedByDomain = Vec(p.iexIssueDomainCount,
    Valid(new OooIexPickRetryReject(p)))
  val pickRecoveryBlockedByDomain = Vec(p.iexIssueDomainCount,
    Valid(new OooIexPickToken(p)))
  val pickPolicyBlockedByDomain = Vec(p.iexIssueDomainCount,
    Valid(new OooIexIssuePolicyBlockEvent(p)))
  val queryPolicyReasons = Output(Vec(p.iexIssueDomainCount,
    UInt(OooIexIssueBlockReason.Count.W)))
  val policyBlockedCount = Output(Vec(p.iexIssueDomainCount,
    UInt(p.countWidth(p.iqClassCount * p.iqBankCount *
      p.iqEntriesPerBank).W)))

  def query = queries(0)
  def queryState = queryStates(0)
  def queryRow = queryRows(0)
  def queryPickable = queryPickables(0)
  def pickBankEnable = pickBankEnables(0)
  def pick = picks(0)
  def pickRetry = pickRetries(0)
  def pickMalformed = pickMalformedByDomain(0)
  def pickRejected = pickRejectedByDomain(0)
  def pickRetryRejected = pickRetryRejectedByDomain(0)
  def pickRecoveryBlocked = pickRecoveryBlockedByDomain(0)
  def pickPolicyBlocked = pickPolicyBlockedByDomain(0)
  def queryPolicyReason = queryPolicyReasons(0)

  val boundEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val residentEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val inFlightEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val storeFrontierBlocked = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.iqClassCount * p.iqBankCount *
      p.iqEntriesPerBank).W)))
  val releaseRejecteds = Vec(p.iexReleaseWidth,
    Valid(new OooIexReleaseReject(p)))
  def releaseRejected = releaseRejecteds(0)
  val recoveryIdle = Output(Bool())
}

/** Canonical Dispatch-to-IQ owner with retained S2/S3 residency.
  *
  * Classed OOO channels allocate physical IQ rows directly.  The compact
  * schedule row and payload sidecar remain the unique issue state; the PTag
  * Ready Bits view is sampled only on the accepted dispatch edge.  Recovery
  * scans exact ROB identities without reconstructing global age.
  */
class OooIexIssue(
    val core: CoreParams,
    val requireStoreReservation: Boolean = false) extends Module {
  val profile = OooIexPhysicalProfile.fromCoreParams(core)
  val p = profile.params
  OooRecoveryMembership.requireCompatible(p, core)
  val io = IO(new OooIexIssueIO(core, p))

  private val classWidth = math.max(1, log2Ceil(p.iqClassCount))
  private val physicalRowCount =
    p.iqClassCount * p.iqBankCount * p.iqEntriesPerBank
  private val normalAdmissionCount = core.iex.aluPipes + core.iex.bruPipes +
    core.iex.aguPipes + core.iex.systemMulticycleQueues +
    core.iex.cmdIssueQueues
  private val admissionCount = normalAdmissionCount + 2 * core.iex.stdPipes
  private val admissionIndexWidth = math.max(1, log2Ceil(admissionCount))

  val slotState = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(OooIexIssueSlotState.Free)))))))
  val scheduleRows = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(
        0.U.asTypeOf(new OooIexScheduleRow(p)))))))))
  val capabilityRows = RegInit(0.U.asTypeOf(
    Vec(p.iqClassCount, Vec(p.iqBankCount,
      Vec(p.iqEntriesPerBank,
        UInt(OooIexDomainCapability.Count.W))))))
  val reservationEpoch = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(
        0.U(p.reservationEpochWidth.W))))))))
  // These serials advance only on canonical Dispatch-to-IQ admission. They
  // deliberately do not rewind on recovery: a canceled memory uop must never
  // alias a later retained uop or load attempt.
  private val memoryTransactionSerialWidth =
    p.memoryTransactionGenerationWidth + p.memoryTransactionIdWidth
  private val memoryTransactionSerial =
    RegInit(0.U(memoryTransactionSerialWidth.W))
  private val initialLoadAttemptSerial =
    RegInit(0.U(p.loadGenerationWidth.W))
  val payloadRows = Seq.tabulate(p.iqClassCount, p.iqBankCount) { (_, _) =>
    Mem(p.iqEntriesPerBank, new OooIexPayloadSidecar(p))
  }

  private def sameMember(left: RobMemberKey, right: RobMemberKey): Bool =
    left.asUInt === right.asUInt

  private def privateMember(rob: RobIdentity): RobMemberKey = {
    val member = Wire(new RobMemberKey(p))
    member.group.valid := true.B
    member.group.peId := rob.peId
    member.group.stid := rob.stid
    member.group.ridSlot := rob.ridSlot
    member.group.ridGeneration := rob.ridGeneration
    member.bid.valid := true.B
    member.bid.value := rob.bid
    member.brobGeneration := rob.brobGeneration
    member.memberIndex := rob.memberIndex
    member.residentGeneration := rob.residentGeneration
    member
  }

  private def operandClass(kind: OperandKind.Type): OperandClass.Type =
    MuxLookup(kind.asUInt, OperandClass.Invalid)(Seq(
      OperandKind.Gpr.asUInt -> OperandClass.P,
      OperandKind.T.asUInt -> OperandClass.T,
      OperandKind.U.asUInt -> OperandClass.U))

  private def destinationKind(kind: OperandKind.Type): DestinationKind.Type =
    MuxLookup(kind.asUInt, DestinationKind.None)(Seq(
      OperandKind.Gpr.asUInt -> DestinationKind.Gpr,
      OperandKind.T.asUInt -> DestinationKind.T,
      OperandKind.U.asUInt -> DestinationKind.U))

  private def localSequence(
      source: linxcore.top.interface.RenamedSource,
      isT: Bool): OooLocalSeq = {
    val sequence = Wire(new OooLocalSeq(p))
    sequence.valid := source.valid && (source.ttagValid || source.utagValid)
    sequence.index := Mux(isT, source.tSeqIndex, source.uSeqIndex)
    sequence.generation := Mux(
      isT, source.tSeqGeneration, source.uSeqGeneration)
    sequence
  }

  private def localDestinationSequence(
      destination: linxcore.top.interface.RenamedDestination,
      isT: Bool): OooLocalSeq = {
    val sequence = Wire(new OooLocalSeq(p))
    sequence.valid := destination.valid &&
      (destination.ttagValid || destination.utagValid)
    sequence.index := Mux(isT,
      destination.tSeqIndex, destination.uSeqIndex)
    sequence.generation := Mux(isT,
      destination.tSeqGeneration, destination.uSeqGeneration)
    sequence
  }

  private def exactWakeup(
      txn: DispatchTxn,
      source: linxcore.top.interface.RenamedSource,
      sourceClass: OperandClass.Type,
      sequence: OooLocalSeq,
      committed: Boolean): Bool = io.wakeup.map { wakeup =>
    val classExact = wakeup.bits.operandClass === sourceClass
    val tagExact = Mux(sourceClass === OperandClass.P,
      wakeup.bits.ptag === source.ptag &&
        wakeup.bits.ptagGeneration === source.pGeneration,
      wakeup.bits.localTag === Mux(sourceClass === OperandClass.T,
        source.ttag, source.utag) &&
        wakeup.bits.localSequence.asUInt === sequence.asUInt)
    wakeup.valid && classExact && tagExact &&
      wakeup.bits.stid === txn.uop.decoded.rob.stid &&
      wakeup.bits.epoch === txn.uop.decoded.instruction.parent.identity.epoch &&
      wakeup.bits.kind === (if (committed) OooIexWakeupKind.Committed
        else OooIexWakeupKind.SpeculativeLoad)
  }.reduce(_ || _)

  private def exactReady(
      txn: DispatchTxn,
      source: linxcore.top.interface.RenamedSource,
      sourceClass: OperandClass.Type,
      sequence: OooLocalSeq): Bool = {
    def physicalIndex(value: UInt, entries: Int): UInt = {
      val width = math.max(1, log2Ceil(entries))
      if (entries == 1) 0.U(width.W) else value(width - 1, 0)
    }
    def safePhysicalIndex(value: UInt, entries: Int, inRange: Bool): UInt = {
      val width = math.max(1, log2Ceil(entries))
      Mux(inRange, physicalIndex(value, entries), 0.U(width.W))
    }
    val stid = txn.uop.decoded.rob.stid
    val stidInRange = stid < p.stidCount.U
    val safeStid = safePhysicalIndex(stid, p.stidCount, stidInRange)
    val ptagInRange = source.ptag < p.pPhysRegs.U
    val safePtag = safePhysicalIndex(source.ptag, p.pPhysRegs, ptagInRange)
    val ttagInRange = source.ttag < p.tPhysRegs.U
    val safeTtag = safePhysicalIndex(source.ttag, p.tPhysRegs, ttagInRange)
    val utagInRange = source.utag < p.uPhysRegs.U
    val safeUtag = safePhysicalIndex(source.utag, p.uPhysRegs, utagInRange)
    val epoch = txn.uop.decoded.instruction.parent.identity.epoch
    val pEntry = io.operandReadyBits.ptag(safePtag)
    val tEntry = if (p.stidCount == 1)
      io.operandReadyBits.ttag(0)(safeTtag)
    else io.operandReadyBits.ttag(safeStid)(safeTtag)
    val uEntry = if (p.stidCount == 1)
      io.operandReadyBits.utag(0)(safeUtag)
    else io.operandReadyBits.utag(safeStid)(safeUtag)
    val pExact = ptagInRange && pEntry.valid && pEntry.ready &&
      pEntry.stid === stid && pEntry.epoch === epoch &&
      pEntry.generation === source.pGeneration
    val tExact = stidInRange && ttagInRange && tEntry.allocated &&
      tEntry.ready && tEntry.epoch === epoch &&
      tEntry.sequence.asUInt === sequence.asUInt
    val uExact = stidInRange && utagInRange && uEntry.allocated &&
      uEntry.ready && uEntry.epoch === epoch &&
      uEntry.sequence.asUInt === sequence.asUInt
    MuxLookup(sourceClass.asUInt, false.B)(Seq(
      OperandClass.P.asUInt -> pExact,
      OperandClass.T.asUInt -> tExact,
      OperandClass.U.asUInt -> uExact)) ||
      exactWakeup(txn, source, sourceClass, sequence, committed = true)
  }

  private def sourceSelected(
      txn: DispatchTxn,
      sourceIndex: Int,
      uopClass: OooUopClass.Type,
      childIndex: UInt): Bool = {
    val source = txn.uop.sources(sourceIndex)
    val storeAddress = uopClass === OooUopClass.Agu && childIndex === 0.U
    val storeData = uopClass === OooUopClass.Std && childIndex === 1.U
    source.valid && Mux(storeAddress,
      txn.uop.decoded.memory.addressSourceMask(sourceIndex),
      Mux(storeData,
        txn.uop.decoded.memory.dataSourceMask(sourceIndex), true.B))
  }

  private def makeRow(
      txn: DispatchTxn,
      uopClass: OooUopClass.Type,
      childIndex: UInt,
      bank: UInt,
      entry: UInt,
      nextEpoch: UInt,
      capability: UInt,
      memoryTransaction: OooIexMemoryTransactionIdentity,
      initialLoadAttemptGeneration: UInt): OooIexIssueRow = {
    val row = Wire(new OooIexIssueRow(p))
    row := 0.U.asTypeOf(row)
    val decoded = txn.uop.decoded
    val instruction = decoded.instruction.parent
    val member = privateMember(decoded.rob)
    row.valid := true.B
    row.peId := decoded.rob.peId
    row.stid := decoded.rob.stid
    row.epoch := instruction.identity.epoch
    row.transactionId := txn.transactionId
    row.memoryTransactionValid := decoded.memory.valid
    row.memoryTransaction := Mux(decoded.memory.valid, memoryTransaction,
      0.U.asTypeOf(row.memoryTransaction))
    row.initialLoadAttemptValid := decoded.memory.valid && decoded.memory.isLoad
    row.initialLoadAttemptGeneration := Mux(
      row.initialLoadAttemptValid, initialLoadAttemptGeneration, 0.U)
    row.dispatchLane := 0.U
    row.uopIndex := decoded.rob.memberIndex
    row.childIndex := childIndex
    row.member := member
    row.reservation.valid := true.B
    row.reservation.uopClass := uopClass
    row.reservation.bank := bank
    row.reservation.writePort := 0.U
    row.reservation.speculativeSlot := entry
    row.reservation.reservationEpoch := nextEpoch

    row.uopKey.primaryParent.valid := true.B
    row.uopKey.primaryParent.peId := instruction.identity.peId
    row.uopKey.primaryParent.stid := instruction.identity.stid
    row.uopKey.primaryParent.instructionId := instruction.identity.instructionId
    row.uopKey.primaryParent.epoch := instruction.identity.epoch
    row.uopKey.uopOrdinal := decoded.rob.memberIndex
    row.uopKey.uopCount := 1.U
    row.parentCount := 1.U
    row.parentPcTokens(0).valid := txn.pcBufferIndexOffset.valid
    row.parentPcTokens(0).index := txn.pcBufferIndexOffset.pcBufferIndex
    row.parentPcTokens(0).byteOffset := txn.pcBufferIndexOffset.pcOffset
    row.parentPcTokens(0).allocationEpoch :=
      txn.pcBufferIndexOffset.allocationEpoch
    row.pcParentIndexValid := txn.pcBufferIndexOffset.valid
    row.pcParentIndex := 0.U
    row.primaryPrediction.valid := instruction.prediction.valid
    row.primaryPrediction.predictionTag := instruction.prediction.predictionTag
    row.primaryPrediction.transactionId := instruction.prediction.transactionId
    row.primaryPrediction.requestPc := instruction.prediction.requestPc
    row.primaryPrediction.taken := instruction.prediction.taken
    row.primaryPrediction.branchPc := instruction.pc
    row.primaryPrediction.target := instruction.prediction.target
    row.primaryPrediction.fallthroughPc := instruction.prediction.fallthroughPc
    row.primaryPrediction.kind := BoundaryKind.Fall
    when(instruction.prediction.kind === PredictionKind.Conditional) {
      row.primaryPrediction.kind := BoundaryKind.Cond
    }.elsewhen(instruction.prediction.kind === PredictionKind.Call) {
      row.primaryPrediction.kind := BoundaryKind.Call
    }.elsewhen(instruction.prediction.kind === PredictionKind.Return) {
      row.primaryPrediction.kind := BoundaryKind.Ret
    }.elsewhen(instruction.prediction.kind === PredictionKind.Direct) {
      row.primaryPrediction.kind := BoundaryKind.Direct
    }.elsewhen(instruction.prediction.kind === PredictionKind.Indirect) {
      row.primaryPrediction.kind := BoundaryKind.Ind
    }
    row.primaryPrediction.provider := instruction.prediction.provider
    row.primaryPrediction.confidence := instruction.prediction.confidence
    row.primaryPrediction.checkpointId := instruction.prediction.checkpointId
    row.primaryPrediction.epoch := instruction.prediction.epoch

    row.boundary.start := decoded.blockStart
    row.boundary.stop := decoded.blockStop
    row.boundary.explicit := decoded.blockBoundary
    row.opcode := decoded.opcode
    row.recipe.valid := decoded.classification.valid
    row.recipe.opcode := decoded.opcode
    row.recipe.disposition := decoded.classification.disposition
    row.recipe.recipeKind := decoded.classification.kind
    row.recipe.uopCountMin := decoded.classification.uopCountMin
    row.recipe.uopCountMax := decoded.classification.uopCountMax
    row.recipe.complexBreak := decoded.classification.complexBreak
    row.recipe.lateSplitKind := decoded.classification.splitKind
    row.recipe.fusionHeadClass := decoded.classification.fusionHeadClass
    row.recipe.fusionTailClass := decoded.classification.fusionTailClass
    row.recipe.fastResolveClass := decoded.classification.fastResolveClass
    row.recipe.implicitSourceMask :=
      decoded.classification.implicitSourceMask
    row.recipe.implicitDestination :=
      decoded.classification.implicitDestination
    row.recipe.sideEffectOwner := decoded.classification.sideEffectOwner
    row.recipe.requiresTargetValidation :=
      decoded.classification.requiresTargetValidation
    row.recipe.mayTrap := decoded.classification.mayTrap
    row.recipe.mayTrapLate := decoded.classification.mayTrapLate
    row.recipe.mayRedirect := decoded.classification.mayRedirect
    row.recipe.nonspeculative := decoded.classification.nonspeculative
    row.recipe.pcReadRequired := decoded.classification.pcReadRequired
    row.recipe.pcReadClass := decoded.classification.pcReadClass
    row.recipe.dispatchClass := decoded.classification.dispatchClass
    row.recipe.dispatchWrites := decoded.classification.dispatchWrites
    for (classIndex <- 0 until p.iqClassCount) {
      row.recipe.dispatchDemand(classIndex) :=
        decoded.classification.dispatchDemand(classIndex)
      row.recipe.dispatchCapabilities(classIndex) :=
        decoded.classification.executionPipeCapability(classIndex)
    }
    row.recipe.memoryRequestCount :=
      decoded.classification.memoryRequestCount
    row.recipe.pSourceCount := decoded.classification.pSourceCount
    row.recipe.pDestinationCount := decoded.classification.pDestinationCount
    row.recipe.tAllocationCount := decoded.classification.tAllocationCount
    row.recipe.uAllocationCount := decoded.classification.uAllocationCount
    row.plannedChildCount := decoded.classification.uopCountMax
    row.immediateValid := decoded.immediateValid
    row.immediate := decoded.immediate
    row.memory.valid := decoded.memory.valid
    row.memory.isLoad := decoded.memory.isLoad
    row.memory.isStore := decoded.memory.isStore
    row.memory.addressMode := MuxLookup(decoded.memory.addressMode.asUInt,
      OooMemoryAddressMode.None)(Seq(
      MemoryAddressMode.BaseIndex.asUInt -> OooMemoryAddressMode.BaseIndex,
      MemoryAddressMode.BaseOffset.asUInt -> OooMemoryAddressMode.BaseOffset,
      MemoryAddressMode.PcOffset.asUInt -> OooMemoryAddressMode.PcOffset))
    row.memory.accessBytes := decoded.memory.accessBytes
    row.memory.signExtend := decoded.memory.signExtend
    row.memory.offset := decoded.memory.offset
    row.memory.indexMode := MuxLookup(decoded.memory.indexMode.asUInt,
      OooMemoryIndexMode.Identity)(Seq(
      MemoryIndexMode.SignExtend32.asUInt -> OooMemoryIndexMode.SignExtend32,
      MemoryIndexMode.ZeroExtend32.asUInt -> OooMemoryIndexMode.ZeroExtend32,
      MemoryIndexMode.Negate.asUInt -> OooMemoryIndexMode.Negate))
    row.memory.indexShift := decoded.memory.indexShift
    row.memory.addressSourceMask := decoded.memory.addressSourceMask
    row.memory.dataSourceMask := decoded.memory.dataSourceMask
    row.memory.writebackValid := decoded.memory.writebackValid
    row.memory.writebackPreIndex := decoded.memory.writebackPreIndex

    row.memoryOrder.valid := decoded.valid
    row.memoryOrder.memoryValid := decoded.memory.valid
    row.memoryOrder.isLoad := decoded.memory.isLoad
    row.memoryOrder.isStore := decoded.memory.isStore
    row.memoryOrder.requestCount := txn.memoryOrder.requestCount
    row.memoryOrder.firstLsid := txn.memoryOrder.firstLsid
    row.memoryOrder.firstTypeId := Mux(decoded.memory.isLoad,
      txn.memoryOrder.firstLid, txn.memoryOrder.firstSid)
    row.memoryOrder.before.lsid := txn.memoryOrder.firstLsid
    row.memoryOrder.before.loadId := txn.memoryOrder.firstLid
    row.memoryOrder.before.storeId := txn.memoryOrder.firstSid
    row.memoryOrder.before.youngestStoreLsidValid := txn.memoryOrder.yostValid
    row.memoryOrder.before.youngestStoreLsid := txn.memoryOrder.yostLsid
    row.memoryOrder.after := row.memoryOrder.before
    row.memoryOrder.after.lsid := txn.memoryOrder.firstLsid +
      txn.memoryOrder.requestCount
    row.memoryOrder.after.loadId := txn.memoryOrder.firstLid +
      Mux(decoded.memory.isLoad, txn.memoryOrder.requestCount, 0.U)
    row.memoryOrder.after.storeId := txn.memoryOrder.firstSid +
      Mux(decoded.memory.isStore, txn.memoryOrder.requestCount, 0.U)
    when(decoded.memory.isStore && txn.memoryOrder.requestCount.orR) {
      row.memoryOrder.after.youngestStoreLsidValid := true.B
      row.memoryOrder.after.youngestStoreLsid :=
        txn.memoryOrder.firstLsid + txn.memoryOrder.requestCount - 1.U
    }
    row.isStore := decoded.memory.valid && decoded.memory.isStore
    row.storeOrder.valid := row.isStore
    row.storeOrder.logicalMember := member
    row.storeOrder.firstLsid := txn.memoryOrder.firstLsid
    row.storeOrder.firstStoreId := txn.memoryOrder.firstSid
    row.storeOrder.requestCount := txn.memoryOrder.requestCount
    row.preciseTrap := txn.trap.valid
    row.trapCause := txn.trap.cause
    row.blockLast := decoded.blockStop

    for (sourceIndex <- 0 until p.maxSourceOperands) {
      val renamed = txn.uop.sources(sourceIndex)
      val source = row.sources(sourceIndex)
      val cls = operandClass(renamed.kind)
      val isT = cls === OperandClass.T
      val sequence = localSequence(renamed, isT)
      val selected = sourceSelected(txn, sourceIndex, uopClass, childIndex)
      val ready = exactReady(txn, renamed, cls, sequence)
      val speculative = exactWakeup(
        txn, renamed, cls, sequence, committed = false)
      source.valid := selected
      source.ready := !selected || ready
      source.specReady := selected && !ready && speculative
      source.operandClass := cls
      source.ptag := renamed.ptag
      source.ptagGeneration := renamed.pGeneration
      source.localTag := Mux(isT, renamed.ttag, renamed.utag)
      source.localSequence := sequence
      source.load := 0.U.asTypeOf(source.load)
      for (port <- 0 until p.iexWakeupPorts) {
        when(source.specReady && io.wakeup(port).valid &&
            io.wakeup(port).bits.kind === OooIexWakeupKind.SpeculativeLoad &&
            io.wakeup(port).bits.operandClass === cls) {
          source.load := io.wakeup(port).bits.load
        }
      }
    }
    for (destinationIndex <- 0 until p.maxDestinationOperands) {
      val renamed = txn.uop.destinations(destinationIndex)
      val destination = row.destinations(destinationIndex)
      val isT = renamed.kind === OperandKind.T
      val selected = renamed.valid &&
        !(decoded.memory.isStore && uopClass === OooUopClass.Std)
      destination.valid := selected
      destination.kind := destinationKind(renamed.kind)
      destination.atag := renamed.atag
      destination.relativeIndex := Mux(isT,
        renamed.tSeqIndex, renamed.uSeqIndex)
      destination.ptag := renamed.ptag
      destination.ptagGeneration := renamed.pGeneration
      destination.localTag := Mux(isT, renamed.ttag, renamed.utag)
      destination.localSequence := localDestinationSequence(renamed, isT)
      row.payload.previousPDestinations(destinationIndex).valid := selected &&
        renamed.kind === OperandKind.Gpr && renamed.previousPtagValid
      row.payload.previousPDestinations(destinationIndex).ptag :=
        renamed.previousPtag
      row.payload.previousPDestinations(destinationIndex).ptagGeneration :=
        renamed.previousPGeneration
    }
    row
  }

  val admissionValid = Wire(Vec(admissionCount, Bool()))
  val admissionTxn = Wire(Vec(admissionCount, new DispatchTxn(core)))
  val admissionClass = Wire(Vec(admissionCount, OooUopClass()))
  val admissionChild = Wire(Vec(admissionCount,
    UInt(math.max(1, log2Ceil(p.maxDispatchWritesPerInstruction)).W)))
  val admissionCapability = Wire(Vec(admissionCount,
    UInt(OooIexDomainCapability.Count.W)))
  val admissionBankMask = Wire(Vec(admissionCount, UInt(p.iqBankCount.W)))
  val admissionChannelExact = Wire(Vec(admissionCount, Bool()))
  admissionValid.foreach(_ := false.B)
  admissionTxn.foreach(_ := 0.U.asTypeOf(new DispatchTxn(core)))
  admissionClass.foreach(_ := OooUopClass.Alu)
  admissionChild.foreach(_ := 0.U)
  admissionCapability.foreach(_ := 0.U)
  admissionBankMask.foreach(_ := 0.U)
  admissionChannelExact.foreach(_ := false.B)

  private def laneBankMask(lane: Int, laneCount: Int): BigInt =
    (0 until p.iqBankCount).filter(_ % laneCount == lane).foldLeft(BigInt(0))(
      (mask, bank) => mask | (BigInt(1) << bank))
  private val allBanks = ((BigInt(1) << p.iqBankCount) - 1)
  private def txnIdentityExact(txn: DispatchTxn): Bool =
    txn.uop.decoded.valid &&
      txn.uop.decoded.rob.stid < p.stidCount.U &&
      txn.uop.decoded.rob.peId === txn.uop.decoded.instruction.parent.identity.peId &&
      txn.uop.decoded.rob.stid === txn.uop.decoded.instruction.parent.identity.stid &&
      (!txn.uop.decoded.instruction.parent.prediction.valid ||
        txn.uop.decoded.instruction.parent.identity.epoch ===
          txn.uop.decoded.instruction.parent.prediction.epoch)
  private def classificationExact(
      txn: DispatchTxn,
      uopClass: UopClass.Type,
      dispatchClass: Int,
      issueClass: Int,
      capabilityMask: BigInt,
      dispatchWrites: Int): Bool = {
    val classification = txn.uop.decoded.classification
    txnIdentityExact(txn) && txn.uop.decoded.uopClass === uopClass &&
      classification.valid &&
      classification.disposition === OooOpcodeDisposition.Dispatch.U &&
      classification.dispatchClass === dispatchClass.U &&
      classification.dispatchWrites === dispatchWrites.U &&
      classification.dispatchDemand(issueClass).orR &&
      (classification.executionPipeCapability(issueClass) &
        capabilityMask.U).orR
  }
  private def loadMemoryExact(txn: DispatchTxn): Bool = {
    val decoded = txn.uop.decoded
    decoded.memory.valid && decoded.memory.isLoad && !decoded.memory.isStore &&
      decoded.memory.requestCount === 1.U &&
      decoded.classification.memoryRequestCount === 1.U &&
      txn.memoryOrder.requestCount === 1.U
  }
  private def storeMemoryExact(txn: DispatchTxn): Bool = {
    val decoded = txn.uop.decoded
    decoded.memory.valid && !decoded.memory.isLoad && decoded.memory.isStore &&
      decoded.memory.requestCount === 1.U &&
      decoded.classification.memoryRequestCount === 1.U &&
      txn.memoryOrder.requestCount === 1.U
  }
  private val systemCapabilityMask = OooIexDomainCapability.mask(
    OooIexDomainCapability.MultiCycleAlu,
    OooIexDomainCapability.System,
    OooIexDomainCapability.PointerAuth)
  private def systemClassificationExact(txn: DispatchTxn): Bool = {
    val decoded = txn.uop.decoded
    val classification = decoded.classification
    val alu = decoded.uopClass === UopClass.Alu &&
      classification.dispatchClass === OooDispatchClass.Alu.U &&
      classification.dispatchDemand(OooDispatchClass.Alu - 1).orR &&
      (classification.executionPipeCapability(OooDispatchClass.Alu - 1) &
        systemCapabilityMask.U).orR
    val system = decoded.uopClass === UopClass.System &&
      classification.dispatchClass === OooDispatchClass.Sys.U &&
      classification.dispatchDemand(OooDispatchClass.Sys - 1).orR &&
      (classification.executionPipeCapability(OooDispatchClass.Sys - 1) &
        systemCapabilityMask.U).orR
    txnIdentityExact(txn) && classification.valid &&
      classification.disposition === OooOpcodeDisposition.Dispatch.U &&
      classification.dispatchWrites === 1.U && !decoded.memory.valid &&
      (alu || system)
  }

  var admission = 0
  for (lane <- 0 until core.iex.aluPipes) {
    val channel = io.dispatch.aluDispatch(lane)
    admissionValid(admission) := channel.valid
    admissionTxn(admission) := channel.bits
    admissionClass(admission) := OooUopClass.Alu
    admissionCapability(admission) := channel.bits.uop.decoded.classification
      .executionPipeCapability(OooDispatchClass.Alu - 1)
    admissionBankMask(admission) := laneBankMask(
      lane, core.iex.aluPipes).U
    admissionChannelExact(admission) := classificationExact(
      channel.bits, UopClass.Alu, OooDispatchClass.Alu,
      OooDispatchClass.Alu - 1,
      OooIexDomainCapability.mask(OooIexDomainCapability.SimpleAlu), 1) &&
      !channel.bits.uop.decoded.memory.valid
    admission += 1
  }
  for (lane <- 0 until core.iex.bruPipes) {
    val channel = io.dispatch.bruDispatch(lane)
    admissionValid(admission) := channel.valid
    admissionTxn(admission) := channel.bits
    admissionClass(admission) := OooUopClass.Bru
    admissionCapability(admission) := channel.bits.uop.decoded.classification
      .executionPipeCapability(OooDispatchClass.Bru - 1)
    admissionBankMask(admission) := allBanks.U
    admissionChannelExact(admission) := classificationExact(
      channel.bits, UopClass.Bru, OooDispatchClass.Bru,
      OooDispatchClass.Bru - 1,
      OooIexDomainCapability.mask(OooIexDomainCapability.Branch), 1) &&
      !channel.bits.uop.decoded.memory.valid
    admission += 1
  }
  for (lane <- 0 until core.iex.aguPipes) {
    val channel = io.dispatch.aguDispatch(lane)
    admissionValid(admission) := channel.valid
    admissionTxn(admission) := channel.bits
    admissionClass(admission) := OooUopClass.Agu
    admissionCapability(admission) := channel.bits.uop.decoded.classification
      .executionPipeCapability(OooDispatchClass.Agu - 1)
    admissionBankMask(admission) := laneBankMask(
      lane, core.iex.aguPipes).U
    admissionChannelExact(admission) := classificationExact(
      channel.bits, UopClass.Agu, OooDispatchClass.Agu,
      OooDispatchClass.Agu - 1,
      OooIexDomainCapability.mask(OooIexDomainCapability.LoadAddress), 1) &&
      loadMemoryExact(channel.bits)
    admission += 1
  }
  val storeAdmissionBase = admission
  for (lane <- 0 until core.iex.stdPipes) {
    val channel = io.dispatch.storeDispatch(lane)
    val address = admission
    val data = admission + 1
    admissionValid(address) := channel.valid
    admissionTxn(address) := channel.bits.sta
    admissionClass(address) := OooUopClass.Agu
    admissionChild(address) := 0.U
    admissionCapability(address) := channel.bits.sta.uop.decoded.classification
      .executionPipeCapability(OooDispatchClass.Agu - 1)
    admissionBankMask(address) := VecInit((0 until p.iqBankCount).map { bank =>
      (bank % core.iex.aguPipes).U === channel.bits.aguPipe
    }).asUInt
    admissionValid(data) := channel.valid
    admissionTxn(data) := channel.bits.std
    admissionClass(data) := OooUopClass.Std
    admissionChild(data) := 1.U
    admissionCapability(data) := channel.bits.std.uop.decoded.classification
      .executionPipeCapability(OooDispatchClass.Std - 1)
    admissionBankMask(data) := VecInit((0 until p.iqBankCount).map { bank =>
      (bank % core.iex.stdPipes).U === channel.bits.stdPipe
    }).asUInt
    val pairExact = classificationExact(channel.bits.sta, UopClass.Std,
      OooDispatchClass.Std, OooDispatchClass.Agu - 1,
      OooIexDomainCapability.mask(OooIexDomainCapability.StoreAddress), 2) &&
      classificationExact(channel.bits.std, UopClass.Std,
        OooDispatchClass.Std, OooDispatchClass.Std - 1,
        OooIexDomainCapability.mask(OooIexDomainCapability.StoreData), 2) &&
      channel.bits.sta.uop.decoded.classification.splitKind =/=
        OooLateSplitKind.None.U &&
      storeMemoryExact(channel.bits.sta) &&
      storeMemoryExact(channel.bits.std) &&
      channel.bits.sta.uop.decoded.memory.asUInt ===
        channel.bits.std.uop.decoded.memory.asUInt &&
      channel.bits.sta.transactionId === channel.bits.std.transactionId &&
      channel.bits.sta.uop.decoded.rob.asUInt ===
        channel.bits.std.uop.decoded.rob.asUInt &&
      channel.bits.sta.memoryOrder.asUInt ===
        channel.bits.std.memoryOrder.asUInt &&
      channel.bits.sta.pcBufferIndexOffset.asUInt ===
        channel.bits.std.pcBufferIndexOffset.asUInt
    admissionChannelExact(address) := pairExact
    admissionChannelExact(data) := pairExact
    admission += 2
  }
  for (lane <- 0 until core.iex.systemMulticycleQueues) {
    val channel = io.dispatch.systemDispatch(lane)
    admissionValid(admission) := channel.valid
    admissionTxn(admission) := channel.bits
    admissionClass(admission) := OooUopClass.Sys
    admissionCapability(admission) := Mux(
      channel.bits.uop.decoded.classification.dispatchClass ===
        OooDispatchClass.Alu.U,
      channel.bits.uop.decoded.classification.executionPipeCapability(
        OooDispatchClass.Alu - 1),
      channel.bits.uop.decoded.classification.executionPipeCapability(
        OooDispatchClass.Sys - 1))
    admissionBankMask(admission) := allBanks.U
    admissionChannelExact(admission) := systemClassificationExact(channel.bits)
    admission += 1
  }
  for (lane <- 0 until core.iex.cmdIssueQueues) {
    val channel = io.dispatch.cmdDispatch(lane)
    admissionValid(admission) := channel.valid
    admissionTxn(admission) := channel.bits
    admissionClass(admission) := OooUopClass.Cmd
    admissionCapability(admission) := channel.bits.uop.decoded.classification
      .executionPipeCapability(OooDispatchClass.Cmd - 1)
    admissionBankMask(admission) := allBanks.U
    admissionChannelExact(admission) := classificationExact(
      channel.bits, UopClass.Cmd, OooDispatchClass.Cmd,
      OooDispatchClass.Cmd - 1,
      OooIexDomainCapability.mask(OooIexDomainCapability.EngineCommand), 1) &&
      !channel.bits.uop.decoded.memory.valid
    admission += 1
  }
  require(admission == admissionCount)

  val selectedValid = Wire(Vec(admissionCount, Bool()))
  val selectedBank = Wire(Vec(admissionCount, UInt(p.iqBankWidth.W)))
  val selectedEntry = Wire(Vec(admissionCount, UInt(p.iqEntryWidth.W)))
  for (index <- 0 until admissionCount) {
    val candidates = Wire(Vec(p.iqBankCount * p.iqEntriesPerBank, Bool()))
    for (bank <- 0 until p.iqBankCount; entry <- 0 until p.iqEntriesPerBank) {
      val flat = bank * p.iqEntriesPerBank + entry
      val claimedByOlder = (0 until index).map { older =>
        admissionValid(older) && selectedValid(older) &&
          admissionClass(older) === admissionClass(index) &&
          selectedBank(older) === bank.U && selectedEntry(older) === entry.U
      }.foldLeft(false.B)(_ || _)
      candidates(flat) := admissionBankMask(index)(bank) &&
        slotState(admissionClass(index).asUInt)(bank)(entry) ===
          OooIexIssueSlotState.Free && !claimedByOlder
    }
    val selectedOH = PriorityEncoderOH(candidates.asUInt)
    val selectedFlat = PriorityEncoder(candidates.asUInt)
    selectedValid(index) := candidates.asUInt.orR
    selectedBank(index) := selectedFlat / p.iqEntriesPerBank.U
    selectedEntry(index) := selectedFlat % p.iqEntriesPerBank.U
    assert(PopCount(selectedOH) <= 1.U,
      "one dispatch child may select at most one physical IQ row")
  }

  val recoveryState = RegInit(OooIexRecoveryScanState.Idle)
  val retainedRecovery = Reg(new RecoveryPlan(core))
  val recoveryCursor = RegInit(0.U(p.iexRecoveryScanCursorWidth.W))
  val recoveryKillMask = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(false.B)))))))
  val prepareExact =
    io.recovery.prepare.bits.phase === RecoveryPhase.Prepare &&
    io.recovery.prepare.bits.trigger.stid < p.stidCount.U &&
    RecoveryPlanContract.legalSuffixWindow(io.recovery.prepare.bits)
  val preparing = io.recovery.prepare.valid && prepareExact
  val recoveryTargetStid = Mux(recoveryState === OooIexRecoveryScanState.Idle,
    io.recovery.prepare.bits.trigger.stid, retainedRecovery.trigger.stid)
  val recoveryTargetActive = preparing ||
    recoveryState =/= OooIexRecoveryScanState.Idle
  io.recoveryIdle := recoveryState === OooIexRecoveryScanState.Idle &&
    !io.recovery.prepare.valid
  private def targetFenced(stid: UInt): Bool =
    recoveryTargetActive && stid === recoveryTargetStid

  val admissionReady = Wire(Vec(admissionCount, Bool()))
  for (index <- 0 until admissionCount) {
    admissionReady(index) := selectedValid(index) &&
      admissionChannelExact(index) &&
      !targetFenced(admissionTxn(index).uop.decoded.rob.stid)
  }

  var readyIndex = 0
  for (lane <- 0 until core.iex.aluPipes) {
    io.dispatch.aluDispatch(lane).ready := admissionReady(readyIndex)
    readyIndex += 1
  }
  for (lane <- 0 until core.iex.bruPipes) {
    io.dispatch.bruDispatch(lane).ready := admissionReady(readyIndex)
    readyIndex += 1
  }
  for (lane <- 0 until core.iex.aguPipes) {
    io.dispatch.aguDispatch(lane).ready := admissionReady(readyIndex)
    readyIndex += 1
  }
  val storeReady = Wire(Vec(core.iex.stdPipes, Bool()))
  for (lane <- 0 until core.iex.stdPipes) {
    val address = readyIndex
    val data = readyIndex + 1
    val earlierStore = (0 until lane).map { older =>
      io.dispatch.storeDispatch(older).valid
    }.foldLeft(false.B)(_ || _)
    val reservePortReady = !requireStoreReservation.B ||
      (io.storeReserve.ready && !earlierStore)
    storeReady(lane) := admissionReady(address) && admissionReady(data) &&
      reservePortReady
    io.dispatch.storeDispatch(lane).ready := storeReady(lane)
    readyIndex += 2
  }
  for (lane <- 0 until core.iex.systemMulticycleQueues) {
    io.dispatch.systemDispatch(lane).ready := admissionReady(readyIndex)
    readyIndex += 1
  }
  for (lane <- 0 until core.iex.cmdIssueQueues) {
    io.dispatch.cmdDispatch(lane).ready := admissionReady(readyIndex)
    readyIndex += 1
  }
  require(readyIndex == admissionCount)

  val admissionFire = Wire(Vec(admissionCount, Bool()))
  var fireIndex = 0
  for (lane <- 0 until core.iex.aluPipes) {
    admissionFire(fireIndex) := io.dispatch.aluDispatch(lane).fire
    fireIndex += 1
  }

  for (lane <- 0 until core.iex.bruPipes) {
    admissionFire(fireIndex) := io.dispatch.bruDispatch(lane).fire
    fireIndex += 1
  }
  for (lane <- 0 until core.iex.aguPipes) {
    admissionFire(fireIndex) := io.dispatch.aguDispatch(lane).fire
    fireIndex += 1
  }
  for (lane <- 0 until core.iex.stdPipes) {
    admissionFire(fireIndex) := io.dispatch.storeDispatch(lane).fire
    admissionFire(fireIndex + 1) := io.dispatch.storeDispatch(lane).fire
    fireIndex += 2
  }
  for (lane <- 0 until core.iex.systemMulticycleQueues) {
    admissionFire(fireIndex) := io.dispatch.systemDispatch(lane).fire
    fireIndex += 1
  }
  for (lane <- 0 until core.iex.cmdIssueQueues) {
    admissionFire(fireIndex) := io.dispatch.cmdDispatch(lane).fire
    fireIndex += 1
  }
  require(fireIndex == admissionCount)

  // A logical memory transaction is allocated by its only leader. Loads have
  // one AGU child; a split store's address child is the leader and its data
  // child receives the same candidate below.
  val memoryLeaderFire = Wire(Vec(admissionCount, Bool()))
  val loadLeaderFire = Wire(Vec(admissionCount, Bool()))
  val admissionMemoryTransaction = Wire(Vec(admissionCount,
    new OooIexMemoryTransactionIdentity(p)))
  val admissionInitialLoadAttempt = Wire(Vec(admissionCount,
    UInt(p.loadGenerationWidth.W)))
  for (index <- 0 until admissionCount) {
    val decoded = admissionTxn(index).uop.decoded
    val leader = decoded.memory.valid &&
      admissionClass(index) === OooUopClass.Agu &&
      admissionChild(index) === 0.U
    memoryLeaderFire(index) := admissionFire(index) && leader
    loadLeaderFire(index) := memoryLeaderFire(index) && decoded.memory.isLoad
    val precedingMemoryLeaders = if (index == 0) 0.U else
      PopCount(memoryLeaderFire.take(index))
    val transactionCandidate = memoryTransactionSerial +
      precedingMemoryLeaders
    admissionMemoryTransaction(index).value :=
      transactionCandidate(p.memoryTransactionIdWidth - 1, 0)
    admissionMemoryTransaction(index).generation :=
      transactionCandidate(memoryTransactionSerialWidth - 1,
        p.memoryTransactionIdWidth)
    val precedingLoadLeaders = if (index == 0) 0.U else
      PopCount(loadLeaderFire.take(index))
    admissionInitialLoadAttempt(index) := initialLoadAttemptSerial +
      precedingLoadLeaders + 1.U
  }
  for (lane <- 0 until core.iex.stdPipes) {
    val address = storeAdmissionBase + 2 * lane
    val data = address + 1
    admissionMemoryTransaction(data) := admissionMemoryTransaction(address)
  }

  when(PopCount(memoryLeaderFire).orR) {
    memoryTransactionSerial := memoryTransactionSerial +
      PopCount(memoryLeaderFire)
  }
  when(PopCount(loadLeaderFire).orR) {
    initialLoadAttemptSerial := initialLoadAttemptSerial +
      PopCount(loadLeaderFire)
  }

  val firstStoreValid = io.dispatch.storeDispatch.map(_.valid).foldLeft(false.B)(_ || _)
  val firstStoreOH = PriorityEncoderOH(VecInit(
    io.dispatch.storeDispatch.map(_.valid)).asUInt)
  val firstStoreLane = PriorityEncoder(VecInit(
    io.dispatch.storeDispatch.map(_.valid)).asUInt)
  val firstStoreAddress = Wire(UInt(admissionIndexWidth.W))
  firstStoreAddress := storeAdmissionBase.U(admissionIndexWidth.W) +
    (firstStoreLane << 1)
  val safeFirstStoreAddress = Mux(firstStoreValid,
    firstStoreAddress, storeAdmissionBase.U)
  val storeReserveRow = makeRow(
    admissionTxn(safeFirstStoreAddress), OooUopClass.Agu, 0.U,
    selectedBank(safeFirstStoreAddress), selectedEntry(safeFirstStoreAddress),
    reservationEpoch(OooUopClass.Agu.asUInt)(selectedBank(safeFirstStoreAddress))(
      selectedEntry(safeFirstStoreAddress)) + 1.U,
    admissionCapability(safeFirstStoreAddress),
    admissionMemoryTransaction(safeFirstStoreAddress),
    admissionInitialLoadAttempt(safeFirstStoreAddress))
  io.storeReserve.valid := requireStoreReservation.B && firstStoreValid &&
    firstStoreOH.orR && storeReady(firstStoreLane)
  io.storeReserve.bits := storeReserveRow

  for (uopClass <- 0 until p.iqClassCount;
       bank <- 0 until p.iqBankCount;
       entry <- 0 until p.iqEntriesPerBank) {
    when(slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.BoundS2 &&
        !targetFenced(scheduleRows(uopClass)(bank)(entry).stid)) {
      slotState(uopClass)(bank)(entry) := OooIexIssueSlotState.ResidentS3
    }
  }

  for (index <- 0 until admissionCount) {
    when(admissionFire(index)) {
      val cls = admissionClass(index).asUInt
      val bank = selectedBank(index)
      val entry = selectedEntry(index)
      val nextEpoch = reservationEpoch(cls)(bank)(entry) + 1.U
      val row = makeRow(admissionTxn(index), admissionClass(index),
        admissionChild(index), bank, entry, nextEpoch,
        admissionCapability(index), admissionMemoryTransaction(index),
        admissionInitialLoadAttempt(index))
      assert(slotState(cls)(bank)(entry) === OooIexIssueSlotState.Free,
        "canonical dispatch may bind only a free physical IQ row")
      scheduleRows(cls)(bank)(entry) := row.schedule
      capabilityRows(cls)(bank)(entry) := admissionCapability(index)
      reservationEpoch(cls)(bank)(entry) := nextEpoch
      for (targetClass <- 0 until p.iqClassCount;
           targetBank <- 0 until p.iqBankCount) {
        when(cls === targetClass.U && bank === targetBank.U) {
          payloadRows(targetClass)(targetBank).write(entry, row.payload)
        }
      }
      slotState(cls)(bank)(entry) := OooIexIssueSlotState.BoundS2
    }
  }
  for (left <- 0 until admissionCount; right <- left + 1 until admissionCount) {
    when(admissionFire(left) && admissionFire(right)) {
      assert(admissionClass(left) =/= admissionClass(right) ||
        selectedBank(left) =/= selectedBank(right) ||
        selectedEntry(left) =/= selectedEntry(right),
        "simultaneously accepted dispatch children need distinct IQ rows")
    }
  }

  def sourceLoadCanceled(
      row: OooIexScheduleRow,
      source: OooIexSourceState): Bool = io.loadCancel.map { cancel =>
    cancel.valid && cancel.bits.load.valid && source.valid &&
      source.specReady && source.load.valid &&
      cancel.bits.stid === row.stid && cancel.bits.epoch === row.epoch &&
      cancel.bits.load.asUInt === source.load.asUInt
  }.reduce(_ || _)
  def rowLoadCanceled(row: OooIexScheduleRow): Bool =
    row.sources.map(sourceLoadCanceled(row, _)).reduce(_ || _)

  for (uopClass <- 0 until p.iqClassCount;
       bank <- 0 until p.iqBankCount;
       entry <- 0 until p.iqEntriesPerBank;
       sourceIndex <- 0 until p.maxSourceOperands) {
    val row = scheduleRows(uopClass)(bank)(entry)
    val source = row.sources(sourceIndex)
    val committedMatch = io.wakeup.map { wakeup =>
      val tagExact = Mux(source.operandClass === OperandClass.P,
        wakeup.bits.ptag === source.ptag &&
          wakeup.bits.ptagGeneration === source.ptagGeneration,
        wakeup.bits.localTag === source.localTag &&
          wakeup.bits.localSequence.asUInt === source.localSequence.asUInt)
      wakeup.valid && wakeup.bits.kind === OooIexWakeupKind.Committed &&
        wakeup.bits.stid === row.stid && wakeup.bits.epoch === row.epoch &&
        wakeup.bits.operandClass === source.operandClass && tagExact
    }.reduce(_ || _)
    val speculativeMatches = VecInit(io.wakeup.map { wakeup =>
      val tagExact = Mux(source.operandClass === OperandClass.P,
        wakeup.bits.ptag === source.ptag &&
          wakeup.bits.ptagGeneration === source.ptagGeneration,
        wakeup.bits.localTag === source.localTag &&
          wakeup.bits.localSequence.asUInt === source.localSequence.asUInt)
      wakeup.valid && wakeup.bits.kind === OooIexWakeupKind.SpeculativeLoad &&
        wakeup.bits.load.valid && wakeup.bits.stid === row.stid &&
        wakeup.bits.epoch === row.epoch &&
        wakeup.bits.operandClass === source.operandClass && tagExact
    })
    val canceled = sourceLoadCanceled(row, source)
    when(row.valid && source.valid && !targetFenced(row.stid) &&
        committedMatch) {
      source.ready := true.B
      source.specReady := false.B
      source.load := 0.U.asTypeOf(source.load)
    }.elsewhen(row.valid && source.valid && canceled) {
      source.specReady := false.B
      source.load := 0.U.asTypeOf(source.load)
    }.elsewhen(row.valid && source.valid && !source.ready &&
        !targetFenced(row.stid) && speculativeMatches.asUInt.orR) {
      source.specReady := true.B
      for (port <- 0 until p.iexWakeupPorts) {
        when(speculativeMatches(port)) {
          source.load := io.wakeup(port).bits.load
        }
      }
    }
    assert(PopCount(speculativeMatches) <= 1.U,
      "one IQ source cannot accept multiple speculative load attempts")
  }

  private val storeClasses = Seq(
    OooUopClass.Agu.asUInt.litValue.toInt,
    OooUopClass.Std.asUInt.litValue.toInt)
  private val storePhysicalRows =
    storeClasses.size * p.iqBankCount * p.iqEntriesPerBank
  val storeIssueFrontier = Module(
    new OooIexStoreIssueFrontier(p, storePhysicalRows))
  val storeIssueAllowed = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount, Vec(p.iqEntriesPerBank, Bool()))))
  for (uopClass <- 0 until p.iqClassCount;
       bank <- 0 until p.iqBankCount;
       entry <- 0 until p.iqEntriesPerBank) {
    storeIssueAllowed(uopClass)(bank)(entry) := true.B
  }
  for ((uopClass, classIndex) <- storeClasses.zipWithIndex;
       bank <- 0 until p.iqBankCount;
       entry <- 0 until p.iqEntriesPerBank) {
    val flat = (classIndex * p.iqBankCount + bank) *
      p.iqEntriesPerBank + entry
    val row = scheduleRows(uopClass)(bank)(entry)
    val candidate = storeIssueFrontier.io.candidates(flat)
    candidate.resident :=
      slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.ResidentS3 &&
        row.valid
    candidate.isStore := row.isStore
    candidate.peId := row.peId
    candidate.stid := row.stid
    candidate.order := row.storeOrder
    storeIssueAllowed(uopClass)(bank)(entry) :=
      storeIssueFrontier.io.allowed(flat)
  }
  io.storeFrontierBlocked := storeIssueFrontier.io.blockedCount

  for (lane <- 0 until p.iexReleaseWidth) {
    val release = io.releases(lane).bits
    val cls = release.dispatch.reservation.uopClass.asUInt
    val classInRange = cls < p.iqClassCount.U
    val bankInRange = release.dispatch.reservation.bank < p.iqBankCount.U
    val entryInRange = release.dispatch.reservation.speculativeSlot <
      p.iqEntriesPerBank.U
    val safeClass = Mux(classInRange, cls, 0.U)
    val safeBank = Mux(bankInRange,
      release.dispatch.reservation.bank, 0.U)
    val safeEntry = Mux(entryInRange,
      release.dispatch.reservation.speculativeSlot, 0.U)
    val row = scheduleRows(safeClass)(safeBank)(safeEntry)
    val collision = (0 until lane).map { older =>
      io.releases(older).valid &&
        io.releases(older).bits.dispatch.reservation.asUInt ===
          release.dispatch.reservation.asUInt
    }.foldLeft(false.B)(_ || _)
    val exact = classInRange && bankInRange && entryInRange &&
      release.dispatch.reservation.valid &&
      slotState(safeClass)(safeBank)(safeEntry) ===
        OooIexIssueSlotState.ResidentS3 && row.valid && row.inFlight &&
      sameMember(release.member, row.member) &&
      release.dispatch.peId === row.peId && release.dispatch.stid === row.stid &&
      release.dispatch.epoch === row.epoch &&
      release.dispatch.transactionId === row.transactionId &&
      release.dispatch.reservation.asUInt === row.reservation.asUInt &&
      !collision && !targetFenced(row.stid)
    io.releases(lane).ready := exact
    io.releaseRejecteds(lane).valid := io.releases(lane).valid && !exact
    io.releaseRejecteds(lane).bits.member := release.member
    io.releaseRejecteds(lane).bits.peId := release.dispatch.peId
    io.releaseRejecteds(lane).bits.stid := release.dispatch.stid
    io.releaseRejecteds(lane).bits.epoch := release.dispatch.epoch
    io.releaseRejecteds(lane).bits.transactionId :=
      release.dispatch.transactionId
    io.releaseRejecteds(lane).bits.reservation :=
      release.dispatch.reservation
    when(io.releases(lane).fire) {
      slotState(safeClass)(safeBank)(safeEntry) := OooIexIssueSlotState.Free
      scheduleRows(safeClass)(safeBank)(safeEntry) :=
        0.U.asTypeOf(new OooIexScheduleRow(p))
    }
  }

  val scanKill = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount,
      Vec(p.iexRecoveryScanEntriesPerBankPerCycle, Bool()))))
  for (uopClass <- 0 until p.iqClassCount;
       bank <- 0 until p.iqBankCount;
       lane <- 0 until p.iexRecoveryScanEntriesPerBankPerCycle) {
    val entryWide = recoveryCursor *
      p.iexRecoveryScanEntriesPerBankPerCycle.U + lane.U
    val entry = entryWide(p.iqEntryWidth - 1, 0)
    val row = scheduleRows(uopClass)(bank)(entry)
    scanKill(uopClass)(bank)(lane) := row.valid &&
      RecoveryPlanContract.suffixMember(
        retainedRecovery,
        OooRecoveryMembership.robIdentity(p, core, row.member))
  }
  val scanLast = recoveryCursor === (p.iexRecoveryScanCycles - 1).U
  io.recovery.prepare.ready :=
    recoveryState === OooIexRecoveryScanState.Idle && prepareExact
  io.recovery.prepared.valid :=
    recoveryState === OooIexRecoveryScanState.Prepared
  io.recovery.prepared.bits := retainedRecovery
  io.recovery.prepared.bits.phase := RecoveryPhase.Prepare
  val applyExact = io.recovery.apply.valid &&
    io.recovery.apply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.apply.bits, retainedRecovery)
  val abortExact = io.recovery.abort.valid &&
    io.recovery.abort.bits.phase === RecoveryPhase.Abort &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.abort.bits, retainedRecovery)
  io.acceptedRecoveryApply.valid :=
    recoveryState === OooIexRecoveryScanState.Prepared && applyExact
  io.acceptedRecoveryApply.bits := retainedRecovery
  io.acceptedRecoveryApply.bits.phase := RecoveryPhase.Apply

  when(io.recovery.prepare.fire) {
    retainedRecovery := io.recovery.prepare.bits
    recoveryCursor := 0.U
    recoveryKillMask := 0.U.asTypeOf(recoveryKillMask)
    recoveryState := OooIexRecoveryScanState.Scan
  }.elsewhen(recoveryState === OooIexRecoveryScanState.Scan) {
    for (uopClass <- 0 until p.iqClassCount;
         bank <- 0 until p.iqBankCount;
         lane <- 0 until p.iexRecoveryScanEntriesPerBankPerCycle) {
      val entryWide = recoveryCursor *
        p.iexRecoveryScanEntriesPerBankPerCycle.U + lane.U
      val entry = entryWide(p.iqEntryWidth - 1, 0)
      recoveryKillMask(uopClass)(bank)(entry) :=
        scanKill(uopClass)(bank)(lane)
    }
    when(scanLast) {
      recoveryState := OooIexRecoveryScanState.Prepared
    }.otherwise {
      recoveryCursor := recoveryCursor + 1.U
    }
  }.elsewhen(recoveryState === OooIexRecoveryScanState.Prepared &&
      applyExact) {
    for (uopClass <- 0 until p.iqClassCount;
         bank <- 0 until p.iqBankCount;
         entry <- 0 until p.iqEntriesPerBank) {
      when(recoveryKillMask(uopClass)(bank)(entry)) {
        slotState(uopClass)(bank)(entry) := OooIexIssueSlotState.Free
        scheduleRows(uopClass)(bank)(entry) :=
          0.U.asTypeOf(new OooIexScheduleRow(p))
      }
    }
    recoveryState := OooIexRecoveryScanState.Idle
  }.elsewhen(recoveryState === OooIexRecoveryScanState.Prepared &&
      abortExact) {
    recoveryState := OooIexRecoveryScanState.Idle
  }
  when(io.recovery.apply.valid) {
    assert(recoveryState === OooIexRecoveryScanState.Prepared && applyExact,
      "IQ recovery apply requires the exact prepared transaction")
  }
  when(io.recovery.abort.valid) {
    assert(recoveryState === OooIexRecoveryScanState.Prepared && abortExact,
      "IQ recovery abort requires the exact prepared transaction")
  }

  private def policyReasons(
      domain: Int,
      uopClass: OooUopClass.Type,
      row: OooIexScheduleRow): UInt = {
    val query = Wire(new OooIexIssueBlockQuery(p))
    query.uopClass := uopClass
    query.stid := row.stid
    query.isStore := row.isStore
    OooIexIssueBlockMatrix.reasons(p, io.issuePolicy, domain, query)
  }

  val physicalCapabilities = VecInit(profile.pickerFunctions.map(
    _.capabilities.U(OooIexDomainCapability.Count.W)))
  for (left <- 0 until p.iexIssueDomainCount;
       right <- left + 1 until p.iexIssueDomainCount) {
    val overlap = VecInit((0 until p.iqClassCount).map { classIndex =>
      (io.pickBankEnables(left)(classIndex) &
        io.pickBankEnables(right)(classIndex)).orR
    }).asUInt.orR
    if ((profile.pickerFunctions(left).capabilities &
        profile.pickerFunctions(right).capabilities) != 0) {
      assert(!overlap,
        "picker projections may overlap only for disjoint capabilities")
    }
  }

  val pickers = Seq.fill(p.iexIssueDomainCount)(
    Module(new OooIexOldestReadyPicker(p)))
  for (domain <- 0 until p.iexIssueDomainCount) {
    val picker = pickers(domain)
    picker.io.classBankEnables := io.pickBankEnables(domain)
    picker.io.stidBlock := Mux(recoveryTargetActive,
      UIntToOH(recoveryTargetStid, p.stidCount), 0.U)

    val policyBlocked = Wire(Vec(p.iqClassCount,
      Vec(p.iqBankCount, Vec(p.iqEntriesPerBank, Bool()))))
    for (classIndex <- 0 until p.iqClassCount;
         bank <- 0 until p.iqBankCount;
         entry <- 0 until p.iqEntriesPerBank) {
      val row = scheduleRows(classIndex)(bank)(entry)
      val ready = row.sources.map(source =>
        !source.valid || source.ready || source.specReady).reduce(_ && _)
      val reasons = policyReasons(domain, OooUopClass.all(classIndex), row)
      policyBlocked(classIndex)(bank)(entry) :=
        io.pickBankEnables(domain)(classIndex)(bank) &&
          slotState(classIndex)(bank)(entry) ===
            OooIexIssueSlotState.ResidentS3 && row.valid && reasons.orR
      val candidate = picker.io.candidates(classIndex)(bank)(entry)
      candidate.eligible :=
        slotState(classIndex)(bank)(entry) ===
          OooIexIssueSlotState.ResidentS3 && row.valid && !row.inFlight &&
          ready && !rowLoadCanceled(row) &&
          storeIssueAllowed(classIndex)(bank)(entry) &&
          OooIexDomainCapability.covers(
            physicalCapabilities(domain),
            capabilityRows(classIndex)(bank)(entry)) &&
          !reasons.orR && !targetFenced(row.stid)
      candidate.peId := row.peId
      candidate.stid := row.stid
      candidate.epoch := row.epoch
      candidate.transactionId := row.transactionId
      candidate.member := row.member
      candidate.reservation := row.reservation
    }
    io.policyBlockedCount(domain) := PopCount(
      policyBlocked.toSeq.flatMap(_.toSeq.flatMap(_.toSeq)))

    val token = picker.io.pick.bits
    val cls = token.query.uopClass.asUInt
    val classInRange = cls < p.iqClassCount.U
    val bankInRange = token.query.bank < p.iqBankCount.U
    val entryInRange = token.query.entry < p.iqEntriesPerBank.U
    val safeClass = Mux(classInRange, cls, 0.U)
    val safeBank = Mux(bankInRange, token.query.bank, 0.U)
    val safeEntry = Mux(entryInRange, token.query.entry, 0.U)
    val row = scheduleRows(safeClass)(safeBank)(safeEntry)
    val residentExact = classInRange && bankInRange && entryInRange &&
      slotState(safeClass)(safeBank)(safeEntry) ===
        OooIexIssueSlotState.ResidentS3 && row.valid
    val identityExact = token.candidate.peId === row.peId &&
      token.candidate.stid === row.stid &&
      token.candidate.epoch === row.epoch &&
      token.candidate.transactionId === row.transactionId &&
      sameMember(token.candidate.member, row.member) &&
      token.candidate.reservation.asUInt === row.reservation.asUInt
    val domainExact = classInRange && bankInRange &&
      io.pickBankEnables(domain)(safeClass)(safeBank) &&
      OooIexDomainCapability.covers(physicalCapabilities(domain),
        capabilityRows(safeClass)(safeBank)(safeEntry))
    val baseExact = residentExact && identityExact && domainExact &&
      !row.inFlight && !rowLoadCanceled(row) &&
      storeIssueAllowed(safeClass)(safeBank)(safeEntry) &&
      !targetFenced(row.stid)
    val reasons = policyReasons(domain, token.query.uopClass, row)
    val claimExact = baseExact && !reasons.orR
    io.picks(domain).valid := picker.io.pick.valid && claimExact
    io.picks(domain).bits := token
    picker.io.pick.ready := Mux(claimExact, io.picks(domain).ready, true.B)
    io.pickRejectedByDomain(domain).valid :=
      picker.io.pick.valid && !baseExact
    io.pickRejectedByDomain(domain).bits.token := token
    io.pickRejectedByDomain(domain).bits.residentExact := residentExact
    io.pickRejectedByDomain(domain).bits.identityExact :=
      identityExact && domainExact
    io.pickRejectedByDomain(domain).bits.notInFlight := !row.inFlight
    io.pickMalformedByDomain(domain) := picker.io.malformed
    io.pickRecoveryBlockedByDomain(domain) := picker.io.blockedCanceled
    io.pickPolicyBlockedByDomain(domain).valid :=
      picker.io.pick.valid && baseExact && reasons.orR
    io.pickPolicyBlockedByDomain(domain).bits.token := token
    io.pickPolicyBlockedByDomain(domain).bits.reasonMask := reasons
    when(io.picks(domain).fire) {
      scheduleRows(safeClass)(safeBank)(safeEntry).inFlight := true.B
    }

    val retry = io.pickRetries(domain).bits
    val retryClass = retry.reservation.uopClass.asUInt
    val retryClassInRange = retryClass < p.iqClassCount.U
    val retryBankInRange = retry.reservation.bank < p.iqBankCount.U
    val retryEntryInRange = retry.reservation.speculativeSlot <
      p.iqEntriesPerBank.U
    val safeRetryClass = Mux(retryClassInRange, retryClass, 0.U)
    val safeRetryBank = Mux(retryBankInRange,
      retry.reservation.bank, 0.U)
    val safeRetryEntry = Mux(retryEntryInRange,
      retry.reservation.speculativeSlot, 0.U)
    val retryRow = scheduleRows(safeRetryClass)(safeRetryBank)(safeRetryEntry)
    val retryResidentExact = retryClassInRange && retryBankInRange &&
      retryEntryInRange &&
      slotState(safeRetryClass)(safeRetryBank)(safeRetryEntry) ===
        OooIexIssueSlotState.ResidentS3 && retryRow.valid
    val retryIdentityExact = sameMember(retry.member, retryRow.member) &&
      retry.reservation.asUInt === retryRow.reservation.asUInt
    val retryDomainExact = retryClassInRange &&
      io.pickBankEnables(domain)(safeRetryClass)(safeRetryBank) &&
      OooIexDomainCapability.covers(physicalCapabilities(domain),
        capabilityRows(safeRetryClass)(safeRetryBank)(safeRetryEntry))
    val sameCyclePick = io.picks(domain).fire &&
      safeRetryClass === safeClass && safeRetryBank === safeBank &&
      safeRetryEntry === safeEntry
    val wasInFlight = retryRow.inFlight || sameCyclePick
    val retryExact = retryResidentExact && retryIdentityExact &&
      retryDomainExact && wasInFlight && !targetFenced(retryRow.stid)
    io.pickRetryRejectedByDomain(domain).valid :=
      io.pickRetries(domain).valid && !retryExact
    io.pickRetryRejectedByDomain(domain).bits.retry := retry
    io.pickRetryRejectedByDomain(domain).bits.residentExact :=
      retryResidentExact
    io.pickRetryRejectedByDomain(domain).bits.identityExact :=
      retryIdentityExact && retryDomainExact
    io.pickRetryRejectedByDomain(domain).bits.wasInFlight := wasInFlight
    when(io.pickRetries(domain).valid && retryExact) {
      scheduleRows(safeRetryClass)(safeRetryBank)(safeRetryEntry).inFlight :=
        false.B
    }

    val query = io.queries(domain)
    val queryClass = query.uopClass.asUInt
    val queryClassInRange = queryClass < p.iqClassCount.U
    val queryBankInRange = query.bank < p.iqBankCount.U
    val queryEntryInRange = query.entry < p.iqEntriesPerBank.U
    val safeQueryClass = Mux(queryClassInRange, queryClass, 0.U)
    val safeQueryBank = Mux(queryBankInRange, query.bank, 0.U)
    val safeQueryEntry = Mux(queryEntryInRange, query.entry, 0.U)
    io.queryStates(domain) :=
      slotState(safeQueryClass)(safeQueryBank)(safeQueryEntry)
    val queryPayload = Wire(Vec(p.iqClassCount,
      Vec(p.iqBankCount, new OooIexPayloadSidecar(p))))
    for (queryClassIndex <- 0 until p.iqClassCount;
         queryBank <- 0 until p.iqBankCount) {
      queryPayload(queryClassIndex)(queryBank) :=
        payloadRows(queryClassIndex)(queryBank).read(safeQueryEntry)
    }
    io.queryRows(domain).schedule :=
      scheduleRows(safeQueryClass)(safeQueryBank)(safeQueryEntry)
    io.queryRows(domain).payload :=
      queryPayload(safeQueryClass)(safeQueryBank)
    val queryReady = io.queryRows(domain).sources.map(source =>
      !source.valid || source.ready || source.specReady).reduce(_ && _)
    val queryReasons = policyReasons(
      domain, query.uopClass, io.queryRows(domain).schedule)
    io.queryPolicyReasons(domain) := queryReasons
    io.queryPickables(domain) := queryClassInRange && queryBankInRange &&
      queryEntryInRange &&
      io.pickBankEnables(domain)(safeQueryClass)(safeQueryBank) &&
      OooIexDomainCapability.covers(physicalCapabilities(domain),
        capabilityRows(safeQueryClass)(safeQueryBank)(safeQueryEntry)) &&
      io.queryStates(domain) === OooIexIssueSlotState.ResidentS3 &&
      io.queryRows(domain).valid &&
      !io.queryRows(domain).schedule.inFlight && queryReady &&
      !rowLoadCanceled(io.queryRows(domain).schedule) &&
      storeIssueAllowed(safeQueryClass)(safeQueryBank)(safeQueryEntry) &&
      !queryReasons.orR && !targetFenced(io.queryRows(domain).stid)
  }

  for (uopClass <- 0 until p.iqClassCount; bank <- 0 until p.iqBankCount) {
    io.boundEntries(uopClass)(bank) := PopCount(VecInit(
      (0 until p.iqEntriesPerBank).map { entry =>
        slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.BoundS2
      }).asUInt)
    io.residentEntries(uopClass)(bank) := PopCount(VecInit(
      (0 until p.iqEntriesPerBank).map { entry =>
        slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.ResidentS3
      }).asUInt)
    io.inFlightEntries(uopClass)(bank) := PopCount(VecInit(
      (0 until p.iqEntriesPerBank).map { entry =>
        scheduleRows(uopClass)(bank)(entry).valid &&
          scheduleRows(uopClass)(bank)(entry).inFlight
      }).asUInt)
    for (entry <- 0 until p.iqEntriesPerBank) {
      assert((slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.Free) ===
        !scheduleRows(uopClass)(bank)(entry).valid,
        "one IQ row lifecycle must agree with its valid bit")
      assert(!scheduleRows(uopClass)(bank)(entry).inFlight ||
        slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.ResidentS3,
        "only a resident S3 row may be in flight")
    }
  }
}
