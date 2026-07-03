package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeResidencySlotReference {
  final case class RobId(valid: Boolean = false, wrap: Boolean = false, value: Int = 0)
  final case class Destination(
      valid: Boolean = false,
      kind: String = "None",
      archTag: Int = 0,
      relTag: Int = 0,
      physTag: Int = 0,
      oldPhysTag: Int = 0)

  final case class Entry(
      targetIsAgu: Boolean = false,
      targetIsLda: Boolean = false,
      pipeIndex: Int = 0,
      loadToUsePipeIndex: Int = 0,
      bid: RobId = RobId(),
      gid: RobId = RobId(),
      rid: RobId = RobId(),
      loadLsId: RobId = RobId(),
      pc: BigInt = 0,
      addr: BigInt = 0,
      size: Int = 0,
      dst: Destination = Destination(),
      data: BigInt = 0,
      wakeupRequired: Boolean = false)

  final case class State(occupied: Boolean = false, entry: Entry = Entry())

  final case class Result(
      accepted: Boolean,
      next: State,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByClear: Boolean,
      blockedByNoWrite: Boolean,
      blockedByInvalidTarget: Boolean,
      blockedByOccupied: Boolean)

  final case class Write(
      valid: Boolean,
      targetIsAgu: Boolean,
      targetIsLda: Boolean,
      pipeIndex: Int = 0,
      loadToUsePipeIndex: Int = 0,
      bid: RobId = RobId(valid = true, value = 1),
      gid: RobId = RobId(valid = true, value = 2),
      rid: RobId = RobId(valid = true, value = 3),
      loadLsId: RobId = RobId(valid = true, value = 4),
      pc: BigInt = 0x80,
      addr: BigInt = 0x1000,
      size: Int = 8,
      dst: Destination = Destination(valid = true, kind = "Gpr", archTag = 5, relTag = 6, physTag = 7, oldPhysTag = 8),
      data: BigInt = 0xfeed,
      wakeupRequired: Boolean = true)

  def step(
      state: State,
      enable: Boolean,
      flush: Boolean,
      clear: Boolean,
      write: Write): Result = {
    val targetValid = write.targetIsAgu ^ write.targetIsLda
    val accepted = enable && !flush && !clear && write.valid && targetValid && !state.occupied
    val next =
      if (flush || clear) {
        State()
      } else if (accepted) {
        State(
          occupied = true,
          Entry(
            targetIsAgu = write.targetIsAgu,
            targetIsLda = write.targetIsLda,
            pipeIndex = write.pipeIndex,
            loadToUsePipeIndex = write.loadToUsePipeIndex,
            bid = write.bid,
            gid = write.gid,
            rid = write.rid,
            loadLsId = write.loadLsId,
            pc = write.pc,
            addr = write.addr,
            size = write.size,
            dst = write.dst,
            data = write.data,
            wakeupRequired = write.wakeupRequired))
      } else {
        state
      }

    Result(
      accepted = accepted,
      next = next,
      blockedByDisabled = !enable && write.valid,
      blockedByFlush = enable && flush && write.valid,
      blockedByClear = enable && !flush && clear && write.valid,
      blockedByNoWrite = enable && !flush && !clear && !write.valid,
      blockedByInvalidTarget = enable && !flush && !clear && write.valid && !targetValid,
      blockedByOccupied = enable && !flush && !clear && write.valid && targetValid && state.occupied)
  }
}

class LoadReplayReturnPipeResidencySlotSpec extends AnyFunSuite {
  import LoadReplayReturnPipeResidencySlotReference._

  test("captures a scalar LDA load-return payload into an empty slot") {
    val write = Write(valid = true, targetIsAgu = false, targetIsLda = true, pipeIndex = 1, loadToUsePipeIndex = 0)
    val result = step(State(), enable = true, flush = false, clear = false, write)

    assert(result.accepted)
    assert(result.next.occupied)
    assert(result.next.entry.targetIsLda)
    assert(!result.next.entry.targetIsAgu)
    assert(result.next.entry.pipeIndex == 1)
    assert(result.next.entry.loadToUsePipeIndex == 0)
    assert(result.next.entry.rid.valid)
    assert(result.next.entry.rid.value == 3)
    assert(result.next.entry.dst.valid)
    assert(result.next.entry.dst.kind == "Gpr")
    assert(result.next.entry.data == 0xfeed)
    assert(result.next.entry.wakeupRequired)
  }

  test("captures a vector AGU target and blocks a second write while occupied") {
    val first = step(
      State(),
      enable = true,
      flush = false,
      clear = false,
      Write(valid = true, targetIsAgu = true, targetIsLda = false, pipeIndex = 0, data = 0x44))
    assert(first.accepted)
    assert(first.next.entry.targetIsAgu)
    assert(!first.next.entry.targetIsLda)

    val second = step(
      first.next,
      enable = true,
      flush = false,
      clear = false,
      Write(valid = true, targetIsAgu = false, targetIsLda = true, data = 0x55))
    assert(!second.accepted)
    assert(second.blockedByOccupied)
    assert(second.next.entry.data == 0x44)
  }

  test("flush and explicit clear have priority over simultaneous writes") {
    val full = step(
      State(),
      enable = true,
      flush = false,
      clear = false,
      Write(valid = true, targetIsAgu = false, targetIsLda = true))
    assert(full.next.occupied)

    val flushed = step(
      full.next,
      enable = true,
      flush = true,
      clear = false,
      Write(valid = true, targetIsAgu = true, targetIsLda = false))
    assert(!flushed.accepted)
    assert(flushed.blockedByFlush)
    assert(!flushed.next.occupied)

    val cleared = step(
      full.next,
      enable = true,
      flush = false,
      clear = true,
      Write(valid = true, targetIsAgu = true, targetIsLda = false))
    assert(!cleared.accepted)
    assert(cleared.blockedByClear)
    assert(!cleared.next.occupied)
  }

  test("reports disabled no-write and invalid-target blockers") {
    val disabled = step(
      State(),
      enable = false,
      flush = false,
      clear = false,
      Write(valid = true, targetIsAgu = false, targetIsLda = true))
    assert(disabled.blockedByDisabled)
    assert(!disabled.accepted)

    val noWrite = step(
      State(),
      enable = true,
      flush = false,
      clear = false,
      Write(valid = false, targetIsAgu = false, targetIsLda = true))
    assert(noWrite.blockedByNoWrite)
    assert(!noWrite.accepted)

    val noTarget = step(
      State(),
      enable = true,
      flush = false,
      clear = false,
      Write(valid = true, targetIsAgu = false, targetIsLda = false))
    assert(noTarget.blockedByInvalidTarget)
    assert(!noTarget.accepted)

    val twoTargets = step(
      State(),
      enable = true,
      flush = false,
      clear = false,
      Write(valid = true, targetIsAgu = true, targetIsLda = true))
    assert(twoTargets.blockedByInvalidTarget)
    assert(!twoTargets.accepted)
  }

  test("Chisel LoadReplayReturnPipeResidencySlot elaborates payload registers and blockers") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeResidencySlot(returnPipeCount = 2))

    assert(sv.contains("module LoadReplayReturnPipeResidencySlot"))
    assert(sv.contains("io_accepted"))
    assert(sv.contains("io_entryWakeupRequired"))
    assert(sv.contains("io_blockedByInvalidTarget"))
    assert(sv.contains("io_blockedByOccupied"))
  }
}
