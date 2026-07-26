package linxcore.frontend

import chisel3._
import chisel3.util.Cat
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
  // BSTART may seed the final prediction before the block body is fetched.
  // Only BSTOP makes the predicted continuation eligible to steer I-F0.
  val continuationReady = Bool()
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
  val restartPc = UInt(p.pcWidth.W)
  val correction = Bool()
  // B-F4 also owns the predicted transfer when I-F4 reaches the end of the
  // block.  This is distinct from a tuple correction: a correct retained
  // prediction still has to redirect I-F0 after the body has been fetched.
  val finalSteer = Bool()
  // This BF4 result carries a block prediction forward, but its request-owned
  // checkpoint cannot be resolved by a SETC in this transaction.  Retire it
  // after the result/canonical correction is accepted; the terminal
  // transaction allocates the backend-resolvable checkpoint.
  val retireHistory = Bool()
  val finalResponse = Bool()
}

class BSideResolveUpdate(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val transactionId = UInt(p.uopUidWidth.W)
  val predictionTag = UInt(p.uopUidWidth.W)
  val threadId = UInt(p.threadIdWidth.W)
  val fetchPacketUid = UInt(p.uopUidWidth.W)
  val fetchSeq = UInt(p.uopUidWidth.W)
  val epoch = UInt(p.blockEpochWidth.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val requestPc = UInt(p.pcWidth.W)
  val branchPc = UInt(p.pcWidth.W)
  val fallthroughPc = UInt(p.pcWidth.W)
  val taken = Bool()
  val target = UInt(p.pcWidth.W)
  val kind = BoundaryKind()
  val mispredict = Bool()
}

class BSidePipePayload(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val request = new ISideFetchRequest(p, lineBytes)
  val effective = new BranchPredictionRecord(p)
  val ghrBefore = UInt(BSideHistoryContract.GhrWidth.W)
  val rasTopValidBefore = Bool()
  val rasTopBefore = UInt(p.pcWidth.W)
}

class BSideHistoryAllocate(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val request = new ISideFetchRequest(p, lineBytes)
  val predictionTag = UInt(p.uopUidWidth.W)
}

class BSideHistoryCheckpoint(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val rasDepth: Int = 8)
    extends Bundle {
  private val rasPointerWidth = math.max(1, chisel3.util.log2Ceil(rasDepth))
  private val rasCountWidth = math.max(1, chisel3.util.log2Ceil(rasDepth + 1))

  val valid = Bool()
  val request = new ISideFetchRequest(p, lineBytes)
  val predictionTag = UInt(p.uopUidWidth.W)
  val ghrBefore = UInt(BSideHistoryContract.GhrWidth.W)
  val appliedValid = Bool()
  val appliedTaken = Bool()
  val rasBefore = Vec(rasDepth, UInt(p.pcWidth.W))
  val rasSpBefore = UInt(rasPointerWidth.W)
  val rasCountBefore = UInt(rasCountWidth.W)
  val rasAppliedValid = Bool()
  val retireOnPrune = Bool()
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

object BSideHistoryContract {
  val GhrWidth = 16

  def appendConditional(history: UInt, taken: Bool): UInt = {
    require(history.getWidth == GhrWidth)
    Cat(history(GhrWidth - 2, 0), taken)
  }
}
