package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadStoreForwardingReference {
  import STQFlushPruneReference.Id

  final case class Query(
      valid: Boolean = true,
      lineAddr: BigInt = 0x1000,
      byteOffset: Int = 0,
      size: Int = 8,
      youngestStoreId: Id = Id(),
      youngestStoreLsId: Id = Id(),
      youngestStoreLsIdFullValid: Boolean = true,
      youngestStoreLsIdFull: BigInt = 0,
      lsidWidth: Int = 32,
      isTile: Boolean = false)

  final case class Store(
      index: Int,
      valid: Boolean = true,
      working: Boolean = true,
      addrReady: Boolean = true,
      dataReady: Boolean = true,
      isTile: Boolean = false,
      storeId: Id = Id(),
      storeLsId: Id = Id(),
      storeLsIdFullValid: Boolean = true,
      storeLsIdFull: BigInt = 0,
      pc: BigInt = 0,
      lineAddr: BigInt = 0x1000,
      byteMask: BigInt = 0,
      data: BigInt = 0)

  final case class Result(
      loadByteMask: BigInt,
      eligibleStoreMask: Int,
      tileSuppressedMask: Int,
      fullLsIdMissingMask: Int,
      fullLsIdAmbiguousMask: Int,
      coveredMask: BigInt,
      forwardMask: BigInt,
      waitMask: BigInt,
      uncoveredLoadMask: BigInt,
      forwardData: BigInt,
      mergedData: BigInt,
      forwardValid: Boolean,
      storeBypassComplete: Boolean,
      waitStore: Option[Store],
      selectedStoreIndexByByte: Vector[Int])

  private def less(lhs: Id, rhs: Id): Boolean =
    if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

  private def lessEqual(lhs: Id, rhs: Id): Boolean =
    less(lhs, rhs) || lhs == rhs

  private def lessFull(lhs: BigInt, rhs: BigInt, width: Int): Boolean = {
    val mask = (BigInt(1) << width) - 1
    val distance = (rhs - lhs) & mask
    lhs != rhs && distance < (BigInt(1) << (width - 1))
  }

  private def ambiguousFull(lhs: BigInt, rhs: BigInt, width: Int): Boolean = {
    val mask = (BigInt(1) << width) - 1
    ((rhs - lhs) & mask) == (BigInt(1) << (width - 1))
  }

  private def storeBeforeOrSame(store: Store, query: Query): Boolean =
    less(store.storeId, query.youngestStoreId) ||
      (store.storeId == query.youngestStoreId && store.storeLsIdFullValid &&
        query.youngestStoreLsIdFullValid &&
        (store.storeLsIdFull == query.youngestStoreLsIdFull ||
          lessFull(store.storeLsIdFull, query.youngestStoreLsIdFull, query.lsidWidth)))

  private def greaterStore(lhs: Store, rhs: Store, width: Int): Boolean =
    less(rhs.storeId, lhs.storeId) ||
      (lhs.storeId == rhs.storeId && lhs.storeLsIdFullValid && rhs.storeLsIdFullValid &&
        lessFull(rhs.storeLsIdFull, lhs.storeLsIdFull, width))

  private def bit(mask: BigInt, lane: Int): Boolean =
    ((mask >> lane) & BigInt(1)) == BigInt(1)

  def byteMask(offset: Int, size: Int): BigInt =
    (0 until 64).foldLeft(BigInt(0)) { case (mask, lane) =>
      if (size > 0 && lane >= offset && lane < offset + size) {
        mask | (BigInt(1) << lane)
      } else {
        mask
      }
    }

  def lineData(bytes: Map[Int, Int]): BigInt =
    bytes.foldLeft(BigInt(0)) { case (data, (lane, value)) =>
      data | (BigInt(value & 0xff) << (lane * 8))
    }

  private def getByte(data: BigInt, lane: Int): Int =
    ((data >> (lane * 8)) & BigInt(0xff)).toInt

  private def setByte(data: BigInt, lane: Int, value: Int): BigInt = {
    val clearMask = ~(BigInt(0xff) << (lane * 8))
    (data & clearMask) | (BigInt(value & 0xff) << (lane * 8))
  }

  private def storeMask(stores: Seq[Store], pred: Store => Boolean): Int =
    stores.foldLeft(0) { case (mask, store) => if (pred(store)) mask | (1 << store.index) else mask }

  def forward(query: Query, stores: Seq[Store], cacheData: BigInt = 0): Result = {
    val loadMask = if (query.valid) byteMask(query.byteOffset, query.size) else BigInt(0)

    def baseEligible(store: Store): Boolean =
      query.valid &&
        store.valid &&
        store.working &&
        store.addrReady &&
        store.lineAddr == query.lineAddr &&
        (store.byteMask & loadMask) != 0 &&
        storeBeforeOrSame(store, query)

    def eligible(store: Store): Boolean =
      baseEligible(store) && !store.isTile && !query.isTile

    def authorityCandidate(store: Store): Boolean =
      query.valid && store.valid && store.working && store.addrReady &&
        store.lineAddr == query.lineAddr && (store.byteMask & loadMask) != 0 &&
        !store.isTile && !query.isTile

    def tileSuppressed(store: Store): Boolean =
      baseEligible(store) && (store.isTile || query.isTile)

    val selected = (0 until 64).map { lane =>
      stores.foldLeft(Option.empty[Store]) {
        case (best, store) if eligible(store) && bit(store.byteMask, lane) =>
          best match {
            case Some(current) if !greaterStore(store, current, query.lsidWidth) => best
            case _ => Some(store)
          }
        case (best, _) => best
      }
    }.toVector

    val coveredMask = selected.zipWithIndex.foldLeft(BigInt(0)) {
      case (mask, (Some(_), lane)) if bit(loadMask, lane) => mask | (BigInt(1) << lane)
      case (mask, _) => mask
    }
    val forwardMask = selected.zipWithIndex.foldLeft(BigInt(0)) {
      case (mask, (Some(store), lane)) if bit(loadMask, lane) && store.dataReady => mask | (BigInt(1) << lane)
      case (mask, _) => mask
    }
    val waitMask = selected.zipWithIndex.foldLeft(BigInt(0)) {
      case (mask, (Some(store), lane)) if bit(loadMask, lane) && !store.dataReady => mask | (BigInt(1) << lane)
      case (mask, _) => mask
    }

    val forwardData = selected.zipWithIndex.foldLeft(BigInt(0)) {
      case (data, (Some(store), lane)) if bit(forwardMask, lane) => setByte(data, lane, getByte(store.data, lane))
      case (data, _) => data
    }
    val mergedData = (0 until 64).foldLeft(cacheData) { case (data, lane) =>
      if (bit(forwardMask, lane)) {
        selected(lane).map(store => setByte(data, lane, getByte(store.data, lane))).getOrElse(data)
      } else {
        data
      }
    }

    val invalidStores = selected.zipWithIndex.collect {
      case (Some(store), lane) if bit(loadMask, lane) && !store.dataReady => store
    }.distinct
    val waitStore = invalidStores.foldLeft(Option.empty[Store]) {
      case (best, store) =>
        best match {
          case Some(current) if !greaterStore(store, current, query.lsidWidth) => best
          case _ => Some(store)
        }
    }

    Result(
      loadByteMask = loadMask,
      eligibleStoreMask = storeMask(stores, eligible),
      tileSuppressedMask = storeMask(stores, tileSuppressed),
      fullLsIdMissingMask = storeMask(stores, store =>
        authorityCandidate(store) && store.storeId == query.youngestStoreId &&
          (!store.storeLsIdFullValid || !query.youngestStoreLsIdFullValid)),
      fullLsIdAmbiguousMask = storeMask(stores, store =>
        authorityCandidate(store) && store.storeId == query.youngestStoreId &&
          store.storeLsIdFullValid && query.youngestStoreLsIdFullValid &&
          ambiguousFull(store.storeLsIdFull, query.youngestStoreLsIdFull, query.lsidWidth)),
      coveredMask = coveredMask,
      forwardMask = forwardMask,
      waitMask = waitMask,
      uncoveredLoadMask = loadMask & ~coveredMask,
      forwardData = forwardData,
      mergedData = mergedData,
      forwardValid = forwardMask != 0,
      storeBypassComplete = query.valid && loadMask != 0 && waitMask == 0 && (forwardMask & loadMask) == loadMask,
      waitStore = waitStore,
      selectedStoreIndexByByte = selected.map(_.map(_.index).getOrElse(0))
    )
  }
}

