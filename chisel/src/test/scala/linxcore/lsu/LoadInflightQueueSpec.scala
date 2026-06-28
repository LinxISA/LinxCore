package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadInflightQueueReference {
  import LoadForwardPipelineReference._
  import LoadStoreForwardingReference.{Query, Store}
  import STQFlushPruneReference.Id

  sealed trait Status
  case object Idle extends Status
  case object Wait extends Status
  case object Repick extends Status
  case object L1DcMiss extends Status
  case object L2Wait extends Status
  case object Resolved extends Status

  final case class Alloc(
      bid: Id = Id(),
      gid: Id = Id(),
      rid: Id = Id(),
      pc: BigInt = 0,
      addr: BigInt = 0,
      size: Int = 8,
      youngestStoreId: Id = Id(),
      isTile: Boolean = false,
      specWakeup: Boolean = false,
      stackValid: Boolean = false)

  final case class Row(
      status: Status = Idle,
      loadId: Id = Id(valid = false),
      alloc: Alloc = Alloc(),
      lineData: BigInt = 0,
      validMask: BigInt = 0,
      loadByteMask: BigInt = 0,
      forwardMask: BigInt = 0,
      waitMask: BigInt = 0,
      waitStore: Option[Store] = None,
      storeBypass: Boolean = false,
      dataComplete: Boolean = false,
      sourcesReturned: Boolean = false,
      l1Miss: Boolean = false,
      missKind: MissKind = NoMiss)

  final case class AllocResult(accepted: Boolean, index: Int, loadId: Id)
  final case class LaunchInput(
      stores: Seq[Store] = Seq.empty,
      baseData: BigInt = 0,
      baseValidMask: BigInt = 0,
      loadDataReturned: Boolean = true,
      scbReturned: Boolean = true,
      returnReady: Boolean = true)
  final case class HitRecord(row: Row, byteMask: BigInt, data: BigInt, forwardedMask: BigInt)
  final case class CycleResult(e4Index: Option[Int] = None, hitRecord: Option[HitRecord] = None)

  final class Model(entries: Int) {
    require(entries > 1 && (entries & (entries - 1)) == 0)

    private val rows = Array.fill[Option[Row]](entries)(None)
    private val pipe = new LoadForwardPipelineReference.Model
    private var e3Index: Option[Int] = None
    private var allocPtr = 0
    private var allocWrap = false

    def row(index: Int): Option[Row] = rows(index)
    def residentCount: Int = rows.count(_.isDefined)
    def occupiedMask: Int = mask(rows.map(_.isDefined).toSeq)
    def waitMask: Int = mask(rows.map(_.exists(_.status == Wait)).toSeq)
    def resolvedMask: Int = mask(rows.map(_.exists(_.status == Resolved)).toSeq)
    def waitStoreMask: Int = mask(rows.map(_.exists(_.waitStore.nonEmpty)).toSeq)
    def missPending: Boolean = rows.exists(_.exists(r => r.status == L1DcMiss || r.status == L2Wait))

    private def mask(bits: Seq[Boolean]): Int =
      bits.zipWithIndex.foldLeft(0) { case (acc, (bit, index)) => if (bit) acc | (1 << index) else acc }

    private def query(row: Row): Query =
      Query(
        valid = true,
        lineAddr = row.alloc.addr & ~BigInt(0x3f),
        byteOffset = (row.alloc.addr & 0x3f).toInt,
        size = row.alloc.size,
        youngestStoreId = row.alloc.youngestStoreId,
        isTile = row.alloc.isTile
      )

    def allocate(alloc: Alloc): AllocResult = {
      val id = Id(valid = true, wrap = allocWrap, value = allocPtr)
      if (rows(allocPtr).isDefined) {
        return AllocResult(accepted = false, index = allocPtr, loadId = id)
      }

      rows(allocPtr) = Some(Row(status = Wait, loadId = id, alloc = alloc))
      val index = allocPtr
      if (allocPtr == entries - 1) {
        allocPtr = 0
        allocWrap = !allocWrap
      } else {
        allocPtr += 1
      }
      AllocResult(accepted = true, index = index, loadId = id)
    }

    def launch(index: Int, input: LaunchInput): Boolean =
      rows(index) match {
        case Some(row) if row.status == Wait && row.waitStore.isEmpty =>
          rows(index) = Some(row.copy(status = Repick, missKind = NoMiss))
          val e4 = pipe.step(Some(Input(
            query = query(row),
            stores = input.stores,
            baseData = input.baseData,
            baseValidMask = input.baseValidMask,
            loadDataReturned = input.loadDataReturned,
            scbReturned = input.scbReturned,
            returnReady = input.returnReady
          )))
          require(e4.e4.isEmpty, "single-cycle tests should not launch over a resident E3")
          e3Index = Some(index)
          true
        case _ =>
          false
      }

    def cycle(): CycleResult = {
      val updateIndex = e3Index
      val out = pipe.step()
      e3Index = None

      (updateIndex, out.e4) match {
        case (Some(index), Some(e4)) =>
          val old = rows(index).get
          val updated = old.copy(
            lineData = e4.lineData,
            validMask = e4.validMask,
            loadByteMask = e4.loadByteMask,
            forwardMask = e4.forwardMask,
            waitMask = e4.waitMask,
            storeBypass = e4.forwardMask != 0,
            dataComplete = e4.dataComplete,
            sourcesReturned = e4.sourcesReturned,
            missKind = e4.missKind
          )

          e4.missKind match {
            case NoMiss if e4.wakeupValid =>
              val row = updated.copy(status = Resolved, waitStore = None, l1Miss = false)
              rows(index) = Some(row)
              CycleResult(Some(index), Some(HitRecord(row, e4.loadByteMask, e4.lineData, e4.forwardMask)))
            case StoreDataNotReady =>
              rows(index) = Some(updated.copy(
                status = Wait,
                lineData = 0,
                validMask = 0,
                loadByteMask = 0,
                forwardMask = 0,
                waitMask = 0,
                waitStore = e4.waitStore,
                dataComplete = false,
                sourcesReturned = false
              ))
              CycleResult(Some(index))
            case DataNotComplete =>
              rows(index) = Some(updated.copy(
                status = L1DcMiss,
                validMask = 0,
                loadByteMask = 0,
                forwardMask = 0,
                waitMask = 0,
                waitStore = None,
                dataComplete = false,
                sourcesReturned = false,
                l1Miss = true
              ))
              CycleResult(Some(index))
            case AwaitingSources | ReturnPortBlocked =>
              rows(index) = Some(updated.copy(
                status = Wait,
                validMask = 0,
                loadByteMask = 0,
                forwardMask = 0,
                waitMask = 0,
                waitStore = None,
                dataComplete = false,
                sourcesReturned = false
              ))
              CycleResult(Some(index))
            case _ =>
              rows(index) = Some(updated)
              CycleResult(Some(index))
          }
        case _ =>
          CycleResult()
      }
    }

    def clearResolved(index: Int): Boolean =
      rows(index) match {
        case Some(row) if row.status == Resolved =>
          rows(index) = None
          true
        case _ =>
          false
      }

    def flush(): Unit = {
      for (index <- rows.indices) {
        rows(index) = None
      }
      allocPtr = 0
      allocWrap = false
      e3Index = None
      pipe.step(flush = true)
    }
  }
}

