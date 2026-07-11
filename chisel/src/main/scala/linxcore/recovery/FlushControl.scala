package linxcore.recovery

import chisel3._

import linxcore.bctrl.BID
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
  val fetchTpc = UInt(64.W)
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

class FullBidFlushReq(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val typ = FlushType()
  val peId = UInt(peIdWidth.W)
  val tid = UInt(tidWidth.W)
  val stid = UInt(stidWidth.W)
  val blockBid = UInt(bidWidth.W)
  val gid = new ROBID(entries)
  val rid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val execEngine = ExecEngineType()
  val fetchTpcValid = Bool()
  val fetchTpc = UInt(64.W)
  val immediateFlush = Bool()
}

class FullBidRecoveryBridgeIO(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val req = Input(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val robFlush = Output(new FlushBus(entries, peIdWidth, stidWidth, tidWidth))
  val blockFlushValid = Output(Bool())
  val blockFlushBid = Output(UInt(bidWidth.W))
  val robBid = Output(new ROBID(entries))
  val baseOnBid = Output(Bool())
}

object FullBidRecoveryBridge {
  def fullBidToRobId(blockBid: UInt, valid: Bool, entries: Int, bidWidth: Int = BID.DefaultWidth): ROBID = {
    require(entries > 1, "recovery bridge entries must be greater than one")
    require((entries & (entries - 1)) == 0, "recovery bridge entries must be a power of two")
    require(bidWidth > BID.slotBits(entries), "full BID width must include uniqueness bits above the slot")

    val id = Wire(new ROBID(entries))
    id.valid := valid
    id.wrap := BID.uniq(blockBid, entries, bidWidth)(0)
    id.value := BID.slot(blockBid, entries)
    id
  }
}

class FullBidRecoveryBridge(
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  val io = IO(new FullBidRecoveryBridgeIO(entries, bidWidth, peIdWidth, stidWidth, tidWidth))

  val robReq = Wire(new FlushReq(entries, peIdWidth, stidWidth, tidWidth))
  robReq := 0.U.asTypeOf(robReq)
  robReq.valid := io.req.valid
  robReq.typ := io.req.typ
  robReq.peId := io.req.peId
  robReq.tid := io.req.tid
  robReq.stid := io.req.stid
  robReq.bid := FullBidRecoveryBridge.fullBidToRobId(io.req.blockBid, io.req.valid, entries, bidWidth)
  robReq.gid := io.req.gid
  robReq.rid := io.req.rid
  robReq.lsId := io.req.lsId
  robReq.execEngine := io.req.execEngine
  robReq.fetchTpcValid := io.req.fetchTpcValid
  robReq.fetchTpc := io.req.fetchTpc
  robReq.immediateFlush := io.req.immediateFlush

  val annotated = FlushControl.annotate(robReq)
  io.robFlush := annotated
  io.blockFlushValid := io.req.valid
  io.blockFlushBid := io.req.blockBid
  io.robBid := robReq.bid
  io.baseOnBid := annotated.baseOnBid
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