class LoadStoreForwardingSpec extends AnyFunSuite {
  import LoadStoreForwardingReference._
  import STQFlushPruneReference.Id

  private def id(value: Int, wrap: Boolean = false): Id =
    Id(wrap = wrap, value = value)

  test("ready older store bytes forward over cache data") {
    val query = Query(byteOffset = 4, size = 4, youngestStoreId = id(5))
    val store = Store(
      index = 0,
      storeId = id(3),
      byteMask = byteMask(4, 4),
      data = lineData(Map(4 -> 0xaa, 5 -> 0xbb, 6 -> 0xcc, 7 -> 0xdd))
    )
    val cache = lineData(Map(4 -> 0x11, 5 -> 0x22, 6 -> 0x33, 7 -> 0x44))

    val result = forward(query, Seq(store), cache)

    assert(result.loadByteMask == BigInt("f0", 16))
    assert(result.eligibleStoreMask == 0x1)
    assert(result.forwardMask == BigInt("f0", 16))
    assert(result.waitMask == 0)
    assert(result.uncoveredLoadMask == 0)
    assert(result.storeBypassComplete)
    assert(result.forwardValid)
    assert(result.forwardData == lineData(Map(4 -> 0xaa, 5 -> 0xbb, 6 -> 0xcc, 7 -> 0xdd)))
    assert(result.mergedData == lineData(Map(4 -> 0xaa, 5 -> 0xbb, 6 -> 0xcc, 7 -> 0xdd)))
  }

