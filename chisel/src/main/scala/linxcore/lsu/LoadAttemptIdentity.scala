package linxcore.lsu

import chisel3._

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

/** Generation-qualified rebinding of one still-resident LIQ row. */
class LoadAttemptRebind(val liqEntries: Int) extends Bundle {
  val loadId = new ROBID(liqEntries)
  val current = new LoadAttemptIdentity
  val next = new LoadAttemptIdentity
}
