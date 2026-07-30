package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

/** Exact semantic producer of one load attempt.
  *
  * These fields mirror the stable meaning of an OOO ROB-member key without
  * making the LSU depend on an OOO implementation class.  They must be mapped
  * field by field at the integration boundary.
  */
class LoadAttemptProducer extends Bundle {
  val valid = Bool()
  val peId = UInt(LoadAttemptIdentity.ScopeWidth.W)
  val stid = UInt(LoadAttemptIdentity.ScopeWidth.W)
  val nativeBidValid = Bool()
  val nativeBid = UInt(LoadAttemptIdentity.IndexWidth.W)
  val brobGeneration = UInt(LoadAttemptIdentity.GenerationWidth.W)
  val ridSlot = UInt(LoadAttemptIdentity.IndexWidth.W)
  val ridGeneration = UInt(LoadAttemptIdentity.GenerationWidth.W)
  val memberIndex = UInt(LoadAttemptIdentity.IndexWidth.W)
  val residentGeneration = UInt(LoadAttemptIdentity.GenerationWidth.W)
}

/** Canonical identity of one producer-owned load attempt.
  *
  * The scalar LSU does not interpret the producer payload.  It retains the
  * payload and generation exactly so an upstream owner can reject a return
  * from an older attempt after replay, row reuse, or generation wrap.
  */
class LoadAttemptIdentity extends Bundle {
  val valid = Bool()
  val producer = new LoadAttemptProducer
  val generation = UInt(LoadAttemptIdentity.GenerationWidth.W)
}

object LoadAttemptIdentity {
  // Protocol capacities, not compressed architectural identifiers.  Bridges
  // must prove their native payloads fit before zero-extending into these
  // fields; truncation is forbidden.
  val ScopeWidth = 16
  val IndexWidth = 16
  val GenerationWidth = 32

  /** Prove that an integration bridge can zero-extend every native identity
    * field into this protocol without truncation.
    *
    * Call this once from each concrete producer adapter during elaboration.
    */
  def requireBridgeFits(
      peIdWidth: Int,
      stidWidth: Int,
      nativeBidWidth: Int,
      ridSlotWidth: Int,
      brobGenerationWidth: Int,
      ridGenerationWidth: Int,
      memberIndexWidth: Int,
      residentGenerationWidth: Int,
      attemptGenerationWidth: Int): Unit = {
    def requireWidth(name: String, width: Int, capacity: Int): Unit =
      require(width > 0 && width <= capacity,
        s"$name width $width must fit in the positive $capacity-bit LoadAttemptIdentity field")

    requireWidth("peId", peIdWidth, ScopeWidth)
    requireWidth("stid", stidWidth, ScopeWidth)
    requireWidth("nativeBid", nativeBidWidth, IndexWidth)
    requireWidth("ridSlot", ridSlotWidth, IndexWidth)
    requireWidth("brobGeneration", brobGenerationWidth, GenerationWidth)
    requireWidth("ridGeneration", ridGenerationWidth, GenerationWidth)
    requireWidth("memberIndex", memberIndexWidth, IndexWidth)
    requireWidth("residentGeneration", residentGenerationWidth, GenerationWidth)
    requireWidth("attemptGeneration", attemptGenerationWidth, GenerationWidth)
  }

  def none: LoadAttemptIdentity = {
    val out = Wire(new LoadAttemptIdentity)
    out.valid := false.B
    out.producer := 0.U.asTypeOf(out.producer)
    out.generation := 0.U
    out
  }

  def equal(lhs: LoadAttemptIdentity, rhs: LoadAttemptIdentity): Bool =
    lhs.valid === rhs.valid &&
      lhs.producer.asUInt === rhs.producer.asUInt &&
      lhs.generation === rhs.generation

  def wellFormed(value: LoadAttemptIdentity): Bool =
    !value.valid || (value.producer.valid && value.producer.nativeBidValid)

  def canonical(value: LoadAttemptIdentity): LoadAttemptIdentity =
    Mux(value.valid, value, none)
}

/** Canonical physical LIQ row lease carried beyond LIQ residency.
  *
  * A load may leave the LIQ before its retained LRET/W1/W2 transaction reaches
  * the architectural terminal sink.  The terminal side therefore cannot use
  * a bare slot number: it must retain the allocation wrap together with the
  * slot and revalidate the exact load attempt before acknowledging the result.
  * Fixed protocol capacities keep this identity independent of LIQ/ROB sizing.
  */
class LoadCanonicalRowIdentity extends Bundle {
  val valid = Bool()
  val slot = UInt(LoadAttemptIdentity.IndexWidth.W)
  val generation = UInt(LoadAttemptIdentity.GenerationWidth.W)
}

object LoadCanonicalRowIdentity {
  def requireBridgeFits(entries: Int): Unit = {
    require(entries > 1 && (entries & (entries - 1)) == 0,
      "canonical load-row bridge requires a power-of-two LIQ greater than one")
    require(log2Ceil(entries) <= LoadAttemptIdentity.IndexWidth,
      s"LIQ slot width ${log2Ceil(entries)} must fit the ${LoadAttemptIdentity.IndexWidth}-bit canonical row identity")
  }

  def none: LoadCanonicalRowIdentity = {
    val out = Wire(new LoadCanonicalRowIdentity)
    out.valid := false.B
    out.slot := 0.U
    out.generation := 0.U
    out
  }

  def fromRobId(value: ROBID): LoadCanonicalRowIdentity = {
    requireBridgeFits(value.entries)
    val out = Wire(new LoadCanonicalRowIdentity)
    out.valid := value.valid
    out.slot := value.value
    out.generation := value.wrap.asUInt
    out
  }

  def equal(lhs: LoadCanonicalRowIdentity, rhs: LoadCanonicalRowIdentity): Bool =
    lhs.valid === rhs.valid && lhs.slot === rhs.slot &&
      lhs.generation === rhs.generation

  def wellFormed(value: LoadCanonicalRowIdentity, entries: Int): Bool = {
    requireBridgeFits(entries)
    !value.valid || (value.slot < entries.U &&
      value.generation(LoadAttemptIdentity.GenerationWidth - 1, 1) === 0.U)
  }
}

object LoadTerminalFault {
  val CauseWidth = 32
}

/** Generation-qualified rebinding of one still-resident LIQ row. */
class LoadAttemptRebind(val liqEntries: Int) extends Bundle {
  val loadId = new ROBID(liqEntries)
  val current = new LoadAttemptIdentity
  val next = new LoadAttemptIdentity
}
