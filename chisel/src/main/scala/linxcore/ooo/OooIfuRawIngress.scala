package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, MuxCase, PopCount, PriorityEncoder, log2Ceil}
import linxcore.common.InterfaceParams
import linxcore.frontend.{
  D1InstructionGroup,
  IfuFlushContract,
  IfuInnerFlush,
  IfuPruneScope,
  InstructionBufferEntry
}

class OooIfuRawIngressIO(
    val ifuP: InterfaceParams = InterfaceParams(),
    val oooP: OooParams = OooParams(),
    val depthPerStid: Int = 16)
    extends Bundle {
  private val countWidth = log2Ceil(depthPerStid + 1)

  /** Thread hint driven back to the fixed-four-wide IFU D1 dequeue. */
  val ifuThreadId = Output(UInt(ifuP.threadIdWidth.W))
  val ifuD1 = Flipped(Decoupled(new D1InstructionGroup(ifuP)))

  /** OOO chooses the STID whose oldest dense prefix is presented. */
  val selectStid = Input(UInt(oooP.stidWidth.W))
  /** Non-mutating recovery admission fence. Retained rows remain intact. */
  val fence = Input(Vec(oooP.stidCount, Bool()))
  /** Retiring BARG snapshot captured with each accepted architectural row. */
  val retiringBargBpcnValid = Input(Vec(oooP.stidCount, Bool()))
  val retiringBargBpcn = Input(Vec(oooP.stidCount, UInt(oooP.pcWidth.W)))
  val out = Decoupled(new OooRawInstructionGroup(oooP))
  val flush = Input(new IfuInnerFlush(ifuP))

  val counts = Output(Vec(oooP.stidCount, UInt(countWidth.W)))
  val eligibleMask = Output(UInt(oooP.stidCount.W))
  val acceptedLaneCount = Output(UInt(log2Ceil(ifuP.decodeWidth + 1).W))
  val emittedLaneCount = Output(UInt(log2Ceil(oooP.instructionDecodeWidth + 1).W))
  val malformedInput = Output(Bool())
}

/** Width and identity adapter between the fixed-four-wide IFU and production OOO.
  *
  * The IFU retains ownership of cacheline fetch and fixed-64-bit expansion. This
  * module owns only a per-STID raw-instruction reservoir, exact metadata mapping,
  * and independent 2/4/6-wide OOO dequeue. It performs no instruction decode.
  */
