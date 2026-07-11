package linxcore.bctrl

import chisel3._
import chisel3.util.{log2Ceil, Mux1H, PopCount}

class BrobLiveBidResolverIO(
    val entries: Int,
    val pointerWidth: Int,
    val stidWidth: Int,
    val stidCount: Int)
    extends Bundle {
  private val slotWidth = BID.slotBits(entries)
  private val countWidth = log2Ceil(entries + 1)

  val candidateValid = Input(Bool())
  val candidateStid = Input(UInt(stidWidth.W))
  val candidateBid = Input(UInt(slotWidth.W))
  val headPointer = Input(Vec(stidCount, UInt(pointerWidth.W)))
  val liveCount = Input(Vec(stidCount, UInt(countWidth.W)))

  val stidInRange = Output(Bool())
  val matchValid = Output(Bool())
  val ambiguous = Output(Bool())
  val resolvedPointer = Output(UInt(pointerWidth.W))
  val distance = Output(UInt(countWidth.W))
  val matchCount = Output(UInt(countWidth.W))
}

/** Resolves an external canonical BID slot against one STID's bounded live window.
  *
  * The internal pointer carries wrap history; the external BID does not. Since a
  * live window contains at most `entries` consecutive pointers, a canonical slot
  * can occur at most once in a valid window.
  */
class BrobLiveBidResolver(
    val entries: Int = 16,
    val pointerWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val stidCount: Int = 1)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0, "BROB entries must be a power of two")
  require(pointerWidth > BID.slotBits(entries), "BROB pointer must carry wrap history above the BID slot")
  require(stidWidth > 0, "BROB STID width must be positive")
  require(stidCount > 0, "BROB must own at least one STID window")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "BROB STID count must fit stidWidth")

  private val countWidth = log2Ceil(entries + 1)
  val io = IO(new BrobLiveBidResolverIO(entries, pointerWidth, stidWidth, stidCount))

  val stidMatch = VecInit((0 until stidCount).map(lane => io.candidateStid === lane.U(stidWidth.W)))
  val stidInRange = stidMatch.asUInt.orR
  val selectedHead = Mux(stidInRange, Mux1H(stidMatch, io.headPointer), 0.U)
  val selectedCount = Mux(stidInRange, Mux1H(stidMatch, io.liveCount), 0.U)

  val pointers = VecInit((0 until entries).map(offset => selectedHead + offset.U))
  val hits = VecInit((0 until entries).map { offset =>
    io.candidateValid && stidInRange && offset.U < selectedCount &&
      BID.slot(pointers(offset), entries) === io.candidateBid
  })
  val matchCount = PopCount(hits)

  io.stidInRange := stidInRange
  io.matchValid := matchCount === 1.U
  io.ambiguous := matchCount > 1.U
  io.resolvedPointer := Mux(io.matchValid, Mux1H(hits, pointers), 0.U)
  io.distance := Mux(
    io.matchValid,
    Mux1H(hits, (0 until entries).map(_.U(countWidth.W))),
    0.U
  )
  io.matchCount := matchCount

  assert(!io.ambiguous, "canonical BID slot matched more than once in a bounded BROB live window")
}
