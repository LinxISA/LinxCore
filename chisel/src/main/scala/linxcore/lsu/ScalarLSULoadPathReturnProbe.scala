package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Fill}
import circt.stage.ChiselStage

import linxcore.common.{CoreParams, ScalarLsuParams}
import linxcore.rob.ROBID

class ScalarLSULoadPathReturnProbeIO extends Bundle {
  val hardFlush = Input(Bool())
  val preciseFlushValid = Input(Bool())
  val preciseFlushStid = Input(UInt(1.W))
  val preciseFlushBid = Input(UInt(3.W))
  val allocValid = Input(Bool())
  val allocStid = Input(UInt(1.W))
  val allocPipe = Input(UInt(1.W))
  val allocBid = Input(UInt(3.W))
  val allocAddr = Input(UInt(6.W))
  val allocReady = Output(Bool())
  val allocAccepted = Output(Bool())
  val allocIndex = Output(UInt(3.W))
  val launchValid = Input(Bool())
  val launchIndex = Input(UInt(3.W))
  val launchReady = Output(Bool())
  val launchAccepted = Output(Bool())
  val launchBlockedByReturnCredit = Output(Bool())
  val drainReady = Input(Bool())
  val sideEffectReady = Input(Bool())
  val drainValid = Output(Bool())
  val drainFire = Output(Bool())
  val drainStid = Output(UInt(1.W))
  val drainPipe = Output(UInt(1.W))
  val drainBid = Output(UInt(3.W))
  val drainData = Output(UInt(64.W))
  val lane0Count = Output(UInt(2.W))
  val lane1Count = Output(UInt(2.W))
  val lane2Count = Output(UInt(2.W))
  val lane3Count = Output(UInt(2.W))
  val reservedCount = Output(UInt(4.W))
  val totalCount = Output(UInt(4.W))
  val publicationValid = Output(Bool())
  val publicationAccepted = Output(Bool())
  val transferPending = Output(Bool())
  val w1ValidMask = Output(UInt(2.W))
  val w2ValidMask = Output(UInt(2.W))
  val completionMask = Output(UInt(2.W))
  val pipelineEmpty = Output(Bool())
  val returnPending = Output(Bool())
  val returnEmpty = Output(Bool())
  val protocolError = Output(Bool())
}

class ScalarLSULoadPathReturnProbe extends Module {
  private val lsuParams = ScalarLsuParams(
    stqEntries = 4,
    commitQueueEntries = 4,
    scbEntries = 4,
    stidCount = 2,
    liqEntries = 8,
    resolveQueueEntries = 8,
    loadReturnQueueEntries = 2,
    loadReturnPipeCount = 2,
    peIdWidth = 1,
    stidWidth = 1,
    tidWidth = 1
  )
  private val coreParams = CoreParams(robEntries = 8, scalarLsu = lsuParams)

  val io = IO(new ScalarLSULoadPathReturnProbeIO)
  val path = Module(new ScalarLSULoadPath(coreParams))

  val preciseFlush = Wire(chiselTypeOf(path.io.preciseFlush))
  preciseFlush := 0.U.asTypeOf(preciseFlush)
  preciseFlush.req.valid := io.preciseFlushValid
  preciseFlush.req.stid := io.preciseFlushStid
  preciseFlush.req.bid.valid := io.preciseFlushValid
  preciseFlush.req.bid.wrap := false.B
  preciseFlush.req.bid.value := io.preciseFlushBid
  preciseFlush.baseOnBid := true.B
  path.io.flush := io.hardFlush
  path.io.preciseFlush := preciseFlush

  val alloc = Wire(chiselTypeOf(path.io.alloc))
  alloc := 0.U.asTypeOf(alloc)
  alloc.bid.valid := io.allocValid
  alloc.bid.wrap := false.B
  alloc.bid.value := io.allocBid
  alloc.gid := ROBID.zero(8)
  alloc.rid.valid := io.allocValid
  alloc.rid.wrap := false.B
  alloc.rid.value := io.allocBid
  alloc.loadLsId.valid := io.allocValid
  alloc.loadLsId.wrap := false.B
  alloc.loadLsId.value := io.allocBid
  alloc.peId := 0.U
  alloc.stid := io.allocStid
  alloc.tid := io.allocStid
  alloc.pc := Cat(0.U(55.W), io.allocBid, 0.U(6.W))
  alloc.addr := io.allocAddr
  alloc.size := 1.U
  alloc.returnSignExtend := false.B
  alloc.dst := LoadReplayDestination.none(lsuParams.archRegWidth, lsuParams.physRegWidth)
  alloc.youngestStoreId := ROBID.disabled(8)
  alloc.youngestStoreLsId := ROBID.disabled(8)
  alloc.returnPipeIndex := io.allocPipe
  path.io.allocValid := io.allocValid
  path.io.alloc := alloc

