package linxcore.ooo

import chisel3._
import linxcore.common.{BoundaryKind, BranchPredictionSidecar, DestinationKind, OperandClass}

object OooUopClass extends ChiselEnum {
  val Alu, Bru, Agu, Std, Fsu, Sys, Cmd, Boundary = Value
}

object OooRecoveryCause extends ChiselEnum {
  val Branch, Exception, Interrupt, Nuke, Debug, CtuCancel = Value
}

/** Encoding-independent scalar memory address form emitted by D1. */
object OooMemoryAddressMode extends ChiselEnum {
  val None, BaseIndex, BaseOffset, PcOffset = Value
}

/** D1-normalized transformation applied to a register index before addition. */
object OooMemoryIndexMode extends ChiselEnum {
  val Identity, SignExtend32, ZeroExtend32, Negate = Value
}

/** Typed scalar memory controls carried with one canonical uop. */
class OooMemoryControl(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val isLoad = Bool()
  val isStore = Bool()
  val addressMode = OooMemoryAddressMode()
  val accessBytes = UInt(4.W)
  val signExtend = Bool()
  val offset = UInt(p.pcWidth.W)
  val indexMode = OooMemoryIndexMode()
  val indexShift = UInt(5.W)
  val addressSourceMask = UInt(p.maxSourceOperands.W)
  val dataSourceMask = UInt(p.maxSourceOperands.W)
  val writebackValid = Bool()
  val writebackPreIndex = Bool()
}

/** Independent proof obligations used by the ROB-owned non-flush window.
  * A producer may satisfy several obligations in one exact retained event.
  */
object OooNonFlushProof {
  val ExceptionSafe = 0
  val MemorySafe = 1
  val ControlSafe = 2
  val SerializationSafe = 3
  val Count = 4

  val ExceptionSafeMask = 1 << ExceptionSafe
  val MemorySafeMask = 1 << MemorySafe
  val ControlSafeMask = 1 << ControlSafe
  val SerializationSafeMask = 1 << SerializationSafe
}

class NativeBid(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val value = UInt(p.nativeBidWidth.W)
}

/** BROB order state is intentionally separate from the native BID field. */
class BrobPointer(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val bid = new NativeBid(p)
  val generation = UInt(p.brobGenerationWidth.W)
}

class RobGroupKey(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val ridSlot = UInt(p.ridSlotWidth.W)
  val ridGeneration = UInt(p.ridGenerationWidth.W)
}

class RobMemberKey(val p: OooParams = OooParams()) extends Bundle {
  val group = new RobGroupKey(p)
  val bid = new NativeBid(p)
  val brobGeneration = UInt(p.brobGenerationWidth.W)
  val memberIndex = UInt(p.robMemberIndexWidth.W)
  val residentGeneration = UInt(p.residentGenerationWidth.W)
}

class CanonicalParentKey(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val instructionId = UInt(p.instructionIdWidth.W)
  val epoch = UInt(p.epochWidth.W)
}

class OooPredictionRecord(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val predictionTag = UInt(p.predictionTagWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val fetchPacketUid = UInt(p.instructionIdWidth.W)
  val fetchSeq = UInt(p.instructionIdWidth.W)
  val requestPc = UInt(p.pcWidth.W)
  val taken = Bool()
  val branchPc = UInt(p.pcWidth.W)
  val target = UInt(p.pcWidth.W)
  val fallthroughPc = UInt(p.pcWidth.W)
  val kind = BoundaryKind()
  val provider = UInt(BranchPredictionSidecar.ProviderWidth.W)
  val stage = UInt(BranchPredictionSidecar.StageWidth.W)
  val confidence = UInt(2.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val epoch = UInt(p.epochWidth.W)
}

class ArchitecturalParentRef(val p: OooParams = OooParams()) extends Bundle {
  val key = new CanonicalParentKey(p)
  val pc = UInt(p.pcWidth.W)
  val rawInstruction = UInt(p.instructionWidth.W)
  val lengthBytes = UInt(p.instructionLengthWidth.W)
  val prediction = new OooPredictionRecord(p)
  val traceOwner = Bool()
  val preciseExceptionOwner = Bool()
}

/** One fixed-64-bit IFU or CTU parent presented to production OOO D1. */
class OooRawInstruction(val p: OooParams = OooParams()) extends Bundle {
  val parent = new ArchitecturalParentRef(p)
  val fetchFaultValid = Bool()
  val fetchFaultCause = UInt(p.trapCauseWidth.W)
}

/** Dense, same-STID architectural instruction prefix accepted by D1. */
class OooRawInstructionGroup(val p: OooParams = OooParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val validMask = UInt(p.instructionDecodeWidth.W)
  val entries = Vec(p.instructionDecodeWidth, new OooRawInstruction(p))
  val endOfStream = Bool()
}

class OooDecodedOperand(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val operandClass = OperandClass()
  val atag = UInt(p.archRegWidth.W)
  val relativeIndex = UInt(p.archRegWidth.W)
}

class OooDecodedDestination(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val kind = DestinationKind()
  val atag = UInt(p.archRegWidth.W)
  val relativeIndex = UInt(p.archRegWidth.W)
}

/** Canonical logical uop before D2 grouping and D3 rename.
  *
  * `plannedChildCount` includes D3/S1 late-split children.  The row itself is
  * still one logical uop until the owning split transaction reserves every
  * child atomically.
  */
class OooDecodedUop(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val identity = new CanonicalUopIdentity(p)
  val opcode = UInt(p.opcodeWidth.W)
  val recipe = new OooOpcodeRecipeMeta(p)
  val plannedChildCount = UInt(p.recipeUopCountWidth.W)
  val sources = Vec(p.maxSourceOperands, new OooDecodedOperand(p))
  val destinations = Vec(p.maxDestinationOperands, new OooDecodedDestination(p))
  val immediateValid = Bool()
  val immediate = UInt(p.pcWidth.W)
  val memory = new OooMemoryControl(p)
  val boundaryTargetValid = Bool()
  val boundaryTarget = UInt(p.pcWidth.W)
  val preciseTrap = Bool()
  val trapCause = UInt(p.trapCauseWidth.W)
}

class OooD1DecodedPacket(val p: OooParams = OooParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val endOfStream = Bool()
  val acceptedInstructionMask = UInt(p.instructionDecodeWidth.W)
  val uopMask = UInt(p.decodedUopWidth.W)
  val uops = Vec(p.decodedUopWidth, new OooDecodedUop(p))
  val ctuParentMask = UInt(p.instructionDecodeWidth.W)
  val ctuParents = Vec(p.instructionDecodeWidth, new OooRawInstruction(p))
  val complexParentMask = UInt(p.instructionDecodeWidth.W)
  val complexParents = Vec(p.instructionDecodeWidth, new OooRawInstruction(p))
  val illegalParentMask = UInt(p.instructionDecodeWidth.W)
  val fusedStartMask = UInt(p.instructionDecodeWidth.W)
  val fusedStopMask = UInt(p.instructionDecodeWidth.W)
  val demand = new InstructionDemand(p)
}

class CanonicalUopKey(val p: OooParams = OooParams()) extends Bundle {
  val primaryParent = new CanonicalParentKey(p)
  val uopOrdinal = UInt(p.robMemberIndexWidth.W)
  val uopCount = UInt(p.robMemberCountWidth.W)
}

class BoundarySidecar(val p: OooParams = OooParams()) extends Bundle {
  val start = Bool()
  val stop = Bool()
  val explicit = Bool()
  val opening = new BrobPointer(p)
  val closing = new BrobPointer(p)
}

class CanonicalUopIdentity(val p: OooParams = OooParams()) extends Bundle {
  val key = new CanonicalUopKey(p)
  val parentCount = UInt(p.architecturalParentCountWidth.W)
  val parents = Vec(p.maxArchitecturalParentRefs, new ArchitecturalParentRef(p))
  val boundary = new BoundarySidecar(p)
  val templateValid = Bool()
  val templateGroupId = UInt(p.templateGroupIdWidth.W)
  val templateGeneration = UInt(p.residentGenerationWidth.W)
}

class InstructionDemand(val p: OooParams = OooParams()) extends Bundle {
  val instructionRows = UInt(p.instructionCountWidth.W)
  val decodedUops = UInt(p.decodedUopCountWidth.W)
  val robGroups = UInt(p.robGroupCountWidth.W)
  val brobSlots = UInt(p.robGroupCountWidth.W)
  val pcBaseWrites = UInt(p.instructionCountWidth.W)
  val pDestinations = UInt(p.destinationDemandWidth.W)
  val tAllocations = UInt(p.destinationDemandWidth.W)
  val uAllocations = UInt(p.destinationDemandWidth.W)
  val mapQRows = UInt(p.destinationDemandWidth.W)
  val dispatchWritesByClass = Vec(p.iqClassCount, UInt(p.dispatchDemandWidth.W))
  val dispatchWritesByBank = Vec(p.iqBankCount, UInt(p.dispatchDemandWidth.W))
  val loadIds = UInt(p.memoryDemandWidth.W)
  val storeIds = UInt(p.memoryDemandWidth.W)
}

/** Next serial identities owned by one STID's canonical memory-order stream.
  *
  * `lsid` orders every memory effect. `loadId` and `storeId` are independent
  * type-local serials used by the future LHQ/STQ owners. None of these values
  * is a physical queue index.
  */
class OooMemoryIdState(val p: OooParams = OooParams()) extends Bundle {
  val lsid = UInt(p.lsidWidth.W)
  val loadId = UInt(p.lsidWidth.W)
  val storeId = UInt(p.lsidWidth.W)
}

/** Exact serial range assigned to one logical decoded uop.
  *
  * An active non-memory uop still carries `valid` and identical before/after
  * snapshots. This lets every ROB group retain a complete memory-tail chain,
  * including groups which happen not to contain a memory request.
  */
class OooMemoryOrderUopAllocation(val p: OooParams = OooParams())
    extends Bundle {
  val valid = Bool()
  val memoryValid = Bool()
  val isLoad = Bool()
  val isStore = Bool()
  val requestCount = UInt(p.memoryDemandWidth.W)
  val firstLsid = UInt(p.lsidWidth.W)
  val firstTypeId = UInt(p.lsidWidth.W)
  val before = new OooMemoryIdState(p)
  val after = new OooMemoryIdState(p)
}

/** All-or-none D3 memory-order lease retained until common S1 publication. */
class OooMemoryOrderReservationLease(val p: OooParams = OooParams())
    extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val uopMask = UInt(p.decodedUopWidth.W)
  val before = new OooMemoryIdState(p)
  val after = new OooMemoryIdState(p)
  val uops = Vec(p.decodedUopWidth,
    new OooMemoryOrderUopAllocation(p))
}

class OooMemoryOrderPrepareReject(val p: OooParams = OooParams())
    extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val requestedLoadIds = UInt(p.memoryDemandWidth.W)
  val requestedStoreIds = UInt(p.memoryDemandWidth.W)
  val calculatedLoadIds = UInt(p.memoryDemandWidth.W)
  val calculatedStoreIds = UInt(p.memoryDemandWidth.W)
  val occupied = Bool()
}

