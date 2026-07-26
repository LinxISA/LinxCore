package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, PopCount, UIntToOH, log2Ceil}
import linxcore.common.{DecodedUop, InterfaceParams}

class D1DecodedLaneQueueEntry(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  private val laneWidth = math.max(1, log2Ceil(p.decodeWidth))

  val groupId = UInt(p.uopUidWidth.W)
  val lane = UInt(laneWidth.W)
  val uop = new DecodedUop(p)
  val meta = new FrontendOpcodeMeta(p)
  val invalidOpcode = Bool()
  val blockBoundary = Bool()
  val blockStop = Bool()
  val load = Bool()
  val store = Bool()
}

class D1DecodedLaneQueueIO(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 8)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)
  private val laneCountWidth = log2Ceil(p.decodeWidth + 1)
  private val laneWidth = math.max(1, log2Ceil(p.decodeWidth))

  val in = Flipped(Decoupled(new D1DecodedInstructionGroup(p)))
  val out = Decoupled(new D1DecodedInstructionGroup(p))
  val nextSameGroupValid = Output(Bool())
  val nextSameGroupUop = Output(new DecodedUop(p))
  val flush = Input(new IfuInnerFlush(p))

  val count = Output(UInt(countWidth.W))
  val inLaneCount = Output(UInt(laneCountWidth.W))
  val headLane = Output(UInt(laneWidth.W))
  val rejectedMalformed = Output(Bool())
  val rebasedTrigger = Output(Bool())
}

/** Registered fixed-width D1-to-backend lane queue.
  *
  * One accepted four-wide D1 group allocates all of its valid lanes atomically.
  * The backend may consume one lane per cycle without converting the decoded
  * instruction back into a fetch packet or byte window.  A private group ID
  * keeps the same-group successor relation exact for service-adjacent BSTOP
  * classification.
  */
