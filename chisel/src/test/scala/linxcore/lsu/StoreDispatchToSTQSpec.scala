package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import linxcore.rename.StoreSplitStoreType
import org.scalatest.funsuite.AnyFunSuite

object StoreDispatchToSTQReference {
  final case class Decision(
      staCandidate: Boolean,
      stdCandidate: Boolean,
      selectedSta: Boolean,
      selectedStd: Boolean,
      blockedByStaExec: Boolean,
      blockedByStdExec: Boolean,
      blockedByStaInsert: Boolean,
      blockedByStdInsert: Boolean,
      stdBypassStaBlocked: Boolean)

  def decide(
      staValid: Boolean,
      stdValid: Boolean,
      staExecValid: Boolean,
      stdExecValid: Boolean,
      staInsertReady: Boolean,
      stdInsertReady: Boolean,
      flush: Boolean = false): Decision = {
    val staCandidate = !flush && staValid && staExecValid
    val stdCandidate = !flush && stdValid && stdExecValid
    val selectedSta = staCandidate && staInsertReady
    val selectedStd = !selectedSta && stdCandidate && stdInsertReady

    Decision(
      staCandidate = staCandidate,
      stdCandidate = stdCandidate,
      selectedSta = selectedSta,
      selectedStd = selectedStd,
      blockedByStaExec = !flush && staValid && !staExecValid,
      blockedByStdExec = !flush && stdValid && !stdExecValid,
      blockedByStaInsert = staCandidate && !staInsertReady,
      blockedByStdInsert = stdCandidate && !stdInsertReady && !selectedSta,
      stdBypassStaBlocked = selectedStd && staCandidate && !staInsertReady
    )
  }
}

class StoreDispatchToSTQSpec extends AnyFunSuite {
  import StoreDispatchToSTQReference._

  test("reference gives executed STA priority when both halves can insert") {
    val decision = decide(
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true,
      staInsertReady = true,
      stdInsertReady = true)

    assert(decision.staCandidate)
    assert(decision.stdCandidate)
    assert(decision.selectedSta)
    assert(!decision.selectedStd)
    assert(!decision.stdBypassStaBlocked)
  }

  test("reference allows STD to bypass a present STA only when STA cannot insert") {
    val decision = decide(
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true,
      staInsertReady = false,
      stdInsertReady = true)

    assert(decision.staCandidate)
    assert(decision.stdCandidate)
    assert(!decision.selectedSta)
    assert(decision.selectedStd)
    assert(decision.blockedByStaInsert)
    assert(decision.stdBypassStaBlocked)
  }

  test("reference reports execution-result backpressure separately from insert backpressure") {
    val decision = decide(
      staValid = true,
      stdValid = true,
      staExecValid = false,
      stdExecValid = false,
      staInsertReady = true,
      stdInsertReady = true)

    assert(!decision.staCandidate)
    assert(!decision.stdCandidate)
    assert(!decision.selectedSta)
    assert(!decision.selectedStd)
    assert(decision.blockedByStaExec)
    assert(decision.blockedByStdExec)
    assert(!decision.blockedByStaInsert)
    assert(!decision.blockedByStdInsert)
  }

  test("reference suppresses candidates and dequeue on flush") {
    val decision = decide(
      staValid = true,
      stdValid = true,
      staExecValid = true,
      stdExecValid = true,
      staInsertReady = true,
      stdInsertReady = true,
      flush = true)

    assert(!decision.staCandidate)
    assert(!decision.stdCandidate)
    assert(!decision.selectedSta)
    assert(!decision.selectedStd)
    assert(!decision.blockedByStaExec)
    assert(!decision.blockedByStdExec)
  }

  test("StoreDispatchToSTQ IO preserves STQ request widths and store type order") {
    val p = InterfaceParams(robEntries = 8)
    val io = new StoreDispatchToSTQIO(p, entries = 8)

    assert(StoreSplitStoreType.All.asUInt.litValue == 0)
    assert(StoreSplitStoreType.Addr.asUInt.litValue == 1)
    assert(StoreSplitStoreType.Data.asUInt.litValue == 2)
    assert(STQStoreType.All.asUInt.litValue == 0)
    assert(STQStoreType.Addr.asUInt.litValue == 1)
    assert(STQStoreType.Data.asUInt.litValue == 2)
    assert(io.staDequeueReady.getWidth == 1)
    assert(io.stdDequeueReady.getWidth == 1)
    assert(io.insertValid.getWidth == 1)
    assert(io.insert.bid.value.getWidth == 3)
    assert(io.insert.lsId.value.getWidth == 3)
    assert(io.insert.tSeq.value.getWidth == 5)
    assert(io.insert.uSeq.value.getWidth == 5)
    assert(io.insert.tuDstValid.getWidth == 1)
    assert(io.insert.addr.getWidth == 64)
    assert(io.insert.data.getWidth == 64)
    assert(io.insert.size.getWidth == 4)
    assert(io.staExec.peId.getWidth == 8)
    assert(io.stdExec.simtLane.getWidth == 8)
  }

  test("StoreDispatchToSTQ elaborates as a separate STQ insert bridge") {
    val sv = ChiselStage.emitSystemVerilog(new StoreDispatchToSTQ(InterfaceParams(robEntries = 8), entries = 8))

    assert(sv.contains("module StoreDispatchToSTQ"))
    assert(sv.contains("io_selectedSta"))
    assert(sv.contains("io_selectedStd"))
    assert(sv.contains("io_insertValid"))
    assert(sv.contains("io_insert_tSeq_value"))
    assert(sv.contains("io_insert_tuDstValid"))
    assert(sv.contains("io_stdBypassStaBlocked"))
  }
}
