package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

import linxcore.common.{CoreParams, DestinationKind, ScalarLsuParams}
import linxcore.lsu.LoadAttemptIdentity

class OooIexCanonicalLoadOwnershipSpec extends AnyFunSuite with ChiselSim {
  private final class ExpectedLease(
      val lane: Int,
      val slot: Int,
      val rowGeneration: Int,
      val ridSlot: Int,
      val lsid: BigInt,
      val returnPipe: Int,
      val attemptGeneration: Int,
      val destinationPhysTag: Int,
      val destinationOldPhysTag: Int) {
    val peId = 3
    val stid = 1
    val nativeBid = ridSlot + 4
    val brobGeneration = 9
    val ridGeneration = 5
    val memberIndex = 1
    val residentGeneration = 11
  }

  private def expectMember(
      member: RobMemberKey,
      expected: ExpectedLease): Unit = {
    member.group.valid.expect(true.B)
    member.group.peId.expect(expected.peId.U)
    member.group.stid.expect(expected.stid.U)
    member.group.ridSlot.expect(expected.ridSlot.U)
    member.group.ridGeneration.expect(expected.ridGeneration.U)
    member.bid.valid.expect(true.B)
    member.bid.value.expect(expected.nativeBid.U)
    member.brobGeneration.expect(expected.brobGeneration.U)
    member.memberIndex.expect(expected.memberIndex.U)
    member.residentGeneration.expect(expected.residentGeneration.U)
  }

  private def expectAttempt(
      attempt: LoadAttemptIdentity,
      expected: ExpectedLease): Unit = {
    attempt.valid.expect(true.B)
    attempt.producer.valid.expect(true.B)
    attempt.producer.peId.expect(expected.peId.U)
    attempt.producer.stid.expect(expected.stid.U)
    attempt.producer.nativeBidValid.expect(true.B)
    attempt.producer.nativeBid.expect(expected.nativeBid.U)
    attempt.producer.brobGeneration.expect(expected.brobGeneration.U)
    attempt.producer.ridSlot.expect(expected.ridSlot.U)
    attempt.producer.ridGeneration.expect(expected.ridGeneration.U)
    attempt.producer.memberIndex.expect(expected.memberIndex.U)
    attempt.producer.residentGeneration.expect(expected.residentGeneration.U)
    assert(attempt.generation.peek().litValue == expected.attemptGeneration,
      s"attempt generation for literal RID ${expected.ridSlot}")
  }

  private def expectLoad(
      load: OooIexLoadGeneration,
      expected: ExpectedLease): Unit = {
    load.valid.expect(true.B)
    expectMember(load.producer, expected)
    load.generation.expect(expected.attemptGeneration.U)
  }

  private def expectAlloc(
      dut: OooIexCanonicalLoadOwnership,
      expected: ExpectedLease): Unit = {
    dut.io.liqAlloc.bits.returnPipeIndex.expect(expected.returnPipe.U)
    dut.io.liqAlloc.bits.loadLsIdFullValid.expect(true.B)
    dut.io.liqAlloc.bits.loadLsIdFull.expect(expected.lsid.U)
    expectAttempt(dut.io.liqAlloc.bits.attempt, expected)
  }

  private def expectResult(
      dut: OooIexCanonicalLoadOwnership,
      expected: ExpectedLease): Unit = {
    dut.io.result.valid.expect(true.B)
    dut.io.resultLane.expect(expected.returnPipe.U)
    expectMember(dut.io.result.bits.agu.execute.i2.row.schedule.member,
      expected)
    expectLoad(dut.io.result.bits.load, expected)
    dut.io.result.bits.agu.execute.i2.row.payload.memoryOrder.firstLsid
      .expect(expected.lsid.U)
    dut.io.result.bits.agu.destination.ptag
      .expect(expected.destinationPhysTag.U)
    dut.io.result.bits.agu.execute.i2.row.payload.previousPDestinations(0).ptag
      .expect(expected.destinationOldPhysTag.U)
  }
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

