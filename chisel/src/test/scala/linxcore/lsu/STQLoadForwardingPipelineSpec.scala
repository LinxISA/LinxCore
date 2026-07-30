package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

import linxcore.rob.ROBID

class STQLoadForwardingPipelineSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: STQLoadForwardingPipeline): Unit = {
    dut.io.hold.poke(false.B)
    dut.io.flush.poke(false.B)
    dut.io.metadataRows.poke(0.U.asTypeOf(dut.io.metadataRows))
    dut.io.dataRows.poke(0.U.asTypeOf(dut.io.dataRows))
    dut.io.queries.foreach { query =>
      query.valid.poke(false.B)
      query.bits.poke(0.U.asTypeOf(query.bits))
    }
    dut.io.responses.foreach(_.ready.poke(true.B))
  }

  private def pokeId(id: ROBID, value: Int): Unit = {
    id.valid.poke(true.B)
    id.wrap.poke(false.B)
    id.value.poke(value.U)
  }

  private def pokeOwner(owner: STQExactOwner, member: Int): Unit = {
    owner.poke(0.U.asTypeOf(owner))
    owner.valid.poke(true.B)
    owner.peId.poke(1.U)
    owner.stid.poke(1.U)
    owner.nativeBidValid.poke(true.B)
    owner.nativeBid.poke(4.U)
    owner.brobGeneration.poke(2.U)
    owner.ridSlot.poke(member.U)
    owner.ridGeneration.poke(3.U)
    owner.memberIndex.poke(member.U)
    owner.residentGeneration.poke(5.U)
  }

  private def pokeRow(
      row: STQEntryBankRow,
      bid: Int,
      lsid: BigInt,
      address: BigInt,
      size: Int,
      generation: Int,
      member: Int,
      addrReady: Boolean = true,
      dataReady: Boolean = true): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.valid.poke(true.B)
    row.status.poke(STQEntryStatus.Wait)
    row.storeType.poke(STQStoreType.Addr)
    row.peId.poke(1.U)
    row.stid.poke(1.U)
    row.tid.poke(1.U)
    pokeId(row.bid, bid)
    pokeId(row.lsId, lsid.toInt & 7)
    row.lsIdFull.poke(lsid.U)
    row.storeIdFullValid.poke(true.B)
    row.storeIdFull.poke((lsid + 0x100).U)
    pokeOwner(row.exactOwner, member)
    row.leaseGeneration.poke(generation.U)
    row.pc.poke((0x8000 + member * 4).U)
    row.addr.poke(address.U)
    row.size.poke(size.U)
    row.scalarIex.poke(true.B)
    row.addrReady.poke(addrReady.B)
    row.dataReady.poke(dataReady.B)
  }

  private def pokeData(
      row: STQDataBankReadRow,
      generation: Int,
      size: Int,
      data: BigInt): Unit = {
    row.poke(0.U.asTypeOf(row))
    row.valid.poke(true.B)
    row.generation.poke(generation.U)
    row.byteMask.poke(((BigInt(1) << size) - 1).U)
    row.lineData.poke(data.U)
    row.data.poke((data & ((BigInt(1) << 64) - 1)).U)
  }

  private def pokeQuery(
      query: STQLoadForwardQuery,
      token: Int,
      bid: Int,
      lsid: BigInt,
      address: BigInt,
      size: Int,
      pipeIndex: Int = 0): Unit = {
    query.poke(0.U.asTypeOf(query))
    query.token.poke(token.U)
    query.loadId.valid.poke(true.B)
    query.loadId.slot.poke((token & 3).U)
    query.loadId.generation.poke((token & 1).U)
    query.attempt.valid.poke(true.B)
    query.attempt.producer.valid.poke(true.B)
    query.attempt.producer.nativeBidValid.poke(true.B)
    query.attempt.producer.stid.poke(1.U)
    query.attempt.generation.poke(token.U)
    query.returnPipeIndex.poke(pipeIndex.U)
    query.stid.poke(1.U)
    pokeId(query.loadBid, bid)
    query.loadLsIdFullValid.poke(true.B)
    query.loadLsIdFull.poke(lsid.U)
    query.address.poke(address.U)
    query.size.poke(size.U)
  }

  test("all replicated load pipes retain independent E1 snapshots") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 3, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      for (pipe <- 0 until 3) {
        pokeQuery(dut.io.queries(pipe).bits, 0x20 + pipe, 3, 30,
          0x1000 + pipe * 8, 8, pipeIndex = pipe)
        dut.io.queries(pipe).valid.poke(true.B)
        dut.io.queries(pipe).ready.expect(true.B)
      }
      dut.clock.step()
      dut.io.queries.foreach(_.valid.poke(false.B))
      dut.io.occupied.expect(7.U)
      dut.clock.step()
      for (pipe <- 0 until 3) {
        dut.io.responses(pipe).valid.expect(true.B)
        dut.io.responses(pipe).bits.query.token.expect((0x20 + pipe).U)
        dut.io.responses(pipe).bits.blocked.expect(false.B)
      }
    }
  }

  test("E3 selects the nearest older store independently for every byte") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 1, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeRow(dut.io.metadataRows(0), 3, 10, 0x1000, 4, 2, 0)
      pokeData(dut.io.dataRows(0), 2, 4, BigInt("44332211", 16))
      pokeRow(dut.io.metadataRows(1), 3, 20, 0x1002, 2, 7, 1)
      pokeData(dut.io.dataRows(1), 7, 2, BigInt("bbaa", 16))
      pokeQuery(dut.io.queries(0).bits, 9, 3, 30, 0x1000, 4)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.clock.step()

      val response = dut.io.responses(0).bits
      dut.io.responses(0).valid.expect(true.B)
      response.blocked.expect(false.B)
      response.forwardMask.expect(0xf.U)
      response.waitMask.expect(0.U)
      response.bypassComplete.expect(true.B)
      response.mergedLineData.expect(BigInt("bbaa2211", 16).U)
      response.eligibleStoreMask.expect(3.U)
    }
  }

  test("an unknown older address blocks without pretending bytes are uncovered") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 1, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeRow(dut.io.metadataRows(2), 2, 14, 0, 8, 5, 2,
        addrReady = false, dataReady = false)
      pokeQuery(dut.io.queries(0).bits, 10, 3, 30, 0x2000, 8)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.clock.step()

      val response = dut.io.responses(0).bits
      response.blocked.expect(true.B)
      response.unknownOlderMask.expect(4.U)
      response.unknownWaitStore.valid.expect(true.B)
      response.unknownWaitStore.storeIndex.expect(2.U)
      response.eligibleStoreMask.expect(0.U)
    }
  }

  test("E3 generation revalidation rejects row reuse after E1") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 1, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeRow(dut.io.metadataRows(0), 3, 10, 0x3000, 4, 2, 0)
      pokeData(dut.io.dataRows(0), 2, 4, BigInt("44332211", 16))
      pokeQuery(dut.io.queries(0).bits, 11, 3, 30, 0x3000, 4)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)

      dut.io.metadataRows(0).leaseGeneration.poke(3.U)
      dut.io.dataRows(0).generation.poke(3.U)
      dut.clock.step()
      dut.io.responses(0).bits.blocked.expect(true.B)
      dut.io.responses(0).bits.staleSnapshotMask.expect(1.U)
      dut.io.responses(0).bits.forwardMask.expect(0.U)
    }
  }

  test("recovery flush drops both tag and response residency") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 1, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeQuery(dut.io.queries(0).bits, 12, 3, 30, 0x4000, 8)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.occupied.expect(0.U)
      dut.io.responses(0).valid.expect(false.B)
    }
  }

  test("malformed identity and cross-line loads fail closed") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 1, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeQuery(dut.io.queries(0).bits, 13, 3, 30, 0x403f, 2)
      dut.io.queries(0).bits.loadBid.valid.poke(false.B)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.clock.step()
      dut.io.responses(0).bits.queryIdentityInvalid.expect(true.B)
      dut.io.responses(0).bits.loadCrossesLine.expect(true.B)
      dut.io.responses(0).bits.blocked.expect(true.B)
      dut.io.responses(0).bits.bypassComplete.expect(false.B)
    }
  }

  test("canonical load attempt and return pipe identity fail closed") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 2, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeQuery(dut.io.queries(1).bits, 22, 3, 30, 0x4800, 8,
        pipeIndex = 0)
      dut.io.queries(1).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(1).valid.poke(false.B)
      dut.clock.step()
      dut.io.responses(1).bits.queryIdentityInvalid.expect(true.B)
      dut.io.responses(1).bits.blocked.expect(true.B)

      pokeQuery(dut.io.queries(0).bits, 23, 3, 30, 0x4800, 8)
      dut.io.queries(0).bits.attempt.producer.stid.poke(2.U)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.clock.step()
      dut.io.responses(0).bits.queryIdentityInvalid.expect(true.B)
      dut.io.responses(0).bits.blocked.expect(true.B)

      pokeQuery(dut.io.queries(0).bits, 24, 3, 30, 0x4800, 8)
      dut.io.queries(0).bits.loadId.valid.poke(false.B)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.clock.step()
      dut.io.responses(0).bits.queryIdentityInvalid.expect(true.B)
      dut.io.responses(0).bits.blocked.expect(true.B)
    }
  }

  test("recovery prepare suppresses response fire without losing residency") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 1, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeQuery(dut.io.queries(0).bits, 14, 3, 30, 0x5000, 8)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.io.hold.poke(true.B)
      dut.clock.step()
      dut.io.responses(0).valid.expect(false.B)
      dut.io.occupied.expect(1.U)
      dut.clock.step(2)
      dut.io.occupied.expect(1.U)
      dut.io.hold.poke(false.B)
      dut.clock.step()
      dut.io.responses(0).valid.expect(true.B)
      dut.io.responses(0).bits.query.token.expect(14.U)
    }
  }

  test("same-BID forwarding requires unambiguous full LSID authority") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 1, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeRow(dut.io.metadataRows(0), 3, 5, 0x6000, 8, 2, 0)
      pokeData(dut.io.dataRows(0), 2, 8, BigInt("8877665544332211", 16))

      pokeQuery(dut.io.queries(0).bits, 15, 3, 30, 0x6000, 8)
      dut.io.queries(0).bits.loadLsIdFullValid.poke(false.B)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.clock.step()
      dut.io.responses(0).bits.fullLsIdMissingMask.expect(1.U)
      dut.io.responses(0).bits.queryIdentityInvalid.expect(true.B)
      dut.io.responses(0).bits.blocked.expect(true.B)
      dut.io.responses(0).bits.forwardMask.expect(0.U)

      val halfRange = BigInt(1) << 39
      pokeQuery(dut.io.queries(0).bits, 16, 3, 5 + halfRange, 0x6000, 8)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.clock.step()
      dut.io.responses(0).bits.fullLsIdAmbiguousMask.expect(1.U)
      dut.io.responses(0).bits.queryIdentityInvalid.expect(false.B)
      dut.io.responses(0).bits.blocked.expect(true.B)
      dut.io.responses(0).bits.forwardMask.expect(0.U)
    }
  }

  test("multiple unknown older stores report the nearest wait identity") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 1, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeRow(dut.io.metadataRows(0), 2, 10, 0, 8, 2, 0,
        addrReady = false, dataReady = false)
      pokeRow(dut.io.metadataRows(1), 3, 10, 0, 8, 3, 1,
        addrReady = false, dataReady = false)
      pokeRow(dut.io.metadataRows(2), 3, 30, 0, 8, 4, 2,
        addrReady = false, dataReady = false)
      pokeQuery(dut.io.queries(0).bits, 17, 3, 40, 0x7000, 8)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.clock.step()

      val response = dut.io.responses(0).bits
      response.unknownOlderMask.expect(7.U)
      response.unknownWaitStore.valid.expect(true.B)
      response.unknownWaitStore.storeIndex.expect(2.U)
      response.unknownWaitStore.storeLsIdFull.expect(30.U)
      response.blocked.expect(true.B)
    }
  }

  test("response backpressure survives recovery prepare without token loss") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 1, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeQuery(dut.io.queries(0).bits, 18, 3, 30, 0x8000, 8)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.io.responses(0).ready.poke(false.B)
      dut.clock.step()
      dut.io.responses(0).valid.expect(true.B)
      dut.io.responses(0).bits.query.token.expect(18.U)

      dut.io.hold.poke(true.B)
      dut.clock.step(2)
      dut.io.responses(0).valid.expect(false.B)
      dut.io.occupied.expect(1.U)

      dut.io.hold.poke(false.B)
      dut.io.responses(0).valid.expect(true.B)
      dut.io.responses(0).bits.query.token.expect(18.U)
      dut.clock.step(2)
      dut.io.responses(0).valid.expect(true.B)
      dut.io.responses(0).bits.query.token.expect(18.U)
      dut.io.responses(0).ready.poke(true.B)
      dut.clock.step()
      dut.io.occupied.expect(0.U)
    }
  }

  test("physical data generation mismatch is stale even when metadata is stable") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 1, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeRow(dut.io.metadataRows(0), 3, 10, 0x9000, 8, 2, 0)
      pokeData(dut.io.dataRows(0), 2, 8, BigInt("8877665544332211", 16))
      pokeQuery(dut.io.queries(0).bits, 19, 3, 30, 0x9000, 8)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.io.dataRows(0).generation.poke(3.U)
      dut.clock.step()

      dut.io.responses(0).bits.staleSnapshotMask.expect(1.U)
      dut.io.responses(0).bits.blocked.expect(true.B)
      dut.io.responses(0).bits.forwardMask.expect(0.U)
    }
  }

  test("a cross-line store blocks only overlapping loads") {
    simulate(new STQLoadForwardingPipeline(
      loadPipes = 1, stqEntries = 4, robEntries = 8, lsidWidth = 40)) { dut =>
      clear(dut)
      pokeRow(dut.io.metadataRows(0), 3, 10, 0x103c, 8, 2, 0)
      pokeData(dut.io.dataRows(0), 2, 8, BigInt("8877665544332211", 16))
      pokeQuery(dut.io.queries(0).bits, 20, 3, 30, 0x103e, 2)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.clock.step()
      dut.io.responses(0).bits.crossLineStoreMask.expect(1.U)
      dut.io.responses(0).bits.blocked.expect(true.B)

      pokeQuery(dut.io.queries(0).bits, 21, 3, 30, 0x1000, 8)
      dut.io.queries(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.queries(0).valid.poke(false.B)
      dut.clock.step()
      dut.io.responses(0).bits.crossLineStoreMask.expect(0.U)
      dut.io.responses(0).bits.blocked.expect(false.B)
    }
  }
}