  test("stores younger than the load allocation snapshot are ignored") {
    val query = Query(byteOffset = 0, size = 8, youngestStoreId = id(5))
    val store = Store(index = 0, storeId = id(6), byteMask = byteMask(0, 8), data = lineData(Map(0 -> 0xaa)))

    val result = forward(query, Seq(store))

    assert(result.eligibleStoreMask == 0)
    assert(result.coveredMask == 0)
    assert(result.forwardMask == 0)
    assert(result.uncoveredLoadMask == byteMask(0, 8))
    assert(!result.forwardValid)
    assert(!result.storeBypassComplete)
  }

  test("newest older store wins per byte and not-ready bytes request replay") {
    val query = Query(byteOffset = 8, size = 4, youngestStoreId = id(4))
    val stores = Seq(
      Store(index = 0, storeId = id(2), byteMask = byteMask(8, 4), data = lineData(Map(8 -> 0x12, 9 -> 0x13, 10 -> 0x14, 11 -> 0x15))),
      Store(index = 1, storeId = id(3), dataReady = false, pc = 0x3000, byteMask = byteMask(9, 2), data = lineData(Map(9 -> 0x31, 10 -> 0x32))),
      Store(index = 2, storeId = id(4), byteMask = byteMask(10, 1), data = lineData(Map(10 -> 0x44)))
    )

    val result = forward(query, stores)

    assert(result.eligibleStoreMask == 0x7)
    assert(result.coveredMask == byteMask(8, 4))
    assert(result.forwardMask == ((BigInt(1) << 8) | (BigInt(1) << 10) | (BigInt(1) << 11)))
    assert(result.waitMask == (BigInt(1) << 9))
    assert(result.forwardData == lineData(Map(8 -> 0x12, 10 -> 0x44, 11 -> 0x15)))
    assert(!result.storeBypassComplete)
    assert(result.waitStore.map(_.pc).contains(0x3000))
    assert(result.selectedStoreIndexByByte(8) == 0)
    assert(result.selectedStoreIndexByByte(9) == 1)
    assert(result.selectedStoreIndexByByte(10) == 2)
    assert(result.selectedStoreIndexByByte(11) == 0)
  }

  test("tile or different-line stores do not produce scalar forwarding") {
    val query = Query(byteOffset = 0, size = 8, youngestStoreId = id(5))
    val stores = Seq(
      Store(index = 0, isTile = true, storeId = id(2), byteMask = byteMask(0, 8)),
      Store(index = 1, storeId = id(3), lineAddr = 0x2000, byteMask = byteMask(0, 8))
    )

    val result = forward(query, stores)

    assert(result.tileSuppressedMask == 0x1)
    assert(result.eligibleStoreMask == 0)
    assert(result.coveredMask == 0)
    assert(result.forwardMask == 0)
    assert(result.uncoveredLoadMask == byteMask(0, 8))
  }

  test("wrap-aware ordering selects the newest store before the snapshot") {
    val query = Query(byteOffset = 0, size = 1, youngestStoreId = id(1, wrap = true))
    val stores = Seq(
      Store(index = 0, storeId = id(14), byteMask = byteMask(0, 1), data = lineData(Map(0 -> 0x14))),
      Store(index = 1, storeId = id(0, wrap = true), byteMask = byteMask(0, 1), data = lineData(Map(0 -> 0x20)))
    )

    val result = forward(query, stores)

    assert(result.eligibleStoreMask == 0x3)
    assert(result.forwardMask == BigInt(1))
    assert(result.forwardData == BigInt(0x20))
    assert(result.storeBypassComplete)
    assert(result.selectedStoreIndexByByte.head == 1)
  }