class LoadInflightQueueSpec extends AnyFunSuite {
  import LoadForwardPipelineReference.{AwaitingSources, DataNotComplete, ReturnPortBlocked}
  import LoadInflightQueueReference._
  import LoadStoreForwardingReference.{Store, byteMask, lineData}
  import STQFlushPruneReference.Id

  private def alloc(n: Int, addr: BigInt = 0x1000, size: Int = 4, youngestStore: Int = 7): Alloc =
    Alloc(
      bid = Id(value = n),
      gid = Id(value = 0),
      rid = Id(value = n),
      pc = 0x2000 + n,
      addr = addr,
      size = size,
      youngestStoreId = Id(value = youngestStore)
    )

  test("allocation uses slot plus wrap load ids and stalls on occupied allocation pointer") {
    val liq = new Model(entries = 2)
    val a0 = liq.allocate(alloc(0))
    val a1 = liq.allocate(alloc(1, addr = 0x1040))
    val blocked = liq.allocate(alloc(2, addr = 0x1080))

    assert(a0.accepted && a0.index == 0 && !a0.loadId.wrap && a0.loadId.value == 0)
    assert(a1.accepted && a1.index == 1 && !a1.loadId.wrap && a1.loadId.value == 1)
    assert(!blocked.accepted && blocked.index == 0 && blocked.loadId.wrap)
    assert(liq.occupiedMask == 0x3)
  }

