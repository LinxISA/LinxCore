package linxcore.lsu

import chisel3._
import chisel3.util.{PopCount, RRArbiter, log2Ceil}

import linxcore.common.{DestinationKind, ScalarLsuParams}
import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

class ScalarLSULoadReturnPipelineIO(
    val idEntries: Int,
    val p: ScalarLsuParams,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val pipeWidth = math.max(1, log2Ceil(p.loadReturnPipeCount))
  private def entryType = new ScalarLSULoadReturnEntry(
    idEntries,
    p.addrWidth,
    p.pcWidth,
    p.dataWidth,
    p.loadSizeWidth,
    p.loadReturnPipeCount,
    p.archRegWidth,
    p.physRegWidth,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth
  )

  val enable = Input(Bool())
  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(
    idEntries, p.peIdWidth, p.stidWidth, p.tidWidth, lsidWidth))

  val inValid = Input(Bool())
  val in = Input(entryType)
  val inReady = Output(Bool())
  val inAccepted = Output(Bool())
  val inInserted = Output(Bool())
  val inDroppedByNeedFlush = Output(Bool())
  val inBlockedByRobMissing = Output(Bool())
  val selectedPipe = Output(UInt(pipeWidth.W))

  val robLookupValid = Output(Bool())
  val robLookupPeId = Output(UInt(p.peIdWidth.W))
  val robLookupStid = Output(UInt(p.stidWidth.W))
  val robLookupTid = Output(UInt(p.tidWidth.W))
  val robLookupBid = Output(new ROBID(idEntries))
  val robLookupGid = Output(new ROBID(idEntries))
  val robLookupRid = Output(new ROBID(idEntries))
  val robLookupLoadLsId = Output(new ROBID(idEntries))
  val robRowValid = Input(Bool())
  val robRowNeedFlush = Input(Bool())

  val resolveReady = Input(Vec(p.loadReturnPipeCount, Bool()))
  val writebackReady = Input(Vec(p.loadReturnPipeCount, Bool()))
  val wakeupReady = Input(Vec(p.loadReturnPipeCount, Bool()))
  val resolveFire = Output(Vec(p.loadReturnPipeCount, Bool()))
  val writebackFire = Output(Vec(p.loadReturnPipeCount, Bool()))
  val wakeupFire = Output(Vec(p.loadReturnPipeCount, Bool()))
  val completion = Output(Vec(p.loadReturnPipeCount, entryType))

  val w1ValidMask = Output(UInt(p.loadReturnPipeCount.W))
  val w2ValidMask = Output(UInt(p.loadReturnPipeCount.W))
  val w1PrecisePruneMask = Output(UInt(p.loadReturnPipeCount.W))
  val w2PrecisePruneMask = Output(UInt(p.loadReturnPipeCount.W))
  val completionMask = Output(UInt(p.loadReturnPipeCount.W))
  val w1Count = Output(UInt(log2Ceil(p.loadReturnPipeCount + 1).W))
  val w2Count = Output(UInt(log2Ceil(p.loadReturnPipeCount + 1).W))
  val empty = Output(Bool())
  val protocolError = Output(Bool())
}