  private def defaults(dut: OooIexCanonicalLoadOwnership): Unit = {
    dut.io.agu.foreach { lane =>
      lane.valid.poke(false.B)
      lane.bits.poke(0.U.asTypeOf(lane.bits))
    }
    dut.io.liqAlloc.ready.poke(false.B)
    dut.io.liqAllocLoadId.poke(0.U.asTypeOf(dut.io.liqAllocLoadId))
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.result.ready.poke(false.B)
    dut.io.rebind.valid.poke(false.B)
    dut.io.rebind.bits.poke(0.U.asTypeOf(dut.io.rebind.bits))
    dut.io.liqRebind.ready.poke(false.B)
    dut.io.attemptLaunch.valid.poke(false.B)
    dut.io.attemptLaunch.bits.poke(
      0.U.asTypeOf(dut.io.attemptLaunch.bits))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
    dut.io.flush.poke(false.B)
  }

  private def pokeMember(member: RobMemberKey, ridSlot: Int): Unit = {
    member.poke(0.U.asTypeOf(member))
    member.group.valid.poke(true.B)
    member.group.peId.poke(3.U)
    member.group.stid.poke(1.U)
    member.group.ridSlot.poke(ridSlot.U)
    member.group.ridGeneration.poke(5.U)
    member.bid.valid.poke(true.B)
    member.bid.value.poke((ridSlot + 4).U)
    member.brobGeneration.poke(9.U)
    member.memberIndex.poke(1.U)
    member.residentGeneration.poke(11.U)
  }

  private def pokeRequest(
      dut: OooIexCanonicalLoadOwnership,
      lane: Int,
      ridSlot: Int,
      lsid: BigInt): Unit = {
    val input = dut.io.agu(lane)
    input.bits.poke(0.U.asTypeOf(input.bits))
    input.valid.poke(true.B)
    input.bits.pcValid.poke(true.B)
    input.bits.pc.poke((0x4000 + ridSlot * 4).U)
    input.bits.address.poke((0x8000 + ridSlot * 8).U)
    input.bits.accessBytes.poke(8.U)
    input.bits.signExtend.poke(true.B)
    input.bits.execute.ownerClass.poke(OooUopClass.Agu)

    val row = input.bits.execute.i2.row
    row.schedule.valid.poke(true.B)
    row.schedule.peId.poke(3.U)
    row.schedule.stid.poke(1.U)
    row.schedule.epoch.poke(7.U)
    pokeMember(row.schedule.member, ridSlot)
    row.schedule.reservation.valid.poke(true.B)
    row.schedule.reservation.uopClass.poke(OooUopClass.Agu)
    row.payload.opcode.poke(101.U)
    row.payload.recipe.valid.poke(true.B)
    row.payload.recipe.opcode.poke(101.U)
    row.payload.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    row.payload.recipe.recipeKind.poke(OooOpcodeRecipeKind.ScalarLoad.U)
    row.payload.recipe.dispatchClass.poke(OooDispatchClass.Agu.U)
    row.payload.recipe.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
    row.payload.recipe.memoryRequestCount.poke(1.U)
    row.payload.memory.valid.poke(true.B)
    row.payload.memory.isLoad.poke(true.B)
    row.payload.memoryOrder.valid.poke(true.B)
    row.payload.memoryOrder.memoryValid.poke(true.B)
    row.payload.memoryOrder.isLoad.poke(true.B)
    row.payload.memoryOrder.requestCount.poke(1.U)
    row.payload.memoryOrder.firstLsid.poke(lsid.U)
    row.payload.memoryOrder.firstTypeId.poke(4.U)
    row.payload.memoryOrder.before.lsid.poke(lsid.U)
    row.payload.memoryOrder.before.loadId.poke(4.U)
    row.payload.memoryOrder.after.lsid.poke((lsid + 1).U)
    row.payload.memoryOrder.after.loadId.poke(5.U)
    row.schedule.destinations(0).valid.poke(true.B)
    row.schedule.destinations(0).kind.poke(DestinationKind.Gpr)
    row.schedule.destinations(0).atag.poke(6.U)
    row.schedule.destinations(0).ptag.poke((31 + lane).U)
    row.schedule.destinations(0).ptagGeneration.poke(4.U)
    row.payload.previousPDestinations(0).valid.poke(true.B)
    row.payload.previousPDestinations(0).ptag.poke((21 + lane).U)
    input.bits.destination.poke(row.schedule.destinations(0).peek())
  }

