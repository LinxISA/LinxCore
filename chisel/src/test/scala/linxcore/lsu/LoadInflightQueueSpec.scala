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
      loadLsId: Id = Id(),
      peId: Int = 0,
      stid: Int = 0,
      tid: Int = 0,
      pc: BigInt = 0,
      addr: BigInt = 0,
      size: Int = 8,
      youngestStoreId: Id = Id(),
      youngestStoreLsId: Id = Id(),
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
      crossLine: Boolean = false,
      secondSegmentActive: Boolean = false,
      firstSegmentDone: Boolean = false,
      firstLineData: BigInt = 0,
      firstValidMask: BigInt = 0,
      firstLoadByteMask: BigInt = 0,
      firstForwardMask: BigInt = 0,
      waitStore: Option[Store] = None,
      storeBypass: Boolean = false,
      dataComplete: Boolean = false,
      sourcesReturned: Boolean = false,
      scbReturned: Boolean = false,
      stqReturned: Boolean = false,
      l1Hit: Boolean = false,
      l1Miss: Boolean = false,
      missKind: MissKind = NoMiss)

  final case class AllocResult(accepted: Boolean, index: Int, loadId: Id)
  final case class LaunchInput(
      stores: Seq[Store] = Seq.empty,
      baseData: BigInt = 0,
      baseValidMask: BigInt = 0,
      loadDataReturned: Boolean = true,
      scbReturned: Boolean = true,
      stqReturned: Boolean = true,
      returnReady: Boolean = true)
  final case class HitRecord(row: Row, byteMask: BigInt, data: BigInt, forwardedMask: BigInt)
  final case class CycleResult(e4Index: Option[Int] = None, hitRecord: Option[HitRecord] = None)
  final case class ReplayWakeResult(
      waitStoreClearMask: Int,
      mergeMask: Int,
      completedMask: Int)
  final case class RefillWakeResult(
      refillAccepted: Boolean,
      wakeMask: Int)

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
    def repickMask: Int = mask(rows.map(_.exists(_.status == Repick)).toSeq)
    def resolvedMask: Int = mask(rows.map(_.exists(_.status == Resolved)).toSeq)
    def waitStoreMask: Int = mask(rows.map(_.exists(_.waitStore.nonEmpty)).toSeq)
    def missPending: Boolean = rows.exists(_.exists(r => r.status == L1DcMiss || r.status == L2Wait))

    def installRow(index: Int, row: Row): Unit = {
      rows(index) = Some(row)
    }

    def preciseFlush(flush: STQFlushPruneReference.Flush): Int = {
      val prune = rows.map(_.exists(row => STQFlushPruneReference.matches(
        flush,
        STQFlushPruneReference.Row(
          valid = true,
          stid = row.alloc.stid,
          peId = row.alloc.peId,
          tid = row.alloc.tid,
          bid = row.alloc.bid,
          gid = row.alloc.gid,
          lsId = row.alloc.loadLsId))))
      val pruneMask = mask(prune.toSeq)
      prune.zipWithIndex.collectFirst { case (true, index) => index }.foreach { first =>
        allocPtr = first
        allocWrap = !rows(first).get.loadId.wrap
      }
      for (idx <- rows.indices if prune(idx)) {
        rows(idx) = None
      }
      pruneMask
    }

    def rowMutation(
        targetIndex: Int,
        request: LoadInflightRowMutationRequestBridgeReference.Request,
        storeEntries: Int = 4,
        conflicts: LoadInflightRowMutationWriteControlReference.Conflicts =
          LoadInflightRowMutationWriteControlReference.Conflicts()): LoadInflightRowMutationPathReference.Result = {
      val rowValid = rows(targetIndex).isDefined
      val current = rows(targetIndex).getOrElse(Row())
      val result = LoadInflightRowMutationPathReference(
        sourceStoreEntries = storeEntries,
        storeEntries = storeEntries,
        enable = true,
        flush = false,
        requestValid = true,
        rowValid = rowValid,
        row = current,
        request = request,
        conflicts = conflicts)
      if (result.applyResult.applyValid) {
        rows(targetIndex) = Some(result.applyResult.nextRow)
      }
      result
    }

    private def mask(bits: Seq[Boolean]): Int =
      bits.zipWithIndex.foldLeft(0) { case (acc, (bit, index)) => if (bit) acc | (1 << index) else acc }

    private def query(row: Row): Query = {
      val offset = (row.alloc.addr & 0x3f).toInt
      val firstSize = 64 - offset
      val second = row.crossLine && row.secondSegmentActive
      Query(
        valid = true,
        lineAddr = (row.alloc.addr & ~BigInt(0x3f)) + (if (second) 64 else 0),
        byteOffset = if (second) 0 else offset,
        size = if (second) row.alloc.size - firstSize else if (row.crossLine) firstSize else row.alloc.size,
        youngestStoreId = row.alloc.youngestStoreId,
        youngestStoreLsId = row.alloc.youngestStoreLsId,
        isTile = row.alloc.isTile
      )
    }

    def allocate(alloc: Alloc): AllocResult = {
      val id = Id(valid = true, wrap = allocWrap, value = allocPtr)
      if (rows(allocPtr).isDefined) {
        return AllocResult(accepted = false, index = allocPtr, loadId = id)
      }

      val offset = (alloc.addr & 0x3f).toInt
      val crossLine = !alloc.isTile && alloc.size != 0 && offset + alloc.size > 64
      rows(allocPtr) = Some(Row(status = Wait, loadId = id, alloc = alloc, crossLine = crossLine))
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
          val baseData = if (row.validMask != 0) row.lineData else input.baseData
          val baseValidMask = if (row.validMask != 0) row.validMask else input.baseValidMask
          rows(index) = Some(row.copy(status = Repick, missKind = NoMiss))
          val e4 = pipe.step(Some(Input(
            query = query(row),
            stores = input.stores,
            baseData = baseData,
            baseValidMask = baseValidMask,
            loadDataReturned = input.loadDataReturned,
            scbReturned = input.scbReturned,
            stqReturned = input.stqReturned,
            returnReady = input.returnReady
          )))
          require(e4.e4.isEmpty, "single-cycle tests should not launch over a resident E3")
          e3Index = Some(index)
          true
        case _ =>
          false
      }

    def pick(index: Int): Boolean =
      rows(index) match {
        case Some(row) if row.status == Wait && row.waitStore.isEmpty =>
          rows(index) = Some(row.copy(status = Repick, missKind = NoMiss))
          true
        case _ =>
          false
      }

    def scbReturn(index: Int): Boolean =
      rows(index) match {
        case Some(row) if row.status == Repick && !row.scbReturned =>
          rows(index) = Some(row.copy(scbReturned = true))
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
            scbReturned = e4.scbReturned,
            stqReturned = e4.stqReturned,
            missKind = e4.missKind
          )

          e4.missKind match {
            case NoMiss if e4.wakeupValid =>
              if (old.crossLine && !old.secondSegmentActive) {
                rows(index) = Some(updated.copy(
                  status = Wait,
                  lineData = 0,
                  validMask = 0,
                  loadByteMask = 0,
                  forwardMask = 0,
                  waitMask = 0,
                  secondSegmentActive = true,
                  firstSegmentDone = true,
                  firstLineData = e4.lineData,
                  firstValidMask = e4.validMask,
                  firstLoadByteMask = e4.loadByteMask,
                  firstForwardMask = e4.forwardMask,
                  waitStore = None,
                  dataComplete = false,
                  sourcesReturned = false,
                  scbReturned = false,
                  stqReturned = false,
                  l1Hit = false,
                  l1Miss = false,
                  missKind = NoMiss))
                CycleResult(Some(index))
              } else {
                val row = updated.copy(status = Repick, waitStore = None, l1Hit = false, l1Miss = false)
                rows(index) = Some(row)
                CycleResult(Some(index), Some(HitRecord(row, e4.loadByteMask, e4.lineData, e4.forwardMask)))
              }
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
                sourcesReturned = false,
                scbReturned = false,
                stqReturned = false,
                l1Hit = false
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
                scbReturned = false,
                stqReturned = false,
                l1Hit = false,
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
                sourcesReturned = false,
                scbReturned = false,
                stqReturned = false,
                l1Hit = false
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
        case Some(row) if row.status == Resolved ||
            (row.status == Repick && row.dataComplete && row.sourcesReturned &&
              row.scbReturned && row.stqReturned && row.waitStore.isEmpty) =>
          rows(index) = None
          true
        case _ =>
          false
      }

    def replayWakeup(wakeup: LoadReplayWakeupReference.Wakeup): ReplayWakeResult = {
      var waitStoreClearMask = 0
      var mergeMask = 0
      var completedMask = 0

      for (index <- rows.indices) {
        rows(index) match {
          case Some(row) =>
            val result = LoadReplayWakeupReference(row, wakeup)
            var next = row
            if (result.waitStoreClear) {
              waitStoreClearMask |= (1 << index)
              next = next.copy(waitStore = None)
            }
            if (result.merge) {
              mergeMask |= (1 << index)
              next = next.copy(
                lineData = result.mergedLineData,
                validMask = result.mergedValidMask,
                loadByteMask = result.requestByteMask)
              if (result.completed) {
                completedMask |= (1 << index)
                next = next.copy(
                  status = Wait,
                  storeBypass = true,
                  dataComplete = true,
                  sourcesReturned = true,
                  scbReturned = next.scbReturned || wakeup.source == LoadReplayWakeupReference.StoreCoalescingBuffer,
                  stqReturned = next.stqReturned || wakeup.source == LoadReplayWakeupReference.StoreUnit,
                  missKind = NoMiss)
              }
            }
            rows(index) = Some(next)
          case None =>
        }
      }

      ReplayWakeResult(waitStoreClearMask, mergeMask, completedMask)
    }

    def refillWakeup(refill: LoadRefillWakeupReference.Refill): RefillWakeResult = {
      var wakeMask = 0

      for (index <- rows.indices) {
        rows(index) match {
          case Some(row) =>
            val result = LoadRefillWakeupReference(row, refill)
            if (result.wake) {
              wakeMask |= (1 << index)
              rows(index) = Some(row.copy(
                status = Wait,
                lineData = refill.data,
                validMask = result.lineValidMask,
                loadByteMask = result.requestByteMask,
                forwardMask = 0,
                waitMask = 0,
                l1Hit = true,
                dataComplete = false,
                sourcesReturned = false,
                scbReturned = false,
                stqReturned = false,
                missKind = NoMiss))
            }
          case None =>
        }
      }

      RefillWakeResult(refillAccepted = refill.isRead, wakeMask = wakeMask)
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
  import LoadInflightRowMutationRequestBridgeReference.{Request => RowMutationRequest}
  import LoadInflightRowMutationWriteControlReference.Conflicts
  import LoadRefillWakeupReference.Refill
  import LoadReplayWakeupReference.{StoreUnit, Wakeup}
  import LoadStoreForwardingReference.{Store, byteMask, lineData}
  import STQFlushPruneReference.Id

  private def alloc(n: Int, addr: BigInt = 0x1000, size: Int = 4, youngestStore: Int = 7): Alloc =
    Alloc(
      bid = Id(value = n),
      gid = Id(value = 0),
      rid = Id(value = n),
      loadLsId = Id(value = n),
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
    assert(liq.row(0).exists(_.alloc.loadLsId.value == 0))
    assert(liq.row(1).exists(_.alloc.loadLsId.value == 1))
    assert(liq.occupiedMask == 0x3)
  }

  test("E4 hit publishes an LHQ record while the LIQ row remains repick-owned") {
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
    assert(row.status == Repick)
    assert(row.dataComplete)
    assert(row.sourcesReturned)
    assert(row.scbReturned)
    assert(row.stqReturned)
    assert(row.forwardMask == byteMask(4, 4))
    assert(result.hitRecord.exists(r => r.row.loadId.value == rowIndex && r.byteMask == byteMask(4, 4)))
    assert(result.hitRecord.exists(_.row.alloc.loadLsId.value == 0))
    assert(liq.repickMask == (1 << rowIndex))
    assert(liq.resolvedMask == 0)
  }

  test("cross-line E4 phases retain one row and publish only after the second line") {
    val liq = new Model(entries = 4)
    val rowIndex = liq.allocate(alloc(0, addr = 0x103e, size = 4)).index
    val fullMask = (BigInt(1) << 64) - 1
    val firstData = lineData(Map(62 -> 0x3e, 63 -> 0x3f))
    val secondData = lineData(Map(0 -> 0x80, 1 -> 0x81))

    assert(liq.row(rowIndex).exists(row => row.crossLine && !row.secondSegmentActive))
    assert(liq.launch(rowIndex, LaunchInput(baseData = firstData, baseValidMask = fullMask)))
    val first = liq.cycle()
    val held = liq.row(rowIndex).get

    assert(first.hitRecord.isEmpty)
    assert(held.status == Wait)
    assert(held.secondSegmentActive && held.firstSegmentDone)
    assert(held.firstLoadByteMask == byteMask(62, 2))
    assert(held.firstLineData == firstData)

    assert(liq.launch(rowIndex, LaunchInput(baseData = secondData, baseValidMask = fullMask)))
    val second = liq.cycle()
    assert(second.hitRecord.exists(_.byteMask == byteMask(0, 2)))
    assert(liq.row(rowIndex).exists(row => row.status == Repick && row.secondSegmentActive))
  }

  test("pick moves a WAIT row to REPICK without launching E4 forwarding") {
    val liq = new Model(entries = 4)
    val rowIndex = liq.allocate(alloc(0, addr = 0x1004, size = 4, youngestStore = 5)).index

    assert(liq.pick(rowIndex))
    assert(liq.row(rowIndex).exists(_.status == Repick))
    val result = liq.cycle()

    assert(result.e4Index.isEmpty)
    assert(result.hitRecord.isEmpty)
    assert(liq.row(rowIndex).exists(row => row.status == Repick && row.missKind == LoadForwardPipelineReference.NoMiss))
  }

  test("pick rejects wait-store and non-WAIT rows") {
    val waitStoreLiq = new Model(entries = 4)
    val waitStoreIndex = waitStoreLiq.allocate(alloc(0, addr = 0x1008, size = 2, youngestStore = 6)).index
    val store = Store(
      index = 1,
      dataReady = false,
      pc = 0x3450,
      storeId = Id(value = 4),
      storeLsIdFull = BigInt("8000000001", 16),
      byteMask = byteMask(8, 2),
      data = lineData(Map(8 -> 0x11, 9 -> 0x22))
    )
    assert(waitStoreLiq.launch(waitStoreIndex, LaunchInput(stores = Seq(store))))
    waitStoreLiq.cycle()
    assert(!waitStoreLiq.pick(waitStoreIndex))
    assert(waitStoreLiq.row(waitStoreIndex).exists(row => row.status == Wait && row.waitStore.nonEmpty))

    val resolvedLiq = new Model(entries = 4)
    val resolvedIndex = resolvedLiq.allocate(alloc(1, addr = 0x1000)).index
    assert(resolvedLiq.launch(resolvedIndex, LaunchInput(baseValidMask = byteMask(0, 4))))
    resolvedLiq.cycle()
    assert(resolvedLiq.row(resolvedIndex).exists(_.status == Repick))
    assert(!resolvedLiq.pick(resolvedIndex))
  }

  test("SCB return marks an admitted REPICK row without mutating STQ return state") {
    val liq = new Model(entries = 4)
    val rowIndex = liq.allocate(alloc(0, addr = 0x1004, size = 4, youngestStore = 5)).index

    assert(liq.pick(rowIndex))
    assert(liq.scbReturn(rowIndex))
    assert(liq.row(rowIndex).exists(row =>
      row.status == Repick && row.scbReturned && !row.stqReturned && !row.sourcesReturned))
    assert(!liq.scbReturn(rowIndex))

    val waitLiq = new Model(entries = 4)
    val waitIndex = waitLiq.allocate(alloc(1, addr = 0x1040)).index
    assert(!waitLiq.scbReturn(waitIndex))
    assert(waitLiq.row(waitIndex).exists(row => row.status == Wait && !row.scbReturned))
  }

  test("store-data-not-ready returns to WAIT with wait-store diagnostics") {
    val liq = new Model(entries = 4)
    val rowIndex = liq.allocate(alloc(0, addr = 0x1008, size = 2, youngestStore = 6)).index
    val store = Store(
      index = 1,
      dataReady = false,
      pc = 0x3450,
      storeId = Id(value = 4),
      storeLsIdFull = BigInt("8000000001", 16),
      byteMask = byteMask(8, 2),
      data = lineData(Map(8 -> 0x11, 9 -> 0x22))
    )

    assert(liq.launch(rowIndex, LaunchInput(stores = Seq(store))))
    liq.cycle()
    val row = liq.row(rowIndex).get

    assert(row.status == Wait)
    assert(row.waitStore.exists(_.pc == 0x3450))
    assert(row.waitStore.exists(_.storeLsIdFull == BigInt("8000000001", 16)))
    assert(liq.waitStoreMask == (1 << rowIndex))
    assert(row.validMask == 0)
    assert(!row.scbReturned)
    assert(!row.stqReturned)
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
    assert(missingScb.row(scbIndex).exists(row => !row.scbReturned && !row.stqReturned))
    assert(scbResult.hitRecord.isEmpty)

    val missingStq = new Model(entries = 4)
    val stqIndex = missingStq.allocate(alloc(0, addr = 0x1000, size = 4)).index
    assert(missingStq.launch(stqIndex, LaunchInput(baseValidMask = byteMask(0, 4), stqReturned = false)))
    val stqResult = missingStq.cycle()
    assert(missingStq.row(stqIndex).exists(row => row.status == Wait && row.missKind == AwaitingSources))
    assert(missingStq.row(stqIndex).exists(row => !row.scbReturned && !row.stqReturned))
    assert(stqResult.hitRecord.isEmpty)

    val blockedReturn = new Model(entries = 4)
    val retIndex = blockedReturn.allocate(alloc(0, addr = 0x1000, size = 4)).index
    assert(blockedReturn.launch(retIndex, LaunchInput(baseValidMask = byteMask(0, 4), returnReady = false)))
    val retResult = blockedReturn.cycle()
    assert(blockedReturn.row(retIndex).exists(row => row.status == Wait && row.missKind == ReturnPortBlocked))
    assert(retResult.hitRecord.isEmpty)
  }

  test("store wakeup clears wait-store row and completed miss row returns to WAIT") {
    val waitLiq = new Model(entries = 4)
    val waitIndex = waitLiq.allocate(alloc(0, addr = 0x1008, size = 2, youngestStore = 6)).index
    val waitStore = Store(
      index = 1,
      dataReady = false,
      pc = 0x3450,
      storeId = Id(value = 4),
      byteMask = byteMask(8, 2),
      data = lineData(Map(8 -> 0x11, 9 -> 0x22))
    )
    assert(waitLiq.launch(waitIndex, LaunchInput(stores = Seq(waitStore))))
    waitLiq.cycle()

    val clear = waitLiq.replayWakeup(Wakeup(source = StoreUnit, storeId = Id(value = 4), pc = 0x3450, lineAddr = 0x1000))
    assert(clear.waitStoreClearMask == (1 << waitIndex))
    assert(waitLiq.row(waitIndex).exists(row => row.status == Wait && row.waitStore.isEmpty))

    val missLiq = new Model(entries = 4)
    val missIndex = missLiq.allocate(alloc(0, addr = 0x1008, size = 2, youngestStore = 6)).index
    assert(missLiq.launch(missIndex, LaunchInput(baseValidMask = byteMask(8, 1))))
    missLiq.cycle()
    assert(missLiq.row(missIndex).exists(_.status == L1DcMiss))

    val complete = missLiq.replayWakeup(Wakeup(
      source = StoreUnit,
      storeId = Id(value = 4),
      lineAddr = 0x1000,
      validMask = byteMask(8, 2),
      data = lineData(Map(8 -> 0xaa, 9 -> 0xbb))))
    val row = missLiq.row(missIndex).get

    assert(complete.mergeMask == (1 << missIndex))
    assert(complete.completedMask == (1 << missIndex))
    assert(row.status == Wait)
    assert(row.storeBypass)
    assert(row.dataComplete)
    assert(!row.scbReturned)
    assert(row.stqReturned)
    assert(!missLiq.missPending)
  }

  test("L1 refill wakes miss row and relaunch uses row-owned line data") {
    val liq = new Model(entries = 4)
    val rowIndex = liq.allocate(alloc(0, addr = 0x1008, size = 2)).index

    assert(liq.launch(rowIndex, LaunchInput(baseValidMask = byteMask(8, 1))))
    liq.cycle()
    assert(liq.row(rowIndex).exists(row => row.status == L1DcMiss && row.l1Miss))
    assert(liq.missPending)

    val refill = liq.refillWakeup(Refill(
      isRead = true,
      lineAddr = 0x1000,
      data = lineData(Map(8 -> 0xaa, 9 -> 0xbb))))
    val woken = liq.row(rowIndex).get

    assert(refill.refillAccepted)
    assert(refill.wakeMask == (1 << rowIndex))
    assert(woken.status == Wait)
    assert(woken.l1Hit)
    assert(!woken.scbReturned)
    assert(!woken.stqReturned)
    assert(woken.validMask == ((BigInt(1) << 64) - 1))
    assert(!liq.missPending)

    assert(liq.launch(rowIndex, LaunchInput()))
    val result = liq.cycle()
    val resolved = liq.row(rowIndex).get

    assert(result.hitRecord.exists(_.byteMask == byteMask(8, 2)))
    assert(resolved.status == Repick)
    assert(resolved.dataComplete)
    assert((resolved.lineData & lineData(Map(8 -> 0xff, 9 -> 0xff))) == lineData(Map(8 -> 0xaa, 9 -> 0xbb)))
  }

  test("complete repick rows clear before allocation wraps back into their slot") {
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

  test("typed precise flush prunes matching load rows and rebases allocation") {
    val liq = new Model(entries = 4)
    liq.allocate(alloc(0).copy(bid = Id(value = 1), loadLsId = Id(value = 1), peId = 2, stid = 3, tid = 4))
    liq.allocate(alloc(1).copy(bid = Id(value = 1), loadLsId = Id(value = 2), peId = 2, stid = 3, tid = 4))
    liq.allocate(alloc(2).copy(bid = Id(value = 2), loadLsId = Id(value = 0), peId = 2, stid = 3, tid = 4))
    liq.allocate(alloc(3).copy(bid = Id(value = 3), loadLsId = Id(value = 0), peId = 2, stid = 7, tid = 4))

    val pruned = liq.preciseFlush(STQFlushPruneReference.Flush(
      stid = 3,
      peId = 2,
      tid = 4,
      bid = Id(value = 1),
      lsId = Id(value = 2),
      baseOnPE = true,
      baseOnThread = true))

    assert(pruned == 0x6)
    assert(liq.residentCount == 2)
    assert(liq.row(0).nonEmpty)
    assert(liq.row(1).isEmpty)
    assert(liq.row(2).isEmpty)
    assert(liq.row(3).nonEmpty)
    val replacement = liq.allocate(alloc(4, addr = 0x1100))
    assert(replacement.accepted && replacement.index == 1 && replacement.loadId.wrap)
  }

  test("native row mutation writes an admitted repick row image") {
    val liq = new Model(entries = 4)
    val rowIndex = 1
    val row = Row(
      status = Repick,
      loadId = Id(valid = true, value = rowIndex),
      alloc = alloc(4, addr = 0x1008, size = 2),
      lineData = lineData(Map(8 -> 0x11)),
      validMask = byteMask(8, 1),
      loadByteMask = byteMask(8, 2),
      dataComplete = true,
      sourcesReturned = true,
      scbReturned = true,
      stqReturned = false)
    liq.installRow(rowIndex, row)

    val result = liq.rowMutation(
      targetIndex = rowIndex,
      request = RowMutationRequest(
        targetMask = 1 << rowIndex,
        targetIndex = rowIndex,
        setWaitStatus = true,
        clearReturnState = true,
        lineWrite = true,
        waitStoreWrite = true,
        nextWaitStore = true,
        sourceStoreIndex = 2,
        nextLineData = 0,
        nextValidMask = 0))
    val next = liq.row(rowIndex).get

    assert(result.bridge.bridgeValid)
    assert(result.control.writeEnable)
    assert(result.applyResult.applyValid)
    assert(next.status == Wait)
    assert(next.waitStore.exists(_.index == 2))
    assert(!next.sourcesReturned)
    assert(!next.scbReturned)
    assert(!next.stqReturned)
    assert(!next.dataComplete)
    assert(next.loadId == row.loadId)
    assert(next.alloc.pc == row.alloc.pc)
  }

  test("native row mutation blocks SCB-missing rows and same-row writer conflicts") {
    val noScb = new Model(entries = 4)
    val rowIndex = 1
    val repickWithoutScb = Row(
      status = Repick,
      loadId = Id(valid = true, value = rowIndex),
      alloc = alloc(5, addr = 0x1040),
      scbReturned = false)
    noScb.installRow(rowIndex, repickWithoutScb)
    val request = RowMutationRequest(
      targetMask = 1 << rowIndex,
      targetIndex = rowIndex,
      keepRepickStatus = true,
      lineWrite = true,
      nextLineData = lineData(Map(0 -> 0xaa)),
      nextValidMask = byteMask(0, 1),
      nextDataComplete = true,
      nextScbReturned = true,
      nextStqReturned = true,
      nextStoreSourceReturned = true)

    val blockedByScb = noScb.rowMutation(rowIndex, request)
    assert(blockedByScb.control.blockedByScbNotReturned)
    assert(!blockedByScb.applyResult.applyValid)
    assert(noScb.row(rowIndex).contains(repickWithoutScb))

    val conflict = new Model(entries = 4)
    val repickWithScb = repickWithoutScb.copy(scbReturned = true)
    conflict.installRow(rowIndex, repickWithScb)
    val blockedByWriter = conflict.rowMutation(
      targetIndex = rowIndex,
      request = request,
      conflicts = Conflicts(e4Update = true))

    assert(blockedByWriter.control.blockedByE4UpdateConflict)
    assert(blockedByWriter.control.writeConflict)
    assert(!blockedByWriter.applyResult.applyValid)
    assert(conflict.row(rowIndex).contains(repickWithScb))
  }

  test("Chisel LoadInflightQueue elaborates with pipeline, row, and LHQ outputs") {
    val sv = ChiselStage.emitSystemVerilog(new LoadInflightQueue(liqEntries = 4, idEntries = 8, storeEntries = 4))

    assert(sv.contains("module LoadInflightQueue"))
    assert(sv.contains("io_pickValid"))
    assert(sv.contains("io_pickReady"))
    assert(sv.contains("io_pickAccepted"))
    assert(sv.contains("io_scbReturnValid"))
    assert(sv.contains("io_scbReturnIndex"))
    assert(sv.contains("io_scbReturnReady"))
    assert(sv.contains("io_scbReturnAccepted"))
    assert(sv.contains("LoadForwardPipeline"))
    assert(sv.contains("io_lhqRecordValid"))
    assert(sv.contains("io_lhqRecord_loadLsId_value"))
    assert(sv.contains("io_lhqRecord_pc"))
    assert(sv.contains("io_alloc_loadLsId_value"))
    assert(sv.contains("io_alloc_dst_physTag"))
    assert(sv.contains("io_e2StqReturned"))
    assert(sv.contains("io_e4UpdateValid"))
    assert(sv.contains("io_missPending"))
    assert(sv.contains("LoadReplayWakeup"))
    assert(sv.contains("io_replayWakeCompletedMask"))
    assert(sv.contains("LoadRefillWakeup"))
    assert(sv.contains("io_refillWakeMask"))
    assert(sv.contains("LoadInflightRowMutationPath"))
    assert(sv.contains("io_rowMutationWriteEnable"))
    assert(sv.contains("io_rowMutationControlBlockedByE4UpdateConflict"))
    assert(sv.contains("io_rows_0_status"))
    assert(sv.contains("io_rows_0_loadLsId_value"))
    assert(sv.contains("io_rows_0_dst_physTag"))
    assert(sv.contains("io_rows_0_scbReturned"))
    assert(sv.contains("io_rows_0_stqReturned"))
    assert(sv.contains("io_preciseFlush_req_valid"))
    assert(sv.contains("io_flushPruneMask"))
    assert(sv.contains("io_alloc_stid"))
    assert(sv.contains("io_rows_0_stid"))
  }
}
