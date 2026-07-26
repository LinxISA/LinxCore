package linxcore.frontend

import chisel3._
import chisel3.util.{Cat, Decoupled, Mux1H, PriorityEncoder, Queue, Valid, log2Ceil}
import linxcore.common.InterfaceParams

class ISideStartRequest(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val threadId = UInt(p.threadIdWidth.W)
  val pc = UInt(p.pcWidth.W)
}

class ISideBackendRestart(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val peId = UInt(p.peIdWidth.W)
  val threadId = UInt(p.threadIdWidth.W)
  val pc = UInt(p.pcWidth.W)
  val checkpointId = UInt(p.checkpointWidth.W)
  val newEpoch = UInt(p.blockEpochWidth.W)
}

class ISideResolvedNextPc(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val threadId = UInt(p.threadIdWidth.W)
  val pc = UInt(p.pcWidth.W)
}

class ISideF0PcSelectIO(
    val p: InterfaceParams = InterfaceParams(),
    val threadCount: Int = 1,
    val lineBytes: Int = 64)
    extends Bundle {
  val start = Flipped(Valid(new ISideStartRequest(p)))
  val backendRestart = Flipped(Valid(new ISideBackendRestart(p)))
  val predictionCorrection = Input(new IfuInnerFlush(p))
  val predictionContext = Flipped(Valid(new BSidePredictionUpdate(p, lineBytes)))
  val resolvedNextPc = Flipped(Valid(new ISideResolvedNextPc(p)))

  val fetch = Decoupled(new ISideFetchRequest(p, lineBytes))
  val predictionRequest = Decoupled(new ISideFetchRequest(p, lineBytes))

  val active = Output(Vec(threadCount, Bool()))
  val currentPc = Output(Vec(threadCount, UInt(p.pcWidth.W)))
  val epochs = Output(Vec(threadCount, UInt(p.blockEpochWidth.W)))
  val startAccepted = Output(Bool())
  val startEpoch = Output(UInt(p.blockEpochWidth.W))
  val predictionDroppedStale = Output(Bool())
}

