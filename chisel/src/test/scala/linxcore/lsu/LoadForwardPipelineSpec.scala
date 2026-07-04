package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadForwardPipelineReference {
  import LoadStoreForwardingReference.{Query, Store, forward}

  sealed trait MissKind
  case object NoMiss extends MissKind
  case object StoreDataNotReady extends MissKind
  case object DataNotComplete extends MissKind
  case object AwaitingSources extends MissKind
  case object ReturnPortBlocked extends MissKind

  final case class Input(
      query: Query,
      stores: Seq[Store] = Seq.empty,
      baseData: BigInt = 0,
      baseValidMask: BigInt = 0,
      loadDataReturned: Boolean = true,
      scbReturned: Boolean = true,
      returnReady: Boolean = true)

  final case class E3(
      loadByteMask: BigInt,
      forwardMask: BigInt,
      waitMask: BigInt,
      mergedData: BigInt,
      baseValidMask: BigInt,
      loadDataReturned: Boolean,
      scbReturned: Boolean,
      returnReady: Boolean,
      waitStore: Option[Store])

  final case class E4(
      lineData: BigInt,
      validMask: BigInt,
      loadByteMask: BigInt,
      forwardMask: BigInt,
      waitMask: BigInt,
      dataComplete: Boolean,
      loadDataReturned: Boolean,
      scbReturned: Boolean,
      sourcesReturned: Boolean,
      wakeupValid: Boolean,
      waitStore: Option[Store],
      missKind: MissKind)

  final case class Output(e3: Option[E3], e4: Option[E4])

  final class Model {
    private var e3: Option[E3] = None

    private def buildE3(input: Input): E3 = {
      val result = forward(input.query, input.stores, input.baseData)
      E3(
        loadByteMask = result.loadByteMask,
        forwardMask = result.forwardMask,
        waitMask = result.waitMask,
        mergedData = result.mergedData,
        baseValidMask = input.baseValidMask,
        loadDataReturned = input.loadDataReturned,
        scbReturned = input.scbReturned,
        returnReady = input.returnReady,
        waitStore = result.waitStore
      )
    }

    private def buildE4(stage: E3): E4 = {
      val validMask = stage.baseValidMask | stage.forwardMask
      val dataComplete = stage.loadByteMask != 0 && (validMask & stage.loadByteMask) == stage.loadByteMask
      val sourcesReturned = stage.loadDataReturned && stage.scbReturned
      val waitStoreBlocked = stage.waitMask != 0
      val wakeup = !waitStoreBlocked && dataComplete && sourcesReturned && stage.returnReady
      val miss =
        if (waitStoreBlocked) StoreDataNotReady
        else if (!dataComplete) DataNotComplete
        else if (!sourcesReturned) AwaitingSources
        else if (!stage.returnReady) ReturnPortBlocked
        else NoMiss

      E4(
        lineData = stage.mergedData,
        validMask = validMask,
        loadByteMask = stage.loadByteMask,
        forwardMask = stage.forwardMask,
        waitMask = stage.waitMask,
        dataComplete = dataComplete,
        loadDataReturned = stage.loadDataReturned,
        scbReturned = stage.scbReturned,
        sourcesReturned = sourcesReturned,
        wakeupValid = wakeup,
        waitStore = stage.waitStore,
        missKind = miss
      )
    }

    def step(input: Option[Input] = None, flush: Boolean = false): Output = {
      if (flush) {
        e3 = None
        Output(None, None)
      } else {
        val nextE4 = e3.map(buildE4)
        val nextE3 = input.map(buildE3)
        e3 = nextE3
        Output(nextE3, nextE4)
      }
    }
  }
}

class LoadForwardPipelineSpec extends AnyFunSuite {
  import LoadForwardPipelineReference._
  import LoadStoreForwardingReference.{Query, Store, byteMask, lineData}
  import STQFlushPruneReference.Id

  private def id(value: Int, wrap: Boolean = false): Id =
    Id(wrap = wrap, value = value)

