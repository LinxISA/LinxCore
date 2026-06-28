package linxcore.common

import chisel3._
import chisel3.util.log2Ceil
import linxcore.rob.ROBID

final case class InterfaceParams(
    fetchWidth: Int = 4,
    decodeWidth: Int = 4,
    issueWidth: Int = 4,
    commitWidth: Int = 4,
    pcWidth: Int = 64,
    windowWidth: Int = 64,
    opcodeWidth: Int = 12,
    insnWidth: Int = 64,
    lenWidth: Int = 4,
    archRegWidth: Int = 6,
    physRegWidth: Int = 6,
    robEntries: Int = 64,
    iqEntries: Int = 32,
    blockBidWidth: Int = 64,
    blockUidWidth: Int = 64,
    uopUidWidth: Int = 64,
    uopKindWidth: Int = 3,
    replayDepthWidth: Int = 8,
    templateKindWidth: Int = 3,
    immWidth: Int = 64,
    immTypeWidth: Int = 3,
    lsidWidth: Int = 32,
    checkpointWidth: Int = 6,
    threadIdWidth: Int = 8,
    producerWidth: Int = 4,
    memSizeWidth: Int = 4,
    trapCauseWidth: Int = 32,
    blockEpochWidth: Int = 8) {
  require(fetchWidth > 0 && fetchWidth <= 4, "fetchWidth must fit the 64-bit fetch window")
  require(decodeWidth > 0 && decodeWidth <= 4, "decodeWidth must fit the 64-bit fetch window")
  require(issueWidth > 0 && issueWidth <= 4, "issueWidth must fit the bring-up issue fabric")
  require(commitWidth > 0 && commitWidth <= 4, "commitWidth must fit the bring-up retire fabric")
  require(fetchWidth == decodeWidth, "fetchWidth and decodeWidth share the F4 bundle contract")
  require(pcWidth == 64, "LinxCore PC is 64 bits")
  require(windowWidth == 64, "F4 decode window is 64 bits")
  require(opcodeWidth == 12, "pyCircuit opcode IDs are 12 bits")
  require(insnWidth >= 48, "instruction payload must hold 48-bit Linx encodings")
  require(lenWidth >= 4, "instruction length must encode 0, 2, 4, 6, and 8 byte rows")
  require(archRegWidth == 6, "architectural register tags use the reg6 namespace")
  require(physRegWidth == 6, "bring-up physical register tags use 64 entries")
  require(robEntries > 1 && (robEntries & (robEntries - 1)) == 0, "robEntries must be a power of two")
  require(iqEntries > 1 && (iqEntries & (iqEntries - 1)) == 0, "iqEntries must be a power of two")
  require(blockBidWidth >= 64, "hardware block BID must preserve the 64-bit model-derived sideband")
  require(blockUidWidth >= 64, "block UID must preserve the 64-bit DFX namespace")
  require(uopUidWidth >= 64, "uop UID must preserve the 64-bit DFX namespace")
  require(lsidWidth >= 32, "load/store ID must preserve the 32-bit backend contract")
  require(checkpointWidth == 6, "checkpoint ID follows the current decode contract")

  def fetchSlotWidth: Int = math.max(1, log2Ceil(fetchWidth))
  def robIndexWidth: Int = log2Ceil(robEntries)
  def iqIndexWidth: Int = log2Ceil(iqEntries)
}

object LinxCommonConstants {
  val RegInvalid: BigInt = 0x3f
  val TrapBruRecoveryNotBstart: BigInt = 0x0000b001L

  def regInvalid(width: Int = 6): UInt = RegInvalid.U(width.W)
  def trapBruRecoveryNotBstart(width: Int = 32): UInt = TrapBruRecoveryNotBstart.U(width.W)
}

object BoundaryKind extends ChiselEnum {
  val Fall, Cond, Call, Ret, Direct, Ind, ICall = Value
}

object OperandClass extends ChiselEnum {
  val Invalid, P, T, U, CArg = Value
}

object DestinationKind extends ChiselEnum {
  val None, Gpr, T, U = Value
}

