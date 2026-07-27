package linxcore.ooo

import org.scalatest.funsuite.AnyFunSuite

class OooParamsSpec extends AnyFunSuite {
  test("production defaults keep fetch-independent OOO widths and native identity domains") {
    val p = OooParams()

    assert(p.stidCount == 4)
    assert(p.instructionDecodeWidth == 4)
    assert(p.decodedUopWidth == 8)
    assert(p.renameWidth == 8)
    assert(p.dispatchWidth == 8)
    assert(p.retireGroupWidth == 4)
    assert(p.ridSlotWidth == 6)
    assert(p.nativeBidWidth == 8)
    assert(p.pArchRegs == 24)
    assert(p.pPhysRegs == 128)
    assert(p.pTagWidth == 7)
    assert(p.pcBufferEntries == 64)
    assert(p.pcBufferIndexWidth == 6)
    assert(p.pcOffsetWidth == 7)
  }

  test("instruction decode widths 2 4 and 6 are independent elaboration points") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(instructionDecodeWidth = width)
      assert(p.instructionDecodeWidth == width)
      assert(p.decodedUopWidth >= width)
      assert(p.maxInstPerRobGroup == 4)
      assert((BigInt(1) << p.destinationDemandWidth) - 1 >= width * p.maxDestinationOperands)
      assert((BigInt(1) << p.dispatchDemandWidth) - 1 >= width * p.maxDispatchWritesPerInstruction)
      assert((BigInt(1) << p.memoryDemandWidth) - 1 >= width * p.maxMemoryRequestsPerInstruction)
    }
  }

  test("unsupported widths and underprovisioned four-thread PTag files fail closed") {
    assertThrows[IllegalArgumentException](OooParams(instructionDecodeWidth = 3))
    assertThrows[IllegalArgumentException](OooParams(pPhysRegs = 64))
  }
}