class OooMemoryOrderRecoveryPrepared(val p: OooParams = OooParams())
    extends Bundle {
  val valid = Bool()
  val stid = UInt(p.stidWidth.W)
  val oldPublishedTail = new OooMemoryIdState(p)
  val newTail = new OooMemoryIdState(p)
  val provisionalKilled = Bool()
}

class OooMemoryOrderRecoveryReject(val p: OooParams = OooParams())
    extends Bundle {
  val requested = new OooRobRecoveryPlan(p)
  val stidInRange = Bool()
  val publishedChainExact = Bool()
  val liveTailExact = Bool()
  val provisionalExact = Bool()
}

class PcBufferToken(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val index = UInt(p.pcBufferIndexWidth.W)
  val byteOffset = UInt(p.pcOffsetWidth.W)
  val allocationEpoch = UInt(p.reservationEpochWidth.W)
}

class PMapPayload(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val ptag = UInt(p.pTagWidth.W)
  val ptagGeneration = UInt(p.pTagGenerationWidth.W)
  val producerToken = UInt(p.transactionIdWidth.W)
  val producerBindingValid = Bool()
  val producerIqClass = OooUopClass()
  val producerIqBank = UInt(p.iqBankWidth.W)
  val producerIqEntry = UInt(p.iqEntryWidth.W)
  val producerIqEpoch = UInt(p.reservationEpochWidth.W)
  val ready = Bool()
  val size = UInt(4.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
}

class OooRenamedOperand(val p: OooParams = OooParams()) extends Bundle {
  val decoded = new OooDecodedOperand(p)
  val pMapping = new PMapPayload(p)
}

class OooRenamedDestination(val p: OooParams = OooParams()) extends Bundle {
  val decoded = new OooDecodedDestination(p)
  val previousPMapping = new PMapPayload(p)
  val currentPMapping = new PMapPayload(p)
}

class OooPRenamedUop(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val member = new RobMemberKey(p)
  val decoded = new OooDecodedUop(p)
  val sources = Vec(p.maxSourceOperands, new OooRenamedOperand(p))
  val destinations = Vec(p.maxDestinationOperands,
    new OooRenamedDestination(p))
}

/** One exact speculative P mapping in program order. */
class OooPMapQEntry(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val mapQIndex = UInt(p.pMapQIndexWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val uopIndex = UInt(p.decodedUopIndexWidth.W)
  val destinationIndex = UInt(math.max(1,
    chisel3.util.log2Ceil(p.maxDestinationOperands)).W)
  val member = new RobMemberKey(p)
  val atag = UInt(p.archRegWidth.W)
  val previous = new PMapPayload(p)
  val current = new PMapPayload(p)
}

/** Side-effect-free P rename view retained by the upstream O3 transaction. */
class OooPRenamePreparedTransaction(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val uopMask = UInt(p.decodedUopWidth.W)
  val uops = Vec(p.decodedUopWidth, new OooPRenamedUop(p))
  val mapQRowMask = UInt(p.pTagAllocationWidth.W)
  val mapQRows = Vec(p.pTagAllocationWidth, new OooPMapQEntry(p))
}

class OooPRenamePrepareReject(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val requestedRows = UInt(p.destinationDemandWidth.W)
  val freeRows = UInt(p.pMapQCountWidth.W)
  val lease = new OooPTagReservation(p)
}

/** Side-effect-free P-map retirement view for one retained ROB commit batch. */
class OooPRenameCommitPrepared(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val stid = UInt(p.stidWidth.W)
  val mapQRowCount = UInt(p.commitMapQRowCountWidth.W)
}

class OooPRenameCommitReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooRobCommitBatch(p)
  val mapQHead = UInt(p.pMapQIndexWidth.W)
  val mapQCount = UInt(p.pMapQCountWidth.W)
}

/** Wrap-qualified sequence in one per-STID T or U mapping queue. */
class OooLocalSeq(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val index = UInt(p.tuMapQIndexWidth.W)
  val generation = UInt(p.localSeqGenerationWidth.W)
}

/** Resolved relative-register mapping. T and U have independent namespaces. */
class OooLocalMapping(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val kind = DestinationKind()
  val relativeIndex = UInt(p.archRegWidth.W)
  val sequence = new OooLocalSeq(p)
  val physicalTag = UInt(p.localTagWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
}

class OooTUAllocation(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val kind = DestinationKind()
  val uopIndex = UInt(p.decodedUopIndexWidth.W)
  val destinationIndex = UInt(math.max(1,
    chisel3.util.log2Ceil(p.maxDestinationOperands)).W)
  val relativeIndex = UInt(p.archRegWidth.W)
  val mapping = new OooLocalMapping(p)
}

/** D2-known part of one later ROB member binding.
  *
  * Native BID and resident generation are assigned only at S1, but the group
  * key and first member index are already immutable in the D2 plan.
  */
class OooTUReservedMember(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val group = new RobGroupKey(p)
  val memberIndex = UInt(p.robMemberIndexWidth.W)
  val blockLast = Bool()
}

/** Exact T/U resources claimed at D3 and retained until S1 publication. */
class OooTUReservation(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val uopMask = UInt(p.decodedUopWidth.W)
  val members = Vec(p.decodedUopWidth, new OooTUReservedMember(p))
  val tAllocationCount = UInt(p.destinationDemandWidth.W)
  val uAllocationCount = UInt(p.destinationDemandWidth.W)
  val allocationMask = UInt(p.tuAllocationWidth.W)
  val allocations = Vec(p.tuAllocationWidth, new OooTUAllocation(p))
  val tSeqBefore = Vec(p.decodedUopWidth, new OooLocalSeq(p))
  val uSeqBefore = Vec(p.decodedUopWidth, new OooLocalSeq(p))
  val sourceMappings = Vec(p.decodedUopWidth,
    Vec(p.maxSourceOperands, new OooLocalMapping(p)))
}

/** Local-register source shape carried across the O3 publication seam. */
class OooTUPublicationSource(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val kind = DestinationKind()
  val relativeIndex = UInt(p.archRegWidth.W)
}

/** Local-register destination shape carried across the O3 publication seam. */
class OooTUPublicationDestination(val p: OooParams = OooParams())
    extends Bundle {
  val valid = Bool()
  val kind = DestinationKind()
  val relativeIndex = UInt(p.archRegWidth.W)
}

/** Exact ROB binding and T/U operand shape for one logical uop.
  *
  * The T/U owner deliberately does not consume a complete decoded uop. P
  * rename and later dispatch retain that payload; this sidecar contains only
  * the fields needed to prove the retained sequential-rename lease still
  * belongs to the O3 publication being committed.
  */
class OooTUPublicationUop(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val member = new RobMemberKey(p)
  val blockLast = Bool()
  val closeBeforeValid = Bool()
  val closeBefore = new BrobPointer(p)
  val sources = Vec(p.maxSourceOperands, new OooTUPublicationSource(p))
  val destinations = Vec(p.maxDestinationOperands,
    new OooTUPublicationDestination(p))
}

class OooTUPublicationRequest(val p: OooParams = OooParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val uopMask = UInt(p.decodedUopWidth.W)
  val uops = Vec(p.decodedUopWidth, new OooTUPublicationUop(p))
}

class OooTURenamedUop(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val member = new RobMemberKey(p)
  val blockLast = Bool()
  val closeBeforeValid = Bool()
  val closeBefore = new BrobPointer(p)
  val tSeqBefore = new OooLocalSeq(p)
  val uSeqBefore = new OooLocalSeq(p)
  val sources = Vec(p.maxSourceOperands, new OooLocalMapping(p))
  val destinations = Vec(p.maxDestinationOperands, new OooLocalMapping(p))
}

/** One published T/U destination in local-allocation order. */
class OooTUMapQEntry(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val retired = Bool()
  val transactionId = UInt(p.transactionIdWidth.W)
  val uopIndex = UInt(p.decodedUopIndexWidth.W)
  val destinationIndex = UInt(math.max(1,
    chisel3.util.log2Ceil(p.maxDestinationOperands)).W)
  val member = new RobMemberKey(p)
  val mapping = new OooLocalMapping(p)
}

/** Side-effect-free T/U publication view with exact ROB member ownership. */
class OooTURenamePreparedTransaction(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val uopMask = UInt(p.decodedUopWidth.W)
  val uops = Vec(p.decodedUopWidth, new OooTURenamedUop(p))
  val allocationMask = UInt(p.tuAllocationWidth.W)
  val rows = Vec(p.tuAllocationWidth, new OooTUMapQEntry(p))
}

class OooTURenamePrepareReject(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val requestedT = UInt(p.destinationDemandWidth.W)
  val requestedU = UInt(p.destinationDemandWidth.W)
  val freeTEntries = UInt(p.tuMapQCountWidth.W)
  val freeUEntries = UInt(p.tuMapQCountWidth.W)
  val freeTPhysical = UInt(p.countWidth(p.tPhysRegs).W)
  val freeUPhysical = UInt(p.countWidth(p.uPhysRegs).W)
  val sourceUnderflowMask = UInt((p.decodedUopWidth * p.maxSourceOperands).W)
}

class OooTURenamePublishReject(val p: OooParams = OooParams()) extends Bundle {
  val requestedStid = UInt(p.stidWidth.W)
  val requestedTransactionId = UInt(p.transactionIdWidth.W)
  val live = new OooTUReservation(p)
}

/** Exact retirement sidecar retained for every published logical uop.
  *
  * Rows without a local destination remain present because a block-last row
  * still drains relation state and authorizes local block release.  An
  * implicit BROB close is attached to the first uop of its exact close-owner
  * group through `closeBefore`.
  */
class OooTURetireSource(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val transactionId = UInt(p.transactionIdWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val uopIndex = UInt(p.decodedUopIndexWidth.W)
  val member = new RobMemberKey(p)
  val blockLast = Bool()
  val closeBeforeValid = Bool()
  val closeBefore = new BrobPointer(p)
  val tSeqBefore = new OooLocalSeq(p)
  val uSeqBefore = new OooLocalSeq(p)
  val pDestinationCount = UInt(p.destinationCountWidth.W)
  val destinations = Vec(p.maxDestinationOperands, new OooLocalMapping(p))
}

class OooTURetirePublication(val p: OooParams = OooParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val uopMask = UInt(p.decodedUopWidth.W)
  val sources = Vec(p.decodedUopWidth, new OooTURetireSource(p))
}

/** Ordered ReportRetired command issued by the production relation owner. */
class OooTURetireCommand(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val member = new RobMemberKey(p)
  val kind = DestinationKind()
  val sequence = new OooLocalSeq(p)
  val dealloc = Bool()
}

/** Post-CleanCMAP local block release. */
class OooTULocalBlockCommit(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val block = new BrobPointer(p)
}

class OooTURetireCommitPrepared(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val stid = UInt(p.stidWidth.W)
  val sourceCount = UInt(p.commitTURetireSourceCountWidth.W)
}

class OooTURetireCommitReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooRobCommitBatch(p)
  val sourceHead = UInt(p.tuRetireSourceIndexWidth.W)
  val sourceCount = UInt(p.tuRetireSourceCountWidth.W)
}

/** Exact ROB/BROB-authorized rename recovery anchor.
  *
  * The recovery coordinator decides whether the trigger itself is killed.
  * Rename owners derive only their own ordered suffix from this exact member;
  * they never compare native BID values as unsigned ages.
  */
class OooRenameRecoveryRequest(val p: OooParams = OooParams()) extends Bundle {
  val key = new ExactRecoveryKey(p)
  val killTrigger = Bool()
}

/** Global recovery adds the complete physical extent of the triggering
  * logical uop. The trigger member must be that uop's first physical member.
  */
class OooGlobalRecoveryRequest(val p: OooParams = OooParams()) extends Bundle {
  val rename = new OooRenameRecoveryRequest(p)
  val triggerMemberCount = UInt(p.robMemberCountWidth.W)
}

/** Side-effect-free grouped-ROB suffix plan retained by the future R0-R4
  * coordinator until every affected owner can apply the same recovery fire.
  */
class OooRobRecoveryPlan(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val request = new OooGlobalRecoveryRequest(p)
  val oldHead = new RobGroupKey(p)
  val oldOccupied = UInt(p.nonFlushPrefixCountWidth.W)
  val pivot = new OooRobPhysicalGroupRecord(p)
  val pivotOffset = UInt(p.nonFlushPrefixCountWidth.W)
  val survivingPivotValid = Bool()
  val survivingPivot = new OooRobPhysicalGroupRecord(p)
  val newOccupied = UInt(p.nonFlushPrefixCountWidth.W)
  val survivingTailValid = Bool()
  val survivingTail = new OooRobPhysicalGroupRecord(p)
  val firstKilledGroup = new RobGroupKey(p)
  val killedGroupCount = UInt(p.nonFlushPrefixCountWidth.W)
  val killedGroupMask = UInt(p.robGroupsPerStid.W)
  val killedGroups = Vec(p.robGroupsPerStid,
    new OooRobPhysicalGroupRecord(p))
  val oldTail = new RobGroupKey(p)
  val newTail = new RobGroupKey(p)
}

/** Compact ROB-authorized window consumed by physical residency owners.
  *
  * The global R0-R4 coordinator projects this view once from the complete ROB
  * plan. Dispatch, IEX, and fast resolve need neither the killed-group record
  * vector nor the ROB/BROB/PC repair payload; exposing those fields on every
  * physical queue owner would create wide unused public ports and duplicate
  * recovery CAM structure.
  */
class OooResidencyRecoveryPlan(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val oldHead = new RobGroupKey(p)
  val oldOccupied = UInt(p.nonFlushPrefixCountWidth.W)
  val newOccupied = UInt(p.nonFlushPrefixCountWidth.W)
  val pivotOffset = UInt(p.nonFlushPrefixCountWidth.W)
  val pivot = new RobMemberKey(p)
  val pivotPhysicalMemberCount = UInt(p.robMemberCountWidth.W)
  val survivingPivotValid = Bool()
  val survivingPivotPhysicalMemberCount = UInt(p.robMemberCountWidth.W)
}

class OooRobRecoveryReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooGlobalRecoveryRequest(p)
  val occupied = UInt(p.nonFlushPrefixCountWidth.W)
  val exactMatchCount = UInt(p.countWidth(p.robGroupsPerStid).W)
  val triggerShapeMatch = Bool()
}

class OooD3RecoveryReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooRobRecoveryPlan(p)
  val liveHead = new RobGroupKey(p)
  val liveTail = new RobGroupKey(p)
  val usedGroups = UInt(p.countWidth(p.robGroupsPerStid).W)
  val publishedGroups = UInt(p.countWidth(p.robGroupsPerStid).W)
  val provisional = Bool()
  val exposedConflict = Bool()
}

