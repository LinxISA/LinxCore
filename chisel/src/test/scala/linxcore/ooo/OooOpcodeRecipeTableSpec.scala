package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class OooOpcodeRecipeProbeIO(val p: OooParams = OooParams()) extends Bundle {
  val insn = Input(UInt(p.instructionWidth.W))
  val lenBytes = Input(UInt(p.instructionLengthWidth.W))
  val meta = Output(new OooOpcodeRecipeMeta(p))
}

class OooOpcodeRecipeProbe(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooOpcodeRecipeProbeIO(p))
  io.meta := OooOpcodeRecipeTable.decode(p, io.insn, io.lenBytes)
}

class OooOpcodeRecipeTableSpec extends AnyFunSuite with ChiselSim {
  private def rule(symbol: String): OooOpcodeRecipeTable.Rule =
    OooOpcodeRecipeTable.Rules.find(_.symbol == symbol).getOrElse(
      fail(s"missing generated recipe rule for $symbol"))

  test("generated recipes classify every encoded catalog form and fail closed explicitly") {
    assert(OooOpcodeRecipeTable.CatalogRecordCount == 689)
    assert(OooOpcodeRecipeTable.DecodeRuleCount == 687)
    assert(OooOpcodeRecipeTable.OpcodeCount == 658)
    assert(OooOpcodeRecipeTable.Rules.size == OooOpcodeRecipeTable.DecodeRuleCount)
    assert(OooOpcodeRecipeTable.Rules.forall { entry =>
      entry.disposition >= OooOpcodeDisposition.Dispatch &&
      entry.disposition <= OooOpcodeDisposition.Illegal
    })

    assert(rule("OP_ADD").dispatchClass == OooDispatchClass.Alu)
    assert(rule("OP_ADD").dispatchCapabilities(
      OooDispatchClass.Alu - 1) ==
      OooIexDomainCapability.mask(OooIexDomainCapability.SimpleAlu))
    assert(rule("OP_DIV").dispatchCapabilities(
      OooDispatchClass.Alu - 1) ==
      OooIexDomainCapability.mask(OooIexDomainCapability.MultiCycleAlu))
    assert(rule("OP_LD").dispatchCapabilities(
      OooDispatchClass.Agu - 1) ==
      OooIexDomainCapability.mask(OooIexDomainCapability.LoadAddress))
    assert(rule("OP_SD").lateSplitKind == OooLateSplitKind.StoreAddressData)
    assert(rule("OP_SD").pSourceCount == 3)
    assert(rule("OP_SD").dispatchCapabilities(
      OooDispatchClass.Agu - 1) ==
      OooIexDomainCapability.mask(OooIexDomainCapability.StoreAddress))
    assert(rule("OP_SD").dispatchCapabilities(
      OooDispatchClass.Std - 1) ==
      OooIexDomainCapability.mask(OooIexDomainCapability.StoreData))
    assert(rule("OP_SDI").pSourceCount == 2)
    assert(rule("OP_C_BSTOP").fastResolveClass == OooFastResolveClass.BoundaryMetadata)
    assert(rule("OP_FENTRY").disposition == OooOpcodeDisposition.Ctu)
    assert(rule("OP_MCOPY").disposition == OooOpcodeDisposition.Illegal)
    assert(rule("OP_LR_W").recipeKind == OooOpcodeRecipeKind.AtomicUnresolved)
    assert(rule("OP_HL_LDIP").recipeKind == OooOpcodeRecipeKind.PairLoad)
    assert(rule("OP_HL_LDIP").memoryRequestCount == 2)
    assert(rule("OP_HL_SDIP").recipeKind == OooOpcodeRecipeKind.PairStore)
    assert(rule("OP_HL_SDIP").pSourceCount == 3)
    assert(rule("OP_HL_SDP").pSourceCount == 4)
    assert(rule("OP_HL_SDIP").dispatchDemand(OooDispatchClass.Agu - 1) == 1)
    assert(rule("OP_HL_SDIP").dispatchDemand(OooDispatchClass.Std - 1) == 1)
    assert(rule("OP_START_CALL_32").fastResolveClass == OooFastResolveClass.ControlValueProducer)
    assert(rule("OP_START_CALL_48").fastResolveClass == OooFastResolveClass.ControlValueProducer)
    assert(rule("OP_SETRET").fastResolveClass == OooFastResolveClass.ImmediateProducer)
    assert(rule("OP_C_SETRET").fastResolveClass == OooFastResolveClass.ImmediateProducer)
    assert(rule("OP_HL_SETRET").fastResolveClass == OooFastResolveClass.ImmediateProducer)
    assert(rule("OP_EBREAK").fastResolveClass == OooFastResolveClass.PreciseTrapRecord)
    assert(rule("OP_ACRC").dispatchClass == OooDispatchClass.Sys)
    assert(rule("OP_BSTART_TMA").recipeKind == OooOpcodeRecipeKind.EngineCmd)
    assert(rule("OP_ADD").pcReadRequired == false)
    assert(rule("OP_B_Z").pcReadRequired)
    assert(rule("OP_JR").pcReadRequired)
    assert(rule("OP_ADDTPC").pcReadRequired)
    assert(rule("OP_LD_PCR").pcReadRequired)
    assert(rule("OP_SD_PCR").pcReadRequired)
    assert(rule("OP_SD_PCR").pcReadClass == OooDispatchClass.Agu)
    assert(rule("OP_HL_LD_PCR").pcReadRequired)
    assert(rule("OP_HL_SD_PCR").pcReadClass == OooDispatchClass.Alu)
    assert(rule("OP_B_Z").pcReadClass == OooDispatchClass.Bru)
    assert(!rule("OP_LD").pcReadRequired)

    val fastRules = OooOpcodeRecipeTable.Rules.filter(
      _.disposition == OooOpcodeDisposition.FastResolve)
    assert(fastRules.nonEmpty)
    assert(fastRules.forall { entry =>
      entry.fastResolveClass >= OooFastResolveClass.BoundaryMetadata &&
      entry.fastResolveClass <= OooFastResolveClass.NoEffect &&
      entry.dispatchWrites == 0 && entry.memoryRequestCount == 0
    })
    assert(OooOpcodeRecipeTable.Rules.forall { entry =>
      entry.disposition match {
        case OooOpcodeDisposition.FastResolve =>
          entry.fastResolveClass != OooFastResolveClass.None
        case OooOpcodeDisposition.Dispatch |
            OooOpcodeDisposition.Ctu |
            OooOpcodeDisposition.Illegal =>
          entry.fastResolveClass == OooFastResolveClass.None
        case _ => false
      }
    })
  }

