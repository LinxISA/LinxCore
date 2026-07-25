package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.{CoreParams, ScalarLsuParams}
import org.scalatest.funsuite.AnyFunSuite

object ScalarLrScReservationOwnerReference {
  private val Mask64 = (BigInt(1) << 64) - 1

  final case class Result(
      lrData: BigInt,
      reservationValid: Boolean,
      scSuccess: Boolean,
      scStatus: BigInt,
      scStoreValid: Boolean)

  final class Model(stidCount: Int) {
    private val valid = Array.fill(stidCount)(false)
    private val line = Array.fill(stidCount)(BigInt(0))

    private def inRange(stid: Int): Boolean = stid >= 0 && stid < stidCount
    private def supported(size: Int): Boolean = Set(1, 2, 4, 8).contains(size)
    private def extend(raw: BigInt, size: Int): BigInt = {
      val masked = raw & Mask64
      size match {
        case 1 => masked & 0xff
        case 2 => masked & 0xffff
        case 4 =>
          val word = masked & 0xffffffffL
          if (((word >> 31) & 1) == 1) (word | (Mask64 ^ 0xffffffffL)) & Mask64 else word
        case 8 => masked
        case _ => BigInt(0)
      }
    }

    def lrComplete(stid: Int, lineAddr: BigInt, size: Int, raw: BigInt, accepted: Boolean = true): BigInt = {
      val data = extend(raw, size)
      if (accepted && inRange(stid) && supported(size)) {
        valid(stid) = true
        line(stid) = lineAddr
      }
      data
    }

    def committedStore(stid: Int, lineAddr: BigInt): Unit = {
      if (inRange(stid) && valid(stid) && line(stid) == lineAddr) {
        valid(stid) = false
      }
    }

    def flush(stid: Option[Int] = None): Unit = {
      stid match {
        case Some(value) if inRange(value) => valid(value) = false
        case None => valid.indices.foreach(valid(_) = false)
        case _ =>
      }
    }

    def sc(stid: Int, lineAddr: BigInt, size: Int, commitReady: Boolean = true): Result = {
      val hit = inRange(stid) && valid(stid) && line(stid) == lineAddr
      if (commitReady && inRange(stid) && supported(size)) {
        valid(stid) = false
      }
      Result(0, inRange(stid) && valid(stid), hit, if (hit) 0 else 1, commitReady && hit)
    }

    def reservationValid(stid: Int): Boolean = inRange(stid) && valid(stid)
  }
}

class ScalarLrScReservationOwnerSpec extends AnyFunSuite with ChiselSim {
  import ScalarLrScReservationOwnerReference._

  private def params: CoreParams =
    CoreParams(
      robEntries = 32,
      scalarLsu = ScalarLsuParams(
        stqEntries = 8,
        commitQueueEntries = 8,
        commitIssueWidth = 1,
        scbEntries = 4,
        liqEntries = 8,
        loadMissQueueEntries = 4,
        resolveQueueEntries = 4,
        stidCount = 2))

  test("reference sign-extends LR.W and uses a 64-byte line reservation") {
    val model = new Model(stidCount = 2)
    val data = model.lrComplete(stid = 0, lineAddr = 0x1000, size = 4, raw = 0x80000000L)

    assert(data == BigInt("ffffffff80000000", 16))
    assert(model.reservationValid(0))
    val sc = model.sc(stid = 0, lineAddr = 0x1000, size = 4)
    assert(sc.scSuccess)
    assert(sc.scStatus == 0)
    assert(sc.scStoreValid)
    assert(!model.reservationValid(0))
  }

  test("reference fails SC without reservation and preserves different STID reservations") {
    val model = new Model(stidCount = 2)
    model.lrComplete(stid = 1, lineAddr = 0x2000, size = 4, raw = 0x7fffffff)

    val sc = model.sc(stid = 0, lineAddr = 0x2000, size = 4)
    assert(!sc.scSuccess)
    assert(sc.scStatus == 1)
    assert(!sc.scStoreValid)
    assert(model.reservationValid(1))
  }

