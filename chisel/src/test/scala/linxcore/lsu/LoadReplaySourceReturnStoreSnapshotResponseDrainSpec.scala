package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotResponseDrainReference {
  final case class Result(
      active: Boolean,
      dequeueReady: Boolean,
      orderedConsumed: Boolean,
      staleDropped: Boolean,
      blockedByNoHead: Boolean,
      blockedByNoAction: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      invalidStaleWithOrdered: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      headValid: Boolean,
      orderedResponse: Boolean,
      headStale: Boolean): Result = {
    val active = enable && !flush
    val orderedConsumed = active && headValid && orderedResponse
    val staleDropped = active && headValid && !orderedResponse && headStale

    Result(
      active = active,
      dequeueReady = orderedConsumed || staleDropped,
      orderedConsumed = orderedConsumed,
      staleDropped = staleDropped,
      blockedByNoHead = active && !headValid,
      blockedByNoAction = active && headValid && !orderedResponse && !headStale,
      blockedByDisabled = !enable && headValid,
      blockedByFlush = enable && flush && headValid,
      invalidStaleWithOrdered = active && headValid && orderedResponse && headStale)
  }
}

class LoadReplaySourceReturnStoreSnapshotResponseDrainSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotResponseDrainReference._

  test("ordered response consumes the visible queue head") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseDrainReference(
      enable = true,
      flush = false,
      headValid = true,
      orderedResponse = true,
      headStale = false)

    assert(result.active)
    assert(result.dequeueReady)
    assert(result.orderedConsumed)
    assert(!result.staleDropped)
    assert(!result.blockedByNoAction)
  }

  test("explicit stale head drops only when ordered response is absent") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseDrainReference(
      enable = true,
      flush = false,
      headValid = true,
      orderedResponse = false,
      headStale = true)

    assert(result.dequeueReady)
    assert(!result.orderedConsumed)
    assert(result.staleDropped)
  }

  test("nonmatching live head holds until row state proves it stale") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseDrainReference(
      enable = true,
      flush = false,
      headValid = true,
      orderedResponse = false,
      headStale = false)

    assert(!result.dequeueReady)
    assert(!result.orderedConsumed)
    assert(!result.staleDropped)
    assert(result.blockedByNoAction)
  }

  test("active drain without a head reports no-head blocker") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseDrainReference(
      enable = true,
      flush = false,
      headValid = false,
      orderedResponse = false,
      headStale = false)

    assert(!result.dequeueReady)
    assert(result.blockedByNoHead)
  }

  test("disabled and flush suppress dequeue while preserving diagnostics") {
    val disabled = LoadReplaySourceReturnStoreSnapshotResponseDrainReference(
      enable = false,
      flush = false,
      headValid = true,
      orderedResponse = true,
      headStale = true)
    val flushing = LoadReplaySourceReturnStoreSnapshotResponseDrainReference(
      enable = true,
      flush = true,
      headValid = true,
      orderedResponse = true,
      headStale = true)

    assert(!disabled.dequeueReady)
    assert(disabled.blockedByDisabled)
    assert(!flushing.dequeueReady)
    assert(flushing.blockedByFlush)
  }

  test("ordered response wins over stale and flags inconsistent row evidence") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseDrainReference(
      enable = true,
      flush = false,
      headValid = true,
      orderedResponse = true,
      headStale = true)

    assert(result.dequeueReady)
    assert(result.orderedConsumed)
    assert(!result.staleDropped)
    assert(result.invalidStaleWithOrdered)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotResponseDrain elaborates consume/drop policy") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotResponseDrain)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotResponseDrain"))
    assert(sv.contains("io_dequeueReady"))
    assert(sv.contains("io_orderedConsumed"))
    assert(sv.contains("io_staleDropped"))
    assert(sv.contains("io_invalidStaleWithOrdered"))
  }
}
