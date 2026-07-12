package linxcore.recovery

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ScalarRedirectRecoverySourceReference {
  final case class State(pending: Boolean = false, published: Boolean = false, identityValid: Boolean = false)

  def sourceValid(state: State, cancel: Boolean = false): Boolean =
    state.pending && !state.published && state.identityValid && !cancel

  def step(
      state: State,
      eventValid: Boolean = false,
      eventIdentityValid: Boolean = false,
      sourceReady: Boolean = false,
      sourceResolved: Boolean = false,
      cancel: Boolean = false): State = {
    val eventReady = !state.pending || sourceResolved
    val eventAccepted = eventValid && eventReady && !cancel
    val sourceAccepted = sourceValid(state, cancel) && sourceReady
    if (cancel) State()
    else if (eventAccepted) State(pending = true, published = false, identityValid = eventIdentityValid)
    else if (sourceResolved) State()
    else if (sourceAccepted) state.copy(published = true)
    else state
  }
}

class ScalarRedirectRecoverySourceSpec extends AnyFunSuite {
  import ScalarRedirectRecoverySourceReference._

  test("redirect publishes once and retains residency until matched source resolution") {
    val captured = step(State(), eventValid = true, eventIdentityValid = true)
    assert(captured.pending)
    assert(!captured.published)

    val published = step(captured, sourceReady = true)
    assert(published.pending)
    assert(published.published)
    assert(step(published, sourceReady = true) == published)
    assert(step(published, sourceResolved = true) == State())
  }

  test("missing or inconsistent full BID blocks publication without losing residency") {
    val blocked = step(State(), eventValid = true, eventIdentityValid = false)
    assert(blocked.pending)
    assert(!blocked.identityValid)
    assert(step(blocked, sourceReady = true) == blocked)
    assert(step(blocked, cancel = true) == State())
  }

  test("consume and replace admits the next redirect atomically") {
    val prior = State(pending = true, published = true, identityValid = true)
    val next = step(
      prior,
      eventValid = true,
      eventIdentityValid = true,
      sourceResolved = true)
    assert(next == State(pending = true, published = false, identityValid = true))
  }

  test("private sidecars require matched payload ownership, not generic consumption") {
    val pending = State(pending = true, published = true, identityValid = true)
    def sidecarValid(payloadIntentConsumed: Boolean): Boolean =
      pending.pending && payloadIntentConsumed

    assert(!sidecarValid(payloadIntentConsumed = false))
    assert(sidecarValid(payloadIntentConsumed = true))
  }

  test("cancel dominates a simultaneous redirect event") {
    val prior = State(pending = true, published = true, identityValid = true)
    assert(step(
      prior,
      eventValid = true,
      eventIdentityValid = true,
      cancel = true) == State())
    val unpublished = prior.copy(published = false)
    assert(!sourceValid(unpublished, cancel = true))
  }

  test("scalar redirect recovery owner elaborates exact identity and sidecar ports") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarRedirectRecoverySource(
      entries = 8,
      bidWidth = 16,
      orderWidth = 12,
      lsidWidth = 40
    ))

    assert(sv.contains("module ScalarRedirectRecoverySource"))
    assert(sv.contains("io_event_blockBidValid"))
    assert(sv.contains("io_event_bid_wrap"))
    assert(sv.contains("io_source_blockBid"))
    assert(sv.contains("io_blockedByMissingIdentity"))
    assert(sv.contains("io_sourceResolved"))
    assert(sv.contains("io_payloadIntentConsumed"))
    assert(sv.contains("io_cleanupOrder"))
    assert(sv.contains("io_cleanupLsId_value"))
    assert(sv.contains("io_event_lsIdFull"))
    assert(sv.contains("io_source_lsIdFull"))
    assert(sv.contains("io_cleanupLsIdFull"))
  }

  test("scalar redirect recovery keeps full LSID independent of ROB identity width") {
    val io = new ScalarRedirectRecoverySourceIO(
      entries = 8,
      bidWidth = 16,
      orderWidth = 12,
      lsidWidth = 40)

    assert(io.event.lsId.value.getWidth == 3)
    assert(io.event.lsIdFull.getWidth == 40)
    assert(io.source.lsId.value.getWidth == 3)
    assert(io.source.lsIdFull.getWidth == 40)
    assert(io.cleanupLsIdFull.getWidth == 40)
  }
}