  test("reference clears same-STID same-line reservations on committed stores only") {
    val model = new Model(stidCount = 2)
    model.lrComplete(stid = 0, lineAddr = 0x3000, size = 4, raw = 1)

    model.committedStore(stid = 1, lineAddr = 0x3000)
    assert(model.reservationValid(0))
    model.committedStore(stid = 0, lineAddr = 0x3040)
    assert(model.reservationValid(0))
    model.committedStore(stid = 0, lineAddr = 0x3000)
    assert(!model.reservationValid(0))
  }

  private def clearInputs(dut: ScalarLrScReservationOwner): Unit = {
    dut.io.enable.poke(true.B)
    dut.io.contextInvalidate.poke(false.B)
    dut.io.flushAll.poke(false.B)
    dut.io.flushValid.poke(false.B)
    dut.io.flushStid.poke(0.U)
    dut.io.flushIdentityValid.poke(false.B)
    pokeIdentity(dut.io.flushIdentity, 0)
    dut.io.lrCompleteValid.poke(false.B)
    dut.io.lrCompleteAccepted.poke(false.B)
    dut.io.lrStid.poke(0.U)
    dut.io.lrLineAddr.poke(0.U)
    dut.io.lrSize.poke(0.U)
    dut.io.lrRawData.poke(0.U)
    dut.io.lrIdentity.bid.poke(0.U)
    dut.io.lrIdentity.gid.valid.poke(false.B)
    dut.io.lrIdentity.gid.wrap.poke(false.B)
    dut.io.lrIdentity.gid.value.poke(0.U)
    dut.io.lrIdentity.rid.valid.poke(false.B)
    dut.io.lrIdentity.rid.wrap.poke(false.B)
    dut.io.lrIdentity.rid.value.poke(0.U)
    dut.io.lrIdentity.lsIdFull.poke(0.U)
    dut.io.scReqValid.poke(false.B)
    dut.io.scReqStid.poke(0.U)
    dut.io.scReqLineAddr.poke(0.U)
    dut.io.scReqSize.poke(0.U)
    dut.io.scReqData.poke(0.U)
    dut.io.scReqIdentity.bid.poke(0.U)
    dut.io.scReqIdentity.gid.valid.poke(false.B)
    dut.io.scReqIdentity.gid.wrap.poke(false.B)
    dut.io.scReqIdentity.gid.value.poke(0.U)
    dut.io.scReqIdentity.rid.valid.poke(false.B)
    dut.io.scReqIdentity.rid.wrap.poke(false.B)
    dut.io.scReqIdentity.rid.value.poke(0.U)
    dut.io.scReqIdentity.lsIdFull.poke(0.U)
    dut.io.scCommitReady.poke(false.B)
    dut.io.committedStoreInvalidateValid.poke(false.B)
    dut.io.committedStoreInvalidateStid.poke(0.U)
    dut.io.committedStoreInvalidateLineAddr.poke(0.U)
  }

  private def pokeIdentity(identity: ScalarLrScIdentity, seed: Int): Unit = {
    identity.bid.poke((seed & 3).U)
    identity.gid.valid.poke(true.B)
    identity.gid.wrap.poke((((seed >> 2) & 1) != 0).B)
    identity.gid.value.poke((seed & 31).U)
    identity.rid.valid.poke(true.B)
    identity.rid.wrap.poke((((seed >> 3) & 1) != 0).B)
    identity.rid.value.poke(((seed + 1) & 31).U)
    identity.lsIdFull.poke(seed.U)
  }

  private def lrComplete(
      dut: ScalarLrScReservationOwner,
      stid: Int,
      line: BigInt,
      raw: BigInt,
      identitySeed: Int = 1): Unit = {
    clearInputs(dut)
    dut.io.lrCompleteValid.poke(true.B)
    dut.io.lrCompleteAccepted.poke(true.B)
    dut.io.lrStid.poke(stid.U)
    dut.io.lrLineAddr.poke(line.U)
    dut.io.lrSize.poke(4.U)
    dut.io.lrRawData.poke(raw.U)
    pokeIdentity(dut.io.lrIdentity, identitySeed)
    dut.io.lrData.expect(BigInt("ffffffff80000000", 16).U)
    dut.clock.step()
  }

