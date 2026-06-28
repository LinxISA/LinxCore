package linxcore.rename

import chisel3._
import chisel3.util.Fill

import linxcore.common.InterfaceParams
import linxcore.rob.ROBID

class TULinkLocalBlockCommitFanoutIO(
    val p: InterfaceParams = InterfaceParams(),
    val peCount: Int = 1,
    val stidCount: Int = 1,
    val stidWidth: Int = 8)
    extends Bundle {
  val inValid = Input(Bool())
  val inBid = Input(new ROBID(p.robEntries))
  val inStid = Input(UInt(stidWidth.W))
  val bankReady = Input(Vec(peCount, Vec(stidCount, Bool())))

  val ready = Output(Bool())
  val accepted = Output(Bool())
  val bankValid = Output(Vec(peCount, Vec(stidCount, Bool())))
  val bankBid = Output(Vec(peCount, Vec(stidCount, new ROBID(p.robEntries))))
  val bankStid = Output(Vec(peCount, Vec(stidCount, UInt(stidWidth.W))))
  val selectedStidOH = Output(UInt(stidCount.W))
  val selectedPeReadyMask = Output(UInt(peCount.W))
  val targetPeMask = Output(UInt(peCount.W))
  val stidInRange = Output(Bool())
  val blockedByStidRange = Output(Bool())
  val blockedByBankReady = Output(Bool())
}

class TULinkLocalBlockCommitFanout(
    val p: InterfaceParams = InterfaceParams(),
    val peCount: Int = 1,
    val stidCount: Int = 1,
    val stidWidth: Int = 8)
    extends Module {
  require(peCount > 0, "local block-commit fanout must target at least one scalar PE")
  require(stidCount > 0, "local block-commit fanout must target at least one scalar STID")
  require(stidWidth > 0, "STID width must be positive")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "STID count must fit stidWidth")

  val io = IO(new TULinkLocalBlockCommitFanoutIO(
    p = p,
    peCount = peCount,
    stidCount = stidCount,
    stidWidth = stidWidth
  ))

  private def zeroRobId: ROBID =
    0.U.asTypeOf(new ROBID(p.robEntries))

  val stidMatches = VecInit((0 until stidCount).map(stid => io.inStid === stid.U(stidWidth.W)))
  val stidInRange = stidMatches.asUInt.orR

  val selectedPeReady = Wire(Vec(peCount, Bool()))
  for (pe <- 0 until peCount) {
    selectedPeReady(pe) := (0 until stidCount)
      .map(stid => stidMatches(stid) && io.bankReady(pe)(stid))
      .reduce(_ || _)
  }

  val allSelectedPeReady = selectedPeReady.asUInt.andR
  val ready = stidInRange && allSelectedPeReady
  val accepted = io.inValid && ready

  for (pe <- 0 until peCount) {
    for (stid <- 0 until stidCount) {
      val selected = stidMatches(stid)
      io.bankValid(pe)(stid) := accepted && selected
      io.bankBid(pe)(stid) := Mux(selected, io.inBid, zeroRobId)
      io.bankStid(pe)(stid) := Mux(selected, io.inStid, 0.U(stidWidth.W))
    }
  }

  io.ready := ready
  io.accepted := accepted
  io.selectedStidOH := stidMatches.asUInt
  io.selectedPeReadyMask := selectedPeReady.asUInt
  io.targetPeMask := Fill(peCount, io.inValid && stidInRange)
  io.stidInRange := stidInRange
  io.blockedByStidRange := io.inValid && !stidInRange
  io.blockedByBankReady := io.inValid && stidInRange && !allSelectedPeReady
}
