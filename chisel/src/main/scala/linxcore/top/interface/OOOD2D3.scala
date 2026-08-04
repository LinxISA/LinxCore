package linxcore.top.interface

import chisel3._
import chisel3.util.{Decoupled, Valid, log2Ceil}
import linxcore.params.CoreParams

/** Exact rename-history row owned by the D2/D3 boundary.
  *
  * One row exists per architectural destination slot. Zero-destination uops
  * still pass through D3 with an empty history vector so commit/recovery can
  * preserve per-uop ordering without inventing a single-destination shape.
  */
class D3RenameHistory(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val kind = OperandKind()
  val atag = UInt(p.archRegWidth.W)

  val ptag = UInt(p.ooo.gprTagWidth.W)
  val previousPtag = UInt(p.ooo.gprTagWidth.W)
  val pGeneration = UInt(p.ooo.gprTagGenerationWidth.W)
  val previousPGeneration = UInt(p.ooo.gprTagGenerationWidth.W)
  val pMapQIndex = UInt(InterfaceWidth.index(p.ooo.gprMapQDepthPerStid).W)
  val pMapQGeneration = UInt(p.ooo.gprTagGenerationWidth.W)

  val ttag = UInt(p.ooo.tTagWidth.W)
  val previousTtag = UInt(p.ooo.tTagWidth.W)
  val tGeneration = UInt(p.ooo.localSeqGenerationWidth.W)
  val tMapQIndex = UInt(InterfaceWidth.index(p.ooo.tuMapQDepthPerStid).W)
  val tMapQGeneration = UInt(p.ooo.localSeqGenerationWidth.W)

  val utag = UInt(p.ooo.uTagWidth.W)
  val previousUtag = UInt(p.ooo.uTagWidth.W)
  val uGeneration = UInt(p.ooo.localSeqGenerationWidth.W)
  val uMapQIndex = UInt(InterfaceWidth.index(p.ooo.tuMapQDepthPerStid).W)
  val uMapQGeneration = UInt(p.ooo.localSeqGenerationWidth.W)
}

class D3LocalSeqSnapshot(val p: CoreParams, val entries: Int) extends Bundle {
  val valid = Bool()
  val tag = UInt(InterfaceWidth.index(entries).W)
  val generation = UInt(p.ooo.localSeqGenerationWidth.W)
}

class D3RenameLane(val p: CoreParams) extends Bundle {
  val uop = new RenamedUop(p)
  val trap = new DecodeTrapIntent(p)
  val history = Vec(p.maxDestinationOperands, new D3RenameHistory(p))
  val tSeqBefore = new D3LocalSeqSnapshot(p, p.ooo.tuMapQDepthPerStid)
  val uSeqBefore = new D3LocalSeqSnapshot(p, p.ooo.tuMapQDepthPerStid)
  val residentBound = Bool()
  val brobBound = Bool()
  val blockStart = Bool()
  val blockStop = Bool()
  val earlyRobComplete = Bool()
  val memoryOrder = new MemoryOrderMeta(p)
  val pcBufferIndexOffset = new PcBufferIndexOffset(p)
}

/** Full per-STID serial tail. This is program-order identity, never a
  * physical LIQ/STQ index or a retry-attempt generation.
  */
class MemoryOrderState(val p: CoreParams) extends Bundle {
  val lsid = UInt(p.lsidWidth.W)
  val lid = UInt(p.lsidWidth.W)
  val sid = UInt(p.lsidWidth.W)
  val yostValid = Bool()
  val yostLsid = UInt(p.lsidWidth.W)
  val yostSid = UInt(p.lsidWidth.W)
  val yoldValid = Bool()
  val yoldLsid = UInt(p.lsidWidth.W)
  val yoldLid = UInt(p.lsidWidth.W)
}

class MemoryOrderReservation(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val stid = UInt(p.ooo.stidWidth.W)
  val count = UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W)
  val before = new MemoryOrderState(p)
  val after = new MemoryOrderState(p)
}

class D3RenameGroup(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W)
  val groupCount = UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W)
  val groups = Vec(p.ooo.d3PrefixWidth, new VirtualRobGroupIntent(p))
  val entries = Vec(p.ooo.d3PrefixWidth, new D3RenameLane(p))
  val memoryOrder = new MemoryOrderReservation(p)
}

/** Side-effect-free limit for the oldest complete portion of one retained D3 row. */
class D3PrefixLimit(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W)
  val groupCount = UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W)
}

class RenameCommitReleaseEntry(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val rob = new RobIdentity(p)
  val blockLast = Bool()
  val history = Vec(p.maxDestinationOperands, new D3RenameHistory(p))
}

class RenameCommitReleaseTxn(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.widths.retireWidth).W)
  val lanes = Vec(p.widths.retireWidth, new RenameCommitReleaseEntry(p))
}

class RENUD2D3IO(val p: CoreParams) extends Bundle {
  val fromD2 = Flipped(Decoupled(new D2AdmissionGroup(p)))
  val candidate = Output(Valid(new D3RenameGroup(p)))
  val prefixLimit = Input(Valid(new D3PrefixLimit(p)))
  val toD3 = Decoupled(new D3RenameGroup(p))
  val publicationIdentity = Input(Valid(new OOORobPrepared(p)))
  val release = Flipped(Valid(new RenameCommitReleaseTxn(p)))
  val releaseReady = Output(Bool())
  val releaseApply = Input(Bool())
  val recovery = Flipped(new RecoveryTargetIO(p))
  val debugPMap = Output(Vec(p.ooo.stidCount,
    Vec(p.ooo.gprArchRegs,
      UInt(p.ooo.gprTagWidth.W))))
  val debugTCount = Output(Vec(p.ooo.stidCount,
    UInt(log2Ceil(p.ooo.tuMapQDepthPerStid + 1).W)))
  val debugUCount = Output(Vec(p.ooo.stidCount,
    UInt(log2Ceil(p.ooo.tuMapQDepthPerStid + 1).W)))
}
