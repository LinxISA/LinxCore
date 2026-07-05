package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class ReducedStoreWaitReplayChiselPathSpec extends AnyFunSuite {
  import ReducedLoadWaitReplaySlotReference.{Capture, Id => SlotId, Wake}
  import ReducedStoreResidentForwardReference.{Id => ForwardId, Row => ForwardRow}
  import ResidentStoreReplayWakeupReference.{Row => WakeRow, WaitStore}

  private def forwardId(value: Int, wrap: Boolean = false): ForwardId =
    ForwardId(value = value, wrap = wrap)

  private def slotId(value: Int, wrap: Boolean = false): SlotId =
    SlotId(valid = true, wrap = wrap, value = value)

  private def wakeId(value: Int, wrap: Boolean = false): STQFlushPruneReference.Id =
    STQFlushPruneReference.Id(valid = true, wrap = wrap, value = value)

  test("STA-only resident store is the required timing window for wait replay capture") {
    val storeBidValue = 7
    val storeLsIdValue = 1
    val loadBidValue = 8
    val loadLsIdValue = 3
    val storePc = BigInt(0x4400)
    val loadPc = BigInt(0x5000)
    val loadAddr = BigInt(0x3000)
    val storeData = BigInt("1122334455667788", 16)
    val baseData = BigInt("ffeeddccbbaa9988", 16)

    val readyAtLookup = ReducedStoreResidentForwardReference(
      enable = true,
      loadValid = true,
      loadAddr = loadAddr,
      loadSize = 8,
      loadBid = forwardId(loadBidValue),
      loadLsId = forwardId(loadLsIdValue),
      baseData = baseData,
      rows = Seq(ForwardRow(
        dataReady = true,
        bid = forwardId(storeBidValue),
        lsId = forwardId(storeLsIdValue),
        pc = storePc,
        addr = loadAddr,
        data = storeData,
        size = 8)))

    assert(!readyAtLookup.waitBlocked)
    assert(readyAtLookup.forwardMask == BigInt("ff", 16))
    assert(readyAtLookup.waitStore.isEmpty)

    val staOnlyAtLookup = ReducedStoreResidentForwardReference(
      enable = true,
      loadValid = true,
      loadAddr = loadAddr,
      loadSize = 8,
      loadBid = forwardId(loadBidValue),
      loadLsId = forwardId(loadLsIdValue),
      baseData = baseData,
      rows = Seq(ForwardRow(
        dataReady = false,
        bid = forwardId(storeBidValue),
        lsId = forwardId(storeLsIdValue),
        pc = storePc,
        addr = loadAddr,
        data = storeData,
        size = 8)))

    assert(staOnlyAtLookup.waitBlocked)
    assert(staOnlyAtLookup.forwardMask == 0)
    assert(staOnlyAtLookup.waitStore.nonEmpty)

    val waitKey = staOnlyAtLookup.waitStore.get
    val captured = ReducedLoadWaitReplaySlotReference.step(
      ReducedLoadWaitReplaySlotReference.State(),
      capture = Some(Capture(
        pc = loadPc,
        addr = loadAddr,
        size = 8,
        bid = slotId(loadBidValue),
        lsId = slotId(loadLsIdValue),
        waitKey = ReducedLoadWaitReplaySlotReference.Wait(
          valid = true,
          index = waitKey.index,
          storeId = slotId(waitKey.bid.value, waitKey.bid.wrap),
          storeLsId = slotId(waitKey.lsId.value, waitKey.lsId.wrap),
          pc = waitKey.pc),
        gid = slotId(0),
        rid = slotId(4),
        youngestStoreId = Some(slotId(storeBidValue)),
        youngestStoreLsId = Some(slotId(storeLsIdValue)))))

    assert(captured.captureAccepted)
    assert(captured.state.active)

    val notReadyWake = ResidentStoreReplayWakeupReference(
      enable = true,
      waitStore = WaitStore(index = waitKey.index, storeId = wakeId(storeBidValue), storeLsId = wakeId(storeLsIdValue), pc = storePc),
      rows = Seq(WakeRow(
        dataReady = false,
        bid = wakeId(storeBidValue),
        lsId = wakeId(storeLsIdValue),
        pc = storePc,
        addr = loadAddr,
        data = storeData,
        size = 8)))
    assert(notReadyWake.identityMatch)
    assert(!notReadyWake.wakeValid)

    val readyWake = ResidentStoreReplayWakeupReference(
      enable = true,
      waitStore = WaitStore(index = waitKey.index, storeId = wakeId(storeBidValue), storeLsId = wakeId(storeLsIdValue), pc = storePc),
      rows = Seq(WakeRow(
        bid = wakeId(storeBidValue),
        lsId = wakeId(storeLsIdValue),
        pc = storePc,
        addr = loadAddr,
        data = storeData,
        size = 8)))
    assert(readyWake.wakeValid)

    val cleared = ReducedLoadWaitReplaySlotReference.step(
      captured.state,
      wake = Some(Wake(storeId = slotId(storeBidValue), storeLsId = slotId(storeLsIdValue), pc = storePc)))

    assert(cleared.waitStoreClear)
    assert(cleared.relaunch.exists(_.youngestStoreLsId.value == storeLsIdValue))
  }

  test("Chisel wait-replay path probe elaborates the real STQ to LIQ owner chain") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedStoreWaitReplayChiselPathProbe(entries = 8, liqEntries = 4))

    assert(sv.contains("module ReducedStoreWaitReplayChiselPathProbe"))
    assert(sv.contains("STQEntryBank"))
    assert(sv.contains("ReducedStoreResidentForward"))
    assert(sv.contains("ReducedLoadWaitReplaySlot"))
    assert(sv.contains("ResidentStoreReplayWakeup"))
    assert(sv.contains("ReducedLoadReplayRelaunchQueue"))
    assert(sv.contains("ReducedLoadReplayLiqAllocPath"))
    assert(sv.contains("io_forwardWaitBlocked"))
    assert(sv.contains("io_waitSlotCaptureAccepted"))
    assert(sv.contains("io_wakeSelectedRowReady"))
    assert(sv.contains("io_relaunchQueueOutFire"))
    assert(sv.contains("io_liqAllocAccepted"))
    assert(sv.contains("io_liqRefillAccepted"))
    assert(sv.contains("io_liqLaunchAccepted"))
    assert(sv.contains("io_liqLaunchSelectedLoadLsId_value"))
    assert(sv.contains("io_liqE2LoadDataReturned"))
    assert(sv.contains("io_liqE4UpdateValid"))
    assert(sv.contains("io_liqLhqRecordValid"))
    assert(sv.contains("io_liqLhqRecordLoadLsId_value"))
    assert(sv.contains("io_liqResolvedMask"))
    assert(sv.contains("io_liqFirstYoungestStoreLsId_value"))
  }
}
