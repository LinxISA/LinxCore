package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadWaitStoreTimeoutReference {
  final case class WaitKey(loadGeneration: Int, storeBid: Int, storeLsId: Int, storePc: BigInt)
  final case class Slot(key: Option[WaitKey] = None, age: Int = 0)

  final class Model(entries: Int, timeoutCycles: Int) {
    require(entries > 1)
    require(timeoutCycles > 0)

    private val slots = Array.fill(entries)(Slot())

    def age(index: Int): Int = slots(index).age

    def step(active: Seq[Option[WaitKey]], releaseAccepted: Boolean = false): Option[Int] = {
      require(active.size == entries)
      val expired = active.indices.filter { index =>
        active(index).nonEmpty &&
          slots(index).key == active(index) &&
          slots(index).age >= timeoutCycles - 1
      }
      val release = expired.headOption

      for (index <- active.indices) {
        if (releaseAccepted && release.contains(index)) {
          slots(index) = Slot()
        } else {
          active(index) match {
            case None => slots(index) = Slot()
            case key if slots(index).key != key => slots(index) = Slot(key = key, age = 0)
            case key => slots(index) = Slot(key = key, age = math.min(timeoutCycles - 1, slots(index).age + 1))
          }
        }
      }
      release
    }
  }
}

class LoadWaitStoreTimeoutSpec extends AnyFunSuite {
  import LoadWaitStoreTimeoutReference._

  private val waitA = WaitKey(loadGeneration = 0, storeBid = 3, storeLsId = 2, storePc = 0x2000)
  private val waitB = WaitKey(loadGeneration = 1, storeBid = 3, storeLsId = 2, storePc = 0x2000)

  test("expires a stable wait after the configured number of resident cycles") {
    val model = new Model(entries = 4, timeoutCycles = 4)
    assert(model.step(Seq(Some(waitA), None, None, None)).isEmpty)
    assert(model.step(Seq(Some(waitA), None, None, None)).isEmpty)
    assert(model.step(Seq(Some(waitA), None, None, None)).isEmpty)
    assert(model.step(Seq(Some(waitA), None, None, None)).isEmpty)
    assert(model.step(Seq(Some(waitA), None, None, None)).contains(0))
  }

  test("restarts ageing when the load generation or predicted store changes") {
    val model = new Model(entries = 4, timeoutCycles = 3)
    model.step(Seq(Some(waitA), None, None, None))
    model.step(Seq(Some(waitA), None, None, None))
    assert(model.age(0) == 1)

    assert(model.step(Seq(Some(waitB), None, None, None)).isEmpty)
    assert(model.age(0) == 0)
    assert(model.step(Seq(None, None, None, None)).isEmpty)
    assert(model.age(0) == 0)
  }

  test("holds an expired row under backpressure and serializes simultaneous expiries") {
    val model = new Model(entries = 4, timeoutCycles = 2)
    val active = Seq(Some(waitA), Some(waitB), None, None)
    model.step(active)
    model.step(active)
    assert(model.step(active).contains(0))
    assert(model.step(active).contains(0))
    assert(model.step(active, releaseAccepted = true).contains(0))
    assert(model.step(Seq(None, Some(waitB), None, None)).contains(1))
  }

  test("Chisel timer elaborates with per-row active, expired, and accepted-release state") {
    val sv = ChiselStage.emitSystemVerilog(new LoadWaitStoreTimeout(
      liqEntries = 4,
      idEntries = 32,
      storeEntries = 4,
      timeoutCycles = 4
    ))

    assert(sv.contains("module LoadWaitStoreTimeout"))
    assert(sv.contains("io_activeMask"))
    assert(sv.contains("io_expiredMask"))
    assert(sv.contains("io_releaseAccepted"))
  }
}
