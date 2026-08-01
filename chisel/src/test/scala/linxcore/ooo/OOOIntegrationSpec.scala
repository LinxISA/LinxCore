package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.params.{ParamProfiles, CoreParams}
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

private object OOOIntegrationSpec {
  final case class Shape(p: Boolean, t: Boolean, u: Boolean)
}

class OOOIntegrationSpec extends AnyFunSuite with ChiselSim {
  import OOOIntegrationSpec.Shape
  test("elaborates the canonical owner graph at W2 W4 W6 and W8") {
    Seq(2, 4, 6, 8).foreach { width =>
      val p: CoreParams = ParamProfiles.forWidth(width)
      val chirrtl = ChiselStage.emitCHIRRTL(new OOO(p))
      assert(chirrtl.contains("circuit OOO"))
      Seq("RENU", "ROB", "BROB", "Dispatch", "CommitControl",
        "RecoveryControl").foreach(owner =>
        assert(chirrtl.contains(s"module $owner")))
      assert(chirrtl.contains("module OooD3ReservationAllocator"))
      Seq("OooO3RenameCoordinator", "OooS1GroupedRob", "OooBrob",
        "OooPRename", "OooTURename").foreach(legacy =>
        assert(!chirrtl.contains(s"module $legacy")))
    }
  }

  private def clear(dut: OOOD3S1Graph): Unit = {
    dut.io.fromD2.valid.poke(false.B)
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.iex.aluDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.bruDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.aguDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.storeDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.systemDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.cmdDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.completion.foreach { in =>
      in.valid.poke(false.B)
      in.bits.poke(0.U.asTypeOf(in.bits))
    }
    dut.io.iex.recoveryEvent.valid.poke(false.B)
    dut.io.iex.recoveryEvent.bits.poke(0.U.asTypeOf(dut.io.iex.recoveryEvent.bits))
    dut.io.commit.ready.poke(true.B)
    dut.io.trap.ready.poke(true.B)
    dut.io.interrupt.valid.poke(false.B)
    dut.io.interrupt.bits.poke(0.U.asTypeOf(dut.io.interrupt.bits))
    Seq(dut.io.recoveryToD1, dut.io.iex.recovery, dut.io.recoveryToIfu,
      dut.io.recoveryToCtu, dut.io.recoveryToLsu).foreach { target =>
      target.prepare.ready.poke(true.B)
      target.prepared.valid.poke(false.B)
      target.prepared.bits.poke(0.U.asTypeOf(target.prepared.bits))
    }
    dut.io.trace.ready.poke(true.B)
  }

  private def driveTargetAcks(dut: OOOD3S1Graph): Unit = {
    Seq(dut.io.recoveryToD1, dut.io.iex.recovery, dut.io.recoveryToIfu,
      dut.io.recoveryToCtu, dut.io.recoveryToLsu).foreach { target =>
      val preparing = target.prepare.valid.peek().litToBoolean
      target.prepared.valid.poke(preparing.B)
      if (preparing) target.prepared.bits.poke(target.prepare.bits.peek())
    }
  }

  private def publish(
      dut: OOOD3S1Graph,
      stid: Int,
      shapes: Seq[Shape]): Seq[RobIdentity] = {
    require(shapes.size == 2)
    val tail = dut.io.ridTailSlot(stid).peek().litValue.toInt
    val generation = dut.io.ridTailGeneration(stid).peek().litValue
    val capacity = dut.p.ooo.robGroupsPerStid
    dut.io.iex.aluDispatch.foreach(_.ready.poke(false.B))
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.fromD2.bits.count.poke(2.U)
    dut.io.fromD2.bits.groupCount.poke(2.U)
    shapes.zipWithIndex.foreach { case (shape, lane) =>
      val absolute = tail + lane
      val slot = absolute % capacity
      val gen = generation + absolute / capacity
      val group = dut.io.fromD2.bits.groups(lane)
      group.valid.poke(true.B)
      group.peId.poke(1.U)
      group.stid.poke(stid.U)
      group.ridSlot.poke(slot.U)
      group.ridGeneration.poke(gen.U)
      val row = dut.io.fromD2.bits.entries(lane).uop
      row.valid.poke(true.B)
      row.instruction.parent.identity.peId.poke(1.U)
      row.instruction.parent.identity.stid.poke(stid.U)
      row.instruction.parent.identity.instructionId.poke((100 + stid * 10 + lane).U)
      row.instruction.parent.identity.epoch.poke(3.U)
      row.rob.peId.poke(1.U)
      row.rob.stid.poke(stid.U)
      row.rob.ridSlot.poke(slot.U)
      row.rob.ridGeneration.poke(gen.U)
      row.rob.memberIndex.poke(0.U)
      row.uopClass.poke(UopClass.Alu)
      row.blockStart.poke(true.B)
      row.blockStop.poke(true.B)
      var destination = 0
      if (shape.p) {
        row.destinations(destination).valid.poke(true.B)
        row.destinations(destination).kind.poke(OperandKind.Gpr)
        row.destinations(destination).atag.poke((stid + lane + 1).U)
        destination += 1
      }
      if (shape.t) {
        row.destinations(destination).valid.poke(true.B)
        row.destinations(destination).kind.poke(OperandKind.T)
        row.destinations(destination).relativeIndex.poke(0.U)
        destination += 1
      }
      if (shape.u) {
        row.destinations(destination).valid.poke(true.B)
        row.destinations(destination).kind.poke(OperandKind.U)
        row.destinations(destination).relativeIndex.poke(0.U)
      }
    }
    dut.io.fromD2.valid.poke(true.B)
    var cycles = 0
    while (!dut.io.fromD2.ready.peek().litToBoolean && cycles < 32) {
      dut.clock.step(); cycles += 1
    }
    assert(cycles < 32)
    dut.clock.step()
    dut.io.fromD2.valid.poke(false.B)
    val identities = (0 until 2).map { _ =>
      cycles = 0
      while (!dut.io.iex.aluDispatch(0).valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step(); cycles += 1
      }
      assert(cycles < 16)
      val identity = dut.io.iex.aluDispatch(0).bits.uop.decoded.rob.peek()
      dut.io.iex.aluDispatch(0).ready.poke(true.B)
      dut.clock.step()
      dut.io.iex.aluDispatch(0).ready.poke(false.B)
      identity
    }
    dut.io.debugDispatchPending.expect(false.B)
    dut.io.iex.aluDispatch.foreach(_.ready.poke(true.B))
    identities
  }

