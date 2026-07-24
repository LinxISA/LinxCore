package linxcore.system

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ReducedServiceRenameSnapshotSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams(robEntries = 8, physRegWidth = 7, blockBidWidth = 64)

  private def clearInputs(dut: ReducedServiceRenameSnapshot): Unit = {
    dut.io.captureValid.poke(false.B)
    dut.io.captureIdentity.poke(0.U.asTypeOf(dut.io.captureIdentity))
    dut.io.capturePhysTags.foreach(_.poke(0.U))
    dut.io.lookupValid.poke(false.B)
    dut.io.lookupIdentity.poke(0.U.asTypeOf(dut.io.lookupIdentity))
    dut.io.clearValid.poke(false.B)
    dut.io.clearIdentity.poke(0.U.asTypeOf(dut.io.clearIdentity))
    dut.io.flush.poke(false.B)
  }

  private def pokeIdentity(
      id: ReducedServiceRenameSnapshotIdentity,
      stid: Int = 1,
      bid: Int = 2,
      gid: Int = 3,
      rid: Int = 4,
      bidWrap: Boolean = false,
      gidWrap: Boolean = false,
      ridWrap: Boolean = true,
      valid: Boolean = true): Unit = {
    id.stid.poke(stid.U)
    id.bid.valid.poke(valid.B)
    id.bid.wrap.poke(bidWrap.B)
    id.bid.value.poke(bid.U)
    id.gid.valid.poke(valid.B)
    id.gid.wrap.poke(gidWrap.B)
    id.gid.value.poke(gid.U)
    id.rid.valid.poke(valid.B)
    id.rid.wrap.poke(ridWrap.B)
    id.rid.value.poke(rid.U)
  }

  private def pokeTags(dut: ReducedServiceRenameSnapshot, base: Int): Unit = {
    for (idx <- 0 until 7) {
      dut.io.capturePhysTags(idx).poke((base + idx).U)
    }
  }

  private def expectTags(dut: ReducedServiceRenameSnapshot, base: Int): Unit = {
    for (idx <- 0 until 7) {
      dut.io.lookupPhysTags(idx).expect((base + idx).U)
    }
  }

  test("captures a service rename identity and returns all seven physical tags on lookup") {
    simulate(new ReducedServiceRenameSnapshot(p)) { dut =>
      clearInputs(dut)
      pokeIdentity(dut.io.captureIdentity)
      pokeTags(dut, 70)
      dut.io.captureValid.poke(true.B)
      dut.clock.step()

      dut.io.captureValid.poke(false.B)
      dut.io.occupied.expect(true.B)
      pokeIdentity(dut.io.lookupIdentity)
      dut.io.lookupValid.poke(true.B)
      dut.io.lookupMatch.expect(true.B)
      dut.io.lookupMismatch.expect(false.B)
      expectTags(dut, 70)
    }
  }

  test("requires full identity so same STID with RID wrap or BID differences does not match") {
    simulate(new ReducedServiceRenameSnapshot(p)) { dut =>
      clearInputs(dut)
      pokeIdentity(dut.io.captureIdentity, stid = 2, bid = 1, gid = 3, rid = 4, ridWrap = false)
      pokeTags(dut, 20)
      dut.io.captureValid.poke(true.B)
      dut.clock.step()

      dut.io.captureValid.poke(false.B)
      dut.io.lookupValid.poke(true.B)
      pokeIdentity(dut.io.lookupIdentity, stid = 2, bid = 1, gid = 3, rid = 4, ridWrap = true)
      dut.io.lookupMatch.expect(false.B)
      dut.io.lookupMismatch.expect(true.B)

      pokeIdentity(dut.io.lookupIdentity, stid = 2, bid = 5, gid = 3, rid = 4, ridWrap = false)
      dut.io.lookupMatch.expect(false.B)
      dut.io.lookupMismatch.expect(true.B)
    }
  }

  test("blocks recapture while occupied and preserves the original snapshot") {
    simulate(new ReducedServiceRenameSnapshot(p)) { dut =>
      clearInputs(dut)
      pokeIdentity(dut.io.captureIdentity, rid = 1)
      pokeTags(dut, 30)
      dut.io.captureValid.poke(true.B)
      dut.clock.step()

      pokeIdentity(dut.io.captureIdentity, rid = 2)
      pokeTags(dut, 40)
      dut.io.captureBlocked.expect(true.B)
      dut.clock.step()

      dut.io.captureValid.poke(false.B)
      dut.io.lookupValid.poke(true.B)
      pokeIdentity(dut.io.lookupIdentity, rid = 1)
      dut.io.lookupMatch.expect(true.B)
      expectTags(dut, 30)
      pokeIdentity(dut.io.lookupIdentity, rid = 2)
      dut.io.lookupMatch.expect(false.B)
    }
  }

  test("clears only when the specified owner identity matches") {
    simulate(new ReducedServiceRenameSnapshot(p)) { dut =>
      clearInputs(dut)
      pokeIdentity(dut.io.captureIdentity, stid = 3, bid = 2, gid = 1, rid = 6)
      pokeTags(dut, 50)
      dut.io.captureValid.poke(true.B)
      dut.clock.step()

      dut.io.captureValid.poke(false.B)
      dut.io.clearValid.poke(true.B)
      pokeIdentity(dut.io.clearIdentity, stid = 3, bid = 2, gid = 1, rid = 5)
      dut.clock.step()
      dut.io.occupied.expect(true.B)

      pokeIdentity(dut.io.clearIdentity, stid = 3, bid = 2, gid = 1, rid = 6)
      dut.clock.step()
      dut.io.occupied.expect(false.B)
    }
  }

  test("flush has same-cycle priority over capture and clear") {
    simulate(new ReducedServiceRenameSnapshot(p)) { dut =>
      clearInputs(dut)
      pokeIdentity(dut.io.captureIdentity, rid = 1)
      pokeTags(dut, 40)
      dut.io.captureValid.poke(true.B)
      dut.clock.step()

      pokeIdentity(dut.io.captureIdentity, rid = 2)
      pokeTags(dut, 50)
      pokeIdentity(dut.io.clearIdentity, rid = 1)
      dut.io.clearValid.poke(true.B)
      dut.io.flush.poke(true.B)
      dut.io.captureBlocked.expect(false.B)
      dut.clock.step()

      dut.io.flush.poke(false.B)
      dut.io.captureValid.poke(false.B)
      dut.io.clearValid.poke(false.B)
      dut.io.occupied.expect(false.B)
      dut.io.lookupValid.poke(true.B)
      pokeIdentity(dut.io.lookupIdentity, rid = 2)
      dut.io.lookupMatch.expect(false.B)
    }
  }

  test("invalid ROBID identities are fail-closed for lookup and clear") {
    simulate(new ReducedServiceRenameSnapshot(p)) { dut =>
      clearInputs(dut)
      pokeIdentity(dut.io.captureIdentity, rid = 3, valid = false)
      pokeTags(dut, 50)
      dut.io.captureValid.poke(true.B)
      dut.clock.step()

      dut.io.captureValid.poke(false.B)
      dut.io.lookupValid.poke(true.B)
      pokeIdentity(dut.io.lookupIdentity, rid = 3, valid = false)
      dut.io.lookupMatch.expect(false.B)
      dut.io.lookupMismatch.expect(true.B)

      dut.io.clearValid.poke(true.B)
      pokeIdentity(dut.io.clearIdentity, rid = 3, valid = false)
      dut.clock.step()
      dut.io.occupied.expect(true.B)

      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.occupied.expect(false.B)
    }
  }

  test("Chisel ReducedServiceRenameSnapshot elaborates the standalone snapshot surface") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedServiceRenameSnapshot(p))

    assert(sv.contains("module ReducedServiceRenameSnapshot"))
    assert(sv.contains("io_captureValid"))
    assert(sv.contains("io_lookupMatch"))
    assert(sv.contains("io_lookupPhysTags_0"))
    assert(sv.contains("io_clearValid"))
    assert(sv.contains("io_captureBlocked"))
    assert(sv.contains("io_lookupMismatch"))
  }
}