class ScalarLSULoadReturnPipeline(
    val idEntries: Int,
    val p: ScalarLsuParams,
    val lsidWidth: Int = 32)
    extends Module {
  require(idEntries > 1 && (idEntries & (idEntries - 1)) == 0,
    "idEntries must be a power of two greater than one")
  require(p.loadReturnPipeCount > 0, "loadReturnPipeCount must be positive")

  private val pipeCount = p.loadReturnPipeCount
  private val pipeWidth = math.max(1, log2Ceil(pipeCount))
  private def entryType = new ScalarLSULoadReturnEntry(
    idEntries,
    p.addrWidth,
    p.pcWidth,
    p.dataWidth,
    p.loadSizeWidth,
    pipeCount,
    p.archRegWidth,
    p.physRegWidth,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth
  )

  val io = IO(new ScalarLSULoadReturnPipelineIO(idEntries, p, lsidWidth))

  private def zeroEntry: ScalarLSULoadReturnEntry = {
    val entry = Wire(entryType)
    entry := 0.U.asTypeOf(entry)
    entry.payload.bid := ROBID.disabled(idEntries)
    entry.payload.gid := ROBID.disabled(idEntries)
    entry.payload.rid := ROBID.disabled(idEntries)
    entry.payload.loadLsId := ROBID.disabled(idEntries)
    entry.payload.dst := LoadReplayDestination.none(p.archRegWidth, p.physRegWidth)
    entry
  }

  private def toPruneEntry(entry: ScalarLSULoadReturnEntry): STQFlushPruneEntry = {
    val row = Wire(new STQFlushPruneEntry(idEntries, p.peIdWidth, p.stidWidth, p.tidWidth))
    row.valid := entry.payload.valid
    row.status := STQEntryStatus.Wait
    row.peId := entry.peId
    row.stid := entry.stid
    row.tid := entry.tid
    row.bid := entry.payload.bid
    row.gid := entry.payload.gid
    row.lsId := entry.payload.loadLsId
    row.lsIdFull := 0.U
    row
  }

  val w1 = RegInit(VecInit(Seq.fill(pipeCount)(zeroEntry)))
  val w2 = RegInit(VecInit(Seq.fill(pipeCount)(zeroEntry)))
  val preciseActive = io.enable && !io.flush && io.preciseFlush.req.valid
  val active = io.enable && !io.flush && !preciseActive

  val w1Prune = Wire(Vec(pipeCount, Bool()))
  val w2Prune = Wire(Vec(pipeCount, Bool()))
  val completionFire = Wire(Vec(pipeCount, Bool()))
  val advance = Wire(Vec(pipeCount, Bool()))
  val w1Free = Wire(Vec(pipeCount, Bool()))

  for (pipe <- 0 until pipeCount) {
    w1Prune(pipe) := preciseActive &&
      STQFlushPrune.matchesFlushProjected(io.preciseFlush, toPruneEntry(w1(pipe)))
    w2Prune(pipe) := preciseActive &&
      STQFlushPrune.matchesFlushProjected(io.preciseFlush, toPruneEntry(w2(pipe)))

    val w2Live = active && w2(pipe).payload.valid
    val hasDestination = w2(pipe).payload.dst.valid &&
      (w2(pipe).payload.dst.kind =/= DestinationKind.None)
    val writebackRequired = hasDestination &&
      (w2(pipe).payload.dst.kind === DestinationKind.Gpr)
    val wakeupRequired = !w2(pipe).payload.specWakeup && !w2(pipe).payload.stackValid
    val allReady = io.resolveReady(pipe) &&
      (!writebackRequired || io.writebackReady(pipe)) &&
      (!wakeupRequired || io.wakeupReady(pipe))

    completionFire(pipe) := w2Live && allReady
    advance(pipe) := active && w1(pipe).payload.valid &&
      (!w2(pipe).payload.valid || completionFire(pipe))
    w1Free(pipe) := active && (!w1(pipe).payload.valid || advance(pipe))

    io.resolveFire(pipe) := completionFire(pipe)
    io.writebackFire(pipe) := completionFire(pipe) && writebackRequired
    io.wakeupFire(pipe) := completionFire(pipe) && wakeupRequired && hasDestination
    io.completion(pipe) := Mux(w2Live, w2(pipe), zeroEntry)
  }

  val insertArb = Module(new RRArbiter(UInt(pipeWidth.W), pipeCount))
  for (pipe <- 0 until pipeCount) {
    insertArb.io.in(pipe).valid := w1Free(pipe)
    insertArb.io.in(pipe).bits := pipe.U
  }
  val inputIdentityValid = io.in.payload.valid && io.in.payload.rid.valid
  val inputLive = active && io.inValid && inputIdentityValid
  val dropByNeedFlush = inputLive && io.robRowValid && io.robRowNeedFlush
  val insertCandidate = inputLive && io.robRowValid && !io.robRowNeedFlush
  insertArb.io.out.ready := insertCandidate
  val insertAccepted = insertCandidate && insertArb.io.out.valid
  val inputReady = active && io.robRowValid && (io.robRowNeedFlush || insertArb.io.out.valid)
  val inputAccepted = io.inValid && inputIdentityValid && inputReady
  val selectedPipe = Mux(insertArb.io.out.valid, insertArb.io.out.bits, 0.U)

  when(!io.enable || io.flush) {
    for (pipe <- 0 until pipeCount) {
      w1(pipe) := zeroEntry
      w2(pipe) := zeroEntry
    }
  }.elsewhen(preciseActive) {
    for (pipe <- 0 until pipeCount) {
      when(w1Prune(pipe)) { w1(pipe) := zeroEntry }
      when(w2Prune(pipe)) { w2(pipe) := zeroEntry }
    }
  }.otherwise {
    for (pipe <- 0 until pipeCount) {
      when(advance(pipe)) {
        w2(pipe) := w1(pipe)
      }.elsewhen(completionFire(pipe)) {
        w2(pipe) := zeroEntry
      }

      when(insertAccepted && selectedPipe === pipe.U) {
        w1(pipe) := io.in
      }.elsewhen(advance(pipe)) {
        w1(pipe) := zeroEntry
      }
    }
  }

  io.inReady := inputReady
  io.inAccepted := inputAccepted
  io.inInserted := insertAccepted
  io.inDroppedByNeedFlush := dropByNeedFlush
  io.inBlockedByRobMissing := active && io.inValid && inputIdentityValid && !io.robRowValid
  io.selectedPipe := selectedPipe
  io.robLookupValid := active && io.inValid && inputIdentityValid
  io.robLookupPeId := io.in.peId
  io.robLookupStid := io.in.stid
  io.robLookupTid := io.in.tid
  io.robLookupBid := io.in.payload.bid
  io.robLookupGid := io.in.payload.gid
  io.robLookupRid := io.in.payload.rid
  io.robLookupLoadLsId := io.in.payload.loadLsId
  io.w1ValidMask := VecInit(w1.map(_.payload.valid)).asUInt
  io.w2ValidMask := VecInit(w2.map(_.payload.valid)).asUInt
  io.w1PrecisePruneMask := w1Prune.asUInt
  io.w2PrecisePruneMask := w2Prune.asUInt
  io.completionMask := completionFire.asUInt
  io.w1Count := PopCount(io.w1ValidMask)
  io.w2Count := PopCount(io.w2ValidMask)
  io.empty := !io.w1ValidMask.orR && !io.w2ValidMask.orR
  io.protocolError :=
    (io.inAccepted && !(io.inInserted || io.inDroppedByNeedFlush)) ||
      (io.inInserted && io.inDroppedByNeedFlush) ||
      (io.writebackFire.asUInt & ~io.resolveFire.asUInt).orR ||
      (io.wakeupFire.asUInt & ~io.resolveFire.asUInt).orR

  assert(!io.protocolError, "canonical scalar load-return pipeline must preserve atomic ownership")
}