/** One youngest-to-oldest logical-uop suffix item during rename recovery. */
class OooRenameRecoverySource(val p: OooParams = OooParams()) extends Bundle {
  val request = new OooRenameRecoveryRequest(p)
  val source = new OooTURetireSource(p)
  val last = Bool()
}

class OooRenameRecoveryReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooRenameRecoveryRequest(p)
  val sourceHead = UInt(p.tuRetireSourceIndexWidth.W)
  val sourceTail = UInt(p.tuRetireSourceIndexWidth.W)
  val sourceCount = UInt(p.tuRetireSourceCountWidth.W)
}

class OooPRenameRecoveryReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooRenameRecoveryRequest(p)
  val mapQTail = UInt(p.pMapQIndexWidth.W)
  val mapQCount = UInt(p.pMapQCountWidth.W)
}

class OooTURenameRecoveryReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooRenameRecoveryRequest(p)
  val tTail = new OooLocalSeq(p)
  val uTail = new OooLocalSeq(p)
  val tMapQCount = UInt(p.tuMapQCountWidth.W)
  val uMapQCount = UInt(p.tuMapQCountWidth.W)
}

class OooPTagToken(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val bank = UInt(p.pTagBankWidth.W)
  val ptag = UInt(p.pTagWidth.W)
  val generation = UInt(p.pTagGenerationWidth.W)
}

