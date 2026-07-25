package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, Valid, log2Ceil}
import linxcore.common.InterfaceParams

class ISideLineContextRow(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val valid = Bool()
  val request = new ISideFetchRequest(p, lineBytes)
  val startPc = UInt(p.pcWidth.W)
  val completed = Bool()
  val result = new ISideF2Result(p, lineBytes)
}

class ISideLineContextQueueIO(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val entries: Int = 8)
    extends Bundle {
  private val countWidth = log2Ceil(entries + 1)

  val allocate = Flipped(Decoupled(new ISideFetchRequest(p, lineBytes)))
  val complete = Flipped(Decoupled(new ISideF2Result(p, lineBytes)))
  val carry = Flipped(Valid(new ISidePrefixCarry(p, lineBytes)))
  val flush = Input(new IfuInnerFlush(p))
  val out = Decoupled(new ISideF2Result(p, lineBytes))

  val count = Output(UInt(countWidth.W))
  val completedMask = Output(UInt(entries.W))
  val headValid = Output(Bool())
  val headCompleted = Output(Bool())
  val headRequest = Output(new ISideFetchRequest(p, lineBytes))
  val completionUnmatched = Output(Bool())
  val completionDuplicate = Output(Bool())
  val carryPending = Output(Bool())
  val carryUnmatched = Output(Bool())
}

/** Ordered transaction context between I-F2 and I-F3.
  *
  * F0 allocation order is retained independently from translation/cache return
  * order. I-F2 may therefore complete younger hits while an older line waits
  * for refill, but I-F3 observes only the oldest completed line. A cross-line
  * instruction applies one exact prefix/carry update to its already-prefetched
  * successor, or retains that update until the successor allocates.
  */