  private def accept(
      dut: OooIexCanonicalLoadOwnership,
      lane: Int,
      slot: Int,
      wrap: Boolean): BigInt = {
    dut.io.liqAllocLoadId.valid.poke(true.B)
    dut.io.liqAllocLoadId.value.poke(slot.U)
    dut.io.liqAllocLoadId.wrap.poke(wrap.B)
    dut.io.liqAlloc.ready.poke(true.B)
    dut.io.liqAlloc.valid.expect(true.B)
    dut.io.agu(lane).ready.expect(true.B)
    dut.io.allocAccepted.expect(true.B)
    val generation = dut.io.liqAlloc.bits.attempt.generation.peek().litValue
    dut.clock.step()
    dut.io.agu(lane).valid.poke(false.B)
    dut.io.liqAlloc.ready.poke(false.B)
    generation
  }

  private def pokeCompletion(
      dut: OooIexCanonicalLoadOwnership,
      slot: Int,
      wrap: Boolean,
      ridSlot: Int,
      attemptGeneration: BigInt,
      fault: Boolean = false,
      destinationPhysTag: Int = 33,
      destinationOldPhysTag: Int = 23): Unit = {
    val completion = dut.io.completion.bits
    completion.poke(0.U.asTypeOf(completion))
    completion.peId.poke(3.U)
    completion.stid.poke(1.U)
    completion.tid.poke(1.U)
    completion.payload.valid.poke(true.B)
    completion.payload.loadId.valid.poke(true.B)
    completion.payload.loadId.slot.poke(slot.U)
    completion.payload.loadId.generation.poke((if (wrap) 1 else 0).U)
    completion.payload.attempt.valid.poke(true.B)
    completion.payload.attempt.producer.valid.poke(true.B)
    completion.payload.attempt.producer.peId.poke(3.U)
    completion.payload.attempt.producer.stid.poke(1.U)
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
    completion.payload.dst.physTag.poke(destinationPhysTag.U)
    completion.payload.dst.oldPhysTag.poke(destinationOldPhysTag.U)
    completion.payload.data.poke(
      (if (fault) BigInt(0) else BigInt("1122334455667788", 16)).U)
    completion.payload.faultValid.poke(fault.B)
    completion.payload.faultCause.poke((if (fault) 0x55 else 0).U)
    dut.io.completion.valid.poke(true.B)
  }

  private def pokeLoadAttempt(
      load: OooIexLoadGeneration,
      attempt: linxcore.lsu.LoadAttemptIdentity,
      ridSlot: Int,
      generation: BigInt): Unit = {
    load.poke(0.U.asTypeOf(load))
    load.valid.poke(true.B)
    pokeMember(load.producer, ridSlot)
    load.generation.poke(generation.U)
    attempt.poke(0.U.asTypeOf(attempt))
    attempt.valid.poke(true.B)
    attempt.producer.valid.poke(true.B)
    attempt.producer.peId.poke(3.U)
    attempt.producer.stid.poke(1.U)
    attempt.producer.nativeBidValid.poke(true.B)
    attempt.producer.nativeBid.poke((ridSlot + 4).U)
    attempt.producer.brobGeneration.poke(9.U)
    attempt.producer.ridSlot.poke(ridSlot.U)
    attempt.producer.ridGeneration.poke(5.U)
    attempt.producer.memberIndex.poke(1.U)
    attempt.producer.residentGeneration.poke(11.U)
    attempt.generation.poke(generation.U)
  }

  private def pokeAttempt(
      attempt: linxcore.lsu.LoadAttemptIdentity,
      ridSlot: Int,
      generation: BigInt): Unit = {
    attempt.poke(0.U.asTypeOf(attempt))
    attempt.valid.poke(true.B)
    attempt.producer.valid.poke(true.B)
    attempt.producer.peId.poke(3.U)
    attempt.producer.stid.poke(1.U)
    attempt.producer.nativeBidValid.poke(true.B)
    attempt.producer.nativeBid.poke((ridSlot + 4).U)
    attempt.producer.brobGeneration.poke(9.U)
    attempt.producer.ridSlot.poke(ridSlot.U)
    attempt.producer.ridGeneration.poke(5.U)
    attempt.producer.memberIndex.poke(1.U)
    attempt.producer.residentGeneration.poke(11.U)
    attempt.generation.poke(generation.U)
  }

  private def pokeLaunch(
      dut: OooIexCanonicalLoadOwnership,
      slot: Int,
      wrap: Boolean,
      ridSlot: Int,
      generation: BigInt): Unit = {
    dut.io.attemptLaunch.bits.poke(
      0.U.asTypeOf(dut.io.attemptLaunch.bits))
    dut.io.attemptLaunch.bits.loadId.valid.poke(true.B)
    dut.io.attemptLaunch.bits.loadId.slot.poke(slot.U)
    dut.io.attemptLaunch.bits.loadId.generation
      .poke((if (wrap) 1 else 0).U)
    pokeAttempt(dut.io.attemptLaunch.bits.attempt, ridSlot, generation)
    dut.io.attemptLaunch.valid.poke(true.B)
  }