object DispatchTarget extends ChiselEnum {
  val None, D2, Alu, Bru, Lsu, Cmd, Bn = Value
}

class FrontendDecodePacket(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val pc = UInt(p.pcWidth.W)
  val window = UInt(p.windowWidth.W)
  val pktUid = UInt(p.uopUidWidth.W)
  val checkpointId = UInt(p.checkpointWidth.W)
}

class UopUidPacket(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val uid = UInt(p.uopUidWidth.W)
  val parentUid = UInt(p.uopUidWidth.W)
  val kind = UInt(p.uopKindWidth.W)
  val fetchPacketUid = UInt(p.uopUidWidth.W)
  val fetchSlot = UInt(p.fetchSlotWidth.W)
  val replayDepth = UInt(p.replayDepthWidth.W)
  val templateKind = UInt(p.templateKindWidth.W)
}

class DecodedOperand(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val operandClass = OperandClass()
  val archTag = UInt(p.archRegWidth.W)
  val relTag = UInt(p.archRegWidth.W)
}

class DecodedDestination(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val kind = DestinationKind()
  val archTag = UInt(p.archRegWidth.W)
  val relTag = UInt(p.archRegWidth.W)
}

class RenamedOperand(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val operandClass = OperandClass()
  val archTag = UInt(p.archRegWidth.W)
  val relTag = UInt(p.archRegWidth.W)
  val physTag = UInt(p.physRegWidth.W)
  val ready = Bool()
  val producer = UInt(p.producerWidth.W)
  val literalValid = Bool()
  val literal = UInt(p.immWidth.W)
}

class RenamedDestination(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val kind = DestinationKind()
  val archTag = UInt(p.archRegWidth.W)
  val relTag = UInt(p.archRegWidth.W)
  val physTag = UInt(p.physRegWidth.W)
  val oldPhysTag = UInt(p.physRegWidth.W)
}