  test("E4 hit resolves the LIQ row and publishes an LHQ hit record") {
    val liq = new Model(entries = 4)
    val rowIndex = liq.allocate(alloc(0, addr = 0x1004, size = 4, youngestStore = 5)).index
    val store = Store(
      index = 0,
      storeId = Id(value = 3),
      byteMask = byteMask(4, 4),
      data = lineData(Map(4 -> 0xaa, 5 -> 0xbb, 6 -> 0xcc, 7 -> 0xdd))
    )

    assert(liq.launch(rowIndex, LaunchInput(stores = Seq(store))))
    assert(liq.row(rowIndex).exists(_.status == Repick))
    val result = liq.cycle()
    val row = liq.row(rowIndex).get

    assert(result.e4Index.contains(rowIndex))
    assert(row.status == Resolved)
    assert(row.dataComplete)
    assert(row.sourcesReturned)
    assert(row.forwardMask == byteMask(4, 4))
    assert(result.hitRecord.exists(r => r.row.loadId.value == rowIndex && r.byteMask == byteMask(4, 4)))
    assert(liq.resolvedMask == (1 << rowIndex))
  }

  test("store-data-not-ready returns to WAIT with wait-store diagnostics") {
    val liq = new Model(entries = 4)
    val rowIndex = liq.allocate(alloc(0, addr = 0x1008, size = 2, youngestStore = 6)).index
    val store = Store(
      index = 1,
      dataReady = false,
      pc = 0x3450,
      storeId = Id(value = 4),
      byteMask = byteMask(8, 2),
      data = lineData(Map(8 -> 0x11, 9 -> 0x22))
    )

    assert(liq.launch(rowIndex, LaunchInput(stores = Seq(store))))
    liq.cycle()
    val row = liq.row(rowIndex).get

    assert(row.status == Wait)
    assert(row.waitStore.exists(_.pc == 0x3450))
    assert(liq.waitStoreMask == (1 << rowIndex))
    assert(row.validMask == 0)
  }

  test("incomplete E4 data marks L1/DC miss and asserts miss pending") {
    val liq = new Model(entries = 4)
    val rowIndex = liq.allocate(alloc(0, addr = 0x1000, size = 4)).index

    assert(liq.launch(rowIndex, LaunchInput(baseValidMask = byteMask(0, 2))))
    liq.cycle()
    val row = liq.row(rowIndex).get

    assert(row.status == L1DcMiss)
    assert(row.l1Miss)
    assert(row.missKind == DataNotComplete)
    assert(liq.missPending)
  }

  test("source and return-port blocks replay as WAIT instead of recording LHQ hits") {
    val missingScb = new Model(entries = 4)
    val scbIndex = missingScb.allocate(alloc(0, addr = 0x1000, size = 4)).index
    assert(missingScb.launch(scbIndex, LaunchInput(baseValidMask = byteMask(0, 4), scbReturned = false)))
    val scbResult = missingScb.cycle()
    assert(missingScb.row(scbIndex).exists(row => row.status == Wait && row.missKind == AwaitingSources))
    assert(scbResult.hitRecord.isEmpty)

    val blockedReturn = new Model(entries = 4)
    val retIndex = blockedReturn.allocate(alloc(0, addr = 0x1000, size = 4)).index
    assert(blockedReturn.launch(retIndex, LaunchInput(baseValidMask = byteMask(0, 4), returnReady = false)))
    val retResult = blockedReturn.cycle()
    assert(blockedReturn.row(retIndex).exists(row => row.status == Wait && row.missKind == ReturnPortBlocked))
    assert(retResult.hitRecord.isEmpty)
  }

  test("resolved rows clear before allocation pointer wraps back into their slot") {
    val liq = new Model(entries = 2)
    val a0 = liq.allocate(alloc(0, addr = 0x1000)).index
    liq.allocate(alloc(1, addr = 0x1040))

    assert(liq.launch(a0, LaunchInput(baseValidMask = byteMask(0, 4))))
    liq.cycle()
    assert(liq.clearResolved(a0))
    val wrapped = liq.allocate(alloc(2, addr = 0x1080))

    assert(wrapped.accepted)
    assert(wrapped.index == 0)
    assert(wrapped.loadId.wrap)
    assert(wrapped.loadId.value == 0)
  }

  test("Chisel LoadInflightQueue elaborates with pipeline, row, and LHQ outputs") {
    val sv = ChiselStage.emitSystemVerilog(new LoadInflightQueue(liqEntries = 4, idEntries = 8, storeEntries = 4))

    assert(sv.contains("module LoadInflightQueue"))
    assert(sv.contains("LoadForwardPipeline"))
    assert(sv.contains("io_lhqRecordValid"))
    assert(sv.contains("io_e4UpdateValid"))
    assert(sv.contains("io_missPending"))
    assert(sv.contains("io_rows_0_status"))
  }
}