  private def acceptSc(
      dut: ScalarLrScReservationOwner,
      stid: Int,
      line: BigInt,
      data: BigInt = 0x12345678,
      identitySeed: Int = 2): Unit = {
    clearInputs(dut)
    dut.io.scReqValid.poke(true.B)
    dut.io.scReqStid.poke(stid.U)
    dut.io.scReqLineAddr.poke(line.U)
    dut.io.scReqSize.poke(4.U)
    dut.io.scReqData.poke(data.U)
    pokeIdentity(dut.io.scReqIdentity, identitySeed)
    dut.io.scReqReady.expect(true.B)
    dut.clock.step()
  }

  test("sim sets LR.W reservation, succeeds SC.W, clears on every SC attempt") {
    simulate(new ScalarLrScReservationOwner(params)) { dut =>
      clearInputs(dut)
      lrComplete(dut, stid = 0, line = 0x1000, raw = 0x80000000L)
      dut.io.reservationValidByStid(0).expect(true.B)

      acceptSc(dut, stid = 0, line = 0x1000)
      dut.io.scSuccess.expect(true.B)
      dut.io.scStatus.expect(0.U)
      dut.io.scStoreValid.expect(false.B)
      dut.io.scCommitReady.poke(false.B)
      dut.clock.step()
      dut.io.scStoreValid.expect(false.B)
      dut.io.reservationValidByStid(0).expect(true.B)

      dut.io.scCommitReady.poke(true.B)
      dut.io.scCompleteFire.expect(true.B)
      dut.io.scStoreValid.expect(true.B)
      dut.io.scStoreMask.expect("h0f".U)
      dut.clock.step()
      dut.io.reservationValidByStid(0).expect(false.B)

      acceptSc(dut, stid = 0, line = 0x1000)
      dut.io.scSuccess.expect(false.B)
      dut.io.scStatus.expect(1.U)
      dut.io.scStoreValid.expect(false.B)
      dut.io.scCommitReady.poke(true.B)
      dut.clock.step()
    }
  }

  test("sim committed stores invalidate only same-STID same-line reservations") {
    simulate(new ScalarLrScReservationOwner(params)) { dut =>
      lrComplete(dut, stid = 0, line = 0x2000, raw = 0x80000000L)
      dut.io.reservationValidByStid(0).expect(true.B)

      clearInputs(dut)
      dut.io.committedStoreInvalidateValid.poke(true.B)
      dut.io.committedStoreInvalidateStid.poke(1.U)
      dut.io.committedStoreInvalidateLineAddr.poke(0x2000.U)
      dut.clock.step()
      dut.io.reservationValidByStid(0).expect(true.B)

      dut.io.committedStoreInvalidateStid.poke(0.U)
      dut.io.committedStoreInvalidateLineAddr.poke(0x2040.U)
      dut.clock.step()
      dut.io.reservationValidByStid(0).expect(true.B)

      dut.io.committedStoreInvalidateLineAddr.poke(0x2000.U)
      dut.clock.step()
      dut.io.reservationValidByStid(0).expect(false.B)
    }
  }

  test("sim flush suppresses LR set and cancels resident SC without clearing an older reservation") {
    simulate(new ScalarLrScReservationOwner(params)) { dut =>
      clearInputs(dut)
      dut.io.lrCompleteValid.poke(true.B)
      dut.io.lrCompleteAccepted.poke(true.B)
      dut.io.lrStid.poke(0.U)
      dut.io.lrLineAddr.poke(0x3000.U)
      dut.io.lrSize.poke(4.U)
      dut.io.lrRawData.poke(0x80000000L.U)
      pokeIdentity(dut.io.lrIdentity, 7)
      dut.io.flushValid.poke(true.B)
      dut.io.flushStid.poke(0.U)
      dut.io.flushIdentityValid.poke(true.B)
      pokeIdentity(dut.io.flushIdentity, 7)
      dut.clock.step()
      dut.io.reservationValidByStid(0).expect(false.B)

      lrComplete(dut, stid = 0, line = 0x3000, raw = 0x80000000L, identitySeed = 8)
      acceptSc(dut, stid = 0, line = 0x3000, identitySeed = 9)
      clearInputs(dut)
      dut.io.flushValid.poke(true.B)
      dut.io.flushStid.poke(0.U)
      dut.io.flushIdentityValid.poke(true.B)
      pokeIdentity(dut.io.flushIdentity, 9)
      dut.clock.step()
      dut.io.scCompleteFire.expect(false.B)
      dut.io.reservationValidByStid(0).expect(true.B)
    }
  }