  test("raw recipe decode covers 16 32 48 and 64 bit containers") {
    val p = OooParams()
    simulate(new OooOpcodeRecipeProbe(p)) { dut =>
      Seq(
        "OP_C_BSTOP",
        "OP_C_SETRET",
        "OP_ADD",
        "OP_HL_LDIP",
        "OP_V_ADD").foreach { symbol =>
        val entry = rule(symbol)
        dut.io.insn.poke(entry.value.U)
        dut.io.lenBytes.poke(entry.lenBytes.U)
        dut.clock.step()
        dut.io.meta.valid.expect(true.B, s"$symbol should decode")
        dut.io.meta.opcode.expect(entry.opcode.U)
        dut.io.meta.disposition.expect(entry.disposition.U)
        dut.io.meta.recipeKind.expect(entry.recipeKind.U)
        dut.io.meta.pcReadRequired.expect(entry.pcReadRequired.B)
        for (classIndex <- 0 until p.iqClassCount) {
          dut.io.meta.dispatchCapabilities(classIndex)
            .expect(entry.dispatchCapabilities(classIndex).U)
        }
      }

      dut.io.insn.poke("hffffffffffffffff".U)
      dut.io.lenBytes.poke(4.U)
      dut.clock.step()
      dut.io.meta.valid.expect(false.B)
      dut.io.meta.disposition.expect(OooOpcodeDisposition.Illegal.U)
    }
  }

  test("hardware priority decode agrees with every generated encoded rule stimulus") {
    val p = OooParams()
    simulate(new OooOpcodeRecipeProbe(p)) { dut =>
      OooOpcodeRecipeTable.Rules.foreach { stimulus =>
        val expected = OooOpcodeRecipeTable.Rules.find { candidate =>
          candidate.lenBytes == stimulus.lenBytes &&
          (stimulus.value & candidate.mask) == candidate.value
        }.getOrElse(fail(s"no generated priority match for ${stimulus.symbol}"))
        dut.io.insn.poke(stimulus.value.U)
        dut.io.lenBytes.poke(stimulus.lenBytes.U)
        dut.clock.step()
        dut.io.meta.valid.expect(true.B, s"${stimulus.symbol} should match")
        dut.io.meta.opcode.expect(expected.opcode.U, s"${stimulus.symbol} priority mismatch")
        dut.io.meta.recipeKind.expect(expected.recipeKind.U)
        dut.io.meta.disposition.expect(expected.disposition.U)
      }
    }
  }

  test("recipe hardware elaborates with production decode widths 2 4 and 6") {
    Seq(2, 4, 6).foreach { width =>
      val sv = ChiselStage.emitSystemVerilog(
        new OooOpcodeRecipeProbe(OooParams(instructionDecodeWidth = width)))
      assert(sv.contains("module OooOpcodeRecipeProbe"))
      assert(sv.contains("io_meta_disposition"))
      assert(sv.contains("io_meta_sideEffectOwner"))
      assert(sv.contains("io_meta_pcReadRequired"))
      assert(sv.contains("io_meta_pcReadClass"))
      assert(sv.contains("io_meta_dispatchCapabilities"))
    }
  }
}
