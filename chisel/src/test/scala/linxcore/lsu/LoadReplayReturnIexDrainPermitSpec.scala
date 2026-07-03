package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnIexDrainPermitReference {
  final case class Result(
      pipeFreeMask: Int,
      anyPipeFree: Boolean,
      selectedPipeIndex: Int,
      drainReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoEntry: Boolean,
      blockedByPipeFull: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      sinkValid: Boolean,
      pipeOccupiedMask: Int,
      returnPipeCount: Int): Result = {
    require(returnPipeCount > 0)
    val allMask = (1 << returnPipeCount) - 1
    val pipeFreeMask = (~pipeOccupiedMask) & allMask
    val anyPipeFree = pipeFreeMask != 0
    val candidateValid = enable && !flush && sinkValid
    val drainReady = candidateValid && anyPipeFree
    val selectedPipeIndex =
      if (drainReady) {
        (0 until returnPipeCount).find(idx => ((pipeFreeMask >> idx) & 1) != 0).getOrElse(0)
      } else {
        0
      }

    Result(
      pipeFreeMask = pipeFreeMask,
      anyPipeFree = anyPipeFree,
      selectedPipeIndex = selectedPipeIndex,
      drainReady = drainReady,
      blockedByDisabled = !enable && sinkValid,
      blockedByFlush = enable && flush && sinkValid,
      blockedByNoEntry = enable && !flush && !sinkValid,
      blockedByPipeFull = candidateValid && !anyPipeFree)
  }
}

class LoadReplayReturnIexDrainPermitSpec extends AnyFunSuite {
  import LoadReplayReturnIexDrainPermitReference._

  test("permits drain when the sink has an entry and any IEX return pipe is free") {
    val result = LoadReplayReturnIexDrainPermitReference(
      enable = true,
      flush = false,
      sinkValid = true,
      pipeOccupiedMask = 0x5,
      returnPipeCount = 4)

    assert(result.pipeFreeMask == 0xa)
    assert(result.anyPipeFree)
    assert(result.selectedPipeIndex == 1)
    assert(result.drainReady)
    assert(!result.blockedByPipeFull)
  }

  test("reports full IEX return pipes after a resident sink entry") {
    val result = LoadReplayReturnIexDrainPermitReference(
      enable = true,
      flush = false,
      sinkValid = true,
      pipeOccupiedMask = 0x3,
      returnPipeCount = 2)

    assert(result.pipeFreeMask == 0)
    assert(!result.anyPipeFree)
    assert(!result.drainReady)
    assert(result.blockedByPipeFull)
  }

  test("orders disabled, flush, and empty-entry blockers") {
    val disabled = LoadReplayReturnIexDrainPermitReference(
      enable = false,
      flush = false,
      sinkValid = true,
      pipeOccupiedMask = 0,
      returnPipeCount = 1)
    assert(disabled.blockedByDisabled)
    assert(!disabled.drainReady)

    val flushed = LoadReplayReturnIexDrainPermitReference(
      enable = true,
      flush = true,
      sinkValid = true,
      pipeOccupiedMask = 0,
      returnPipeCount = 1)
    assert(flushed.blockedByFlush)
    assert(!flushed.drainReady)

    val empty = LoadReplayReturnIexDrainPermitReference(
      enable = true,
      flush = false,
      sinkValid = false,
      pipeOccupiedMask = 0,
      returnPipeCount = 1)
    assert(empty.blockedByNoEntry)
    assert(!empty.drainReady)
  }

  test("masks occupied bits outside the configured return-pipe count") {
    val result = LoadReplayReturnIexDrainPermitReference(
      enable = true,
      flush = false,
      sinkValid = true,
      pipeOccupiedMask = 0x8,
      returnPipeCount = 3)

    assert(result.pipeFreeMask == 0x7)
    assert(result.selectedPipeIndex == 0)
    assert(result.drainReady)
  }

  test("Chisel LoadReplayReturnIexDrainPermit elaborates drain diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnIexDrainPermit(returnPipeCount = 4))

    assert(sv.contains("module LoadReplayReturnIexDrainPermit"))
    assert(sv.contains("io_pipeFreeMask"))
    assert(sv.contains("io_anyPipeFree"))
    assert(sv.contains("io_selectedPipeIndex"))
    assert(sv.contains("io_drainReady"))
    assert(sv.contains("io_blockedByPipeFull"))
  }
}
