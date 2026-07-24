package linxcore.execute

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ReducedScalarWritebackArbiterReference {
  final case class Result(
      writeValid: Boolean,
      writeTag: Int,
      writeData: BigInt,
      selectedExecute: Boolean,
      selectedReplay: Boolean,
      selectedService: Boolean,
      selectedTemplate: Boolean,
      replayBlockedByDisabled: Boolean,
      replayBlockedByExecute: Boolean,
      serviceBlockedByDisabled: Boolean,
      serviceBlockedByExecute: Boolean,
      serviceBlockedByReplay: Boolean,
      templateBlockedByDisabled: Boolean,
      templateBlockedByExecute: Boolean,
      templateBlockedByReplay: Boolean,
      templateBlockedByService: Boolean)

  def apply(
      executeValid: Boolean,
      executeTag: Int,
      executeData: BigInt,
      replayEnable: Boolean,
      replayValid: Boolean,
      replayTag: Int,
      replayData: BigInt,
      serviceEnable: Boolean = false,
      serviceValid: Boolean = false,
      serviceTag: Int = 0,
      serviceData: BigInt = 0,
      templateEnable: Boolean = false,
      templateValid: Boolean = false,
      templateTag: Int = 0,
      templateData: BigInt = 0): Result = {
    val replayCandidate = replayEnable && replayValid
    val serviceCandidate = serviceEnable && serviceValid
    val templateCandidate = templateEnable && templateValid
    val selectedExecute = executeValid
    val selectedReplay = replayCandidate && !executeValid
    val selectedService = serviceCandidate && !executeValid && !replayCandidate
    val selectedTemplate = templateCandidate && !executeValid && !replayCandidate && !serviceCandidate
    val selectedTag =
      if (selectedExecute) executeTag
      else if (selectedReplay) replayTag
      else if (selectedService) serviceTag
      else if (selectedTemplate) templateTag
      else 0
    val selectedData =
      if (selectedExecute) executeData
      else if (selectedReplay) replayData
      else if (selectedService) serviceData
      else if (selectedTemplate) templateData
      else BigInt(0)

    Result(
      writeValid = selectedExecute || selectedReplay || selectedService || selectedTemplate,
      writeTag = selectedTag,
      writeData = selectedData,
      selectedExecute = selectedExecute,
      selectedReplay = selectedReplay,
      selectedService = selectedService,
      selectedTemplate = selectedTemplate,
      replayBlockedByDisabled = !replayEnable && replayValid,
      replayBlockedByExecute = replayCandidate && executeValid,
      serviceBlockedByDisabled = !serviceEnable && serviceValid,
      serviceBlockedByExecute = serviceCandidate && executeValid,
      serviceBlockedByReplay = serviceCandidate && !executeValid && replayCandidate,
      templateBlockedByDisabled = !templateEnable && templateValid,
      templateBlockedByExecute = templateCandidate && executeValid,
      templateBlockedByReplay = templateCandidate && !executeValid && replayCandidate,
      templateBlockedByService = templateCandidate && !executeValid && !replayCandidate && serviceCandidate)
  }
}

class ReducedScalarWritebackArbiterSpec extends AnyFunSuite with ChiselSim {
  import ReducedScalarWritebackArbiterReference._

  test("selects execute writeback with priority over replay") {
    val result = ReducedScalarWritebackArbiterReference(
      executeValid = true,
      executeTag = 5,
      executeData = BigInt("1111222233334444", 16),
      replayEnable = true,
      replayValid = true,
      replayTag = 42,
      replayData = BigInt("aaaabbbbccccdddd", 16))

    assert(result.writeValid)
    assert(result.writeTag == 5)
    assert(result.writeData == BigInt("1111222233334444", 16))
    assert(result.selectedExecute)
    assert(!result.selectedReplay)
    assert(!result.selectedService)
    assert(!result.selectedTemplate)
    assert(result.replayBlockedByExecute)
    assert(!result.replayBlockedByDisabled)
  }

  test("selects replay writeback when enabled and execute is idle") {
    val result = ReducedScalarWritebackArbiterReference(
      executeValid = false,
      executeTag = 5,
      executeData = 0x1111,
      replayEnable = true,
      replayValid = true,
      replayTag = 42,
      replayData = BigInt("aaaabbbbccccdddd", 16))

    assert(result.writeValid)
    assert(result.writeTag == 42)
    assert(result.writeData == BigInt("aaaabbbbccccdddd", 16))
    assert(!result.selectedExecute)
    assert(result.selectedReplay)
    assert(!result.selectedService)
    assert(!result.selectedTemplate)
    assert(!result.replayBlockedByExecute)
    assert(!result.replayBlockedByDisabled)
  }

