package linxcore.top.interface

import chisel3._
import linxcore.params.CoreParams

object PredictionKind extends ChiselEnum {
  val None, Fall, Conditional, Call, Return, Direct, Indirect = Value
}

class PredictionMeta(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val predictionTag = UInt(p.transactionIdWidth.W)
  val transactionId = UInt(p.transactionIdWidth.W)
  val checkpointId =
    UInt(InterfaceWidth.index(p.ifu.predictionCheckpointEntries).W)
  val requestPc = UInt(p.pcWidth.W)
  val taken = Bool()
  val target = UInt(p.pcWidth.W)
  val fallthroughPc = UInt(p.pcWidth.W)
  val kind = PredictionKind()
  val provider = UInt(4.W)
  val confidence = UInt(2.W)
  val epoch = UInt(p.epochWidth.W)
}

class FetchedInstruction(val p: CoreParams) extends Bundle {
  val identity = new InstructionIdentity(p)
  val pc = UInt(p.pcWidth.W)
  val instruction = UInt(p.instructionWidth.W)
  val lengthBytes = UInt(4.W)
  val prediction = new PredictionMeta(p)
  val fetchFault = Bool()
  val fetchFaultCause = UInt(p.trapCauseWidth.W)
}

class FetchedPacket(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.widths.fetchWidth).W)
  val entries = Vec(p.widths.fetchWidth, new FetchedInstruction(p))
}
