package linxcore.ooo

import chisel3._

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
  val requestPc = UInt(p.pcWidth.W)
  val taken = Bool()
  val target = UInt(p.pcWidth.W)
  val fallthroughPc = UInt(p.pcWidth.W)
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
  val pcBaseWrites = UInt(p.countWidth(p.pcWritePorts).W)
  val pDestinations = UInt(p.renameCountWidth.W)
  val tAllocations = UInt(p.renameCountWidth.W)
  val uAllocations = UInt(p.renameCountWidth.W)
  val mapQRows = UInt(p.renameCountWidth.W)
  val dispatchWritesByClass = Vec(p.iqClassCount, UInt(p.dispatchCountWidth.W))
  val dispatchWritesByBank = Vec(p.iqBankCount, UInt(p.dispatchCountWidth.W))
  val loadIds = UInt(p.decodedUopCountWidth.W)
  val storeIds = UInt(p.decodedUopCountWidth.W)
}

class PcBufferToken(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val index = UInt(p.pcBufferIndexWidth.W)
  val byteOffset = UInt(p.pcOffsetWidth.W)
  val allocationEpoch = UInt(p.reservationEpochWidth.W)
}

class PMapPayload(val p: OooParams = OooParams()) extends Bundle {
  val ptag = UInt(p.pTagWidth.W)
  val producerToken = UInt(p.transactionIdWidth.W)
  val producerIqBank = UInt(p.iqBankWidth.W)
  val producerIqEntry = UInt(p.iqEntryWidth.W)
  val ready = Bool()
  val size = UInt(4.W)
  val stid = UInt(p.stidWidth.W)
  val epoch = UInt(p.epochWidth.W)
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
  val virtualTailEpoch = UInt(p.reservationEpochWidth.W)
  val demand = new InstructionDemand(p)
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
