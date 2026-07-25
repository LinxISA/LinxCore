package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, PopCount, log2Ceil}
import linxcore.common.InterfaceParams

class InstructionBufferIO(
    val p: InterfaceParams = InterfaceParams(),
    val depthPerThread: Int = 16,
    val threadCount: Int = 1)
    extends Bundle {
  require(depthPerThread >= p.fetchWidth)
  require((depthPerThread & (depthPerThread - 1)) == 0)
  require(threadCount > 0 && (threadCount & (threadCount - 1)) == 0)
  require(threadCount <= (1 << p.threadIdWidth))

  private val countWidth = log2Ceil(depthPerThread + 1)

  val enq = Flipped(Decoupled(new InstructionBufferEnqueueGroup(p)))
  val deqThreadId = Input(UInt(p.threadIdWidth.W))
  val deq = Decoupled(new D1InstructionGroup(p))
  val flush = Input(new IfuInnerFlush(p))

  val counts = Output(Vec(threadCount, UInt(countWidth.W)))
  val activeEpochs = Output(Vec(threadCount, UInt(p.blockEpochWidth.W)))
  val enqLaneCount = Output(UInt(log2Ceil(p.fetchWidth + 1).W))
  val deqLaneCount = Output(UInt(log2Ceil(p.decodeWidth + 1).W))
  val enqRejectedStale = Output(Bool())
  val enqRejectedMalformed = Output(Bool())
}

