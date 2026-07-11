package linxcore.recovery

import org.scalatest.funsuite.AnyFunSuite

final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)

object FlushRefType extends Enumeration {
  val MissPredFlush, PeReplay, NukeFlush, InnerFlush, FastReplay, FastFlush, SimtInnerFlush = Value
}

object ExecRefType extends Enumeration {
  val Scalar, Simt, Mem, IexNumOrHigher = Value
}

final case class FlushReqRef(
    valid: Boolean = true,
    typ: FlushRefType.Value = FlushRefType.MissPredFlush,
    peId: Int = 0,
    tid: Int = 0,
    stid: Int = 0,
    bid: Id = Id(),
    gid: Id = Id(),
    rid: Id = Id(),
    lsId: Id = Id(),
    execEngine: ExecRefType.Value = ExecRefType.Scalar,
    fetchTpcValid: Boolean = false,
    immediateFlush: Boolean = false
)

final case class FlushBusRef(
    req: FlushReqRef,
    baseOnBid: Boolean = false,
    baseOnGroup: Boolean = false,
    baseOnPE: Boolean = false,
    baseOnThread: Boolean = false,
    simtReplay: Boolean = false,
    mtcReplay: Boolean = false
)

object FlushControlReference {
  def less(lhs: Id, rhs: Id): Boolean =
    if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

  def lessEqual(lhs: Id, rhs: Id): Boolean =
    less(lhs, rhs) || lhs == rhs

  def lessEqualBidRid(srcBid: Id, srcRid: Id, dstBid: Id, dstRid: Id): Boolean =
    less(srcBid, dstBid) || (srcBid == dstBid && lessEqual(srcRid, dstRid))

  def isFlushType(typ: FlushRefType.Value): Boolean =
    typ == FlushRefType.MissPredFlush ||
      typ == FlushRefType.NukeFlush ||
      typ == FlushRefType.InnerFlush ||
      typ == FlushRefType.FastFlush

  def annotate(req: FlushReqRef): FlushBusRef =
    FlushBusRef(
      req = req,
      baseOnBid = req.typ == FlushRefType.MissPredFlush ||
        req.typ == FlushRefType.NukeFlush ||
        req.typ == FlushRefType.FastReplay ||
        req.typ == FlushRefType.FastFlush ||
        (req.typ == FlushRefType.PeReplay && !req.fetchTpcValid),
      baseOnPE = req.typ == FlushRefType.PeReplay || req.execEngine != ExecRefType.Scalar,
      baseOnThread = req.execEngine != ExecRefType.Scalar,
      baseOnGroup = req.typ == FlushRefType.SimtInnerFlush,
      simtReplay = req.execEngine == ExecRefType.Simt || req.execEngine == ExecRefType.IexNumOrHigher,
      mtcReplay = req.execEngine == ExecRefType.Mem
    )

  def checkOlder(
      srcSignal: FlushBusRef,
      dstSignal: FlushBusRef,
      oldestBid: Id,
      oldestValid: Boolean = true): Boolean = {
    if (srcSignal.req.stid != dstSignal.req.stid) {
      return false
    }

    val srcType = srcSignal.req.typ
    val dstType = dstSignal.req.typ
    val baseOnBid = srcSignal.baseOnBid || dstSignal.baseOnBid

    if (baseOnBid && srcSignal.req.bid == dstSignal.req.bid) {
      return srcType == FlushRefType.MissPredFlush ||
        (srcType == FlushRefType.NukeFlush && dstType == FlushRefType.InnerFlush) ||
        (srcType == FlushRefType.NukeFlush && dstType == FlushRefType.PeReplay) ||
        (srcType == FlushRefType.FastReplay && dstType == FlushRefType.PeReplay)
    }

    if (!baseOnBid && srcSignal.req.bid == dstSignal.req.bid && srcSignal.req.rid == dstSignal.req.rid) {
      return (srcType == FlushRefType.InnerFlush && dstType == FlushRefType.PeReplay) ||
        (srcType == FlushRefType.PeReplay && dstType == FlushRefType.InnerFlush) ||
        (srcType == FlushRefType.InnerFlush && dstType == FlushRefType.InnerFlush)
    }

    if (srcType == FlushRefType.PeReplay) {
      if (dstType == FlushRefType.MissPredFlush) {
        return false
      }
      if (dstType == FlushRefType.FastReplay) {
        return true
      }
      if (dstType == FlushRefType.PeReplay) {
        return srcSignal.req.peId == dstSignal.req.peId &&
          (if (baseOnBid) lessEqual(srcSignal.req.bid, dstSignal.req.bid)
           else lessEqualBidRid(srcSignal.req.bid, srcSignal.req.rid, dstSignal.req.bid, dstSignal.req.rid))
      }
    }

    if (baseOnBid &&
      (lessEqual(srcSignal.req.bid, dstSignal.req.bid) || (oldestValid && srcSignal.req.bid == oldestBid))) {
      return true
    }

    !baseOnBid && lessEqualBidRid(srcSignal.req.bid, srcSignal.req.rid, dstSignal.req.bid, dstSignal.req.rid)
  }
}