class ISideLineContextQueue(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val entries: Int = 8)
    extends Module {
  require(entries > 0 && (entries & (entries - 1)) == 0)

  private val ptrWidth = math.max(1, log2Ceil(entries))
  private val countWidth = log2Ceil(entries + 1)

  val io = IO(new ISideLineContextQueueIO(p, lineBytes, entries))

  val rows = RegInit(
    VecInit(Seq.fill(entries)(0.U.asTypeOf(new ISideLineContextRow(p, lineBytes)))))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))
  val pendingCarryValid = RegInit(false.B)
  val pendingCarry = RegInit(0.U.asTypeOf(new ISidePrefixCarry(p, lineBytes)))

  def advance(ptr: UInt, amount: UInt): UInt =
    (ptr + amount)(ptrWidth - 1, 0)

  // Chisel represents the sole row of a one-entry queue with a zero-bit
  // index.  Keep entries=1 as a supported verification configuration without
  // elaborating over-wide dynamic Vec indices.
  def rowAt(index: UInt): ISideLineContextRow =
    if (entries == 1) rows(0) else rows(index)

  def exactRequest(lhs: ISideFetchRequest, rhs: ISideFetchRequest): Bool =
    lhs.identity.peId === rhs.identity.peId &&
      lhs.transactionId === rhs.transactionId &&
      lhs.identity.threadId === rhs.identity.threadId &&
      lhs.identity.fetchPacketUid === rhs.identity.fetchPacketUid &&
      lhs.identity.fetchSeq === rhs.identity.fetchSeq &&
      lhs.identity.checkpointId === rhs.identity.checkpointId &&
      lhs.identity.epoch === rhs.identity.epoch &&
      lhs.pc === rhs.pc &&
      lhs.lineVa === rhs.lineVa

  def carryMatches(request: ISideFetchRequest, carry: ISidePrefixCarry): Bool =
    request.identity.peId === carry.successorIdentity.peId &&
      request.transactionId === carry.successorTransactionId &&
      request.identity.threadId === carry.successorIdentity.threadId &&
      request.identity.fetchPacketUid === carry.successorIdentity.fetchPacketUid &&
      request.identity.fetchSeq === carry.successorIdentity.fetchSeq &&
      request.identity.checkpointId === carry.successorIdentity.checkpointId &&
      request.identity.epoch === carry.successorIdentity.epoch &&
      request.lineVa === carry.successorLineVa

  val completionMatches = Wire(Vec(entries, Bool()))
  val carryMatchesRows = Wire(Vec(entries, Bool()))
  for (entry <- 0 until entries) {
    completionMatches(entry) :=
      rows(entry).valid && exactRequest(rows(entry).request, io.complete.bits.request)
    carryMatchesRows(entry) :=
      rows(entry).valid && carryMatches(rows(entry).request, io.carry.bits)
  }
  val completionMatchValid = completionMatches.asUInt.orR
  val completionMatchIndex = chisel3.util.PriorityEncoder(completionMatches.asUInt)
  val completionDuplicate =
    completionMatchValid && rowAt(completionMatchIndex).completed
  val incomingCarryMatchValid = carryMatchesRows.asUInt.orR
  val incomingCarryMatchIndex = chisel3.util.PriorityEncoder(carryMatchesRows.asUInt)
  val incomingCarryMatchesAllocate =
    io.carry.valid && carryMatches(io.allocate.bits, io.carry.bits)
  val pendingCarryMatchesAllocate =
    pendingCarryValid && carryMatches(io.allocate.bits, pendingCarry)

  val headRow = rowAt(head)
  val incomingCarryMatchesHead =
    io.carry.valid && headRow.valid && carryMatches(headRow.request, io.carry.bits)

  io.allocate.ready := count =/= entries.U && !io.flush.valid
  io.complete.ready := !io.flush.valid
  io.out.valid :=
    count =/= 0.U &&
      headRow.valid &&
      headRow.completed &&
      !incomingCarryMatchesHead &&
      !io.flush.valid
  io.out.bits := headRow.result
  io.out.bits.request.pc := headRow.startPc

  io.count := count
  io.completedMask := VecInit(rows.map(row => row.valid && row.completed)).asUInt
  io.headValid := count =/= 0.U && headRow.valid
  io.headCompleted := io.headValid && headRow.completed
  io.headRequest := headRow.request
  io.completionUnmatched := io.complete.valid && !completionMatchValid
  io.completionDuplicate := io.complete.valid && completionDuplicate
  io.carryPending := pendingCarryValid
  io.carryUnmatched :=
    io.carry.valid &&
      !incomingCarryMatchValid &&
      !(io.allocate.valid && incomingCarryMatchesAllocate)

  val allocateFire = io.allocate.valid && io.allocate.ready
  val completeFire = io.complete.valid && io.complete.ready
  val outFire = io.out.valid && io.out.ready

  when(io.flush.valid) {
    for (entry <- 0 until entries) {
      rows(entry).valid := false.B
    }
    val keep = Wire(Vec(entries, Bool()))
    val keepPrefix = Wire(Vec(entries + 1, UInt(countWidth.W)))
    keepPrefix(0) := 0.U
    for (offset <- 0 until entries) {
      val readPtr = advance(head, offset.U)
      val row = rowAt(readPtr)
      keep(offset) :=
        offset.U < count &&
          !IfuFlushContract.kills(
            row.request.identity,
            row.request.transactionId,
            io.flush)
      keepPrefix(offset + 1) := keepPrefix(offset) + keep(offset).asUInt
      val writePtr = keepPrefix(offset)(ptrWidth - 1, 0)
      when(keep(offset)) {
        rowAt(writePtr) := row
      }
    }
    val keptCount = keepPrefix(entries)
    head := 0.U
    tail := keptCount(ptrWidth - 1, 0)
    count := keptCount

    when(
      pendingCarryValid &&
        IfuFlushContract.kills(
          pendingCarry.successorIdentity,
          pendingCarry.successorTransactionId,
          io.flush)) {
      pendingCarryValid := false.B
    }
  }.otherwise {
    when(allocateFire) {
      rowAt(tail) := 0.U.asTypeOf(rowAt(tail))
      rowAt(tail).valid := true.B
      rowAt(tail).request := io.allocate.bits
      rowAt(tail).startPc := io.allocate.bits.pc
      when(incomingCarryMatchesAllocate) {
        rowAt(tail).startPc := io.carry.bits.successorPc
      }.elsewhen(pendingCarryMatchesAllocate) {
        rowAt(tail).startPc := pendingCarry.successorPc
        pendingCarryValid := false.B
      }
      tail := advance(tail, 1.U)
    }

    when(completeFire && completionMatchValid && !completionDuplicate) {
      rowAt(completionMatchIndex).completed := true.B
      rowAt(completionMatchIndex).result := io.complete.bits
    }

    when(io.carry.valid && incomingCarryMatchValid) {
      rowAt(incomingCarryMatchIndex).startPc := io.carry.bits.successorPc
    }.elsewhen(
      io.carry.valid &&
        !(allocateFire && incomingCarryMatchesAllocate)) {
      assert(!pendingCarryValid, "I-SIDE may retain only one unresolved prefix carry")
      pendingCarryValid := true.B
      pendingCarry := io.carry.bits
    }

    when(outFire) {
      rowAt(head).valid := false.B
      head := advance(head, 1.U)
    }

    when(allocateFire && !outFire) {
      count := count + 1.U
    }.elsewhen(!allocateFire && outFire) {
      count := count - 1.U
    }
  }

  when(completeFire && completionMatchValid) {
    assert(chisel3.util.PopCount(completionMatches) === 1.U)
  }
  when(io.carry.valid && incomingCarryMatchValid) {
    assert(chisel3.util.PopCount(carryMatchesRows) === 1.U)
  }
}
