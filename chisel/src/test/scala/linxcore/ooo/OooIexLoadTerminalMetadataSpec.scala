package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

import linxcore.common.{CoreParams, DestinationKind, ScalarLsuParams}

class OooIexLoadTerminalMetadataSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 4,
    instructionDecodeWidth = 2,
    decodedUopWidth = 2,
    renameWidth = 2,
    dispatchWidth = 2,
    retireGroupWidth = 2,
    robGroupsPerStid = 8,
    robBankCount = 2,
    robRecoveryScanGroupsPerCycle = 2,
    robNonFlushScanGroupsPerCycle = 2,
    brobEntriesPerStid = 16,
    pcBufferEntries = 16,
    pcBankCount = 2,
    pcRecoveryScanGroupsPerCycle = 2,
    pcWritePorts = 2,
    iqBankCount = 2,
    iqEntriesPerBank = 4,
    iqFreeSelectLeafEntries = 2,
    tuRetireSourceDepthPerStid = 16,
    lsidWidth = 40)

  private val core = CoreParams(
    robEntries = 8,
    lsidWidth = 40,
    scalarLsu = ScalarLsuParams(
      stqEntries = 4,
      liqEntries = 4,
      loadReturnPipeCount = 3,
      stidCount = 4))

  private def defaults(dut: OooIexLoadTerminalMetadata): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.alloc.valid.poke(false.B)
    dut.io.alloc.bits.poke(0.U.asTypeOf(dut.io.alloc.bits))
    dut.io.rebind.valid.poke(false.B)
    dut.io.rebind.bits.poke(0.U.asTypeOf(dut.io.rebind.bits))
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.result.ready.poke(false.B)
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
  }

  private def pokeMember(
      member: RobMemberKey,
      ridSlot: Int,
      memberIndex: Int = 1,
      stid: Int = 1): Unit = {
    member.poke(0.U.asTypeOf(member))
    member.group.valid.poke(true.B)
    member.group.peId.poke(3.U)
    member.group.stid.poke(stid.U)
    member.group.ridSlot.poke(ridSlot.U)
    member.group.ridGeneration.poke(5.U)
    member.bid.valid.poke(true.B)
    member.bid.value.poke((ridSlot + 4).U)
    member.brobGeneration.poke(9.U)
    member.memberIndex.poke(memberIndex.U)
    member.residentGeneration.poke(11.U)
  }

  private def pokeAttemptFromLoad(
      attempt: linxcore.lsu.LoadAttemptIdentity,
      load: OooIexLoadGeneration): Unit = {
    attempt.poke(0.U.asTypeOf(attempt))
    attempt.valid.poke(true.B)
    attempt.producer.valid.poke(true.B)
    attempt.producer.peId.poke(load.producer.group.peId.peek())
    attempt.producer.stid.poke(load.producer.group.stid.peek())
    attempt.producer.nativeBidValid.poke(true.B)
    attempt.producer.nativeBid.poke(load.producer.bid.value.peek())
    attempt.producer.brobGeneration.poke(
      load.producer.brobGeneration.peek())
    attempt.producer.ridSlot.poke(load.producer.group.ridSlot.peek())
    attempt.producer.ridGeneration.poke(
      load.producer.group.ridGeneration.peek())
    attempt.producer.memberIndex.poke(load.producer.memberIndex.peek())
    attempt.producer.residentGeneration.poke(
      load.producer.residentGeneration.peek())
    attempt.generation.poke(load.generation.peek())
  }

  private def pokeAlloc(
      dut: OooIexLoadTerminalMetadata,
      slot: Int,
      rowGeneration: Int,
      ridSlot: Int,
      attemptGeneration: Int,
      stid: Int = 1): Unit = {
    val alloc = dut.io.alloc.bits
    alloc.poke(0.U.asTypeOf(alloc))
    alloc.loadId.valid.poke(true.B)
    alloc.loadId.slot.poke(slot.U)
    alloc.loadId.generation.poke(rowGeneration.U)
    alloc.load.valid.poke(true.B)
    pokeMember(alloc.load.producer, ridSlot, stid = stid)
    alloc.load.generation.poke(attemptGeneration.U)

    val row = alloc.request.execute.i2.row
    row.schedule.valid.poke(true.B)
    row.schedule.peId.poke(3.U)
    row.schedule.stid.poke(stid.U)
    row.schedule.epoch.poke(7.U)
    pokeMember(row.schedule.member, ridSlot, stid = stid)
    row.schedule.destinations(0).valid.poke(true.B)
    row.schedule.destinations(0).kind.poke(DestinationKind.Gpr)
    row.schedule.destinations(0).atag.poke(6.U)
    row.schedule.destinations(0).ptag.poke((31 + slot).U)
    row.schedule.destinations(0).ptagGeneration.poke(4.U)
    row.payload.previousPDestinations(0).valid.poke(true.B)
    row.payload.previousPDestinations(0).ptag.poke((21 + slot).U)
    row.payload.previousPDestinations(0).ptagGeneration.poke(3.U)
    alloc.request.destination.poke(row.schedule.destinations(0).peek())
    alloc.request.address.poke((0x8000 + slot * 8).U)
    alloc.request.accessBytes.poke(8.U)
    pokeAttemptFromLoad(alloc.attempt, alloc.load)
    dut.io.alloc.valid.poke(true.B)
  }

  private def acceptAlloc(dut: OooIexLoadTerminalMetadata): Unit = {
    dut.io.alloc.ready.expect(true.B)
    dut.clock.step()
    dut.io.alloc.valid.poke(false.B)
  }

  private def pokeCompletion(
      dut: OooIexLoadTerminalMetadata,
      slot: Int,
      rowGeneration: Int,
      ridSlot: Int,
      attemptGeneration: Int,
      fault: Boolean = false,
      stid: Int = 1): Unit = {
    val completion = dut.io.completion.bits
    completion.poke(0.U.asTypeOf(completion))
    completion.peId.poke(3.U)
    completion.stid.poke(stid.U)
    completion.tid.poke(stid.U)
    completion.payload.valid.poke(true.B)
    completion.payload.loadId.valid.poke(true.B)
    completion.payload.loadId.slot.poke(slot.U)
    completion.payload.loadId.generation.poke(rowGeneration.U)
    completion.payload.attempt.valid.poke(true.B)
    completion.payload.attempt.producer.valid.poke(true.B)
    completion.payload.attempt.producer.peId.poke(3.U)
    completion.payload.attempt.producer.stid.poke(stid.U)
    completion.payload.attempt.producer.nativeBidValid.poke(true.B)
    completion.payload.attempt.producer.nativeBid.poke((ridSlot + 4).U)
    completion.payload.attempt.producer.brobGeneration.poke(9.U)
    completion.payload.attempt.producer.ridSlot.poke(ridSlot.U)
    completion.payload.attempt.producer.ridGeneration.poke(5.U)
    completion.payload.attempt.producer.memberIndex.poke(1.U)
    completion.payload.attempt.producer.residentGeneration.poke(11.U)
    completion.payload.attempt.generation.poke(attemptGeneration.U)
    completion.payload.dst.valid.poke(true.B)
    completion.payload.dst.kind.poke(DestinationKind.Gpr)
    completion.payload.dst.archTag.poke(6.U)
    completion.payload.dst.physTag.poke((31 + slot).U)
    completion.payload.dst.oldPhysTag.poke((21 + slot).U)
    completion.payload.data.poke(
      (if (fault) BigInt(0) else BigInt("1122334455667788", 16)).U)
    completion.payload.faultValid.poke(fault.B)
    completion.payload.faultCause.poke((if (fault) 0x55 else 0).U)
    dut.io.completion.valid.poke(true.B)
  }

  test("retains exact terminal metadata under backpressure and clears only on atomic fire") {
    simulate(new OooIexLoadTerminalMetadata(p, core)) { dut =>
      defaults(dut)
      pokeAlloc(dut, slot = 2, rowGeneration = 1, ridSlot = 3,
        attemptGeneration = 7)
      acceptAlloc(dut)
      dut.io.occupied.expect(1.U)

      pokeCompletion(dut, slot = 2, rowGeneration = 1, ridSlot = 3,
        attemptGeneration = 7)
      dut.io.result.valid.expect(true.B)
      dut.io.completion.ready.expect(false.B)
      dut.io.result.bits.agu.destination.ptag.expect(33.U)
      dut.io.result.bits.agu.destination.ptagGeneration.expect(4.U)
      dut.io.result.bits.data.expect(BigInt("1122334455667788", 16).U)
      dut.clock.step(3)
      dut.io.occupied.expect(1.U)
      dut.io.result.valid.expect(true.B)

      // The canonical LIQ slot cannot be reused while its terminal metadata
      // still owns the older physical row lease.
      pokeAlloc(dut, slot = 2, rowGeneration = 0, ridSlot = 4,
        attemptGeneration = 8)
      dut.io.alloc.ready.expect(false.B)
      dut.io.allocRejected.valid.expect(true.B)
      dut.io.allocRejected.bits.resident.expect(true.B)
      dut.io.alloc.valid.poke(false.B)

      dut.io.result.ready.poke(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.result.ready.poke(false.B)
      dut.io.empty.expect(true.B)
    }
  }

  test("requires exact rebind generation and rejects the stale attempt after replay") {
    simulate(new OooIexLoadTerminalMetadata(p, core)) { dut =>
      defaults(dut)
      pokeAlloc(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 5)
      acceptAlloc(dut)

      val rebind = dut.io.rebind.bits
      rebind.poke(0.U.asTypeOf(rebind))
      rebind.loadId.valid.poke(true.B)
      rebind.loadId.slot.poke(1.U)
      rebind.currentLoad.valid.poke(true.B)
      pokeMember(rebind.currentLoad.producer, ridSlot = 2)
      rebind.currentLoad.generation.poke(5.U)
      pokeAttemptFromLoad(rebind.currentAttempt, rebind.currentLoad)
      rebind.nextLoad.valid.poke(true.B)
      pokeMember(rebind.nextLoad.producer, ridSlot = 2)
      rebind.nextLoad.generation.poke(6.U)
      pokeAttemptFromLoad(rebind.nextAttempt, rebind.nextLoad)
      dut.io.rebind.valid.poke(true.B)
      dut.io.rebind.ready.expect(true.B)
      dut.clock.step()
      dut.io.rebind.valid.poke(false.B)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 5)
      dut.io.result.ready.poke(true.B)
      dut.io.result.valid.expect(false.B)
      dut.io.completion.ready.expect(false.B)
      dut.io.completionRejected.valid.expect(true.B)
      dut.io.completionRejected.bits.attemptExact.expect(false.B)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 6)
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.load.generation.expect(6.U)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.empty.expect(true.B)
    }
  }

  test("recovery fences terminal publication and prunes only the exact killed member") {
    simulate(new OooIexLoadTerminalMetadata(p, core)) { dut =>
      defaults(dut)
      pokeAlloc(dut, slot = 0, rowGeneration = 0, ridSlot = 1,
        attemptGeneration = 1)
      acceptAlloc(dut)
      pokeAlloc(dut, slot = 1, rowGeneration = 0, ridSlot = 3,
        attemptGeneration = 2)
      acceptAlloc(dut)
      dut.io.occupied.expect(2.U)

      val plan = dut.io.recoveryPrepare.bits
      plan.poke(0.U.asTypeOf(plan))
      plan.valid.poke(true.B)
      plan.oldHead.valid.poke(true.B)
      plan.oldHead.peId.poke(3.U)
      plan.oldHead.stid.poke(1.U)
      plan.oldHead.ridSlot.poke(1.U)
      plan.oldHead.ridGeneration.poke(5.U)
      plan.oldOccupied.poke(3.U)
      plan.newOccupied.poke(2.U)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryKilledMask.expect(2.U)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 3,
        attemptGeneration = 2, fault = true)
      dut.io.result.ready.poke(true.B)
      dut.io.result.valid.expect(false.B)
      dut.io.completion.ready.expect(false.B)

      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      dut.io.occupied.expect(1.U)

      pokeCompletion(dut, slot = 0, rowGeneration = 0, ridSlot = 1,
        attemptGeneration = 1, fault = true)
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.faultValid.expect(true.B)
      dut.io.result.bits.faultCause.expect(0x55.U)
      dut.io.result.bits.data.expect(0.U)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.empty.expect(true.B)
    }
  }
}