class OooPTagAllocation(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val uopIndex = UInt(p.decodedUopIndexWidth.W)
  val destinationIndex = UInt(math.max(1,
    chisel3.util.log2Ceil(p.maxDestinationOperands)).W)
  val atag = UInt(p.archRegWidth.W)
  val token = new OooPTagToken(p)
}

/** Exact PTag claim retained between D3 reservation and S1 publication. */
class OooPTagReservation(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val allocationMask = UInt(p.pTagAllocationWidth.W)
  val allocations = Vec(p.pTagAllocationWidth, new OooPTagAllocation(p))
}

class OooPTagPublish(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
}

class OooPTagReturnBatch(val p: OooParams = OooParams()) extends Bundle {
  val count = UInt(p.pTagReturnCountWidth.W)
  val tokens = Vec(p.pTagReturnWidth, new OooPTagToken(p))
}

class OooPTagPrepareReject(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val requestedDestinations = UInt(p.destinationDemandWidth.W)
}

class OooPTagPublishReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooPTagPublish(p)
  val live = new OooPTagReservation(p)
}

class OooPTagReturnReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooPTagReturnBatch(p)
  val publishedMask = UInt(p.pPhysRegs.W)
}

class DispatchReservation(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val uopClass = OooUopClass()
  val bank = UInt(p.iqBankWidth.W)
  val writePort = UInt(p.iqWritePortWidth.W)
  val speculativeSlot = UInt(p.iqEntryWidth.W)
  val reservationEpoch = UInt(p.reservationEpochWidth.W)
}

/** One generated dispatch child bound to an exact speculative IQ slot. */
class OooDispatchAllocation(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val uopIndex = UInt(p.decodedUopIndexWidth.W)
  val childIndex = UInt(math.max(1,
    chisel3.util.log2Ceil(p.maxDispatchWritesPerInstruction)).W)
  val reservation = new DispatchReservation(p)
}

/** Exact all-or-none D3 dispatch lease retained until S1 or cancellation. */
class OooDispatchReservationLease(val p: OooParams = OooParams())
    extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val allocationMask = UInt(p.dispatchWidth.W)
  val allocations = Vec(p.dispatchWidth, new OooDispatchAllocation(p))
}

class OooDispatchPublish(val p: OooParams = OooParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val memberMask = UInt(p.dispatchWidth.W)
  val members = Vec(p.dispatchWidth, new RobMemberKey(p))
}

class OooDispatchRelease(val p: OooParams = OooParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val member = new RobMemberKey(p)
  val reservation = new DispatchReservation(p)
}

class OooDispatchPrepareReject(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val requestedWrites = UInt(p.dispatchCountWidth.W)
  val plannedWrites = UInt(p.dispatchDemandWidth.W)
  val liveLease = new OooDispatchReservationLease(p)
}

class OooDispatchPublishReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooDispatchPublish(p)
  val live = new OooDispatchReservationLease(p)
}

class OooDispatchReleaseReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooDispatchRelease(p)
}

class OooDispatchRecoveryPrepared(val p: OooParams = OooParams())
    extends Bundle {
  val valid = Bool()
  val stid = UInt(p.stidWidth.W)
  val provisionalKilled = UInt(p.dispatchCountWidth.W)
  val publishedKilled = UInt(p.countWidth(p.iqClassCount * p.iqBankCount *
    p.iqEntriesPerBank).W)
}

class OooDispatchRecoveryReject(val p: OooParams = OooParams())
    extends Bundle {
  val requested = new OooResidencyRecoveryPlan(p)
  val stidInRange = Bool()
  val publishedMembersExact = Bool()
}

/** Atomic OOO S1 payload accepted by the production IEX boundary.
  *
  * The three prepared views deliberately remain separate.  Their redundant
  * identities let IEX prove that the exact ROB/PC, P rename, T/U rename, and
  * dispatch lease which joined the common OOO publication fire also reached
  * the speculative issue-slot owner.
  */
class OooIexS1Transaction(val p: OooParams = OooParams()) extends Bundle {
  val o3 = new OooO3PreparedPublication(p)
  val pRename = new OooPRenamePreparedTransaction(p)
  val tuRename = new OooTURenamePreparedTransaction(p)
  val dispatch = new OooDispatchReservationLease(p)
  val memoryOrder = new OooMemoryOrderReservationLease(p)
}

