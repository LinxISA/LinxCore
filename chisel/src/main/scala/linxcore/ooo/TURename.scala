package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, PopCount, Valid, log2Ceil}
import linxcore.params.CoreParams
import linxcore.top.interface._

private[ooo] object TURename {
  def tTagWidth(p: CoreParams): Int = math.max(1, log2Ceil(p.ooo.tPhysRegs))
  def uTagWidth(p: CoreParams): Int = math.max(1, log2Ceil(p.ooo.uPhysRegs))
}

private[ooo] class TURenameIO(val p: CoreParams) extends Bundle {
  val prepare = Flipped(Valid(new D2AdmissionGroup(p)))
  val prepareReady = Output(Bool())
  val prepared = Output(new D3RenameGroup(p))
  val publish = Flipped(Valid(new D3RenameGroup(p)))
  val release = Flipped(Valid(new RenameCommitReleaseTxn(p)))
  val releaseExact = Output(Bool())
  val releaseApply = Input(Bool())
  val recoveryApply = Flipped(Valid(new RecoveryPlan(p)))
  val debugTCount = Output(Vec(p.ooo.stidCount,
    UInt((log2Ceil(p.ooo.tuMapQDepthPerStid + 1)).W)))
  val debugUCount = Output(Vec(p.ooo.stidCount,
    UInt((log2Ceil(p.ooo.tuMapQDepthPerStid + 1)).W)))
}

/** Independent relative T/U rename owner.
  *
  * T and U keep separate ordered MapQ rows, physical cursors, committed-head
  * cursors, and counts. Relative lookup walks the full retained sequence:
  * same-prefix rows override older MapQ rows, while stale release and recovery
  * both require exact ordered row identity.
  */
