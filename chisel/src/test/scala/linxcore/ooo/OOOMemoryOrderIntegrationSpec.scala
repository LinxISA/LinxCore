package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.ParamProfiles
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

private object OOOMemoryOrderIntegrationSpec {
  final case class Id(peId: BigInt, stid: BigInt, ridSlot: BigInt,
      ridGeneration: BigInt, memberIndex: BigInt, residentGeneration: BigInt,
      bid: BigInt, brobGeneration: BigInt)
  final case class Observed(transactionId: BigInt, requestCount: BigInt,
      firstLsid: BigInt, firstLid: BigInt, firstSid: BigInt,
      yostValid: Boolean, yoldValid: Boolean, id: Id)
}

class OOOMemoryOrderIntegrationSpec extends AnyFunSuite with ChiselSim {
  import OOOMemoryOrderIntegrationSpec.{Id, Observed}
  private val p = {
    val base = ParamProfiles.W2
    base.copy(ooo = base.ooo.copy(stidCount = 2,
      robGroupsPerStid = 8,
      robBankCount = 2,
      brobEntriesPerStid = 8,
      gprPhysRegs = 64,
      gprMapQDepthPerStid = 8,
      tPhysRegs = 8,
      uPhysRegs = 8,
      tuMapQDepthPerStid = 8))
  }