/** Whether a wakeup is architecturally stable or still load-cancellable. */
object OooIexWakeupKind extends ChiselEnum {
  val Committed, SpeculativeLoad = Value
}

/** Exact identity of one speculative load attempt.
  *
  * A numerical attempt generation is never authoritative by itself.  The
  * producer ROB member prevents an old load slot/generation from poisoning a
  * different producer after wrap or reuse.
  */
class OooIexLoadGeneration(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val producer = new RobMemberKey(p)
  val generation = UInt(p.loadGenerationWidth.W)
}

/** Producer-relative result age carried by the data bypass network. */
object OooIexBypassStage extends ChiselEnum {
  val W1, W2, W3 = Value
}

/** One exact data candidate visible to every I1 operand selector.
  *
  * P/T/U destination identity prevents numerical tag aliasing.  A
  * speculative-load consumer additionally requires `load` to match the
  * source row's exact producer/generation token.
  */
class OooIexBypassCandidate(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val producer = new RobMemberKey(p)
  val operandClass = OperandClass()
  val ptag = UInt(p.pTagWidth.W)
  val ptagGeneration = UInt(p.pTagGenerationWidth.W)
  val localTag = UInt(p.localTagWidth.W)
  val localSequence = new OooLocalSeq(p)
  val load = new OooIexLoadGeneration(p)
  val stage = OooIexBypassStage()
  val data = UInt(p.pcWidth.W)
}

/** One exact speculative load attempt withdrawn after miss/replay resolve. */
class OooIexLoadCancel(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val load = new OooIexLoadGeneration(p)
}

/** One generation-qualified producer wakeup observed by resident IQ rows. */
class OooIexWakeup(val p: OooParams = OooParams()) extends Bundle {
  val kind = OooIexWakeupKind()
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val operandClass = OperandClass()
  val ptag = UInt(p.pTagWidth.W)
  val ptagGeneration = UInt(p.pTagGenerationWidth.W)
  val localTag = UInt(p.localTagWidth.W)
  val localSequence = new OooLocalSeq(p)
  val load = new OooIexLoadGeneration(p)
}

/** Source identity and registered readiness owned by one physical IQ row. */
class OooIexSourceState(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  // `ready` is non-speculative and may be initialized from the RF/global
  // scoreboard. `specReady` is IQ-local and can be withdrawn by load replay.
  val ready = Bool()
  val specReady = Bool()
  val operandClass = OperandClass()
  val ptag = UInt(p.pTagWidth.W)
  val ptagGeneration = UInt(p.pTagGenerationWidth.W)
  val localTag = UInt(p.localTagWidth.W)
  val localSequence = new OooLocalSeq(p)
  val load = new OooIexLoadGeneration(p)
}

/** Destination identity retained by IEX without copying rename-owner state. */
class OooIexDestinationState(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val kind = DestinationKind()
  val atag = UInt(p.archRegWidth.W)
  val relativeIndex = UInt(p.archRegWidth.W)
  val ptag = UInt(p.pTagWidth.W)
  val ptagGeneration = UInt(p.pTagGenerationWidth.W)
  val localTag = UInt(p.localTagWidth.W)
  val localSequence = new OooLocalSeq(p)
}

/** Frequently scanned physical scheduling state.
  *
  * Recovery, wakeup, release, and pick inspect only this compact row.  Wide
  * execution controls live in a separately addressed payload memory.
  */
class OooIexScheduleRow(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  // Canonical speculative issue ownership. The row remains resident while a
  // P1/I1/I2 lane owns it and becomes pickable again only on an exact retry.
  val inFlight = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val dispatchLane = UInt(math.max(1,
    chisel3.util.log2Ceil(p.dispatchWidth)).W)
  val uopIndex = UInt(p.decodedUopIndexWidth.W)
  val childIndex = UInt(math.max(1,
    chisel3.util.log2Ceil(p.maxDispatchWritesPerInstruction)).W)
  val member = new RobMemberKey(p)
  val reservation = new DispatchReservation(p)
  val sources = Vec(p.maxSourceOperands, new OooIexSourceState(p))
  val destinations = Vec(p.maxDestinationOperands,
    new OooIexDestinationState(p))
}

/** Wide execution payload read only for the selected physical row. */
class OooIexPayloadSidecar(val p: OooParams = OooParams()) extends Bundle {
  val uopKey = new CanonicalUopKey(p)
  val parentCount = UInt(p.architecturalParentCountWidth.W)
  val parentPcTokens = Vec(p.maxArchitecturalParentRefs,
    new PcBufferToken(p))
  // Derived once from the canonical parent-key array when the IQ row binds.
  // The opcode recipe says whether execution needs PC; this index says which
  // architectural parent owns that PC without carrying the full address.
  val pcParentIndexValid = Bool()
  val pcParentIndex = UInt(math.max(1,
    chisel3.util.log2Ceil(p.maxArchitecturalParentRefs)).W)
  val primaryPrediction = new OooPredictionRecord(p)
  val boundary = new BoundarySidecar(p)
  val templateValid = Bool()
  val templateGroupId = UInt(p.templateGroupIdWidth.W)
  val templateGeneration = UInt(p.residentGenerationWidth.W)
  val opcode = UInt(p.opcodeWidth.W)
  val recipe = new OooOpcodeRecipeMeta(p)
  val plannedChildCount = UInt(p.recipeUopCountWidth.W)
  val immediateValid = Bool()
  val immediate = UInt(p.pcWidth.W)
  val memory = new OooMemoryControl(p)
  val memoryOrder = new OooMemoryOrderUopAllocation(p)
  val boundaryTargetValid = Bool()
  val boundaryTarget = UInt(p.pcWidth.W)
  val preciseTrap = Bool()
  val trapCause = UInt(p.trapCauseWidth.W)
  val blockLast = Bool()
  val closeBeforeValid = Bool()
  val closeBefore = new BrobPointer(p)
}

/** Physical IEX row installed from one exact dispatch child.
  *
  * The public row is the joined view of compact scheduling state and a wide
  * execution sidecar.  Keeping the join in the bundle preserves the existing
  * execution/query contract while allowing the physical owner to scan only
  * `schedule` and infer `payload` as memory.
  */
class OooIexIssueRow(val p: OooParams = OooParams()) extends Bundle {
  val schedule = new OooIexScheduleRow(p)
  val payload = new OooIexPayloadSidecar(p)

  def valid = schedule.valid
  def peId = schedule.peId
  def stid = schedule.stid
  def epoch = schedule.epoch
  def transactionId = schedule.transactionId
  def dispatchLane = schedule.dispatchLane
  def uopIndex = schedule.uopIndex
  def childIndex = schedule.childIndex
  def member = schedule.member
  def reservation = schedule.reservation
  def sources = schedule.sources
  def destinations = schedule.destinations
  def uopKey = payload.uopKey
  def parentCount = payload.parentCount
  def parentPcTokens = payload.parentPcTokens
  def pcParentIndexValid = payload.pcParentIndexValid
  def pcParentIndex = payload.pcParentIndex
  def primaryPrediction = payload.primaryPrediction
  def boundary = payload.boundary
  def templateValid = payload.templateValid
  def templateGroupId = payload.templateGroupId
  def templateGeneration = payload.templateGeneration
  def opcode = payload.opcode
  def recipe = payload.recipe
  def plannedChildCount = payload.plannedChildCount
  def immediateValid = payload.immediateValid
  def immediate = payload.immediate
  def memory = payload.memory
  def memoryOrder = payload.memoryOrder
  def boundaryTargetValid = payload.boundaryTargetValid
  def boundaryTarget = payload.boundaryTarget
  def preciseTrap = payload.preciseTrap
  def trapCause = payload.trapCause
  def blockLast = payload.blockLast
  def closeBeforeValid = payload.closeBeforeValid
  def closeBefore = payload.closeBefore
}

