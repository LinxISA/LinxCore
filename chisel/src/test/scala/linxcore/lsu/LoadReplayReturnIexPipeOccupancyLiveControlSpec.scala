package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnIexPipeOccupancyLiveControlReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      occupancyEvidenceValid: Boolean,
      allPipeMask: Int,
      maskedLivePipeOccupiedMask: Int,
      liveRequested: Boolean,
      livePipeOccupiedMask: Int,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByRequestDisabled: Boolean,
      blockedByNoSource: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      requestEnable: Boolean,
      sourceValid: Boolean,
      livePipeOccupiedMaskIn: Int,
      returnPipeCount: Int): Result = {
    require(returnPipeCount > 0)

    val allPipeMask = (1 << returnPipeCount) - 1
    val active = enable && !flush
    val requestActive = active && requestEnable
    val maskedLivePipeOccupiedMask = livePipeOccupiedMaskIn & allPipeMask
    val liveRequested = requestActive && sourceValid

    Result(
      active = active,
      requestActive = requestActive,
      occupancyEvidenceValid = active && sourceValid,
      allPipeMask = allPipeMask,
      maskedLivePipeOccupiedMask = maskedLivePipeOccupiedMask,
      liveRequested = liveRequested,
      livePipeOccupiedMask = if (liveRequested) maskedLivePipeOccupiedMask else 0,
      blockedByDisabled = !enable && (requestEnable || sourceValid),
      blockedByFlush = enable && flush && (requestEnable || sourceValid),
      blockedByRequestDisabled = active && !requestEnable && sourceValid,
      blockedByNoSource = requestActive && !sourceValid)
  }
}

class LoadReplayReturnIexPipeOccupancyLiveControlSpec extends AnyFunSuite {
  import LoadReplayReturnIexPipeOccupancyLiveControlReference._

  test("keeps live occupancy disabled when the top request is false") {
    val result = LoadReplayReturnIexPipeOccupancyLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = false,
      sourceValid = true,
      livePipeOccupiedMaskIn = 0xa,
      returnPipeCount = 4)

    assert(result.active)
    assert(!result.requestActive)
    assert(result.occupancyEvidenceValid)
    assert(!result.liveRequested)
    assert(result.livePipeOccupiedMask == 0x0)
    assert(result.maskedLivePipeOccupiedMask == 0xa)
    assert(result.blockedByRequestDisabled)
  }

  test("requests live occupancy only with an active request and source evidence") {
    val result = LoadReplayReturnIexPipeOccupancyLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      sourceValid = true,
      livePipeOccupiedMaskIn = 0xb,
      returnPipeCount = 3)

    assert(result.requestActive)
    assert(result.occupancyEvidenceValid)
    assert(result.allPipeMask == 0x7)
    assert(result.maskedLivePipeOccupiedMask == 0x3)
    assert(result.liveRequested)
    assert(result.livePipeOccupiedMask == 0x3)
  }

  test("requires a source before issuing the occupancy request") {
    val result = LoadReplayReturnIexPipeOccupancyLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      sourceValid = false,
      livePipeOccupiedMaskIn = 0x1,
      returnPipeCount = 1)

    assert(result.requestActive)
    assert(!result.occupancyEvidenceValid)
    assert(!result.liveRequested)
    assert(result.livePipeOccupiedMask == 0)
    assert(result.blockedByNoSource)
  }

  test("reports disabled and flush blockers without issuing live requests") {
    val disabled = LoadReplayReturnIexPipeOccupancyLiveControlReference(
      enable = false,
      flush = false,
      requestEnable = true,
      sourceValid = true,
      livePipeOccupiedMaskIn = 0x1,
      returnPipeCount = 1)
    assert(disabled.blockedByDisabled)
    assert(!disabled.active)
    assert(!disabled.liveRequested)

    val flushed = LoadReplayReturnIexPipeOccupancyLiveControlReference(
      enable = true,
      flush = true,
      requestEnable = true,
      sourceValid = true,
      livePipeOccupiedMaskIn = 0x1,
      returnPipeCount = 1)
    assert(flushed.blockedByFlush)
    assert(!flushed.active)
    assert(!flushed.liveRequested)
  }

  test("Chisel LoadReplayReturnIexPipeOccupancyLiveControl elaborates request diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnIexPipeOccupancyLiveControl(returnPipeCount = 4))

    assert(sv.contains("module LoadReplayReturnIexPipeOccupancyLiveControl"))
    assert(sv.contains("io_occupancyEvidenceValid"))
    assert(sv.contains("io_liveRequested"))
    assert(sv.contains("io_livePipeOccupiedMask"))
    assert(sv.contains("io_blockedByNoSource"))
  }
}