class D1DecodedLaneQueue(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 8)
    extends Module {
  require(p.decodeWidth == 4, "production D1 lane queue is four-wide")
  require(depth >= p.decodeWidth, "D1 lane queue must hold one full group")
  require((depth & (depth - 1)) == 0, "D1 lane queue depth must be a power of two")

  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new D1DecodedLaneQueueIO(p, depth))

  val entries = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new D1DecodedLaneQueueEntry(p)))))
  val valids = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))
  val nextGroupId = RegInit(0.U(p.uopUidWidth.W))

  private def advance(ptr: UInt, amount: UInt): UInt = (ptr + amount)(ptrWidth - 1, 0)
  private def denseMask(mask: UInt): Bool =
    (0 to p.decodeWidth)
      .map(lanes => mask === ((BigInt(1) << lanes) - 1).U(p.decodeWidth.W))
      .reduce(_ || _)

  val inLaneCount = PopCount(io.in.bits.validMask)
  val inHasLanes = inLaneCount =/= 0.U
  val inDense = denseMask(io.in.bits.validMask)
  val inRowsValid = (0 until p.decodeWidth)
    .map(lane =>
      !io.in.bits.validMask(lane) ||
        io.in.bits.entries(lane).valid ||
        io.in.bits.invalidOpcodeMask(lane))
    .reduce(_ && _)
  val inSameThread = (1 until p.decodeWidth)
    .map(lane =>
      !io.in.bits.validMask(lane) ||
        io.in.bits.entries(lane).threadId === io.in.bits.entries(0).threadId)
    .reduceOption(_ && _)
    .getOrElse(true.B)
  val inMalformed = inHasLanes && (!inDense || !inRowsValid || !inSameThread)
  val free = depth.U((countWidth + 1).W) - count.pad(countWidth + 1)
  val canAccept = free >= inLaneCount.pad(countWidth + 1)

  io.in.ready := !io.flush.valid && inHasLanes && !inMalformed && canAccept
  val inFire = io.in.valid && io.in.ready

  val headValid = !io.flush.valid && count =/= 0.U && valids(head)
  val headEntry = entries(head)
  val nextHead = advance(head, 1.U)
  val nextEntry = entries(nextHead)
  val nextSameGroup =
    headValid && count > 1.U && valids(nextHead) && nextEntry.groupId === headEntry.groupId

  io.out.valid := headValid
  io.out.bits := 0.U.asTypeOf(io.out.bits)
  when(headValid) {
    io.out.bits.validMask := UIntToOH(headEntry.lane, p.decodeWidth)
    io.out.bits.entries(headEntry.lane) := headEntry.uop
    io.out.bits.meta(headEntry.lane) := headEntry.meta
    io.out.bits.invalidOpcodeMask :=
      Mux(headEntry.invalidOpcode, UIntToOH(headEntry.lane, p.decodeWidth), 0.U(p.decodeWidth.W))
    io.out.bits.blockBoundaryMask :=
      Mux(headEntry.blockBoundary, UIntToOH(headEntry.lane, p.decodeWidth), 0.U(p.decodeWidth.W))
    io.out.bits.blockStopMask :=
      Mux(headEntry.blockStop, UIntToOH(headEntry.lane, p.decodeWidth), 0.U(p.decodeWidth.W))
    io.out.bits.loadMask :=
      Mux(headEntry.load, UIntToOH(headEntry.lane, p.decodeWidth), 0.U(p.decodeWidth.W))
    io.out.bits.storeMask :=
      Mux(headEntry.store, UIntToOH(headEntry.lane, p.decodeWidth), 0.U(p.decodeWidth.W))
  }
  io.nextSameGroupValid := nextSameGroup
  io.nextSameGroupUop := Mux(nextSameGroup, nextEntry.uop, 0.U.asTypeOf(new DecodedUop(p)))
  io.count := count
  io.inLaneCount := inLaneCount
  io.headLane := headEntry.lane
  io.rejectedMalformed := io.in.valid && inMalformed

  val rebaseHits = Wire(Vec(depth, Bool()))
  for (offset <- 0 until depth) {
    val readPtr = advance(head, offset.U)
    val row = entries(readPtr)
    rebaseHits(offset) :=
      io.flush.valid &&
        io.flush.scope === IfuPruneScope.PreserveTriggerKillYounger &&
        offset.U < count && valids(readPtr) &&
        row.uop.threadId === io.flush.threadId &&
        row.uop.prediction.epoch === io.flush.oldEpoch &&
        row.uop.prediction.fetchSeq === io.flush.fetchSeq &&
        row.uop.prediction.transactionId === io.flush.transactionId
  }
  io.rebasedTrigger := rebaseHits.asUInt.orR

  when(io.flush.valid) {
    val keep = Wire(Vec(depth, Bool()))
    val prefix = Wire(Vec(depth + 1, UInt(countWidth.W)))
    prefix(0) := 0.U
    for (offset <- 0 until depth) {
      val readPtr = advance(head, offset.U)
      val row = entries(readPtr)
      val resident = offset.U < count && valids(readPtr)
      val killed = IfuFlushContract.kills(
        row.uop.threadId,
        row.uop.prediction.epoch,
        row.uop.prediction.fetchSeq,
        row.uop.prediction.transactionId,
        io.flush)
      keep(offset) := resident && !killed
      prefix(offset + 1) := prefix(offset) + keep(offset).asUInt

      when(keep(offset)) {
        val retained = Wire(new D1DecodedLaneQueueEntry(p))
        retained := row
        when(rebaseHits(offset)) {
          retained.uop.prediction.epoch := io.flush.newEpoch
        }
        entries(prefix(offset)(ptrWidth - 1, 0)) := retained
      }
    }
    for (slot <- 0 until depth) {
      val liveAfter = slot.U < prefix(depth)
      valids(slot) := liveAfter
      when(!liveAfter) {
        entries(slot) := 0.U.asTypeOf(entries(slot))
      }
    }
    head := 0.U
    tail := prefix(depth)(ptrWidth - 1, 0)
    count := prefix(depth)
  }.otherwise {
    when(inFire) {
      for (lane <- 0 until p.decodeWidth) {
        val priorMask =
          if (lane == 0) 0.U(p.decodeWidth.W)
          else io.in.bits.validMask(lane - 1, 0).pad(p.decodeWidth)
        val writePtr = advance(tail, PopCount(priorMask))
        when(io.in.bits.validMask(lane)) {
          val row = Wire(new D1DecodedLaneQueueEntry(p))
          row := 0.U.asTypeOf(row)
          row.groupId := nextGroupId
          row.lane := lane.U
          row.uop := io.in.bits.entries(lane)
          row.meta := io.in.bits.meta(lane)
          row.invalidOpcode := io.in.bits.invalidOpcodeMask(lane)
          row.blockBoundary := io.in.bits.blockBoundaryMask(lane)
          row.blockStop := io.in.bits.blockStopMask(lane)
          row.load := io.in.bits.loadMask(lane)
          row.store := io.in.bits.storeMask(lane)
          entries(writePtr) := row
          valids(writePtr) := true.B
        }
      }
      tail := advance(tail, inLaneCount)
      nextGroupId := nextGroupId + 1.U
    }

    when(io.out.fire) {
      valids(head) := false.B
      entries(head) := 0.U.asTypeOf(entries(head))
      head := advance(head, 1.U)
    }

    count := count + Mux(inFire, inLaneCount.pad(countWidth), 0.U) -
      Mux(io.out.fire, 1.U(countWidth.W), 0.U)
  }

  when(inFire) {
    for (lane <- 0 until p.decodeWidth) {
      when(io.in.bits.validMask(lane)) {
        assert(io.in.bits.entries(lane).prediction.valid,
          "production D1 lane queue requires final prediction metadata")
      }
    }
  }
}
