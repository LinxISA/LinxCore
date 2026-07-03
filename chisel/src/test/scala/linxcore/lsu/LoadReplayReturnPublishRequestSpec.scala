package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPublishRequestReference {
  final case class Result(
      requestValid: Boolean,
      lretRequest: Boolean,
      writebackRequest: Boolean,
      wakeupRequest: Boolean,
      requestMask: Int,
      blockedByNoFire: Boolean,
      invalidFireWithoutPayload: Boolean)

  def apply(
      publishFire: Boolean,
      payloadValid: Boolean,
      writebackRequired: Boolean,
      wakeupRequired: Boolean): Result = {
    val requestValid = publishFire && payloadValid
    val writebackRequest = requestValid && writebackRequired
    val wakeupRequest = requestValid && wakeupRequired
    val requestMask =
      (if (wakeupRequest) 4 else 0) |
        (if (writebackRequest) 2 else 0) |
        (if (requestValid) 1 else 0)

    Result(
      requestValid = requestValid,
      lretRequest = requestValid,
      writebackRequest = writebackRequest,
      wakeupRequest = wakeupRequest,
      requestMask = requestMask,
      blockedByNoFire = payloadValid && !publishFire,
      invalidFireWithoutPayload = publishFire && !payloadValid)
  }
}

class LoadReplayReturnPublishRequestSpec extends AnyFunSuite {
  import LoadReplayReturnPublishRequestReference._

  test("requests all live side effects when publish fires with all effects required") {
    val result = LoadReplayReturnPublishRequestReference(
      publishFire = true,
      payloadValid = true,
      writebackRequired = true,
      wakeupRequired = true)

    assert(result.requestValid)
    assert(result.lretRequest)
    assert(result.writebackRequest)
    assert(result.wakeupRequest)
    assert(result.requestMask == 0x7)
    assert(!result.blockedByNoFire)
    assert(!result.invalidFireWithoutPayload)
  }

  test("always requests LRET but suppresses optional side effects when not required") {
    val result = LoadReplayReturnPublishRequestReference(
      publishFire = true,
      payloadValid = true,
      writebackRequired = false,
      wakeupRequired = false)

    assert(result.requestValid)
    assert(result.lretRequest)
    assert(!result.writebackRequest)
    assert(!result.wakeupRequest)
    assert(result.requestMask == 0x1)
  }

  test("does not request side effects while publish fire is disabled") {
    val result = LoadReplayReturnPublishRequestReference(
      publishFire = false,
      payloadValid = true,
      writebackRequired = true,
      wakeupRequired = true)

    assert(!result.requestValid)
    assert(!result.lretRequest)
    assert(!result.writebackRequest)
    assert(!result.wakeupRequest)
    assert(result.requestMask == 0x0)
    assert(result.blockedByNoFire)
  }

  test("flags an illegal fire without a payload") {
    val result = LoadReplayReturnPublishRequestReference(
      publishFire = true,
      payloadValid = false,
      writebackRequired = true,
      wakeupRequired = true)

    assert(!result.requestValid)
    assert(!result.lretRequest)
    assert(!result.writebackRequest)
    assert(!result.wakeupRequest)
    assert(result.invalidFireWithoutPayload)
    assert(!result.blockedByNoFire)
  }

  test("encodes independent writeback and wakeup request mask bits") {
    val writebackOnly = LoadReplayReturnPublishRequestReference(
      publishFire = true,
      payloadValid = true,
      writebackRequired = true,
      wakeupRequired = false)
    val wakeupOnly = LoadReplayReturnPublishRequestReference(
      publishFire = true,
      payloadValid = true,
      writebackRequired = false,
      wakeupRequired = true)

    assert(writebackOnly.requestMask == 0x3)
    assert(wakeupOnly.requestMask == 0x5)
  }

  test("Chisel LoadReplayReturnPublishRequest elaborates request diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPublishRequest)

    assert(sv.contains("module LoadReplayReturnPublishRequest"))
    assert(sv.contains("io_publishFire"))
    assert(sv.contains("io_payloadValid"))
    assert(sv.contains("io_writebackRequired"))
    assert(sv.contains("io_wakeupRequired"))
    assert(sv.contains("io_requestValid"))
    assert(sv.contains("io_lretRequest"))
    assert(sv.contains("io_writebackRequest"))
    assert(sv.contains("io_wakeupRequest"))
    assert(sv.contains("io_requestMask"))
    assert(sv.contains("io_invalidFireWithoutPayload"))
  }
}
