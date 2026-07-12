package linxcore.execute

import chisel3._
import chisel3.util.{PopCount, PriorityEncoder, UIntToOH, log2Ceil}

import linxcore.common.InterfaceParams
import linxcore.rob.ROBID

class ScalarIssueCandidateArbiterIO(
    val p: InterfaceParams = InterfaceParams(),
    val candidates: Int = 2)
    extends Bundle {
  private val indexWidth = log2Ceil(candidates)

  val valid = Input(Vec(candidates, Bool()))
  val stid = Input(Vec(candidates, UInt(p.threadIdWidth.W)))
  val rid = Input(Vec(candidates, new ROBID(p.robEntries)))
  val advance = Input(Bool())

  val grant = Output(Vec(candidates, Bool()))
  val selectedValid = Output(Bool())
  val selectedIndex = Output(UInt(indexWidth.W))
  val contended = Output(Bool())
  val candidateCount = Output(UInt(log2Ceil(candidates + 1).W))
  val invalidRid = Output(Bool())
  val rrBase = Output(UInt(indexWidth.W))
}

class ScalarIssueCandidateArbiter(
    val p: InterfaceParams = InterfaceParams(),
    val candidates: Int = 2)
    extends Module {
  require(candidates > 1, "scalar issue arbitration needs at least two candidates")
  require((candidates & (candidates - 1)) == 0,
    "scalar issue candidate count must be a power of two")

  private val indexWidth = log2Ceil(candidates)
  val io = IO(new ScalarIssueCandidateArbiterIO(p, candidates))

  val rrBase = RegInit(0.U(indexWidth.W))
  val perStidOldest = Wire(Vec(candidates, Bool()))
  for (candidate <- 0 until candidates) {
    val olderPeer = VecInit((0 until candidates).filter(_ != candidate).map { peer =>
      val peerWinsTie = peer < candidate
      io.valid(peer) &&
        (io.stid(peer) === io.stid(candidate)) &&
        (ROBID.less(io.rid(peer), io.rid(candidate)) ||
          (ROBID.equal(io.rid(peer), io.rid(candidate)) && peerWinsTie.B))
    }).asUInt.orR
    perStidOldest(candidate) := io.valid(candidate) && !olderPeer
  }

  val scanValid = Wire(Vec(candidates, Bool()))
  for (offset <- 0 until candidates) {
    val candidate = (rrBase + offset.U)(indexWidth - 1, 0)
    scanValid(offset) := perStidOldest(candidate)
  }
  val selectedValid = scanValid.asUInt.orR
  val selectedOffset = PriorityEncoder(scanValid.asUInt)
  val selectedIndex = (rrBase + selectedOffset)(indexWidth - 1, 0)
  val grantMask = Mux(selectedValid, UIntToOH(selectedIndex, candidates), 0.U)
  val candidateCount = PopCount(io.valid)
  val invalidRid = VecInit((0 until candidates).map(idx => io.valid(idx) && !io.rid(idx).valid)).asUInt.orR

  io.grant := VecInit(grantMask.asBools)
  io.selectedValid := selectedValid
  io.selectedIndex := selectedIndex
  io.contended := candidateCount > 1.U
  io.candidateCount := candidateCount
  io.invalidRid := invalidRid
  io.rrBase := rrBase

  when(io.advance && selectedValid) {
    rrBase := selectedIndex + 1.U
  }

  assert(!invalidRid, "scalar issue arbitration requires a valid RID for every candidate")
}
