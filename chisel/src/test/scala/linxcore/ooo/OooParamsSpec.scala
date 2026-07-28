package linxcore.ooo

import org.scalatest.funsuite.AnyFunSuite

class OooParamsSpec extends AnyFunSuite {
  test("defaults keep fetch-independent OOO widths and native identity domains") {
    val p = OooParams()

    assert(p.stidCount == 4)
    assert(p.instructionDecodeWidth == 4)
    assert(p.decodedUopWidth == 8)
    assert(p.renameWidth == 8)
    assert(p.dispatchWidth == 8)
    assert(p.retireGroupWidth == 4)
    assert(p.ridSlotWidth == 6)
    assert(p.robBankCount == 8)
    assert(p.robBankCountEffective == 8)
    assert(p.robSubbankCount == 2)
    assert(p.robSubbankCountEffective == 2)
    assert(p.robRowsPerSubbank == 4)
    assert(p.robRecoveryScanGroupsPerCycle == 8)
    assert(p.robRecoveryScanGroupsPerCycleEffective == 8)
    assert(p.robRecoveryScanCycles == 8)
    assert(p.nativeBidWidth == 8)
    assert(p.pArchRegs == 24)
    assert(p.pPhysRegs == 128)
    assert(p.pTagWidth == 7)
    assert(p.pTagBanks == 2)
    assert(p.pTagStagingDepthPerBank == 8)
    assert(p.pTagAllocationWidth == 16)
    assert(p.pTagReturnWidth == 8)
    assert(p.pMapQDepthPerStid == 256)
    assert(p.pMapQSubbankCount == 2)
    assert(p.pMapQSubbankIndexBits == 1)
    assert(p.pMapQRowsPerSubbank == 128)
    assert(p.tuRetireSourceDepthPerStid == 512)
    assert(p.tuRetireSourceIndexWidth == 9)
    assert(p.tuRelationDepthPerStid == 8)
    assert(p.tuRelationReleaseThreshold == 4)
    assert(p.maxCommitTURetireSources == 32)
    assert(p.pcBufferEntries == 64)
    assert(p.pcBankCount == 4)
    assert(p.pcRowsPerBank == 4)
    assert(p.pcBankSelectionBits == 2)
    assert(p.pcBufferIndexWidth == 6)
    assert(p.pcEntriesPerStid == 16)
    assert(p.pcPartitionIndexWidth == 4)
    assert(p.pcPartitionCountWidth == 5)
    assert(p.pcOffsetWidth == 7)
    assert(p.iqFreeSelectLeafEntries == 4)
    assert(p.iqFreeSelectLeafEntriesEffective == 4)
    assert(p.iqFreeSelectGroupCount == 8)
    assert(p.iexWakeupPorts == 8)
  }

  test("instruction decode widths 2 4 and 6 are independent elaboration points") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(instructionDecodeWidth = width)
      assert(p.instructionDecodeWidth == width)
      assert(p.decodedUopWidth >= width)
      assert(p.maxInstPerRobGroup == 4)
      assert((BigInt(1) << p.destinationDemandWidth) - 1 >=
        p.decodedUopWidth * p.maxDestinationOperands)
      assert((BigInt(1) << p.dispatchDemandWidth) - 1 >=
        p.decodedUopWidth * p.maxDispatchWritesPerInstruction)
      assert((BigInt(1) << p.memoryDemandWidth) - 1 >=
        p.decodedUopWidth * p.maxMemoryRequestsPerInstruction)
    }
    val minimumLocalNamespaces = OooParams(tPhysRegs = 2, uPhysRegs = 2)
    assert(minimumLocalNamespaces.tPhysRegs == 2)
    assert(minimumLocalNamespaces.uPhysRegs == 2)
    val smallRob = OooParams(robGroupsPerStid = 4)
    assert(smallRob.robBankCountEffective == 4)
    assert(smallRob.robSubbankCountEffective == 1)
    assert(smallRob.robRowsPerSubbank == 1)
    assert(smallRob.robRecoveryScanGroupsPerCycleEffective == 4)
    assert(smallRob.robRecoveryScanCycles == 1)
  }

  test("unsupported widths and underprovisioned four-thread PTag files fail closed") {
    assertThrows[IllegalArgumentException](OooParams(instructionDecodeWidth = 3))
    assertThrows[IllegalArgumentException](OooParams(pPhysRegs = 64))
    assertThrows[IllegalArgumentException](OooParams(pTagStagingDepthPerBank = 7))
    assertThrows[IllegalArgumentException](OooParams(pTagBanks = 64))
    assertThrows[IllegalArgumentException](OooParams(
      tuRetireSourceDepthPerStid = 256))
    assertThrows[IllegalArgumentException](OooParams(
      tuRelationDepthPerStid = 3))
    assertThrows[IllegalArgumentException](OooParams(
      tuRelationReleaseThreshold = 8))
    assertThrows[IllegalArgumentException](OooParams(tPhysRegs = 1))
    assertThrows[IllegalArgumentException](OooParams(uPhysRegs = 1))
    assertThrows[IllegalArgumentException](OooParams(iexWakeupPorts = 0))
    assertThrows[IllegalArgumentException](OooParams(
      iqFreeSelectLeafEntries = 3))
    assertThrows[IllegalArgumentException](OooParams(
      iqFreeSelectLeafEntries = 16))
    assertThrows[IllegalArgumentException](OooParams(
      iqEntriesPerBank = 128, iqFreeSelectLeafEntries = 8))
    assertThrows[IllegalArgumentException](OooParams(
      pMapQSubbankCount = 3))
    assertThrows[IllegalArgumentException](OooParams(
      pMapQDepthPerStid = 4, pMapQSubbankCount = 8))
    assertThrows[IllegalArgumentException](OooParams(robBankCount = 3))
    assertThrows[IllegalArgumentException](OooParams(robSubbankCount = 3))
    assertThrows[IllegalArgumentException](OooParams(
      robRecoveryScanGroupsPerCycle = 3))
    assertThrows[IllegalArgumentException](OooParams(
      robRecoveryScanGroupsPerCycle = 16))
    assertThrows[IllegalArgumentException](OooParams(
      instructionDecodeWidth = 6, robBankCount = 4))
    assertThrows[IllegalArgumentException](OooParams(
      retireGroupWidth = 8, robBankCount = 4))
    assertThrows[IllegalArgumentException](OooParams(pcBufferEntries = 32, stidCount = 3))
    assertThrows[IllegalArgumentException](OooParams(pcBufferEntries = 8, stidCount = 8))
    assertThrows[IllegalArgumentException](OooParams(pcBankCount = 3))
    assertThrows[IllegalArgumentException](OooParams(pcBankCount = 32))
    assertThrows[IllegalArgumentException](OooParams(pcBankCount = 2))
    assertThrows[IllegalArgumentException](OooParams(
      pcBankCount = 4, retireGroupWidth = 6))
  }
}