/** One exact resident IQ row selected into the canonical P1 read lane. */
class OooIexP1Request(val p: OooParams = OooParams()) extends Bundle {
  val row = new OooIexIssueRow(p)
  val pcReadRequired = Bool()
  val pcParentIndex = UInt(math.max(1,
    chisel3.util.log2Ceil(p.maxArchitecturalParentRefs)).W)
}

/** Atomic I1 request presented to the shared P/T/U and PC read arbiters. */
class OooIexI1ReadAttempt(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val reservation = new DispatchReservation(p)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val sourceMask = UInt(p.maxSourceOperands.W)
  val sources = Vec(p.maxSourceOperands, new OooIexSourceState(p))
  val pcRequired = Bool()
  val pcToken = new PcBufferToken(p)
}

/** Retained I2 payload after one atomic I1 read-port grant. */
class OooIexI2Transaction(val p: OooParams = OooParams()) extends Bundle {
  val row = new OooIexIssueRow(p)
  val sourceMask = UInt(p.maxSourceOperands.W)
  val sourceData = Vec(p.maxSourceOperands, UInt(p.pcWidth.W))
  val bypassMask = UInt(p.maxSourceOperands.W)
  val bypass = Vec(p.maxSourceOperands, new OooIexBypassCandidate(p))
  val pcValid = Bool()
  val pc = UInt(p.pcWidth.W)
}

/** Full transaction after an exact I2-to-E1 ownership transfer. */
class OooIexExecuteTransaction(val p: OooParams = OooParams()) extends Bundle {
  val ownerClass = OooUopClass()
  val ownerLane = UInt(p.iexIssueDomainWidth.W)
  val slotGeneration = UInt(p.executeSlotGenerationWidth.W)
  val i2 = new OooIexI2Transaction(p)
}

class OooIexReadRepick(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val reservation = new DispatchReservation(p)
}

class OooIexReadReject(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val reservation = new DispatchReservation(p)
  val sourceMask = UInt(p.maxSourceOperands.W)
  val sourceDataValid = UInt(p.maxSourceOperands.W)
  val pcRequired = Bool()
  val pcDataValid = Bool()
}

class OooIexP1Reject(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val reservation = new DispatchReservation(p)
  val identityExact = Bool()
  val sourcesReady = Bool()
  val pcTokenExact = Bool()
}

/** Minimal combinational projection of one canonical IQ scheduling row. */
class OooIexPickCandidate(val p: OooParams = OooParams()) extends Bundle {
  val eligible = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val member = new RobMemberKey(p)
  val reservation = new DispatchReservation(p)
}

/** Exact retained picker result used to address the canonical IQ payload. */
class OooIexPickToken(val p: OooParams = OooParams()) extends Bundle {
  val query = new OooIexSlotQuery(p)
  val candidate = new OooIexPickCandidate(p)
}

class OooIexPickReject(val p: OooParams = OooParams()) extends Bundle {
  val token = new OooIexPickToken(p)
  val identityExact = Bool()
  val reservationExact = Bool()
}

class OooIexPickClaimReject(val p: OooParams = OooParams()) extends Bundle {
  val token = new OooIexPickToken(p)
  val residentExact = Bool()
  val identityExact = Bool()
  val notInFlight = Bool()
}

class OooIexPickRetryReject(val p: OooParams = OooParams()) extends Bundle {
  val retry = new OooIexReadRepick(p)
  val residentExact = Bool()
  val identityExact = Bool()
  val wasInFlight = Bool()
}

/** A picked scheduling token failed to join its canonical payload sidecar. */
class OooIexPickJoinReject(val p: OooParams = OooParams()) extends Bundle {
  val token = new OooIexPickToken(p)
  val residentExact = Bool()
  val identityExact = Bool()
  val recipeExact = Bool()
  val pcMetadataExact = Bool()
}

/** Exact S2 acknowledgment for the earlier retained S1 transaction. */
class OooIexS2BindAck(val p: OooParams = OooParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val allocationMask = UInt(p.dispatchWidth.W)
}

/** Exact S3 enable event.  It is one registered stage after S2 bind. */
class OooIexS3Enable(val p: OooParams = OooParams()) extends Bundle {
  val bind = new OooIexS2BindAck(p)
}

class OooIexSlotQuery(val p: OooParams = OooParams()) extends Bundle {
  val uopClass = OooUopClass()
  val bank = UInt(p.iqBankWidth.W)
  val entry = UInt(p.iqEntryWidth.W)
}

/** Future I2 terminal release.  The row and dispatch owner must both match. */
class OooIexIssueRelease(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val dispatch = new OooDispatchRelease(p)
}

class OooIexS1Reject(val p: OooParams = OooParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val shapeExact = Bool()
  val targetsExact = Bool()
}

class OooIexReleaseReject(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val reservation = new DispatchReservation(p)
}

class OooIexRecoveryPrepared(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val stid = UInt(p.stidWidth.W)
  val s1Killed = UInt(p.dispatchCountWidth.W)
  val boundKilled = UInt(p.countWidth(p.iqClassCount * p.iqBankCount *
    p.iqEntriesPerBank).W)
  val residentKilled = UInt(p.countWidth(p.iqClassCount * p.iqBankCount *
    p.iqEntriesPerBank).W)
}

class OooIexRecoveryReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooResidencyRecoveryPlan(p)
  val stidInRange = Bool()
  val residentRowsExact = Bool()
  val s1RowsExact = Bool()
}

/** One retained typed fast-resolve member after the common OOO S1 fire. */
class OooFastResolveEntry(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val uopIndex = UInt(p.decodedUopIndexWidth.W)
  val member = new RobMemberKey(p)
  val uopKey = new CanonicalUopKey(p)
  val opcode = UInt(p.opcodeWidth.W)
  val fastResolveClass = UInt(3.W)
  val boundary = new BoundarySidecar(p)
  val prediction = new OooPredictionRecord(p)
  val targetValid = Bool()
  val target = UInt(p.pcWidth.W)
  val trapValid = Bool()
  val trapCause = UInt(p.trapCauseWidth.W)
  val resultValid = Bool()
  val result = UInt(p.pcWidth.W)
  val destination = new PMapPayload(p)
}

/** BCTRL validation obligation for a boundary or control-value fast member. */
class OooFastResolveBoundaryRequest(val p: OooParams = OooParams())
    extends Bundle {
  val member = new RobMemberKey(p)
  val uopKey = new CanonicalUopKey(p)
  val opcode = UInt(p.opcodeWidth.W)
  val boundary = new BoundarySidecar(p)
  val prediction = new OooPredictionRecord(p)
  val targetValid = Bool()
  val target = UInt(p.pcWidth.W)
}

/** Exact PRF result obligation for SETRET and fused start-call producers. */
class OooFastResolveWriteback(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val ptag = UInt(p.pTagWidth.W)
  val ptagGeneration = UInt(p.pTagGenerationWidth.W)
  val data = UInt(p.pcWidth.W)
}

/** Trace-side terminal record emitted before fast member completion. */
class OooFastResolveTrace(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val uopKey = new CanonicalUopKey(p)
  val opcode = UInt(p.opcodeWidth.W)
  val fastResolveClass = UInt(3.W)
  val trapValid = Bool()
  val trapCause = UInt(p.trapCauseWidth.W)
  val resultValid = Bool()
  val result = UInt(p.pcWidth.W)
}

class OooFastResolveS1Reject(val p: OooParams = OooParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val fastMask = UInt(p.decodedUopWidth.W)
  val shapeExact = Bool()
}

class OooFastResolveRecoveryPrepared(val p: OooParams = OooParams())
    extends Bundle {
  val valid = Bool()
  val stid = UInt(p.stidWidth.W)
  val pendingKilled = UInt(p.decodedUopCountWidth.W)
}

class OooFastResolveRecoveryReject(val p: OooParams = OooParams())
    extends Bundle {
  val requested = new OooResidencyRecoveryPlan(p)
  val stidInRange = Bool()
  val pendingRowsExact = Bool()
}

