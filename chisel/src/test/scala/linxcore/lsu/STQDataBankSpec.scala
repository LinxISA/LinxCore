package linxcore.lsu

import circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class STQDataBankSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: STQDataBank): Unit = {
    dut.io.residentRows.poke(0.U.asTypeOf(dut.io.residentRows))
    dut.io.writes.foreach { port =>
      port.valid.poke(false.B)
      port.bits.poke(0.U.asTypeOf(port.bits))
    }
    dut.io.hold.poke(false.B)
    dut.io.clearMask.poke(0.U)
  }

  private def pokeOwner(
      owner: STQExactOwner,
      member: Int,
      residentGeneration: Int): Unit = {
    owner.poke(0.U.asTypeOf(owner))
    owner.valid.poke(true.B)
    owner.peId.poke(2.U)
    owner.stid.poke(1.U)
    owner.nativeBidValid.poke(true.B)
    owner.nativeBid.poke(0x31.U)
    owner.brobGeneration.poke(4.U)
    owner.ridSlot.poke(3.U)
    owner.ridGeneration.poke(5.U)
    owner.memberIndex.poke(member.U)
    owner.residentGeneration.poke(residentGeneration.U)
  }

  private def pokeResident(
      row: STQEntryBankRow,
      leaseGeneration: Int,
      member: Int,
      lsid: BigInt,
      storeId: BigInt): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.valid.poke(true.B)
    row.status.poke(STQEntryStatus.Wait)
    row.storeType.poke(STQStoreType.Addr)
    row.peId.poke(2.U)
    row.stid.poke(1.U)
    row.tid.poke(1.U)
    row.lsIdFull.poke(lsid.U)
    row.storeIdFullValid.poke(true.B)
    row.storeIdFull.poke(storeId.U)
    row.logicalStoreValid.poke(true.B)
    row.logicalFirstLsid.poke(lsid.U)
    row.logicalFirstStoreId.poke(storeId.U)
    row.logicalRequestCount.poke(1.U)
    row.logicalBeat.poke(0.U)
    pokeOwner(row.exactOwner, member, residentGeneration = member + 9)
    row.leaseGeneration.poke(leaseGeneration.U)
    row.addrReady.poke(true.B)
    row.dataReady.poke(false.B)
  }

  private def pokeWrite(
      request: STQStoreRequest,
      index: Int,
      leaseGeneration: Int,
      member: Int,
      lsid: BigInt,
      storeId: BigInt,
      data: BigInt,
      size: Int): Unit = {
    request.poke(0.U.asTypeOf(request))
    request.storeType.poke(STQStoreType.Data)
    request.peId.poke(2.U)
    request.stid.poke(1.U)
    request.tid.poke(1.U)
    request.lsIdFull.poke(lsid.U)
    request.storeIdFullValid.poke(true.B)
    request.storeIdFull.poke(storeId.U)
    request.logicalStoreValid.poke(true.B)
    request.logicalFirstLsid.poke(lsid.U)
    request.logicalFirstStoreId.poke(storeId.U)
    request.logicalRequestCount.poke(1.U)
    request.logicalBeat.poke(0.U)
    pokeOwner(request.exactOwner, member, residentGeneration = member + 9)
    request.lease.valid.poke(true.B)
    request.lease.index.poke(index.U)
    request.lease.generation.poke(leaseGeneration.U)
    request.data.poke(data.U)
    request.size.poke(size.U)
  }

  test("two exact STD writes complete independently after mask and data phases") {
    simulate(new STQDataBank(
      entries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeResident(dut.io.residentRows(0), 3, 2,
        BigInt("100000001", 16), BigInt("200000001", 16))
      pokeResident(dut.io.residentRows(1), 7, 4,
        BigInt("100000002", 16), BigInt("200000002", 16))
      pokeWrite(dut.io.writes(0).bits, 0, 3, 2,
        BigInt("100000001", 16), BigInt("200000001", 16),
        BigInt("1122334455667788", 16), 8)
      pokeWrite(dut.io.writes(1).bits, 1, 7, 4,
        BigInt("100000002", 16), BigInt("200000002", 16),
        BigInt("8877665544332211", 16), 8)
      dut.io.writes.foreach(_.valid.poke(true.B))
      dut.io.writes.foreach(_.ready.expect(true.B))

      dut.clock.step()
      dut.io.writes.foreach(_.valid.poke(false.B))
      dut.io.pendingMask.expect(3.U)
      dut.io.completions.foreach(_.valid.expect(false.B))

      dut.clock.step()
      dut.io.completions(0).valid.expect(true.B)
      dut.io.completions(0).bits.lease.index.expect(0.U)
      dut.io.completions(0).bits.lease.generation.expect(3.U)
      dut.io.completions(1).valid.expect(true.B)
      dut.io.completions(1).bits.lease.index.expect(1.U)
      dut.io.completions(1).bits.lease.generation.expect(7.U)

      dut.clock.step()
      dut.io.readyMask.expect(3.U)
      dut.io.rows(0).byteMask.expect(0xff.U)
      dut.io.rows(0).data.expect(BigInt("1122334455667788", 16).U)
      dut.io.rows(1).data.expect(BigInt("8877665544332211", 16).U)
      dut.io.empty.expect(false.B)

      dut.io.clearMask.poke(3.U)
      dut.clock.step()
      dut.io.clearMask.poke(0.U)
      dut.io.readyMask.expect(0.U)
      dut.io.empty.expect(true.B)
    }
  }

  test("stale generation and same-row dual writes fail closed") {
    simulate(new STQDataBank(
      entries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeResident(dut.io.residentRows(0), 9, 2,
        BigInt("100000010", 16), BigInt("200000010", 16))
      for (port <- 0 until 2) {
        pokeWrite(dut.io.writes(port).bits, 0,
          if (port == 0) 9 else 8, 2,
          BigInt("100000010", 16), BigInt("200000010", 16),
          BigInt("deadbeef", 16), 4)
        dut.io.writes(port).valid.poke(true.B)
      }
      dut.io.writes(0).ready.expect(true.B)
      dut.io.writes(1).ready.expect(false.B)
      dut.io.conflict(1).expect(true.B)
      dut.clock.step()
      dut.io.writes.foreach(_.valid.poke(false.B))

      dut.io.clearMask.poke(1.U)
      dut.clock.step()
      dut.io.clearMask.poke(0.U)
      dut.io.pendingMask.expect(0.U)
      dut.clock.step(2)
      dut.io.readyMask.expect(0.U)
      dut.io.completions.foreach(_.valid.expect(false.B))

      pokeWrite(dut.io.writes(0).bits, 0, 8, 2,
        BigInt("100000010", 16), BigInt("200000010", 16),
        BigInt("bad0", 16), 2)
      pokeWrite(dut.io.writes(1).bits, 0, 9, 2,
        BigInt("100000010", 16), BigInt("200000010", 16),
        BigInt("600d", 16), 2)
      dut.io.writes.foreach(_.valid.poke(true.B))
      dut.io.writes(0).ready.expect(false.B)
      dut.io.writes(1).ready.expect(true.B)
    }
  }

  test("recovery prepare holds a retained data transaction without mutation") {
    simulate(new STQDataBank(
      entries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeResident(dut.io.residentRows(0), 6, 2, 31, 41)
      pokeWrite(dut.io.writes(0).bits, 0, 6, 2, 31, 41,
        BigInt("cafebabedeadbeef", 16), 8)
      dut.io.writes(0).valid.poke(true.B)
      dut.io.writes(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.writes(0).valid.poke(false.B)

      dut.io.hold.poke(true.B)
      dut.clock.step(3)
      dut.io.pendingMask.expect(1.U)
      dut.io.readyMask.expect(0.U)
      dut.io.completions.foreach(_.valid.expect(false.B))

      dut.io.hold.poke(false.B)
      dut.clock.step()
      dut.io.completions(0).valid.expect(true.B)
      dut.clock.step()
      dut.io.rows(0).data.expect(BigInt("cafebabedeadbeef", 16).U)
    }
  }

  test("the second physical bank owns bytes 32 through 63") {
    simulate(new STQDataBank(
      entries = 4,
      dataWidth = 512,
      sizeWidth = 7,
      robEntries = 8,
      lsidWidth = 40)) { dut =>
      clear(dut)
      pokeResident(dut.io.residentRows(0), 5, 2, 11, 21)
      val payload = (BigInt(0xa5) << (39 * 8)) | BigInt(0x5a)
      pokeWrite(dut.io.writes(0).bits, 0, 5, 2, 11, 21, payload, 40)
      dut.io.writes(0).valid.poke(true.B)
      dut.io.writes(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.writes(0).valid.poke(false.B)
      dut.clock.step(2)

      dut.io.rows(0).byteMask.expect(((BigInt(1) << 40) - 1).U)
      dut.io.rows(0).lineData.expect(payload.U)
      assert(((dut.io.rows(0).lineData.peek().litValue >> (39 * 8)) & 0xff) == 0xa5)
    }
  }

  test("production data bank elaborates with two write ports and banked rows") {
    val sv = ChiselStage.emitSystemVerilog(new STQDataBank(
      entries = 4, robEntries = 8, lsidWidth = 40))

    assert(sv.contains("module STQDataBank"))
    assert(sv.contains("io_writes_0"))
    assert(sv.contains("io_writes_1"))
    assert(sv.contains("io_completions_0"))
    assert(sv.contains("io_rows_0_lineData"))
  }
}
