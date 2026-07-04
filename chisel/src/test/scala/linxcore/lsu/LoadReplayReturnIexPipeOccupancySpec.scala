package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnIexPipeOccupancyReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      allPipeMask: Int,
      maskedLivePipeOccupiedMask: Int,
      pipeOccupiedMask: Int,
      forcedFull: Boolean,
      anyPipeOccupied: Boolean,
      allPipesOccupied: Boolean,
      anyPipeFree: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByLiveDisabled: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveRequested: Boolean,
      livePipeOccupiedMask: Int,
      returnPipeCount: Int): Result = {
    require(returnPipeCount > 0)

    val allPipeMask = (1 << returnPipeCount) - 1
    val active = enable && !flush
    val requestActive = active && liveRequested
    val maskedLivePipeOccupiedMask = livePipeOccupiedMask & allPipeMask
    val pipeOccupiedMask = if (requestActive) maskedLivePipeOccupiedMask else allPipeMask

    Result(
      active = active,
      requestActive = requestActive,
      allPipeMask = allPipeMask,
      maskedLivePipeOccupiedMask = maskedLivePipeOccupiedMask,
      pipeOccupiedMask = pipeOccupiedMask,
      forcedFull = !requestActive,
      anyPipeOccupied = pipeOccupiedMask != 0,
      allPipesOccupied = pipeOccupiedMask == allPipeMask,
      anyPipeFree = (pipeOccupiedMask ^ allPipeMask) != 0,
      blockedByDisabled = !enable && liveRequested,
      blockedByFlush = enable && flush && liveRequested,
      blockedByLiveDisabled = active && !liveRequested)
  }
}

class LoadReplayReturnIexPipeOccupancySpec extends AnyFunSuite {
  import LoadReplayReturnIexPipeOccupancyReference._

  test("forces every return pipe occupied while live occupancy is disabled") {
    val result = LoadReplayReturnIexPipeOccupancyReference(
      enable = true,
      flush = false,
      liveRequested = false,
      livePipeOccupiedMask = 0x0,
      returnPipeCount = 4)

    assert(result.active)
    assert(!result.requestActive)
    assert(result.pipeOccupiedMask == 0xf)
    assert(result.forcedFull)
    assert(result.allPipesOccupied)
    assert(!result.anyPipeFree)
    assert(result.blockedByLiveDisabled)
  }

  test("passes through the masked live occupancy request when enabled") {
    val result = LoadReplayReturnIexPipeOccupancyReference(
      enable = true,
      flush = false,
      liveRequested = true,
      livePipeOccupiedMask = 0xa,
      returnPipeCount = 4)

    assert(result.requestActive)
    assert(result.maskedLivePipeOccupiedMask == 0xa)
    assert(result.pipeOccupiedMask == 0xa)
    assert(!result.forcedFull)
    assert(result.anyPipeOccupied)
    assert(!result.allPipesOccupied)
    assert(result.anyPipeFree)
  }

  test("masks high occupancy bits outside the configured return-pipe count") {
    val result = LoadReplayReturnIexPipeOccupancyReference(
      enable = true,
      flush = false,
      liveRequested = true,
      livePipeOccupiedMask = 0x8,
      returnPipeCount = 3)

    assert(result.allPipeMask == 0x7)
    assert(result.maskedLivePipeOccupiedMask == 0x0)
    assert(result.pipeOccupiedMask == 0x0)
    assert(result.anyPipeFree)
  }

  test("reports disabled and flush blockers while preserving a full mask") {
    val disabled = LoadReplayReturnIexPipeOccupancyReference(
      enable = false,
      flush = false,
      liveRequested = true,
      livePipeOccupiedMask = 0x0,
      returnPipeCount = 2)
    assert(disabled.blockedByDisabled)
    assert(disabled.pipeOccupiedMask == 0x3)
    assert(disabled.forcedFull)

    val flushed = LoadReplayReturnIexPipeOccupancyReference(
      enable = true,
      flush = true,
      liveRequested = true,
      livePipeOccupiedMask = 0x0,
      returnPipeCount = 2)
    assert(flushed.blockedByFlush)
    assert(flushed.pipeOccupiedMask == 0x3)
    assert(flushed.forcedFull)
  }

  test("Chisel LoadReplayReturnIexPipeOccupancy elaborates occupancy diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnIexPipeOccupancy(returnPipeCount = 4))

    assert(sv.contains("module LoadReplayReturnIexPipeOccupancy"))
    assert(sv.contains("io_pipeOccupiedMask"))
    assert(sv.contains("io_maskedLivePipeOccupiedMask"))
    assert(sv.contains("io_forcedFull"))
    assert(sv.contains("io_anyPipeFree"))
    assert(sv.contains("io_blockedByLiveDisabled"))
  }
}
