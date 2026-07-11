package linxcore.backend

import chisel3._
import chisel3.util.{log2Ceil, Mux1H}

class GPRReservationTrackerIO(
    val queueDepth: Int,
    val physRegs: Int,
    val mapQDepth: Int,
    val stidWidth: Int) extends Bundle {
  private val countWidth = log2Ceil(queueDepth + 1)

  val flush = Input(Bool())
  val pushValid = Input(Bool())
  val pushStid = Input(UInt(stidWidth.W))
  val popValid = Input(Bool())
  val popStid = Input(UInt(stidWidth.W))

  val selectedValid = Input(Bool())
  val selectedStid = Input(UInt(stidWidth.W))
  val selectedNeedsGpr = Input(Bool())
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

/** Tracks shared physical-register credit and lane-local MapQ credit. */
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

  private val countWidth = log2Ceil(queueDepth + 1)
  val io = IO(new GPRReservationTrackerIO(queueDepth, physRegs, mapQDepth, stidWidth))

  private def matchesStid(stid: UInt): Vec[Bool] =
    VecInit((0 until stidCount).map(idx => stid === idx.U(stidWidth.W)))

  val pushMatch = matchesStid(io.pushStid)
  val popMatch = matchesStid(io.popStid)
  val selectedMatch = matchesStid(io.selectedStid)
  val pushInRange = pushMatch.asUInt.orR
  val popInRange = popMatch.asUInt.orR
  val selectedInRange = selectedMatch.asUInt.orR
  val pushFire = io.pushValid && pushInRange
  val popFire = io.popValid && popInRange

  val physReservationCount = RegInit(0.U(countWidth.W))
  val mapQReservationCount = RegInit(VecInit(Seq.fill(stidCount)(0.U(countWidth.W))))
  val selectedMapQReservationCount = Mux1H(selectedMatch, mapQReservationCount)
  val selectedSlots = Mux(io.selectedNeedsGpr, 1.U(countWidth.W), 0.U(countWidth.W))
  val physReservationNeed = physReservationCount + selectedSlots
  val selectedMapQReservationNeed = selectedMapQReservationCount + selectedSlots

  io.ready := (!io.selectedValid || selectedInRange) &&
    (physReservationNeed <= io.freePhysCount) &&
    (selectedMapQReservationNeed <= io.selectedMapQFreeCount)
  io.selectedStidInRange := selectedInRange
  io.physReservationCount := physReservationCount
  io.physReservationNeed := physReservationNeed
  io.selectedMapQReservationCount := Mux(selectedInRange, selectedMapQReservationCount, 0.U)
  io.selectedMapQReservationNeed := Mux(selectedInRange, selectedMapQReservationNeed, 0.U)
  io.stateError := (io.pushValid && !pushInRange) || (io.popValid && !popInRange) ||
    (io.selectedValid && !selectedInRange) || (popFire && physReservationCount === 0.U) ||
    (0 until stidCount).map(stid => popMatch(stid) && mapQReservationCount(stid) === 0.U).reduce(_ || _)

  when(io.flush) {
    physReservationCount := 0.U
    mapQReservationCount := VecInit(Seq.fill(stidCount)(0.U(countWidth.W)))
  }.otherwise {
    when(pushFire && !popFire) {
      physReservationCount := physReservationCount + 1.U
    }.elsewhen(!pushFire && popFire) {
      physReservationCount := physReservationCount - 1.U
    }

    for (stid <- 0 until stidCount) {
      val lanePush = pushFire && pushMatch(stid)
      val lanePop = popFire && popMatch(stid)
      when(lanePush && !lanePop) {
        mapQReservationCount(stid) := mapQReservationCount(stid) + 1.U
      }.elsewhen(!lanePush && lanePop) {
        mapQReservationCount(stid) := mapQReservationCount(stid) - 1.U
      }
    }
  }
}