class InstructionBuffer(
    val p: InterfaceParams = InterfaceParams(),
    val depthPerThread: Int = 16,
    val threadCount: Int = 1)
    extends Module {
  require(p.fetchWidth == 4, "InstructionBuffer implements the four-wide I-F4 enqueue contract")
  require(p.decodeWidth == 4, "InstructionBuffer implements the four-wide D1 dequeue contract")
  require(p.insnWidth == 64, "InstructionBuffer stores fixed-width 64-bit instructions")
  require(depthPerThread >= p.fetchWidth, "each STID bank must accept one full I-F4 group")
  require((depthPerThread & (depthPerThread - 1)) == 0, "depthPerThread must be a power of two")
  require(threadCount > 0 && (threadCount & (threadCount - 1)) == 0, "threadCount must be a power of two")
  require(threadCount <= (1 << p.threadIdWidth), "threadCount must fit threadIdWidth")

  private val ptrWidth = math.max(1, log2Ceil(depthPerThread))
  private val countWidth = log2Ceil(depthPerThread + 1)
  private val threadIndexWidth = math.max(1, log2Ceil(threadCount))

  val io = IO(new InstructionBufferIO(p, depthPerThread, threadCount))

  val entries = RegInit(
    VecInit(
      Seq.fill(threadCount)(
        VecInit(Seq.fill(depthPerThread)(0.U.asTypeOf(new InstructionBufferEntry(p)))))))
  val heads = RegInit(VecInit(Seq.fill(threadCount)(0.U(ptrWidth.W))))
  val tails = RegInit(VecInit(Seq.fill(threadCount)(0.U(ptrWidth.W))))
  val counts = RegInit(VecInit(Seq.fill(threadCount)(0.U(countWidth.W))))
  val activeEpochs = RegInit(VecInit(Seq.fill(threadCount)(0.U(p.blockEpochWidth.W))))

  def threadIndex(threadId: UInt): UInt =
    if (threadCount == 1) 0.U(threadIndexWidth.W) else threadId(threadIndexWidth - 1, 0)

  def advancePtr(ptr: UInt, amount: UInt): UInt =
    (ptr + amount)(ptrWidth - 1, 0)

  def denseMask(mask: UInt, width: Int): Bool =
    (0 to width).map(lanes => mask === ((BigInt(1) << lanes) - 1).U(width.W)).reduce(_ || _)

  val enqMask = io.enq.bits.validMask
  val enqLaneCount = PopCount(enqMask)
  val enqHasRows = enqLaneCount =/= 0.U
  val enqThreadId = io.enq.bits.entries(0).identity.threadId
  val enqThread = threadIndex(enqThreadId)
  val enqThreadSupported = enqThreadId < threadCount.U
  val enqMaskDense = denseMask(enqMask, p.fetchWidth)
  val enqSameThread = (0 until p.fetchWidth)
    .map(lane => !enqMask(lane) || io.enq.bits.entries(lane).identity.threadId === enqThreadId)
    .reduce(_ && _)
  val enqSameEpoch = (0 until p.fetchWidth)
    .map(lane =>
      !enqMask(lane) ||
        io.enq.bits.entries(lane).identity.epoch === io.enq.bits.entries(0).identity.epoch)
    .reduce(_ && _)
  val enqEpochMatches =
    enqThreadSupported &&
      io.enq.bits.entries(0).identity.epoch === activeEpochs(enqThread)

  val deqThreadSupported = io.deqThreadId < threadCount.U
  val deqThread = threadIndex(io.deqThreadId)
  val selectedCount = Mux(deqThreadSupported, counts(deqThread), 0.U)
  val deqLaneCount = Mux(selectedCount >= p.decodeWidth.U, p.decodeWidth.U, selectedCount)
  val deqHasRows = deqLaneCount =/= 0.U

  io.deq.valid := !io.flush.valid && deqThreadSupported && deqHasRows
  io.deq.bits := 0.U.asTypeOf(io.deq.bits)
  io.deq.bits.validMask := Mux(
    io.deq.valid,
    ((1.U((p.decodeWidth + 1).W) << deqLaneCount) - 1.U)(p.decodeWidth - 1, 0),
    0.U)
  for (lane <- 0 until p.decodeWidth) {
    val readPtr = advancePtr(heads(deqThread), lane.U)
    when(io.deq.valid && lane.U < deqLaneCount) {
      io.deq.bits.entries(lane) := entries(deqThread)(readPtr)
    }
  }

  val deqFire = io.deq.valid && io.deq.ready
  val sameBankFire = deqFire && enqThreadSupported && enqThread === deqThread
  val reclaimedRows = Mux(sameBankFire, deqLaneCount, 0.U)
  val freeRows =
    depthPerThread.U((countWidth + 1).W) -
      counts(enqThread).pad(countWidth + 1) +
      reclaimedRows.pad(countWidth + 1)
  val enqMalformed = enqHasRows && (!enqMaskDense || !enqSameThread || !enqSameEpoch)
  val enqStale = enqHasRows && enqThreadSupported && !enqEpochMatches
  val enqCanFit = freeRows >= enqLaneCount.pad(countWidth + 1)

  io.enq.ready :=
    !io.flush.valid &&
      enqHasRows &&
      enqThreadSupported &&
      !enqMalformed &&
      !enqStale &&
      enqCanFit

  val enqFire = io.enq.valid && io.enq.ready

  io.counts := counts
  io.activeEpochs := activeEpochs
  io.enqLaneCount := enqLaneCount
  io.deqLaneCount := deqLaneCount
  io.enqRejectedStale := io.enq.valid && enqStale
  io.enqRejectedMalformed := io.enq.valid && (enqMalformed || (enqHasRows && !enqThreadSupported))

  when(io.flush.valid) {
    for (thread <- 0 until threadCount) {
      when(io.flush.threadId === thread.U) {
        when(io.flush.scope === IfuPruneScope.KillAllThreadState) {
          heads(thread) := 0.U
          tails(thread) := 0.U
          counts(thread) := 0.U
        }.otherwise {
          val keep = Wire(Vec(depthPerThread, Bool()))
          for (offset <- 0 until depthPerThread) {
            val readPtr = advancePtr(heads(thread), offset.U)
            val candidate = entries(thread)(readPtr)
            keep(offset) :=
              offset.U < counts(thread) &&
                !IfuFlushContract.kills(
                  candidate.identity,
                  candidate.identity.fetchPacketUid,
                  io.flush)
          }
          val keepPrefix = Wire(Vec(depthPerThread + 1, UInt(countWidth.W)))
          keepPrefix(0) := 0.U
          for (offset <- 0 until depthPerThread) {
            keepPrefix(offset + 1) := keepPrefix(offset) + keep(offset).asUInt
            val readPtr = advancePtr(heads(thread), offset.U)
            val candidate = entries(thread)(readPtr)
            val writePtr = keepPrefix(offset)(ptrWidth - 1, 0)
            when(keep(offset)) {
              entries(thread)(writePtr) := candidate
            }
          }
          val keptCount = keepPrefix(depthPerThread)
          heads(thread) := 0.U
          tails(thread) := keptCount(ptrWidth - 1, 0)
          counts(thread) := keptCount
        }
        activeEpochs(thread) := io.flush.newEpoch
      }
    }
  }.otherwise {
    when(deqFire) {
      heads(deqThread) := advancePtr(heads(deqThread), deqLaneCount)
    }

    when(enqFire) {
      for (lane <- 0 until p.fetchWidth) {
        val priorMask = if (lane == 0) 0.U(p.fetchWidth.W) else enqMask(lane - 1, 0).pad(p.fetchWidth)
        val writeOffset = PopCount(priorMask)
        val writePtr = advancePtr(tails(enqThread), writeOffset)
        when(enqMask(lane)) {
          entries(enqThread)(writePtr) := io.enq.bits.entries(lane)
        }
      }
      tails(enqThread) := advancePtr(tails(enqThread), enqLaneCount)
    }

    when(enqFire && deqFire && enqThread === deqThread) {
      counts(enqThread) :=
        counts(enqThread) +
          enqLaneCount.pad(countWidth) -
          deqLaneCount.pad(countWidth)
    }.otherwise {
      when(enqFire) {
        counts(enqThread) := counts(enqThread) + enqLaneCount.pad(countWidth)
      }
      when(deqFire) {
        counts(deqThread) := counts(deqThread) - deqLaneCount.pad(countWidth)
      }
    }
  }
}