  test("allocates canonical LIQ and terminal metadata on one atomic fire") {
    simulate(new OooIexCanonicalLoadOwnership(p, core)) { dut =>
      defaults(dut)
      pokeRequest(dut, lane = 2, ridSlot = 3, lsid = BigInt("100000001", 16))
      dut.io.liqAllocLoadId.valid.poke(true.B)
      dut.io.liqAllocLoadId.value.poke(2.U)
      // Valid is allowed to advertise the transaction before the canonical
      // LIQ is ready; neither producer nor metadata may consume it early.
      dut.io.liqAlloc.valid.expect(true.B)
      dut.io.agu(2).ready.expect(false.B)
      dut.io.allocAccepted.expect(false.B)

      val generation = accept(dut, lane = 2, slot = 2, wrap = true)
      dut.io.metadataOccupied.expect(1.U)
      dut.io.liqAlloc.bits.returnPipeIndex.expect(2.U)

      // Reusing the same canonical row lease is held off by the still-live
      // terminal sidecar even when the LIQ advertises allocation readiness.
      pokeRequest(dut, lane = 0, ridSlot = 4, lsid = 9)
      dut.io.liqAlloc.ready.poke(true.B)
      dut.io.liqAlloc.valid.expect(false.B)
      dut.io.agu(0).ready.expect(false.B)
      dut.io.metadataOccupied.expect(1.U)
      assert(generation == 1)
    }
  }

