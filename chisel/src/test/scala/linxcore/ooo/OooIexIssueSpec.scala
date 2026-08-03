package linxcore.ooo

import linxcore.params.ParamProfiles
import linxcore.top.interface.{DispatchTxn, StoreDispatchTxn}
import org.scalatest.funsuite.AnyFunSuite

/** Structural contract retained beside the behavior-focused private ingress
  * suite.  Picker/retry/release behavior remains covered through the composed
  * IEX mechanism suites.
  */
class OooIexIssueSpec extends AnyFunSuite {
  test("canonical IQ ingress exposes only classed dispatch and recovery") {
    val io = new OooIexIssueIO(ParamProfiles.W4)
    assert(io.dispatch.aluDispatch.length == 2)
    assert(io.dispatch.bruDispatch.length == 1)
    assert(io.dispatch.aguDispatch.length == 2)
    assert(io.dispatch.storeDispatch.length == 2)
    assert(io.dispatch.systemDispatch.length == 1)
    assert(io.dispatch.cmdDispatch.length == 1)
    assert(io.dispatch.aluDispatch.head.bits.isInstanceOf[DispatchTxn])
    assert(io.dispatch.storeDispatch.head.bits.isInstanceOf[StoreDispatchTxn])
    assert(!io.elements.contains("s1"))
    assert(!io.elements.contains("dispatchReleases"))
    assert(!io.elements.contains("ptagRecycle"))
  }
}
