package linxcore.backend

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object DecodeLoadStoreIdAssignReference {
  final case class State(lsid: BigInt = 0, loadId: BigInt = 0, storeId: BigInt = 0)
  final case class Output(
      state: State,
      memoryValid: Boolean,
      loadIdValid: Boolean,
      storeIdValid: Boolean,
      assignFire: Boolean,
      outLsId: BigInt,
      assignedLsId: BigInt,
      assignedLoadId: BigInt,
      assignedStoreId: BigInt,
      storeSplitIntent: Boolean)

  def step(
      state: State,
      inValid: Boolean,
      isLoad: Boolean,
      isStore: Boolean,
      isDczva: Boolean,
      isLoadStorePair: Boolean,
      isStorePcr: Boolean = false,
      cacheMaintainNoSplit: Boolean = false,
      storeSplitRequest: Boolean,
      stackSetRequest: Boolean,
      accept: Boolean,
      flush: Boolean = false,
      restore: Option[State] = None): Output = {
    val storeLike = inValid && (isStore || isDczva)
    val loadLike = inValid && isLoad && !storeLike
    val memoryValid = loadLike || storeLike
    val assignFire = accept && memoryValid
    val splitIntent =
      inValid && isStore && (storeSplitRequest || stackSetRequest) &&
        !isLoadStorePair && !cacheMaintainNoSplit
    val nextState =
      if (flush) {
        restore.getOrElse(State())
      } else if (assignFire) {
        State(
          lsid = state.lsid + 1,
          loadId = state.loadId + (if (loadLike) 1 else 0),
          storeId = state.storeId + (if (storeLike) 1 else 0))
      } else {
        state
      }

    Output(
      state = nextState,
      memoryValid = memoryValid,
      loadIdValid = loadLike,
      storeIdValid = storeLike,
      assignFire = assignFire,
      outLsId = if (inValid) state.lsid else 0,
      assignedLsId = if (memoryValid) state.lsid else 0,
      assignedLoadId = if (loadLike) state.loadId else 0,
      assignedStoreId = if (storeLike) state.storeId else 0,
      storeSplitIntent = splitIntent)
  }
}

class DecodeLoadStoreIdAssignSpec extends AnyFunSuite {
  import DecodeLoadStoreIdAssignReference._

  test("reference assigns lsID and load_id only when a load is accepted") {
    val load = step(
      State(),
      inValid = true,
      isLoad = true,
      isStore = false,
      isDczva = false,
      isLoadStorePair = false,
      storeSplitRequest = false,
      stackSetRequest = false,
      accept = true)

    assert(load.memoryValid)
    assert(load.loadIdValid)
    assert(!load.storeIdValid)
    assert(load.assignFire)
    assert(load.assignedLsId == 0)
    assert(load.assignedLoadId == 0)
    assert(load.state == State(lsid = 1, loadId = 1, storeId = 0))
  }

  test("reference assigns lsID and sid for store-like operations") {
    val afterLoad = State(lsid = 1, loadId = 1, storeId = 0)
    val store = step(
      afterLoad,
      inValid = true,
      isLoad = false,
      isStore = true,
      isDczva = false,
      isLoadStorePair = false,
      storeSplitRequest = true,
      stackSetRequest = false,
      accept = true)

    assert(store.storeIdValid)
    assert(store.assignedLsId == 1)
    assert(store.assignedStoreId == 0)
    assert(store.storeSplitIntent)
    assert(store.state == State(lsid = 2, loadId = 1, storeId = 1))

    val dczva = step(
      store.state,
      inValid = true,
      isLoad = false,
      isStore = false,
      isDczva = true,
      isLoadStorePair = false,
      storeSplitRequest = false,
      stackSetRequest = false,
      accept = true)

    assert(dczva.storeIdValid)
    assert(!dczva.storeSplitIntent)
    assert(dczva.state == State(lsid = 3, loadId = 1, storeId = 2))
  }

  test("reference does not advance counters for backpressure or non-memory rows") {
    val state = State(lsid = 7, loadId = 3, storeId = 4)
    val stalled = step(
      state,
      inValid = true,
      isLoad = true,
      isStore = false,
      isDczva = false,
      isLoadStorePair = false,
      storeSplitRequest = false,
      stackSetRequest = false,
      accept = false)
    val scalar = step(
      state,
      inValid = true,
      isLoad = false,
      isStore = false,
      isDczva = false,
      isLoadStorePair = false,
      storeSplitRequest = false,
      stackSetRequest = false,
      accept = true)

    assert(!stalled.assignFire)
    assert(stalled.assignedLsId == 7)
    assert(stalled.state == state)
    assert(!scalar.memoryValid)
    assert(!scalar.assignFire)
    assert(scalar.outLsId == 7)
    assert(scalar.state == state)
  }

  test("reference suppresses split intent for load-store pairs, cache maintenance, and restores after flush") {
    val pair = step(
      State(lsid = 2, loadId = 1, storeId = 1),
      inValid = true,
      isLoad = false,
      isStore = true,
      isDczva = false,
      isLoadStorePair = true,
      storeSplitRequest = true,
      stackSetRequest = false,
      accept = true)
    val cacheMaintain = step(
      pair.state,
      inValid = true,
      isLoad = false,
      isStore = true,
      isDczva = false,
      isLoadStorePair = false,
      cacheMaintainNoSplit = true,
      storeSplitRequest = true,
      stackSetRequest = false,
      accept = true)
    val restored = step(
      cacheMaintain.state,
      inValid = true,
      isLoad = true,
      isStore = false,
      isDczva = false,
      isLoadStorePair = false,
      storeSplitRequest = false,
      stackSetRequest = false,
      accept = true,
      flush = true,
      restore = Some(State(lsid = 11, loadId = 5, storeId = 6)))

    assert(!pair.storeSplitIntent)
    assert(pair.state == State(lsid = 3, loadId = 1, storeId = 2))
    assert(!cacheMaintain.storeSplitIntent)
    assert(cacheMaintain.state == State(lsid = 4, loadId = 1, storeId = 3))
    assert(restored.state == State(lsid = 11, loadId = 5, storeId = 6))
  }

  test("IO exposes model-derived LSID, load_id, sid, and split observability") {
    val p = InterfaceParams(lsidWidth = 32)
    val io = new DecodeLoadStoreIdAssignIO(p, serialWidth = 64)

    assert(io.assignedLsId.getWidth == 32)
    assert(io.assignedLoadId.getWidth == 64)
    assert(io.assignedStoreId.getWidth == 64)
    assert(io.nextLsId.getWidth == 32)
    assert(io.nextLoadId.getWidth == 64)
    assert(io.nextStoreId.getWidth == 64)
    assert(io.storeSplitIntent.getWidth == 1)
    assert(io.isStorePcr.getWidth == 1)
    assert(io.cacheMaintainNoSplit.getWidth == 1)
  }

  test("DecodeLoadStoreIdAssign elaborates as a separate backend owner") {
    val sv = ChiselStage.emitSystemVerilog(
      new DecodeLoadStoreIdAssign(InterfaceParams(), serialWidth = 64)
    )

    assert(sv.contains("module DecodeLoadStoreIdAssign"))
    assert(sv.contains("io_assignedLsId"))
    assert(sv.contains("io_assignedLoadId"))
    assert(sv.contains("io_assignedStoreId"))
    assert(sv.contains("io_storeSplitIntent"))
  }
}