  private def recover(
      dut: OOOD3S1Graph,
      cause: RecoveryCause.Type,
      trigger: RobIdentity,
      transactionId: Int): Unit = {
    dut.io.iex.recoveryEvent.bits.poke(0.U.asTypeOf(dut.io.iex.recoveryEvent.bits))
    dut.io.iex.recoveryEvent.bits.transactionId.poke(transactionId.U)
    dut.io.iex.recoveryEvent.bits.cause.poke(cause)
    dut.io.iex.recoveryEvent.bits.trigger.poke(trigger)
    dut.io.iex.recoveryEvent.valid.poke(true.B)
    var sent = false
    var applied = false
    var cycles = 0
    while (!applied && cycles < 96) {
      driveTargetAcks(dut)
      sent ||= dut.io.iex.recoveryEvent.ready.peek().litToBoolean
      applied = dut.io.recoveryToD1.apply.valid.peek().litToBoolean
      dut.clock.step()
      if (sent) dut.io.iex.recoveryEvent.valid.poke(false.B)
      cycles += 1
    }
    assert(applied, s"recovery $transactionId timed out")
    driveTargetAcks(dut)
    dut.clock.step()
  }

  test("matches a four-STID reference across canonical publish and recovery") {
    val base = ParamProfiles.W2
    val p = base.copy(ooo = base.ooo.copy(stidCount = 4,
      robGroupsPerStid = 8, brobEntriesPerStid = 8,
      gprPhysRegs = 128, gprMapQDepthPerStid = 8,
      tPhysRegs = 16, uPhysRegs = 16, tuMapQDepthPerStid = 8))
    simulate(new OOOD3S1Graph(p)) { dut =>
      clear(dut)
      val shapes = Seq(
        Seq(Shape(p = true, t = false, u = false), Shape(p = false, t = true, u = false)),
        Seq(Shape(p = false, t = false, u = true), Shape(p = false, t = false, u = false)),
        Seq(Shape(p = true, t = true, u = false), Shape(p = false, t = false, u = true)),
        Seq(Shape(p = false, t = true, u = true), Shape(p = false, t = false, u = false)))
      val ids = shapes.indices.map(stid => publish(dut, stid, shapes(stid)))

      (0 until 4).foreach { stid =>
        dut.io.ridTailSlot(stid).expect(2.U)
        dut.io.debugBrobUsed(stid).expect(2.U)
      }
      dut.io.debugTCount(0).expect(1.U)
      dut.io.debugUCount(1).expect(1.U)
      dut.io.debugTCount(2).expect(1.U)
      dut.io.debugUCount(2).expect(1.U)
      dut.io.debugTCount(3).expect(1.U)
      dut.io.debugUCount(3).expect(1.U)
      val stid0P = dut.io.debugPMap(0)(1).peek()
      assert(stid0P.litValue != 1)
      val stid1Tail = dut.io.ridTailSlot(1).peek()
      val stid2Tail = dut.io.ridTailSlot(2).peek()
      val stid1U = dut.io.debugUCount(1).peek()
      val stid2T = dut.io.debugTCount(2).peek()

      recover(dut, RecoveryCause.Branch, ids(0).head, transactionId = 0x51)
      dut.io.ridTailSlot(0).expect(1.U)
      dut.io.debugBrobUsed(0).expect(1.U)
      dut.io.debugTCount(0).expect(0.U)
      dut.io.debugPMap(0)(1).expect(stid0P)
      dut.io.ridTailSlot(1).expect(stid1Tail)
      dut.io.ridTailSlot(2).expect(stid2Tail)

      recover(dut, RecoveryCause.MemoryOrder, ids(3).head, transactionId = 0x52)
      dut.io.ridTailSlot(3).expect(0.U)
      dut.io.debugBrobUsed(3).expect(0.U)
      dut.io.debugTCount(3).expect(0.U)
      dut.io.debugUCount(3).expect(0.U)
      dut.io.ridTailSlot(1).expect(stid1Tail)
      dut.io.ridTailSlot(2).expect(stid2Tail)
      dut.io.debugUCount(1).expect(stid1U)
      dut.io.debugTCount(2).expect(stid2T)
      dut.io.debugDispatchPending.expect(false.B)
    }
  }
}
