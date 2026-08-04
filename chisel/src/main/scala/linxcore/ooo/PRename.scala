package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, PopCount, PriorityEncoder, Valid, log2Ceil}
import linxcore.params.CoreParams
import linxcore.top.interface._

private[ooo] object PRename {
  def tagWidth(p: CoreParams): Int = p.ooo.gprTagWidth
  def committedTag(p: CoreParams, stid: Int, atag: Int): Int =
    stid * p.ooo.gprArchRegs + atag
  def isCommittedResetTag(p: CoreParams, tag: UInt): Bool =
    tag < (p.ooo.stidCount * p.ooo.gprArchRegs).U
}

private[ooo] class PRenameIO(val p: CoreParams) extends Bundle {
  val prepare = Flipped(Valid(new D2AdmissionGroup(p)))
  val prepareReady = Output(Bool())
  val prepared = Output(new D3RenameGroup(p))
  val reserve = Flipped(Valid(new D3RenameGroup(p)))
  val publish = Flipped(Valid(new D3RenameGroup(p)))
  val cancel = Flipped(Valid(UInt(p.ooo.stidWidth.W)))
  val release = Flipped(Valid(new RenameCommitReleaseTxn(p)))
  val releaseExact = Output(Bool())
  val releaseApply = Input(Bool())
  val recoveryApply = Flipped(Valid(new RecoveryPlan(p)))
  val debugPMap = Output(Vec(p.ooo.stidCount,
    Vec(p.ooo.gprArchRegs, UInt(PRename.tagWidth(p).W))))
}

/** Independent P-register rename owner.
  *
  * Owns P SMAP/CMAP, physical free state, per-tag generation, and P MapQ. It
  * prepares a combinational overlay for RENU and mutates state only when RENU
  * sends the common D3 publish pulse.
  */
