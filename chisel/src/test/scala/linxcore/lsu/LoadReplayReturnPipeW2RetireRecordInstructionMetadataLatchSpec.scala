package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatchReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)
  final case class State(valid: Boolean = false, rid: Id = Id(valid = false), raw: BigInt = 0, len: Int = 0)
  final case class Result(
      state: State,
      captureIntent: Boolean,
      capturePayloadRidValid: Boolean,
      w2RidValid: Boolean,
      w2RidMatchesCapture: Boolean,
      w2MetadataReady: Boolean,
      captureFromW2: Boolean,
      captureFromDrain: Boolean,
      captureBlockedByNoPayloadRid: Boolean,
      captureBlockedByNoW2Rid: Boolean,
      captureBlockedByRidMismatch: Boolean,
      captureBlockedByNoW2Metadata: Boolean,
      clearAccepted: Boolean,
      providerValid: Boolean,
      providerRaw: BigInt,
      providerLen: Int)

  private def same(a: Id, b: Id): Boolean =
    a.valid && b.valid && a.wrap == b.wrap && a.value == b.value

  def step(
      state: State,
      enable: Boolean,
      flush: Boolean,
      captureAccepted: Boolean,
      capturePayloadRid: Id,
      w2Rid: Id,
      w2InstructionValid: Boolean,
      w2InstructionRaw: BigInt,
      w2InstructionLen: Int,
      drainInstructionCapture: Boolean,
      drainRid: Id,
      drainInstructionRaw: BigInt,
      drainInstructionLen: Int,
      recordFire: Boolean,
      recordFireRid: Id,
      recordValid: Boolean,
      recordRid: Id): Result = {
    val active = enable && !flush
    val captureIntent = active && captureAccepted
    val capturePayloadRidValid = capturePayloadRid.valid
    val w2RidValid = w2Rid.valid
    val w2RidMatchesCapture = same(capturePayloadRid, w2Rid)
    val w2MetadataReady = w2InstructionValid && w2InstructionLen != 0
    val captureFromW2 =
      captureIntent && w2RidMatchesCapture && w2MetadataReady
    val drainCaptureCandidate =
      active && drainInstructionCapture && drainRid.valid && drainInstructionLen != 0
    val captureFromDrain = !state.valid && !captureFromW2 && drainCaptureCandidate
    val captureBlockedByNoPayloadRid = captureIntent && !capturePayloadRidValid
    val captureBlockedByNoW2Rid = captureIntent && capturePayloadRidValid && !w2RidValid
    val captureBlockedByRidMismatch = captureIntent && capturePayloadRidValid && w2RidValid && !w2RidMatchesCapture
    val captureBlockedByNoW2Metadata = captureIntent && w2RidMatchesCapture && !w2MetadataReady
    val clearAccepted = recordFire && same(recordFireRid, state.rid) && state.valid
    val next =
      if (flush || !enable) State()
      else if (captureFromW2) State(valid = true, rid = capturePayloadRid, raw = w2InstructionRaw, len = w2InstructionLen)
      else if (captureFromDrain) State(valid = true, rid = drainRid, raw = drainInstructionRaw, len = drainInstructionLen)
      else if (clearAccepted) State()
      else state
    val providerValid = state.valid && same(recordRid, state.rid) && recordValid

    Result(
      next,
      captureIntent,
      capturePayloadRidValid,
      w2RidValid,
      w2RidMatchesCapture,
      w2MetadataReady,
      captureFromW2,
      captureFromDrain,
      captureBlockedByNoPayloadRid,
      captureBlockedByNoW2Rid,
      captureBlockedByRidMismatch,
      captureBlockedByNoW2Metadata,
      clearAccepted,
      providerValid,
      state.raw,
      state.len)
  }
}

class LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatchSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatchReference._

  test("captures W2 metadata before the later drain metadata fallback") {
    val empty = State()
    val rid = Id(value = 3)
    val captured = step(
      state = empty,
      enable = true,
      flush = false,
      captureAccepted = true,
      capturePayloadRid = rid,
      w2Rid = rid,
      w2InstructionValid = true,
      w2InstructionRaw = 0x1234,
      w2InstructionLen = 4,
      drainInstructionCapture = true,
      drainRid = rid,
      drainInstructionRaw = 0x5678,
      drainInstructionLen = 6,
      recordFire = false,
      recordFireRid = Id(valid = false),
      recordValid = false,
      recordRid = Id(valid = false))
    val observed = step(
      state = captured.state,
      enable = true,
      flush = false,
      captureAccepted = false,
      capturePayloadRid = Id(valid = false),
      w2Rid = Id(valid = false),
      w2InstructionValid = false,
      w2InstructionRaw = 0,
      w2InstructionLen = 0,
      drainInstructionCapture = false,
      drainRid = Id(valid = false),
      drainInstructionRaw = 0,
      drainInstructionLen = 0,
      recordFire = false,
      recordFireRid = Id(valid = false),
      recordValid = true,
      recordRid = rid)

    assert(captured.captureFromW2)
    assert(!captured.captureFromDrain)
    assert(captured.captureIntent)
    assert(captured.w2RidMatchesCapture)
    assert(captured.w2MetadataReady)
    assert(observed.providerValid)
    assert(observed.providerRaw == 0x1234)
    assert(observed.providerLen == 4)
  }

  test("reports capture blockers before falling back to drain metadata") {
    val result = step(
      state = State(),
      enable = true,
      flush = false,
      captureAccepted = true,
      capturePayloadRid = Id(value = 1),
      w2Rid = Id(value = 2),
      w2InstructionValid = false,
      w2InstructionRaw = 0,
      w2InstructionLen = 0,
      drainInstructionCapture = true,
      drainRid = Id(value = 1),
      drainInstructionRaw = 0x9999,
      drainInstructionLen = 4,
      recordFire = false,
      recordFireRid = Id(valid = false),
      recordValid = false,
      recordRid = Id(valid = false))

    assert(result.captureIntent)
    assert(result.captureBlockedByRidMismatch)
    assert(!result.captureBlockedByNoW2Metadata)
    assert(!result.captureFromW2)
    assert(result.captureFromDrain)
  }

  test("does not overwrite retained W2 metadata with later drain metadata") {
    val retainedRid = Id(value = 5)
    val otherRid = Id(value = 6)
    val state = State(valid = true, rid = retainedRid, raw = 0xaaaa, len = 4)
    val result = step(
      state = state,
      enable = true,
      flush = false,
      captureAccepted = false,
      capturePayloadRid = Id(valid = false),
      w2Rid = Id(valid = false),
      w2InstructionValid = false,
      w2InstructionRaw = 0,
      w2InstructionLen = 0,
      drainInstructionCapture = true,
      drainRid = otherRid,
      drainInstructionRaw = 0xbbbb,
      drainInstructionLen = 4,
      recordFire = false,
      recordFireRid = Id(valid = false),
      recordValid = true,
      recordRid = retainedRid)

    assert(!result.captureFromDrain)
    assert(result.providerValid)
    assert(result.providerRaw == 0xaaaa)
    assert(result.state.rid == retainedRid)
  }

  test("clears metadata when the retained record fires") {
    val rid = Id(value = 4)
    val state = State(valid = true, rid = rid, raw = 0xabcd, len = 6)
    val result = step(
      state = state,
      enable = true,
      flush = false,
      captureAccepted = false,
      capturePayloadRid = Id(valid = false),
      w2Rid = Id(valid = false),
      w2InstructionValid = false,
      w2InstructionRaw = 0,
      w2InstructionLen = 0,
      drainInstructionCapture = false,
      drainRid = Id(valid = false),
      drainInstructionRaw = 0,
      drainInstructionLen = 0,
      recordFire = true,
      recordFireRid = rid,
      recordValid = true,
      recordRid = rid)

    assert(result.clearAccepted)
    assert(!result.state.valid)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatch elaborates") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatch(idEntries = 8))

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatch"))
    assert(sv.contains("io_captureFromW2"))
    assert(sv.contains("io_captureBlockedByNoW2Metadata"))
    assert(sv.contains("io_providerValid"))
  }
}
