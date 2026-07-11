package linxcore.recovery

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class RecoveryClassMergeSpec extends AnyFunSuite {
  test("recovery class merge elaborates independent Linx STID and PE lanes") {
    val sv = ChiselStage.emitSystemVerilog(new RecoveryClassMerge(
      stidCount = 2,
      peCount = 3,
      entries = 8,
      bidWidth = 16
    ))

    assert(sv.contains("module RecoveryClassMerge"))
    assert(sv.contains("io_globalFlushPendingMask"))
    assert(sv.contains("io_globalReplayPendingMask"))
    assert(sv.contains("io_pePendingMask"))
    assert(sv.contains("io_inDroppedByOlder"))
    assert(sv.contains("io_inDroppedByComplete"))
    assert(sv.contains("io_inMerged"))
    assert(sv.contains("io_selectedClass"))
  }

  test("recovery class probe exposes the generated RTL proof surface") {
    val sv = ChiselStage.emitSystemVerilog(new RecoveryClassMergeProbe)

    assert(sv.contains("module RecoveryClassMergeProbe"))
    assert(sv.contains("module RecoveryClassMerge"))
    assert(sv.contains("io_outBlockBid"))
    assert(sv.contains("io_outClass"))
    assert(sv.contains("io_globalFlushPendingMask"))
    assert(sv.contains("io_globalReplayPendingMask"))
    assert(sv.contains("io_pePendingMask"))
  }

  test("recovery fabric preserves source, class, and cleanup ownership boundaries") {
    val sv = ChiselStage.emitSystemVerilog(new RecoveryFabric(
      sourceCount = 4,
      stidCount = 2,
      peCount = 3,
      entries = 8,
      bidWidth = 16
    ))

    assert(sv.contains("module RecoveryFabric"))
    assert(sv.contains("module RecoverySourceArbiter"))
    assert(sv.contains("module RecoveryClassMerge"))
    assert(sv.contains("module RecoveryCleanupControl"))
    assert(sv.contains("io_sourcePendingMask"))
    assert(sv.contains("io_sourceBlockedByPe"))
    assert(sv.contains("io_classGlobalFlushPendingMask"))
    assert(sv.contains("io_classBlockedByPe"))
    assert(sv.contains("io_intent_blockFlushBid"))
  }
}
