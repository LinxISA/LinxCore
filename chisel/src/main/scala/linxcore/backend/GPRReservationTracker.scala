package linxcore.backend

import chisel3._
import chisel3.util.{log2Ceil, Mux1H}

class GPRReservationTrackerIO(
    val queueDepth: Int,
    val physRegs: Int,
    val mapQDepth: Int,
    val stidWidth: Int) extends Bundle {
  private val maxCreditsPerUop = 2
  private val countWidth = log2Ceil(queueDepth * maxCreditsPerUop + 1)
  private val creditWidth = log2Ceil(maxCreditsPerUop + 1)

  val flush = Input(Bool())
  val pushValid = Input(Bool())
  val pushStid = Input(UInt(stidWidth.W))
  val pushCount = Input(UInt(creditWidth.W))
  val popValid = Input(Bool())
  val popStid = Input(UInt(stidWidth.W))
  val popCount = Input(UInt(creditWidth.W))

  val selectedValid = Input(Bool())
  val selectedStid = Input(UInt(stidWidth.W))
  val selectedCount = Input(UInt(creditWidth.W))
  val freePhysCount = Input(UInt(log2Ceil(physRegs + 1).W))
  val selectedMapQFreeCount = Input(UInt(log2Ceil(mapQDepth + 1).W))

  val ready = Output(Bool())
  val selectedStidInRange = Output(Bool())
  val physReservationCount = Output(UInt(countWidth.W))
  val physReservationNeed = Output(UInt(countWidth.W))
  val selectedMapQReservationCount = Output(UInt(countWidth.W))
  val selectedMapQReservationNeed = Output(UInt(countWidth.W))
  val stateError = Output(Bool())
}

/** Tracks shared physical-register credit and lane-local MapQ credit.
  *
  * A scalar uop can reserve zero, one, or two credits.  The two-credit case is
  * required by architectural pair loads, whose two GPR destinations must be
  * renamed atomically.
  */
class GPRReservationTracker(
    val queueDepth: Int,
    val physRegs: Int,
    val mapQDepth: Int,
    val stidWidth: Int,
    val stidCount: Int) extends Module {
  require(queueDepth > 0, "GPR reservation queue depth must be nonzero")
  require(physRegs > 0, "GPR reservation physical capacity must be nonzero")
  require(mapQDepth > 0, "GPR reservation MapQ depth must be nonzero")
  require(stidCount > 0, "GPR reservation tracker must expose at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "GPR reservation STID count must fit stidWidth")

  private val maxCreditsPerUop = 2
  private val maxCredits = queueDepth * maxCreditsPerUop
  private val countWidth = log2Ceil(maxCredits + 1)
  val io = IO(new GPRReservationTrackerIO(queueDepth, physRegs, mapQDepth, stidWidth))

  private def matchesStid(stid: UInt): Vec[Bool] =
    VecInit((0 until stidCount).map(idx => stid === idx.U(stidWidth.W)))

  val pushMatch = matchesStid(io.pushStid)
  val popMatch = matchesStid(io.popStid)
  val selectedMatch = matchesStid(io.selectedStid)
  val pushInRange = pushMatch.asUInt.orR
  val popInRange = popMatch.asUInt.orR
  val selectedInRange = selectedMatch.asUInt.orR
  val pushCountValid = io.pushCount <= maxCreditsPerUop.U
  val popCountValid = io.popCount <= maxCreditsPerUop.U
  val selectedCountValid = io.selectedCount <= maxCreditsPerUop.U
  val pushFire = io.pushValid && pushInRange && pushCountValid && io.pushCount =/= 0.U
  val popFire = io.popValid && popInRange && popCountValid && io.popCount =/= 0.U
  val pushSlots = io.pushCount.pad(countWidth)
  val popSlots = io.popCount.pad(countWidth)

  val physReservationCount = RegInit(0.U(countWidth.W))
  val mapQReservationCount = RegInit(VecInit(Seq.fill(stidCount)(0.U(countWidth.W))))
  val selectedMapQReservationCount = Mux1H(selectedMatch, mapQReservationCount)
  val selectedSlots = io.selectedCount.pad(countWidth)
  val physReservationNeed = physReservationCount + selectedSlots
  val selectedMapQReservationNeed = selectedMapQReservationCount + selectedSlots

  io.ready := (!io.selectedValid || selectedInRange) && selectedCountValid &&
    (physReservationNeed <= io.freePhysCount) &&
    (selectedMapQReservationNeed <= io.selectedMapQFreeCount)
  io.selectedStidInRange := selectedInRange
  io.physReservationCount := physReservationCount
  io.physReservationNeed := physReservationNeed
  io.selectedMapQReservationCount := Mux(selectedInRange, selectedMapQReservationCount, 0.U)
  io.selectedMapQReservationNeed := Mux(selectedInRange, selectedMapQReservationNeed, 0.U)
  val physPushOverflow =
    pushFire && ((physReservationCount +& pushSlots) > (maxCredits.U + popSlots))
  val laneStateError = (0 until stidCount).map { stid =>
    val lanePush = pushFire && pushMatch(stid)
    val lanePop = popFire && popMatch(stid)
    val lanePushSlots = Mux(lanePush, pushSlots, 0.U)
    val lanePopSlots = Mux(lanePop, popSlots, 0.U)
    (lanePop && mapQReservationCount(stid) < popSlots) ||
      (lanePush && ((mapQReservationCount(stid) +& lanePushSlots) > (maxCredits.U + lanePopSlots)))
  }.reduce(_ || _)
  io.stateError := (io.pushValid && (!pushInRange || !pushCountValid || io.pushCount === 0.U)) ||
    (io.popValid && (!popInRange || !popCountValid || io.popCount === 0.U)) ||
    (io.selectedValid && (!selectedInRange || !selectedCountValid)) ||
    (!io.selectedValid && io.selectedCount =/= 0.U) ||
    (popFire && physReservationCount < popSlots) || physPushOverflow || laneStateError

  when(io.flush) {
    physReservationCount := 0.U
    mapQReservationCount := VecInit(Seq.fill(stidCount)(0.U(countWidth.W)))
  }.otherwise {
    when(pushFire || popFire) {
      physReservationCount :=
        physReservationCount + Mux(pushFire, pushSlots, 0.U) - Mux(popFire, popSlots, 0.U)
    }

    for (stid <- 0 until stidCount) {
      val lanePush = pushFire && pushMatch(stid)
      val lanePop = popFire && popMatch(stid)
      when(lanePush || lanePop) {
        mapQReservationCount(stid) :=
          mapQReservationCount(stid) +
            Mux(lanePush, pushSlots, 0.U) -
            Mux(lanePop, popSlots, 0.U)
      }
    }
  }
}
