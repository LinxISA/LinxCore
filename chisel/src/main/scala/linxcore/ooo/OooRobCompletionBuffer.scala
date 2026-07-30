package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, Valid, log2Ceil}

class OooRobCompletionBufferPrepared(val p: OooParams = OooParams())
    extends Bundle {
  val valid = Bool()
  val stid = UInt(p.stidWidth.W)
  val retained = UInt(p.robCompletionBufferCountWidth.W)
  val killed = UInt(p.robCompletionBufferCountWidth.W)
}

class OooRobCompletionBufferRecoveryReject(val p: OooParams = OooParams())
    extends Bundle {
  val requested = new OooResidencyRecoveryPlan(p)
  val used = UInt(p.robCompletionBufferCountWidth.W)
  val malformedMask = UInt(p.robCompletionBufferEntries.W)
}

class OooRobCompletionBufferIO(val p: OooParams = OooParams()) extends Bundle {
  val enqueues = Flipped(Vec(p.robCompletionInputWidth,
    Decoupled(new OooRobMemberCompletion(p))))
  val dequeue = Decoupled(new OooRobMemberCompletion(p))

  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Output(new OooRobCompletionBufferPrepared(p))
  val recoveryFire = Input(Bool())
  val recoveryRejected = Valid(new OooRobCompletionBufferRecoveryReject(p))

  val used = Output(UInt(p.robCompletionBufferCountWidth.W))
  val free = Output(UInt(p.robCompletionBufferCountWidth.W))
  val full = Output(Bool())
  val empty = Output(Bool())
  val enqueueCount = Output(UInt(p.countWidth(p.robCompletionInputWidth).W))
  val dequeued = Output(Bool())
}

/** Retained multi-producer completion boundary in front of the grouped ROB.
  *
  * All visible producers are accepted as one credit-checked group and retained
  * in lane order.  The current grouped ROB has one physical completion write
  * port, so this owner drains one exact completion per cycle without forcing
  * independent execution lanes to hold terminal state behind an arbiter.
  *
  * Recovery preparation is mutation-free.  Every buffered completion for the
  * selected STID must belong to the exact old ROB window.  The common recovery
  * fire removes only killed members and compacts all survivors in FIFO order;
  * completions for other STIDs remain live.
  */
