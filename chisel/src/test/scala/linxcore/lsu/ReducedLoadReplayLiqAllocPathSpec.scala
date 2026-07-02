package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ReducedLoadReplayLiqAllocPathReference {
  import ReducedLoadReplayLiqAllocAdapterReference.{candidate, id}
  import ReducedLoadWaitReplaySlotReference.Relaunch

  final case class Row(candidate: Relaunch, loadIdWrap: Boolean, loadIdValue: Int)
  final case class Step(
      consumeReady: Boolean,
      allocValid: Boolean,
      allocReady: Boolean,
      allocAccepted: Boolean,
      blockedByAlloc: Boolean,
      residentCount: Int,
      rows: Vector[Row])

  final class Model(entries: Int) {
    require(entries > 1 && (entries & (entries - 1)) == 0)

    private var rows = Vector.empty[Row]
    private var allocPtr = 0
    private var allocWrap = false

    def step(flush: Boolean = false, candidateIn: Option[Relaunch] = None): Step = {
      val usable = !flush && candidateIn.isDefined
      val allocReady = !flush && !rows.exists(_.loadIdValue == allocPtr)
      val accepted = usable && allocReady
      val beforeFlushRows = rows

      if (flush) {
        rows = Vector.empty
        allocPtr = 0
        allocWrap = false
      } else if (accepted) {
        rows = rows :+ Row(candidateIn.get, allocWrap, allocPtr)
        if (allocPtr == entries - 1) {
          allocPtr = 0
          allocWrap = !allocWrap
        } else {
          allocPtr += 1
        }
      }

      Step(
        consumeReady = accepted,
        allocValid = usable,
        allocReady = allocReady,
        allocAccepted = accepted,
        blockedByAlloc = usable && !allocReady,
        residentCount = rows.size,
        rows = if (flush) Vector.empty else if (accepted) rows else beforeFlushRows)
    }

    def defaultCandidate(pc: BigInt, lsId: Int, youngestStoreLsId: Int): Relaunch =
      candidate.copy(pc = pc, lsId = id(lsId), youngestStoreLsId = id(youngestStoreLsId))
  }
}

class ReducedLoadReplayLiqAllocPathSpec extends AnyFunSuite {
  import ReducedLoadReplayLiqAllocPathReference._

  test("allocates replay candidates into LIQ rows in allocation-pointer order") {
    val model = new Model(entries = 2)
    val first = model.defaultCandidate(pc = 0x4000, lsId = 3, youngestStoreLsId = 1)
    val second = model.defaultCandidate(pc = 0x4010, lsId = 4, youngestStoreLsId = 2)

    val s0 = model.step(candidateIn = Some(first))
    val s1 = model.step(candidateIn = Some(second))

    assert(s0.consumeReady && s0.allocAccepted && s0.residentCount == 1)
    assert(s1.consumeReady && s1.allocAccepted && s1.residentCount == 2)
    assert(s1.rows.map(_.loadIdValue) == Vector(0, 1))
    assert(s1.rows.head.candidate.lsId.value == 3)
    assert(s1.rows.head.candidate.youngestStoreLsId.value == 1)
    assert(s1.rows(1).candidate.lsId.value == 4)
    assert(s1.rows(1).candidate.youngestStoreLsId.value == 2)
  }

  test("full LIQ backpressures the replay queue head without consuming it") {
    val model = new Model(entries = 2)

    assert(model.step(candidateIn = Some(model.defaultCandidate(0x4000, 1, 1))).allocAccepted)
    assert(model.step(candidateIn = Some(model.defaultCandidate(0x4010, 2, 2))).allocAccepted)
    val full = model.step(candidateIn = Some(model.defaultCandidate(0x4020, 3, 3)))

    assert(full.allocValid)
    assert(!full.allocReady)
    assert(!full.consumeReady)
    assert(!full.allocAccepted)
    assert(full.blockedByAlloc)
    assert(full.residentCount == 2)
  }

  test("flush clears resident LIQ rows and suppresses candidate consumption") {
    val model = new Model(entries = 2)

    assert(model.step(candidateIn = Some(model.defaultCandidate(0x4000, 1, 1))).allocAccepted)
    val flushed = model.step(flush = true, candidateIn = Some(model.defaultCandidate(0x4010, 2, 2)))
    val afterFlush = model.step(candidateIn = Some(model.defaultCandidate(0x4020, 3, 3)))

    assert(!flushed.allocValid)
    assert(!flushed.consumeReady)
    assert(flushed.residentCount == 0)
    assert(afterFlush.allocAccepted)
    assert(afterFlush.rows.head.loadIdValue == 0)
    assert(!afterFlush.rows.head.loadIdWrap)
  }

  test("Chisel ReducedLoadReplayLiqAllocPath elaborates adapter and LIQ row owner") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedLoadReplayLiqAllocPath(liqEntries = 4, idEntries = 8, storeEntries = 4))

    assert(sv.contains("module ReducedLoadReplayLiqAllocPath"))
    assert(sv.contains("ReducedLoadReplayLiqAllocAdapter"))
    assert(sv.contains("LoadInflightQueue"))
    assert(sv.contains("LoadInflightLaunchSelect"))
    assert(sv.contains("io_candidateConsumeReady"))
    assert(sv.contains("io_candidateBlockedByAlloc"))
    assert(sv.contains("io_allocAccepted"))
    assert(sv.contains("io_launchEnable"))
    assert(sv.contains("io_e2Stores_0_valid"))
    assert(sv.contains("io_launchDataHitMask"))
    assert(sv.contains("io_launchCandidateMask"))
    assert(sv.contains("io_launchValid"))
    assert(sv.contains("io_launchDriveValid"))
    assert(sv.contains("io_launchReady"))
    assert(sv.contains("io_launchAccepted"))
    assert(sv.contains("io_e4UpdateValid"))
    assert(sv.contains("io_lhqRecordValid"))
    assert(sv.contains("io_missPending"))
    assert(sv.contains("io_rows_0_loadLsId_value"))
    assert(sv.contains("io_rows_0_youngestStoreLsId_value"))
  }
}