private[ooo] class TURename(val p: CoreParams) extends Module {
  val io = IO(new TURenameIO(p))

  private val width = p.widths.decodeWidth
  private val tTagWidth = TURename.tTagWidth(p)
  private val uTagWidth = TURename.uTagWidth(p)
  private val stidWidth = math.max(1, log2Ceil(p.ooo.stidCount))
  private val tuIndexWidth = math.max(1, log2Ceil(p.ooo.tuMapQDepthPerStid))
  private val tuCountWidth = math.max(1, log2Ceil(p.ooo.tuMapQDepthPerStid + 1))
  private val destSlots = width * p.maxDestinationOperands

  private def trunc(value: UInt, width: Int): UInt =
    if (value.getWidth == width) value
    else if (value.getWidth > width) value(width - 1, 0)
    else Cat(0.U((width - value.getWidth).W), value)

  private def safeStid(value: UInt): UInt =
    if (p.ooo.stidCount == 1) 0.U(stidWidth.W) else trunc(value, stidWidth)

  private def addWrap(
      ptr: UInt,
      gen: UInt,
      amount: UInt,
      entries: Int,
      ptrWidth: Int): (UInt, UInt) = {
    val sum = ptr +& amount
    (trunc(sum, ptrWidth), gen + (sum >= entries.U).asUInt)
  }

  private def sameRob(a: RobIdentity, b: RobIdentity): Bool =
    a.asUInt === b.asUInt

  private def robBeforeOrAt(a: RobIdentity, b: RobIdentity): Bool = {
    val slotWidth = math.max(1, log2Ceil(p.ooo.robGroupsPerStid))
    val orderWidth = p.ridGenerationWidth + slotWidth
    val aOrder = Cat(a.ridGeneration, a.ridSlot)
    val bOrder = Cat(b.ridGeneration, b.ridSlot)
    val delta = bOrder -% aOrder
    val sameGroup = a.ridGeneration === b.ridGeneration &&
      a.ridSlot === b.ridSlot
    a.peId === b.peId && a.stid === b.stid &&
      (sameGroup && a.memberIndex <= b.memberIndex ||
        (!sameGroup && delta < (BigInt(1) << (orderWidth - 1)).U))
  }

  val tMapQ = Reg(Vec(p.ooo.stidCount, Vec(p.ooo.tuMapQDepthPerStid,
    new RenameCommitReleaseEntry(p))))
  val uMapQ = Reg(Vec(p.ooo.stidCount, Vec(p.ooo.tuMapQDepthPerStid,
    new RenameCommitReleaseEntry(p))))

  val tHeadQ = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(tuIndexWidth.W))))
  val uHeadQ = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(tuIndexWidth.W))))
  val tTailQ = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(tuIndexWidth.W))))
  val uTailQ = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(tuIndexWidth.W))))
  val tHeadGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.localSeqGenerationWidth.W))))
  val uHeadGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.localSeqGenerationWidth.W))))

  val tTail = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(tTagWidth.W))))
  val uTail = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(uTagWidth.W))))
  val tTailQGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.localSeqGenerationWidth.W))))
  val uTailQGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.localSeqGenerationWidth.W))))
  val tTagGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.localSeqGenerationWidth.W))))
  val uTagGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.localSeqGenerationWidth.W))))
  val tCommittedTail = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(tTagWidth.W))))
  val uCommittedTail = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(uTagWidth.W))))
  val tCommittedGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.localSeqGenerationWidth.W))))
  val uCommittedGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.localSeqGenerationWidth.W))))
  val tCount = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(tuCountWidth.W))))
  val uCount = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(tuCountWidth.W))))

  io.debugTCount := tCount
  io.debugUCount := uCount

  val in = io.prepare.bits
  val stidRaw = in.entries(0).uop.instruction.parent.identity.stid
  val stid = safeStid(stidRaw)
  val stidInRange = stidRaw < p.ooo.stidCount.U
  val active = Wire(Vec(width, Bool()))
  for (lane <- 0 until width) {
    active(lane) := lane.U < in.count && in.entries(lane).uop.valid
  }

  val tDestActive = Wire(Vec(destSlots, Bool()))
  val uDestActive = Wire(Vec(destSlots, Bool()))
  for (lane <- 0 until width; dest <- 0 until p.maxDestinationOperands) {
    val flat = lane * p.maxDestinationOperands + dest
    val d = in.entries(lane).uop.destinations(dest)
    tDestActive(flat) := active(lane) && d.valid && d.kind === OperandKind.T
    uDestActive(flat) := active(lane) && d.valid && d.kind === OperandKind.U
  }

  val underflow = Wire(Vec(width * p.maxSourceOperands, Bool()))
  for (lane <- 0 until width; source <- 0 until p.maxSourceOperands) {
    val flat = lane * p.maxSourceOperands + source
    val src = in.entries(lane).uop.sources(source)
    val olderT = PopCount(tDestActive.take(lane * p.maxDestinationOperands))
    val olderU = PopCount(uDestActive.take(lane * p.maxDestinationOperands))
    val offset = src.relativeIndex +& 1.U
    underflow(flat) := active(lane) && src.valid && (
      (src.kind === OperandKind.T && offset > (tCount(stid) +& olderT)) ||
      (src.kind === OperandKind.U && offset > (uCount(stid) +& olderU)))
  }
  val tDemand = PopCount(tDestActive)
  val uDemand = PopCount(uDestActive)
  io.prepareReady := stidInRange && !underflow.asUInt.orR &&
    tCount(stid) +& tDemand <= p.ooo.tuMapQDepthPerStid.U &&
    uCount(stid) +& uDemand <= p.ooo.tuMapQDepthPerStid.U &&
    tCount(stid) +& tDemand <= p.ooo.tPhysRegs.U &&
    uCount(stid) +& uDemand <= p.ooo.uPhysRegs.U

  io.prepared := 0.U.asTypeOf(io.prepared)
  io.prepared.count := in.count
  io.prepared.groupCount := in.groupCount
  for (group <- 0 until width) {
    io.prepared.groups(group) := in.groups(group)
  }
  for (lane <- 0 until width) {
    io.prepared.entries(lane).trap := in.entries(lane).trap
    io.prepared.entries(lane).residentBound := in.entries(lane).residentBound
    io.prepared.entries(lane).brobBound := in.entries(lane).brobBound
    io.prepared.entries(lane).blockStart := in.entries(lane).uop.blockStart
    io.prepared.entries(lane).blockStop := in.entries(lane).uop.blockStop
    io.prepared.entries(lane).earlyRobComplete :=
      in.entries(lane).uop.earlyComplete
    io.prepared.entries(lane).uop.decoded := in.entries(lane).uop
    val olderLaneT = PopCount(tDestActive.take(lane * p.maxDestinationOperands))
    val olderLaneU = PopCount(uDestActive.take(lane * p.maxDestinationOperands))
    val laneTSeq = addWrap(tTailQ(stid), tTailQGeneration(stid), olderLaneT,
      p.ooo.tuMapQDepthPerStid, tuIndexWidth)
    val laneUSeq = addWrap(uTailQ(stid), uTailQGeneration(stid), olderLaneU,
      p.ooo.tuMapQDepthPerStid, tuIndexWidth)
    io.prepared.entries(lane).tSeqBefore.valid := active(lane)
    io.prepared.entries(lane).tSeqBefore.tag := laneTSeq._1
    io.prepared.entries(lane).tSeqBefore.generation := laneTSeq._2
    io.prepared.entries(lane).uSeqBefore.valid := active(lane)
    io.prepared.entries(lane).uSeqBefore.tag := laneUSeq._1
    io.prepared.entries(lane).uSeqBefore.generation := laneUSeq._2
    for (source <- 0 until p.maxSourceOperands) {
      val src = in.entries(lane).uop.sources(source)
      val out = io.prepared.entries(lane).uop.sources(source)
      out.valid := src.valid
      out.kind := src.kind
      out.atag := src.atag
      out.ready := true.B
      when(src.valid && src.kind === OperandKind.T) {
        val olderT = PopCount(tDestActive.take(lane * p.maxDestinationOperands))
        val offset = src.relativeIndex +& 1.U
        val retainedNeed = offset - olderT
        val retainedIndex = trunc(tTailQ(stid) - retainedNeed, tuIndexWidth)
        out.ttag := tMapQ(stid)(retainedIndex).history(0).ttag
        out.tGeneration := tMapQ(stid)(retainedIndex).history(0).tGeneration
        out.tSeqIndex := tMapQ(stid)(retainedIndex).history(0).tMapQIndex
        out.tSeqGeneration :=
          tMapQ(stid)(retainedIndex).history(0).tMapQGeneration
        out.ttagValid := true.B
        out.ready := offset > olderT
        for (flat <- 0 until lane * p.maxDestinationOperands) {
          val ordinal = PopCount(tDestActive.take(flat))
          val wanted = olderT - offset
          when(tDestActive(flat) && offset <= olderT && ordinal === wanted) {
            val physical = addWrap(tTail(stid), tTagGeneration(stid), ordinal,
              p.ooo.tPhysRegs, tTagWidth)
            val seq = addWrap(tTailQ(stid), tTailQGeneration(stid), ordinal,
              p.ooo.tuMapQDepthPerStid, tuIndexWidth)
            out.ttag := physical._1
            out.tGeneration := physical._2
            out.tSeqIndex := seq._1
            out.tSeqGeneration := seq._2
          }
        }
      }.elsewhen(src.valid && src.kind === OperandKind.U) {
        val olderU = PopCount(uDestActive.take(lane * p.maxDestinationOperands))
        val offset = src.relativeIndex +& 1.U
        val retainedNeed = offset - olderU
        val retainedIndex = trunc(uTailQ(stid) - retainedNeed, tuIndexWidth)
        out.utag := uMapQ(stid)(retainedIndex).history(0).utag
        out.uGeneration := uMapQ(stid)(retainedIndex).history(0).uGeneration
        out.uSeqIndex := uMapQ(stid)(retainedIndex).history(0).uMapQIndex
        out.uSeqGeneration :=
          uMapQ(stid)(retainedIndex).history(0).uMapQGeneration
        out.utagValid := true.B
        out.ready := offset > olderU
        for (flat <- 0 until lane * p.maxDestinationOperands) {
          val ordinal = PopCount(uDestActive.take(flat))
          val wanted = olderU - offset
          when(uDestActive(flat) && offset <= olderU && ordinal === wanted) {
            val physical = addWrap(uTail(stid), uTagGeneration(stid), ordinal,
              p.ooo.uPhysRegs, uTagWidth)
            val seq = addWrap(uTailQ(stid), uTailQGeneration(stid), ordinal,
              p.ooo.tuMapQDepthPerStid, tuIndexWidth)
            out.utag := physical._1
            out.uGeneration := physical._2
            out.uSeqIndex := seq._1
            out.uSeqGeneration := seq._2
          }
        }
      }
    }
    for (dest <- 0 until p.maxDestinationOperands) {
      val flat = lane * p.maxDestinationOperands + dest
      val din = in.entries(lane).uop.destinations(dest)
      val dout = io.prepared.entries(lane).uop.destinations(dest)
      val hist = io.prepared.entries(lane).history(dest)
      dout.valid := din.valid
      dout.kind := din.kind
      dout.atag := din.atag
      hist.valid := tDestActive(flat) || uDestActive(flat)
      hist.kind := din.kind
      hist.atag := din.atag
      when(tDestActive(flat)) {
        val ordinal = PopCount(tDestActive.take(flat))
        val physical = addWrap(tTail(stid), tTagGeneration(stid), ordinal,
          p.ooo.tPhysRegs, tTagWidth)
        val seq = addWrap(tTailQ(stid), tTailQGeneration(stid), ordinal,
          p.ooo.tuMapQDepthPerStid, tuIndexWidth)
        dout.ttag := physical._1
        dout.tGeneration := physical._2
        dout.tSeqIndex := seq._1
        dout.tSeqGeneration := seq._2
        dout.ttagValid := true.B
        hist.ttag := dout.ttag
        hist.tGeneration := dout.tGeneration
        hist.tMapQIndex := seq._1
        hist.tMapQGeneration := seq._2
      }.elsewhen(uDestActive(flat)) {
        val ordinal = PopCount(uDestActive.take(flat))
        val physical = addWrap(uTail(stid), uTagGeneration(stid), ordinal,
          p.ooo.uPhysRegs, uTagWidth)
        val seq = addWrap(uTailQ(stid), uTailQGeneration(stid), ordinal,
          p.ooo.tuMapQDepthPerStid, tuIndexWidth)
        dout.utag := physical._1
        dout.uGeneration := physical._2
        dout.uSeqIndex := seq._1
        dout.uSeqGeneration := seq._2
        dout.utagValid := true.B
        hist.utag := dout.utag
        hist.uGeneration := dout.uGeneration
        hist.uMapQIndex := seq._1
        hist.uMapQGeneration := seq._2
      }
    }
  }

  when(io.publish.valid) {
    val pub = io.publish.bits
    val st = safeStid(pub.entries(0).uop.decoded.instruction.parent.identity.stid)
    val publishedT = Wire(Vec(destSlots, Bool()))
    val publishedU = Wire(Vec(destSlots, Bool()))
    for (flat <- 0 until destSlots) {
      publishedT(flat) := false.B
      publishedU(flat) := false.B
    }
    for (lane <- 0 until width) {
      when(lane.U < pub.count && pub.entries(lane).uop.decoded.valid) {
        for (dest <- 0 until p.maxDestinationOperands) {
          val flat = lane * p.maxDestinationOperands + dest
          val hist = pub.entries(lane).history(dest)
          when(hist.valid && hist.kind === OperandKind.T) {
            val idx = trunc(tTailQ(st) + PopCount(publishedT.take(flat)),
              tuIndexWidth)
            val row = Wire(new RenameCommitReleaseEntry(p))
            row := 0.U.asTypeOf(row)
            row.valid := true.B
            row.rob := pub.entries(lane).uop.decoded.rob
            row.blockLast := pub.entries(lane).uop.decoded.blockBoundary
            row.history(0) := hist
            tMapQ(st)(idx) := row
            publishedT(flat) := true.B
          }.elsewhen(hist.valid && hist.kind === OperandKind.U) {
            val idx = trunc(uTailQ(st) + PopCount(publishedU.take(flat)),
              tuIndexWidth)
            val row = Wire(new RenameCommitReleaseEntry(p))
            row := 0.U.asTypeOf(row)
            row.valid := true.B
            row.rob := pub.entries(lane).uop.decoded.rob
            row.blockLast := pub.entries(lane).uop.decoded.blockBoundary
            row.history(0) := hist
            uMapQ(st)(idx) := row
            publishedU(flat) := true.B
          }
        }
      }
    }
    val tN = PopCount(publishedT)
    val uN = PopCount(publishedU)
    val nextTSeq = addWrap(tTailQ(st), tTailQGeneration(st), tN,
      p.ooo.tuMapQDepthPerStid, tuIndexWidth)
    val nextUSeq = addWrap(uTailQ(st), uTailQGeneration(st), uN,
      p.ooo.tuMapQDepthPerStid, tuIndexWidth)
    val nextTTag = addWrap(tTail(st), tTagGeneration(st), tN,
      p.ooo.tPhysRegs, tTagWidth)
    val nextUTag = addWrap(uTail(st), uTagGeneration(st), uN,
      p.ooo.uPhysRegs, uTagWidth)
    tTailQ(st) := nextTSeq._1
    tTailQGeneration(st) := nextTSeq._2
    uTailQ(st) := nextUSeq._1
    uTailQGeneration(st) := nextUSeq._2
    tTail(st) := nextTTag._1
    tTagGeneration(st) := nextTTag._2
    uTail(st) := nextUTag._1
    uTagGeneration(st) := nextUTag._2
    tCount(st) := tCount(st) + tN
    uCount(st) := uCount(st) + uN
  }

  val releaseSlotCount = p.widths.retireWidth * p.maxDestinationOperands
  val releaseTAccept = Wire(Vec(releaseSlotCount, Bool()))
  val releaseUAccept = Wire(Vec(releaseSlotCount, Bool()))
  val releaseTAttempt = Wire(Vec(releaseSlotCount, Bool()))
  val releaseUAttempt = Wire(Vec(releaseSlotCount, Bool()))
  releaseTAccept := VecInit(Seq.fill(releaseSlotCount)(false.B))
  releaseUAccept := VecInit(Seq.fill(releaseSlotCount)(false.B))
  releaseTAttempt := VecInit(Seq.fill(releaseSlotCount)(false.B))
  releaseUAttempt := VecInit(Seq.fill(releaseSlotCount)(false.B))
  val releaseTAllExact = Wire(Vec(p.ooo.stidCount, Bool()))
  val releaseUAllExact = Wire(Vec(p.ooo.stidCount, Bool()))
  for (st <- 0 until p.ooo.stidCount) {
    val attemptedT = (0 until releaseSlotCount).map { flat =>
      val lane = flat / p.maxDestinationOperands
      releaseTAttempt(flat) && io.release.bits.lanes(lane).rob.stid === st.U
    }
    val attemptedU = (0 until releaseSlotCount).map { flat =>
      val lane = flat / p.maxDestinationOperands
      releaseUAttempt(flat) && io.release.bits.lanes(lane).rob.stid === st.U
    }
    val exactT = (0 until releaseSlotCount).map { flat =>
      val lane = flat / p.maxDestinationOperands
      releaseTAccept(flat) && io.release.bits.lanes(lane).rob.stid === st.U
    }
    val exactU = (0 until releaseSlotCount).map { flat =>
      val lane = flat / p.maxDestinationOperands
      releaseUAccept(flat) && io.release.bits.lanes(lane).rob.stid === st.U
    }
    releaseTAllExact(st) := !VecInit(attemptedT).asUInt.orR ||
      PopCount(attemptedT) === PopCount(exactT)
    releaseUAllExact(st) := !VecInit(attemptedU).asUInt.orR ||
      PopCount(attemptedU) === PopCount(exactU)
  }
  val releaseAllExact = releaseTAllExact.asUInt.andR &&
    releaseUAllExact.asUInt.andR
  io.releaseExact := !io.release.valid || releaseAllExact
  when(io.release.valid) {
    for (flat <- 0 until releaseSlotCount) {
      val lane = flat / p.maxDestinationOperands
      val dest = flat % p.maxDestinationOperands
      val rel = io.release.bits.lanes(lane)
      val hist = rel.history(dest)
      val st = safeStid(rel.rob.stid)
      val priorT = PopCount((0 until flat).map { prior =>
        val priorLane = prior / p.maxDestinationOperands
        val priorRel = io.release.bits.lanes(priorLane)
        releaseTAccept(prior) && priorRel.rob.stid === rel.rob.stid
      })
      val priorU = PopCount((0 until flat).map { prior =>
        val priorLane = prior / p.maxDestinationOperands
        val priorRel = io.release.bits.lanes(priorLane)
        releaseUAccept(prior) && priorRel.rob.stid === rel.rob.stid
      })
      val tIdx = trunc(tHeadQ(st) + priorT, tuIndexWidth)
      val uIdx = trunc(uHeadQ(st) + priorU, tuIndexWidth)
      val tRow = tMapQ(st)(tIdx)
      val uRow = uMapQ(st)(uIdx)
      val laneLive = rel.valid && lane.U < io.release.bits.count
      val tExact = laneLive && hist.valid && hist.kind === OperandKind.T &&
        rel.rob.stid < p.ooo.stidCount.U && priorT < tCount(st) &&
        tRow.valid && sameRob(rel.rob, tRow.rob) &&
        hist.asUInt === tRow.history(0).asUInt
      val uExact = laneLive && hist.valid && hist.kind === OperandKind.U &&
        rel.rob.stid < p.ooo.stidCount.U && priorU < uCount(st) &&
        uRow.valid && sameRob(rel.rob, uRow.rob) &&
        hist.asUInt === uRow.history(0).asUInt
      releaseTAttempt(flat) := laneLive && hist.valid &&
        hist.kind === OperandKind.T
      releaseUAttempt(flat) := laneLive && hist.valid &&
        hist.kind === OperandKind.U
      releaseTAccept(flat) := tExact
      releaseUAccept(flat) := uExact
      when(tExact && releaseTAllExact(st) && releaseAllExact &&
        io.releaseApply) {
        val nextCommitted = addWrap(tRow.history(0).ttag,
          tRow.history(0).tGeneration, 1.U, p.ooo.tPhysRegs, tTagWidth)
        tCommittedTail(st) := nextCommitted._1
        tCommittedGeneration(st) := nextCommitted._2
        tRow.valid := false.B
      }
      when(uExact && releaseUAllExact(st) && releaseAllExact &&
        io.releaseApply) {
        val nextCommitted = addWrap(uRow.history(0).utag,
          uRow.history(0).uGeneration, 1.U, p.ooo.uPhysRegs, uTagWidth)
        uCommittedTail(st) := nextCommitted._1
        uCommittedGeneration(st) := nextCommitted._2
        uRow.valid := false.B
      }
    }
    for (st <- 0 until p.ooo.stidCount) {
      val acceptedT = PopCount((0 until releaseSlotCount).map { flat =>
        val lane = flat / p.maxDestinationOperands
        releaseTAccept(flat) && io.release.bits.lanes(lane).rob.stid === st.U
      })
      val acceptedU = PopCount((0 until releaseSlotCount).map { flat =>
        val lane = flat / p.maxDestinationOperands
        releaseUAccept(flat) && io.release.bits.lanes(lane).rob.stid === st.U
      })
      val nextTHead = addWrap(tHeadQ(st), tHeadGeneration(st), acceptedT,
        p.ooo.tuMapQDepthPerStid, tuIndexWidth)
      val nextUHead = addWrap(uHeadQ(st), uHeadGeneration(st), acceptedU,
        p.ooo.tuMapQDepthPerStid, tuIndexWidth)
      when(acceptedT.orR && releaseTAllExact(st) && releaseAllExact &&
        io.releaseApply) {
        tHeadQ(st) := nextTHead._1
        tHeadGeneration(st) := nextTHead._2
        tCount(st) := tCount(st) - acceptedT
      }
      when(acceptedU.orR && releaseUAllExact(st) && releaseAllExact &&
        io.releaseApply) {
        uHeadQ(st) := nextUHead._1
        uHeadGeneration(st) := nextUHead._2
        uCount(st) := uCount(st) - acceptedU
      }
    }
  }

  when(io.recoveryApply.valid && io.recoveryApply.bits.phase === RecoveryPhase.Apply) {
    val st = safeStid(io.recoveryApply.bits.trigger.stid)
    val tBeforeSurvivor = Wire(Vec(p.ooo.tuMapQDepthPerStid, Bool()))
    val uBeforeSurvivor = Wire(Vec(p.ooo.tuMapQDepthPerStid, Bool()))
    for (offset <- 0 until p.ooo.tuMapQDepthPerStid) {
      val tIdx = trunc(tHeadQ(st) + offset.U, tuIndexWidth)
      val uIdx = trunc(uHeadQ(st) + offset.U, tuIndexWidth)
      tBeforeSurvivor(offset) := offset.U < tCount(st) &&
        io.recoveryApply.bits.survivingTailValid &&
        tMapQ(st)(tIdx).valid &&
        robBeforeOrAt(tMapQ(st)(tIdx).rob,
          io.recoveryApply.bits.survivingTail)
      uBeforeSurvivor(offset) := offset.U < uCount(st) &&
        io.recoveryApply.bits.survivingTailValid &&
        uMapQ(st)(uIdx).valid &&
        robBeforeOrAt(uMapQ(st)(uIdx).rob,
          io.recoveryApply.bits.survivingTail)
    }
    val tSurvivors = Mux(io.recoveryApply.bits.survivingTailValid,
      trunc(PopCount(tBeforeSurvivor), tuCountWidth), 0.U)
    val uSurvivors = Mux(io.recoveryApply.bits.survivingTailValid,
      trunc(PopCount(uBeforeSurvivor), tuCountWidth), 0.U)
    for (offset <- 1 until p.ooo.tuMapQDepthPerStid) {
      when(tBeforeSurvivor(offset)) {
        assert(tBeforeSurvivor(offset - 1),
          "T recovery survivors must form an ordered prefix")
      }
      when(uBeforeSurvivor(offset)) {
        assert(uBeforeSurvivor(offset - 1),
          "U recovery survivors must form an ordered prefix")
      }
    }

    for (offset <- 0 until p.ooo.tuMapQDepthPerStid) {
      val tIdx = trunc(tHeadQ(st) + offset.U, tuIndexWidth)
      val uIdx = trunc(uHeadQ(st) + offset.U, tuIndexWidth)
      when(offset.U < tCount(st) && offset.U >= tSurvivors) {
        tMapQ(st)(tIdx).valid := false.B
      }
      when(offset.U < uCount(st) && offset.U >= uSurvivors) {
        uMapQ(st)(uIdx).valid := false.B
      }
    }

    val nextTTag = Wire(UInt(tTagWidth.W))
    val nextUTag = Wire(UInt(uTagWidth.W))
    val nextTTagGeneration = Wire(UInt(p.ooo.localSeqGenerationWidth.W))
    val nextUTagGeneration = Wire(UInt(p.ooo.localSeqGenerationWidth.W))
    nextTTag := tCommittedTail(st)
    nextUTag := uCommittedTail(st)
    nextTTagGeneration := tCommittedGeneration(st)
    nextUTagGeneration := uCommittedGeneration(st)
    for (offset <- 0 until p.ooo.tuMapQDepthPerStid) {
      val tIdx = trunc(tHeadQ(st) + offset.U, tuIndexWidth)
      val uIdx = trunc(uHeadQ(st) + offset.U, tuIndexWidth)
      when(tSurvivors.orR && offset.U === tSurvivors - 1.U) {
        val next = addWrap(tMapQ(st)(tIdx).history(0).ttag,
          tMapQ(st)(tIdx).history(0).tGeneration, 1.U,
          p.ooo.tPhysRegs, tTagWidth)
        nextTTag := next._1
        nextTTagGeneration := next._2
      }
      when(uSurvivors.orR && offset.U === uSurvivors - 1.U) {
        val next = addWrap(uMapQ(st)(uIdx).history(0).utag,
          uMapQ(st)(uIdx).history(0).uGeneration, 1.U,
          p.ooo.uPhysRegs, uTagWidth)
        nextUTag := next._1
        nextUTagGeneration := next._2
      }
    }
    val nextTSeq = addWrap(tHeadQ(st), tHeadGeneration(st), tSurvivors,
      p.ooo.tuMapQDepthPerStid, tuIndexWidth)
    val nextUSeq = addWrap(uHeadQ(st), uHeadGeneration(st), uSurvivors,
      p.ooo.tuMapQDepthPerStid, tuIndexWidth)
    tTailQ(st) := nextTSeq._1
    uTailQ(st) := nextUSeq._1
    tTail(st) := nextTTag
    tTagGeneration(st) := nextTTagGeneration
    tTailQGeneration(st) := nextTSeq._2
    uTail(st) := nextUTag
    uTagGeneration(st) := nextUTagGeneration
    uTailQGeneration(st) := nextUSeq._2
    tCount(st) := tSurvivors
    uCount(st) := uSurvivors
  }
}
