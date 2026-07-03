package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnIexDataCandidateReference {
  final case class Entry(
      valid: Boolean = true,
      bid: Int = 1,
      gid: Int = 2,
      rid: Int = 3,
      loadLsId: Int = 4,
      pc: BigInt = 0x1000,
      addr: BigInt = 0x2000,
      size: Int = 8,
      data: BigInt = 0x12345678L,
      pipeIndex: Int = 0,
      specWakeup: Boolean = false,
      stackValid: Boolean = false)

  final case class Result(
      candidateValid: Boolean,
      wouldDrain: Boolean,
      setMemDataValid: Boolean,
      copiedBid: Int,
      copiedRid: Int,
      copiedData: BigInt,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoEntry: Boolean,
      blockedByInvalidEntry: Boolean,
      blockedByDrain: Boolean,
      blockedByRobMissing: Boolean,
      blockedByNeedFlush: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      sinkValid: Boolean,
      drainReady: Boolean,
      entry: Entry,
      robRowValid: Boolean,
      robRowNeedFlush: Boolean): Result = {
    val candidateValid = enable && !flush && sinkValid && entry.valid
    val wouldDrain = candidateValid && drainReady
    val setMemDataValid = wouldDrain && robRowValid && !robRowNeedFlush

    Result(
      candidateValid = candidateValid,
      wouldDrain = wouldDrain,
      setMemDataValid = setMemDataValid,
      copiedBid = if (setMemDataValid) entry.bid else 0,
      copiedRid = if (setMemDataValid) entry.rid else 0,
      copiedData = if (setMemDataValid) entry.data else 0,
      blockedByDisabled = !enable && sinkValid,
      blockedByFlush = enable && flush && sinkValid,
      blockedByNoEntry = enable && !flush && !sinkValid,
      blockedByInvalidEntry = enable && !flush && sinkValid && !entry.valid,
      blockedByDrain = candidateValid && !drainReady,
      blockedByRobMissing = wouldDrain && !robRowValid,
      blockedByNeedFlush = wouldDrain && robRowValid && robRowNeedFlush)
  }
}

class LoadReplayReturnIexDataCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnIexDataCandidateReference._

  test("admits a drained LRET entry when the ROB row exists and is not need-flush") {
    val result = LoadReplayReturnIexDataCandidateReference(
      enable = true,
      flush = false,
      sinkValid = true,
      drainReady = true,
      entry = Entry(bid = 5, rid = 7, data = 0xdeadbeefL),
      robRowValid = true,
      robRowNeedFlush = false)

    assert(result.candidateValid)
    assert(result.wouldDrain)
    assert(result.setMemDataValid)
    assert(result.copiedBid == 5)
    assert(result.copiedRid == 7)
    assert(result.copiedData == 0xdeadbeefL)
    assert(!result.blockedByRobMissing)
    assert(!result.blockedByNeedFlush)
  }

  test("blocks after a would-drain event when the ROB row image is absent") {
    val result = LoadReplayReturnIexDataCandidateReference(
      enable = true,
      flush = false,
      sinkValid = true,
      drainReady = true,
      entry = Entry(),
      robRowValid = false,
      robRowNeedFlush = false)

    assert(result.candidateValid)
    assert(result.wouldDrain)
    assert(!result.setMemDataValid)
    assert(result.blockedByRobMissing)
    assert(result.copiedData == 0)
  }

  test("skips need-flush ROB rows without publishing setMemData") {
    val result = LoadReplayReturnIexDataCandidateReference(
      enable = true,
      flush = false,
      sinkValid = true,
      drainReady = true,
      entry = Entry(),
      robRowValid = true,
      robRowNeedFlush = true)

    assert(result.wouldDrain)
    assert(!result.setMemDataValid)
    assert(result.blockedByNeedFlush)
  }

  test("reports pre-drain blockers without fabricating a ROB mutation") {
    val disabled = LoadReplayReturnIexDataCandidateReference(
      enable = false,
      flush = false,
      sinkValid = true,
      drainReady = true,
      entry = Entry(),
      robRowValid = true,
      robRowNeedFlush = false)
    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)
    assert(!disabled.setMemDataValid)

    val flushed = LoadReplayReturnIexDataCandidateReference(
      enable = true,
      flush = true,
      sinkValid = true,
      drainReady = true,
      entry = Entry(),
      robRowValid = true,
      robRowNeedFlush = false)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)

    val empty = LoadReplayReturnIexDataCandidateReference(
      enable = true,
      flush = false,
      sinkValid = false,
      drainReady = true,
      entry = Entry(),
      robRowValid = true,
      robRowNeedFlush = false)
    assert(empty.blockedByNoEntry)
    assert(!empty.candidateValid)

    val invalid = LoadReplayReturnIexDataCandidateReference(
      enable = true,
      flush = false,
      sinkValid = true,
      drainReady = true,
      entry = Entry(valid = false),
      robRowValid = true,
      robRowNeedFlush = false)
    assert(invalid.blockedByInvalidEntry)
    assert(!invalid.candidateValid)
  }

  test("holds the candidate when the IEX return pipe drain permit is closed") {
    val result = LoadReplayReturnIexDataCandidateReference(
      enable = true,
      flush = false,
      sinkValid = true,
      drainReady = false,
      entry = Entry(),
      robRowValid = true,
      robRowNeedFlush = false)

    assert(result.candidateValid)
    assert(!result.wouldDrain)
    assert(!result.setMemDataValid)
    assert(result.blockedByDrain)
  }

  test("Chisel LoadReplayReturnIexDataCandidate elaborates setMemData diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnIexDataCandidate(
      idEntries = 8,
      returnPipeCount = 2
    ))

    assert(sv.contains("module LoadReplayReturnIexDataCandidate"))
    assert(sv.contains("io_candidateValid"))
    assert(sv.contains("io_wouldDrain"))
    assert(sv.contains("io_setMemDataValid"))
    assert(sv.contains("io_memData"))
    assert(sv.contains("io_blockedByRobMissing"))
    assert(sv.contains("io_blockedByNeedFlush"))
  }
}