class FlushControlSpec extends AnyFunSuite {
  import FlushControlReference._
  import FlushRefType._

  private val oldest = Id(value = 0)

  test("request classification follows LinxCoreModel getSignal helpers") {
    val miss = annotate(FlushReqRef(typ = MissPredFlush))
    assert(miss.baseOnBid)
    assert(!miss.baseOnPE)
    assert(isFlushType(miss.req.typ))

    val peReplayWithTarget = annotate(FlushReqRef(typ = PeReplay, fetchTpcValid = true))
    assert(!peReplayWithTarget.baseOnBid)
    assert(peReplayWithTarget.baseOnPE)

    val simtInner = annotate(FlushReqRef(typ = SimtInnerFlush, execEngine = ExecRefType.Simt))
    assert(simtInner.baseOnGroup)
    assert(simtInner.baseOnPE)
    assert(simtInner.baseOnThread)
    assert(simtInner.simtReplay)

    val mtc = annotate(FlushReqRef(typ = PeReplay, execEngine = ExecRefType.Mem))
    assert(mtc.mtcReplay)
  }

  test("same-BID conflicts use model priority") {
    val inner = annotate(FlushReqRef(typ = InnerFlush, bid = Id(value = 3)))
    val pe = annotate(FlushReqRef(typ = PeReplay, bid = Id(value = 3)))
    val nuke = annotate(FlushReqRef(typ = NukeFlush, bid = Id(value = 3)))
    val miss = annotate(FlushReqRef(typ = MissPredFlush, bid = Id(value = 3)))
    val fast = annotate(FlushReqRef(typ = FastReplay, bid = Id(value = 3)))

    assert(checkOlder(miss, inner, oldest))
    assert(checkOlder(nuke, inner, oldest))
    assert(checkOlder(nuke, pe, oldest))
    assert(checkOlder(fast, pe, oldest))
    assert(!checkOlder(inner, nuke, oldest))
  }

  test("same non-BID RID conflicts keep inner flush and PE replay mutually older") {
    val base = FlushReqRef(bid = Id(value = 4), rid = Id(value = 2), fetchTpcValid = true)
    val inner = annotate(base.copy(typ = InnerFlush))
    val pe = annotate(base.copy(typ = PeReplay))

    assert(!inner.baseOnBid)
    assert(!pe.baseOnBid)
    assert(checkOlder(inner, pe, oldest))
    assert(checkOlder(pe, inner, oldest))
    assert(checkOlder(inner, inner, oldest))
  }

  test("PE replay age only compares within the same PE except fast-replay cancellation") {
    val olderPe = annotate(FlushReqRef(typ = PeReplay, peId = 1, bid = Id(value = 2), rid = Id(value = 1), fetchTpcValid = true))
    val youngerSamePe = annotate(FlushReqRef(typ = PeReplay, peId = 1, bid = Id(value = 2), rid = Id(value = 3), fetchTpcValid = true))
    val youngerOtherPe = annotate(FlushReqRef(typ = PeReplay, peId = 2, bid = Id(value = 2), rid = Id(value = 3), fetchTpcValid = true))
    val miss = annotate(FlushReqRef(typ = MissPredFlush, bid = Id(value = 2)))
    val fastDifferentBid = annotate(FlushReqRef(typ = FastReplay, bid = Id(value = 3)))

    assert(checkOlder(olderPe, youngerSamePe, oldest))
    assert(!checkOlder(olderPe, youngerOtherPe, oldest))
    assert(!checkOlder(olderPe, miss, oldest))
    assert(checkOlder(olderPe, fastDifferentBid, oldest))
  }

  test("different STID never compares older") {
    val src = annotate(FlushReqRef(typ = MissPredFlush, stid = 0, bid = Id(value = 1)))
    val dst = annotate(FlushReqRef(typ = InnerFlush, stid = 1, bid = Id(value = 2)))
    assert(!checkOlder(src, dst, oldest))
  }

  test("oldest block special-case lets oldest BID win base-on-BID arbitration") {
    val src = annotate(FlushReqRef(typ = NukeFlush, bid = Id(value = 7)))
    val dst = annotate(FlushReqRef(typ = NukeFlush, bid = Id(value = 3)))
    assert(!lessEqual(src.req.bid, dst.req.bid))
    assert(checkOlder(src, dst, Id(value = 7)))
    assert(!checkOlder(src, dst, Id(value = 7), oldestValid = false))
  }
}
