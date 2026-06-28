package linxcore.recovery

import chisel3._

import linxcore.rob.ROBID

object FlushType extends ChiselEnum {
  val MissPredFlush, PeReplay, NukeFlush, InnerFlush, FastReplay, FastFlush, SimtInnerFlush = Value
}

object ExecEngineType extends ChiselEnum {
  val Scalar, Simt, Mem, IexNumOrHigher = Value
}

class FlushReq(val entries: Int, val peIdWidth: Int = 8, val stidWidth: Int = 8, val tidWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val typ = FlushType()
  val peId = UInt(peIdWidth.W)
  val tid = UInt(tidWidth.W)
  val stid = UInt(stidWidth.W)
  val bid = new ROBID(entries)
  val gid = new ROBID(entries)
  val rid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val execEngine = ExecEngineType()
  val fetchTpcValid = Bool()
  val immediateFlush = Bool()
}

class FlushBus(val entries: Int, val peIdWidth: Int = 8, val stidWidth: Int = 8, val tidWidth: Int = 8)
    extends Bundle {
  val req = new FlushReq(entries, peIdWidth, stidWidth, tidWidth)
  val baseOnBid = Bool()
  val baseOnGroup = Bool()
  val baseOnPE = Bool()
  val baseOnThread = Bool()
  val simtReplay = Bool()
  val mtcReplay = Bool()
}

object FlushControl {
  def isFlushType(typ: FlushType.Type): Bool =
    (typ === FlushType.MissPredFlush) ||
      (typ === FlushType.NukeFlush) ||
      (typ === FlushType.InnerFlush) ||
      (typ === FlushType.FastFlush)

  def isBaseOnBid(req: FlushReq): Bool =
    (req.typ === FlushType.MissPredFlush) ||
      (req.typ === FlushType.NukeFlush) ||
      (req.typ === FlushType.FastReplay) ||
      (req.typ === FlushType.FastFlush) ||
      ((req.typ === FlushType.PeReplay) && !req.fetchTpcValid)

  def isBasedOnPE(req: FlushReq): Bool =
    (req.typ === FlushType.PeReplay) || (req.execEngine =/= ExecEngineType.Scalar)

  def isBasedOnThread(req: FlushReq): Bool =
    req.execEngine =/= ExecEngineType.Scalar

  def isBasedOnGroup(req: FlushReq): Bool =
    req.typ === FlushType.SimtInnerFlush

  def isNeedSimtReplay(req: FlushReq): Bool =
    (req.execEngine === ExecEngineType.Simt) || (req.execEngine === ExecEngineType.IexNumOrHigher)

  def isNeedMtcReplay(req: FlushReq): Bool =
    req.execEngine === ExecEngineType.Mem

  def annotate(req: FlushReq): FlushBus = {
    val out = Wire(new FlushBus(req.entries, req.peIdWidth, req.stidWidth, req.tidWidth))
    out.req := req
    out.baseOnBid := isBaseOnBid(req)
    out.baseOnGroup := isBasedOnGroup(req)
    out.baseOnPE := isBasedOnPE(req)
    out.baseOnThread := isBasedOnThread(req)
    out.simtReplay := isNeedSimtReplay(req)
    out.mtcReplay := isNeedMtcReplay(req)
    out
  }

  def lessEqualBidRid(srcBid: ROBID, srcRid: ROBID, dstBid: ROBID, dstRid: ROBID): Bool =
    ROBID.less(srcBid, dstBid) || (ROBID.equal(srcBid, dstBid) && ROBID.lessEqual(srcRid, dstRid))

