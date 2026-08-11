package linxcore.top.interface

import chisel3._
import linxcore.common.BoundaryKind
import linxcore.params.CoreParams

object BranchValidationKind extends ChiselEnum {
  val Condition, Target = Value
}

/** Exact backend result used to train B-SIDE and, on mismatch, start precise
  * recovery. Prediction identity and resident ROB identity remain separate.
  */
class BranchValidationTxn(val p: CoreParams) extends Bundle {
  val executionTransactionId = UInt(p.transactionIdWidth.W)
  val rob = new RobIdentity(p)
  val instruction = new InstructionIdentity(p)
  val kind = BranchValidationKind()

  val predictionValid = Bool()
  val predictionTag = UInt(p.transactionIdWidth.W)
  val predictionTransactionId = UInt(p.transactionIdWidth.W)
  val fetchPacketUid = UInt(p.instructionIdWidth.W)
  val fetchSeq = UInt(p.instructionIdWidth.W)
  val predictionEpoch = UInt(p.epochWidth.W)
  val checkpointId = UInt(InterfaceWidth.index(
    p.ifu.predictionCheckpointEntries).W)
  val requestPc = UInt(p.pcWidth.W)
  val predictedTaken = Bool()
  val predictedBranchPc = UInt(p.pcWidth.W)
  val predictedTarget = UInt(p.pcWidth.W)
  val predictedFallthroughPc = UInt(p.pcWidth.W)
  val predictedKind = BoundaryKind()

  val actualTaken = Bool()
  val actualBranchPc = UInt(p.pcWidth.W)
  val actualTarget = UInt(p.pcWidth.W)
  val actualFallthroughPc = UInt(p.pcWidth.W)
  val actualKind = BoundaryKind()
}

/** B-SIDE training transaction after backend comparison. */
class BranchResolveTxn(val p: CoreParams) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val predictionTag = UInt(p.transactionIdWidth.W)
  val stid = UInt(p.ooo.stidWidth.W)
  val fetchPacketUid = UInt(p.instructionIdWidth.W)
  val fetchSeq = UInt(p.instructionIdWidth.W)
  val epoch = UInt(p.epochWidth.W)
  val checkpointId = UInt(InterfaceWidth.index(
    p.ifu.predictionCheckpointEntries).W)
  val requestPc = UInt(p.pcWidth.W)
  val branchPc = UInt(p.pcWidth.W)
  val fallthroughPc = UInt(p.pcWidth.W)
  val taken = Bool()
  val target = UInt(p.pcWidth.W)
  val kind = BoundaryKind()
  val mispredict = Bool()
}
