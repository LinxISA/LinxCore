package linxcore.frontend

import chisel3._
import linxcore.common.{BoundaryKind, InterfaceParams}

object BSideStage extends ChiselEnum {
  val Sequential, BF0, BF1, BF2, BF3, BF4 = Value
}

object PredictionProvider extends ChiselEnum {
  val Sequential,
      NanoBtb,
      UBtb,
      FastRas,
      PBtb,
      Bim,
      ShortTage,
      MediumTage,
      Static,
      LongTage,
      IndirectBtb,
      Loop,
      FinalRas = Value
}

object IfuInnerFlushReason extends ChiselEnum {
  val ItlbMiss, PredictionCorrection, FetchReplay, StaleResponse, BruRecovery = Value
}

object IfuPruneScope extends ChiselEnum {
  val KillAllThreadState, KillTriggerAndYounger, PreserveTriggerKillYounger = Value
}

object GhrRecoveryAction extends ChiselEnum {
  val None, Reset, RestoreTrigger = Value
}

object RasRecoveryAction extends ChiselEnum {
  val None, Reset, RestoreTrigger = Value
}

object RasUpdateAction extends ChiselEnum {
  val None, Push, Pop = Value
}

class IfuEpochSeed(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val threadId = UInt(p.threadIdWidth.W)
  val epoch = UInt(p.blockEpochWidth.W)
}

class IfuFetchIdentity(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val threadId = UInt(p.threadIdWidth.W)
  val fetchPacketUid = UInt(p.uopUidWidth.W)
  val fetchSeq = UInt(p.uopUidWidth.W)
  val fetchSlot = UInt(p.fetchSlotWidth.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val epoch = UInt(p.blockEpochWidth.W)
}

class BranchPredictionRecord(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val predictionTag = UInt(p.uopUidWidth.W)
  val requestPc = UInt(p.pcWidth.W)
  val taken = Bool()
  val branchPc = UInt(p.pcWidth.W)
  val target = UInt(p.pcWidth.W)
  val fallthroughPc = UInt(p.pcWidth.W)
  val kind = BoundaryKind()
  val provider = PredictionProvider()
  val stage = BSideStage()
  val confidence = UInt(2.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val epoch = UInt(p.blockEpochWidth.W)
}

class InstructionBufferEntry(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val pc = UInt(p.pcWidth.W)
  val instructionUid = UInt(p.uopUidWidth.W)
  val transactionId = UInt(p.uopUidWidth.W)
  val insn = UInt(p.insnWidth.W)
  val lenBytes = UInt(p.lenWidth.W)
  val isBlockStart = Bool()
  val isBlockStop = Bool()
  val identity = new IfuFetchIdentity(p)
  val prediction = new BranchPredictionRecord(p)
}

class InstructionBufferEnqueueGroup(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val validMask = UInt(p.fetchWidth.W)
  val entries = Vec(p.fetchWidth, new InstructionBufferEntry(p))
  val transactionComplete = Bool()
}

class D1InstructionGroup(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val validMask = UInt(p.decodeWidth.W)
  val entries = Vec(p.decodeWidth, new InstructionBufferEntry(p))
}

class IfuInnerFlush(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val threadId = UInt(p.threadIdWidth.W)
  val transactionId = UInt(p.uopUidWidth.W)
  val fetchSeq = UInt(p.uopUidWidth.W)
  val oldEpoch = UInt(p.blockEpochWidth.W)
  val restartPc = UInt(p.pcWidth.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val newEpoch = UInt(p.blockEpochWidth.W)
  val reason = IfuInnerFlushReason()
  val scope = IfuPruneScope()
  val historyKeyValid = Bool()
  val predictionTag = UInt(p.uopUidWidth.W)
  val fetchPacketUid = UInt(p.uopUidWidth.W)
  val ghrAction = GhrRecoveryAction()
  val ghrAppendValid = Bool()
  val ghrAppendTaken = Bool()
  val rasAction = RasRecoveryAction()
  val rasUpdate = RasUpdateAction()
  val rasPushAddress = UInt(p.pcWidth.W)
}

object IfuFlushContract {
  def isYounger(candidateFetchSeq: UInt, triggerFetchSeq: UInt): Bool = {
    require(candidateFetchSeq.getWidth == triggerFetchSeq.getWidth)
    val delta = candidateFetchSeq - triggerFetchSeq
    delta =/= 0.U && !delta(delta.getWidth - 1)
  }

  def kills(
      threadId: UInt,
      epoch: UInt,
      fetchSeq: UInt,
      transactionId: UInt,
      flush: IfuInnerFlush): Bool = {
    val sameThread = threadId === flush.threadId
    val sameTrigger =
      epoch === flush.oldEpoch &&
      fetchSeq === flush.fetchSeq &&
        transactionId === flush.transactionId
    val younger = isYounger(fetchSeq, flush.fetchSeq)

    flush.valid &&
      sameThread &&
      Mux(
        flush.scope === IfuPruneScope.KillAllThreadState,
        true.B,
        Mux(
          flush.scope === IfuPruneScope.KillTriggerAndYounger,
          sameTrigger || younger,
          younger))
  }

  def kills(identity: IfuFetchIdentity, transactionId: UInt, flush: IfuInnerFlush): Bool =
    kills(
      identity.threadId,
      identity.epoch,
      identity.fetchSeq,
      transactionId,
      flush)
}
