package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooIexStoreIssueFrontierSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 2,
    instructionDecodeWidth = 2,
    decodedUopWidth = 2,
    renameWidth = 2,
    dispatchWidth = 2,
    retireGroupWidth = 2,
    robGroupsPerStid = 8,
    robBankCount = 2,
    robRecoveryScanGroupsPerCycle = 2,
    robNonFlushScanGroupsPerCycle = 2,
    pcBufferEntries = 8,
    pcBankCount = 2,
    pcRecoveryScanGroupsPerCycle = 2,
    pcWritePorts = 2,
    iqBankCount = 2,
    iqEntriesPerBank = 4,
    iqFreeSelectLeafEntries = 2,
    tuRetireSourceDepthPerStid = 16,
    lsidWidth = 40)

  private def clear(dut: OooIexStoreIssueFrontier): Unit =
    dut.io.candidates.foreach(candidate =>
      candidate.poke(0.U.asTypeOf(candidate)))

  private def pokeStore(
      target: OooIexStoreFrontierCandidate,
      stid: Int,
      ridSlot: Int,
      memberIndex: Int,
      firstLsid: BigInt,
      firstStoreId: BigInt,
      requestCount: Int = 1): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.resident.poke(true.B)
    target.isStore.poke(true.B)
    target.peId.poke(3.U)
    target.stid.poke(stid.U)
    target.order.valid.poke(true.B)
    val member = target.order.logicalMember
    member.group.valid.poke(true.B)
    member.group.peId.poke(3.U)
    member.group.stid.poke(stid.U)
    member.group.ridSlot.poke(ridSlot.U)
    member.group.ridGeneration.poke(4.U)
    member.bid.valid.poke(true.B)
    member.bid.value.poke(9.U)
    member.brobGeneration.poke(5.U)
    member.memberIndex.poke(memberIndex.U)
    member.residentGeneration.poke(6.U)
    target.order.firstLsid.poke(firstLsid.U)
    target.order.firstStoreId.poke(firstStoreId.U)
    target.order.requestCount.poke(requestCount.U)
  }

  test("admits both children of only the oldest same-STID logical store") {
    simulate(new OooIexStoreIssueFrontier(p, candidateCount = 6)) { dut =>
      clear(dut)
      pokeStore(dut.io.candidates(0), stid = 0, ridSlot = 1,
        memberIndex = 2, firstLsid = 20, firstStoreId = 10)
      pokeStore(dut.io.candidates(1), stid = 0, ridSlot = 1,
        memberIndex = 2, firstLsid = 20, firstStoreId = 10)
      pokeStore(dut.io.candidates(2), stid = 0, ridSlot = 2,
        memberIndex = 0, firstLsid = 23, firstStoreId = 11)
      pokeStore(dut.io.candidates(3), stid = 1, ridSlot = 1,
        memberIndex = 0, firstLsid = 4, firstStoreId = 2)
      // A resident non-store row never participates in this frontier.
      dut.io.candidates(4).resident.poke(true.B)
      dut.io.candidates(4).stid.poke(0.U)

      dut.io.allowed(0).expect(true.B)
      dut.io.allowed(1).expect(true.B)
      dut.io.allowed(2).expect(false.B)
      dut.io.blocked(2).expect(true.B)
      dut.io.allowed(3).expect(true.B)
      dut.io.allowed(4).expect(true.B)
      dut.io.frontiers(0).valid.expect(true.B)
      dut.io.frontiers(0).bits.firstStoreId.expect(10.U)
      dut.io.blockedCount(0).expect(1.U)
      dut.io.blockedCount(1).expect(0.U)

      // Canonical IQ release is the only state change.  The frontier then
      // recomputes and admits the younger store without an explicit advance.
      dut.io.candidates(0).resident.poke(false.B)
      dut.io.candidates(1).resident.poke(false.B)
      dut.io.allowed(2).expect(true.B)
      dut.io.frontiers(0).bits.firstStoreId.expect(11.U)
    }
  }

  test("orders full serials across wrap without treating them as queue indices") {
    val modulus = BigInt(1) << p.lsidWidth
    simulate(new OooIexStoreIssueFrontier(p, candidateCount = 3)) { dut =>
      clear(dut)
      pokeStore(dut.io.candidates(0), stid = 0, ridSlot = 7,
        memberIndex = 1, firstLsid = modulus - 2,
        firstStoreId = modulus - 1)
      pokeStore(dut.io.candidates(1), stid = 0, ridSlot = 0,
        memberIndex = 0, firstLsid = 1, firstStoreId = 0)

      dut.io.allowed(0).expect(true.B)
      dut.io.allowed(1).expect(false.B)
      dut.io.frontiers(0).bits.firstStoreId.expect((modulus - 1).U)

      dut.io.candidates(0).resident.poke(false.B)
      dut.io.allowed(1).expect(true.B)
    }
  }

  test("fails closed on duplicate serial ownership while peers continue") {
    simulate(new OooIexStoreIssueFrontier(p, candidateCount = 4)) { dut =>
      clear(dut)
      pokeStore(dut.io.candidates(0), stid = 0, ridSlot = 1,
        memberIndex = 0, firstLsid = 8, firstStoreId = 3)
      pokeStore(dut.io.candidates(1), stid = 0, ridSlot = 2,
        memberIndex = 0, firstLsid = 8, firstStoreId = 3)
      pokeStore(dut.io.candidates(2), stid = 1, ridSlot = 1,
        memberIndex = 0, firstLsid = 8, firstStoreId = 3)

      dut.io.allowed(0).expect(false.B)
      dut.io.allowed(1).expect(false.B)
      dut.io.malformed(0).expect(true.B)
      dut.io.malformed(1).expect(true.B)
      dut.io.allowed(2).expect(true.B)
      dut.io.malformed(2).expect(false.B)
    }
  }

  test("fails closed when a resident store loses its logical order key") {
    simulate(new OooIexStoreIssueFrontier(p, candidateCount = 3)) { dut =>
      clear(dut)
      dut.io.candidates(0).resident.poke(true.B)
      dut.io.candidates(0).isStore.poke(true.B)
      dut.io.candidates(0).stid.poke(0.U)
      pokeStore(dut.io.candidates(1), stid = 1, ridSlot = 1,
        memberIndex = 0, firstLsid = 2, firstStoreId = 1)

      dut.io.allowed(0).expect(false.B)
      dut.io.blocked(0).expect(true.B)
      dut.io.malformed(0).expect(true.B)
      dut.io.allowed(1).expect(true.B)
      dut.io.malformed(1).expect(false.B)
    }
  }
}
