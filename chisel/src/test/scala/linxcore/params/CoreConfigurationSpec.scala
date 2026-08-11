package linxcore.params

import org.scalatest.funsuite.AnyFunSuite

class CoreConfigurationSpec extends AnyFunSuite {
  private val supportedWidths = Seq(2, 4, 6, 8)

  test("W2 W4 W6 and W8 profiles keep every principal box width coherent") {
    supportedWidths.foreach { width =>
      val p = ParamProfiles.forWidth(width)

      ParamChecks.validate(p)
      assert(p.widths == WidthParams.uniform(width))
      assert(p.ifu.fetchWidth == width)
      assert(p.ifu.ctuTransferWidth == width)
      assert(p.ctu.inputWidth == width)
      assert(p.ctu.outputWidth == width)
      assert(p.ooo.decodeWidth == width)
      assert(p.ooo.renameWidth == width)
      assert(p.ooo.dispatchWidth == width)
      assert(p.ooo.d3PrefixWidth == width)
      assert(p.iex.issueWidth == width)
    }
  }

  test("W4 is the default profile and preserves the requested physical topology") {
    val p = ParamProfiles.Default

    assert(p == ParamProfiles.W4)
    assert(CoreParams() == ParamProfiles.W4)
    assert(p.iex.aluPipes == 2)
    assert(p.iex.bruPipes == 1)
    assert(p.iex.aguPipes == 2)
    assert(p.iex.stdPipes == 2)
    assert(p.iex.systemMulticycleQueues == 1)
    assert(p.iex.cmdIssueQueues == 1)
    assert(p.lsu.loadPipes == 2)
    assert(p.lsu.storePipes == 2)
    assert(p.ooo.gprTagGenerationWidth == 16)
    assert(p.ooo.localSeqGenerationWidth == 16)
  }

  test("unsupported profile width fails before a configuration is published") {
    val error = intercept[IllegalArgumentException] {
      ParamProfiles.forWidth(3)
    }

    assert(error.getMessage.contains("supported widths are 2, 4, 6, and 8"))
  }

  test("CTU cannot publish a wider prefix than IFU can transfer") {
    val base = ParamProfiles.W4
    val error = intercept[IllegalArgumentException] {
      base.copy(
        widths = base.widths.copy(fetchWidth = 2, ctuOutputWidth = 4),
        ifu = base.ifu.copy(fetchWidth = 2, ctuTransferWidth = 4))
    }

    assert(error.getMessage.contains("IFU fetch width must cover CTU output"))
  }

  test("OOO dispatch covers the complete accepted D3 prefix") {
    val base = ParamProfiles.W4
    val error = intercept[IllegalArgumentException] {
      base.copy(
        widths = base.widths.copy(dispatchWidth = 2),
        ooo = base.ooo.copy(dispatchWidth = 2, d3PrefixWidth = 4))
    }

    assert(error.getMessage.contains("dispatch width must cover the D3 prefix"))
  }

  test("LSU requires at least one load pipe and one store pipe") {
    val base = ParamProfiles.W4

    val loadError = intercept[IllegalArgumentException] {
      base.copy(lsu = base.lsu.copy(loadPipes = 0))
    }
    val storeError = intercept[IllegalArgumentException] {
      base.copy(lsu = base.lsu.copy(storePipes = 0))
    }

    assert(loadError.getMessage.contains("load pipe count must be positive"))
    assert(storeError.getMessage.contains("store pipe count must be positive"))
  }

  test("STQ covers one atomic STD reservation batch and pipe counts match") {
    val base = ParamProfiles.W4

    val capacityError = intercept[IllegalArgumentException] {
      base.copy(lsu = base.lsu.copy(storeQueueEntries = 2))
    }
    assert(capacityError.getMessage.contains(
      "store queue must cover one atomic STD reservation batch"))

    val couplingError = intercept[IllegalArgumentException] {
      base.copy(iex = base.iex.copy(stdPipes = 1))
    }
    assert(couplingError.getMessage.contains(
      "STD pipe count must match the LSU store pipe count"))
  }

  test("lower-memory ledgers cover retained store and SCB capacities") {
    val base = ParamProfiles.W4

    val storeError = intercept[IllegalArgumentException] {
      ParamChecks.validate(base.copy(lsu = base.lsu.copy(
        lowerMemoryTransactionsPerLane = 4,
        storeCommitQueueEntries = 5,
        scbEntries = 4,
        loadMissQueueEntries = 4)))
    }
    assert(storeError.getMessage.contains("store commit queue"))

    val scbError = intercept[IllegalArgumentException] {
      ParamChecks.validate(base.copy(lsu = base.lsu.copy(
        lowerMemoryTransactionsPerLane = 4,
        storeCommitQueueEntries = 4,
        scbEntries = 5,
        loadMissQueueEntries = 4)))
    }
    assert(scbError.getMessage.contains("SCB"))
  }

  test("BROB entries per STID must preserve a nonzero BID width") {
    val base = ParamProfiles.W4

    val singletonError = intercept[IllegalArgumentException] {
      base.copy(ooo = base.ooo.copy(brobEntriesPerStid = 1))
    }

    assert(singletonError.getMessage.contains(
      "BROB entries per STID must be a power of two and at least 2"))
    assert(base.copy(ooo = base.ooo.copy(brobEntriesPerStid = 4)).nativeBidWidth ==
      base.nativeBidWidth)
    assert(base.nativeBidWidth == 8)
  }

  test("module-local widths cannot drift from the central width contract") {
    val base = ParamProfiles.W6
    val error = intercept[IllegalArgumentException] {
      base.copy(iex = base.iex.copy(issueWidth = 4))
    }

    assert(error.getMessage.contains("IEX issue width must match WidthParams"))
  }

