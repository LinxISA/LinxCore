package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.Decoupled
import org.scalatest.funsuite.AnyFunSuite

private class OooD3S1BrobHarnessIO(val p: OooParams) extends Bundle {
  val reserve = Flipped(Decoupled(new OooD2GroupedTransaction(p)))
  val commitReady = Input(Bool())
  val d3Used = Output(UInt(p.countWidth(p.robGroupsPerStid).W))
  val d3Published = Output(UInt(p.countWidth(p.robGroupsPerStid).W))
  val s1Occupied = Output(UInt(p.countWidth(p.robGroupsPerStid).W))
  val brobUsed = Output(UInt(p.brobCountWidth.W))
  val commitValid = Output(Bool())
}

private class OooD3S1BrobHarness(val p: OooParams) extends Module {
  val io = IO(new OooD3S1BrobHarnessIO(p))
  val d3 = Module(new OooD3ReservationAllocator(p))
  val s1 = Module(new OooS1GroupedRob(p))
  val brob = Module(new OooProductionBrob(p))

  d3.io.in <> io.reserve
  d3.io.cancel.foreach(_ := false.B)
  brob.io.prepare.valid := d3.io.out.valid
  brob.io.prepare.bits := d3.io.out.bits

  s1.io.publish.valid := d3.io.out.valid && brob.io.prepareReady
  s1.io.publish.bits := 0.U.asTypeOf(s1.io.publish.bits)
  s1.io.publish.bits.reservation := d3.io.out.bits
  for (groupIndex <- 0 until p.instructionDecodeWidth) {
    val active = d3.io.out.bits.transaction.groups(groupIndex).valid
    val binding = s1.io.publish.bits.bindings(groupIndex)
    binding.valid := active
    binding.brob := brob.io.prepared.pointers(groupIndex)
    binding.residentGeneration :=
      d3.io.out.bits.transaction.groups(groupIndex).key.ridGeneration
    binding.initiallyCompletedMembers := Mux(active, 1.U, 0.U)
  }
  d3.io.out.ready := brob.io.prepareReady && s1.io.publish.ready
  val sharedPublishFire = d3.io.out.valid && d3.io.out.ready
  brob.io.publishFire := sharedPublishFire

  s1.io.completion.valid := false.B
  s1.io.completion.bits := 0.U.asTypeOf(s1.io.completion.bits)
  brob.io.commit.bits := s1.io.commit.bits
  d3.io.release.bits := s1.io.commit.bits.release
  val allCommitReady = brob.io.commit.ready && d3.io.release.ready
  io.commitValid := s1.io.commit.valid && allCommitReady
  s1.io.commit.ready := io.commitReady && allCommitReady
  val sharedCommitFire = io.commitValid && io.commitReady
  brob.io.commit.valid := sharedCommitFire
  d3.io.release.valid := sharedCommitFire

  io.d3Used := d3.io.usedGroups(1)
  io.d3Published := d3.io.publishedGroups(1)
  io.s1Occupied := s1.io.occupiedGroups(1)
  io.brobUsed := brob.io.usedBlocks(1)
}

class OooD3S1BrobIntegrationSpec extends AnyFunSuite with ChiselSim {
  test("publishes and retires D3 grouped ROB and BROB state in one shared transaction") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      retireGroupWidth = 4,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8)
    simulate(new OooD3S1BrobHarness(p)) { dut =>
      dut.io.reserve.valid.poke(false.B)
      dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
      dut.io.commitReady.poke(false.B)

      val transaction = dut.io.reserve.bits
      transaction.plan.peId.poke(4.U)
      transaction.plan.stid.poke(1.U)
      transaction.plan.epoch.poke(7.U)
      transaction.plan.transactionId.poke(0.U)
      transaction.plan.groupCount.poke(2.U)
      transaction.plan.virtualTailEpoch.poke(0.U)
      transaction.plan.firstVirtualGroup.valid.poke(true.B)
      transaction.plan.firstVirtualGroup.peId.poke(4.U)
      transaction.plan.firstVirtualGroup.stid.poke(1.U)
      transaction.plan.firstVirtualGroup.ridSlot.poke(0.U)
      transaction.plan.firstVirtualGroup.ridGeneration.poke(0.U)
      transaction.decoded.peId.poke(4.U)
      transaction.decoded.stid.poke(1.U)
      transaction.decoded.epoch.poke(7.U)
      transaction.groupMask.poke(3.U)
      for (groupIndex <- 0 until 2) {
        val group = transaction.groups(groupIndex)
        group.valid.poke(true.B)
        group.key.valid.poke(true.B)
        group.key.peId.poke(4.U)
        group.key.stid.poke(1.U)
        group.key.ridSlot.poke(groupIndex.U)
        group.key.ridGeneration.poke(0.U)
        group.logicalUopMask.poke((1 << groupIndex).U)
        group.physicalMemberCount.poke(1.U)
        group.architecturalParentCount.poke(1.U)
        group.boundaryStart.poke((groupIndex == 0).B)
        group.boundaryStop.poke((groupIndex == 1).B)
      }

      dut.io.reserve.valid.poke(true.B)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.d3Used.expect(2.U)
      dut.io.d3Published.expect(0.U)
      dut.io.s1Occupied.expect(0.U)
      dut.io.brobUsed.expect(0.U)

      dut.clock.step()
      dut.io.d3Published.expect(2.U)
      dut.io.s1Occupied.expect(2.U)
      dut.io.brobUsed.expect(1.U)

      dut.clock.step()
      dut.io.commitValid.expect(true.B)
      dut.clock.step(2)
      dut.io.commitValid.expect(true.B)
      dut.io.d3Used.expect(2.U)
      dut.io.d3Published.expect(2.U)
      dut.io.s1Occupied.expect(2.U)
      dut.io.brobUsed.expect(1.U)
      dut.io.commitReady.poke(true.B)
      dut.clock.step()
      dut.io.commitReady.poke(false.B)
      dut.io.d3Used.expect(0.U)
      dut.io.d3Published.expect(0.U)
      dut.io.s1Occupied.expect(0.U)
      dut.io.brobUsed.expect(0.U)
    }
  }
}
