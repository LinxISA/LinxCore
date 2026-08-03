package linxcore.testutil

import org.scalatest.funsuite.AnyFunSuite

class SimulationProgressSpec extends AnyFunSuite {
  test("a typed progress event resets the idle-cycle window") {
    val progress = new SimulationProgress(8, () => "rob=1,liq=0")

    progress.observe(
      7,
      Seq(SimulationProgressEvent("dispatch", "rid=3")))
    progress.requireAlive(15)
  }

  test("the first cycle beyond the limit reports identity and occupancy") {
    val progress = new SimulationProgress(8, () => "rob=1,liq=1")
    progress.observe(
      4,
      Seq(SimulationProgressEvent("memory-rebind", "attempt=2")))

    val error = intercept[IllegalStateException] {
      progress.requireAlive(13)
    }

    assert(error.getMessage.contains("cycle=13"))
    assert(error.getMessage.contains("lastCycle=4"))
    assert(error.getMessage.contains("memory-rebind"))
    assert(error.getMessage.contains("attempt=2"))
    assert(error.getMessage.contains("rob=1,liq=1"))
  }

  test("empty observations do not create synthetic progress") {
    val progress = new SimulationProgress(2, () => "iq=1")

    progress.observe(0, Seq.empty)

    assertThrows[IllegalStateException] {
      progress.requireAlive(3)
    }
  }

  test("decreasing cycle observations fail closed") {
    val progress = new SimulationProgress(8, () => "rob=0")
    progress.observe(5, Seq(SimulationProgressEvent("commit", "rid=1")))

    val error = intercept[IllegalArgumentException] {
      progress.observe(4, Seq(SimulationProgressEvent("dispatch", "rid=2")))
    }

    assert(error.getMessage.contains("cycle must not move backwards"))
  }

  test("the idle limit must be positive") {
    val error = intercept[IllegalArgumentException] {
      new SimulationProgress(0, () => "")
    }

    assert(error.getMessage.contains("maxIdleCycles must be positive"))
  }
}
