package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnIexPipeInsertCandidateReference {
  final case class Source(valid: Boolean = false, reg: Int = 0, data: BigInt = 0)
  final case class Mem(
      bidValid: Boolean = true,
      gidValid: Boolean = true,
      ridValid: Boolean = true,
      loadLsIdValid: Boolean = true,
      bid: Int = 1,
      gid: Int = 2,
      rid: Int = 3,
      loadLsId: Int = 4,
      pc: BigInt = 0x1000,
      addr: BigInt = 0x2000,
      size: Int = 8,
      sourceTraceValid: Boolean = true,
      source0: Source = Source(valid = true, reg = 5, data = 0x1111),
      source1: Source = Source(valid = true, reg = 6, data = 0x2222),
      data: BigInt = 0x12345678L,
      loadToUsePipeIndex: Int = 0,
      specWakeup: Boolean = false,
      stackValid: Boolean = false)

  final case class Result(
      candidateValid: Boolean,
      insertValid: Boolean,
      insertIsLoadReturn: Boolean,
      insertPipeIndex: Int,
      insertLoadToUsePipeIndex: Int,
      copiedRid: Int,
      copiedSourceTraceValid: Boolean,
      copiedSource0: Source,
      copiedData: BigInt,
      wakeupRequired: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoSetMemData: Boolean,
      blockedByNoPipe: Boolean,
      blockedByInvalidRid: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      setMemDataValid: Boolean,
      pipeInsertReady: Boolean,
      pipeInsertIndex: Int,
      mem: Mem): Result = {
    val candidateValid = enable && !flush && setMemDataValid
    val insertValid = candidateValid && pipeInsertReady && mem.ridValid

    Result(
      candidateValid = candidateValid,
      insertValid = insertValid,
      insertIsLoadReturn = insertValid,
      insertPipeIndex = if (insertValid) pipeInsertIndex else 0,
      insertLoadToUsePipeIndex = if (insertValid) mem.loadToUsePipeIndex else 0,
      copiedRid = if (insertValid) mem.rid else 0,
      copiedSourceTraceValid = insertValid && mem.sourceTraceValid,
      copiedSource0 = if (insertValid) mem.source0 else Source(),
      copiedData = if (insertValid) mem.data else 0,
      wakeupRequired = insertValid && !mem.specWakeup && !mem.stackValid,
      blockedByDisabled = !enable && setMemDataValid,
      blockedByFlush = enable && flush && setMemDataValid,
      blockedByNoSetMemData = enable && !flush && !setMemDataValid,
      blockedByNoPipe = candidateValid && !pipeInsertReady,
      blockedByInvalidRid = candidateValid && pipeInsertReady && !mem.ridValid)
  }
}

class LoadReplayReturnIexPipeInsertCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnIexPipeInsertCandidateReference._

  test("forms an E4 insert candidate after setMemData admission and pipe readiness") {
    val result = LoadReplayReturnIexPipeInsertCandidateReference(
      enable = true,
      flush = false,
      setMemDataValid = true,
      pipeInsertReady = true,
      pipeInsertIndex = 1,
      mem = Mem(rid = 7, data = 0xdeadbeefL, loadToUsePipeIndex = 0))

    assert(result.candidateValid)
    assert(result.insertValid)
    assert(result.insertIsLoadReturn)
    assert(result.insertPipeIndex == 1)
    assert(result.insertLoadToUsePipeIndex == 0)
    assert(result.copiedRid == 7)
    assert(result.copiedSourceTraceValid)
    assert(result.copiedSource0.data == 0x1111)
    assert(result.copiedData == 0xdeadbeefL)
    assert(result.wakeupRequired)
  }

  test("suppresses wakeup-required for speculative or stack rows") {
    val speculative = LoadReplayReturnIexPipeInsertCandidateReference(
      enable = true,
      flush = false,
      setMemDataValid = true,
      pipeInsertReady = true,
      pipeInsertIndex = 0,
      mem = Mem(specWakeup = true))
    assert(speculative.insertValid)
    assert(!speculative.wakeupRequired)

    val stack = LoadReplayReturnIexPipeInsertCandidateReference(
      enable = true,
      flush = false,
      setMemDataValid = true,
      pipeInsertReady = true,
      pipeInsertIndex = 0,
      mem = Mem(stackValid = true))
    assert(stack.insertValid)
    assert(!stack.wakeupRequired)
  }

  test("reports disabled flush no-setMemData and pipe blockers") {
    val disabled = LoadReplayReturnIexPipeInsertCandidateReference(
      enable = false,
      flush = false,
      setMemDataValid = true,
      pipeInsertReady = true,
      pipeInsertIndex = 0,
      mem = Mem())
    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)

    val flushed = LoadReplayReturnIexPipeInsertCandidateReference(
      enable = true,
      flush = true,
      setMemDataValid = true,
      pipeInsertReady = true,
      pipeInsertIndex = 0,
      mem = Mem())
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)

    val noSetMemData = LoadReplayReturnIexPipeInsertCandidateReference(
      enable = true,
      flush = false,
      setMemDataValid = false,
      pipeInsertReady = true,
      pipeInsertIndex = 0,
      mem = Mem())
    assert(noSetMemData.blockedByNoSetMemData)
    assert(!noSetMemData.candidateValid)

    val noPipe = LoadReplayReturnIexPipeInsertCandidateReference(
      enable = true,
      flush = false,
      setMemDataValid = true,
      pipeInsertReady = false,
      pipeInsertIndex = 0,
      mem = Mem())
    assert(noPipe.candidateValid)
    assert(noPipe.blockedByNoPipe)
    assert(!noPipe.insertValid)
  }

  test("does not insert when the admitted memory payload lacks a valid RID") {
    val result = LoadReplayReturnIexPipeInsertCandidateReference(
      enable = true,
      flush = false,
      setMemDataValid = true,
      pipeInsertReady = true,
      pipeInsertIndex = 0,
      mem = Mem(ridValid = false, rid = 9, data = 0xbeef))

    assert(result.candidateValid)
    assert(!result.insertValid)
    assert(result.blockedByInvalidRid)
    assert(result.copiedRid == 0)
    assert(!result.copiedSourceTraceValid)
    assert(result.copiedData == 0)
  }

  test("Chisel LoadReplayReturnIexPipeInsertCandidate elaborates insert diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnIexPipeInsertCandidate(returnPipeCount = 2))
    assert(sv.contains("module LoadReplayReturnIexPipeInsertCandidate"))
    assert(sv.contains("io_insertSourceTraceValid"))
    assert(sv.contains("io_insertSource0_data"))
  }
}
