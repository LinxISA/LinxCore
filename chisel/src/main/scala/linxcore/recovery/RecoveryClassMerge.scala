package linxcore.recovery

import chisel3._
import chisel3.util.{is, log2Ceil, switch}

import linxcore.bctrl.BID
import linxcore.rob.{ROBID}

object RecoveryActionClass extends ChiselEnum {
  val GlobalFlush, GlobalReplay, PeScoped = Value
}

class RecoveryClassMergeIO(
    val stidCount: Int,
    val peCount: Int,
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sourceCount: Int = 1)
    extends Bundle {
  private val stidIndexWidth = math.max(1, log2Ceil(stidCount))
  private val peIndexWidth = math.max(1, log2Ceil(peCount))

  val in = Input(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val inProvenance = Input(new RecoveryProvenance(sourceCount))
  val inReady = Output(Bool())
  val inAccepted = Output(Bool())
  val inBlockedByStid = Output(Bool())
  val inBlockedByPe = Output(Bool())
  val inDroppedByOlder = Output(Bool())
  val inDroppedByComplete = Output(Bool())
  val inMerged = Output(Bool())

  val oldestBid = Input(Vec(stidCount, new ROBID(entries)))
  val oldestBlockComplete = Input(Vec(stidCount, Bool()))

  val out = Output(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val outProvenance = Output(new RecoveryProvenance(sourceCount))
  val outReady = Input(Bool())
  val outAccepted = Output(Bool())
  val selectedClass = Output(RecoveryActionClass())
  val selectedStid = Output(UInt(stidIndexWidth.W))
  val selectedPe = Output(UInt(peIndexWidth.W))

  val globalFlushPendingMask = Output(UInt(stidCount.W))
  val globalReplayPendingMask = Output(UInt(stidCount.W))
  val pePendingMask = Output(UInt((stidCount * peCount).W))
  val pending = Output(Bool())
  val resolvedMask = Output(UInt(sourceCount.W))
}

/** Stateful model-equivalent recovery class owner.
  *
  * Reports arrive after producer retention and exact full-BID promotion. The
  * model's global-flush, global-replay, and per-PE lanes are kept separately
  * for every instantiated STID. An accepted action is copied into an
  * irrevocable output slot, so later reports may update queued class state
  * without violating downstream valid/ready stability.
  */
class RecoveryClassMerge(
    val stidCount: Int,
    val peCount: Int,
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sourceCount: Int = 1)
    extends Module {
  require(stidCount > 0, "recovery class merge must expose at least one STID")
  require(peCount > 0, "recovery class merge must expose at least one PE")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "STID count must fit stidWidth")
  require(BigInt(peCount) <= (BigInt(1) << peIdWidth), "PE count must fit peIdWidth")

  private val stidIndexWidth = math.max(1, log2Ceil(stidCount))
  private val peIndexWidth = math.max(1, log2Ceil(peCount))
  private val laneSumWidth = math.max(2, log2Ceil(stidCount * 2))
  private def requestType = new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth)

  val io = IO(new RecoveryClassMergeIO(
    stidCount,
    peCount,
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    sourceCount
  ))

  private def annotateFull(req: FullBidFlushReq): FlushBus = {
    val ringReq = Wire(new FlushReq(entries, peIdWidth, stidWidth, tidWidth))
    ringReq := 0.U.asTypeOf(ringReq)
    ringReq.valid := req.valid
    ringReq.typ := req.typ
    ringReq.peId := req.peId
    ringReq.tid := req.tid
    ringReq.stid := req.stid
    ringReq.bid := FullBidRecoveryBridge.fullBidToRobId(req.blockBid, req.valid, entries, bidWidth)
    ringReq.gid := req.gid
    ringReq.rid := req.rid
    ringReq.lsId := req.lsId
    ringReq.execEngine := req.execEngine
    ringReq.fetchTpcValid := req.fetchTpcValid
    ringReq.fetchTpc := req.fetchTpc
    ringReq.immediateFlush := req.immediateFlush
    FlushControl.annotate(ringReq)
  }

  private def mergeSignal(
      srcReq: FullBidFlushReq,
      src: FlushBus,
      dstReq: FullBidFlushReq,
      dst: FlushBus): (Bool, FullBidFlushReq) = {
    val baseOnBid = src.baseOnBid || dst.baseOnBid
    val dstLessEqualSrc = Mux(
      baseOnBid,
      ROBID.lessEqual(dst.req.bid, src.req.bid),
      FlushControl.lessEqualBidRid(dst.req.bid, dst.req.rid, src.req.bid, src.req.rid)
    )
    val samePe = src.req.peId === dst.req.peId
    val mergeInner = !baseOnBid && samePe && dstLessEqualSrc && (src.req.typ === FlushType.InnerFlush)
    val mergeNuke = baseOnBid && samePe && ROBID.less(dst.req.bid, src.req.bid) &&
      (src.req.typ === FlushType.NukeFlush)
    val mergedValid = mergeInner || mergeNuke
    val merged = Wire(chiselTypeOf(srcReq))
    merged := srcReq
    when(mergedValid) {
      merged := dstReq
      merged.valid := true.B
      merged.typ := FlushType.InnerFlush
    }
    (mergedValid, merged)
  }

  val globalFlush = RegInit(VecInit(Seq.fill(stidCount)(0.U.asTypeOf(requestType))))
  val globalReplay = RegInit(VecInit(Seq.fill(stidCount)(0.U.asTypeOf(requestType))))
  val peScoped = RegInit(VecInit(Seq.fill(stidCount)(
    VecInit(Seq.fill(peCount)(0.U.asTypeOf(requestType)))
  )))
  private def provenanceType = new RecoveryProvenance(sourceCount)
  val globalFlushProvenance = RegInit(VecInit(Seq.fill(stidCount)(0.U.asTypeOf(provenanceType))))
  val globalReplayProvenance = RegInit(VecInit(Seq.fill(stidCount)(0.U.asTypeOf(provenanceType))))
  val peScopedProvenance = RegInit(VecInit(Seq.fill(stidCount)(
    VecInit(Seq.fill(peCount)(0.U.asTypeOf(provenanceType)))
  )))
  val nextStid = RegInit(0.U(stidIndexWidth.W))

  val flushBus = Wire(Vec(stidCount, new FlushBus(entries, peIdWidth, stidWidth, tidWidth)))
  val replayBus = Wire(Vec(stidCount, new FlushBus(entries, peIdWidth, stidWidth, tidWidth)))
  val peBus = Wire(Vec(stidCount, Vec(peCount, new FlushBus(entries, peIdWidth, stidWidth, tidWidth))))
  for (lane <- 0 until stidCount) {
    flushBus(lane) := annotateFull(globalFlush(lane))
    replayBus(lane) := annotateFull(globalReplay(lane))
    for (pe <- 0 until peCount) {
      peBus(lane)(pe) := annotateFull(peScoped(lane)(pe))
    }
  }
  val inBus = annotateFull(io.in)

  val laneValid = Wire(Vec(stidCount, Bool()))
  val laneClass = Wire(Vec(stidCount, RecoveryActionClass()))
  val lanePe = Wire(Vec(stidCount, UInt(peIndexWidth.W)))
  val laneReq = Wire(Vec(stidCount, requestType))
  val laneProvenance = Wire(Vec(stidCount, provenanceType))
  val replayCancelsFlush = Wire(Vec(stidCount, Bool()))
  for (lane <- 0 until stidCount) {
    var firstPeValid = false.B
    var firstPe = 0.U(peIndexWidth.W)
    for (pe <- 0 until peCount) {
      val take = !firstPeValid && peScoped(lane)(pe).valid
      firstPe = Mux(take, pe.U, firstPe)
      firstPeValid = firstPeValid || peScoped(lane)(pe).valid
    }
    replayCancelsFlush(lane) := globalFlush(lane).valid && globalReplay(lane).valid &&
      ROBID.lessEqual(replayBus(lane).req.bid, flushBus(lane).req.bid)
    laneValid(lane) := globalFlush(lane).valid || globalReplay(lane).valid || firstPeValid
    laneClass(lane) := RecoveryActionClass.PeScoped
    lanePe(lane) := firstPe
    laneReq(lane) := (if (peCount == 1) peScoped(lane)(0) else peScoped(lane)(firstPe))
    laneProvenance(lane) :=
      (if (peCount == 1) peScopedProvenance(lane)(0) else peScopedProvenance(lane)(firstPe))
    when(globalReplay(lane).valid && (!globalFlush(lane).valid || replayCancelsFlush(lane))) {
      laneClass(lane) := RecoveryActionClass.GlobalReplay
      lanePe(lane) := 0.U
      laneReq(lane) := globalReplay(lane)
      laneProvenance(lane) := globalReplayProvenance(lane)
    }.elsewhen(globalFlush(lane).valid) {
      laneClass(lane) := RecoveryActionClass.GlobalFlush
      lanePe(lane) := 0.U
      laneReq(lane) := globalFlush(lane)
      laneProvenance(lane) := globalFlushProvenance(lane)
    }.elsewhen(globalReplay(lane).valid) {
      laneClass(lane) := RecoveryActionClass.GlobalReplay
      lanePe(lane) := 0.U
      laneReq(lane) := globalReplay(lane)
      laneProvenance(lane) := globalReplayProvenance(lane)
    }
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
  val selectedClass = if (stidCount == 1) laneClass(0) else laneClass(selectedLane)
  val selectedPe = if (stidCount == 1) lanePe(0) else lanePe(selectedLane)
  val selectedReq = if (stidCount == 1) laneReq(0) else laneReq(selectedLane)
  val selectedProvenance =
    if (stidCount == 1) laneProvenance(0) else laneProvenance(selectedLane)

  val outPending = RegInit(false.B)
  val outReq = RegInit(0.U.asTypeOf(requestType))
  val outClass = RegInit(RecoveryActionClass.GlobalFlush)
  val outStid = RegInit(0.U(stidIndexWidth.W))
  val outPe = RegInit(0.U(peIndexWidth.W))
  val outProvenance = RegInit(0.U.asTypeOf(provenanceType))
  val outSlotReady = !outPending || io.outReady
  val dispatch = outSlotReady && selectedValid

  io.out := outReq
  io.out.valid := outPending
  io.outAccepted := outPending && io.outReady
  io.outProvenance := outProvenance
  io.selectedClass := outClass
  io.selectedStid := outStid
  io.selectedPe := outPe

  when(outSlotReady) {
    outPending := dispatch
    when(dispatch) {
      outReq := selectedReq
      outReq.valid := true.B
      outClass := selectedClass
      outStid := selectedLane
      outPe := selectedPe
      outProvenance := selectedProvenance
    }
  }

  val nextFlush = WireInit(globalFlush)
  val nextReplay = WireInit(globalReplay)
  val nextPeScoped = WireInit(peScoped)
  val nextFlushProvenance = WireInit(globalFlushProvenance)
  val nextReplayProvenance = WireInit(globalReplayProvenance)
  val nextPeScopedProvenance = WireInit(peScopedProvenance)
  val resolved = WireInit(VecInit(Seq.fill(sourceCount)(false.B)))

  def resolve(mask: UInt, condition: Bool): Unit = {
    for (source <- 0 until sourceCount) {
      when(condition && mask(source)) {
        resolved(source) := true.B
      }
    }
  }

  when(dispatch) {
    for (lane <- 0 until stidCount) {
      when(selectedLane === lane.U) {
        switch(selectedClass) {
          is(RecoveryActionClass.GlobalFlush) {
            nextFlush(lane).valid := false.B
            nextFlushProvenance(lane) := 0.U.asTypeOf(nextFlushProvenance(lane))
          }
          is(RecoveryActionClass.GlobalReplay) {
            nextReplay(lane).valid := false.B
            nextReplayProvenance(lane) := 0.U.asTypeOf(nextReplayProvenance(lane))
            when(replayCancelsFlush(lane)) {
              resolve(globalFlushProvenance(lane).causeMask, true.B)
              nextFlush(lane).valid := false.B
              nextFlushProvenance(lane) := 0.U.asTypeOf(nextFlushProvenance(lane))
            }
          }
          is(RecoveryActionClass.PeScoped) {
            for (pe <- 0 until peCount) {
              when(selectedPe === pe.U) {
                nextPeScoped(lane)(pe).valid := false.B
                nextPeScopedProvenance(lane)(pe) :=
                  0.U.asTypeOf(nextPeScopedProvenance(lane)(pe))
              }
            }
          }
        }
      }
    }
    nextStid := Mux(selectedLane === (stidCount - 1).U, 0.U, selectedLane + 1.U)
  }

  val inStidRange = io.in.stid < stidCount.U
  val inPeRange = io.in.peId < peCount.U
  val inPeIndex = io.in.peId(peIndexWidth - 1, 0)
  io.inReady := inStidRange && inPeRange
  io.inAccepted := io.in.valid && io.inReady
  io.inBlockedByStid := io.in.valid && !inStidRange
  io.inBlockedByPe := io.in.valid && inStidRange && !inPeRange
  io.inDroppedByOlder := false.B
  io.inDroppedByComplete := false.B
  io.inMerged := false.B

  for (lane <- 0 until stidCount) {
    val laneSelected = dispatch && (selectedLane === lane.U)
    val flushEffective = globalFlush(lane).valid &&
      !(laneSelected && ((selectedClass === RecoveryActionClass.GlobalFlush) ||
        ((selectedClass === RecoveryActionClass.GlobalReplay) && replayCancelsFlush(lane))))
    val replayEffective = globalReplay(lane).valid &&
      !(laneSelected && (selectedClass === RecoveryActionClass.GlobalReplay))
    val peEffective = Wire(Vec(peCount, Bool()))
    for (pe <- 0 until peCount) {
      peEffective(pe) := peScoped(lane)(pe).valid &&
        !(laneSelected && (selectedClass === RecoveryActionClass.PeScoped) && (selectedPe === pe.U))
    }

    when(io.inAccepted && (io.in.stid === lane.U)) {
      val inputPeScoped = inBus.baseOnPE || inBus.baseOnThread
      val inputGlobalFlush = !inputPeScoped && FlushControl.isFlushType(io.in.typ)
      val inputGlobalReplay = !inputPeScoped && !FlushControl.isFlushType(io.in.typ)
      val targetPeEffective = if (peCount == 1) peEffective(0) else peEffective(inPeIndex)
      val targetPeBus = if (peCount == 1) peBus(lane)(0) else peBus(lane)(inPeIndex)
      val targetPeReq = if (peCount == 1) peScoped(lane)(0) else peScoped(lane)(inPeIndex)
      val targetPeProvenance =
        if (peCount == 1) peScopedProvenance(lane)(0) else peScopedProvenance(lane)(inPeIndex)
      val targetPeOlder = targetPeEffective &&
        FlushControl.checkOlder(targetPeBus, inBus, io.oldestBid(lane))
      val flushOlder = flushEffective &&
        FlushControl.checkOlder(flushBus(lane), inBus, io.oldestBid(lane))
      val inputOlderThanFlush = flushEffective &&
        FlushControl.checkOlder(inBus, flushBus(lane), io.oldestBid(lane))
      val replayOlder = replayEffective &&
        FlushControl.checkOlder(replayBus(lane), inBus, io.oldestBid(lane))

      when(inputGlobalFlush) {
        when(flushOlder) {
          io.inDroppedByOlder := true.B
          resolve(io.inProvenance.causeMask, true.B)
        }.elsewhen((io.in.typ =/= FlushType.MissPredFlush) && targetPeOlder) {
          val (mergedValid, mergedReq) = mergeSignal(
            io.in,
            inBus,
            targetPeReq,
            targetPeBus
          )
          when(mergedValid) {
            val mergedBus = annotateFull(mergedReq)
            io.inMerged := true.B
            val mergedProvenance = RecoveryProvenance.merged(io.inProvenance, targetPeProvenance)
            for (pe <- 0 until peCount) {
              when(io.in.peId === pe.U) {
                nextPeScoped(lane)(pe).valid := false.B
                nextPeScopedProvenance(lane)(pe) :=
                  0.U.asTypeOf(nextPeScopedProvenance(lane)(pe))
              }
            }
            when(mergedBus.baseOnPE || mergedBus.baseOnThread) {
              for (pe <- 0 until peCount) {
                when(mergedReq.peId === pe.U) {
                  nextPeScoped(lane)(pe) := mergedReq
                  nextPeScopedProvenance(lane)(pe) := mergedProvenance
                }
              }
            }.otherwise {
              nextFlush(lane) := mergedReq
              nextFlushProvenance(lane) := mergedProvenance
              for (pe <- 0 until peCount) {
                when(peEffective(pe) && FlushControl.checkOlder(mergedBus, peBus(lane)(pe), io.oldestBid(lane))) {
                  resolve(
                    peScopedProvenance(lane)(pe).causeMask,
                    io.in.peId =/= pe.U
                  )
                  nextPeScoped(lane)(pe).valid := false.B
                  nextPeScopedProvenance(lane)(pe) :=
                    0.U.asTypeOf(nextPeScopedProvenance(lane)(pe))
                }
              }
              when(replayEffective && FlushControl.checkOlder(mergedBus, replayBus(lane), io.oldestBid(lane))) {
                resolve(globalReplayProvenance(lane).causeMask, true.B)
                nextReplay(lane).valid := false.B
                nextReplayProvenance(lane) := 0.U.asTypeOf(nextReplayProvenance(lane))
              }
            }
          }.otherwise {
            io.inDroppedByOlder := true.B
            resolve(io.inProvenance.causeMask, true.B)
          }
        }.otherwise {
          resolve(globalFlushProvenance(lane).causeMask, flushEffective)
          nextFlush(lane) := io.in
          nextFlushProvenance(lane) := io.inProvenance
          for (pe <- 0 until peCount) {
            when(peEffective(pe) && FlushControl.checkOlder(inBus, peBus(lane)(pe), io.oldestBid(lane))) {
              resolve(peScopedProvenance(lane)(pe).causeMask, true.B)
              nextPeScoped(lane)(pe).valid := false.B
              nextPeScopedProvenance(lane)(pe) :=
                0.U.asTypeOf(nextPeScopedProvenance(lane)(pe))
            }
          }
          when(replayEffective && FlushControl.checkOlder(inBus, replayBus(lane), io.oldestBid(lane))) {
            resolve(globalReplayProvenance(lane).causeMask, true.B)
            nextReplay(lane).valid := false.B
            nextReplayProvenance(lane) := 0.U.asTypeOf(nextReplayProvenance(lane))
          }
        }
      }.elsewhen(inputGlobalReplay) {
        when(io.oldestBlockComplete(lane)) {
          io.inDroppedByComplete := true.B
          resolve(io.inProvenance.causeMask, true.B)
        }.elsewhen(
          flushEffective && (globalFlush(lane).typ === FlushType.MissPredFlush) && flushOlder
        ) {
          io.inDroppedByOlder := true.B
          resolve(io.inProvenance.causeMask, true.B)
        }.elsewhen(replayOlder) {
          io.inDroppedByOlder := true.B
          resolve(io.inProvenance.causeMask, true.B)
        }.otherwise {
          resolve(globalReplayProvenance(lane).causeMask, replayEffective)
          nextReplay(lane) := io.in
          nextReplayProvenance(lane) := io.inProvenance
          for (pe <- 0 until peCount) {
            when(peEffective(pe) && FlushControl.checkOlder(inBus, peBus(lane)(pe), io.oldestBid(lane))) {
              resolve(peScopedProvenance(lane)(pe).causeMask, true.B)
              nextPeScoped(lane)(pe).valid := false.B
              nextPeScopedProvenance(lane)(pe) :=
                0.U.asTypeOf(nextPeScopedProvenance(lane)(pe))
            }
          }
        }
      }.otherwise {
        val (flushMergeValid, flushMergedReq) = mergeSignal(
          globalFlush(lane),
          flushBus(lane),
          io.in,
          inBus
        )
        when(flushOlder || (flushEffective && (globalFlush(lane).peId === io.in.peId) && inputOlderThanFlush)) {
          when(flushMergeValid) {
            val mergedBus = annotateFull(flushMergedReq)
            io.inMerged := true.B
            val mergedProvenance = RecoveryProvenance.merged(globalFlushProvenance(lane), io.inProvenance)
            nextFlush(lane).valid := false.B
            nextFlushProvenance(lane) := 0.U.asTypeOf(nextFlushProvenance(lane))
            when(mergedBus.baseOnPE || mergedBus.baseOnThread) {
              for (pe <- 0 until peCount) {
                when(flushMergedReq.peId === pe.U) {
                  resolve(peScopedProvenance(lane)(pe).causeMask, peEffective(pe))
                  nextPeScoped(lane)(pe) := flushMergedReq
                  nextPeScopedProvenance(lane)(pe) := mergedProvenance
                }
              }
            }.otherwise {
              nextFlush(lane) := flushMergedReq
              nextFlushProvenance(lane) := mergedProvenance
            }
          }.otherwise {
            io.inDroppedByOlder := true.B
            resolve(io.inProvenance.causeMask, true.B)
          }
        }.elsewhen(replayOlder) {
          io.inDroppedByOlder := true.B
          resolve(io.inProvenance.causeMask, true.B)
        }.elsewhen(targetPeOlder) {
          io.inDroppedByOlder := true.B
          resolve(io.inProvenance.causeMask, true.B)
        }.otherwise {
          for (pe <- 0 until peCount) {
            when(io.in.peId === pe.U) {
              resolve(peScopedProvenance(lane)(pe).causeMask, peEffective(pe))
              nextPeScoped(lane)(pe) := io.in
              nextPeScopedProvenance(lane)(pe) := io.inProvenance
            }
          }
        }
      }
    }
  }

  globalFlush := nextFlush
  globalReplay := nextReplay
  peScoped := nextPeScoped
  globalFlushProvenance := nextFlushProvenance
  globalReplayProvenance := nextReplayProvenance
  peScopedProvenance := nextPeScopedProvenance
  io.resolvedMask := resolved.asUInt

  io.globalFlushPendingMask := VecInit(globalFlush.map(_.valid)).asUInt
  io.globalReplayPendingMask := VecInit(globalReplay.map(_.valid)).asUInt
  io.pePendingMask := VecInit(peScoped.flatMap(_.map(_.valid))).asUInt
  io.pending := outPending || io.globalFlushPendingMask.orR ||
    io.globalReplayPendingMask.orR || io.pePendingMask.orR
}
