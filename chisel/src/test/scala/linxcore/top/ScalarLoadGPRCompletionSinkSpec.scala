package linxcore.top

import circt.stage.ChiselStage
import linxcore.common.{ScalarBackendParams, ScalarLsuParams}
import org.scalatest.funsuite.AnyFunSuite

object ScalarLoadGPRCompletionSinkReference {
  final case class Result(
      ready: Boolean,
      writebackFire: Boolean,
      wakeupFire: Boolean,
      blockedByExternal: Boolean,
      unsupportedDestination: Boolean)

  def arbitrate(
      loadValid: Boolean,
      loadTag: Int,
      externalTag: Option[Int],
      writePorts: Int,
      resolveReady: Boolean,
      destinationSupported: Boolean = true): Result = {
    val unsupported = loadValid && !destinationSupported
    val hasPort = externalTag.isEmpty || (writePorts > 1 && !externalTag.contains(loadTag))
    val ready = !loadValid || (destinationSupported && hasPort)
    val resolveFire = loadValid && resolveReady && ready
    Result(
      ready = ready,
      writebackFire = resolveFire,
      wakeupFire = resolveFire,
      blockedByExternal = loadValid && destinationSupported && !hasPort,
      unsupportedDestination = unsupported)
  }
}

class ScalarLoadGPRCompletionSinkSpec extends AnyFunSuite {
  import ScalarLoadGPRCompletionSinkReference._

  test("same-tag external write holds scalar W2") {
    val result = arbitrate(
      loadValid = true,
      loadTag = 40,
      externalTag = Some(40),
      writePorts = 2,
      resolveReady = true)
    assert(!result.ready && !result.writebackFire && !result.wakeupFire)
    assert(result.blockedByExternal)
  }

  test("independent tags use parameterized write ports in one cycle") {
    val result = arbitrate(
      loadValid = true,
      loadTag = 40,
      externalTag = Some(41),
      writePorts = 2,
      resolveReady = true)
    assert(result.ready && result.writebackFire && result.wakeupFire)
    assert(!result.blockedByExternal)
  }

  test("one physical port serializes every external write") {
    val result = arbitrate(
      loadValid = true,
      loadTag = 40,
      externalTag = Some(41),
      writePorts = 1,
      resolveReady = true)
    assert(!result.ready && result.blockedByExternal)
  }

  test("unsupported T/U destination is an explicit non-mutating contract error") {
    val result = arbitrate(
      loadValid = true,
      loadTag = 40,
      externalTag = None,
      writePorts = 2,
      resolveReady = true,
      destinationSupported = false)
    assert(!result.ready)
    assert(!result.writebackFire && !result.wakeupFire)
    assert(result.unsupportedDestination)
  }

  test("sink elaborates canonical GPR writeback and ready-table wakeup ownership") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarLoadGPRCompletionSink(
      ScalarBackendParams(gprWritePorts = 2),
      ScalarLsuParams()
    ))
    assert(sv.contains("module ScalarLoadGPRCompletionSink"))
    assert(sv.contains("module ScalarGPRFile"))
    assert(sv.contains("io_loadWritebackReady"))
    assert(sv.contains("io_loadWakeupReady"))
    assert(sv.contains("io_loadBlockedByExternalWrite"))
    assert(sv.contains("io_readyMask"))
  }
}
