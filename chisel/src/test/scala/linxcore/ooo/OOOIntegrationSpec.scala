package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.params.{ParamProfiles, CoreParams}
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

private object OOOIntegrationSpec {
  final case class Shape(p: Boolean, t: Boolean, u: Boolean)
  final case class PhysicalTag(index: BigInt, generation: BigInt)
  final case class Published(
      identity: RobIdentity,
      p: Option[PhysicalTag],
      t: Option[PhysicalTag],
      u: Option[PhysicalTag])
}

class OOOIntegrationSpec extends AnyFunSuite with ChiselSim {
  import OOOIntegrationSpec.{PhysicalTag, Published, Shape}
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
      shapes: Seq[Shape]): Seq[Published] = {
    require(shapes.nonEmpty)
    val tail = dut.io.ridTailSlot(stid).peek().litValue.toInt
    val generation = dut.io.ridTailGeneration(stid).peek().litValue
    val capacity = dut.p.ooo.robGroupsPerStid
    dut.io.iex.aluDispatch.foreach(_.ready.poke(false.B))
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.fromD2.bits.count.poke(shapes.length.U)
    dut.io.fromD2.bits.groupCount.poke(shapes.length.U)
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
    val published = shapes.indices.map { lane =>
      cycles = 0
      while (!dut.io.iex.aluDispatch(0).valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step(); cycles += 1
      }
      assert(cycles < 16)
      val held = dut.io.iex.aluDispatch(0).bits.peek()
      dut.clock.step()
      dut.io.iex.aluDispatch(0).valid.expect(true.B)
      dut.io.iex.aluDispatch(0).bits.expect(held)
      var destination = 0
      def nextTag(kind: OperandKind.Type): PhysicalTag = {
        val row = held.uop.destinations(destination)
        destination += 1
        kind match {
          case OperandKind.Gpr => PhysicalTag(
            row.ptag.litValue,
            row.pGeneration.litValue,
          )
          case OperandKind.T => PhysicalTag(
            row.ttag.litValue,
            row.tGeneration.litValue,
          )
          case OperandKind.U => PhysicalTag(
            row.utag.litValue,
            row.uGeneration.litValue,
          )
        }
      }
      val shape = shapes(lane)
      val observation = Published(
        held.uop.decoded.rob,
        if (shape.p) Some(nextTag(OperandKind.Gpr)) else None,
        if (shape.t) Some(nextTag(OperandKind.T)) else None,
        if (shape.u) Some(nextTag(OperandKind.U)) else None,
      )
      dut.io.iex.aluDispatch(0).ready.poke(true.B)
      dut.clock.step()
      dut.io.iex.aluDispatch(0).ready.poke(false.B)
      observation
    }
    dut.io.iex.aluDispatch.foreach(_.ready.poke(true.B))
    published
  }

  private def complete(dut: OOOD3S1Graph, identity: RobIdentity): Unit = {
    dut.io.commit.ready.poke(false.B)
    val completion = dut.io.iex.completion(0)
    completion.bits.poke(0.U.asTypeOf(completion.bits))
    completion.bits.rob.poke(identity)
    completion.valid.poke(true.B)
    completion.ready.expect(true.B)
    dut.clock.step()
    completion.valid.poke(false.B)
  }

  private def expectCommit(
      dut: OOOD3S1Graph,
      identities: Seq[RobIdentity],
      label: String): Unit = {
    dut.io.commit.ready.poke(false.B)
    var cycles = 0
    while (!dut.io.commit.valid.peek().litToBoolean && cycles < 64) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < 64, s"timed out waiting for $label commit")
    dut.io.commit.bits.count.expect(identities.length.U)
    identities.zipWithIndex.foreach { case (identity, lane) =>
      dut.io.commit.bits.entries(lane).rob.expect(identity)
    }
    val held = dut.io.commit.bits.peek()
    dut.clock.step(2)
    dut.io.commit.valid.expect(true.B)
    dut.io.commit.bits.expect(held)
    dut.io.commit.ready.poke(true.B)
    dut.clock.step()
    dut.io.commit.valid.expect(false.B)
  }

  private def expectNoCommit(dut: OOOD3S1Graph, cycles: Int = 3): Unit = {
    dut.io.commit.ready.poke(false.B)
    (0 until cycles).foreach { _ =>
      dut.io.commit.valid.expect(false.B)
      dut.clock.step()
    }
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

  test("commits one completed canonical OOO publication exactly once") {
    val base = ParamProfiles.W2
    val p = base.copy(ooo = base.ooo.copy(
      robGroupsPerStid = 8,
      robBankCount = 2,
      brobEntriesPerStid = 8,
      gprPhysRegs = 64,
      gprMapQDepthPerStid = 8,
      tPhysRegs = 8,
      uPhysRegs = 8,
      tuMapQDepthPerStid = 8,
    ))
    simulate(new OOOD3S1Graph(p)) { dut =>
      clear(dut)
      dut.io.commit.ready.poke(false.B)
      val identity = publish(
        dut,
        stid = 0,
        Seq(Shape(p = true, t = false, u = false)),
      ).head.identity
      dut.io.commit.valid.expect(false.B)

      val completion = dut.io.iex.completion(0)
      completion.bits.poke(0.U.asTypeOf(completion.bits))
      completion.bits.rob.poke(identity)
      completion.valid.poke(true.B)
      completion.ready.expect(true.B)
      dut.clock.step()
      completion.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.commit.valid.peek().litToBoolean && cycles < 64) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 64)
      dut.io.commit.bits.count.expect(1.U)
      dut.io.commit.bits.entries(0).rob.expect(identity)
      val heldCommit = dut.io.commit.bits.peek()
      dut.clock.step(2)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.expect(heldCommit)

      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      (0 until 3).foreach { _ =>
        dut.io.commit.valid.expect(false.B)
        dut.clock.step()
      }
    }
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
      val rows = shapes.indices.map(stid => publish(dut, stid, shapes(stid)))

      (0 until 4).foreach { stid =>
        dut.io.ridTailSlot(stid).expect(2.U)
      }
      complete(dut, rows(1).head.identity)
      expectCommit(dut, Seq(rows(1).head.identity), "unrelated STID1 head")
      complete(dut, rows(1)(1).identity)
      expectCommit(dut, Seq(rows(1)(1).identity), "unrelated STID1 younger")
      complete(dut, rows(2).head.identity)
      expectCommit(dut, Seq(rows(2).head.identity), "unrelated STID2 head")
      val stid1Tail = dut.io.ridTailSlot(1).peek()
      val stid2Tail = dut.io.ridTailSlot(2).peek()

      recover(dut, RecoveryCause.Branch, rows(0).head.identity, transactionId = 0x51)
      dut.io.ridTailSlot(0).expect(1.U)
      dut.io.ridTailSlot(1).expect(stid1Tail)
      dut.io.ridTailSlot(2).expect(stid2Tail)

      complete(dut, rows(0)(1).identity)
      expectNoCommit(dut)
      complete(dut, rows(0).head.identity)
      expectCommit(dut, Seq(rows(0).head.identity), "STID0 survivor")
      val stid0Probe = publish(
        dut,
        stid = 0,
        Seq(Shape(p = false, t = true, u = false)),
      ).head
      assert(stid0Probe.t == rows(0)(1).t)

      recover(dut, RecoveryCause.MemoryOrder, rows(3).head.identity,
        transactionId = 0x52)
      dut.io.ridTailSlot(3).expect(0.U)
      dut.io.ridTailSlot(1).expect(stid1Tail)
      dut.io.ridTailSlot(2).expect(stid2Tail)

      rows(3).foreach(row => complete(dut, row.identity))
      expectNoCommit(dut)
      val stid3Probe = publish(
        dut,
        stid = 3,
        Seq(Shape(p = false, t = true, u = true)),
      ).head
      assert(stid3Probe.t == rows(3).head.t)
      assert(stid3Probe.u == rows(3).head.u)
    }
  }
}
