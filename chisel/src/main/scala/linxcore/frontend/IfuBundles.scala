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
  val ItlbMiss, PredictionCorrection, FetchReplay, StaleResponse = Value
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
  val taken = Bool()
  val branchPc = UInt(p.pcWidth.W)
  val target = UInt(p.pcWidth.W)
  val kind = BoundaryKind()
  val provider = PredictionProvider()
  val stage = BSideStage()
  val checkpointId = UInt(p.checkpointWidth.W)
  val epoch = UInt(p.blockEpochWidth.W)
}

class InstructionBufferEntry(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val pc = UInt(p.pcWidth.W)
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
}

class D1InstructionGroup(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val validMask = UInt(p.decodeWidth.W)
  val entries = Vec(p.decodeWidth, new InstructionBufferEntry(p))
}

class IfuInnerFlush(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val threadId = UInt(p.threadIdWidth.W)
  val restartPc = UInt(p.pcWidth.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val newEpoch = UInt(p.blockEpochWidth.W)
  val reason = IfuInnerFlushReason()
}