class ISideF0PcSelect(
    val p: InterfaceParams = InterfaceParams(),
    val threadCount: Int = 1,
    val lineBytes: Int = 64,
    val predictionQueueDepth: Int = 4)
    extends Module {
  require(threadCount > 0 && (threadCount & (threadCount - 1)) == 0)
  require(threadCount <= (1 << p.threadIdWidth))
  require(lineBytes >= 8 && (lineBytes & (lineBytes - 1)) == 0)
  require(predictionQueueDepth > 0)

  private val threadIndexWidth = math.max(1, log2Ceil(threadCount))
  private val lineOffsetBits = log2Ceil(lineBytes)

  val io = IO(new ISideF0PcSelectIO(p, threadCount, lineBytes))

  val active = RegInit(VecInit(Seq.fill(threadCount)(false.B)))
  val peIds = RegInit(VecInit(Seq.fill(threadCount)(0.U(p.peIdWidth.W))))
  val pcs = RegInit(VecInit(Seq.fill(threadCount)(0.U(p.pcWidth.W))))
  val fetchSeqs = RegInit(VecInit(Seq.fill(threadCount)(0.U(p.uopUidWidth.W))))
  val epochs = RegInit(VecInit(Seq.fill(threadCount)(0.U(p.blockEpochWidth.W))))
  val checkpoints = RegInit(VecInit(Seq.fill(threadCount)(0.U(p.checkpointWidth.W))))
  val predictionContextValid = RegInit(VecInit(Seq.fill(threadCount)(false.B)))
  val predictionContexts =
    RegInit(VecInit(Seq.fill(threadCount)(0.U.asTypeOf(new BranchPredictionRecord(p)))))
  val rr = RegInit(0.U(threadIndexWidth.W))

  val activeByOffset = Wire(Vec(threadCount, Bool()))
  for (offset <- 0 until threadCount) {
    if (threadCount == 1) {
      activeByOffset(offset) := active(0)
    } else {
      val candidate = (rr + offset.U)(threadIndexWidth - 1, 0)
      activeByOffset(offset) := active(candidate)
    }
  }
  val selectedValid = activeByOffset.asUInt.orR
  val selectedOffset = PriorityEncoder(activeByOffset.asUInt)
  val selectedThread =
    if (threadCount == 1) 0.U(threadIndexWidth.W)
    else (rr + selectedOffset)(threadIndexWidth - 1, 0)

  val selectedPc =
    if (threadCount == 1) pcs(0)
    else Mux1H((0 until threadCount).map(thread => (selectedThread === thread.U) -> pcs(thread)))
  val selectedFetchSeq =
    if (threadCount == 1) fetchSeqs(0)
    else Mux1H((0 until threadCount).map(thread => (selectedThread === thread.U) -> fetchSeqs(thread)))
  val selectedPeId =
    if (threadCount == 1) peIds(0)
    else Mux1H((0 until threadCount).map(thread => (selectedThread === thread.U) -> peIds(thread)))
  val selectedCheckpoint =
    if (threadCount == 1) checkpoints(0)
    else Mux1H((0 until threadCount).map(thread => (selectedThread === thread.U) -> checkpoints(thread)))
  val selectedEpoch =
    if (threadCount == 1) epochs(0)
    else Mux1H((0 until threadCount).map(thread => (selectedThread === thread.U) -> epochs(thread)))
  val selectedPredictionContextValid =
    if (threadCount == 1) predictionContextValid(0)
    else
      Mux1H(
        (0 until threadCount).map(thread =>
          (selectedThread === thread.U) -> predictionContextValid(thread)))
  val selectedPredictionContext =
    if (threadCount == 1) predictionContexts(0)
    else
      Mux1H(
        (0 until threadCount).map(thread =>
          (selectedThread === thread.U) -> predictionContexts(thread)))

  val predictionQueue =
    Module(new Queue(new ISideFetchRequest(p, lineBytes), predictionQueueDepth, pipe = true, flow = false))

  val controlValid =
    io.backendRestart.valid ||
      io.predictionCorrection.valid ||
      io.start.valid ||
      io.resolvedNextPc.valid
  val startThreadSupported = io.start.bits.threadId < threadCount.U
  val startThread =
    if (threadCount == 1) 0.U(threadIndexWidth.W)
    else io.start.bits.threadId(threadIndexWidth - 1, 0)
  val startWasActive =
    if (threadCount == 1) active(0)
    else Mux1H((0 until threadCount).map(thread => (startThread === thread.U) -> active(thread)))
  val acceptedStart =
    io.start.valid &&
      startThreadSupported &&
      !io.backendRestart.valid
  val canAllocate = selectedValid && predictionQueue.io.enq.ready && !controlValid

  val request = Wire(new ISideFetchRequest(p, lineBytes))
  request := 0.U.asTypeOf(request)
  request.pc := selectedPc
  request.lineVa := Cat(selectedPc(p.pcWidth - 1, lineOffsetBits), 0.U(lineOffsetBits.W))
  request.transactionId := selectedFetchSeq
  request.identity.peId := selectedPeId
  request.identity.threadId := selectedThread
  request.identity.fetchPacketUid := selectedFetchSeq
  request.identity.fetchSeq := selectedFetchSeq
  request.identity.fetchSlot := 0.U
  request.identity.checkpointId := selectedCheckpoint
  request.identity.epoch := selectedEpoch
  request.prediction.valid := false.B
  request.prediction.requestPc := selectedPc
  request.prediction.taken := false.B
  request.prediction.branchPc := selectedPc
  request.prediction.target := request.lineVa + lineBytes.U
  request.prediction.fallthroughPc := request.lineVa + lineBytes.U
  request.prediction.kind := linxcore.common.BoundaryKind.Fall
  request.prediction.provider := PredictionProvider.Sequential
  request.prediction.stage := BSideStage.Sequential
  request.prediction.checkpointId := selectedCheckpoint
  request.prediction.epoch := selectedEpoch
  when(
    selectedPredictionContextValid &&
      selectedPredictionContext.epoch === selectedEpoch) {
    request.prediction := selectedPredictionContext
  }

  io.fetch.valid := canAllocate
  io.fetch.bits := request
  predictionQueue.io.enq.valid := io.fetch.fire
  predictionQueue.io.enq.bits := request

  val predictionHeadThreadSupported =
    predictionQueue.io.deq.bits.identity.threadId < threadCount.U
  val predictionHeadEpoch =
    if (threadCount == 1) epochs(0)
    else
      Mux1H(
        (0 until threadCount).map(thread =>
          (predictionQueue.io.deq.bits.identity.threadId === thread.U) -> epochs(thread)))
  val predictionHeadStale =
    predictionQueue.io.deq.valid &&
      (!predictionHeadThreadSupported ||
        predictionQueue.io.deq.bits.identity.epoch =/= predictionHeadEpoch)
  io.predictionRequest.valid := predictionQueue.io.deq.valid && !predictionHeadStale
  io.predictionRequest.bits := predictionQueue.io.deq.bits
  predictionQueue.io.deq.ready := predictionHeadStale || io.predictionRequest.ready
  io.predictionDroppedStale := predictionHeadStale

  when(io.backendRestart.valid) {
    for (thread <- 0 until threadCount) {
      when(io.backendRestart.bits.threadId === thread.U) {
        active(thread) := true.B
        peIds(thread) := io.backendRestart.bits.peId
        pcs(thread) := io.backendRestart.bits.pc
        fetchSeqs(thread) := 0.U
        epochs(thread) := io.backendRestart.bits.newEpoch
        checkpoints(thread) := io.backendRestart.bits.checkpointId
        predictionContextValid(thread) := false.B
        rr := thread.U
      }
    }
  }.elsewhen(acceptedStart) {
    for (thread <- 0 until threadCount) {
      when(io.start.bits.threadId === thread.U) {
        active(thread) := true.B
        peIds(thread) := io.start.bits.peId
        pcs(thread) := io.start.bits.pc
        fetchSeqs(thread) := 0.U
        epochs(thread) := Mux(active(thread), epochs(thread) + 1.U, 0.U)
        checkpoints(thread) := 0.U
        predictionContextValid(thread) := false.B
        rr := thread.U
      }
    }
  }.elsewhen(io.predictionCorrection.valid) {
    for (thread <- 0 until threadCount) {
      when(io.predictionCorrection.threadId === thread.U) {
        active(thread) := true.B
        pcs(thread) := io.predictionCorrection.restartPc
        epochs(thread) := io.predictionCorrection.newEpoch
        checkpoints(thread) := io.predictionCorrection.checkpointId
        when(io.predictionCorrection.reason =/= IfuInnerFlushReason.PredictionCorrection) {
          predictionContextValid(thread) := false.B
        }
        rr := thread.U
      }
    }
  }.elsewhen(io.resolvedNextPc.valid) {
    for (thread <- 0 until threadCount) {
      when(io.resolvedNextPc.bits.threadId === thread.U) {
        pcs(thread) := io.resolvedNextPc.bits.pc
        rr := thread.U
      }
    }
  }.elsewhen(io.fetch.fire) {
    for (thread <- 0 until threadCount) {
      when(selectedThread === thread.U) {
        pcs(thread) :=
          Cat(
            pcs(thread)(p.pcWidth - 1, lineOffsetBits) + 1.U,
            0.U(lineOffsetBits.W))
        fetchSeqs(thread) := fetchSeqs(thread) + 1.U
        checkpoints(thread) := checkpoints(thread) + 1.U
      }
    }
    rr := selectedThread + 1.U
  }

  // The B-SIDE response and inner flush are accepted atomically.  A BF4
  // correction at BSTART seeds the block-body context.  A final steer has
  // consumed that block and must clear the context before fetching its target;
  // the target BSTART will establish the next dynamic block context.
  // A start/backend restart destroys every older speculative B-SIDE record.
  // Suppress a response that happens to retire in the same cycle so it cannot
  // reinstall stale prediction context after the restart cleared it.
  when(io.predictionContext.valid && !io.backendRestart.valid && !acceptedStart) {
    for (thread <- 0 until threadCount) {
      when(io.predictionContext.bits.request.identity.threadId === thread.U) {
        when(
          io.predictionContext.bits.finalSteer ||
            io.predictionContext.bits.prediction.stage =/= BSideStage.BF4) {
          predictionContextValid(thread) := false.B
        }.otherwise {
          predictionContextValid(thread) := true.B
          predictionContexts(thread) := io.predictionContext.bits.prediction
        }
      }
    }
  }

  io.active := active
  io.currentPc := pcs
  io.epochs := epochs
  io.startAccepted := acceptedStart
  io.startEpoch := Mux(startWasActive, epochs(startThread) + 1.U, 0.U)
}