  test("rename generation widths are centrally configured and fail closed") {
    val base = ParamProfiles.W4
    val custom = base.copy(ooo = base.ooo.copy(
      gprTagGenerationWidth = 12,
      localSeqGenerationWidth = 10,
      gprPhysRegs = 128,
      gprMapQDepthPerStid = 16))

    assert(custom.ooo.gprTagGenerationWidth == 12)
    assert(custom.ooo.localSeqGenerationWidth == 10)
    assert(custom.ooo.gprPhysRegs == 128)
    assert(custom.ooo.gprMapQDepthPerStid == 16)
    assertThrows[IllegalArgumentException] {
      base.copy(ooo = base.ooo.copy(gprTagGenerationWidth = 0))
    }
    assertThrows[IllegalArgumentException] {
      base.copy(ooo = base.ooo.copy(localSeqGenerationWidth = 0))
    }
    assertThrows[IllegalArgumentException] {
      base.copy(ooo = base.ooo.copy(gprMapQDepthPerStid = 4))
    }
  }

  test("rename physical and MapQ capacities independently cover one prefix") {
    val wide = ParamProfiles.W8

    assertThrows[IllegalArgumentException] {
      wide.copy(ooo = wide.ooo.copy(stidCount = 1, gprPhysRegs = 32))
    }
    assertThrows[IllegalArgumentException] {
      wide.copy(ooo = wide.ooo.copy(tPhysRegs = 8))
    }
    assertThrows[IllegalArgumentException] {
      wide.copy(ooo = wide.ooo.copy(uPhysRegs = 8))
    }
    assertThrows[IllegalArgumentException] {
      wide.copy(ooo = wide.ooo.copy(tuMapQDepthPerStid = 8))
    }
  }

  test("identity namespaces fail closed when they do not cover physical storage") {
    val base = ParamProfiles.W4

    assertThrows[IllegalArgumentException] {
      base.copy(ooo = base.ooo.copy(
        robIdentityGroupsPerStid = 4,
        robGroupsPerStid = 8))
    }
    assertThrows[IllegalArgumentException] {
      base.copy(ooo = base.ooo.copy(
        robIdentityMembersPerGroup = 2,
        maxInstructionsPerRobGroup = 4))
    }
    assertThrows[IllegalArgumentException] {
      base.copy(ooo = base.ooo.copy(
        uopIdentityEntriesPerInstruction = 8,
        maxUopsPerInstruction = 12))
    }
    assertThrows[IllegalArgumentException] {
      base.copy(ooo = base.ooo.copy(
        gprTagIdentityEntries = 32,
        gprPhysRegs = 64))
    }
  }

  test("DTU trace packets cover a complete IFU transfer prefix") {
    val base = ParamProfiles.W8
    val error = intercept[IllegalArgumentException] {
      base.copy(dtu = base.dtu.copy(traceWidth = 4))
    }

    assert(error.getMessage.contains("DTU trace width must cover the IFU transfer"))
  }

  test("legacy parameter records convert to the central profile without state") {
    val legacyCore = linxcore.common.CoreParams(
      robEntries = 64,
      commitWidth = 2,
      lsidWidth = 40)
    val convertedCore = legacyCore.toMainline(profileWidth = 2)
    val legacyOoo = linxcore.ooo.OooParams(
      stidCount = 2,
      instructionDecodeWidth = 2,
      decodedUopWidth = 4,
      renameWidth = 4,
      dispatchWidth = 4,
      pTagGenerationWidth = 13,
      localSeqGenerationWidth = 11)
    val convertedOoo = legacyOoo.toMainline

    assert(convertedCore.widths.decodeWidth == 2)
    assert(convertedCore.ooo.robCapacityPerStid == 64)
    assert(convertedCore.widths.retireWidth == 2)
    assert(convertedCore.lsidWidth == 40)
    assert(convertedOoo.stidCount == 2)
    assert(convertedOoo.decodeWidth == 2)
    assert(convertedOoo.renameWidth == 4)
    assert(convertedOoo.dispatchWidth == 4)
    assert(convertedOoo.gprTagGenerationWidth == 13)
    assert(convertedOoo.localSeqGenerationWidth == 11)
  }

  test("central parameters construct value-only legacy adapters") {
    val mainline = ParamProfiles.W2
    val legacyCore = linxcore.common.CoreParams.fromMainline(mainline)
    val legacyOoo = linxcore.ooo.OooParams.fromMainline(mainline.ooo)
    val legacyWideOoo =
      linxcore.ooo.OooParams.fromMainline(ParamProfiles.W8.ooo)

    assert(legacyCore.robEntries == mainline.ooo.robCapacityPerStid)
    assert(legacyCore.commitWidth == mainline.widths.retireWidth)
    assert(legacyCore.scalarLsu.liqEntries == mainline.lsu.loadQueueEntries)
    assert(legacyCore.scalarLsu.stqEntries == mainline.lsu.storeQueueEntries)
    assert(legacyOoo.instructionDecodeWidth == mainline.ooo.decodeWidth)
    assert(legacyOoo.renameWidth == mainline.ooo.renameWidth)
    assert(legacyOoo.dispatchWidth == mainline.ooo.dispatchWidth)
    assert(legacyOoo.pTagGenerationWidth ==
      mainline.ooo.gprTagGenerationWidth)
    assert(legacyOoo.localSeqGenerationWidth ==
      mainline.ooo.localSeqGenerationWidth)
    assert(legacyWideOoo.instructionDecodeWidth == 8)
  }
}
