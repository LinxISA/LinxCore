package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

private final case class ObservedPTag(
    ptag: BigInt,
    bank: BigInt,
    generation: BigInt)

class OooPTagStagingPoolSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooPTagStagingPool): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.reserveFire.poke(false.B)
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.publish.valid.poke(false.B)
    dut.io.publish.bits.poke(0.U.asTypeOf(dut.io.publish.bits))
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
  }

  private def pokeDestinations(
      dut: OooPTagStagingPool,
      stid: Int,
      transactionId: Int,
      destinations: Seq[(Int, Int, Int)],
      peId: Int = 3,
      epoch: Int = 5): Unit = {
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    val transaction = dut.io.prepare.bits
    transaction.plan.peId.poke(peId.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(epoch.U)
    transaction.plan.transactionId.poke(transactionId.U)
    transaction.plan.demand.pDestinations.poke(destinations.size.U)
    transaction.plan.demand.mapQRows.poke(destinations.size.U)
    transaction.decoded.peId.poke(peId.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(epoch.U)
    val activeUops = destinations.map(_._1).distinct.sorted
    val uopMask = activeUops.foldLeft(0)((mask, uop) => mask | (1 << uop))
    transaction.decoded.uopMask.poke(uopMask.U)
    activeUops.foreach { uopIndex =>
      transaction.decoded.uops(uopIndex).valid.poke(true.B)
    }
    destinations.foreach { case (uopIndex, destinationIndex, atag) =>
      val destination = transaction.decoded.uops(uopIndex)
        .destinations(destinationIndex)
      destination.valid.poke(true.B)
      destination.kind.poke(linxcore.common.DestinationKind.Gpr)
      destination.atag.poke(atag.U)
    }
    dut.io.prepare.valid.poke(true.B)
  }

  private def claim(dut: OooPTagStagingPool): Seq[ObservedPTag] = {
    dut.io.prepareReady.expect(true.B)
    val tags = (0 until dut.p.pTagAllocationWidth).collect {
      case index if dut.io.prepared.allocations(index).valid.peek().litToBoolean =>
        val token = dut.io.prepared.allocations(index).token
        ObservedPTag(
          token.ptag.peek().litValue,
          token.bank.peek().litValue,
          token.generation.peek().litValue)
    }
    dut.io.reserveFire.poke(true.B)
    dut.clock.step()
    dut.io.reserveFire.poke(false.B)
    dut.io.prepare.valid.poke(false.B)
    tags
  }

  test("refills banked staging and retains an exact cancelable D3 lease") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooPTagStagingPool(p)) { dut =>
      clear(dut)
      dut.io.conservationValid.expect(true.B)
      dut.io.freeCount.expect(32.U)
      dut.clock.step()
      dut.io.stagedCount.foreach(_.expect(2.U))
      dut.io.freeCount.expect(28.U)

      pokeDestinations(dut, stid = 1, transactionId = 0,
        destinations = Seq((0, 0, 1), (0, 1, 2), (1, 0, 3), (1, 1, 4)))
      dut.io.prepared.allocationMask.expect("hf".U)
      val tags = claim(dut)
      assert(tags.size == 4)
      tags.zipWithIndex.foreach { case (tag, index) =>
        assert(tag.bank == (index % 2))
        assert((tag.ptag % 2) == tag.bank)
        assert(tag.generation == 1)
      }
      dut.io.provisional(1).valid.expect(true.B)
      dut.io.provisionalCount.expect(4.U)
      dut.io.stagedCount.foreach(_.expect(2.U))
      dut.io.freeCount.expect(24.U)
      dut.io.conservationValid.expect(true.B)

      val retained = (0 until 4).map(index =>
        dut.io.provisional(1).allocations(index).token.ptag.peek().litValue)
      dut.clock.step(3)
      dut.io.provisional(1).valid.expect(true.B)
      (0 until 4).foreach { index =>
        dut.io.provisional(1).allocations(index).token.ptag.expect(retained(index).U)
      }

      dut.io.cancel(1).poke(true.B)
      dut.clock.step()
      dut.io.cancel(1).poke(false.B)
      dut.io.provisional(1).valid.expect(false.B)
      dut.io.provisionalCount.expect(0.U)
      dut.io.freeCount.expect(28.U)
      dut.io.conservationValid.expect(true.B)
    }
  }

  test("moves one exact lease through publish return and refill") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooPTagStagingPool(p)) { dut =>
      clear(dut)
      dut.clock.step()
      pokeDestinations(dut, stid = 2, transactionId = 9,
        destinations = Seq((0, 0, 6), (0, 1, 7)))
      val tags = claim(dut)
      assert(tags.size == 2)

      dut.io.publish.bits.stid.poke(2.U)
      dut.io.publish.bits.transactionId.poke(9.U)
      dut.io.publish.valid.poke(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.io.provisional(2).valid.expect(false.B)
      dut.io.publishedCount.expect(2.U)
      dut.io.conservationValid.expect(true.B)

      dut.io.publish.valid.poke(true.B)
      dut.io.publishRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.io.publishedCount.expect(2.U)

      dut.io.release.bits.count.poke(2.U)
      for (index <- 0 until 2) {
        val token = dut.io.release.bits.tokens(index)
        token.valid.poke(true.B)
        token.ptag.poke(tags.head.ptag.U)
        token.bank.poke(tags.head.bank.U)
        token.generation.poke(tags.head.generation.U)
      }
      dut.io.release.valid.poke(true.B)
      dut.io.release.ready.expect(false.B)
      dut.io.returnRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.publishedCount.expect(2.U)

      dut.io.release.bits.tokens(1).ptag.poke(tags(1).ptag.U)
      dut.io.release.bits.tokens(1).bank.poke(tags(1).bank.U)
      dut.io.release.bits.tokens(1).generation.poke((tags(1).generation - 1).U)
      dut.io.release.ready.expect(false.B)
      dut.clock.step()
      dut.io.publishedCount.expect(2.U)

      dut.io.release.bits.tokens(1).generation.poke(tags(1).generation.U)
      // Lanes beyond count are intentionally don't-care payload.
      dut.io.release.bits.tokens(2).valid.poke(true.B)
      dut.io.release.bits.tokens(2).ptag.poke(127.U)
      dut.io.release.bits.tokens(2).bank.poke(1.U)
      dut.io.release.bits.tokens(2).generation.poke(99.U)
      dut.io.release.ready.expect(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.publishedCount.expect(0.U)
      dut.io.freeCount.expect(28.U)
      dut.io.conservationValid.expect(true.B)
    }
  }

  test("isolates provisional rows by STID and rejects malformed P demand") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooPTagStagingPool(p)) { dut =>
      clear(dut)
      dut.clock.step()

      pokeDestinations(dut, stid = 0, transactionId = 0,
        destinations = Seq((0, 0, 1)))
      val first = claim(dut).head.ptag
      pokeDestinations(dut, stid = 3, transactionId = 0,
        destinations = Seq((0, 0, 2)))
      val second = claim(dut).head.ptag
      assert(first != second)
      dut.io.provisional(0).valid.expect(true.B)
      dut.io.provisional(3).valid.expect(true.B)

      pokeDestinations(dut, stid = 1, transactionId = 0,
        destinations = Seq((0, 0, 24)))
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.valid.expect(true.B)
      dut.io.provisionalCount.expect(2.U)
      dut.io.conservationValid.expect(true.B)
    }
  }

  test("blocks an all-bank claim without mutation when every speculative tag is live") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooPTagStagingPool(p)) { dut =>
      clear(dut)
      dut.clock.step()
      val destinations = Seq((0, 0, 1), (0, 1, 2), (1, 0, 3), (1, 1, 4))
      for (transactionId <- 0 until 8) {
        val stid = transactionId % p.stidCount
        pokeDestinations(dut, stid, transactionId, destinations)
        claim(dut)
        dut.io.publish.bits.stid.poke(stid.U)
        dut.io.publish.bits.transactionId.poke(transactionId.U)
        dut.io.publish.valid.poke(true.B)
        dut.clock.step()
        dut.io.publish.valid.poke(false.B)
      }
      dut.io.publishedCount.expect(32.U)
      dut.io.freeCount.expect(0.U)
      dut.io.stagedCount.foreach(_.expect(0.U))
      dut.io.conservationValid.expect(true.B)

      pokeDestinations(dut, stid = 0, transactionId = 8,
        destinations = destinations)
      dut.io.prepareReady.expect(false.B)
      dut.io.reserveFire.poke(false.B)
      dut.clock.step(2)
      dut.io.publishedCount.expect(32.U)
      dut.io.provisionalCount.expect(0.U)
      dut.io.conservationValid.expect(true.B)
    }
  }

  test("elaborates the staging owner at instruction widths 2 4 and 6") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(instructionDecodeWidth = width)
      val sv = ChiselStage.emitSystemVerilog(new OooPTagStagingPool(p))
      assert(sv.contains("module OooPTagStagingPool"))
      assert(sv.contains("io_conservationValid"))
    }
  }
}