  def checkOlder(srcSignal: FlushBus, dstSignal: FlushBus, oldestBid: ROBID): Bool = {
    val srcType = srcSignal.req.typ
    val dstType = dstSignal.req.typ
    val sameStid = srcSignal.req.stid === dstSignal.req.stid
    val baseOnBid = srcSignal.baseOnBid || dstSignal.baseOnBid
    val result = WireDefault(false.B)

    when(sameStid) {
      when(baseOnBid && ROBID.equal(srcSignal.req.bid, dstSignal.req.bid)) {
        result := (srcType === FlushType.MissPredFlush) ||
          ((srcType === FlushType.NukeFlush) && (dstType === FlushType.InnerFlush)) ||
          ((srcType === FlushType.NukeFlush) && (dstType === FlushType.PeReplay)) ||
          ((srcType === FlushType.FastReplay) && (dstType === FlushType.PeReplay))
      }.elsewhen(!baseOnBid && ROBID.equal(srcSignal.req.bid, dstSignal.req.bid) && ROBID.equal(srcSignal.req.rid, dstSignal.req.rid)) {
        result := ((srcType === FlushType.InnerFlush) && (dstType === FlushType.PeReplay)) ||
          ((srcType === FlushType.PeReplay) && (dstType === FlushType.InnerFlush)) ||
          ((srcType === FlushType.InnerFlush) && (dstType === FlushType.InnerFlush))
      }.elsewhen(srcType === FlushType.PeReplay) {
        when(dstType === FlushType.MissPredFlush) {
          result := false.B
        }.elsewhen(dstType === FlushType.FastReplay) {
          result := true.B
        }.elsewhen(dstType === FlushType.PeReplay) {
          result := (srcSignal.req.peId === dstSignal.req.peId) &&
            Mux(
              baseOnBid,
              ROBID.lessEqual(srcSignal.req.bid, dstSignal.req.bid),
              lessEqualBidRid(srcSignal.req.bid, srcSignal.req.rid, dstSignal.req.bid, dstSignal.req.rid)
            )
        }.otherwise {
          result := Mux(
            baseOnBid,
            ROBID.lessEqual(srcSignal.req.bid, dstSignal.req.bid) || ROBID.equal(srcSignal.req.bid, oldestBid),
            lessEqualBidRid(srcSignal.req.bid, srcSignal.req.rid, dstSignal.req.bid, dstSignal.req.rid)
          )
        }
      }.otherwise {
        result := Mux(
          baseOnBid,
          ROBID.lessEqual(srcSignal.req.bid, dstSignal.req.bid) || ROBID.equal(srcSignal.req.bid, oldestBid),
          lessEqualBidRid(srcSignal.req.bid, srcSignal.req.rid, dstSignal.req.bid, dstSignal.req.rid)
        )
      }
    }

    result
  }

  def needFlush(signal: FlushBus): Bool =
    signal.req.valid

  def needFlush(signal: FlushBus, bid: ROBID): Bool =
    signal.req.valid && ROBID.lessEqual(signal.req.bid, bid)

  def needFlush(signal: FlushBus, bid: ROBID, subId: ROBID): Bool =
    signal.req.valid &&
      Mux(
        signal.baseOnBid,
        ROBID.lessEqual(signal.req.bid, bid),
        lessEqualBidRid(signal.req.bid, signal.req.lsId, bid, subId)
      )
}

class FlushOlderSelectorIO(val entries: Int, val peIdWidth: Int = 8, val stidWidth: Int = 8, val tidWidth: Int = 8)
    extends Bundle {
  val src = Input(new FlushBus(entries, peIdWidth, stidWidth, tidWidth))
  val dst = Input(new FlushBus(entries, peIdWidth, stidWidth, tidWidth))
  val oldestBid = Input(new ROBID(entries))
  val srcOlder = Output(Bool())
}

class FlushOlderSelector(val entries: Int, val peIdWidth: Int = 8, val stidWidth: Int = 8, val tidWidth: Int = 8)
    extends Module {
  val io = IO(new FlushOlderSelectorIO(entries, peIdWidth, stidWidth, tidWidth))
  io.srcOlder := FlushControl.checkOlder(io.src, io.dst, io.oldestBid)
}