  test("same-BID stores use LSID to choose the nearest older byte source") {
    val query = Query(
      byteOffset = 0,
      size = 2,
      youngestStoreId = id(2),
      youngestStoreLsId = id(3),
      youngestStoreLsIdFull = BigInt("0100000003", 16),
      lsidWidth = 40)
    val stores = Seq(
      Store(index = 0, storeId = id(2), storeLsId = id(1), storeLsIdFull = BigInt("0100000001", 16), byteMask = byteMask(0, 2), data = lineData(Map(0 -> 0x11, 1 -> 0x12))),
      Store(index = 1, storeId = id(2), storeLsId = id(3), storeLsIdFull = BigInt("0100000003", 16), byteMask = byteMask(0, 2), data = lineData(Map(0 -> 0x31, 1 -> 0x32))),
      Store(index = 2, storeId = id(2), storeLsId = id(4), storeLsIdFull = BigInt("0100000004", 16), byteMask = byteMask(0, 2), data = lineData(Map(0 -> 0x41, 1 -> 0x42)))
    )

    val result = forward(query, stores)

    assert(result.eligibleStoreMask == 0x3)
    assert(result.forwardMask == byteMask(0, 2))
    assert(result.forwardData == lineData(Map(0 -> 0x31, 1 -> 0x32)))
    assert(result.selectedStoreIndexByByte(0) == 1)
    assert(result.selectedStoreIndexByByte(1) == 1)
  }

  test("same-BID forwarding uses full LSID high bits and rejects missing or ambiguous authority") {
    val query = Query(
      byteOffset = 0,
      size = 1,
      youngestStoreId = id(2),
      youngestStoreLsId = id(1),
      youngestStoreLsIdFull = BigInt("0100000005", 16),
      lsidWidth = 40)
    val stores = Seq(
      Store(index = 0, storeId = id(2), storeLsId = id(1), storeLsIdFull = BigInt("0000000001", 16), byteMask = 1, data = 0x11),
      Store(index = 1, storeId = id(2), storeLsId = id(1), storeLsIdFull = BigInt("0100000004", 16), byteMask = 1, data = 0x44),
      Store(index = 2, storeId = id(2), storeLsId = id(1), storeLsIdFullValid = false, byteMask = 1, data = 0x77),
      Store(index = 3, storeId = id(2), storeLsId = id(1), storeLsIdFull = BigInt("8100000005", 16), byteMask = 1, data = 0x88),
      Store(index = 4, storeId = id(2), storeLsIdFullValid = false, lineAddr = 0x2000, byteMask = 1),
      Store(index = 5, storeId = id(2), storeLsIdFull = BigInt("8100000005", 16), isTile = true, byteMask = 1))

    val result = forward(query, stores)

    assert(result.eligibleStoreMask == 0x3)
    assert(result.fullLsIdMissingMask == 0x4)
    assert(result.fullLsIdAmbiguousMask == 0x8)
    assert(result.forwardData == 0x44)
    assert(result.selectedStoreIndexByByte.head == 1)
  }

  test("Chisel LoadStoreForwarding elaborates with byte masks, merge output, and wait diagnostics") {
    val io = new LoadStoreForwardingIO(robEntries = 8, storeEntries = 4, lsidWidth = 40)
    assert(io.stores.head.storeLsIdFull.getWidth == 40)
    assert(io.query.youngestStoreLsIdFull.getWidth == 40)
    assert(io.waitStore.storeLsIdFull.getWidth == 40)

    val sv = ChiselStage.emitSystemVerilog(new LoadStoreForwarding(
      robEntries = 8, storeEntries = 4, lsidWidth = 40))

    assert(sv.contains("module LoadStoreForwarding"))
    assert(sv.contains("io_forwardMask"))
    assert(sv.contains("io_waitMask"))
    assert(sv.contains("io_mergedData"))
    assert(sv.contains("io_waitStore_valid"))
    assert(sv.contains("io_waitStore_storeLsIdFull"))
    assert(sv.contains("io_fullLsIdMissingMask"))
    assert(sv.contains("io_fullLsIdAmbiguousMask"))
    assert(sv.contains("io_selectedStoreIndexByByte"))
  }
}