  path.io.launchValid := io.launchValid
  path.io.launchIndex := io.launchIndex
  path.io.pickValid := false.B
  path.io.pickIndex := 0.U
  path.io.scbReturnValid := false.B
  path.io.scbReturnIndex := 0.U

  for (idx <- 0 until lsuParams.stqEntries) {
    path.io.e2Stores(idx) := 0.U.asTypeOf(path.io.e2Stores(idx))
    path.mdbStore.rows(idx) := 0.U.asTypeOf(path.mdbStore.rows(idx))
  }
  val lineBytes = (0 until lsuParams.lineBytes).reverse.map(_.U(8.W))
  path.io.e2BaseData := Cat(lineBytes)
  path.io.e2BaseValidMask := Fill(lsuParams.lineBytes, 1.U(1.W))
  path.io.e2LoadDataReturned := true.B
  path.io.e2ScbReturned := true.B
  path.io.e2StqReturned := true.B
  path.io.replayWakeValid := false.B
  path.io.replayWake := 0.U.asTypeOf(path.io.replayWake)
  path.io.refillValid := false.B
  path.io.refill := 0.U.asTypeOf(path.io.refill)
  path.io.missRequestReady := true.B
  path.io.missResponseValid := false.B
  path.io.missResponse := 0.U.asTypeOf(path.io.missResponse)
  path.io.resolveRetireValid := false.B
  path.io.resolveRetireBid := ROBID.disabled(8)
  path.io.resolveRetireLsId := ROBID.disabled(8)
  path.io.resolveRetireLsIdFullValid := false.B
  path.io.resolveRetireLsIdFull := 0.U
  path.io.loadReturn.robRowValid := io.drainReady
  path.io.loadReturn.robRowNeedFlush := false.B
  path.io.loadReturn.resolveReady := io.sideEffectReady
  path.io.loadReturn.writebackReady := io.sideEffectReady
  path.io.loadReturn.wakeupReady := io.sideEffectReady

  path.mdbStore.probe := 0.U.asTypeOf(path.mdbStore.probe)
  path.mdbStore.probeCommit := false.B
  path.recovery.ready := true.B

  io.allocReady := path.io.allocReady
  io.allocAccepted := path.io.allocAccepted
  io.allocIndex := path.io.allocIndex
  io.launchReady := path.io.launchReady
  io.launchAccepted := path.io.launchAccepted
  io.launchBlockedByReturnCredit := path.io.launchBlockedByReturnCredit
  io.drainValid := path.io.loadReturn.drainValid
  io.drainFire := path.io.loadReturn.drainFire
  io.drainStid := path.io.loadReturn.drainStid
  io.drainPipe := path.io.loadReturn.drainPipeIndex
  io.drainBid := path.io.loadReturn.drain.bid.value
  io.drainData := path.io.loadReturn.drain.data
  io.lane0Count := path.io.loadReturn.laneCounts(0)
  io.lane1Count := path.io.loadReturn.laneCounts(1)
  io.lane2Count := path.io.loadReturn.laneCounts(2)
  io.lane3Count := path.io.loadReturn.laneCounts(3)
  io.reservedCount := path.io.loadReturn.reservedCount
  io.totalCount := path.io.loadReturn.totalCount
  io.publicationValid := path.io.loadReturn.publicationValid
  io.publicationAccepted := path.io.loadReturn.publicationAccepted
  io.transferPending := path.io.transferPending
  io.w1ValidMask := path.io.loadReturn.w1ValidMask
  io.w2ValidMask := path.io.loadReturn.w2ValidMask
  io.completionMask := path.io.loadReturn.completionMask
  io.pipelineEmpty := path.io.loadReturn.pipelineEmpty
  io.returnPending := path.io.loadReturn.pending
  io.returnEmpty := path.io.loadReturn.empty
  io.protocolError := path.io.loadReturn.protocolError || path.io.transferProtocolError
}

object EmitScalarLSULoadPathReturnProbe extends App {
  ChiselStage.emitSystemVerilogFile(
    new ScalarLSULoadPathReturnProbe,
    args,
    firtoolOpts = Array("--disable-all-randomization", "--strip-debug-info")
  )
}
