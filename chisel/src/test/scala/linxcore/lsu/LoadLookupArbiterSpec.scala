package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadLookupArbiterReference {
  final case class Result(
      lookupValid: Boolean,
      lookupAddr: BigInt,
      lookupPc: BigInt,
      executeGranted: Boolean,
      replayGranted: Boolean,
      replayBlockedByExecute: Boolean,
      idle: Boolean)

  def apply(
      executeValid: Boolean,
      executeAddr: BigInt,
      executePc: BigInt,
      replayValid: Boolean,
      replayAddr: BigInt,
      replayPc: BigInt): Result = {
    val executeGranted = executeValid
    val replayGranted = !executeValid && replayValid
    val lookupValid = executeGranted || replayGranted
    val lookupAddr =
      if (executeGranted) executeAddr
      else if (replayGranted) replayAddr
      else BigInt(0)
    val lookupPc =
      if (executeGranted) executePc
      else if (replayGranted) replayPc
      else BigInt(0)

    Result(
      lookupValid = lookupValid,
      lookupAddr = lookupAddr,
      lookupPc = lookupPc,
      executeGranted = executeGranted,
      replayGranted = replayGranted,
      replayBlockedByExecute = executeValid && replayValid,
      idle = !executeValid && !replayValid)
  }
}

class LoadLookupArbiterSpec extends AnyFunSuite {
  import LoadLookupArbiterReference._

  test("grants execute over replay when both request the shared lookup port") {
    val result = LoadLookupArbiterReference(
      executeValid = true,
      executeAddr = 0x1008,
      executePc = 0x4000,
      replayValid = true,
      replayAddr = 0x2008,
      replayPc = 0x5000)

    assert(result.lookupValid)
    assert(result.lookupAddr == 0x1008)
    assert(result.lookupPc == 0x4000)
    assert(result.executeGranted)
    assert(!result.replayGranted)
    assert(result.replayBlockedByExecute)
    assert(!result.idle)
  }

  test("grants replay when execute is idle") {
    val result = LoadLookupArbiterReference(
      executeValid = false,
      executeAddr = 0x1008,
      executePc = 0x4000,
      replayValid = true,
      replayAddr = 0x2008,
      replayPc = 0x5000)

    assert(result.lookupValid)
    assert(result.lookupAddr == 0x2008)
    assert(result.lookupPc == 0x5000)
    assert(!result.executeGranted)
    assert(result.replayGranted)
    assert(!result.replayBlockedByExecute)
    assert(!result.idle)
  }

  test("reports idle with zeroed lookup identity when no source requests") {
    val result = LoadLookupArbiterReference(
      executeValid = false,
      executeAddr = 0x1008,
      executePc = 0x4000,
      replayValid = false,
      replayAddr = 0x2008,
      replayPc = 0x5000)

    assert(!result.lookupValid)
    assert(result.lookupAddr == 0)
    assert(result.lookupPc == 0)
    assert(!result.executeGranted)
    assert(!result.replayGranted)
    assert(!result.replayBlockedByExecute)
    assert(result.idle)
  }

  test("Chisel LoadLookupArbiter elaborates execute-priority diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadLookupArbiter())

    assert(sv.contains("module LoadLookupArbiter"))
    assert(sv.contains("io_executeValid"))
    assert(sv.contains("io_replayValid"))
    assert(sv.contains("io_lookupValid"))
    assert(sv.contains("io_lookupAddr"))
    assert(sv.contains("io_executeGranted"))
    assert(sv.contains("io_replayGranted"))
    assert(sv.contains("io_replayBlockedByExecute"))
  }
}