  test("blocks replay while disabled and suppresses stale write fields") {
    val result = ReducedScalarWritebackArbiterReference(
      executeValid = false,
      executeTag = 5,
      executeData = 0x1111,
      replayEnable = false,
      replayValid = true,
      replayTag = 42,
      replayData = 0x2222)

    assert(!result.writeValid)
    assert(result.writeTag == 0)
    assert(result.writeData == 0)
    assert(!result.selectedExecute)
    assert(!result.selectedReplay)
    assert(!result.selectedService)
    assert(!result.selectedTemplate)
    assert(!result.replayBlockedByExecute)
    assert(result.replayBlockedByDisabled)
  }

  test("selects service after replay and before template") {
    val result = ReducedScalarWritebackArbiterReference(
      executeValid = false,
      executeTag = 5,
      executeData = 0x1111,
      replayEnable = false,
      replayValid = false,
      replayTag = 42,
      replayData = 0x2222,
      serviceEnable = true,
      serviceValid = true,
      serviceTag = 17,
      serviceData = BigInt("8877665544332211", 16),
      templateEnable = true,
      templateValid = true,
      templateTag = 23,
      templateData = 0x3333)

    assert(result.writeValid)
    assert(result.writeTag == 17)
    assert(result.writeData == BigInt("8877665544332211", 16))
    assert(result.selectedService)
    assert(!result.selectedTemplate)
    assert(result.templateBlockedByService)
  }

  test("Chisel priority keeps service behind replay and ahead of template") {
    simulate(new ReducedScalarWritebackArbiter()) { dut =>
      dut.io.executeValid.poke(false.B)
      dut.io.executeTag.poke(5.U)
      dut.io.executeData.poke(0x1111.U)
      dut.io.replayEnable.poke(true.B)
      dut.io.replayValid.poke(true.B)
      dut.io.replayTag.poke(42.U)
      dut.io.replayData.poke(0x2222.U)
      dut.io.serviceEnable.poke(true.B)
      dut.io.serviceValid.poke(true.B)
      dut.io.serviceTag.poke(17.U)
      dut.io.serviceData.poke(BigInt("8877665544332211", 16).U)
      dut.io.templateEnable.poke(true.B)
      dut.io.templateValid.poke(true.B)
      dut.io.templateTag.poke(23.U)
      dut.io.templateData.poke(0x3333.U)

      dut.io.writeValid.expect(true.B)
      dut.io.writeTag.expect(42.U)
      dut.io.selectedReplay.expect(true.B)
      dut.io.selectedService.expect(false.B)
      dut.io.serviceBlockedByReplay.expect(true.B)
      dut.io.templateBlockedByService.expect(false.B)

      dut.io.replayValid.poke(false.B)
      dut.io.writeValid.expect(true.B)
      dut.io.writeTag.expect(17.U)
      dut.io.writeData.expect(BigInt("8877665544332211", 16).U)
      dut.io.selectedService.expect(true.B)
      dut.io.selectedTemplate.expect(false.B)
      dut.io.templateBlockedByService.expect(true.B)

      dut.io.serviceValid.poke(false.B)
      dut.io.writeValid.expect(true.B)
      dut.io.writeTag.expect(23.U)
      dut.io.selectedTemplate.expect(true.B)
    }
  }

  test("selects template only after execute replay and service are idle") {
    val result = ReducedScalarWritebackArbiterReference(
      executeValid = false,
      executeTag = 5,
      executeData = 0x1111,
      replayEnable = true,
      replayValid = false,
      replayTag = 42,
      replayData = 0x2222,
      serviceEnable = true,
      serviceValid = false,
      serviceTag = 17,
      serviceData = 0x3333,
      templateEnable = true,
      templateValid = true,
      templateTag = 23,
      templateData = BigInt("abcdef", 16))

    assert(result.writeValid)
    assert(result.writeTag == 23)
    assert(result.writeData == BigInt("abcdef", 16))
    assert(!result.selectedService)
    assert(result.selectedTemplate)
  }

  test("Chisel ReducedScalarWritebackArbiter elaborates arbitration diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarWritebackArbiter)

    assert(sv.contains("module ReducedScalarWritebackArbiter"))
    assert(sv.contains("io_executeValid"))
    assert(sv.contains("io_replayEnable"))
    assert(sv.contains("io_selectedExecute"))
    assert(sv.contains("io_selectedReplay"))
    assert(sv.contains("io_selectedService"))
    assert(sv.contains("io_selectedTemplate"))
    assert(sv.contains("io_replayBlockedByExecute"))
    assert(sv.contains("io_serviceBlockedByReplay"))
    assert(sv.contains("io_templateBlockedByService"))
  }
}