class OooRobCompletionBuffer(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooRobCompletionBufferIO(p))

  private val entries = p.robCompletionBufferEntries
  private val pointerWidth = math.max(1, log2Ceil(entries))
  private val zeroCompletion = 0.U.asTypeOf(new OooRobMemberCompletion(p))

  private def addPointer(base: UInt, increment: UInt): UInt =
    (base + increment)(pointerWidth - 1, 0)

  private def pointerFromCount(value: UInt): UInt =
    value(pointerWidth - 1, 0)

  val rows = RegInit(VecInit(Seq.fill(entries)(zeroCompletion)))
  val head = RegInit(0.U(pointerWidth.W))
  val tail = RegInit(0.U(pointerWidth.W))
  val count = RegInit(0.U(p.robCompletionBufferCountWidth.W))

  val recoveryPlan = io.recoveryPrepare.bits
  val recoveryStid = recoveryPlan.oldHead.stid
  val recoveryStidInRange = recoveryStid < p.stidCount.U
  val recoveryPlanShapeExact = recoveryPlan.valid &&
    recoveryPlan.oldHead.valid && recoveryStidInRange &&
    recoveryPlan.oldOccupied <= p.robGroupsPerStid.U &&
    recoveryPlan.newOccupied <= recoveryPlan.oldOccupied
  val recoveryFreeze = io.recoveryPrepare.valid

  val recoveryRows = Wire(Vec(entries, new OooRobMemberCompletion(p)))
  val recoveryOccupied = Wire(Vec(entries, Bool()))
  val recoveryTargetsStid = Wire(Vec(entries, Bool()))
  val recoveryWindowExact = Wire(Vec(entries, Bool()))
  val recoveryKill = Wire(Vec(entries, Bool()))
  val recoveryKeep = Wire(Vec(entries, Bool()))
  val recoveryKeepRank = Wire(Vec(entries,
    UInt(p.robCompletionBufferCountWidth.W)))

  for (offset <- 0 until entries) {
    recoveryRows(offset) := rows(addPointer(head, offset.U))
    recoveryOccupied(offset) := offset.U < count
    recoveryTargetsStid(offset) := recoveryOccupied(offset) &&
      recoveryRows(offset).key.group.stid === recoveryStid
    recoveryWindowExact(offset) := !recoveryTargetsStid(offset) ||
      OooRecoveryMembership.memberInOldWindow(
        p, recoveryPlan, recoveryRows(offset).key)
    recoveryKill(offset) := recoveryTargetsStid(offset) &&
      recoveryWindowExact(offset) &&
      OooRecoveryMembership.memberKilled(
        p, recoveryPlan, recoveryRows(offset).key)
    recoveryKeep(offset) := recoveryOccupied(offset) &&
      !recoveryKill(offset)
    recoveryKeepRank(offset) := (if (offset == 0) 0.U else
      PopCount(VecInit(recoveryKeep.take(offset)).asUInt))
  }

  val malformedRecoveryMask = VecInit((0 until entries).map { offset =>
    recoveryTargetsStid(offset) && !recoveryWindowExact(offset)
  }).asUInt
  val recoveryExact = recoveryPlanShapeExact &&
    !malformedRecoveryMask.orR
  val recoverySurvivorCount = PopCount(recoveryKeep.asUInt)
  val recoveryKilledCount = PopCount(recoveryKill.asUInt)

  val compactedRows = Wire(Vec(entries, new OooRobMemberCompletion(p)))
  for (destination <- 0 until entries) {
    compactedRows(destination) := zeroCompletion
    for (source <- 0 until entries) {
      when(recoveryKeep(source) &&
          recoveryKeepRank(source) === destination.U) {
        compactedRows(destination) := recoveryRows(source)
      }
    }
  }

  io.recoveryPrepareReady := io.recoveryPrepare.valid && recoveryExact
  io.recoveryPrepared.valid := io.recoveryPrepareReady
  io.recoveryPrepared.stid := recoveryStid
  io.recoveryPrepared.retained := recoverySurvivorCount
  io.recoveryPrepared.killed := recoveryKilledCount
  io.recoveryRejected.valid := io.recoveryPrepare.valid && !recoveryExact
  io.recoveryRejected.bits.requested := recoveryPlan
  io.recoveryRejected.bits.used := count
  io.recoveryRejected.bits.malformedMask := malformedRecoveryMask

  val queueNonEmpty = count =/= 0.U
  io.dequeue.valid := queueNonEmpty && !recoveryFreeze
  io.dequeue.bits := Mux(queueNonEmpty, rows(head), zeroCompletion)
  val dequeueFire = io.dequeue.valid && io.dequeue.ready

  val offeredCount = PopCount(VecInit(io.enqueues.map(_.valid)).asUInt)
  val freeBefore = entries.U - count
  val freeAfterDequeue = freeBefore + dequeueFire.asUInt
  val enqueueWindowReady = !recoveryFreeze &&
    offeredCount <= freeAfterDequeue
  for (lane <- 0 until p.robCompletionInputWidth) {
    io.enqueues(lane).ready := enqueueWindowReady
    val priorAccepted = if (lane == 0) 0.U else PopCount(VecInit(
      io.enqueues.take(lane).map(port => port.valid && port.ready)).asUInt)
    val writePointer = addPointer(tail, priorAccepted)
    when(io.enqueues(lane).fire) {
      rows(writePointer) := io.enqueues(lane).bits
    }
  }
  val acceptedCount = PopCount(VecInit(io.enqueues.map(_.fire)).asUInt)

  when(io.recoveryFire) {
    assert(io.recoveryPrepare.valid && io.recoveryPrepareReady,
      "ROB completion recovery may apply only after an exact mutation-free prepare")
    rows := compactedRows
    head := 0.U
    tail := pointerFromCount(recoverySurvivorCount)
    count := recoverySurvivorCount
  }.elsewhen(!recoveryFreeze) {
    when(dequeueFire) {
      head := addPointer(head, 1.U)
    }
    when(acceptedCount =/= 0.U) {
      tail := addPointer(tail, acceptedCount)
    }
    count := count + acceptedCount - dequeueFire.asUInt
  }

  when(recoveryFreeze) {
    assert(!dequeueFire && acceptedCount === 0.U,
      "ROB completion recovery prepare must fence all queue mutation")
  }
  assert(count <= entries.U,
    "ROB completion buffer count must remain within physical capacity")
  when(enqueueWindowReady) {
    assert(acceptedCount === offeredCount,
      "ROB completion group admission must accept every visible producer")
  }

  io.used := count
  io.free := entries.U - count
  io.full := count === entries.U
  io.empty := count === 0.U
  io.enqueueCount := acceptedCount
  io.dequeued := dequeueFire
}
