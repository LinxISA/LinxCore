package linxcore.frontend

import chisel3._
import chisel3.util.{Cat, log2Ceil}
import linxcore.common.InterfaceParams

object ISideF2Status extends ChiselEnum {
  val Hit, ItlbMiss, AccessFault, L1IMiss, Stale = Value
}

class ISideFetchRequest(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  require(lineBytes >= 8 && (lineBytes & (lineBytes - 1)) == 0)

  private val lineOffsetBits = log2Ceil(lineBytes)

  val pc = UInt(p.pcWidth.W)
  val lineVa = UInt(p.pcWidth.W)
  val transactionId = UInt(p.uopUidWidth.W)
  val identity = new IfuFetchIdentity(p)
  val prediction = new BranchPredictionRecord(p)

  def alignedPc: UInt = Cat(pc(p.pcWidth - 1, lineOffsetBits), 0.U(lineOffsetBits.W))
}

class ISideTranslationResult(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val pageBytes: Int = 4096)
    extends Bundle {
  require(pageBytes > lineBytes && (pageBytes & (pageBytes - 1)) == 0)

  private val pageOffsetBits = log2Ceil(pageBytes)

  val request = new ISideFetchRequest(p, lineBytes)
  val hit = Bool()
  val accessFault = Bool()
  val ppn = UInt((p.pcWidth - pageOffsetBits).W)
}

class ISideCacheCandidate(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  private val lineOffsetBits = log2Ceil(lineBytes)

  val request = new ISideFetchRequest(p, lineBytes)
  val candidateValid = Bool()
  val physicalTag = UInt((p.pcWidth - lineOffsetBits).W)
  val lineData = UInt((lineBytes * 8).W)
}

class ISideItlbRefill(
    val p: InterfaceParams = InterfaceParams(),
    val pageBytes: Int = 4096)
    extends Bundle {
  private val pageOffsetBits = log2Ceil(pageBytes)

  val vpn = UInt((p.pcWidth - pageOffsetBits).W)
  val ppn = UInt((p.pcWidth - pageOffsetBits).W)
  val executable = Bool()
}

class ISideL1IRefill(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val linePa = UInt(p.pcWidth.W)
  val lineData = UInt((lineBytes * 8).W)
}

class ISidePtwRequest(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val pageBytes: Int = 4096)
    extends Bundle {
  require(pageBytes > lineBytes && (pageBytes & (pageBytes - 1)) == 0)

  private val pageOffsetBits = log2Ceil(pageBytes)

  val request = new ISideFetchRequest(p, lineBytes)
  val vpn = UInt((p.pcWidth - pageOffsetBits).W)
}

class ISideLineReadRequest(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val request = new ISideFetchRequest(p, lineBytes)
  val linePa = UInt(p.pcWidth.W)
}

class ISideFetchFault(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val request = new ISideFetchRequest(p, lineBytes)
  val linePa = UInt(p.pcWidth.W)
}

class ISideF2Result(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val request = new ISideFetchRequest(p, lineBytes)
  val status = ISideF2Status()
  val linePa = UInt(p.pcWidth.W)
  val lineData = UInt((lineBytes * 8).W)
}

class ISideLineResponse(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val transactionId = UInt(p.uopUidWidth.W)
  val threadId = UInt(p.threadIdWidth.W)
  val fetchPacketUid = UInt(p.uopUidWidth.W)
  val fetchSeq = UInt(p.uopUidWidth.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val epoch = UInt(p.blockEpochWidth.W)
  val lineVa = UInt(p.pcWidth.W)
  val linePa = UInt(p.pcWidth.W)
  val lineData = UInt((lineBytes * 8).W)
}

class ISideInstructionCandidate(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val pc = UInt(p.pcWidth.W)
  val insn = UInt(p.insnWidth.W)
  val lenBytes = UInt(p.lenWidth.W)
  val crossesLine = Bool()
  val identity = new IfuFetchIdentity(p)
  val prediction = new BranchPredictionRecord(p)
}

class ISideAssembledGroup(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val validMask = UInt(p.fetchWidth.W)
  val entries = Vec(p.fetchWidth, new ISideInstructionCandidate(p))
  val lineComplete = Bool()
}
