package linxcore.recovery

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class RecoveryProducerSpec extends AnyFunSuite {
  test("recovery producers retain parameterized exact full-BID reports") {
    val modules = Seq(
      ChiselStage.emitSystemVerilog(new BccRecoverySource(queueEntries = 3, entries = 8, bidWidth = 16)),
      ChiselStage.emitSystemVerilog(new IexSlowInsertRecoverySource(queueEntries = 3, entries = 8, bidWidth = 16)),
      ChiselStage.emitSystemVerilog(new IexIqStallRecoverySource(
        stallThreshold = 7,
        queueEntries = 3,
        entries = 8,
        bidWidth = 16
      )),
      ChiselStage.emitSystemVerilog(new PeMismatchRecoverySource(queueEntries = 3, entries = 8, bidWidth = 16))
    )

    assert(modules.forall(_.contains("module RecoveryProducerQueue")))
    assert(modules(0).contains("io_source_blockBid"))
    assert(modules(1).contains("io_source_immediateFlush"))
    assert(modules(2).contains("io_blockedByMissingIdentity"))
    assert(modules(3).contains("io_source_rid_value"))
  }

  test("producer probe composes BCC IEX and PE reports through RecoveryFabric") {
    val sv = ChiselStage.emitSystemVerilog(new RecoveryProducerProbe)

    assert(sv.contains("module BccRecoverySource"))
    assert(sv.contains("module IexSlowInsertRecoverySource"))
    assert(sv.contains("module IexIqStallRecoverySource"))
    assert(sv.contains("module PeMismatchRecoverySource"))
    assert(sv.contains("module RecoveryNonLsuProducerBank"))
    assert(sv.contains("module RecoveryFabric"))
    assert(sv.contains("io_intentBlockBid"))
    assert(sv.contains("io_intentFetchTpc"))
    assert(sv.contains("io_intentExecEngine"))
    assert(sv.contains("io_stallBlockedByMissingIdentity"))
  }

  test("non-LSU producer bank exposes four independently retained source lanes") {
    val io = new RecoveryNonLsuProducerBankIO(
      queueEntries = 3,
      stallThreshold = 7,
      entries = 8,
      bidWidth = 16,
      peIdWidth = 2,
      stidWidth = 2,
      tidWidth = 2
    )
    assert(io.sources.length == RecoveryNonLsuProducerBank.SourceCount)
    assert(io.pendingMask.getWidth == RecoveryNonLsuProducerBank.SourceCount)
    assert(io.iexIqStallCount.getWidth == 3)

    val sv = ChiselStage.emitSystemVerilog(new RecoveryNonLsuProducerBank(
      queueEntries = 3,
      stallThreshold = 7,
      entries = 8,
      bidWidth = 16,
      peIdWidth = 2,
      stidWidth = 2,
      tidWidth = 2
    ))
    assert(sv.contains("module RecoveryNonLsuProducerBank"))
    assert(sv.contains("module BccRecoverySource"))
    assert(sv.contains("module IexSlowInsertRecoverySource"))
    assert(sv.contains("module IexIqStallRecoverySource"))
    assert(sv.contains("module PeMismatchRecoverySource"))
  }

  test("IEX IQ watchdog identity selects one STID and wraps the full commit pointer") {
    def resolve(stid: Int, oldestValid: Seq[Boolean], oldestComplete: Seq[Boolean], commit: Seq[Int]):
        (Boolean, Boolean, Int) = {
      val inRange = stid >= 0 && stid < commit.length
      val identityValid = inRange && oldestValid(stid)
      val complete = !identityValid || oldestComplete(stid)
      val recovery = if (inRange) (commit(stid) + 1) & 0xffff else 0
      (identityValid, complete, recovery)
    }

    assert(resolve(1, Seq(true, true), Seq(false, false), Seq(0x12, 0xffff)) ==
      ((true, false, 0)))
    assert(resolve(2, Seq(true, true), Seq(false, false), Seq(0x12, 0x34)) ==
      ((false, true, 0)))

    val sv = ChiselStage.emitSystemVerilog(new IexIqStallRecoveryIdentity(
      stidCount = 2,
      bidWidth = 16,
      stidWidth = 2
    ))
    assert(sv.contains("module IexIqStallRecoveryIdentity"))
    assert(sv.contains("io_blockCommitPointer_1"))
    assert(sv.contains("io_recoveryBlockBid"))
  }

  test("minimum producer queue and watchdog threshold elaborate") {
    val queueSv = ChiselStage.emitSystemVerilog(new RecoveryProducerQueue(
      queueEntries = 1,
      entries = 8,
      bidWidth = 16
    ))
    val stallSv = ChiselStage.emitSystemVerilog(new IexIqStallRecoverySource(
      stallThreshold = 1,
      queueEntries = 1,
      entries = 8,
      bidWidth = 16
    ))

    assert(queueSv.contains("module RecoveryProducerQueue"))
    assert(queueSv.contains("io_inReady"))
    assert(stallSv.contains("io_triggerCaptured"))
    assert(stallSv.contains("io_blockedByMissingIdentity"))
  }
}
