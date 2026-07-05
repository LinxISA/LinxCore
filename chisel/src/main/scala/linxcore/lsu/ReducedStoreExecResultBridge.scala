package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, Mux1H, PopCount, PriorityEncoder}

import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.common.InterfaceParams
import linxcore.rename.{StoreSplitIssuePayload, StoreSplitStoreType}
import linxcore.rob.ROBID

class ReducedStoreExecResultEntry(
    val robEntries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val stidWidth: Int = 8,
    val sizeWidth: Int = 4)
    extends Bundle {
  val valid = Bool()
  val bid = new ROBID(robEntries)
  val rid = new ROBID(robEntries)
  val stid = UInt(stidWidth.W)
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
  val size = UInt(sizeWidth.W)
}

class ReducedStoreExecResultBridgeIO(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val bufferEntries: Int = 4,
    val mapQDepth: Int = 32,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8)
    extends Bundle {
  private val countWidth = log2Ceil(bufferEntries + 1)

  val flushValid = Input(Bool())
  val completeValid = Input(Bool())
  val completeRow = Input(new CommitTraceRow(traceParams))
  val completeBid = Input(new ROBID(p.robEntries))
  val completeRid = Input(new ROBID(p.robEntries))
  val completeStid = Input(UInt(stidWidth.W))

  val staQueueValid = Input(Bool())
  val staQueue = Input(new StoreSplitIssuePayload(p, mapQDepth))
  val stdQueueValid = Input(Bool())
  val stdQueue = Input(new StoreSplitIssuePayload(p, mapQDepth))
  val staConsumed = Input(Bool())
  val stdConsumed = Input(Bool())

  val staExec = Output(new StoreDispatchExecResult(addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  val stdExec = Output(new StoreDispatchExecResult(addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  val completeStoreValid = Output(Bool())
  val captureFire = Output(Bool())
  val captureBlocked = Output(Bool())
  val captureDuplicate = Output(Bool())
  val staMatch = Output(Bool())
  val stdMatch = Output(Bool())
  val validMask = Output(UInt(bufferEntries.W))
  val bufferCount = Output(UInt(countWidth.W))
}

class ReducedStoreExecResultBridge(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val bufferEntries: Int = 4,
    val mapQDepth: Int = 32,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val stdDelayCycles: Int = 0)
    extends Module {
  require(bufferEntries > 1, "reduced store exec bridge needs at least two result entries")
  require((bufferEntries & (bufferEntries - 1)) == 0, "reduced store exec bridge depth must be a power of two")
  require(traceParams.dataWidth >= dataWidth, "commit trace data must hold store data")
  require(traceParams.dataWidth >= addrWidth, "commit trace data must hold store address")
  require(traceParams.robValueWidth >= p.robIndexWidth, "commit trace ROB width must cover ROB ids")
  require(stdDelayCycles >= 0, "STD delay cycles must be nonnegative")

  val io = IO(new ReducedStoreExecResultBridgeIO(
    p,
    traceParams,
    bufferEntries,
    mapQDepth,
    addrWidth,
    dataWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    sizeWidth,
    simtLaneWidth
  ))

  private def resize(value: UInt, width: Int): UInt =
    if (width <= value.getWidth) value(width - 1, 0) else value.pad(width)

  private def zeroEntry: ReducedStoreExecResultEntry = {
    val entry = Wire(new ReducedStoreExecResultEntry(p.robEntries, addrWidth, dataWidth, stidWidth, sizeWidth))
    entry := 0.U.asTypeOf(entry)
    entry.bid := ROBID.disabled(p.robEntries)
    entry.rid := ROBID.disabled(p.robEntries)
    entry
  }

  private def zeroExec: StoreDispatchExecResult = {
    val exec = Wire(new StoreDispatchExecResult(addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
    exec := 0.U.asTypeOf(exec)
    exec
  }

  private def samePayload(entry: ReducedStoreExecResultEntry, payload: StoreSplitIssuePayload): Bool =
    entry.valid &&
      payload.valid &&
      ROBID.equal(entry.bid, payload.uop.bid) &&
      ROBID.equal(entry.rid, payload.uop.rid) &&
      entry.stid === resize(payload.uop.threadId, stidWidth)

  private def sameComplete(entry: ReducedStoreExecResultEntry): Bool =
    entry.valid &&
      ROBID.equal(entry.bid, io.completeBid) &&
      ROBID.equal(entry.rid, io.completeRid) &&
      entry.stid === io.completeStid

  private def execFromEntry(entry: ReducedStoreExecResultEntry, payload: StoreSplitIssuePayload): StoreDispatchExecResult = {
    val exec = Wire(new StoreDispatchExecResult(addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
    exec := 0.U.asTypeOf(exec)
    exec.valid := entry.valid && payload.valid
    exec.addr := entry.addr
    exec.data := entry.data
    exec.size := entry.size
    exec.peId := resize(payload.uop.peId, peIdWidth)
    exec.stid := resize(payload.uop.threadId, stidWidth)
    exec.tid := resize(payload.uop.threadId, tidWidth)
    exec.stackValid := false.B
    exec.scalarIex := true.B
    exec.simtLane := 0.U
    exec
  }

  val entries = RegInit(VecInit(Seq.fill(bufferEntries)(zeroEntry)))

  val completeStoreValid =
    !io.flushValid && io.completeValid && io.completeRow.mem.valid && io.completeRow.mem.isStore
  val completeEntry = Wire(new ReducedStoreExecResultEntry(p.robEntries, addrWidth, dataWidth, stidWidth, sizeWidth))
  completeEntry := zeroEntry
  completeEntry.valid := true.B
  completeEntry.bid := io.completeBid
  completeEntry.rid := io.completeRid
  completeEntry.stid := io.completeStid
  completeEntry.addr := resize(io.completeRow.mem.addr, addrWidth)
  completeEntry.data := resize(io.completeRow.mem.wdata, dataWidth)
  completeEntry.size := resize(io.completeRow.mem.size, sizeWidth)

  val staHitVec = VecInit((0 until bufferEntries).map(idx =>
    io.staQueueValid && samePayload(entries(idx), io.staQueue)))
  val stdHitVec = VecInit((0 until bufferEntries).map(idx =>
    io.stdQueueValid && samePayload(entries(idx), io.stdQueue)))
  val staHit = staHitVec.asUInt.orR
  val stdHit = stdHitVec.asUInt.orR
  val staIndex = PriorityEncoder(staHitVec.asUInt)
  val stdIndex = PriorityEncoder(stdHitVec.asUInt)

  val duplicateVec = VecInit((0 until bufferEntries).map(idx => sameComplete(entries(idx))))
  val duplicateCapture = completeStoreValid && duplicateVec.asUInt.orR
  val clearVec = Wire(Vec(bufferEntries, Bool()))
  for (idx <- 0 until bufferEntries) {
    val staClearsUnsplit =
      io.staConsumed && staHitVec(idx) && (io.staQueue.storeType === StoreSplitStoreType.All)
    val stdClearsSplit = io.stdConsumed && stdHitVec(idx)
    clearVec(idx) := staClearsUnsplit || stdClearsSplit
  }

  io.staExec := Mux(staHit, execFromEntry(entries(staIndex), io.staQueue), zeroExec)
  val rawStdExec = Mux(stdHit, execFromEntry(entries(stdIndex), io.stdQueue), zeroExec)
  val stdDelayActiveVec =
    if (stdDelayCycles > 0) {
      val width = math.max(1, log2Ceil(stdDelayCycles + 1))
      val counters = RegInit(VecInit(Seq.fill(bufferEntries)(0.U(width.W))))
      val activeVec = VecInit((0 until bufferEntries).map(idx => counters(idx) =/= 0.U))
      val staArmsDelayVec = VecInit((0 until bufferEntries).map(idx =>
        io.staConsumed && staHitVec(idx) && (io.staQueue.storeType === StoreSplitStoreType.Addr)))

      when(io.flushValid) {
        for (idx <- 0 until bufferEntries) {
          counters(idx) := 0.U
        }
      }.otherwise {
        for (idx <- 0 until bufferEntries) {
          when(clearVec(idx)) {
            counters(idx) := 0.U
          }.elsewhen(staArmsDelayVec(idx)) {
            counters(idx) := stdDelayCycles.U
          }.elsewhen(counters(idx) =/= 0.U) {
            counters(idx) := counters(idx) - 1.U
          }
        }
      }

      activeVec
    } else {
      VecInit(Seq.fill(bufferEntries)(false.B))
    }
  val stdExec = Wire(new StoreDispatchExecResult(addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  stdExec := rawStdExec
  when(stdHit && stdDelayActiveVec(stdIndex)) {
    stdExec.valid := false.B
  }
  io.stdExec := stdExec
  io.staMatch := staHit
  io.stdMatch := stdHit

  val freeVec = VecInit((0 until bufferEntries).map(idx => !entries(idx).valid || clearVec(idx)))
  val freeMask = freeVec.asUInt
  val firstFreeValid = freeMask.orR
  val firstFreeIndex = PriorityEncoder(freeMask)
  val captureFire = completeStoreValid && !duplicateCapture && firstFreeValid

  when(io.flushValid) {
    for (idx <- 0 until bufferEntries) {
      entries(idx) := zeroEntry
    }
  }.otherwise {
    for (idx <- 0 until bufferEntries) {
      when(clearVec(idx)) {
        entries(idx) := zeroEntry
      }
    }
    when(captureFire) {
      entries(firstFreeIndex) := completeEntry
    }
  }

  val validVec = VecInit(entries.map(_.valid))
  io.completeStoreValid := completeStoreValid
  io.captureFire := captureFire
  io.captureBlocked := completeStoreValid && !duplicateCapture && !firstFreeValid
  io.captureDuplicate := duplicateCapture
  io.validMask := validVec.asUInt
  io.bufferCount := PopCount(validVec.asUInt)
}
