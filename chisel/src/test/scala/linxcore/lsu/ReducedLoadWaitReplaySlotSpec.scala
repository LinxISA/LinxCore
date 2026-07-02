package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ReducedLoadWaitReplaySlotReference {
  final case class Id(valid: Boolean = false, wrap: Boolean = false, value: Int = 0)
  final case class Wait(
      valid: Boolean = false,
      index: Int = 0,
      storeId: Id = Id(),
      storeLsId: Id = Id(),
      pc: BigInt = 0)
  final case class Capture(pc: BigInt, addr: BigInt, size: Int, bid: Id, lsId: Id, waitKey: Wait)
  final case class Wake(storeId: Id, storeLsId: Id, pc: BigInt)
  final case class State(
      active: Boolean = false,
      pc: BigInt = 0,
      addr: BigInt = 0,
      size: Int = 0,
      waitKey: Wait = Wait())
  final case class Result(state: State, captureAccepted: Boolean, waitStoreClear: Boolean)

  def step(
      state: State,
      flush: Boolean = false,
      capture: Option[Capture] = None,
      wake: Option[Wake] = None): Result = {
    val captureAccepted = !flush && capture.exists(_.waitKey.valid)
    val waitStoreClear = state.active && wake.exists { event =>
      state.waitKey.valid &&
        state.waitKey.storeId == event.storeId &&
        state.waitKey.storeLsId == event.storeLsId &&
        state.waitKey.pc == event.pc
    }
    val next =
      if (flush) {
        State()
      } else if (captureAccepted) {
        val captured = capture.get
        State(
          active = true,
          pc = captured.pc,
          addr = captured.addr,
          size = captured.size,
          waitKey = captured.waitKey)
      } else if (waitStoreClear) {
        State()
      } else {
        state
      }

    Result(next, captureAccepted, waitStoreClear)
  }
}

class ReducedLoadWaitReplaySlotSpec extends AnyFunSuite {
  import ReducedLoadWaitReplaySlotReference._

  private def id(value: Int, wrap: Boolean = false): Id =
    Id(valid = true, wrap = wrap, value = value)

  private def wait(pc: BigInt, storeId: Id, storeLsId: Id = Id()): Wait =
    Wait(valid = true, index = storeId.value, storeId = storeId, storeLsId = storeLsId, pc = pc)

  test("capture holds a wait-store identity until a matching replay wake clears it") {
    val storeId = id(4)
    val captured = Capture(
      pc = 0x4000,
      addr = 0x1008,
      size = 8,
      bid = id(6),
      lsId = id(3),
      waitKey = wait(pc = 0x3450, storeId = storeId))

    val afterCapture = step(State(), capture = Some(captured))
    assert(afterCapture.captureAccepted)
    assert(afterCapture.state.active)
    assert(afterCapture.state.waitKey.pc == 0x3450)

    val afterWake =
      step(afterCapture.state, wake = Some(Wake(storeId = storeId, storeLsId = Id(), pc = 0x3450)))
    assert(afterWake.waitStoreClear)
    assert(!afterWake.state.active)
  }

  test("mismatched replay wake leaves the registered wait slot active") {
    val storeId = id(2)
    val state = step(
      State(),
      capture = Some(Capture(0x4100, 0x1080, 8, id(6), id(1), wait(pc = 0x3570, storeId = storeId)))).state

    val afterWrongPc =
      step(state, wake = Some(Wake(storeId = storeId, storeLsId = Id(), pc = 0x3574)))
    assert(!afterWrongPc.waitStoreClear)
    assert(afterWrongPc.state.active)
    assert(afterWrongPc.state.waitKey.pc == 0x3570)
  }

  test("a later capture overwrites the previous wait key") {
    val firstStore = id(1)
    val secondStore = id(5)
    val first = step(
      State(),
      capture = Some(Capture(0x4000, 0x1000, 8, id(6), id(0), wait(pc = 0x3000, storeId = firstStore)))).state
    val second = step(
      first,
      capture = Some(Capture(0x4010, 0x2000, 8, id(7), id(2), wait(pc = 0x3010, storeId = secondStore)))).state

    val oldWake = step(second, wake = Some(Wake(storeId = firstStore, storeLsId = Id(), pc = 0x3000)))
    assert(!oldWake.waitStoreClear)
    assert(oldWake.state.active)

    val newWake = step(oldWake.state, wake = Some(Wake(storeId = secondStore, storeLsId = Id(), pc = 0x3010)))
    assert(newWake.waitStoreClear)
    assert(!newWake.state.active)
  }

  test("flush clears the registered wait slot") {
    val active = step(
      State(),
      capture = Some(Capture(0x4100, 0x1080, 8, id(6), id(1), wait(pc = 0x3570, storeId = id(2))))).state

    val flushed = step(active, flush = true)
    assert(!flushed.captureAccepted)
    assert(!flushed.state.active)
  }

  test("Chisel ReducedLoadWaitReplaySlot elaborates through LoadReplayWakeup") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedLoadWaitReplaySlot(idEntries = 8, storeEntries = 8))

    assert(sv.contains("module ReducedLoadWaitReplaySlot"))
    assert(sv.contains("module LoadReplayWakeup"))
    assert(sv.contains("io_captureAccepted"))
    assert(sv.contains("io_waitStoreClear"))
    assert(sv.contains("io_storedWaitStore_valid"))
  }
}