class DecodedUop(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val threadId = UInt(p.threadIdWidth.W)
  val pc = UInt(p.pcWidth.W)
  val opcode = UInt(p.opcodeWidth.W)
  val uopType = UInt(p.uopKindWidth.W)
  val src = Vec(3, new DecodedOperand(p))
  val dst = Vec(1, new DecodedDestination(p))
  val imm = UInt(p.immWidth.W)
  val immType = UInt(p.immTypeWidth.W)
  val immValid = Bool()
  val rid = new ROBID(p.robEntries)
  val bid = new ROBID(p.robEntries)
  val gid = new ROBID(p.robEntries)
  val lsid = UInt(p.lsidWidth.W)
  val isLoad = Bool()
  val isStore = Bool()
  val storeSplitIntent = Bool()
  val isLoadStorePair = Bool()
  val isStorePcr = Bool()
  val cacheMaintainNoSplit = Bool()
  val sob = Bool()
  val eob = Bool()
  val boundaryKind = BoundaryKind()
  val boundaryTarget = UInt(p.pcWidth.W)
  val predTaken = Bool()
  val insnLen = UInt(p.lenWidth.W)
  val insnRaw = UInt(p.insnWidth.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val blockUid = UInt(p.blockUidWidth.W)
  val blockBidValid = Bool()
  val blockBid = UInt(p.blockBidWidth.W)
  val uid = new UopUidPacket(p)
}

class RenamedUop(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val threadId = UInt(p.threadIdWidth.W)
  val pc = UInt(p.pcWidth.W)
  val opcode = UInt(p.opcodeWidth.W)
  val dispatchTarget = DispatchTarget()
  val src = Vec(3, new RenamedOperand(p))
  val dst = Vec(1, new RenamedDestination(p))
  val imm = UInt(p.immWidth.W)
  val immType = UInt(p.immTypeWidth.W)
  val immValid = Bool()
  val rid = new ROBID(p.robEntries)
  val bid = new ROBID(p.robEntries)
  val gid = new ROBID(p.robEntries)
  val lsid = UInt(p.lsidWidth.W)
  val isLoad = Bool()
  val isStore = Bool()
  val storeSplitIntent = Bool()
  val isLoadStorePair = Bool()
  val isStorePcr = Bool()
  val cacheMaintainNoSplit = Bool()
  val sob = Bool()
  val eob = Bool()
  val boundaryKind = BoundaryKind()
  val boundaryTarget = UInt(p.pcWidth.W)
  val predTaken = Bool()
  val resolvedD2 = Bool()
  val insnLen = UInt(p.lenWidth.W)
  val insnRaw = UInt(p.insnWidth.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val blockUid = UInt(p.blockUidWidth.W)
  val blockBidValid = Bool()
  val blockBid = UInt(p.blockBidWidth.W)
  val uid = new UopUidPacket(p)
}

class IssueQueueEntry(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val inflight = Bool()
  val issueSlot = UInt(p.iqIndexWidth.W)
  val target = DispatchTarget()
  val uop = new RenamedUop(p)
}

class LsuRequest(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val uid = UInt(p.uopUidWidth.W)
  val isLoad = Bool()
  val isStore = Bool()
  val pc = UInt(p.pcWidth.W)
  val opcode = UInt(p.opcodeWidth.W)
  val rid = new ROBID(p.robEntries)
  val bid = new ROBID(p.robEntries)
  val gid = new ROBID(p.robEntries)
  val subrid = new ROBID(p.robEntries)
  val modelLsId = new ROBID(p.robEntries)
  val lsid = UInt(p.lsidWidth.W)
  val threadId = UInt(p.threadIdWidth.W)
  val stid = UInt(p.threadIdWidth.W)
  val addr = UInt(p.pcWidth.W)
  val data = UInt(p.immWidth.W)
  val mask = UInt((p.immWidth / 8).W)
  val size = UInt(p.memSizeWidth.W)
  val start = UInt(p.memSizeWidth.W)
  val blockBidValid = Bool()
  val blockBid = UInt(p.blockBidWidth.W)
}

class LsuResponse(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val isLoad = Bool()
  val isStore = Bool()
  val rid = new ROBID(p.robEntries)
  val gid = new ROBID(p.robEntries)
  val lsid = UInt(p.lsidWidth.W)
  val addr = UInt(p.pcWidth.W)
  val data = UInt(p.immWidth.W)
  val mask = UInt((p.immWidth / 8).W)
  val trapValid = Bool()
  val trapCause = UInt(p.trapCauseWidth.W)
}

class RobRow(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val done = Bool()
  val pc = UInt(p.pcWidth.W)
  val opcode = UInt(p.opcodeWidth.W)
  val insnRaw = UInt(p.insnWidth.W)
  val insnLen = UInt(p.lenWidth.W)
  val dst = new RenamedDestination(p)
  val value = UInt(p.immWidth.W)
  val src = Vec(3, new RenamedOperand(p))
  val loadValid = Bool()
  val storeValid = Bool()
  val lsid = UInt(p.lsidWidth.W)
  val sob = Bool()
  val eob = Bool()
  val boundaryKind = BoundaryKind()
  val boundaryTarget = UInt(p.pcWidth.W)
  val predTaken = Bool()
  val blockEpoch = UInt(p.blockEpochWidth.W)
  val blockUid = UInt(p.blockUidWidth.W)
  val blockBidValid = Bool()
  val blockBid = UInt(p.blockBidWidth.W)
  val resolvedD2 = Bool()
  val checkpointId = UInt(p.checkpointWidth.W)
  val macroOp = Bool()
  val uid = new UopUidPacket(p)
  val trapValid = Bool()
  val trapCause = UInt(p.trapCauseWidth.W)
}

class LinxTraceProbe(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val valid = Bool()
  val cycle = UInt(64.W)
  val pc = UInt(p.pcWidth.W)
  val opcode = UInt(p.opcodeWidth.W)
  val uid = UInt(p.uopUidWidth.W)
  val rid = new ROBID(p.robEntries)
  val bid = new ROBID(p.robEntries)
  val gid = new ROBID(p.robEntries)
  val blockBidValid = Bool()
  val blockBid = UInt(p.blockBidWidth.W)
}
