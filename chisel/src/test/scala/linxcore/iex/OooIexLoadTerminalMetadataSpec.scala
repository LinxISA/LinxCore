package linxcore.iex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

import linxcore.common.{CoreParams, DestinationKind, ScalarLsuParams}
import linxcore.ooo._
import linxcore.top.interface.RecoveryPhase

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
    pcBankCount = 4,
    pcRecoveryScanGroupsPerCycle = 2,
    pcWritePorts = 3,
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

  test("derives the W4 two-lane terminal geometry from the canonical LSU profile") {
    val twoPipeCore = core.copy(
      scalarLsu = core.scalarLsu.copy(loadReturnPipeCount = 2))

    simulate(new OooIexLoadTerminalMetadata(p, twoPipeCore)) { dut =>
      assert(dut.io.speculativeWakeup.length == 2)
      assert(dut.io.loadCancel.length == 2)
      assert(dut.io.loadBypass.length == 2)
    }
  }

  private def defaults(dut: OooIexLoadTerminalMetadata): Unit = {
    dut.io.alloc.valid.poke(false.B)
    dut.io.alloc.bits.poke(0.U.asTypeOf(dut.io.alloc.bits))
    dut.io.rebind.valid.poke(false.B)
    dut.io.rebind.bits.poke(0.U.asTypeOf(dut.io.rebind.bits))
    dut.io.attemptLaunch.valid.poke(false.B)
    dut.io.attemptLaunch.bits.poke(
      0.U.asTypeOf(dut.io.attemptLaunch.bits))
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.result.ready.poke(false.B)
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryApply.valid.poke(false.B)
    dut.io.recoveryApply.bits.poke(
      0.U.asTypeOf(dut.io.recoveryApply.bits))
    dut.io.recoveryAbort.valid.poke(false.B)
    dut.io.recoveryAbort.bits.poke(
      0.U.asTypeOf(dut.io.recoveryAbort.bits))
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
    attempt.transactionValue.poke(load.transaction.value.peek())
    attempt.transactionGeneration.poke(load.transaction.generation.peek())
    attempt.generation.poke(load.generation.peek())
  }

  private def pokeRebind(
      dut: OooIexLoadTerminalMetadata,
      slot: Int,
      ridSlot: Int,
      currentGeneration: Int,
      nextGeneration: Int,
      transactionValue: BigInt,
      transactionGeneration: BigInt): Unit = {
    val rebind = dut.io.rebind.bits
    rebind.poke(0.U.asTypeOf(rebind))
    rebind.loadId.valid.poke(true.B)
    rebind.loadId.slot.poke(slot.U)
    rebind.currentLoad.valid.poke(true.B)
    pokeMember(rebind.currentLoad.producer, ridSlot)
    rebind.currentLoad.transaction.value.poke(transactionValue.U)
    rebind.currentLoad.transaction.generation.poke(transactionGeneration.U)
    rebind.currentLoad.generation.poke(currentGeneration.U)
    pokeAttemptFromLoad(rebind.currentAttempt, rebind.currentLoad)
    rebind.nextLoad.valid.poke(true.B)
    pokeMember(rebind.nextLoad.producer, ridSlot)
    rebind.nextLoad.transaction.value.poke(transactionValue.U)
    rebind.nextLoad.transaction.generation.poke(transactionGeneration.U)
    rebind.nextLoad.generation.poke(nextGeneration.U)
    pokeAttemptFromLoad(rebind.nextAttempt, rebind.nextLoad)
    dut.io.rebind.valid.poke(true.B)
  }

  private def pokeAlloc(
      dut: OooIexLoadTerminalMetadata,
      slot: Int,
      rowGeneration: Int,
      ridSlot: Int,
      attemptGeneration: Int,
      stid: Int = 1,
      transactionValue: BigInt = 0,
      transactionGeneration: BigInt = 0): Unit = {
    val alloc = dut.io.alloc.bits
    alloc.poke(0.U.asTypeOf(alloc))
    alloc.loadId.valid.poke(true.B)
    alloc.loadId.slot.poke(slot.U)
    alloc.loadId.generation.poke(rowGeneration.U)
    alloc.load.valid.poke(true.B)
    pokeMember(alloc.load.producer, ridSlot, stid = stid)
    alloc.load.transaction.value.poke(transactionValue.U)
    alloc.load.transaction.generation.poke(transactionGeneration.U)
    alloc.load.generation.poke(attemptGeneration.U)

    val row = alloc.request.execute.i2.row
    row.schedule.valid.poke(true.B)
    row.schedule.peId.poke(3.U)
    row.schedule.stid.poke(stid.U)
    row.schedule.epoch.poke(7.U)
    row.schedule.memoryTransactionValid.poke(true.B)
    row.schedule.memoryTransaction.value.poke(transactionValue.U)
    row.schedule.memoryTransaction.generation.poke(transactionGeneration.U)
    row.schedule.initialLoadAttemptValid.poke(true.B)
    row.schedule.initialLoadAttemptGeneration.poke(attemptGeneration.U)
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
      stid: Int = 1,
      transactionValue: BigInt = 0,
      transactionGeneration: BigInt = 0,
      pipeIndex: Int = 0): Unit = {
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
    completion.payload.attempt.transactionValue.poke(transactionValue.U)
    completion.payload.attempt.transactionGeneration.poke(
      transactionGeneration.U)
    completion.payload.attempt.generation.poke(attemptGeneration.U)
    completion.payload.transactionValid.poke(true.B)
    completion.payload.transactionValue.poke(transactionValue.U)
    completion.payload.transactionGeneration.poke(transactionGeneration.U)
    completion.payload.pipeIndex.poke(pipeIndex.U)
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

  test("rejects allocation that does not match the IQ-retained transaction and initial attempt") {
    simulate(new OooIexLoadTerminalMetadata(p, core)) { dut =>
      defaults(dut)
      pokeAlloc(dut, slot = 0, rowGeneration = 0, ridSlot = 1,
        attemptGeneration = 7, transactionValue = 19,
        transactionGeneration = 3)

      dut.io.alloc.bits.request.execute.i2.row.schedule.memoryTransaction.value
        .poke(20.U)
      dut.io.alloc.ready.expect(false.B)
      dut.io.allocRejected.valid.expect(true.B)
      dut.io.allocRejected.bits.producerExact.expect(false.B)

      dut.io.alloc.bits.request.execute.i2.row.schedule.memoryTransaction.value
        .poke(19.U)
      dut.io.alloc.bits.request.execute.i2.row.schedule
        .initialLoadAttemptGeneration.poke(8.U)
      dut.io.alloc.ready.expect(false.B)
      dut.io.allocRejected.valid.expect(true.B)
      dut.io.allocRejected.bits.attemptExact.expect(false.B)
    }
  }

  test("requires exact rebind generation and rejects the stale attempt after replay") {
    simulate(new OooIexLoadTerminalMetadata(p, core)) { dut =>
      defaults(dut)
      pokeAlloc(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 5, transactionValue = 19,
        transactionGeneration = 3)
      acceptAlloc(dut)

      val rebind = dut.io.rebind.bits
      rebind.poke(0.U.asTypeOf(rebind))
      rebind.loadId.valid.poke(true.B)
      rebind.loadId.slot.poke(1.U)
      rebind.currentLoad.valid.poke(true.B)
      pokeMember(rebind.currentLoad.producer, ridSlot = 2)
      rebind.currentLoad.transaction.value.poke(19.U)
      rebind.currentLoad.transaction.generation.poke(3.U)
      rebind.currentLoad.generation.poke(5.U)
      pokeAttemptFromLoad(rebind.currentAttempt, rebind.currentLoad)
      rebind.nextLoad.valid.poke(true.B)
      pokeMember(rebind.nextLoad.producer, ridSlot = 2)
      rebind.nextLoad.transaction.value.poke(20.U)
      rebind.nextLoad.transaction.generation.poke(3.U)
      rebind.nextLoad.generation.poke(6.U)
      pokeAttemptFromLoad(rebind.nextAttempt, rebind.nextLoad)
      dut.io.rebind.valid.poke(true.B)
      dut.io.rebind.ready.expect(false.B)
      dut.io.rebindRejected.valid.expect(true.B)

      rebind.nextLoad.transaction.value.poke(19.U)
      pokeAttemptFromLoad(rebind.nextAttempt, rebind.nextLoad)
      dut.io.rebind.ready.expect(true.B)
      dut.clock.step()
      dut.io.rebind.valid.poke(false.B)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 7, transactionValue = 19,
        transactionGeneration = 3)
      dut.io.result.ready.poke(true.B)
      dut.io.result.valid.expect(false.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.occupied.expect(1.U)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 4, transactionValue = 19,
        transactionGeneration = 3)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.occupied.expect(1.U)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 5, transactionValue = 20,
        transactionGeneration = 3)
      dut.io.completion.ready.expect(true.B)
      dut.io.completionRejected.bits.transactionExact.expect(false.B)
      dut.clock.step()
      dut.io.occupied.expect(1.U)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 5, transactionValue = 19,
        transactionGeneration = 3, pipeIndex = 1)
      dut.io.completion.ready.expect(true.B)
      dut.io.completionRejected.bits.pipeExact.expect(false.B)
      dut.clock.step()
      dut.io.occupied.expect(1.U)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 5, transactionValue = 19,
        transactionGeneration = 3)
      dut.io.completion.bits.payload.valid.poke(false.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.occupied.expect(1.U)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 5, transactionValue = 19,
        transactionGeneration = 3)
      dut.io.occupied.expect(1.U)
      dut.io.result.valid.expect(false.B)
      dut.io.completion.ready.expect(true.B)
      dut.io.completionRejected.valid.expect(true.B)
      dut.io.completionRejected.bits.attemptExact.expect(false.B)
      dut.clock.step()
      dut.io.occupied.expect(1.U)
      dut.io.result.valid.expect(false.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.occupied.expect(1.U)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 6, transactionValue = 19,
        transactionGeneration = 3)
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.load.transaction.value.expect(19.U)
      dut.io.result.bits.load.transaction.generation.expect(3.U)
      dut.io.result.bits.load.generation.expect(6.U)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.empty.expect(true.B)
    }
  }

  test("two replays drain stale attempts in reverse order and drain future attempts without mutation") {
    simulate(new OooIexLoadTerminalMetadata(p, core)) { dut =>
      defaults(dut)
      pokeAlloc(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 5, transactionValue = 19,
        transactionGeneration = 3)
      acceptAlloc(dut)

      pokeRebind(dut, slot = 1, ridSlot = 2, currentGeneration = 5,
        nextGeneration = 6, transactionValue = 19,
        transactionGeneration = 3)
      dut.io.rebind.ready.expect(true.B)
      dut.clock.step()
      pokeRebind(dut, slot = 1, ridSlot = 2, currentGeneration = 6,
        nextGeneration = 7, transactionValue = 19,
        transactionGeneration = 3)
      dut.io.rebind.ready.expect(true.B)
      dut.clock.step()
      dut.io.rebind.valid.poke(false.B)
      dut.io.result.ready.poke(true.B)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 8, transactionValue = 19,
        transactionGeneration = 3)
      dut.io.result.valid.expect(false.B)
      dut.io.completionRejected.valid.expect(true.B)
      dut.io.completion.ready.expect(true.B,
        "a future untracked attempt must reject-and-drain")
      dut.clock.step()
      dut.io.occupied.expect(1.U)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 6, transactionValue = 19,
        transactionGeneration = 3)
      dut.io.result.valid.expect(false.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.occupied.expect(1.U)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 5, transactionValue = 19,
        transactionGeneration = 3)
      dut.io.result.valid.expect(false.B)
      dut.io.completion.ready.expect(true.B,
        "the oldest tombstone must survive a second rebind")
      dut.clock.step()
      dut.io.occupied.expect(1.U)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 2,
        attemptGeneration = 7, transactionValue = 19,
        transactionGeneration = 3)
      dut.io.result.valid.expect(true.B)
      dut.io.completion.ready.expect(true.B)
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
      pokeAlloc(dut, slot = 2, rowGeneration = 0, ridSlot = 3,
        attemptGeneration = 3, stid = 2)
      acceptAlloc(dut)
      dut.io.occupied.expect(3.U)

      val plan = dut.io.recoveryPrepare.bits
      plan.poke(0.U.asTypeOf(plan))
      plan.transactionId.poke(73.U)
      plan.phase.poke(RecoveryPhase.Prepare)
      plan.trigger.peId.poke(3.U)
      plan.trigger.stid.poke(1.U)
      plan.firstKilledValid.poke(true.B)
      plan.firstKilled.peId.poke(3.U)
      plan.firstKilled.stid.poke(1.U)
      plan.firstKilled.ridSlot.poke(3.U)
      plan.firstKilled.ridGeneration.poke(5.U)
      plan.firstKilled.memberIndex.poke(1.U)
      plan.lastKilled.poke(plan.firstKilled.peek())
      plan.killedGroupCount.poke(1.U)
      plan.killedMemberCount.poke(1.U)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.clock.step()
      dut.io.recoveryPrepared.valid.expect(false.B)
      dut.io.occupied.expect(3.U)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.io.recoveryPrepared.bits.transactionId.expect(73.U)
      dut.io.recoveryKilledMask.expect(2.U)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 3,
        attemptGeneration = 2, fault = true)
      dut.io.result.ready.poke(true.B)
      dut.io.result.valid.expect(false.B)
      dut.io.completion.ready.expect(false.B)

      // A peer completion is not fenced by the target-STID recovery.
      pokeCompletion(dut, slot = 2, rowGeneration = 0, ridSlot = 3,
        attemptGeneration = 3, stid = 2)
      dut.io.result.valid.expect(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.occupied.expect(2.U)

      // A target-STID allocation remains held, while the same free canonical
      // slot can be allocated immediately by a peer STID.
      pokeAlloc(dut, slot = 2, rowGeneration = 1, ridSlot = 4,
        attemptGeneration = 4, stid = 1)
      dut.io.alloc.ready.expect(false.B)
      pokeAlloc(dut, slot = 2, rowGeneration = 1, ridSlot = 4,
        attemptGeneration = 4, stid = 2)
      acceptAlloc(dut)
      dut.io.occupied.expect(3.U)

      val rebind = dut.io.rebind.bits
      rebind.poke(0.U.asTypeOf(rebind))
      rebind.loadId.valid.poke(true.B)
      rebind.loadId.slot.poke(1.U)
      rebind.currentLoad.valid.poke(true.B)
      pokeMember(rebind.currentLoad.producer, ridSlot = 3, stid = 1)
      rebind.currentLoad.generation.poke(2.U)
      pokeAttemptFromLoad(rebind.currentAttempt, rebind.currentLoad)
      rebind.nextLoad.valid.poke(true.B)
      pokeMember(rebind.nextLoad.producer, ridSlot = 3, stid = 1)
      rebind.nextLoad.generation.poke(3.U)
      pokeAttemptFromLoad(rebind.nextAttempt, rebind.nextLoad)
      dut.io.rebind.valid.poke(true.B)
      dut.io.rebind.ready.expect(false.B)

      rebind.loadId.slot.poke(2.U)
      rebind.loadId.generation.poke(1.U)
      pokeMember(rebind.currentLoad.producer, ridSlot = 4, stid = 2)
      rebind.currentLoad.generation.poke(4.U)
      pokeAttemptFromLoad(rebind.currentAttempt, rebind.currentLoad)
      pokeMember(rebind.nextLoad.producer, ridSlot = 4, stid = 2)
      rebind.nextLoad.generation.poke(5.U)
      pokeAttemptFromLoad(rebind.nextAttempt, rebind.nextLoad)
      dut.io.rebind.ready.expect(true.B)
      dut.clock.step()
      dut.io.rebind.valid.poke(false.B)

      dut.io.recoveryApply.bits.poke(dut.io.recoveryPrepared.bits.peek())
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.recoveryApply.bits.transactionId.poke(74.U)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApplyAccepted.expect(false.B)
      dut.io.recoveryApplyRejected.expect(true.B)
      dut.clock.step()
      dut.io.occupied.expect(3.U)

      dut.io.recoveryApply.bits.transactionId.poke(73.U)
      pokeCompletion(dut, slot = 2, rowGeneration = 1, ridSlot = 4,
        attemptGeneration = 5, stid = 2)
      dut.io.recoveryApplyAccepted.expect(true.B)
      dut.io.result.valid.expect(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
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

  test("accepts only the matching recovery abort and retains every metadata entry") {
    simulate(new OooIexLoadTerminalMetadata(p, core)) { dut =>
      defaults(dut)
      pokeAlloc(dut, slot = 1, rowGeneration = 0, ridSlot = 3,
        attemptGeneration = 9)
      acceptAlloc(dut)
      pokeAlloc(dut, slot = 2, rowGeneration = 0, ridSlot = 4,
        attemptGeneration = 10, stid = 2)
      acceptAlloc(dut)

      val prepare = dut.io.recoveryPrepare.bits
      prepare.poke(0.U.asTypeOf(prepare))
      prepare.transactionId.poke(91.U)
      prepare.phase.poke(RecoveryPhase.Prepare)
      prepare.trigger.peId.poke(3.U)
      prepare.trigger.stid.poke(1.U)
      prepare.firstKilledValid.poke(true.B)
      prepare.firstKilled.peId.poke(3.U)
      prepare.firstKilled.stid.poke(1.U)
      prepare.firstKilled.ridSlot.poke(3.U)
      prepare.firstKilled.ridGeneration.poke(5.U)
      prepare.firstKilled.memberIndex.poke(1.U)
      prepare.lastKilled.poke(prepare.firstKilled.peek())
      prepare.killedGroupCount.poke(1.U)
      prepare.killedMemberCount.poke(1.U)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.io.occupied.expect(2.U)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.expect(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(92.U)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.expect(true.B)
      dut.io.recoveryPrepare.valid.poke(false.B)

      dut.io.recoveryAbort.bits.poke(dut.io.recoveryPrepared.bits.peek())
      dut.io.recoveryAbort.bits.phase.poke(RecoveryPhase.Abort)
      dut.io.recoveryAbort.bits.transactionId.poke(92.U)
      dut.io.recoveryAbort.valid.poke(true.B)
      dut.io.recoveryAbortAccepted.expect(false.B)
      dut.io.recoveryAbortRejected.expect(true.B)
      dut.clock.step()
      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.io.occupied.expect(2.U)

      dut.io.recoveryAbort.bits.transactionId.poke(91.U)
      pokeCompletion(dut, slot = 2, rowGeneration = 0, ridSlot = 4,
        attemptGeneration = 10, stid = 2)
      dut.io.result.ready.poke(true.B)
      dut.io.recoveryAbortAccepted.expect(true.B)
      dut.io.recoveryAbortRejected.expect(false.B)
      dut.io.result.valid.expect(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryAbort.valid.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      dut.io.recoveryPrepared.valid.expect(false.B)
      dut.io.occupied.expect(1.U)

      pokeCompletion(dut, slot = 1, rowGeneration = 0, ridSlot = 3,
        attemptGeneration = 9)
      dut.io.result.ready.poke(true.B)
      dut.io.result.valid.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.empty.expect(true.B)
    }
  }
}
