package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ReducedLoadWaitReplaySlotReference {
  final case class Id(valid: Boolean = false, wrap: Boolean = false, value: Int = 0)
  final case class Dst(
      valid: Boolean = false,
      kind: Int = 0,
      archTag: Int = 0,
      relTag: Int = 0,
      physTag: Int = 0,
      oldPhysTag: Int = 0)
  final case class Source(valid: Boolean = false, reg: Int = 0, data: BigInt = 0)
  final case class Wait(
      valid: Boolean = false,
      index: Int = 0,
      storeId: Id = Id(),
      storeLsId: Id = Id(),
      pc: BigInt = 0)
  final case class Capture(
      pc: BigInt,
      addr: BigInt,
      size: Int,
      bid: Id,
      lsId: Id,
      waitKey: Wait,
      gid: Id = Id(),
      rid: Id = Id(),
      youngestStoreId: Option[Id] = None,
      youngestStoreLsId: Option[Id] = None,
      returnSignExtend: Boolean = false,
      dst: Dst = Dst(),
      sourceTraceValid: Boolean = false,
      source0: Source = Source(),
      source1: Source = Source())
  final case class Wake(storeId: Id, storeLsId: Id, pc: BigInt)
  final case class Relaunch(
      pc: BigInt,
      addr: BigInt,
      size: Int,
      bid: Id,
      lsId: Id,
      dst: Dst = Dst(),
      gid: Id = Id(),
      rid: Id = Id(),
      youngestStoreId: Id = Id(),
      youngestStoreLsId: Id = Id(),
      returnSignExtend: Boolean = false,
      sourceTraceValid: Boolean = false,
      source0: Source = Source(),
      source1: Source = Source())
  final case class State(
      active: Boolean = false,
      pc: BigInt = 0,
      addr: BigInt = 0,
      size: Int = 0,
      returnSignExtend: Boolean = false,
      bid: Id = Id(),
      lsId: Id = Id(),
      gid: Id = Id(),
      rid: Id = Id(),
      dst: Dst = Dst(),
      sourceTraceValid: Boolean = false,
      source0: Source = Source(),
      source1: Source = Source(),
      youngestStoreId: Id = Id(),
      youngestStoreLsId: Id = Id(),
      waitKey: Wait = Wait())
  final case class Result(
      state: State,
      captureAccepted: Boolean,
      waitStoreClear: Boolean,
      relaunch: Option[Relaunch])

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
    val relaunch =
      if (waitStoreClear && !captureAccepted && !flush) {
        Some(Relaunch(
          pc = state.pc,
          addr = state.addr,
          size = state.size,
          bid = state.bid,
          lsId = state.lsId,
          dst = state.dst,
          gid = state.gid,
          rid = state.rid,
          youngestStoreId = state.youngestStoreId,
          youngestStoreLsId = state.youngestStoreLsId,
          returnSignExtend = state.returnSignExtend,
          sourceTraceValid = state.sourceTraceValid,
          source0 = state.source0,
          source1 = state.source1))
      } else {
        None
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
          returnSignExtend = captured.returnSignExtend,
          bid = captured.bid,
          lsId = captured.lsId,
          gid = captured.gid,
          rid = captured.rid,
          dst = captured.dst,
          sourceTraceValid = captured.sourceTraceValid,
          source0 = captured.source0,
          source1 = captured.source1,
          youngestStoreId = captured.youngestStoreId.getOrElse(captured.bid),
          youngestStoreLsId = captured.youngestStoreLsId.getOrElse(captured.lsId),
          waitKey = captured.waitKey)
      } else if (waitStoreClear) {
        State()
      } else {
        state
      }

    Result(next, captureAccepted, waitStoreClear, relaunch)
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
      returnSignExtend = true,
      bid = id(6),
      lsId = id(3),
      gid = id(2),
      rid = id(7),
      youngestStoreId = Some(id(5)),
      youngestStoreLsId = Some(id(4)),
      dst = Dst(valid = true, kind = 1, archTag = 10, relTag = 10, physTag = 42, oldPhysTag = 10),
      sourceTraceValid = true,
      source0 = Source(valid = true, reg = 2, data = 0x1122),
      source1 = Source(valid = true, reg = 3, data = 0x3344),
      waitKey = wait(pc = 0x3450, storeId = storeId))

    val afterCapture = step(State(), capture = Some(captured))
    assert(afterCapture.captureAccepted)
    assert(afterCapture.state.active)
    assert(afterCapture.state.waitKey.pc == 0x3450)

    val afterWake =
      step(afterCapture.state, wake = Some(Wake(storeId = storeId, storeLsId = Id(), pc = 0x3450)))
    assert(afterWake.waitStoreClear)
    assert(afterWake.relaunch.contains(Relaunch(
      pc = 0x4000,
      addr = 0x1008,
      size = 8,
      returnSignExtend = true,
      bid = id(6),
      lsId = id(3),
      dst = Dst(valid = true, kind = 1, archTag = 10, relTag = 10, physTag = 42, oldPhysTag = 10),
      gid = id(2),
      rid = id(7),
      youngestStoreId = id(5),
      youngestStoreLsId = id(4),
      sourceTraceValid = true,
      source0 = Source(valid = true, reg = 2, data = 0x1122),
      source1 = Source(valid = true, reg = 3, data = 0x3344))))
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
    assert(afterWrongPc.relaunch.isEmpty)
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
    assert(oldWake.relaunch.isEmpty)
    assert(oldWake.state.active)

    val newWake = step(oldWake.state, wake = Some(Wake(storeId = secondStore, storeLsId = Id(), pc = 0x3010)))
    assert(newWake.waitStoreClear)
    assert(newWake.relaunch.contains(Relaunch(
      pc = 0x4010,
      addr = 0x2000,
      size = 8,
      returnSignExtend = false,
      bid = id(7),
      lsId = id(2),
      youngestStoreId = id(7),
      youngestStoreLsId = id(2))))
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
    assert(sv.contains("io_relaunch_valid"))
    assert(sv.contains("io_captureReturnSignExtend"))
    assert(sv.contains("io_captureDst_physTag"))
    assert(sv.contains("io_captureSourceTraceValid"))
    assert(sv.contains("io_captureSource0_data"))
    assert(sv.contains("io_relaunch_returnSignExtend"))
    assert(sv.contains("io_relaunch_dst_physTag"))
    assert(sv.contains("io_relaunch_sourceTraceValid"))
    assert(sv.contains("io_relaunch_source1_data"))
    assert(sv.contains("io_relaunch_gid_value"))
    assert(sv.contains("io_relaunch_rid_value"))
    assert(sv.contains("io_relaunch_loadLsId_value"))
    assert(sv.contains("io_relaunch_youngestStoreId_value"))
    assert(sv.contains("io_relaunch_youngestStoreLsId_value"))
  }

  test("wait slot preserves full selected-store authority at 40-bit LSID width") {
    val io = new ReducedLoadWaitReplaySlotIO(
      idEntries = 8,
      storeEntries = 8,
      lsidWidth = 40)
    val sv = ChiselStage.emitSystemVerilog(new ReducedLoadWaitReplaySlot(
      idEntries = 8,
      storeEntries = 8,
      lsidWidth = 40))

    assert(io.captureWaitStore.storeLsIdFull.getWidth == 40)
    assert(io.storedWaitStore.storeLsIdFull.getWidth == 40)
    assert(io.replayWake.storeLsIdFull.getWidth == 40)
    assert(sv.contains("io_storedWaitStore_storeLsIdFull"))
    assert(sv.contains("io_replayWake_storeLsIdFull"))
  }
}
