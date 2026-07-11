package linxcore.bctrl

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class BrobLiveBidResolverSpec extends AnyFunSuite {
  private def resolve(entries: Int, pointerWidth: Int, head: BigInt, liveCount: Int, slot: Int): Option[(BigInt, Int)] = {
    val mask = (BigInt(1) << pointerWidth) - 1
    val matches = (0 until liveCount).flatMap { distance =>
      val pointer = (head + distance) & mask
      Option.when((pointer & (entries - 1)) == slot)((pointer, distance))
    }
    assert(matches.size <= 1)
    matches.headOption
  }

  test("canonical BID resolves uniquely across internal pointer rollover") {
    assert(resolve(entries = 8, pointerWidth = 4, head = 14, liveCount = 3, slot = 0).contains((0, 2)))
    assert(resolve(entries = 8, pointerWidth = 4, head = 14, liveCount = 3, slot = 7).contains((15, 1)))
  }

  test("canonical BID outside the selected live window is rejected") {
    assert(resolve(entries = 8, pointerWidth = 4, head = 14, liveCount = 3, slot = 1).isEmpty)
    assert(resolve(entries = 8, pointerWidth = 4, head = 14, liveCount = 0, slot = 6).isEmpty)
  }

  test("Chisel BrobLiveBidResolver elaborates parameterized slot resolution") {
    val sv = ChiselStage.emitSystemVerilog(
      new BrobLiveBidResolver(entries = 8, pointerWidth = 16, stidWidth = 2, stidCount = 2)
    )
    assert(sv.contains("module BrobLiveBidResolver"))
    assert(sv.contains("io_candidateBid"))
    assert(sv.contains("io_resolvedPointer"))
    assert(sv.contains("io_matchCount"))
  }
}
