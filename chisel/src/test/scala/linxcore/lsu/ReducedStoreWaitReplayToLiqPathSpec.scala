package linxcore.lsu

import org.scalatest.funsuite.AnyFunSuite

class ReducedStoreWaitReplayToLiqPathSpec extends AnyFunSuite {
  import ReducedLoadReplayLiqAllocPathReference.Model
  import ReducedLoadReplayRelaunchQueueReference.{Model => QueueModel}
  import ReducedLoadWaitReplaySlotReference.{Capture, Id => SlotId, Wake}
  import ReducedStoreResidentForwardReference.{Id => ForwardId, Row => ForwardRow}
  import ResidentStoreReplayWakeupReference.{Row => WakeRow, WaitStore}

  private def forwardId(value: Int, wrap: Boolean = false): ForwardId =
    ForwardId(value = value, wrap = wrap)

  private def slotId(value: Int, wrap: Boolean = false): SlotId =
    SlotId(valid = true, wrap = wrap, value = value)

  private def wakeId(value: Int, wrap: Boolean = false): STQFlushPruneReference.Id =
    STQFlushPruneReference.Id(valid = true, wrap = wrap, value = value)

  test("not-ready resident store captures, wakes, queues, and allocates a replay LIQ row") {
    val storeBidValue = 7
    val storeLsIdValue = 1
    val loadBidValue = 8
    val loadLsIdValue = 3
    val storePc = BigInt(0x4400)
    val loadPc = BigInt(0x5000)
    val loadAddr = BigInt(0x3000)
    val storeData = BigInt("1122334455667788", 16)
    val baseData = BigInt("ffeeddccbbaa9988", 16)

    val forward = ReducedStoreResidentForwardReference(
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

    assert(forward.waitBlocked)
    assert(forward.forwardMask == 0)
    assert(forward.waitMask == BigInt("ff", 16))
    assert(forward.data == baseData)
    assert(forward.waitStore.nonEmpty)

    val waitKey = forward.waitStore.get
    val afterCapture = ReducedLoadWaitReplaySlotReference.step(
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

    assert(afterCapture.captureAccepted)
    assert(afterCapture.state.active)
    assert(afterCapture.state.waitKey.pc == storePc)

    val notReadyWake = ResidentStoreReplayWakeupReference(
      enable = true,
      waitStore = WaitStore(
        index = waitKey.index,
        storeId = wakeId(storeBidValue),
        storeLsId = wakeId(storeLsIdValue),
        pc = storePc),
      rows = Seq(WakeRow(
        dataReady = false,
        bid = wakeId(storeBidValue),
        lsId = wakeId(storeLsIdValue),
        pc = storePc,
        addr = loadAddr,
        data = storeData,
        size = 8)))

    assert(notReadyWake.selected)
    assert(notReadyWake.identityMatch)
    assert(!notReadyWake.ready)
    assert(!notReadyWake.wakeValid)

    val readyWake = ResidentStoreReplayWakeupReference(
      enable = true,
      waitStore = WaitStore(
        index = waitKey.index,
        storeId = wakeId(storeBidValue),
        storeLsId = wakeId(storeLsIdValue),
        pc = storePc),
      rows = Seq(WakeRow(
        bid = wakeId(storeBidValue),
        lsId = wakeId(storeLsIdValue),
        pc = storePc,
        addr = loadAddr,
        data = storeData,
        size = 8)))

    assert(readyWake.wakeValid)
    assert(readyWake.wake.exists(_.validMask == BigInt("ff", 16)))
    assert((readyWake.wake.get.data & ((BigInt(1) << 64) - 1)) == storeData)

    val afterWake = ReducedLoadWaitReplaySlotReference.step(
      afterCapture.state,
      wake = Some(Wake(
        storeId = slotId(storeBidValue),
        storeLsId = slotId(storeLsIdValue),
        pc = storePc)))

    assert(afterWake.waitStoreClear)
    assert(!afterWake.state.active)
    val relaunch = afterWake.relaunch.getOrElse(fail("matching wake did not relaunch captured load"))
    assert(relaunch.pc == loadPc)
    assert(relaunch.addr == loadAddr)
    assert(relaunch.lsId.value == loadLsIdValue)
    assert(relaunch.youngestStoreId.value == storeBidValue)
    assert(relaunch.youngestStoreLsId.value == storeLsIdValue)

    val queue = new QueueModel(depth = 2)
    val queued = queue.step(enqueue = Some(relaunch))
    assert(queued.enqueueAccepted)
    assert(queued.pending)
    val head = queue.step(outReady = true)
    assert(head.outFire)
    assert(head.out.contains(relaunch))

    val liq = new Model(entries = 4)
    val allocated = liq.step(candidateIn = head.out)
    assert(allocated.allocValid)
    assert(allocated.allocReady)
    assert(allocated.allocAccepted)
    assert(allocated.residentCount == 1)
    assert(allocated.rows.head.candidate.pc == loadPc)
    assert(allocated.rows.head.candidate.youngestStoreLsId.value == storeLsIdValue)
  }
}
