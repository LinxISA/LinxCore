package linxcore.bctrl

import chisel3._
import chisel3.util.{log2Ceil, Mux1H, Queue, RRArbiter}

class BrobRobAllocationAdmissionIO extends Bundle {
  val allocValid = Input(Bool())
  val usesExistingBlock = Input(Bool())
  val stidInRange = Input(Bool())
  val brobReady = Input(Bool())
  val robReady = Input(Bool())
  val recoveryValid = Input(Bool())
  val allocReady = Output(Bool())
  val allocFire = Output(Bool())
  val robAllocValid = Output(Bool())
  val brobAllocValid = Output(Bool())
}

/** One decision shared by public allocation and both resident child owners. */
class BrobRobAllocationAdmission extends Module {
  val io = IO(new BrobRobAllocationAdmissionIO)
  val needsBrob = io.allocValid && !io.usesExistingBlock
  val canUseBrob = io.stidInRange && (!needsBrob || io.brobReady)

  io.allocReady := io.robReady && canUseBrob && !io.recoveryValid
  io.allocFire := io.allocValid && io.allocReady
  io.robAllocValid := io.allocValid && canUseBrob && !io.recoveryValid
  io.brobAllocValid := needsBrob && io.allocReady
}

class BrobOrderStateIO(
    val entries: Int,
    val bidWidth: Int,
    val stidWidth: Int,
    val stidCount: Int)
    extends Bundle {
  private val countWidth = log2Ceil(entries + 1)
  private val blockBidWidth = BID.slotBits(entries)

  val allocValid = Input(Bool())
  val allocBid = Input(UInt(bidWidth.W))
  val allocStid = Input(UInt(stidWidth.W))

  val recoveryValid = Input(Bool())
  val recoveryStid = Input(UInt(stidWidth.W))
  val recoveryPivotBid = Input(UInt(blockBidWidth.W))
  val recoveryTransportPointerValid = Input(Bool())
  val recoveryTransportPointer = Input(UInt(bidWidth.W))
  val recoveryInclusive = Input(Bool())

  val headResident = Input(Vec(stidCount, Bool()))
  val headComplete = Input(Vec(stidCount, Bool()))
  val retireReady = Input(Bool())

  val allocInRange = Output(Bool())
  val allocIdentityMatch = Output(Bool())
  val allocApplied = Output(Bool())
  val recoveryInRange = Output(Bool())
  val recoveryCanonicalMatch = Output(Bool())
  val recoveryResolvedPivotBid = Output(UInt(bidWidth.W))
  val recoveryLegacyPointerMismatch = Output(Bool())
  val recoveryWindowValid = Output(Bool())
  val recoveryFirstKilledBid = Output(UInt(bidWidth.W))
  val recoveryOldAllocBid = Output(UInt(bidWidth.W))
  val recoveryRetainedCount = Output(UInt(countWidth.W))
  val recoveryApplied = Output(Bool())

  val allocCursor = Output(Vec(stidCount, UInt(bidWidth.W)))
  val commitCursor = Output(Vec(stidCount, UInt(bidWidth.W)))
  val liveCount = Output(Vec(stidCount, UInt(countWidth.W)))
  val empty = Output(Vec(stidCount, Bool()))
  val full = Output(Vec(stidCount, Bool()))
  val headValid = Output(Vec(stidCount, Bool()))
  val headMismatch = Output(Vec(stidCount, Bool()))

  val retireValid = Output(Bool())
  val retireBid = Output(UInt(bidWidth.W))
  val retireStid = Output(UInt(stidWidth.W))
  val retireFire = Output(Bool())
}

class BrobRetireIdentity(val bidWidth: Int, val stidWidth: Int) extends Bundle {
  val bid = UInt(bidWidth.W)
  val stid = UInt(stidWidth.W)
}

/** Per-STID BROB allocation/commit window and ordered-retirement owner.
  *
  * Internal pointers are monotonically allocated modulo `bidWidth`. Recovery
  * resolves the external canonical BID slot against the selected STID's bounded
  * live window before truncating the resident suffix.
  */