class OooD2VirtualPlan(val p: OooParams = OooParams()) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val instructionMask = UInt(p.instructionDecodeWidth.W)
  val uopMask = UInt(p.decodedUopWidth.W)
  val firstVirtualGroup = new RobGroupKey(p)
  val groupCount = UInt(p.robGroupCountWidth.W)
  val virtualTailEpoch = UInt(p.reservationEpochWidth.W)
  val demand = new InstructionDemand(p)
}

class OooRobGroupPreview(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val key = new RobGroupKey(p)
  val logicalUopMask = UInt(p.decodedUopWidth.W)
  val firstLogicalUop = UInt(p.decodedUopIndexWidth.W)
  val logicalUopCount = UInt(p.decodedUopCountWidth.W)
  val physicalMemberCount = UInt(p.robMemberCountWidth.W)
  val pMapQRows = UInt(p.destinationDemandWidth.W)
  val architecturalParentCount = UInt(p.robGroupParentDemandWidth.W)
  val boundaryStart = Bool()
  val boundaryStop = Bool()
  val releasePcBase = Bool()
  val preciseTrap = Bool()
}

/** Complete D2 preview transaction. No field in this bundle is a physical
  * allocation until a later D3/S1 owner validates the tail epoch and publishes.
  */
class OooD2GroupedTransaction(val p: OooParams = OooParams()) extends Bundle {
  val plan = new OooD2VirtualPlan(p)
  val decoded = new OooD1DecodedPacket(p)
  val groupMask = UInt(p.instructionDecodeWidth.W)
  val groups = Vec(p.instructionDecodeWidth, new OooRobGroupPreview(p))
  val uopGroupIndex = Vec(p.decodedUopWidth, UInt(p.robGroupIndexWidth.W))
  val uopMemberBase = Vec(p.decodedUopWidth, UInt(p.robMemberIndexWidth.W))
}

class OooD3GroupedReservation(val p: OooParams = OooParams()) extends Bundle {
  val transaction = new OooD2GroupedTransaction(p)
  val claimEpoch = UInt(p.reservationEpochWidth.W)
  val tailAfter = new RobGroupKey(p)
}

/** Physical resources bound to one D2 group at the S1 publication boundary.
  *
  * This sidecar is intentionally outside the D3 allocator: BROB and PC-base
  * owners may reserve independently, but their bindings become architectural
  * only in the same handshake that publishes the grouped ROB rows.
  */
class OooS1GroupBinding(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val brob = new BrobPointer(p)
  val brobAllocated = Bool()
  val brobImplicitCloseValid = Bool()
  val brobImplicitClose = new BrobPointer(p)
  val pcBase = new PcBufferToken(p)
  val pcBaseAllocated = Bool()
  val pcImplicitCloseValid = Bool()
  val pcImplicitClose = new PcBufferToken(p)
  val residentGeneration = UInt(p.residentGenerationWidth.W)
  val initiallyCompletedMembers = UInt(p.maxOrdinaryUopsPerGroup.W)
  val memoryOrderValid = Bool()
  val memoryBefore = new OooMemoryIdState(p)
  val memoryAfter = new OooMemoryIdState(p)
  val logicalMemoryAfter = Vec(p.decodedUopWidth,
    new OooMemoryIdState(p))
}

class OooS1GroupedPublicationRequest(val p: OooParams = OooParams()) extends Bundle {
  val reservation = new OooD3GroupedReservation(p)
  val bindings = Vec(p.instructionDecodeWidth, new OooS1GroupBinding(p))
}

/** Side-effect-free O3 publication view presented to later D3/S1 owners.
  * `request` is the exact grouped ROB publication packet; `parentPcTokens`
  * attaches compressed PC identity to every architectural parent before the
  * common publication fire.
  */
class OooO3PreparedPublication(val p: OooParams = OooParams()) extends Bundle {
  val request = new OooS1GroupedPublicationRequest(p)
  val parentPcTokens = Vec(p.decodedUopWidth,
    Vec(p.maxArchitecturalParentRefs, new PcBufferToken(p)))
  val brobImplicitCloseMask = UInt(p.instructionDecodeWidth.W)
  val brobImplicitClosePointers = Vec(p.instructionDecodeWidth,
    new BrobPointer(p))
}

/** Physical grouped-ROB row. A row owns one exact RID generation and a dense
  * member-completion bitmap; slot-only completion is never authoritative.
  */
class OooRobPhysicalGroupRecord(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val key = new RobGroupKey(p)
  val transactionId = UInt(p.transactionIdWidth.W)
  val publicationEpoch = UInt(p.epochWidth.W)
  val claimEpoch = UInt(p.reservationEpochWidth.W)
  val brob = new BrobPointer(p)
  val brobAllocated = Bool()
  val brobImplicitCloseValid = Bool()
  val brobImplicitClose = new BrobPointer(p)
  val pcBase = new PcBufferToken(p)
  val pcBaseAllocated = Bool()
  val pcImplicitCloseValid = Bool()
  val pcImplicitClose = new PcBufferToken(p)
  val residentGeneration = UInt(p.residentGenerationWidth.W)
  val logicalUopMask = UInt(p.decodedUopWidth.W)
  val physicalMemberCount = UInt(p.robMemberCountWidth.W)
  val pMapQRows = UInt(p.destinationDemandWidth.W)
  val completedMembers = UInt(p.maxOrdinaryUopsPerGroup.W)
  val faultedMembers = UInt(p.maxOrdinaryUopsPerGroup.W)
  val memberFaultCauses = Vec(p.maxOrdinaryUopsPerGroup,
    UInt(p.trapCauseWidth.W))
  val architecturalParentCount = UInt(p.robGroupParentDemandWidth.W)
  val boundaryStart = Bool()
  val boundaryStop = Bool()
  val releasePcBase = Bool()
  val preciseTrap = Bool()
  val nonFlushRequiredProofs = UInt(OooNonFlushProof.Count.W)
  val nonFlushObservedProofs = UInt(OooNonFlushProof.Count.W)
  val nonFlushNever = Bool()
  val logicalMemberBase = Vec(p.decodedUopWidth,
    UInt(p.robMemberIndexWidth.W))
  val logicalMemberCount = Vec(p.decodedUopWidth,
    UInt(p.robMemberCountWidth.W))
  val logicalPMapQRows = Vec(p.decodedUopWidth,
    UInt(p.destinationCountWidth.W))
  val logicalArchitecturalParentCount = Vec(p.decodedUopWidth,
    UInt(p.architecturalParentCountWidth.W))
  val logicalBoundaryStart = UInt(p.decodedUopWidth.W)
  val logicalBoundaryStop = UInt(p.decodedUopWidth.W)
  val logicalReleasePcBase = UInt(p.decodedUopWidth.W)
  val logicalPreciseTrap = UInt(p.decodedUopWidth.W)
  val logicalNonFlushRequiredProofs = Vec(p.decodedUopWidth,
    UInt(OooNonFlushProof.Count.W))
  val logicalNonFlushNever = UInt(p.decodedUopWidth.W)
  val memoryOrderValid = Bool()
  val memoryBefore = new OooMemoryIdState(p)
  val memoryAfter = new OooMemoryIdState(p)
  val logicalMemoryAfter = Vec(p.decodedUopWidth,
    new OooMemoryIdState(p))
}

class OooRobMemberCompletion(val p: OooParams = OooParams()) extends Bundle {
  val key = new RobMemberKey(p)
  val faultValid = Bool()
  val faultCause = UInt(p.trapCauseWidth.W)
}

class OooRobMemberCompletionReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new RobMemberKey(p)
  val occupied = Bool()
  val live = new OooRobPhysicalGroupRecord(p)
}

/** Exact, typed proof that one live ROB group has crossed a non-flush safety
  * point. `key` is member-qualified to reject stale or misrouted producers;
  * the proof updates group state but never completes or retires the member.
  */
class OooRobNonFlushEvidence(val p: OooParams = OooParams()) extends Bundle {
  val key = new RobMemberKey(p)
  val proofs = UInt(OooNonFlushProof.Count.W)
}

class OooRobNonFlushEvidenceReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooRobNonFlushEvidence(p)
  val occupied = Bool()
  val live = new OooRobPhysicalGroupRecord(p)
}

class OooS1PublicationReject(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val groupMask = UInt(p.instructionDecodeWidth.W)
}

class OooRobCommitBatch(val p: OooParams = OooParams()) extends Bundle {
  val release = new OooRobGroupRelease(p)
  val groups = Vec(p.retireGroupWidth, new OooRobPhysicalGroupRecord(p))
}

class OooBrobEntry(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val pointer = new BrobPointer(p)
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val firstRobGroup = new RobGroupKey(p)
  val lastRobGroup = new RobGroupKey(p)
  val nextCommitRobGroup = new RobGroupKey(p)
  val liveRobGroups = UInt(p.brobLiveGroupCountWidth.W)
  val closed = Bool()
  val closeOwnerValid = Bool()
  val closeOwner = new RobGroupKey(p)
  val closeCommitted = Bool()
}

class OooBrobPreparedBindings(val p: OooParams = OooParams()) extends Bundle {
  val validMask = UInt(p.instructionDecodeWidth.W)
  val pointers = Vec(p.instructionDecodeWidth, new BrobPointer(p))
  val newBlockMask = UInt(p.instructionDecodeWidth.W)
  val implicitCloseMask = UInt(p.instructionDecodeWidth.W)
  val implicitClosePointers = Vec(p.instructionDecodeWidth, new BrobPointer(p))
  val allocatedBlocks = UInt(p.robGroupCountWidth.W)
  val tailAfter = new BrobPointer(p)
  val currentAfterValid = Bool()
  val currentAfter = new BrobPointer(p)
}

class OooBrobPrepareReject(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val groupMask = UInt(p.instructionDecodeWidth.W)
}

class OooBrobCommitReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooRobCommitBatch(p)
  val head = new BrobPointer(p)
}

class OooBrobRecoveryPrepared(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val stid = UInt(p.stidWidth.W)
  val freedBlocks = UInt(p.brobCountWidth.W)
  val tailAfter = new BrobPointer(p)
  val currentAfterValid = Bool()
  val currentAfter = new BrobPointer(p)
}

class OooBrobRecoveryReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooRobRecoveryPlan(p)
  val liveTail = new BrobPointer(p)
  val usedBlocks = UInt(p.brobCountWidth.W)
  val killedRowsExact = Bool()
  val tailSuffixExact = Bool()
  val currentAfterExact = Bool()
}

class OooPcBaseEntry(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val stid = UInt(p.stidWidth.W)
  val token = new PcBufferToken(p)
  val base = UInt(p.pcWidth.W)
  val firstRobGroup = new RobGroupKey(p)
  val lastRobGroup = new RobGroupKey(p)
  val nextCommitRobGroup = new RobGroupKey(p)
  val liveRobGroups = UInt(p.brobLiveGroupCountWidth.W)
  val closed = Bool()
  val closeOwnerValid = Bool()
  val closeOwner = new RobGroupKey(p)
  val closeCommitted = Bool()
}

/** Minimal PC-base payload replicated for fixed readyless consumer read ports. */
class OooPcReadEntry(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val stid = UInt(p.stidWidth.W)
  val index = UInt(p.pcBufferIndexWidth.W)
  val allocationEpoch = UInt(p.reservationEpochWidth.W)
  val base = UInt(p.pcWidth.W)
}

class OooPcPreparedBindings(val p: OooParams = OooParams()) extends Bundle {
  val validMask = UInt(p.instructionDecodeWidth.W)
  val groupTokens = Vec(p.instructionDecodeWidth, new PcBufferToken(p))
  val parentTokens = Vec(p.decodedUopWidth,
    Vec(p.maxArchitecturalParentRefs, new PcBufferToken(p)))
  val newBaseMask = UInt(p.instructionDecodeWidth.W)
  val newBases = Vec(p.instructionDecodeWidth, UInt(p.pcWidth.W))
  val implicitCloseMask = UInt(p.instructionDecodeWidth.W)
  val implicitCloseTokens = Vec(p.instructionDecodeWidth, new PcBufferToken(p))
  val allocatedBases = UInt(p.robGroupCountWidth.W)
  val currentAfterValid = Bool()
  val currentAfter = new PcBufferToken(p)
}

class OooPcPrepareReject(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val groupMask = UInt(p.instructionDecodeWidth.W)
}

class OooPcCommitReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooRobCommitBatch(p)
  val head = new PcBufferToken(p)
}

class OooPcRecoveryPrepared(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val stid = UInt(p.stidWidth.W)
  val freedBases = UInt(p.pcPartitionCountWidth.W)
  val tailAfter = new PcBufferToken(p)
  val currentAfterValid = Bool()
  val currentAfter = new PcBufferToken(p)
  val currentBaseAfter = UInt(p.pcWidth.W)
}

class OooPcRecoveryReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooRobRecoveryPlan(p)
  val liveTail = new PcBufferToken(p)
  val usedBases = UInt(p.pcPartitionCountWidth.W)
  val killedRowsExact = Bool()
  val tailSuffixExact = Bool()
  val currentAfterExact = Bool()
}

class OooRobGroupRelease(val p: OooParams = OooParams()) extends Bundle {
  val firstGroup = new RobGroupKey(p)
  val headEpoch = UInt(p.reservationEpochWidth.W)
  val groupCount = UInt(p.robReleaseCountWidth.W)
}

class OooD3StalePlanReject(val p: OooParams = OooParams()) extends Bundle {
  val stid = UInt(p.stidWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val plannedTailEpoch = UInt(p.reservationEpochWidth.W)
  val liveTailEpoch = UInt(p.reservationEpochWidth.W)
}

class OooD3ReleaseReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new OooRobGroupRelease(p)
  val liveHead = new RobGroupKey(p)
  val liveHeadEpoch = UInt(p.reservationEpochWidth.W)
}

class OooD3Reservation(val p: OooParams = OooParams()) extends Bundle {
  val plan = new OooD2VirtualPlan(p)
  val groupValidMask = UInt(p.instructionDecodeWidth.W)
  val groups = Vec(p.instructionDecodeWidth, new RobGroupKey(p))
  val bid = new BrobPointer(p)
  val ptagValidMask = UInt(p.renameWidth.W)
  val ptags = Vec(p.renameWidth, UInt(p.pTagWidth.W))
  val pcTokens = Vec(p.instructionDecodeWidth, new PcBufferToken(p))
  val dispatch = Vec(p.dispatchWidth, new DispatchReservation(p))
  val reservationEpoch = UInt(p.reservationEpochWidth.W)
}

class OooS1Publication(val p: OooParams = OooParams()) extends Bundle {
  val reservation = new OooD3Reservation(p)
  val publishedGroupMask = UInt(p.instructionDecodeWidth.W)
  val publishedUopMask = UInt(p.dispatchWidth.W)
  val fastResolvedUopMask = UInt(p.dispatchWidth.W)
}

class ExactRecoveryKey(val p: OooParams = OooParams()) extends Bundle {
  val member = new RobMemberKey(p)
  val cause = OooRecoveryCause()
  val transactionId = UInt(p.transactionIdWidth.W)
  val epoch = UInt(p.epochWidth.W)
}

class NonFlushWindow(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val stid = UInt(p.stidWidth.W)
  val head = new RobGroupKey(p)
  val prefixCount = UInt(p.nonFlushPrefixCountWidth.W)
  val epoch = UInt(p.epochWidth.W)
}

/** Minimal identity/demand envelope used by the O1 elastic stage shell. */
class OooPipelineToken(val p: OooParams = OooParams()) extends Bundle {
  val transactionId = UInt(p.transactionIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val instructionMask = UInt(p.instructionDecodeWidth.W)
  val uopMask = UInt(p.decodedUopWidth.W)
  val demand = new InstructionDemand(p)
}