private[ooo] class PRename(val p: CoreParams) extends Module {
  val io = IO(new PRenameIO(p))

  private val width = p.widths.decodeWidth
  private val pTagWidth = PRename.tagWidth(p)
  private val physicalPTagWidth = math.max(1, log2Ceil(p.ooo.gprPhysRegs))
  private val archIndexWidth = math.max(1, log2Ceil(p.ooo.gprArchRegs))
  private val physicalStidWidth = math.max(1, log2Ceil(p.ooo.stidCount))
  private val mapQIndexWidth = math.max(1, log2Ceil(p.ooo.gprMapQDepthPerStid))
  private val mapQCountWidth = math.max(1, log2Ceil(p.ooo.gprMapQDepthPerStid + 1))
  private val pDestSlots = width * p.maxDestinationOperands
  private val committedPTagLimit = p.ooo.stidCount * p.ooo.gprArchRegs

  private def trunc(value: UInt, width: Int): UInt =
    if (value.getWidth == width) value
    else if (value.getWidth > width) value(width - 1, 0)
    else Cat(0.U((width - value.getWidth).W), value)

  private def safeAtag(value: UInt): UInt = trunc(value, archIndexWidth)
  private def ptagInRange(value: UInt): Bool = value < p.ooo.gprPhysRegs.U
  private def physicalPtag(value: UInt): UInt =
    value(physicalPTagWidth - 1, 0)
  private def generationFor(value: UInt): UInt =
    Mux(ptagInRange(value), generations(physicalPtag(value)),
      0.U(p.ooo.gprTagGenerationWidth.W))
  private def safeStid(value: UInt): UInt =
    if (p.ooo.stidCount == 1) 0.U(physicalStidWidth.W)
    else trunc(value, physicalStidWidth)

  private def sameRob(a: RobIdentity, b: RobIdentity): Bool =
    a.asUInt === b.asUInt

  private def robOlderOrSame(a: RobIdentity, b: RobIdentity): Bool = {
    val groupOrderWidth = a.ridGeneration.getWidth + a.ridSlot.getWidth
    val aGroup = Cat(a.ridGeneration, a.ridSlot)
    val bGroup = Cat(b.ridGeneration, b.ridSlot)
    val delta = trunc(bGroup - aGroup, groupOrderWidth)
    val sameGroup = delta === 0.U
    val rowToSurvivorWithinLiveWindow =
      delta < (BigInt(1) << (groupOrderWidth - 1)).U(groupOrderWidth.W)
    a.stid === b.stid && a.peId === b.peId && rowToSurvivorWithinLiveWindow &&
      (!sameGroup || a.memberIndex <= b.memberIndex)
  }

  val smap = RegInit(VecInit((0 until p.ooo.stidCount).map { stid =>
    VecInit((0 until p.ooo.gprArchRegs).map { atag =>
      PRename.committedTag(p, stid, atag).U(pTagWidth.W)
    })
  }))
  val cmap = RegInit(VecInit((0 until p.ooo.stidCount).map { stid =>
    VecInit((0 until p.ooo.gprArchRegs).map { atag =>
      PRename.committedTag(p, stid, atag).U(pTagWidth.W)
    })
  }))
  val free = RegInit(VecInit((0 until p.ooo.gprPhysRegs).map { tag =>
    (tag >= committedPTagLimit).B
  }))
  val generations = RegInit(VecInit(Seq.fill(p.ooo.gprPhysRegs)(
    0.U(p.ooo.gprTagGenerationWidth.W))))
  val mapQ = Reg(Vec(p.ooo.stidCount, Vec(p.ooo.gprMapQDepthPerStid,
    new RenameCommitReleaseEntry(p))))
  val head = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(mapQIndexWidth.W))))
  val tail = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(mapQIndexWidth.W))))
  val tailGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.gprTagGenerationWidth.W))))
  val headGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.ooo.gprTagGenerationWidth.W))))
  val count = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(mapQCountWidth.W))))
  val reserved = RegInit(VecInit(Seq.fill(p.ooo.gprPhysRegs)(false.B)))
  val reservedValid = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(false.B)))
  val reservedLease = Reg(Vec(p.ooo.stidCount, new D3RenameGroup(p)))

  io.debugPMap := smap

  val in = io.prepare.bits
  val stidRaw = in.entries(0).uop.instruction.parent.identity.stid
  val stidInRange = stidRaw < p.ooo.stidCount.U
  val stid = safeStid(stidRaw)
  val prospectivePublish = io.publish.bits
  val prospectivePublishStid = safeStid(
    prospectivePublish.entries(0).uop.decoded.instruction.parent.identity.stid)
  val prospectivePublishSameStid = io.publish.valid &&
    prospectivePublish.entries(0).uop.decoded.instruction.parent.identity.stid <
      p.ooo.stidCount.U && prospectivePublishStid === stid
  val prospectivePublishFinal = prospectivePublishSameStid &&
    reservedValid(stid) &&
    prospectivePublish.count === reservedLease(stid).count &&
    prospectivePublish.groupCount === reservedLease(stid).groupCount
  val prospectivePublishedP = Wire(Vec(pDestSlots, Bool()))
  for (lane <- 0 until width; dest <- 0 until p.maxDestinationOperands) {
    val flat = lane * p.maxDestinationOperands + dest
    val hist = prospectivePublish.entries(lane).history(dest)
    prospectivePublishedP(flat) := prospectivePublishSameStid &&
      lane.U < prospectivePublish.count && hist.valid &&
      hist.kind === OperandKind.Gpr
  }
  val prospectivePublishedPCount = PopCount(prospectivePublishedP)
  val prospectiveSmap = Wire(Vec(p.ooo.gprArchRegs, UInt(pTagWidth.W)))
  prospectiveSmap := smap(stid)
  for (lane <- 0 until width; dest <- 0 until p.maxDestinationOperands) {
    val flat = lane * p.maxDestinationOperands + dest
    val hist = prospectivePublish.entries(lane).history(dest)
    when(prospectivePublishedP(flat) && hist.atag < p.ooo.gprArchRegs.U) {
      prospectiveSmap(safeAtag(hist.atag)) := hist.ptag
    }
  }
  val prospectiveTailSum = tail(stid) +& prospectivePublishedPCount
  val prospectiveTail = trunc(prospectiveTailSum, mapQIndexWidth)
  val prospectiveTailGeneration = tailGeneration(stid) +
    (prospectiveTailSum >= p.ooo.gprMapQDepthPerStid.U).asUInt
  val prospectiveCount = count(stid) +& prospectivePublishedPCount
  val active = Wire(Vec(width, Bool()))
  for (lane <- 0 until width) {
    active(lane) := lane.U < in.count && in.entries(lane).uop.valid
  }

  val destActive = Wire(Vec(pDestSlots, Bool()))
  val destAtag = Wire(Vec(pDestSlots, UInt(archIndexWidth.W)))
  for (lane <- 0 until width; dest <- 0 until p.maxDestinationOperands) {
    val flat = lane * p.maxDestinationOperands + dest
    val d = in.entries(lane).uop.destinations(dest)
    destActive(flat) := active(lane) && d.valid &&
      d.kind === OperandKind.Gpr && d.atag < p.ooo.gprArchRegs.U
    destAtag(flat) := safeAtag(d.atag)
  }
  val destCount = PopCount(destActive)
  val freeForPrepare = Wire(Vec(p.ooo.gprPhysRegs, Bool()))
  for (tag <- 0 until p.ooo.gprPhysRegs) {
    freeForPrepare(tag) := free(tag) && !reserved(tag)
  }
  io.prepareReady := stidInRange &&
    (!reservedValid(stid) || prospectivePublishFinal) &&
    destCount <= PopCount(freeForPrepare) &&
    prospectiveCount +& destCount <= p.ooo.gprMapQDepthPerStid.U

  val allocatedTag = Wire(Vec(pDestSlots, UInt(pTagWidth.W)))
  val allocatedGen = Wire(Vec(pDestSlots,
    UInt(p.ooo.gprTagGenerationWidth.W)))
  allocatedTag := VecInit(Seq.fill(pDestSlots)(0.U(pTagWidth.W)))
  allocatedGen := VecInit(Seq.fill(pDestSlots)(
    0.U(p.ooo.gprTagGenerationWidth.W)))
  val chosen = Wire(Vec(pDestSlots + 1, Vec(p.ooo.gprPhysRegs, Bool())))
  chosen(0) := VecInit(Seq.fill(p.ooo.gprPhysRegs)(false.B))
  for (flat <- 0 until pDestSlots) {
    val available = Wire(Vec(p.ooo.gprPhysRegs, Bool()))
    for (tag <- 0 until p.ooo.gprPhysRegs) {
      available(tag) := freeForPrepare(tag) && !chosen(flat)(tag)
    }
    val tag = PriorityEncoder(available.asUInt)
    allocatedTag(flat) := trunc(tag, pTagWidth)
    allocatedGen(flat) := generations(tag)
    for (tagIndex <- 0 until p.ooo.gprPhysRegs) {
      chosen(flat + 1)(tagIndex) :=
        chosen(flat)(tagIndex) || (destActive(flat) && tag === tagIndex.U)
    }
  }

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
    io.prepared.entries(lane).earlyRobComplete :=
      in.entries(lane).uop.earlyComplete
    io.prepared.entries(lane).uop.decoded := in.entries(lane).uop
    for (source <- 0 until p.maxSourceOperands) {
      val src = in.entries(lane).uop.sources(source)
      val out = io.prepared.entries(lane).uop.sources(source)
      out.valid := src.valid
      out.kind := src.kind
      out.atag := src.atag
      out.ready := true.B
      when(src.valid && src.kind === OperandKind.Gpr &&
        src.atag < p.ooo.gprArchRegs.U) {
        val atag = safeAtag(src.atag)
        val tag = Wire(UInt(pTagWidth.W))
        val gen = Wire(UInt(p.ooo.gprTagGenerationWidth.W))
        val forwarded = Wire(Bool())
        tag := prospectiveSmap(atag)
        gen := generationFor(tag)
        forwarded := false.B
        for (flat <- 0 until lane * p.maxDestinationOperands) {
          val olderDest = in.entries(flat / p.maxDestinationOperands)
            .uop.destinations(flat % p.maxDestinationOperands)
          when(destActive(flat) && olderDest.atag === src.atag) {
            tag := allocatedTag(flat)
            gen := allocatedGen(flat)
            forwarded := true.B
          }
        }
        out.ptag := tag
        out.pGeneration := gen
        out.ptagValid := true.B
        out.ready := !forwarded && ptagInRange(tag)
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
      hist.valid := destActive(flat)
      hist.kind := din.kind
      hist.atag := din.atag
      when(destActive(flat)) {
        val previous = Wire(UInt(pTagWidth.W))
        previous := prospectiveSmap(destAtag(flat))
        for (older <- 0 until flat) {
          val olderDest = in.entries(older / p.maxDestinationOperands)
            .uop.destinations(older % p.maxDestinationOperands)
          when(destActive(older) && olderDest.atag === din.atag) {
            previous := allocatedTag(older)
          }
        }
        dout.ptag := allocatedTag(flat)
        dout.previousPtag := previous
        dout.pGeneration := allocatedGen(flat)
        dout.previousPGeneration := generationFor(previous)
        dout.previousPtagValid := true.B
        dout.ptagValid := true.B
        hist.ptag := allocatedTag(flat)
        hist.previousPtag := previous
        hist.pGeneration := allocatedGen(flat)
        hist.previousPGeneration := generationFor(previous)
        val ordinal = PopCount(destActive.take(flat))
        val indexSum = prospectiveTail +& ordinal
        hist.pMapQIndex := trunc(indexSum, mapQIndexWidth)
        hist.pMapQGeneration := prospectiveTailGeneration +
          (indexSum >= p.ooo.gprMapQDepthPerStid.U).asUInt
      }
    }
  }

  when(io.reserve.valid) {
    val lease = io.reserve.bits
    val leaseStid = safeStid(
      lease.entries(0).uop.decoded.instruction.parent.identity.stid)
    reservedLease(leaseStid) := lease
    reservedValid(leaseStid) := true.B
    for (lane <- 0 until width) {
      when(lane.U < lease.count && lease.entries(lane).uop.decoded.valid) {
        for (dest <- 0 until p.maxDestinationOperands) {
          val hist = lease.entries(lane).history(dest)
          when(hist.valid && hist.kind === OperandKind.Gpr &&
            hist.ptag < p.ooo.gprPhysRegs.U) {
            reserved(physicalPtag(hist.ptag)) := true.B
          }
        }
      }
    }
  }

  when(io.cancel.valid) {
    val cancelStid = safeStid(io.cancel.bits)
    val lease = reservedLease(cancelStid)
    when(reservedValid(cancelStid)) {
      for (lane <- 0 until width) {
        when(lane.U < lease.count && lease.entries(lane).uop.decoded.valid) {
          for (dest <- 0 until p.maxDestinationOperands) {
            val hist = lease.entries(lane).history(dest)
            when(hist.valid && hist.kind === OperandKind.Gpr &&
              hist.ptag < p.ooo.gprPhysRegs.U) {
              reserved(physicalPtag(hist.ptag)) := false.B
            }
          }
        }
      }
    }
    reservedValid(cancelStid) := false.B
  }

  when(io.publish.valid) {
    val pub = io.publish.bits
    val pubStid = safeStid(pub.entries(0).uop.decoded.instruction.parent.identity.stid)
    val lease = reservedLease(pubStid)
    val prefixExact = reservedValid(pubStid) && pub.count.orR &&
      pub.count <= lease.count && pub.groupCount.orR &&
      pub.groupCount <= lease.groupCount
    assert(prefixExact,
      "P publication must consume an exact retained reservation prefix")
    for (lane <- 0 until width) {
      when(lane.U < pub.count) {
        assert(pub.entries(lane).history.asUInt ===
          lease.entries(lane).history.asUInt,
          "P publication history must match the retained prefix lease")
      }
    }
    val published = Wire(Vec(pDestSlots, Bool()))
    for (flat <- 0 until pDestSlots) { published(flat) := false.B }
    for (lane <- 0 until width) {
      when(lane.U < pub.count && pub.entries(lane).uop.decoded.valid) {
        for (dest <- 0 until p.maxDestinationOperands) {
          val flat = lane * p.maxDestinationOperands + dest
          val hist = pub.entries(lane).history(dest)
          when(hist.valid && hist.kind === OperandKind.Gpr &&
            hist.atag < p.ooo.gprArchRegs.U &&
            hist.ptag < p.ooo.gprPhysRegs.U) {
            val row = Wire(new RenameCommitReleaseEntry(p))
            row := 0.U.asTypeOf(row)
            row.valid := true.B
            row.rob := pub.entries(lane).uop.decoded.rob
            row.blockLast := pub.entries(lane).uop.decoded.blockBoundary
            row.history(dest) := hist
            val idx = trunc(tail(pubStid) + PopCount(published.take(flat)),
              mapQIndexWidth)
            mapQ(pubStid)(idx) := row
            smap(pubStid)(safeAtag(hist.atag)) := hist.ptag
            free(physicalPtag(hist.ptag)) := false.B
            reserved(physicalPtag(hist.ptag)) := false.B
            published(flat) := true.B
          }
        }
      }
    }
    val n = PopCount(published)
    val tailSum = tail(pubStid) +& n
    tail(pubStid) := trunc(tailSum, mapQIndexWidth)
    when(tailSum >= p.ooo.gprMapQDepthPerStid.U) {
      tailGeneration(pubStid) := tailGeneration(pubStid) + 1.U
    }
    count(pubStid) := count(pubStid) + n
    val suffix = Wire(new D3RenameGroup(p))
    suffix := 0.U.asTypeOf(suffix)
    suffix.count := lease.count - pub.count
    suffix.groupCount := lease.groupCount - pub.groupCount
    for (group <- 0 until width) {
      for (source <- 0 until width) {
        when(source.U === group.U + pub.groupCount &&
            source.U < lease.groupCount) {
          suffix.groups(group) := lease.groups(source)
        }
      }
    }
    for (lane <- 0 until width) {
      for (source <- 0 until width) {
        when(source.U === lane.U + pub.count && source.U < lease.count) {
          suffix.entries(lane) := lease.entries(source)
        }
      }
    }
    reservedLease(pubStid) := suffix
    reservedValid(pubStid) := suffix.count.orR

    val reserveStid = safeStid(
      io.reserve.bits.entries(0).uop.decoded.instruction.parent.identity.stid)
    val sameStidReplacement = io.reserve.valid && reserveStid === pubStid
    when(sameStidReplacement) {
      assert(!suffix.count.orR && !suffix.groupCount.orR,
        "P reservation replacement requires final publication of the old lease")
      for (oldLane <- 0 until width; oldDest <- 0 until p.maxDestinationOperands) {
        val oldHist = pub.entries(oldLane).history(oldDest)
        val oldLive = oldLane.U < pub.count && oldHist.valid &&
          oldHist.kind === OperandKind.Gpr
        for (newLane <- 0 until width; newDest <- 0 until p.maxDestinationOperands) {
          val newHist = io.reserve.bits.entries(newLane).history(newDest)
          val newLive = newLane.U < io.reserve.bits.count && newHist.valid &&
            newHist.kind === OperandKind.Gpr
          when(oldLive && newLive) {
            assert(oldHist.ptag =/= newHist.ptag,
              "replacement P reservations must not reuse a published physical tag")
          }
        }
      }
      reservedLease(pubStid) := io.reserve.bits
      reservedValid(pubStid) := true.B
    }
  }

  val releaseSlotCount = p.widths.retireWidth * p.maxDestinationOperands
  val releaseWanted = Wire(Vec(releaseSlotCount, Bool()))
  val releaseExact = Wire(Vec(releaseSlotCount, Bool()))
  val releaseAccept = Wire(Vec(releaseSlotCount, Bool()))
  releaseWanted := VecInit(Seq.fill(releaseSlotCount)(false.B))
  releaseExact := VecInit(Seq.fill(releaseSlotCount)(false.B))
  releaseAccept := VecInit(Seq.fill(releaseSlotCount)(false.B))
  for (flat <- 0 until releaseSlotCount) {
    val lane = flat / p.maxDestinationOperands
    val dest = flat % p.maxDestinationOperands
    val rel = io.release.bits.lanes(lane)
    val hist = rel.history(dest)
    val st = safeStid(rel.rob.stid)
    releaseWanted(flat) := io.release.valid && rel.valid &&
      lane.U < io.release.bits.count &&
      hist.valid && hist.kind === OperandKind.Gpr
    val priorRows = PopCount((0 until flat).map { prior =>
      val priorLane = prior / p.maxDestinationOperands
      releaseWanted(prior) &&
        io.release.bits.lanes(priorLane).rob.stid === rel.rob.stid
    })
    val rowIndex = trunc(head(st) + priorRows, mapQIndexWidth)
    val row = mapQ(st)(rowIndex)
    val available = count(st) > priorRows
    releaseExact(flat) := releaseWanted(flat) &&
      rel.rob.stid < p.ooo.stidCount.U && available && row.valid &&
      rel.rob.asUInt === row.rob.asUInt &&
      hist.asUInt === row.history(dest).asUInt &&
      hist.pMapQIndex === rowIndex &&
      ptagInRange(hist.ptag) && ptagInRange(hist.previousPtag) &&
      hist.pGeneration === generationFor(hist.ptag) &&
      hist.previousPGeneration === generationFor(hist.previousPtag)
  }
  val releaseAllExact = !releaseWanted.asUInt.orR ||
    (releaseWanted.asUInt === releaseExact.asUInt)
  io.releaseExact := !io.release.valid || releaseAllExact
  for (flat <- 0 until releaseSlotCount) {
    releaseAccept(flat) := releaseWanted(flat) && releaseAllExact
  }
  when(io.release.valid && io.releaseApply && releaseAllExact) {
    for (flat <- 0 until releaseSlotCount) {
      val lane = flat / p.maxDestinationOperands
      val dest = flat % p.maxDestinationOperands
      val rel = io.release.bits.lanes(lane)
      val hist = rel.history(dest)
      val st = safeStid(rel.rob.stid)
      when(releaseAccept(flat)) {
        cmap(st)(safeAtag(hist.atag)) := hist.ptag
        when(!PRename.isCommittedResetTag(p, hist.previousPtag)) {
          val previousIndex = physicalPtag(hist.previousPtag)
          free(previousIndex) := true.B
          generations(previousIndex) := generations(previousIndex) + 1.U
        }
      }
    }
    for (stid <- 0 until p.ooo.stidCount) {
      val acceptedForStid = PopCount((0 until releaseSlotCount).map { flat =>
        val lane = flat / p.maxDestinationOperands
        releaseAccept(flat) &&
          io.release.bits.lanes(lane).rob.stid === stid.U
      })
      when(acceptedForStid.orR) {
        val headSum = head(stid) +& acceptedForStid
        head(stid) := trunc(headSum, mapQIndexWidth)
        when(headSum >= p.ooo.gprMapQDepthPerStid.U) {
          headGeneration(stid) := headGeneration(stid) + 1.U
        }
        count(stid) := count(stid) - acceptedForStid
      }
    }
  }

  when(io.recoveryApply.valid && io.recoveryApply.bits.phase === RecoveryPhase.Apply) {
    val target = safeStid(io.recoveryApply.bits.trigger.stid)
    val survivorMatches = Wire(Vec(p.ooo.gprMapQDepthPerStid, Bool()))
    for (offset <- 0 until p.ooo.gprMapQDepthPerStid) {
      val idx = trunc(head(target) + offset.U, mapQIndexWidth)
      survivorMatches(offset) := offset.U < count(target) &&
        io.recoveryApply.bits.survivingTailValid &&
        mapQ(target)(idx).valid &&
        robOlderOrSame(mapQ(target)(idx).rob,
          io.recoveryApply.bits.survivingTail)
    }
    val survivorCount = Wire(UInt(mapQCountWidth.W))
    survivorCount := Mux(io.recoveryApply.bits.survivingTailValid,
      trunc(PopCount(survivorMatches), mapQCountWidth),
      0.U(mapQCountWidth.W))
    for (offset <- 1 until p.ooo.gprMapQDepthPerStid) {
      when(survivorMatches(offset)) {
        assert(survivorMatches(offset - 1),
          "P recovery survivors must form an ordered prefix")
      }
    }

    for (atag <- 0 until p.ooo.gprArchRegs) {
      smap(target)(atag) := cmap(target)(atag)
    }
    for (offset <- 0 until p.ooo.gprMapQDepthPerStid) {
      val rowIndex = trunc(head(target) + offset.U, mapQIndexWidth)
      val row = mapQ(target)(rowIndex)
      val live = offset.U < count(target)
      val survives = live && offset.U < survivorCount
      val killed = live && !survives
      when(survives) {
        for (dest <- 0 until p.maxDestinationOperands) {
          val hist = row.history(dest)
          when(hist.valid && hist.kind === OperandKind.Gpr &&
            hist.atag < p.ooo.gprArchRegs.U && ptagInRange(hist.ptag)) {
            smap(target)(safeAtag(hist.atag)) := hist.ptag
          }
        }
      }
      when(killed) {
        row.valid := false.B
        for (dest <- 0 until p.maxDestinationOperands) {
          val hist = row.history(dest)
          when(hist.valid && hist.kind === OperandKind.Gpr &&
            hist.ptag < p.ooo.gprPhysRegs.U &&
            !PRename.isCommittedResetTag(p, hist.ptag)) {
            val tagIndex = physicalPtag(hist.ptag)
            free(tagIndex) := true.B
            generations(tagIndex) := generations(tagIndex) + 1.U
          }
        }
      }
    }
    val recoveredTail = head(target) +& survivorCount
    tail(target) := trunc(recoveredTail, mapQIndexWidth)
    tailGeneration(target) := headGeneration(target) +
      (recoveredTail >= p.ooo.gprMapQDepthPerStid.U).asUInt
    count(target) := survivorCount
  }
}
