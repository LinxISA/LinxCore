package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class LoadReplayReturnPipeW2SlotSpec extends AnyFunSuite {
  import LoadReplayReturnPipeResidencySlotReference._

  test("captures an advanced scalar LDA payload into an empty W2 slot") {
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

  test("captures a vector AGU target and blocks a second W2 write while occupied") {
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

  test("flush and explicit W2 clear have priority over simultaneous writes") {
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

  test("same-cycle clear and write can replace a resident W2 payload when enabled") {
    val full = step(
      State(),
      enable = true,
      flush = false,
      clear = false,
      Write(valid = true, targetIsAgu = false, targetIsLda = true, data = 0x44))
    assert(full.next.occupied)

    val replaced = step(
      full.next,
      enable = true,
      flush = false,
      clear = true,
      Write(valid = true, targetIsAgu = true, targetIsLda = false, pipeIndex = 0, data = 0x99),
      replaceOnClear = true)
    assert(replaced.accepted)
    assert(!replaced.acceptedEmpty)
    assert(replaced.replacedOnClear)
    assert(!replaced.blockedByClear)
    assert(!replaced.blockedByReplaceDisabled)
    assert(replaced.next.occupied)
    assert(replaced.next.entry.targetIsAgu)
    assert(!replaced.next.entry.targetIsLda)
    assert(replaced.next.entry.data == 0x99)
  }

  test("replace-on-clear preserves flush priority and target validation") {
    val full = step(
      State(),
      enable = true,
      flush = false,
      clear = false,
      Write(valid = true, targetIsAgu = false, targetIsLda = true, data = 0x44))

    val flushed = step(
      full.next,
      enable = true,
      flush = true,
      clear = true,
      Write(valid = true, targetIsAgu = true, targetIsLda = false, data = 0x99),
      replaceOnClear = true)
    assert(!flushed.accepted)
    assert(!flushed.replacedOnClear)
    assert(flushed.blockedByFlush)
    assert(!flushed.next.occupied)

    val invalid = step(
      full.next,
      enable = true,
      flush = false,
      clear = true,
      Write(valid = true, targetIsAgu = true, targetIsLda = true, data = 0x99),
      replaceOnClear = true)
    assert(!invalid.accepted)
    assert(!invalid.replacedOnClear)
    assert(invalid.blockedByClear)
    assert(!invalid.next.occupied)
  }

  test("clear with replace disabled reports the future replacement blocker") {
    val full = step(
      State(),
      enable = true,
      flush = false,
      clear = false,
      Write(valid = true, targetIsAgu = false, targetIsLda = true, data = 0x44))

    val blocked = step(
      full.next,
      enable = true,
      flush = false,
      clear = true,
      Write(valid = true, targetIsAgu = true, targetIsLda = false, data = 0x99))
    assert(!blocked.accepted)
    assert(!blocked.replacedOnClear)
    assert(blocked.blockedByClear)
    assert(blocked.blockedByReplaceDisabled)
    assert(!blocked.next.occupied)
  }

  test("reports disabled no-write and invalid-target W2 blockers") {
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

  test("Chisel LoadReplayReturnPipeW2Slot elaborates W2 payload registers and blockers") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2Slot(returnPipeCount = 2))

    assert(sv.contains("module LoadReplayReturnPipeW2Slot"))
    assert(sv.contains("io_accepted"))
    assert(sv.contains("io_entryWakeupRequired"))
    assert(sv.contains("io_blockedByInvalidTarget"))
    assert(sv.contains("io_blockedByOccupied"))
    assert(sv.contains("io_replaceOnClear"))
    assert(sv.contains("io_replacedOnClear"))
  }
}
