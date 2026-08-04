package linxcore.top.interface

import chisel3._
import chisel3.util.{Decoupled, log2Ceil}
import linxcore.params.CoreParams

/** Typed precise-trap intent attached to the canonical D1 decode result. */
class DecodeTrapIntent(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val cause = UInt(p.trapCauseWidth.W)
}

/** One normalized D1 result shared by encoded and CTU template operations. */
class DecodedLane(val p: CoreParams) extends Bundle {
  val uop = new DecodedUop(p)
  val trap = new DecodeTrapIntent(p)
}

class DecodedPacket(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.widths.decodeWidth).W)
  val entries = Vec(p.widths.decodeWidth, new DecodedLane(p))
}

class VirtualRobGroupIntent(val p: CoreParams) extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.ooo.stidWidth.W)
  val ridSlot = UInt(p.ooo.ridSlotWidth.W)
  val ridGeneration = UInt(p.ridGenerationWidth.W)
}

class D2AdmissionLane(val p: CoreParams) extends Bundle {
  val uop = new DecodedUop(p)
  val trap = new DecodeTrapIntent(p)
  val residentBound = Bool()
  val brobBound = Bool()
}

/** Immutable D2 virtual allocation transaction. Physical ROB/BROB publication
  * is deliberately absent; D3 remains the unique tail mutator.
  */
class D2AdmissionGroup(val p: CoreParams) extends Bundle {
  val count = UInt(PrefixPacketContract.countWidth(p.widths.decodeWidth).W)
  val groupCount = UInt(PrefixPacketContract.countWidth(p.widths.decodeWidth).W)
  val groups = Vec(p.widths.decodeWidth, new VirtualRobGroupIntent(p))
  val entries = Vec(p.widths.decodeWidth, new D2AdmissionLane(p))
}

/** Complete public IO of the Task-7 OOO D1/D2 slice.
  *
  * Later OOO tasks extend or replace this slice at the canonical `OOOIO`
  * boundary; no implementation package owns a duplicate public contract.
  */
class OOOD1D2IO(val p: CoreParams) extends Bundle {
  val fromCtu = Flipped(Decoupled(new D1Packet(p)))
  val ridTailSlot = Input(Vec(p.ooo.stidCount,
    UInt(p.ooo.ridSlotWidth.W)))
  val ridTailGeneration = Input(Vec(p.ooo.stidCount,
    UInt(p.ridGenerationWidth.W)))
  val recovery = Flipped(new RecoveryTargetIO(p))
  val d2 = Decoupled(new D2AdmissionGroup(p))
}