class BrobOrderState(
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val stidCount: Int = 1)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0, "BROB entries must be a power of two")
  require(bidWidth > log2Ceil(entries), "BROB BID width must include uniqueness bits")
  require(stidWidth > 0, "BROB STID width must be positive")
  require(stidCount > 0, "BROB must own at least one STID window")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "BROB STID count must fit stidWidth")

  private val countWidth = log2Ceil(entries + 1)
  val io = IO(new BrobOrderStateIO(entries, bidWidth, stidWidth, stidCount))

  val allocCursor = RegInit(VecInit(Seq.fill(stidCount)(0.U(bidWidth.W))))
  val commitCursor = RegInit(VecInit(Seq.fill(stidCount)(0.U(bidWidth.W))))
  val liveCount = RegInit(VecInit(Seq.fill(stidCount)(0.U(countWidth.W))))

  private def laneMatch(stid: UInt): Vec[Bool] =
    VecInit((0 until stidCount).map(lane => stid === lane.U(stidWidth.W)))

  val allocMatch = laneMatch(io.allocStid)
  val recoveryMatch = laneMatch(io.recoveryStid)
  val allocInRange = allocMatch.asUInt.orR
  val recoveryInRange = recoveryMatch.asUInt.orR
  val selectedAllocCursor = Mux(allocInRange, Mux1H(allocMatch, allocCursor), 0.U)
  val selectedAllocCount = Mux(allocInRange, Mux1H(allocMatch, liveCount), 0.U)
  val selectedRecoveryTail = Mux(recoveryInRange, Mux1H(recoveryMatch, allocCursor), 0.U)

  val recoveryResolver = Module(new BrobLiveBidResolver(entries, bidWidth, stidWidth, stidCount))
  recoveryResolver.io.candidateValid := io.recoveryValid
  recoveryResolver.io.candidateStid := io.recoveryStid
  recoveryResolver.io.candidateBid := io.recoveryPivotBid
  recoveryResolver.io.headPointer := commitCursor
  recoveryResolver.io.liveCount := liveCount

  val recoveryResolvedPivotBid = recoveryResolver.io.resolvedPointer
  val recoveryFirstKilledBid = Mux(
    io.recoveryInclusive,
    recoveryResolvedPivotBid,
    recoveryResolvedPivotBid + 1.U
  )
  val recoveryRetainedCount = Wire(UInt(countWidth.W))
  recoveryRetainedCount := recoveryResolver.io.distance + Mux(io.recoveryInclusive, 0.U, 1.U)
  val recoveryWindowValid = recoveryResolver.io.matchValid && !recoveryResolver.io.ambiguous
  val recoveryApplied = io.recoveryValid && recoveryWindowValid

  val allocIdentityMatch = io.allocBid === selectedAllocCursor
  val allocApplied = io.allocValid && !io.recoveryValid && allocInRange &&
    allocIdentityMatch && selectedAllocCount =/= entries.U

  val retireArb = Module(new RRArbiter(UInt(stidWidth.W), stidCount))
  for (lane <- 0 until stidCount) {
    val headValid = liveCount(lane) =/= 0.U
    retireArb.io.in(lane).valid := !io.recoveryValid && headValid && io.headResident(lane) && io.headComplete(lane)
    retireArb.io.in(lane).bits := lane.U
    io.empty(lane) := !headValid
    io.full(lane) := liveCount(lane) === entries.U
    io.headValid(lane) := headValid
    io.headMismatch(lane) := headValid =/= io.headResident(lane)
  }
  val retireSlot = withReset(reset.asBool || recoveryApplied) {
    Module(new Queue(new BrobRetireIdentity(bidWidth, stidWidth), entries = 1, pipe = false, flow = true))
  }
  retireSlot.io.enq.valid := retireArb.io.out.valid && !io.recoveryValid
  retireSlot.io.enq.bits.stid := retireArb.io.out.bits
  retireSlot.io.enq.bits.bid := commitCursor(retireArb.io.chosen)
  retireArb.io.out.ready := retireSlot.io.enq.ready && !io.recoveryValid
  retireSlot.io.deq.ready := io.retireReady && !io.recoveryValid

  io.allocInRange := allocInRange
  io.allocIdentityMatch := allocIdentityMatch
  io.allocApplied := allocApplied
  io.recoveryInRange := recoveryInRange
  io.recoveryCanonicalMatch := recoveryResolver.io.matchValid
  io.recoveryResolvedPivotBid := recoveryResolvedPivotBid
  io.recoveryLegacyPointerMismatch := io.recoveryValid && recoveryResolver.io.matchValid &&
    io.recoveryTransportPointerValid && io.recoveryTransportPointer =/= recoveryResolvedPivotBid
  io.recoveryWindowValid := recoveryWindowValid
  io.recoveryFirstKilledBid := recoveryFirstKilledBid
  io.recoveryOldAllocBid := selectedRecoveryTail
  io.recoveryRetainedCount := recoveryRetainedCount
  io.recoveryApplied := recoveryApplied
  io.allocCursor := allocCursor
  io.commitCursor := commitCursor
  io.liveCount := liveCount
  io.retireValid := retireSlot.io.deq.valid && !io.recoveryValid
  io.retireStid := Mux(io.retireValid, retireSlot.io.deq.bits.stid, 0.U)
  io.retireBid := Mux(io.retireValid, retireSlot.io.deq.bits.bid, 0.U)
  io.retireFire := retireSlot.io.deq.fire

  for (lane <- 0 until stidCount) {
    val laneAlloc = allocApplied && allocMatch(lane)
    val laneRetire = retireSlot.io.deq.fire && retireSlot.io.deq.bits.stid === lane.U
    when(recoveryApplied && recoveryMatch(lane)) {
      allocCursor(lane) := recoveryFirstKilledBid
      liveCount(lane) := recoveryRetainedCount
    }.otherwise {
      when(laneAlloc) {
        allocCursor(lane) := allocCursor(lane) + 1.U
      }
      when(laneRetire) {
        commitCursor(lane) := commitCursor(lane) + 1.U
      }
      when(laneAlloc && !laneRetire) {
        liveCount(lane) := liveCount(lane) + 1.U
      }.elsewhen(laneRetire && !laneAlloc) {
        liveCount(lane) := liveCount(lane) - 1.U
      }
    }
  }
}
