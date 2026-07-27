package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.Decoupled
import org.scalatest.funsuite.AnyFunSuite

private class OooD3S1GroupedRobHarnessIO(val p: OooParams) extends Bundle {
  val reserve = Flipped(Decoupled(new OooD2GroupedTransaction(p)))
  val bindings = Input(Vec(p.instructionDecodeWidth, new OooS1GroupBinding(p)))
  val commit = Decoupled(new OooRobCommitBatch(p))
  val cancel = Input(Vec(p.stidCount, Bool()))
  val d3Used = Output(Vec(p.stidCount, UInt(p.countWidth(p.robGroupsPerStid).W)))
  val d3Published = Output(Vec(p.stidCount, UInt(p.countWidth(p.robGroupsPerStid).W)))
  val d3HeadEpoch = Output(Vec(p.stidCount, UInt(p.reservationEpochWidth.W)))
  val s1Occupied = Output(Vec(p.stidCount, UInt(p.countWidth(p.robGroupsPerStid).W)))
  val s1HeadEpoch = Output(Vec(p.stidCount, UInt(p.reservationEpochWidth.W)))
}

private class OooD3S1GroupedRobHarness(val p: OooParams) extends Module {
  val io = IO(new OooD3S1GroupedRobHarnessIO(p))

  val d3 = Module(new OooD3ReservationAllocator(p))
  val s1 = Module(new OooS1GroupedRob(p))
  d3.io.in <> io.reserve
  d3.io.cancel := io.cancel
  d3.io.publishEligible.foreach(_ := true.B)

  s1.io.publish.valid := d3.io.out.valid
  s1.io.publish.bits.reservation := d3.io.out.bits
  s1.io.publish.bits.bindings := io.bindings
  d3.io.out.ready := s1.io.publish.ready

  s1.io.completion.valid := false.B
  s1.io.completion.bits := 0.U.asTypeOf(s1.io.completion.bits)
  s1.io.nonFlushEvidence.valid := false.B
  s1.io.nonFlushEvidence.bits := 0.U.asTypeOf(s1.io.nonFlushEvidence.bits)
  s1.io.interruptPending.foreach(_ := false.B)
  d3.io.release.bits := s1.io.commit.bits.release
  io.commit.valid := s1.io.commit.valid && d3.io.release.ready
  io.commit.bits := s1.io.commit.bits
  s1.io.commit.ready := io.commit.ready && d3.io.release.ready
  val sharedCommitFire = io.commit.valid && io.commit.ready
  d3.io.release.valid := sharedCommitFire

  io.d3Used := d3.io.usedGroups
  io.d3Published := d3.io.publishedGroups
  io.d3HeadEpoch := d3.io.headEpoch
  io.s1Occupied := s1.io.occupiedGroups
  io.s1HeadEpoch := s1.io.headEpoch
}

class OooD3S1GroupedRobIntegrationSpec extends AnyFunSuite with ChiselSim {
  test("publishes and retires one exact grouped transaction across D3 and S1") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      retireGroupWidth = 4,
      robGroupsPerStid = 8)
    simulate(new OooD3S1GroupedRobHarness(p)) { dut =>
      dut.io.reserve.valid.poke(false.B)
      dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
      dut.io.bindings.foreach(_.poke(0.U.asTypeOf(dut.io.bindings.head)))
      dut.io.cancel.foreach(_.poke(false.B))
      dut.io.commit.ready.poke(false.B)

      val transaction = dut.io.reserve.bits
      transaction.plan.peId.poke(6.U)
      transaction.plan.stid.poke(2.U)
      transaction.plan.epoch.poke(9.U)
      transaction.plan.transactionId.poke(0.U)
      transaction.plan.groupCount.poke(2.U)
      transaction.plan.virtualTailEpoch.poke(0.U)
      transaction.plan.firstVirtualGroup.valid.poke(true.B)
      transaction.plan.firstVirtualGroup.peId.poke(6.U)
      transaction.plan.firstVirtualGroup.stid.poke(2.U)
      transaction.plan.firstVirtualGroup.ridSlot.poke(0.U)
      transaction.plan.firstVirtualGroup.ridGeneration.poke(0.U)
      transaction.decoded.peId.poke(6.U)
      transaction.decoded.stid.poke(2.U)
      transaction.decoded.epoch.poke(9.U)
      transaction.groupMask.poke(3.U)
      for (groupIndex <- 0 until 2) {
        val group = transaction.groups(groupIndex)
        group.valid.poke(true.B)
        group.key.valid.poke(true.B)
        group.key.peId.poke(6.U)
        group.key.stid.poke(2.U)
        group.key.ridSlot.poke(groupIndex.U)
        group.key.ridGeneration.poke(0.U)
        group.logicalUopMask.poke((1 << groupIndex).U)
        group.physicalMemberCount.poke(1.U)
        group.architecturalParentCount.poke(1.U)

        val binding = dut.io.bindings(groupIndex)
        binding.valid.poke(true.B)
        binding.brob.valid.poke(true.B)
        binding.brob.bid.valid.poke(true.B)
        binding.brob.bid.value.poke(12.U)
        binding.brob.generation.poke(4.U)
        binding.residentGeneration.poke(3.U)
        binding.initiallyCompletedMembers.poke(1.U)
      }

      dut.io.reserve.valid.poke(true.B)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.d3Used(2).expect(2.U)
      dut.io.d3Published(2).expect(0.U)

      dut.clock.step()
      dut.io.d3Used(2).expect(2.U)
      dut.io.d3Published(2).expect(2.U)
      dut.io.s1Occupied(2).expect(2.U)

      dut.clock.step()
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.release.firstGroup.stid.expect(2.U)
      dut.io.commit.bits.release.firstGroup.ridSlot.expect(0.U)
      dut.io.commit.bits.release.headEpoch.expect(0.U)
      dut.io.commit.bits.release.groupCount.expect(2.U)
      dut.clock.step(2)
      dut.io.commit.valid.expect(true.B)
      dut.io.d3Used(2).expect(2.U)
      dut.io.d3Published(2).expect(2.U)
      dut.io.s1Occupied(2).expect(2.U)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)

      dut.io.d3Used(2).expect(0.U)
      dut.io.d3Published(2).expect(0.U)
      dut.io.s1Occupied(2).expect(0.U)
      dut.io.d3HeadEpoch(2).expect(1.U)
      dut.io.s1HeadEpoch(2).expect(1.U)
    }
  }
}
