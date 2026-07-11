package linxcore.recovery

import chisel3._
import chisel3.util.log2Ceil

import linxcore.bctrl.BID
import linxcore.rob.ROBID

class RecoverySourceArbiterIO(
    val sourceCount: Int,
    val stidCount: Int,
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  private val sourceIndexWidth = math.max(1, log2Ceil(sourceCount))
  private val stidIndexWidth = math.max(1, log2Ceil(stidCount))

  val sources = Input(Vec(sourceCount, new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth)))
  val sourceReady = Output(Vec(sourceCount, Bool()))
  val sourceAccepted = Output(Vec(sourceCount, Bool()))
  val sourceBlockedByStid = Output(Vec(sourceCount, Bool()))
  val oldestBid = Input(Vec(stidCount, new ROBID(entries)))

  val out = Output(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val outReady = Input(Bool())
  val outAccepted = Output(Bool())
  val selectedSourceValid = Output(Bool())
  val selectedSource = Output(UInt(sourceIndexWidth.W))
  val selectedStid = Output(UInt(stidIndexWidth.W))
  val pendingMask = Output(UInt(sourceCount.W))
  val lanePendingMask = Output(UInt(stidCount.W))
}

/** Retained report arbitration matching the LinxCoreModel same-STID age rules.
  *
  * Sources are independent one-entry queues. Requests from different STIDs are
  * deliberately not compared by BID; a round-robin lane selector serializes
  * their independently selected winners onto the cleanup path.
  */
class RecoverySourceArbiter(
    val sourceCount: Int,
    val stidCount: Int,
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  require(sourceCount > 0, "recovery arbiter must expose at least one source")
  require(stidCount > 0, "recovery arbiter must expose at least one STID lane")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "STID count must fit stidWidth")

  private val sourceIndexWidth = math.max(1, log2Ceil(sourceCount))
  private val stidIndexWidth = math.max(1, log2Ceil(stidCount))
  private val laneSumWidth = math.max(2, log2Ceil(stidCount * 2))

  val io = IO(new RecoverySourceArbiterIO(
    sourceCount,
    stidCount,
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

  val pending = RegInit(VecInit(Seq.fill(sourceCount)(false.B)))
  val requests = RegInit(VecInit(Seq.fill(sourceCount)(
    0.U.asTypeOf(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  )))
  val nextStid = RegInit(0.U(stidIndexWidth.W))

  val annotated = Wire(Vec(sourceCount, new FlushBus(entries, peIdWidth, stidWidth, tidWidth)))
  for (source <- 0 until sourceCount) {
    val bridge = Module(new FullBidRecoveryBridge(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
    val bridgeReq = Wire(chiselTypeOf(requests(source)))
    bridgeReq := requests(source)
    bridgeReq.valid := pending(source)
    bridge.io.req := bridgeReq
    annotated(source) := bridge.io.robFlush
  }

  val laneValid = Wire(Vec(stidCount, Bool()))
  val laneSource = Wire(Vec(stidCount, UInt(sourceIndexWidth.W)))
  for (lane <- 0 until stidCount) {
    var found = false.B
    var winner = 0.U(sourceIndexWidth.W)
    for (source <- 0 until sourceCount) {
      val belongs = pending(source) && (requests(source).stid === lane.U)
      val winnerRequest = if (sourceCount == 1) annotated(0) else annotated(winner)
      val winnerOlder = FlushControl.checkOlder(winnerRequest, annotated(source), io.oldestBid(lane))
      val replace = belongs && (!found || !winnerOlder)
      winner = Mux(replace, source.U, winner)
      found = found || belongs
    }
    laneValid(lane) := found
    laneSource(lane) := winner
  }

  val (selectedValid, selectedLane) = if (stidCount == 1) {
    (laneValid(0), 0.U(stidIndexWidth.W))
  } else {
    var found = false.B
    var winner = 0.U(stidIndexWidth.W)
    for (offset <- 0 until stidCount) {
      val sum = nextStid.pad(laneSumWidth) + offset.U(laneSumWidth.W)
      val wrapped = Mux(sum >= stidCount.U, sum - stidCount.U, sum)
      val lane = wrapped(stidIndexWidth - 1, 0)
      val take = !found && laneValid(lane)
      winner = Mux(take, lane, winner)
      found = found || laneValid(lane)
    }
    (found, winner)
  }

  val selectedSource = if (stidCount == 1) laneSource(0) else laneSource(selectedLane)
  val selectedRequest = if (sourceCount == 1) requests(0) else requests(selectedSource)
  io.out := 0.U.asTypeOf(io.out)
  when(selectedValid) {
    io.out := selectedRequest
    io.out.valid := true.B
  }
  io.selectedSourceValid := selectedValid
  io.selectedSource := selectedSource
  io.selectedStid := selectedLane
  io.outAccepted := selectedValid && io.outReady
  io.pendingMask := pending.asUInt
  io.lanePendingMask := laneValid.asUInt

  for (source <- 0 until sourceCount) {
    val selected = selectedValid && (selectedSource === source.U)
    val slotReady = !pending(source) || (io.outAccepted && selected)
    val stidInRange = io.sources(source).stid < stidCount.U
    io.sourceReady(source) := slotReady && stidInRange
    io.sourceAccepted(source) := io.sources(source).valid && io.sourceReady(source)
    io.sourceBlockedByStid(source) := io.sources(source).valid && !stidInRange

    when(io.sourceAccepted(source)) {
      requests(source) := io.sources(source)
      pending(source) := true.B
    }.elsewhen(io.outAccepted && selected) {
      pending(source) := false.B
    }
  }

  when(io.outAccepted) {
    nextStid := Mux(selectedLane === (stidCount - 1).U, 0.U, selectedLane + 1.U)
  }
}