  test("retains exact hit or fault completion until one terminal fire") {
    simulate(new OooIexCanonicalLoadOwnership(p, core)) { dut =>
      defaults(dut)
      pokeRequest(dut, lane = 2, ridSlot = 3, lsid = 7)
      val generation = accept(dut, lane = 2, slot = 2, wrap = true)
      pokeCompletion(dut, slot = 2, wrap = true, ridSlot = 3,
        attemptGeneration = generation)
      dut.io.result.valid.expect(true.B)
      dut.io.completion.ready.expect(false.B)
      dut.io.result.bits.data.expect(BigInt("1122334455667788", 16).U)
      dut.io.loadBypass(2).valid.expect(true.B)
      dut.io.loadBypass(2).bits.data
        .expect(BigInt("1122334455667788", 16).U)
      dut.io.loadCancel.foreach(_.valid.expect(false.B))
      dut.clock.step(2)
      dut.io.metadataOccupied.expect(1.U)

      dut.io.result.ready.poke(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.result.ready.poke(false.B)
      dut.io.metadataEmpty.expect(true.B)

      pokeRequest(dut, lane = 2, ridSlot = 3, lsid = 8)
      val faultGeneration = accept(dut, lane = 2, slot = 2, wrap = false)
      pokeCompletion(dut, slot = 2, wrap = false, ridSlot = 3,
        attemptGeneration = faultGeneration, fault = true)
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.faultValid.expect(true.B)
      dut.io.result.bits.data.expect(0.U)
      dut.io.loadBypass.foreach(_.valid.expect(false.B))
      // Fault cancellation is a one-shot policy event.  It must not depend on
      // the terminal sink accepting W2, otherwise terminal ready can close a
      // combinational loop through the ALU cancel consumers.
      dut.io.loadCancel(2).valid.expect(true.B)
      dut.io.loadCancel(2).bits.load.generation.expect(faultGeneration.U)
      dut.clock.step()
      dut.io.result.valid.expect(true.B)
      dut.io.loadCancel.foreach(_.valid.expect(false.B))

      dut.io.result.ready.poke(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.io.loadCancel.foreach(_.valid.expect(false.B))
      dut.clock.step()
      dut.io.metadataEmpty.expect(true.B)
    }
  }

  test("qualifies speculative wakeup by exact LIQ launch attempt") {
    simulate(new OooIexCanonicalLoadOwnership(p, core)) { dut =>
      defaults(dut)
      pokeRequest(dut, lane = 2, ridSlot = 3, lsid = 7)
      val generation = accept(dut, lane = 2, slot = 2, wrap = true)

      pokeLaunch(dut, slot = 2, wrap = true, ridSlot = 3,
        generation = generation)
      dut.io.attemptLaunchAccepted.expect(true.B)
      dut.io.attemptLaunchRejected.valid.expect(false.B)
      dut.io.speculativeWakeup(0).valid.expect(false.B)
      dut.io.speculativeWakeup(1).valid.expect(false.B)
      dut.io.speculativeWakeup(2).valid.expect(true.B)
      dut.io.speculativeWakeup(2).bits.ptag.expect(33.U)
      dut.io.speculativeWakeup(2).bits.load.generation.expect(generation.U)
      dut.clock.step()

      pokeLaunch(dut, slot = 2, wrap = true, ridSlot = 3,
        generation = generation + 1)
      dut.io.attemptLaunchAccepted.expect(false.B)
      dut.io.attemptLaunchRejected.valid.expect(true.B)
      dut.io.attemptLaunchRejected.bits.attemptExact.expect(false.B)
      dut.io.speculativeWakeup.foreach(_.valid.expect(false.B))
    }
  }

  test("rebinds canonical LIQ and OOO attempt metadata on one fire") {
    simulate(new OooIexCanonicalLoadOwnership(p, core)) { dut =>
      defaults(dut)
      pokeRequest(dut, lane = 2, ridSlot = 3, lsid = 7)
      val current = accept(dut, lane = 2, slot = 2, wrap = true)

      val rebind = dut.io.rebind.bits
      rebind.loadId.valid.poke(true.B)
      rebind.loadId.slot.poke(2.U)
      rebind.loadId.generation.poke(1.U)
      pokeLoadAttempt(rebind.currentLoad, rebind.currentAttempt,
        ridSlot = 3, generation = current)
      pokeLoadAttempt(rebind.nextLoad, rebind.nextAttempt,
        ridSlot = 3, generation = current + 1)
      dut.io.rebind.valid.poke(true.B)

      dut.io.liqRebind.valid.expect(true.B)
      dut.io.rebind.ready.expect(false.B)
      dut.io.rebindAccepted.expect(false.B)
      dut.io.liqRebind.bits.loadId.value.expect(2.U)
      dut.io.liqRebind.bits.loadId.wrap.expect(true.B)
      dut.clock.step(2)
      dut.io.metadataOccupied.expect(1.U)

      dut.io.liqRebind.ready.poke(true.B)
      dut.io.rebind.ready.expect(true.B)
      dut.io.rebindAccepted.expect(true.B)
      dut.io.loadCancel(2).valid.expect(true.B)
      dut.io.loadCancel(2).bits.load.generation.expect(current.U)
      dut.clock.step()
      dut.io.rebind.valid.poke(false.B)
      dut.io.liqRebind.ready.poke(false.B)

      pokeCompletion(dut, slot = 2, wrap = true, ridSlot = 3,
        attemptGeneration = current)
      dut.io.result.ready.poke(true.B)
      dut.io.result.valid.expect(false.B)
      dut.io.completionRejected.valid.expect(true.B)
      pokeCompletion(dut, slot = 2, wrap = true, ridSlot = 3,
        attemptGeneration = current + 1)
      dut.io.result.valid.expect(true.B)

      dut.io.completion.valid.poke(false.B)
      dut.io.result.ready.poke(false.B)
      pokeLaunch(dut, slot = 2, wrap = true, ridSlot = 3,
        generation = current + 1)
      dut.io.attemptLaunchAccepted.expect(true.B)
      dut.io.speculativeWakeup(2).valid.expect(true.B)
      dut.io.speculativeWakeup(2).bits.load.generation
        .expect((current + 1).U)
    }
  }

  test("fences allocation and terminal publication during common recovery") {
    simulate(new OooIexCanonicalLoadOwnership(p, core)) { dut =>
      defaults(dut)
      pokeRequest(dut, lane = 2, ridSlot = 3, lsid = 7)
      val generation = accept(dut, lane = 2, slot = 2, wrap = true)
      pokeCompletion(dut, slot = 2, wrap = true, ridSlot = 3,
        attemptGeneration = generation)
      dut.io.result.ready.poke(true.B)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.oldHead.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.oldHead.peId.poke(3.U)
      dut.io.recoveryPrepare.bits.oldHead.stid.poke(1.U)
      dut.io.recoveryPrepare.bits.oldHead.ridSlot.poke(3.U)
      dut.io.recoveryPrepare.bits.oldHead.ridGeneration.poke(5.U)
      dut.io.recoveryPrepare.bits.oldOccupied.poke(1.U)
      dut.io.recoveryPrepare.bits.newOccupied.poke(0.U)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.result.valid.expect(false.B)
      dut.io.completion.ready.expect(false.B)
      dut.io.recoveryKilledMask.expect(4.U)

      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      dut.io.metadataEmpty.expect(true.B)
    }
  }

  test("serializes fault and replay cancels that target one physical lane") {
    simulate(new OooIexCanonicalLoadOwnership(p, core)) { dut =>
      defaults(dut)
      pokeRequest(dut, lane = 2, ridSlot = 2, lsid = 6)
      val faultGeneration = accept(dut, lane = 2, slot = 1, wrap = false)
      pokeRequest(dut, lane = 2, ridSlot = 3, lsid = 7)
      val replayGeneration = accept(dut, lane = 2, slot = 2, wrap = false)

      pokeCompletion(dut, slot = 1, wrap = false, ridSlot = 2,
        attemptGeneration = faultGeneration, fault = true)
      dut.io.result.ready.poke(true.B)

      val rebind = dut.io.rebind.bits
      rebind.loadId.valid.poke(true.B)
      rebind.loadId.slot.poke(2.U)
      rebind.loadId.generation.poke(0.U)
      pokeLoadAttempt(rebind.currentLoad, rebind.currentAttempt,
        ridSlot = 3, generation = replayGeneration)
      pokeLoadAttempt(rebind.nextLoad, rebind.nextAttempt,
        ridSlot = 3, generation = replayGeneration + 1)
      dut.io.rebind.valid.poke(true.B)
      dut.io.liqRebind.ready.poke(true.B)

      dut.io.rebind.ready.expect(false.B)
      dut.io.liqRebind.valid.expect(false.B)
      dut.io.loadCancel(2).valid.expect(true.B)
      dut.io.loadCancel(2).bits.load.generation.expect(faultGeneration.U)
      dut.clock.step()

      dut.io.completion.valid.poke(false.B)
      dut.io.result.ready.poke(false.B)
      dut.io.rebind.ready.expect(true.B)
      dut.io.loadCancel(2).valid.expect(true.B)
      dut.io.loadCancel(2).bits.load.generation.expect(replayGeneration.U)
    }
  }

  test("keeps two canonical AGU leases disjoint through retry rebind return and recovery") {
    val twoPipeCore = core.copy(
      scalarLsu = core.scalarLsu.copy(loadReturnPipeCount = 2))
    simulate(new OooIexCanonicalLoadOwnership(
      p, twoPipeCore, laneCount = 2)) { dut =>
      defaults(dut)

      pokeRequest(dut, lane = 0, ridSlot = 2,
        lsid = BigInt("100000002", 16))
      pokeRequest(dut, lane = 1, ridSlot = 3,
        lsid = BigInt("200000003", 16))
      val lane1 = new ExpectedLease(lane = 1, slot = 0, rowGeneration = 0,
        ridSlot = 3, lsid = BigInt("200000003", 16), returnPipe = 1,
        attemptGeneration = 1, destinationPhysTag = 32,
        destinationOldPhysTag = 22)
      val lane0Initial = new ExpectedLease(lane = 0, slot = 1,
        rowGeneration = 0, ridSlot = 2, lsid = BigInt("100000002", 16),
        returnPipe = 0, attemptGeneration = 2, destinationPhysTag = 31,
        destinationOldPhysTag = 21)
      val lane0Rebound = new ExpectedLease(lane = 0, slot = 1,
        rowGeneration = 0, ridSlot = 2, lsid = BigInt("100000002", 16),
        returnPipe = 0, attemptGeneration = 3, destinationPhysTag = 31,
        destinationOldPhysTag = 21)
      dut.io.liqAllocLoadId.valid.poke(true.B)
      dut.io.liqAllocLoadId.value.poke(0.U)
      dut.io.liqAlloc.ready.poke(true.B)
      dut.io.agu(0).ready.expect(false.B)
      dut.io.agu(1).ready.expect(true.B)
      expectAlloc(dut, lane1)
      dut.clock.step()
      dut.io.agu(1).valid.poke(false.B)

      dut.io.liqAllocLoadId.value.poke(1.U)
      dut.io.agu(0).ready.expect(true.B)
      expectAlloc(dut, lane0Initial)
      dut.clock.step()
      dut.io.agu(0).valid.poke(false.B)
      dut.io.liqAlloc.ready.poke(false.B)
      dut.io.metadataOccupied.expect(2.U)

      val rebind = dut.io.rebind.bits
      rebind.loadId.valid.poke(true.B)
      rebind.loadId.slot.poke(1.U)
      rebind.loadId.generation.poke(0.U)
      pokeLoadAttempt(rebind.currentLoad, rebind.currentAttempt,
        ridSlot = 2, generation = lane0Initial.attemptGeneration)
      pokeLoadAttempt(rebind.nextLoad, rebind.nextAttempt,
        ridSlot = 2, generation = lane0Rebound.attemptGeneration)
      dut.io.rebind.valid.poke(true.B)
      dut.io.liqRebind.ready.poke(false.B)
      for (_ <- 0 until 2) {
        dut.io.rebind.ready.expect(false.B)
        dut.io.rebindAccepted.expect(false.B)
        dut.io.loadCancel.foreach(_.valid.expect(false.B))
        dut.io.metadataOccupied.expect(2.U)
        dut.io.liqRebind.valid.expect(true.B)
        dut.io.liqRebind.bits.loadId.valid.expect(true.B)
        dut.io.liqRebind.bits.loadId.value.expect(lane0Initial.slot.U)
        dut.io.liqRebind.bits.loadId.wrap.expect(false.B)
        expectAttempt(dut.io.liqRebind.bits.current, lane0Initial)
        expectAttempt(dut.io.liqRebind.bits.next, lane0Rebound)
        dut.clock.step()
      }
      dut.io.liqRebind.ready.poke(true.B)
      dut.io.rebind.ready.expect(true.B)
      dut.io.rebindAccepted.expect(true.B)
      dut.io.loadCancel(0).valid.expect(true.B)
      dut.io.loadCancel(0).bits.stid.expect(lane0Initial.stid.U)
      expectLoad(dut.io.loadCancel(0).bits.load, lane0Initial)
      dut.io.loadCancel(1).valid.expect(false.B)
      dut.clock.step()
      dut.io.rebind.valid.poke(false.B)
      dut.io.liqRebind.ready.poke(false.B)

      pokeCompletion(dut, slot = 1, wrap = false, ridSlot = 2,
        attemptGeneration = lane0Initial.attemptGeneration,
        destinationPhysTag = 31,
        destinationOldPhysTag = 21)
      dut.io.completionRejected.valid.expect(true.B)
      expectMember(dut.io.completionRejected.bits.member, lane0Rebound)
      dut.io.result.valid.expect(false.B)
      dut.io.completion.valid.poke(false.B)

      pokeCompletion(dut, slot = 0, wrap = false, ridSlot = 3,
        attemptGeneration = lane1.attemptGeneration, destinationPhysTag = 32,
        destinationOldPhysTag = 22)
      expectResult(dut, lane1)
      dut.io.result.ready.poke(false.B)
      for (_ <- 0 until 3) {
        expectResult(dut, lane1)
        dut.io.completion.ready.expect(false.B)
        dut.clock.step()
      }
      dut.io.result.ready.poke(true.B)
      expectResult(dut, lane1)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.io.result.ready.poke(false.B)
      dut.io.metadataOccupied.expect(1.U)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.oldHead.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.oldHead.peId.poke(3.U)
      dut.io.recoveryPrepare.bits.oldHead.stid.poke(1.U)
      dut.io.recoveryPrepare.bits.oldHead.ridSlot.poke(2.U)
      dut.io.recoveryPrepare.bits.oldHead.ridGeneration.poke(5.U)
      dut.io.recoveryPrepare.bits.oldOccupied.poke(1.U)
      dut.io.recoveryPrepare.bits.newOccupied.poke(0.U)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryKilledMask.expect(2.U)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.metadataEmpty.expect(true.B)
    }
  }

  test("emits a production ownership graph without a duplicate load tracker") {
    val systemVerilog = ChiselStage.emitSystemVerilog(
      new OooIexCanonicalLoadOwnership(p, core))

    assert(systemVerilog.contains("module OooIexCanonicalLoadOwnership"))
    assert(systemVerilog.contains("OooIexLoadLiqAllocAdapter adapter"))
    assert(systemVerilog.contains("OooIexLoadTerminalMetadata metadata"))
    assert(!systemVerilog.contains("OooIexLoadUnit"))
  }
}
