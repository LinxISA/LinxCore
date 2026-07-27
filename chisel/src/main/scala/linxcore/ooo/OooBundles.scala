package linxcore.ooo

import chisel3._
import linxcore.common.{BoundaryKind, BranchPredictionSidecar, DestinationKind, OperandClass}

object OooUopClass extends ChiselEnum {
  val Alu, Bru, Agu, Std, Fsu, Sys, Cmd, Boundary = Value
}

object OooRecoveryCause extends ChiselEnum {
  val Branch, Exception, Interrupt, Nuke, Debug, CtuCancel = Value
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
  val producerIqBank = UInt(p.iqBankWidth.W)
  val producerIqEntry = UInt(p.iqEntryWidth.W)
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
  val pcBase = new PcBufferToken(p)
  val residentGeneration = UInt(p.residentGenerationWidth.W)
  val initiallyCompletedMembers = UInt(p.maxOrdinaryUopsPerGroup.W)
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
}

/** Physical grouped-ROB row. A row owns one exact RID generation and a dense
  * member-completion bitmap; slot-only completion is never authoritative.
  */
class OooRobPhysicalGroupRecord(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val key = new RobGroupKey(p)
  val transactionId = UInt(p.transactionIdWidth.W)
  val claimEpoch = UInt(p.reservationEpochWidth.W)
  val brob = new BrobPointer(p)
  val pcBase = new PcBufferToken(p)
  val residentGeneration = UInt(p.residentGenerationWidth.W)
  val logicalUopMask = UInt(p.decodedUopWidth.W)
  val physicalMemberCount = UInt(p.robMemberCountWidth.W)
  val pMapQRows = UInt(p.destinationDemandWidth.W)
  val completedMembers = UInt(p.maxOrdinaryUopsPerGroup.W)
  val architecturalParentCount = UInt(p.robGroupParentDemandWidth.W)
  val boundaryStart = Bool()
  val boundaryStop = Bool()
  val releasePcBase = Bool()
  val preciseTrap = Bool()
}

class OooRobMemberCompletion(val p: OooParams = OooParams()) extends Bundle {
  val key = new RobMemberKey(p)
}

class OooRobMemberCompletionReject(val p: OooParams = OooParams()) extends Bundle {
  val requested = new RobMemberKey(p)
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
  val prefixCount = UInt(p.robGroupCountWidth.W)
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
