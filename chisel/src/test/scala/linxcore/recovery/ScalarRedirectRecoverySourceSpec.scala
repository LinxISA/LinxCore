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
      intentConsumed: Boolean = false,
      cancel: Boolean = false): State = {
    val eventReady = !state.pending || intentConsumed
    val eventAccepted = eventValid && eventReady && !cancel
    val sourceAccepted = sourceValid(state, cancel) && sourceReady
    if (cancel) State()
    else if (eventAccepted) State(pending = true, published = false, identityValid = eventIdentityValid)
    else if (intentConsumed) State()
    else if (sourceAccepted) state.copy(published = true)
    else state
  }
}

class ScalarRedirectRecoverySourceSpec extends AnyFunSuite {
  import ScalarRedirectRecoverySourceReference._

  test("redirect publishes once and retains sidecars until cleanup consumption") {
    val captured = step(State(), eventValid = true, eventIdentityValid = true)
    assert(captured.pending)
    assert(!captured.published)

    val published = step(captured, sourceReady = true)
    assert(published.pending)
    assert(published.published)
    assert(step(published, sourceReady = true) == published)
    assert(step(published, intentConsumed = true) == State())
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
      intentConsumed = true)
    assert(next == State(pending = true, published = false, identityValid = true))
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
      orderWidth = 12
    ))

    assert(sv.contains("module ScalarRedirectRecoverySource"))
    assert(sv.contains("io_event_blockBidValid"))
    assert(sv.contains("io_event_bid_wrap"))
    assert(sv.contains("io_source_blockBid"))
    assert(sv.contains("io_blockedByMissingIdentity"))
    assert(sv.contains("io_cleanupOrder"))
    assert(sv.contains("io_cleanupLsId_value"))
  }
}