  private def clear(dut: OOOD3S1Graph): Unit = {
    dut.io.fromD2.valid.poke(false.B)
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.iex.aluDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.bruDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.aguDispatch.foreach(_.ready.poke(false.B))
    dut.io.iex.storeDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.systemDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.cmdDispatch.foreach(_.ready.poke(true.B))
    dut.io.iex.robNoflushReady.valid.poke(false.B)
    dut.io.iex.robNoflushReady.bits.poke(
      0.U.asTypeOf(dut.io.iex.robNoflushReady.bits))
    dut.io.iex.robNoflush.ready.poke(true.B)
    dut.io.iex.robResolve.foreach { port =>
      port.valid.poke(false.B)
      port.bits.poke(0.U.asTypeOf(port.bits))
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

  private def submitLoad(
      dut: OOOD3S1Graph,
      instructionId: Int,
      stid: Int = 0): Unit = {
    val tail = dut.io.ridTailSlot(stid).peek().litValue
    val generation = dut.io.ridTailGeneration(stid).peek().litValue
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.fromD2.bits.count.poke(1.U)
    dut.io.fromD2.bits.groupCount.poke(1.U)
    dut.io.fromD2.bits.groups(0).valid.poke(true.B)
    dut.io.fromD2.bits.groups(0).peId.poke(1.U)
    dut.io.fromD2.bits.groups(0).stid.poke(stid.U)
    dut.io.fromD2.bits.groups(0).ridSlot.poke(tail.U)
    dut.io.fromD2.bits.groups(0).ridGeneration.poke(generation.U)
    val row = dut.io.fromD2.bits.entries(0).uop
    row.valid.poke(true.B)
    row.instruction.parent.identity.peId.poke(1.U)
    row.instruction.parent.identity.stid.poke(stid.U)
    row.instruction.parent.identity.instructionId.poke(instructionId.U)
    row.instruction.parent.identity.epoch.poke(3.U)
    row.rob.peId.poke(1.U)
    row.rob.stid.poke(stid.U)
    row.rob.ridSlot.poke(tail.U)
    row.rob.ridGeneration.poke(generation.U)
    row.blockStart.poke(true.B)
    row.blockStop.poke(true.B)
    row.uopClass.poke(UopClass.Agu)
    row.memory.valid.poke(true.B)
    row.memory.isLoad.poke(true.B)
    row.memory.requestCount.poke(1.U)
    dut.io.fromD2.valid.poke(true.B)
    var cycles = 0
    while (!dut.io.fromD2.ready.peek().litToBoolean && cycles < 32) {
      dut.clock.step(); cycles += 1
    }
    assert(cycles < 32)
    dut.clock.step()
    dut.io.fromD2.valid.poke(false.B)
  }

  private def observeLoad(dut: OOOD3S1Graph): Observed = {
    var cycles = 0
    while (!dut.io.iex.aguDispatch(0).valid.peek().litToBoolean && cycles < 32) {
      dut.clock.step(); cycles += 1
    }
    assert(cycles < 32)
    val bits = dut.io.iex.aguDispatch(0).bits
    val id = bits.uop.decoded.rob
    Observed(bits.transactionId.peek().litValue,
      bits.memoryOrder.requestCount.peek().litValue,
      bits.memoryOrder.firstLsid.peek().litValue,
      bits.memoryOrder.firstLid.peek().litValue,
      bits.memoryOrder.firstSid.peek().litValue,
      bits.memoryOrder.yostValid.peek().litToBoolean,
      bits.memoryOrder.yoldValid.peek().litToBoolean,
      Id(id.peId.peek().litValue, id.stid.peek().litValue,
        id.ridSlot.peek().litValue, id.ridGeneration.peek().litValue,
        id.memberIndex.peek().litValue, id.residentGeneration.peek().litValue,
        id.bid.peek().litValue, id.brobGeneration.peek().litValue))
  }

  private def publishLoad(
      dut: OOOD3S1Graph,
      instructionId: Int,
      stid: Int = 0): Observed = {
    submitLoad(dut, instructionId, stid)
    observeLoad(dut)
  }

  private def submitSystem(dut: OOOD3S1Graph, instructionId: Int): Unit = {
    val tail = dut.io.ridTailSlot(0).peek().litValue
    val generation = dut.io.ridTailGeneration(0).peek().litValue
    dut.io.fromD2.bits.poke(0.U.asTypeOf(dut.io.fromD2.bits))
    dut.io.fromD2.bits.count.poke(1.U)
    dut.io.fromD2.bits.groupCount.poke(1.U)
    dut.io.fromD2.bits.groups(0).valid.poke(true.B)
    dut.io.fromD2.bits.groups(0).peId.poke(1.U)
    dut.io.fromD2.bits.groups(0).stid.poke(0.U)
    dut.io.fromD2.bits.groups(0).ridSlot.poke(tail.U)
    dut.io.fromD2.bits.groups(0).ridGeneration.poke(generation.U)
    val row = dut.io.fromD2.bits.entries(0).uop
    row.valid.poke(true.B)
    row.instruction.parent.identity.peId.poke(1.U)
    row.instruction.parent.identity.stid.poke(0.U)
    row.instruction.parent.identity.instructionId.poke(instructionId.U)
    row.instruction.parent.identity.epoch.poke(3.U)
    row.rob.peId.poke(1.U)
    row.rob.stid.poke(0.U)
    row.rob.ridSlot.poke(tail.U)
    row.rob.ridGeneration.poke(generation.U)
    row.blockStart.poke(true.B)
    row.blockStop.poke(true.B)
    row.uopClass.poke(UopClass.System)
    dut.io.fromD2.valid.poke(true.B)
    var cycles = 0
    while (!dut.io.fromD2.ready.peek().litToBoolean && cycles < 32) {
      dut.clock.step(); cycles += 1
    }
    assert(cycles < 32)
    dut.clock.step()
    dut.io.fromD2.valid.poke(false.B)
  }

  private def pokeId(target: RobIdentity, id: Id): Unit = {
    target.peId.poke(id.peId.U); target.stid.poke(id.stid.U)
    target.ridSlot.poke(id.ridSlot.U)
    target.ridGeneration.poke(id.ridGeneration.U)
    target.memberIndex.poke(id.memberIndex.U)
    target.residentGeneration.poke(id.residentGeneration.U)
    target.bid.poke(id.bid.U); target.brobGeneration.poke(id.brobGeneration.U)
  }

  private def driveTargetAcks(dut: OOOD3S1Graph): Unit = {
    Seq(dut.io.recoveryToD1, dut.io.iex.recovery, dut.io.recoveryToIfu,
      dut.io.recoveryToCtu, dut.io.recoveryToLsu).foreach { target =>
      target.prepared.valid.poke(target.prepare.valid.peek())
      if (target.prepare.valid.peek().litToBoolean) {
        target.prepared.bits.poke(target.prepare.bits.peek())
      }
    }
  }

  private def recover(dut: OOOD3S1Graph, id: Id, transactionId: Int): Unit = {
    dut.io.iex.recoveryEvent.bits.poke(0.U.asTypeOf(dut.io.iex.recoveryEvent.bits))
    dut.io.iex.recoveryEvent.bits.transactionId.poke(transactionId.U)
    dut.io.iex.recoveryEvent.bits.cause.poke(RecoveryCause.MemoryOrder)
    pokeId(dut.io.iex.recoveryEvent.bits.trigger, id)
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
    assert(sent, s"recovery event $transactionId was not accepted")
    assert(applied, s"recovery $transactionId timed out")
  }

  test("publishes DEC memory demand as stable full-order metadata") {
    simulate(new OOOD3S1Graph(p)) { dut =>
      clear(dut)
      val first = publishLoad(dut, instructionId = 100)
      assert(first.requestCount == 1)
      assert(first.firstLsid == 0)
      assert(first.firstLid == 0)
      assert(first.firstSid == 0)
      assert(!first.yostValid)
      assert(!first.yoldValid)
      val held = dut.io.iex.aguDispatch(0).bits.peek()
      dut.clock.step(3)
      dut.io.iex.aguDispatch(0).valid.expect(true.B)
      dut.io.iex.aguDispatch(0).bits.expect(held)
      dut.io.iex.aguDispatch(0).ready.poke(true.B)
      dut.clock.step()

      val peerFirst = publishLoad(dut, instructionId = 200, stid = 1)
      assert(peerFirst.firstLsid == 0)
      assert(peerFirst.firstLid == 0)
      dut.io.iex.aguDispatch(0).ready.poke(true.B)
      dut.clock.step()

      val second = publishLoad(dut, instructionId = 101)
      assert(second.firstLsid == 1)
      assert(second.firstLid == 1)
      assert(second.firstSid == 0)
      assert(!second.yostValid)
      assert(second.yoldValid)
      assert(second.transactionId > first.transactionId)
      dut.io.iex.aguDispatch(0).ready.poke(true.B)
      dut.clock.step()
      recover(dut, second.id, transactionId = 0x91)

      val peerSecond = publishLoad(dut, instructionId = 201, stid = 1)
      assert(peerSecond.firstLsid == 1,
        "target-STID recovery must preserve peer LSID")
      assert(peerSecond.firstLid == 1,
        "target-STID recovery must preserve peer LID")
      assert(peerSecond.yoldValid)
      dut.io.iex.aguDispatch(0).ready.poke(true.B)
      dut.clock.step()

      val replacement = publishLoad(dut, instructionId = 102)
      assert(replacement.firstLsid == 1)
      assert(replacement.firstLid == 1)
      assert(replacement.firstSid == 0)
      assert(replacement.yoldValid)
      assert(replacement.transactionId > second.transactionId,
        "dispatch transaction IDs must not rewind with memory-order recovery")
    }
  }

  test("common D3 backpressure preserves one unpublished memory-order prefix") {
    simulate(new OOOD3S1Graph(p)) { dut =>
      clear(dut)
      val first = publishLoad(dut, instructionId = 300)
      val held = dut.io.iex.aguDispatch(0).bits.peek()
      val tailAfterFirst = dut.io.ridTailSlot(0).peek()

      submitLoad(dut, instructionId = 301)
      dut.clock.step(3)
      dut.io.ridTailSlot(0).expect(tailAfterFirst)
      dut.io.iex.aguDispatch(0).valid.expect(true.B)
      dut.io.iex.aguDispatch(0).bits.expect(held)

      dut.io.iex.aguDispatch(0).ready.poke(true.B)
      dut.clock.step()
      val second = observeLoad(dut)
      assert(first.firstLsid == 0)
      assert(second.firstLsid == 1)
      assert(second.firstLid == 1)
      assert(second.transactionId > first.transactionId)
      dut.io.ridTailSlot(0).expect(2.U)
    }
  }

  test("ROB head and exact NFRDY proof authorize one system member once") {
    simulate(new OOOD3S1Graph(p)) { dut =>
      clear(dut)
      dut.io.iex.systemDispatch.foreach(_.ready.poke(false.B))
      dut.io.iex.robNoflush.ready.poke(false.B)
      submitSystem(dut, instructionId = 400)

      var cycles = 0
      while (!dut.io.iex.systemDispatch(0).valid.peek().litToBoolean &&
        cycles < 32) {
        dut.clock.step(); cycles += 1
      }
      assert(cycles < 32)
      val dispatch = dut.io.iex.systemDispatch(0).bits
      dut.io.iex.robNoflush.valid.expect(false.B,
        "ROB head shape is not a legality/drain proof")

      dut.io.iex.robNoflushReady.bits.transactionId.poke(
        dispatch.transactionId.peek())
      dut.io.iex.robNoflushReady.bits.instruction.poke(
        dispatch.uop.decoded.instruction.parent.identity.peek())
      dut.io.iex.robNoflushReady.bits.rob.poke(
        dispatch.uop.decoded.rob.peek())
      dut.io.iex.robNoflushReady.valid.poke(true.B)
      dut.io.iex.robNoflush.valid.expect(true.B)
      val permit = dut.io.iex.robNoflush.bits.peek()
      dut.clock.step(2)
      dut.io.iex.robNoflush.valid.expect(true.B)
      dut.io.iex.robNoflush.bits.expect(permit)

      dut.io.iex.robNoflush.ready.poke(true.B)
      dut.clock.step()
      dut.io.iex.robNoflush.valid.expect(false.B,
        "the unresolved resident head must not receive a second permit")
      dut.io.iex.robNoflushReady.ready.expect(true.B)
    }
  }
}
