package linxcore.common

import org.scalatest.funsuite.AnyFunSuite

class CoreParamsInterfaceClosureSpec extends AnyFunSuite {
  test("scalar backend exposes a validated GPR MapQ depth") {
    val backend = ScalarBackendParams(gprPhysRegs = 128, gprMapQDepth = 256)

    assert(backend.gprMapQDepth == 256)
    assertThrows[IllegalArgumentException] {
      ScalarBackendParams(gprPhysRegs = 128, gprMapQDepth = 64)
    }
    assertThrows[IllegalArgumentException] {
      ScalarBackendParams(gprPhysRegs = 128, gprMapQDepth = 192)
    }
  }

  test("renamed uops carry FRET stack validity sidebands") {
    val renamed = new RenamedUop(InterfaceParams())

    assert(renamed.fretStkContextValid.getWidth == 1)
    assert(renamed.fretStkConditionValid.getWidth == 1)
    assert(renamed.fretStkFallbackTargetValid.getWidth == 1)
  }
}