class OooIfuRawIngress(
    val ifuP: InterfaceParams = InterfaceParams(),
    val oooP: OooParams = OooParams(),
    val depthPerStid: Int = 16)
    extends Module {
  require(ifuP.fetchWidth == 4 && ifuP.decodeWidth == 4,
    "production IFU ingress consumes the fixed-four-wide D1 contract")
  require(ifuP.insnWidth == 64 && oooP.instructionWidth == 64)
  require(depthPerStid >= math.max(ifuP.decodeWidth, oooP.instructionDecodeWidth))
  require((depthPerStid & (depthPerStid - 1)) == 0,
    "depthPerStid must be a power of two")
  require(oooP.stidCount <= (1 << ifuP.threadIdWidth))
  require(oooP.peIdWidth == ifuP.peIdWidth)
  require(oooP.pcWidth == ifuP.pcWidth)
  require(oooP.instructionIdWidth == ifuP.uopUidWidth)
  require(oooP.transactionIdWidth == ifuP.uopUidWidth)
  require(oooP.predictionTagWidth == ifuP.uopUidWidth)
  require(oooP.checkpointWidth == ifuP.checkpointWidth)
  require(oooP.epochWidth == ifuP.blockEpochWidth)

  private val ptrWidth = math.max(1, log2Ceil(depthPerStid))
  private val countWidth = log2Ceil(depthPerStid + 1)

  val io = IO(new OooIfuRawIngressIO(ifuP, oooP, depthPerStid))

  val entries = RegInit(VecInit(Seq.fill(oooP.stidCount)(
    VecInit(Seq.fill(depthPerStid)(0.U.asTypeOf(new InstructionBufferEntry(ifuP)))))))
  val heads = RegInit(VecInit(Seq.fill(oooP.stidCount)(0.U(ptrWidth.W))))
  val tails = RegInit(VecInit(Seq.fill(oooP.stidCount)(0.U(ptrWidth.W))))
  val counts = RegInit(VecInit(Seq.fill(oooP.stidCount)(0.U(countWidth.W))))
  val pullStid = RegInit(0.U(oooP.stidWidth.W))

  private def advancePtr(ptr: UInt, amount: UInt): UInt =
    (ptr + amount)(ptrWidth - 1, 0)

  private def denseMask(mask: UInt, width: Int): Bool =
    (0 to width)
      .map(lanes => mask === ((BigInt(1) << lanes) - 1).U(width.W))
      .reduce(_ || _)

  private def mapEntry(target: OooRawInstruction, source: InstructionBufferEntry): Unit = {
    target := 0.U.asTypeOf(target)
    target.parent.key.valid := true.B
    target.parent.key.peId := source.identity.peId
    target.parent.key.stid := source.identity.threadId
    target.parent.key.instructionId := source.instructionUid
    target.parent.key.epoch := source.identity.epoch
    target.parent.pc := source.pc
    target.parent.rawInstruction := source.insn
    target.parent.lengthBytes := source.lenBytes
    target.parent.prediction.valid := source.prediction.valid
    target.parent.prediction.predictionTag := source.prediction.predictionTag
    target.parent.prediction.transactionId := source.transactionId
    target.parent.prediction.fetchPacketUid := source.identity.fetchPacketUid
    target.parent.prediction.fetchSeq := source.identity.fetchSeq
    target.parent.prediction.requestPc := source.prediction.requestPc
    target.parent.prediction.taken := source.prediction.taken
    target.parent.prediction.branchPc := source.prediction.branchPc
    target.parent.prediction.target := source.prediction.target
    target.parent.prediction.fallthroughPc := source.prediction.fallthroughPc
    target.parent.prediction.kind := source.prediction.kind
    target.parent.prediction.provider := source.prediction.provider.asUInt
    target.parent.prediction.stage := source.prediction.stage.asUInt
    target.parent.prediction.confidence := source.prediction.confidence
    target.parent.prediction.checkpointId := source.prediction.checkpointId
    target.parent.prediction.epoch := source.prediction.epoch
    target.parent.traceOwner := true.B
    target.parent.preciseExceptionOwner := true.B
    target.retiringBargBpcnValid := (0 until oooP.stidCount).map { stid =>
      source.identity.threadId === stid.U && io.retiringBargBpcnValid(stid)
    }.reduce(_ || _)
    target.retiringBargBpcn := MuxCase(0.U, (0 until oooP.stidCount).map { stid =>
      (source.identity.threadId === stid.U) -> io.retiringBargBpcn(stid)
    })
    target.fetchFaultValid := false.B
    target.fetchFaultCause := 0.U
  }

  val pullCandidates = Wire(Vec(oooP.stidCount, Bool()))
  val pullCandidateStids = Wire(Vec(oooP.stidCount, UInt(oooP.stidWidth.W)))
  for (offset <- 0 until oooP.stidCount) {
    val candidate =
      if (oooP.stidCount == 1) 0.U(oooP.stidWidth.W)
      else (pullStid + offset.U)(oooP.stidWidth - 1, 0)
    pullCandidateStids(offset) := candidate
    pullCandidates(offset) := !io.fence(candidate)
  }
  val anyPullCandidate = pullCandidates.asUInt.orR
  val pullOffset = PriorityEncoder(pullCandidates.asUInt)
  val selectedPullStid =
    if (oooP.stidCount == 1) 0.U(oooP.stidWidth.W)
    else Mux(anyPullCandidate, pullCandidateStids(pullOffset), pullStid)

  io.ifuThreadId := selectedPullStid.pad(ifuP.threadIdWidth)
  when(!io.flush.valid && (!io.ifuD1.valid || io.ifuD1.fire)) {
    pullStid := Mux(
      selectedPullStid === (oooP.stidCount - 1).U,
      0.U,
      selectedPullStid + 1.U)
  }

  val inMask = io.ifuD1.bits.validMask
  val inLaneCount = PopCount(inMask)
  val inHasRows = inLaneCount.orR
  val inStid = io.ifuD1.bits.entries(0).identity.threadId
  val inStidSupported = inStid < oooP.stidCount.U
  val inBank = inStid(oooP.stidWidth - 1, 0)
  val inFenced = inStidSupported && io.fence(inBank)
  val inPeId = io.ifuD1.bits.entries(0).identity.peId
  val inEpoch = io.ifuD1.bits.entries(0).identity.epoch
  val inDense = denseMask(inMask, ifuP.decodeWidth)
  val inSameContext = (0 until ifuP.decodeWidth).map { lane =>
    !inMask(lane) || (
      io.ifuD1.bits.entries(lane).identity.threadId === inStid &&
        io.ifuD1.bits.entries(lane).identity.peId === inPeId &&
        io.ifuD1.bits.entries(lane).identity.epoch === inEpoch &&
        io.ifuD1.bits.entries(lane).prediction.epoch === inEpoch)
  }.reduce(_ && _)
  val malformedInput = !inHasRows || !inDense || !inStidSupported || !inSameContext

  val selectSupported = io.selectStid < oooP.stidCount.U
  val selectedBank = io.selectStid
  val selectedCount = Mux(selectSupported, counts(selectedBank), 0.U)
  val selectedHead = entries(selectedBank)(heads(selectedBank))

  val outputPrefix = Wire(Vec(oooP.instructionDecodeWidth, Bool()))
  for (lane <- 0 until oooP.instructionDecodeWidth) {
    val readPtr = advancePtr(heads(selectedBank), lane.U)
    val candidate = entries(selectedBank)(readPtr)
    val present = lane.U < selectedCount
    val sameContext =
      candidate.identity.peId === selectedHead.identity.peId &&
        candidate.identity.threadId === selectedHead.identity.threadId &&
        candidate.identity.epoch === selectedHead.identity.epoch &&
        candidate.prediction.epoch === selectedHead.identity.epoch
    outputPrefix(lane) :=
      present && sameContext && (if (lane == 0) true.B else outputPrefix(lane - 1))
  }
  val emittedLaneCount = PopCount(outputPrefix)

  // The canonical flush cycle is a global publication barrier. Only the
  // addressed bank is mutated; every unaffected bank resumes on the next
  // cycle without losing or duplicating its head transaction.
  io.out.valid := selectSupported && selectedCount.orR &&
    !io.fence(selectedBank) && !io.flush.valid
  io.out.bits := 0.U.asTypeOf(io.out.bits)
  io.out.bits.peId := selectedHead.identity.peId
  io.out.bits.stid := selectedHead.identity.threadId
  io.out.bits.epoch := selectedHead.identity.epoch
  io.out.bits.validMask := outputPrefix.asUInt
  io.out.bits.endOfStream := false.B
  for (lane <- 0 until oooP.instructionDecodeWidth) {
    val readPtr = advancePtr(heads(selectedBank), lane.U)
    when(outputPrefix(lane)) {
      mapEntry(io.out.bits.entries(lane), entries(selectedBank)(readPtr))
    }
  }

  val outFire = io.out.valid && io.out.ready
  val sameBankFire =
    outFire && inStidSupported && inBank === selectedBank
  val reclaimedRows = Mux(sameBankFire, emittedLaneCount, 0.U)
  val freeRows =
    depthPerStid.U((countWidth + 1).W) -
      counts(inBank).pad(countWidth + 1) +
      reclaimedRows.pad(countWidth + 1)
  val inCanFit = freeRows >= inLaneCount.pad(countWidth + 1)

  io.ifuD1.ready :=
    !io.flush.valid && !inFenced && !malformedInput && inCanFit
  val inFire = io.ifuD1.valid && io.ifuD1.ready

  io.counts := counts
  io.eligibleMask := VecInit((0 until oooP.stidCount).map { stid =>
    counts(stid).orR && !io.fence(stid)
  }).asUInt
  io.acceptedLaneCount := Mux(inFire, inLaneCount, 0.U)
  io.emittedLaneCount := Mux(outFire, emittedLaneCount, 0.U)
  io.malformedInput := io.ifuD1.valid && malformedInput

  when(io.ifuD1.valid) {
    assert(!malformedInput,
      "IFU raw ingress requires a non-empty dense same-PE/STID/epoch prefix")
  }
  when(io.out.valid) {
    assert(outputPrefix(0), "OOO raw ingress may only publish a non-empty prefix")
    assert(io.out.bits.stid === io.selectStid)
    for (lane <- 0 until oooP.instructionDecodeWidth) {
      when(io.out.bits.validMask(lane)) {
        assert(io.out.bits.entries(lane).parent.key.valid)
        assert(io.out.bits.entries(lane).parent.key.peId === io.out.bits.peId)
        assert(io.out.bits.entries(lane).parent.key.stid === io.out.bits.stid)
        assert(io.out.bits.entries(lane).parent.key.epoch === io.out.bits.epoch)
        assert(io.out.bits.entries(lane).parent.prediction.epoch === io.out.bits.epoch)
      }
    }
  }

  when(io.flush.valid) {
    for (stid <- 0 until oooP.stidCount) {
      when(io.flush.threadId === stid.U) {
        when(io.flush.scope === IfuPruneScope.KillAllThreadState) {
          heads(stid) := 0.U
          tails(stid) := 0.U
          counts(stid) := 0.U
        }.otherwise {
          val keep = Wire(Vec(depthPerStid, Bool()))
          for (offset <- 0 until depthPerStid) {
            val readPtr = advancePtr(heads(stid), offset.U)
            keep(offset) :=
              offset.U < counts(stid) &&
                !IfuFlushContract.killsInstruction(entries(stid)(readPtr), io.flush)
          }
          val keepPrefix = Wire(Vec(depthPerStid + 1, UInt(countWidth.W)))
          keepPrefix(0) := 0.U
          for (offset <- 0 until depthPerStid) {
            val readPtr = advancePtr(heads(stid), offset.U)
            val writePtr = keepPrefix(offset)(ptrWidth - 1, 0)
            keepPrefix(offset + 1) := keepPrefix(offset) + keep(offset).asUInt
            when(keep(offset)) {
              val retained = Wire(new InstructionBufferEntry(ifuP))
              retained := entries(stid)(readPtr)
              when(io.flush.terminalSteer) {
                retained.identity.epoch := io.flush.newEpoch
                retained.prediction.epoch := io.flush.newEpoch
              }
              entries(stid)(writePtr) := retained
            }
          }
          val keptCount = keepPrefix(depthPerStid)
          heads(stid) := 0.U
          tails(stid) := keptCount(ptrWidth - 1, 0)
          counts(stid) := keptCount
        }
      }
    }
  }.otherwise {
    when(outFire) {
      heads(selectedBank) := advancePtr(heads(selectedBank), emittedLaneCount)
    }
    when(inFire) {
      for (lane <- 0 until ifuP.decodeWidth) {
        val priorMask =
          if (lane == 0) 0.U(ifuP.decodeWidth.W)
          else inMask(lane - 1, 0).pad(ifuP.decodeWidth)
        val writeOffset = PopCount(priorMask)
        val writePtr = advancePtr(tails(inBank), writeOffset)
        when(inMask(lane)) {
          entries(inBank)(writePtr) := io.ifuD1.bits.entries(lane)
        }
      }
      tails(inBank) := advancePtr(tails(inBank), inLaneCount)
    }

    when(inFire && outFire && inBank === selectedBank) {
      counts(inBank) :=
        counts(inBank) + inLaneCount.pad(countWidth) - emittedLaneCount.pad(countWidth)
    }.otherwise {
      when(inFire) {
        counts(inBank) := counts(inBank) + inLaneCount.pad(countWidth)
      }
      when(outFire) {
        counts(selectedBank) := counts(selectedBank) - emittedLaneCount.pad(countWidth)
      }
    }
  }
}