  test("ready forwarded bytes register through E3 and wake at E4") {
    val model = new Model
    val query = Query(byteOffset = 4, size = 4, youngestStoreId = id(5))
    val store = Store(
      index = 0,
      storeId = id(3),
      byteMask = byteMask(4, 4),
      data = lineData(Map(4 -> 0xaa, 5 -> 0xbb, 6 -> 0xcc, 7 -> 0xdd))
    )

    val e3 = model.step(Some(Input(query = query, stores = Seq(store)))).e3
    assert(e3.exists(_.forwardMask == byteMask(4, 4)))
    assert(model.step().e4.exists { out =>
      out.wakeupValid &&
      out.dataComplete &&
      out.loadDataReturned &&
      out.scbReturned &&
      out.sourcesReturned &&
      out.missKind == NoMiss &&
      out.lineData == lineData(Map(4 -> 0xaa, 5 -> 0xbb, 6 -> 0xcc, 7 -> 0xdd))
    })
  }

  test("not-ready selected store reaches E4 as store-data replay") {
    val model = new Model
    val query = Query(byteOffset = 8, size = 2, youngestStoreId = id(4))
    val store = Store(
      index = 1,
      dataReady = false,
      pc = 0x3000,
      storeId = id(3),
      byteMask = byteMask(8, 2),
      data = lineData(Map(8 -> 0x11, 9 -> 0x22))
    )

    model.step(Some(Input(query = query, stores = Seq(store))))
    val e4 = model.step().e4.get

    assert(e4.waitMask == byteMask(8, 2))
    assert(!e4.wakeupValid)
    assert(e4.missKind == StoreDataNotReady)
    assert(e4.waitStore.map(_.pc).contains(0x3000))
  }

  test("uncovered bytes use base valid mask and classify incomplete data") {
    val model = new Model
    val query = Query(byteOffset = 0, size = 4, youngestStoreId = id(4))

    model.step(Some(Input(query = query, baseValidMask = byteMask(0, 2))))
    val e4 = model.step().e4.get

    assert(e4.validMask == byteMask(0, 2))
    assert(!e4.dataComplete)
    assert(!e4.wakeupValid)
    assert(e4.missKind == DataNotComplete)
  }

  test("source-return and return-port gates block E4 wakeup after data is complete") {
    val missingScb = new Model
    val query = Query(byteOffset = 0, size = 4, youngestStoreId = id(4))
    missingScb.step(Some(Input(query = query, baseValidMask = byteMask(0, 4), scbReturned = false)))
    assert(missingScb.step().e4.exists(out => !out.wakeupValid && !out.scbReturned && out.missKind == AwaitingSources))

    val blockedReturn = new Model
    blockedReturn.step(Some(Input(query = query, baseValidMask = byteMask(0, 4), returnReady = false)))
    assert(blockedReturn.step().e4.exists(out => !out.wakeupValid && out.missKind == ReturnPortBlocked))
  }

  test("flush clears resident E3 work before it can reach E4") {
    val model = new Model
    val query = Query(byteOffset = 0, size = 4, youngestStoreId = id(4))

    assert(model.step(Some(Input(query = query, baseValidMask = byteMask(0, 4)))).e3.nonEmpty)
    assert(model.step(flush = true).e4.isEmpty)
    assert(model.step().e4.isEmpty)
  }

  test("Chisel LoadForwardPipeline elaborates with E3/E4 forwarding and miss-kind outputs") {
    val sv = ChiselStage.emitSystemVerilog(new LoadForwardPipeline(robEntries = 8, storeEntries = 4))

    assert(sv.contains("module LoadForwardPipeline"))
    assert(sv.contains("LoadStoreForwarding"))
    assert(sv.contains("io_e3ForwardMask"))
    assert(sv.contains("io_e4WakeupValid"))
    assert(sv.contains("io_e4LoadDataReturned"))
    assert(sv.contains("io_e4ScbReturned"))
    assert(sv.contains("io_e4MissKind"))
    assert(sv.contains("io_e4WaitStore_valid"))
  }
}
