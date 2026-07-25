package linxcore.frontend

import chisel3._
import chisel3.util.{Cat, Decoupled, Queue, log2Ceil}
import linxcore.common.{BoundaryKind, InterfaceParams}

class BSidePredictionPipelineIO(
  val p: InterfaceParams = InterfaceParams(),
  val lineBytes: Int = 64)
    extends Bundle {
  val request = Flipped(Decoupled(new ISideFetchRequest(p, lineBytes)))
  val boundary = Flipped(Decoupled(new BSideBoundaryMetadata(p)))
  val resolve = Flipped(Decoupled(new BSideResolveUpdate(p)))
  val prune = Input(new IfuInnerFlush(p))

  val response = Decoupled(new BSidePredictionUpdate(p, lineBytes))
  val innerFlush = Decoupled(new IfuInnerFlush(p))

  val stageValid = Output(UInt(5.W))
  val responseQueueCount = Output(UInt(5.W))
  val trainingQueueCount = Output(UInt(4.W))
  val boundaryCollision = Output(Bool())
  val duplicateTraining = Output(Bool())
}

class BSidePredictionPipeline(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val threadCount: Int = 1,
    val boundaryEntries: Int = 16,
    val responseEntries: Int = 16,
    val trainingEntries: Int = 8,
    val nanoEntries: Int = 8,
    val ubtbEntries: Int = 16,
    val pbtbEntries: Int = 32,
    val bimEntries: Int = 64,
    val tageEntries: Int = 32,
    val ibtbEntries: Int = 16,
    val loopEntries: Int = 16,
    val rasDepth: Int = 8)
    extends Module {
  require(threadCount > 0 && (threadCount & (threadCount - 1)) == 0)
  require(boundaryEntries > 0 && (boundaryEntries & (boundaryEntries - 1)) == 0)
  require(responseEntries > 0 && (responseEntries & (responseEntries - 1)) == 0)
  require(trainingEntries > 0 && (trainingEntries & (trainingEntries - 1)) == 0)
  Seq(nanoEntries, ubtbEntries, pbtbEntries, bimEntries, tageEntries, ibtbEntries, loopEntries, rasDepth)
    .foreach(entries => require(entries > 0 && (entries & (entries - 1)) == 0))

  private val boundaryIndexWidth = math.max(1, log2Ceil(boundaryEntries))
  private val threadIndexWidth = math.max(1, log2Ceil(threadCount))
  private val ghrWidth = 16

  val io = IO(new BSidePredictionPipelineIO(p, lineBytes))

  def tableIndex(pc: UInt, entries: Int): UInt = {
    val width = math.max(1, log2Ceil(entries))
    if (entries == 1) 0.U(width.W) else pc(width + 1, 2)
  }

  def threadIndex(threadId: UInt): UInt =
    if (threadCount == 1) 0.U(threadIndexWidth.W) else threadId(threadIndexWidth - 1, 0)

  def btbCandidate(
      payload: BSidePipePayload,
      table: Vec[BSideBtbEntry],
      provider: PredictionProvider.Type,
      stage: BSideStage.Type): BSidePredictionCandidate = {
    val candidate = Wire(new BSidePredictionCandidate(p))
    candidate := 0.U.asTypeOf(candidate)
    val entry = table(tableIndex(payload.request.pc, table.length))
    val hit = entry.valid && entry.requestPc === payload.request.pc
    candidate.valid := hit
    candidate.taken := Mux(entry.kind === BoundaryKind.Cond, entry.taken, hit)
    candidate.branchPc := entry.branchPc
    candidate.target := entry.target
    candidate.fallthroughPc := entry.fallthroughPc
    candidate.kind := entry.kind
    candidate.provider := provider
    candidate.stage := stage
    candidate.confidence := Mux(hit, 3.U, 0.U)
    candidate
  }

  def nextPrediction(
      payload: BSidePipePayload,
      candidate: BSidePredictionCandidate): BranchPredictionRecord = {
    val next = Wire(new BranchPredictionRecord(p))
    next := payload.effective
    when(candidate.valid) {
      next.valid := true.B
      next.taken := candidate.taken
      next.branchPc := candidate.branchPc
      next.target := candidate.target
      next.fallthroughPc := candidate.fallthroughPc
      next.kind := candidate.kind
      next.provider := candidate.provider
      next.stage := candidate.stage
      next.confidence := candidate.confidence
      next.checkpointId := payload.request.identity.checkpointId
    }
    next
  }

  def updatePayload(
      payload: BSidePipePayload,
      prediction: BranchPredictionRecord,
      correction: Bool): BSidePipePayload = {
    val next = Wire(new BSidePipePayload(p, lineBytes))
    next := payload
    next.effective := prediction
    when(correction) {
      next.effective.epoch := payload.effective.epoch + 1.U
    }
    next
  }

  val nanoBtb = RegInit(VecInit(Seq.fill(nanoEntries)(0.U.asTypeOf(new BSideBtbEntry(p)))))
  val ubtb = RegInit(VecInit(Seq.fill(ubtbEntries)(0.U.asTypeOf(new BSideBtbEntry(p)))))
  val pbtb = RegInit(VecInit(Seq.fill(pbtbEntries)(0.U.asTypeOf(new BSideBtbEntry(p)))))
  val bim = RegInit(VecInit(Seq.fill(bimEntries)(1.U(2.W))))
  val shortTags = RegInit(VecInit(Seq.fill(tageEntries)(0.U(12.W))))
  val shortCounters = RegInit(VecInit(Seq.fill(tageEntries)(1.U(2.W))))
  val shortValid = RegInit(VecInit(Seq.fill(tageEntries)(false.B)))
  val longTags = RegInit(VecInit(Seq.fill(tageEntries)(0.U(12.W))))
  val longCounters = RegInit(VecInit(Seq.fill(tageEntries)(1.U(2.W))))
  val longValid = RegInit(VecInit(Seq.fill(tageEntries)(false.B)))
  val ibtb = RegInit(VecInit(Seq.fill(ibtbEntries)(0.U.asTypeOf(new BSideBtbEntry(p)))))
  val loopTags = RegInit(VecInit(Seq.fill(loopEntries)(0.U(p.pcWidth.W))))
  val loopDirection = RegInit(VecInit(Seq.fill(loopEntries)(false.B)))
  val loopConfidence = RegInit(VecInit(Seq.fill(loopEntries)(0.U(2.W))))
  val ghr = RegInit(VecInit(Seq.fill(threadCount)(0.U(ghrWidth.W))))
  val ras = RegInit(
    VecInit(
      Seq.fill(threadCount)(
        VecInit(Seq.fill(rasDepth)(0.U(p.pcWidth.W))))))
  val rasSp = RegInit(VecInit(Seq.fill(threadCount)(0.U(math.max(1, log2Ceil(rasDepth)).W))))
  val rasCount =
    RegInit(VecInit(Seq.fill(threadCount)(0.U(math.max(1, log2Ceil(rasDepth + 1)).W))))

  val boundaryValid = RegInit(VecInit(Seq.fill(boundaryEntries)(false.B)))
  val boundaries = RegInit(VecInit(Seq.fill(boundaryEntries)(0.U.asTypeOf(new BSideBoundaryMetadata(p)))))
  val boundaryIndex =
    if (boundaryEntries == 1) 0.U(boundaryIndexWidth.W)
    else io.boundary.bits.transactionId(boundaryIndexWidth - 1, 0)
  val boundarySameIdentity =
    boundaries(boundaryIndex).peId === io.boundary.bits.peId &&
      boundaries(boundaryIndex).transactionId === io.boundary.bits.transactionId &&
      boundaries(boundaryIndex).threadId === io.boundary.bits.threadId &&
      boundaries(boundaryIndex).fetchPacketUid === io.boundary.bits.fetchPacketUid &&
      boundaries(boundaryIndex).fetchSeq === io.boundary.bits.fetchSeq &&
      boundaries(boundaryIndex).checkpointId === io.boundary.bits.checkpointId &&
      boundaries(boundaryIndex).epoch === io.boundary.bits.epoch
  val boundaryCollision =
    io.boundary.valid &&
      boundaryValid(boundaryIndex) &&
      !boundarySameIdentity
  io.boundary.ready :=
    !io.prune.valid &&
      (!boundaryValid(boundaryIndex) || boundarySameIdentity)
  io.boundaryCollision := boundaryCollision
  for (entry <- 0 until boundaryEntries) {
    when(
      boundaryValid(entry) &&
        IfuFlushContract.kills(
          boundaries(entry).threadId,
          boundaries(entry).epoch,
          boundaries(entry).fetchSeq,
          boundaries(entry).transactionId,
          io.prune)) {
      boundaryValid(entry) := false.B
    }
  }
  when(io.boundary.fire) {
    boundaryValid(boundaryIndex) := true.B
    boundaries(boundaryIndex) := io.boundary.bits
  }

  val trainingQueue =
    Module(new Queue(new BSideResolveUpdate(p), trainingEntries, pipe = true, flow = false))
  trainingQueue.io.enq <> io.resolve
  trainingQueue.io.deq.ready := true.B
  io.trainingQueueCount := trainingQueue.io.count

  val lastTrainingValid = RegInit(false.B)
  val lastTrainingTransaction = RegInit(0.U(p.uopUidWidth.W))
  val lastTrainingThread = RegInit(0.U(p.threadIdWidth.W))
  val lastTrainingEpoch = RegInit(0.U(p.blockEpochWidth.W))
  val duplicateTraining =
    trainingQueue.io.deq.valid &&
      lastTrainingValid &&
      trainingQueue.io.deq.bits.predictionTag === lastTrainingTransaction &&
      trainingQueue.io.deq.bits.threadId === lastTrainingThread &&
      trainingQueue.io.deq.bits.epoch === lastTrainingEpoch
  io.duplicateTraining := duplicateTraining

  when(trainingQueue.io.deq.fire && !duplicateTraining) {
    val update = trainingQueue.io.deq.bits
    val btbRow = Wire(new BSideBtbEntry(p))
    btbRow.valid := true.B
    btbRow.requestPc := update.requestPc
    btbRow.branchPc := update.branchPc
    btbRow.target := update.target
    btbRow.fallthroughPc := update.fallthroughPc
    btbRow.taken := update.taken
    btbRow.kind := update.kind
    nanoBtb(tableIndex(update.requestPc, nanoEntries)) := btbRow
    ubtb(tableIndex(update.requestPc, ubtbEntries)) := btbRow
    pbtb(tableIndex(update.requestPc, pbtbEntries)) := btbRow
    when(update.kind === BoundaryKind.Ind || update.kind === BoundaryKind.ICall) {
      ibtb(tableIndex(update.requestPc, ibtbEntries)) := btbRow
    }

    val thread = threadIndex(update.threadId)
    val history = ghr(thread)
    when(update.kind === BoundaryKind.Cond) {
      val shortIndex =
        tableIndex(update.requestPc ^ history(3, 0), tageEntries)
      val longIndex =
        tableIndex(update.requestPc ^ history, tageEntries)
      val shortTag = (update.requestPc(13, 2) ^ history(11, 0))(11, 0)
      val longTag = (update.requestPc(13, 2) ^ history(15, 4))(11, 0)
      shortValid(shortIndex) := true.B
      shortTags(shortIndex) := shortTag
      shortCounters(shortIndex) :=
        Mux(
          update.taken,
          Mux(shortCounters(shortIndex) === 3.U, 3.U, shortCounters(shortIndex) + 1.U),
          Mux(shortCounters(shortIndex) === 0.U, 0.U, shortCounters(shortIndex) - 1.U))
      longValid(longIndex) := true.B
      longTags(longIndex) := longTag
      longCounters(longIndex) :=
        Mux(
          update.taken,
          Mux(longCounters(longIndex) === 3.U, 3.U, longCounters(longIndex) + 1.U),
          Mux(longCounters(longIndex) === 0.U, 0.U, longCounters(longIndex) - 1.U))
      val bimIndex = tableIndex(update.requestPc, bimEntries)
      bim(bimIndex) :=
        Mux(
          update.taken,
          Mux(bim(bimIndex) === 3.U, 3.U, bim(bimIndex) + 1.U),
          Mux(bim(bimIndex) === 0.U, 0.U, bim(bimIndex) - 1.U))

      val loopIndex = tableIndex(update.branchPc, loopEntries)
      when(loopTags(loopIndex) === update.branchPc && loopDirection(loopIndex) === update.taken) {
        loopConfidence(loopIndex) :=
          Mux(loopConfidence(loopIndex) === 3.U, 3.U, loopConfidence(loopIndex) + 1.U)
      }.otherwise {
        loopTags(loopIndex) := update.branchPc
        loopDirection(loopIndex) := update.taken
        loopConfidence(loopIndex) := 0.U
      }
    }

    when(update.kind === BoundaryKind.Call || update.kind === BoundaryKind.ICall) {
      when(rasCount(thread) =/= rasDepth.U) {
        val nextSp = rasSp(thread) + 1.U
        ras(thread)(nextSp) := update.fallthroughPc
        rasSp(thread) := nextSp
        rasCount(thread) := rasCount(thread) + 1.U
      }
    }.elsewhen(update.kind === BoundaryKind.Ret) {
      when(rasCount(thread) =/= 0.U) {
        rasSp(thread) := rasSp(thread) - 1.U
        rasCount(thread) := rasCount(thread) - 1.U
      }
    }
    when(update.kind === BoundaryKind.Cond) {
      ghr(thread) := Cat(history(ghrWidth - 2, 0), update.taken)
    }

    lastTrainingValid := true.B
    lastTrainingTransaction := update.predictionTag
    lastTrainingThread := update.threadId
    lastTrainingEpoch := update.epoch
  }

  val stages =
    Seq.fill(5)(Module(new Queue(new BSidePipePayload(p, lineBytes), 1, pipe = true, flow = false)))
  val nextPredictionTag = RegInit(0.U(p.uopUidWidth.W))

  val initialPayload = Wire(new BSidePipePayload(p, lineBytes))
  initialPayload := 0.U.asTypeOf(initialPayload)
  initialPayload.request := io.request.bits
  initialPayload.effective := io.request.bits.prediction
  initialPayload.effective.valid := true.B
  initialPayload.effective.predictionTag := nextPredictionTag
  initialPayload.effective.taken := false.B
  initialPayload.effective.branchPc := io.request.bits.pc
  initialPayload.effective.target := io.request.bits.lineVa + lineBytes.U
  initialPayload.effective.fallthroughPc := io.request.bits.lineVa + lineBytes.U
  initialPayload.effective.kind := BoundaryKind.Fall
  initialPayload.effective.provider := PredictionProvider.Sequential
  initialPayload.effective.stage := BSideStage.Sequential
  initialPayload.effective.confidence := 0.U
  initialPayload.effective.checkpointId := io.request.bits.identity.checkpointId
  initialPayload.effective.epoch := io.request.bits.identity.epoch
  stages.head.io.enq.valid := io.request.valid && !io.prune.valid
  stages.head.io.enq.bits := initialPayload
  io.request.ready := stages.head.io.enq.ready && !io.prune.valid
  when(io.request.fire) {
    nextPredictionTag := nextPredictionTag + 1.U
  }

  val payloads = stages.map(_.io.deq.bits)
  val stageKilled = Wire(Vec(5, Bool()))
  for (stage <- 0 until 5) {
    stageKilled(stage) :=
      stages(stage).io.deq.valid &&
        IfuFlushContract.kills(
          payloads(stage).request.identity,
          payloads(stage).request.transactionId,
          io.prune)
  }
  val candidates = Wire(Vec(5, new BSidePredictionCandidate(p)))
  candidates(0) := btbCandidate(payloads(0), nanoBtb, PredictionProvider.NanoBtb, BSideStage.BF0)
  candidates(1) := btbCandidate(payloads(1), ubtb, PredictionProvider.UBtb, BSideStage.BF1)

  val pbtbCandidate =
    btbCandidate(payloads(2), pbtb, PredictionProvider.PBtb, BSideStage.BF2)
  when(pbtbCandidate.valid && pbtbCandidate.kind === BoundaryKind.Cond) {
    pbtbCandidate.taken := bim(tableIndex(payloads(2).request.pc, bimEntries))(1)
    pbtbCandidate.provider := PredictionProvider.Bim
  }
  candidates(2) := pbtbCandidate

  val stage3 = Wire(new BSidePredictionCandidate(p))
  stage3 := 0.U.asTypeOf(stage3)
  val stage3Thread = threadIndex(payloads(3).request.identity.threadId)
  val stage3History = ghr(stage3Thread)
  val stage3Index = tableIndex(payloads(3).request.pc ^ stage3History(3, 0), tageEntries)
  val stage3Tag = (payloads(3).request.pc(13, 2) ^ stage3History(11, 0))(11, 0)
  val stage3Hit =
    shortValid(stage3Index) &&
      shortTags(stage3Index) === stage3Tag &&
      payloads(3).effective.kind === BoundaryKind.Cond
  stage3.valid := stage3Hit && payloads(3).effective.valid
  stage3.taken := shortCounters(stage3Index)(1)
  stage3.branchPc := payloads(3).effective.branchPc
  stage3.target := payloads(3).effective.target
  stage3.fallthroughPc := payloads(3).effective.fallthroughPc
  stage3.kind := payloads(3).effective.kind
  stage3.provider := PredictionProvider.ShortTage
  stage3.stage := BSideStage.BF3
  stage3.confidence := shortCounters(stage3Index)
  candidates(3) := stage3

  val stage4 = Wire(new BSidePredictionCandidate(p))
  stage4 := 0.U.asTypeOf(stage4)
  val stage4Payload = payloads(4)
  val stage4BoundaryIndex =
    if (boundaryEntries == 1) 0.U(boundaryIndexWidth.W)
    else stage4Payload.request.transactionId(boundaryIndexWidth - 1, 0)
  val boundary = boundaries(stage4BoundaryIndex)
  val boundaryPresent =
    boundaryValid(stage4BoundaryIndex) &&
      boundary.peId === stage4Payload.request.identity.peId &&
      boundary.transactionId === stage4Payload.request.transactionId &&
      boundary.threadId === stage4Payload.request.identity.threadId &&
      boundary.fetchPacketUid === stage4Payload.request.identity.fetchPacketUid &&
      boundary.fetchSeq === stage4Payload.request.identity.fetchSeq &&
      boundary.checkpointId === stage4Payload.request.identity.checkpointId &&
      boundary.epoch === stage4Payload.request.identity.epoch
  val boundaryHit = boundaryPresent && boundary.valid
  val stage4Thread = threadIndex(stage4Payload.request.identity.threadId)
  val stage4History = ghr(stage4Thread)
  val longIndex = tableIndex(stage4Payload.request.pc ^ stage4History, tageEntries)
  val longTag = (stage4Payload.request.pc(13, 2) ^ stage4History(15, 4))(11, 0)
  val longHit = longValid(longIndex) && longTags(longIndex) === longTag
  val resolvedKind = Mux(boundaryHit, boundary.kind, stage4Payload.effective.kind)
  val longEligible = longHit && resolvedKind === BoundaryKind.Cond
  val earlierDirectionEligible =
    boundaryHit &&
      resolvedKind === BoundaryKind.Cond &&
      stage4Payload.effective.valid &&
      stage4Payload.effective.kind === BoundaryKind.Cond &&
      (stage4Payload.effective.provider === PredictionProvider.ShortTage ||
        stage4Payload.effective.provider === PredictionProvider.Bim)
  val loopIndex = tableIndex(boundary.branchPc, loopEntries)
  val loopHit =
    boundaryHit &&
      boundary.kind === BoundaryKind.Cond &&
      loopTags(loopIndex) === boundary.branchPc &&
      loopConfidence(loopIndex)(1)
  val indirectEntry = ibtb(tableIndex(stage4Payload.request.pc, ibtbEntries))
  val indirectHit =
      indirectEntry.valid &&
      indirectEntry.requestPc === stage4Payload.request.pc
  val rasTarget = ras(stage4Thread)(rasSp(stage4Thread))
  val rasHit = boundaryHit && boundary.kind === BoundaryKind.Ret && rasCount(stage4Thread) =/= 0.U

  stage4.valid := boundaryHit || stage4Payload.effective.valid
  stage4.branchPc := Mux(boundaryHit, boundary.branchPc, stage4Payload.effective.branchPc)
  stage4.kind := resolvedKind
  stage4.taken := Mux(
    loopHit,
    loopDirection(loopIndex),
    Mux(
      longEligible,
      longCounters(longIndex)(1),
      Mux(
        earlierDirectionEligible,
        stage4Payload.effective.taken,
        Mux(boundaryHit, boundary.staticTaken, stage4Payload.effective.taken))))
  stage4.target := Mux(
    rasHit,
    rasTarget,
    Mux(
      boundaryHit && (boundary.kind === BoundaryKind.Ind || boundary.kind === BoundaryKind.ICall) && indirectHit,
      indirectEntry.target,
      Mux(boundaryHit, boundary.target, stage4Payload.effective.target)))
  stage4.fallthroughPc :=
    Mux(boundaryPresent, boundary.fallthroughPc, stage4Payload.effective.fallthroughPc)
  stage4.provider := Mux(
    rasHit,
    PredictionProvider.FinalRas,
    Mux(
      boundaryHit && (boundary.kind === BoundaryKind.Ind || boundary.kind === BoundaryKind.ICall) && indirectHit,
      PredictionProvider.IndirectBtb,
      Mux(
        loopHit,
        PredictionProvider.Loop,
        Mux(
          longEligible,
          PredictionProvider.LongTage,
          Mux(
            earlierDirectionEligible,
            stage4Payload.effective.provider,
            Mux(boundaryHit, PredictionProvider.Static, stage4Payload.effective.provider))))))
  stage4.stage := BSideStage.BF4
  stage4.confidence :=
    Mux(
      loopHit || longEligible || indirectHit || rasHit,
      3.U,
      Mux(
        earlierDirectionEligible,
        stage4Payload.effective.confidence,
        Mux(boundaryHit, 1.U, stage4Payload.effective.confidence)))
  candidates(4) := stage4

  val nextPredictions = Wire(Vec(5, new BranchPredictionRecord(p)))
  val tupleMismatch = Wire(Vec(5, Bool()))
  val publishNeeded = Wire(Vec(5, Bool()))
  for (stage <- 0 until 5) {
    nextPredictions(stage) := nextPrediction(payloads(stage), candidates(stage))
    tupleMismatch(stage) :=
      candidates(stage).valid &&
        !BSidePredictionContract.exactTupleMatch(payloads(stage).effective, candidates(stage))
    publishNeeded(stage) := tupleMismatch(stage) || (stage == 4).B
  }

  val responseQueue =
    Module(new Queue(new BSidePredictionUpdate(p, lineBytes), responseEntries, pipe = true, flow = false))

  val downstreamReady = Wire(Vec(5, Bool()))
  for (stage <- 0 until 4) {
    downstreamReady(stage) := stages(stage + 1).io.enq.ready
  }
  downstreamReady(4) := true.B

  val publishRequest = Wire(Vec(5, Bool()))
  for (stage <- 0 until 5) {
    publishRequest(stage) :=
      stages(stage).io.deq.valid &&
        !stageKilled(stage) &&
        publishNeeded(stage) &&
        downstreamReady(stage) &&
        (if (stage == 4) boundaryPresent else true.B)
  }

  val grants = Wire(Vec(5, Bool()))
  grants(4) := responseQueue.io.enq.ready && publishRequest(4)
  for (stage <- 0 until 4) {
    grants(stage) :=
      responseQueue.io.enq.ready &&
        publishRequest(stage) &&
        !(stage + 1 until 5).map(higher => publishRequest(higher)).reduceOption(_ || _).getOrElse(false.B)
  }

  val updates = Wire(Vec(5, new BSidePredictionUpdate(p, lineBytes)))
  for (stage <- 0 until 5) {
    updates(stage) := 0.U.asTypeOf(updates(stage))
    updates(stage).request := payloads(stage).request
    updates(stage).prediction := nextPredictions(stage)
    updates(stage).prediction.epoch :=
      Mux(tupleMismatch(stage), payloads(stage).effective.epoch + 1.U, payloads(stage).effective.epoch)
    updates(stage).correction := tupleMismatch(stage)
    updates(stage).finalResponse := (stage == 4).B
  }

  responseQueue.io.enq.valid := grants.asUInt.orR
  responseQueue.io.enq.bits := updates(0)
  for (stage <- 0 until 5) {
    when(grants(stage)) {
      responseQueue.io.enq.bits := updates(stage)
    }
  }

  for (stage <- 0 until 4) {
    val canAdvance =
      stages(stage).io.deq.valid &&
        !stageKilled(stage) &&
        (!publishNeeded(stage) || grants(stage))
    stages(stage + 1).io.enq.valid := canAdvance
    stages(stage + 1).io.enq.bits :=
      updatePayload(payloads(stage), nextPredictions(stage), tupleMismatch(stage))
    stages(stage).io.deq.ready :=
      stageKilled(stage) ||
        (stages(stage + 1).io.enq.ready &&
          (!publishNeeded(stage) || grants(stage)))
  }
  stages(4).io.deq.ready := stageKilled(4) || grants(4)

  val responseHead = responseQueue.io.deq.bits
  val responseHeadKilled =
    responseQueue.io.deq.valid &&
      IfuFlushContract.kills(
        responseHead.request.identity,
        responseHead.request.transactionId,
        io.prune)
  val responseNeedsFlush =
    responseQueue.io.deq.valid &&
      !responseHeadKilled &&
      responseHead.correction
  io.response.valid :=
    responseQueue.io.deq.valid &&
      !responseHeadKilled &&
      (!responseNeedsFlush || io.innerFlush.ready)
  io.response.bits := responseHead
  io.innerFlush.valid := responseNeedsFlush && io.response.ready
  io.innerFlush.bits := 0.U.asTypeOf(io.innerFlush.bits)
  io.innerFlush.bits.valid := responseNeedsFlush
  io.innerFlush.bits.peId := responseHead.request.identity.peId
  io.innerFlush.bits.threadId := responseHead.request.identity.threadId
  io.innerFlush.bits.transactionId := responseHead.request.transactionId
  io.innerFlush.bits.fetchSeq := responseHead.request.identity.fetchSeq
  io.innerFlush.bits.oldEpoch := responseHead.request.identity.epoch
  io.innerFlush.bits.restartPc :=
    Mux(responseHead.prediction.taken, responseHead.prediction.target, responseHead.prediction.fallthroughPc)
  io.innerFlush.bits.checkpointId := responseHead.prediction.checkpointId
  io.innerFlush.bits.newEpoch := responseHead.prediction.epoch
  io.innerFlush.bits.reason := IfuInnerFlushReason.PredictionCorrection
  io.innerFlush.bits.scope := IfuPruneScope.PreserveTriggerKillYounger
  responseQueue.io.deq.ready :=
    responseHeadKilled ||
      (io.response.ready && (!responseNeedsFlush || io.innerFlush.ready))

  when(stages(4).io.deq.fire && !stageKilled(4)) {
    when(io.boundary.fire && boundaryIndex === stage4BoundaryIndex) {
      boundaryValid(stage4BoundaryIndex) := true.B
    }.otherwise {
      boundaryValid(stage4BoundaryIndex) := false.B
    }
  }

  io.stageValid := VecInit(stages.map(_.io.deq.valid)).asUInt
  io.responseQueueCount := responseQueue.io.count
  io.boundaryCollision :=
    boundaryCollision &&
      !(stages(4).io.deq.fire && !stageKilled(4) && boundaryIndex === stage4BoundaryIndex)
}