  test("sim precise flush does not suppress same-STID nonmatching LR or resident SC") {
    simulate(new ScalarLrScReservationOwner(params)) { dut =>
      clearInputs(dut)
      dut.io.lrCompleteValid.poke(true.B)
      dut.io.lrCompleteAccepted.poke(true.B)
      dut.io.lrStid.poke(0.U)
      dut.io.lrLineAddr.poke(0x3400.U)
      dut.io.lrSize.poke(4.U)
      dut.io.lrRawData.poke(0x80000000L.U)
      pokeIdentity(dut.io.lrIdentity, 10)
      dut.io.flushValid.poke(true.B)
      dut.io.flushStid.poke(0.U)
      dut.io.flushIdentityValid.poke(true.B)
      pokeIdentity(dut.io.flushIdentity, 11)
      dut.clock.step()
      dut.io.reservationValidByStid(0).expect(true.B)

      acceptSc(dut, stid = 0, line = 0x3400, identitySeed = 10)
      clearInputs(dut)
      dut.io.flushValid.poke(true.B)
      dut.io.flushStid.poke(0.U)
      dut.io.flushIdentityValid.poke(true.B)
      pokeIdentity(dut.io.flushIdentity, 11)
      dut.clock.step()

      clearInputs(dut)
      dut.io.scCommitReady.poke(true.B)
      dut.io.scCompleteFire.expect(true.B)
      dut.io.scSuccess.expect(true.B)
      dut.io.scStoreValid.expect(true.B)
      dut.clock.step()
    }
  }

  test("sim same-cycle committed-store invalidation forces SC failure") {
    simulate(new ScalarLrScReservationOwner(params)) { dut =>
      lrComplete(dut, stid = 0, line = 0x3800, raw = 0x80000000L)
      acceptSc(dut, stid = 0, line = 0x3800)

      clearInputs(dut)
      dut.io.scCommitReady.poke(true.B)
      dut.io.committedStoreInvalidateValid.poke(true.B)
      dut.io.committedStoreInvalidateStid.poke(0.U)
      dut.io.committedStoreInvalidateLineAddr.poke(0x3800.U)
      dut.io.scCompleteFire.expect(true.B)
      dut.io.scSuccess.expect(false.B)
      dut.io.scStatus.expect(1.U)
      dut.io.scStoreValid.expect(false.B)
      dut.clock.step()
      dut.io.reservationValidByStid(0).expect(false.B)
    }
  }

  test("sim backpressure holds resident SC and blocks a second request") {
    simulate(new ScalarLrScReservationOwner(params)) { dut =>
      lrComplete(dut, stid = 0, line = 0x4000, raw = 0x80000000L)
      acceptSc(dut, stid = 0, line = 0x4000, data = 0x55aa)

      clearInputs(dut)
      dut.io.scReqValid.poke(true.B)
      dut.io.scReqStid.poke(0.U)
      dut.io.scReqLineAddr.poke(0x4040.U)
      dut.io.scReqSize.poke(4.U)
      dut.io.scReqReady.expect(false.B)
      dut.io.blockedByScResident.expect(true.B)
      dut.clock.step()
      dut.io.scSuccess.expect(true.B)
      dut.io.scStoreData.expect(0x55aa.U)
      dut.io.scCommitReady.poke(true.B)
      dut.clock.step()
    }
  }

  test("Chisel ScalarLrScReservationOwner elaborates retained LR/SC owner state") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarLrScReservationOwner(params))

    assert(sv.contains("module ScalarLrScReservationOwner"))
    assert(sv.contains("io_scReqReady"))
    assert(sv.contains("io_scStoreValid"))
    assert(sv.contains("io_reservationValidByStid"))
    assert(sv.contains("io_protocolError"))
  }
}
