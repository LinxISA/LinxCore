package linxcore.bctrl

import chisel3._
import chisel3.util.Mux1H

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

class BrobAllocationRecoveryIO(
    val bidWidth: Int,
    val stidWidth: Int,
    val stidCount: Int)
    extends Bundle {
  val advanceValid = Input(Bool())
  val advanceStid = Input(UInt(stidWidth.W))
  val recoveryValid = Input(Bool())
  val recoveryStid = Input(UInt(stidWidth.W))
  val recoveryPivotBid = Input(UInt(bidWidth.W))
  val recoveryInclusive = Input(Bool())
  val queryStid = Input(UInt(stidWidth.W))

  val nextBid = Output(UInt(bidWidth.W))
  val queryInRange = Output(Bool())
  val advanceInRange = Output(Bool())
  val recoveryInRange = Output(Bool())
  val recoveryFirstKilledBid = Output(UInt(bidWidth.W))
  val recoveryOldAllocBid = Output(UInt(bidWidth.W))
  val recoveryApplied = Output(Bool())
  val cursor = Output(Vec(stidCount, UInt(bidWidth.W)))
}

/** Per-STID BROB allocation-tail owner.
  *
  * A miss-predict report names the first block to discard and therefore
  * restores the cursor inclusively. Other accepted global flushes preserve
  * the authoritative pivot block and restore the cursor to its successor.
  */
class BrobAllocationRecovery(
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val stidCount: Int = 1)
    extends Module {
  require(bidWidth > 0, "BROB BID width must be positive")
  require(stidWidth > 0, "BROB STID width must be positive")
  require(stidCount > 0, "BROB must own at least one STID cursor")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "BROB STID count must fit stidWidth")

  val io = IO(new BrobAllocationRecoveryIO(bidWidth, stidWidth, stidCount))
  val cursor = RegInit(VecInit(Seq.fill(stidCount)(0.U(bidWidth.W))))

  private def laneMatch(stid: UInt): Vec[Bool] =
    VecInit((0 until stidCount).map(lane => stid === lane.U(stidWidth.W)))

  val queryMatch = laneMatch(io.queryStid)
  val advanceMatch = laneMatch(io.advanceStid)
  val recoveryMatch = laneMatch(io.recoveryStid)
  val recoveryFirstKilledBid = Mux(
    io.recoveryInclusive,
    io.recoveryPivotBid,
    io.recoveryPivotBid + 1.U
  )

  io.queryInRange := queryMatch.asUInt.orR
  io.advanceInRange := advanceMatch.asUInt.orR
  io.recoveryInRange := recoveryMatch.asUInt.orR
  io.nextBid := Mux(io.queryInRange, Mux1H(queryMatch, cursor), 0.U)
  io.recoveryFirstKilledBid := recoveryFirstKilledBid
  io.recoveryOldAllocBid := Mux(io.recoveryInRange, Mux1H(recoveryMatch, cursor), 0.U)
  io.recoveryApplied := io.recoveryValid && io.recoveryInRange
  io.cursor := cursor

  for (lane <- 0 until stidCount) {
    when(io.recoveryValid && recoveryMatch(lane)) {
      cursor(lane) := recoveryFirstKilledBid
    }.elsewhen(io.advanceValid && advanceMatch(lane)) {
      cursor(lane) := cursor(lane) + 1.U
    }
  }
}
