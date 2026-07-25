package linxcore.frontend

import chisel3._
import linxcore.common.{BoundaryKind, InterfaceParams}

class BSideBoundaryMetadata(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val transactionId = UInt(p.uopUidWidth.W)
  val threadId = UInt(p.threadIdWidth.W)
  val fetchPacketUid = UInt(p.uopUidWidth.W)
  val fetchSeq = UInt(p.uopUidWidth.W)
  val epoch = UInt(p.blockEpochWidth.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val branchPc = UInt(p.pcWidth.W)
  val target = UInt(p.pcWidth.W)
  val fallthroughPc = UInt(p.pcWidth.W)
  val kind = BoundaryKind()
  val staticTaken = Bool()
}

class BSidePredictionCandidate(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val taken = Bool()
  val branchPc = UInt(p.pcWidth.W)
  val target = UInt(p.pcWidth.W)
  val fallthroughPc = UInt(p.pcWidth.W)
  val kind = BoundaryKind()
  val provider = PredictionProvider()
  val stage = BSideStage()
  val confidence = UInt(2.W)
}

class BSidePredictionUpdate(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val request = new ISideFetchRequest(p, lineBytes)
  val prediction = new BranchPredictionRecord(p)
  val correction = Bool()
  val finalResponse = Bool()
}

class BSideResolveUpdate(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val transactionId = UInt(p.uopUidWidth.W)
  val predictionTag = UInt(p.uopUidWidth.W)
  val threadId = UInt(p.threadIdWidth.W)
  val epoch = UInt(p.blockEpochWidth.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val requestPc = UInt(p.pcWidth.W)
  val branchPc = UInt(p.pcWidth.W)
  val fallthroughPc = UInt(p.pcWidth.W)
  val taken = Bool()
  val target = UInt(p.pcWidth.W)
  val kind = BoundaryKind()
}

class BSidePipePayload(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val request = new ISideFetchRequest(p, lineBytes)
  val effective = new BranchPredictionRecord(p)
}

class BSideBtbEntry(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val requestPc = UInt(p.pcWidth.W)
  val branchPc = UInt(p.pcWidth.W)
  val target = UInt(p.pcWidth.W)
  val fallthroughPc = UInt(p.pcWidth.W)
  val taken = Bool()
  val kind = BoundaryKind()
}

object BSidePredictionContract {
  def exactTupleMatch(lhs: BranchPredictionRecord, rhs: BSidePredictionCandidate): Bool =
    lhs.taken === rhs.taken &&
      lhs.branchPc === rhs.branchPc &&
      lhs.target === rhs.target &&
      lhs.kind === rhs.kind
}
