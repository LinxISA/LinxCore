package linxcore.backend

import circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

object ScalarContinuationBlockIdentityReference {
  final case class Row(name: String, fullBid: BigInt, resourceCutLast: Boolean, architecturalLast: Boolean)

  def prematureRelease(
      rows: Seq[Row],
      releasedBid: BigInt,
      releasedRow: String): Boolean = {
    val releasedIndex = rows.indexWhere(_.name == releasedRow)
    require(releasedIndex >= 0, "released row must be resident")
    val release = rows(releasedIndex)
    require(release.fullBid == releasedBid, "release row must own the released full BID")

    release.resourceCutLast &&
      !release.architecturalLast &&
      rows.drop(releasedIndex + 1).exists(row => row.fullBid == releasedBid)
  }
}

class ScalarContinuationBlockIdentitySpec extends AnyFunSuite with ChiselSim {
  import ScalarContinuationBlockIdentityReference._

  test("reference rejects resource-cut local release while younger rows retain the same full BID") {
    val activeBid = BigInt("116", 16)
    val rows = Seq(
      Row("older-tu-producer", activeBid, resourceCutLast = false, architecturalLast = false),
      Row("gpr-pressure-cut", activeBid, resourceCutLast = true, architecturalLast = false),
      Row("younger-tu-consumer", activeBid, resourceCutLast = false, architecturalLast = false))

    assert(prematureRelease(rows, releasedBid = activeBid, releasedRow = "gpr-pressure-cut"))
  }

  test("reference accepts a fresh continuation BID after a resource-management cut") {
    val activeBid = BigInt("116", 16)
    val continuationBid = BigInt("117", 16)
    val rows = Seq(
      Row("older-tu-producer", activeBid, resourceCutLast = false, architecturalLast = false),
      Row("gpr-pressure-cut", activeBid, resourceCutLast = true, architecturalLast = false),
      Row("younger-tu-consumer", continuationBid, resourceCutLast = false, architecturalLast = false))

    assert(!prematureRelease(rows, releasedBid = activeBid, releasedRow = "gpr-pressure-cut"))
  }

  test("reference accepts true architectural block-last release") {
    val activeBid = BigInt("116", 16)
    val rows = Seq(
      Row("older-tu-producer", activeBid, resourceCutLast = false, architecturalLast = false),
      Row("architectural-last", activeBid, resourceCutLast = false, architecturalLast = true))

    assert(!prematureRelease(rows, releasedBid = activeBid, releasedRow = "architectural-last"))
  }

  test("probe elaborates the real DecodeRenameROBPath with the minimum legal cut threshold") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarContinuationBlockIdentityProbe)

    assert(sv.contains("module DecodeRenameROBPath"))
    assert(sv.contains("io_selectedBlockBid"))
    assert(sv.contains("io_robDeallocBlockLastValid"))
    assert(sv.contains("io_tuRetireLocalBlockCommitValid"))
    assert(sv.contains("io_gprCommitAccepted"))
  }

  test("real path cuts T continuation pressure and releases capacity through block lifecycle") {
    simulate(new ScalarContinuationBlockIdentityProbe) { dut =>
      def idleInputs(): Unit = {
        dut.io.decodeValid.poke(false.B)
        dut.io.decodeInsn.poke(0.U)
        dut.io.decodePc.poke(0.U)
        dut.io.decodeLen.poke(0.U)
        dut.io.decodeLast.poke(false.B)
        dut.io.completeValid.poke(false.B)
        dut.io.completeRobValue.poke(0.U)
        dut.io.completeBlockBid.poke(0.U)
        dut.io.completePc.poke(0.U)
        dut.io.deallocReady.poke(true.B)
      }

      def issue(raw: BigInt, pc: BigInt, expectedBid: BigInt, expectedRob: Int, cut: Boolean): Unit = {
        idleInputs()
        dut.io.decodeValid.poke(true.B)
        dut.io.decodeInsn.poke(raw.U)
        dut.io.decodePc.poke(pc.U)
        dut.io.decodeLen.poke(2.U)
        dut.io.decodeLast.poke(false.B)
        dut.io.decodeReady.expect(true.B)
        dut.io.selectedValid.expect(true.B)
        dut.io.selectedBlockBid.expect(expectedBid.U)
        dut.io.selectedRobValue.expect(expectedRob.U)
        dut.io.selectedIsLastInBlock.expect(cut.B)
        dut.io.scalarContinuationTCutFire.expect(cut.B)
        dut.io.scalarContinuationOwnershipCutFire.expect(cut.B)
        dut.io.blockedByTURename.expect(false.B)
        dut.clock.step()
        idleInputs()
      }

      def marker(raw: BigInt, pc: BigInt): Unit = {
        idleInputs()
        dut.io.decodeValid.poke(true.B)
        dut.io.decodeInsn.poke(raw.U)
        dut.io.decodePc.poke(pc.U)
        dut.io.decodeLen.poke(2.U)
        dut.io.decodeLast.poke(false.B)
        dut.io.decodeReady.expect(true.B)
        dut.clock.step()
        idleInputs()
      }

      def complete(rob: Int, bid: BigInt, pc: BigInt): Unit = {
        idleInputs()
        dut.io.completeValid.poke(true.B)
        dut.io.completeRobValue.poke(rob.U)
        dut.io.completeBlockBid.poke(bid.U)
        dut.io.completePc.poke(pc.U)
        dut.clock.step()
        idleInputs()
      }

      idleInputs()
      dut.clock.step()

      marker(0x0194, 0x12490)
      issue(0xf886, 0x12492, expectedBid = 0, expectedRob = 0, cut = false)
      issue(0xf886, 0x12494, expectedBid = 0, expectedRob = 1, cut = true)
      issue(0xf886, 0x12496, expectedBid = 1, expectedRob = 2, cut = false)

      for (_ <- 0 until 8) {
        dut.clock.step()
      }
      dut.io.tuRenameTUsedEntries.expect(3.U)
      complete(rob = 0, bid = 0, pc = 0x12492)
      complete(rob = 1, bid = 0, pc = 0x12494)
      for (_ <- 0 until 24) {
        dut.clock.step()
      }

      dut.io.observedTCutBid0.expect(true.B)
      dut.io.observedFreshBid1.expect(true.B)
      dut.io.observedBlockLastBid0.expect(true.B)
      dut.io.observedLocalCommitBid0.expect(true.B)
      dut.io.observedTRelease.expect(true.B)
      dut.io.tuRenameTUsedEntries.expect(1.U)

      issue(0xf886, 0x12498, expectedBid = 1, expectedRob = 3, cut = true)
      for (_ <- 0 until 8) {
        dut.clock.step()
      }
      dut.io.observedPostReleaseTAccept.expect(true.B)
    }
  }
}
